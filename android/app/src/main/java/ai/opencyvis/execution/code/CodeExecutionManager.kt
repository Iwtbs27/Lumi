package ai.opencyvis.execution.code

import android.content.Context
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject

typealias CodeHostCall = suspend (String, JSONObject) -> JSONObject

object CodeExecutionStatus {
    const val COMPLETED = "completed"
    const val FAILED = "failed"
    const val CANCELLED = "cancelled"
    const val TIMED_OUT = "timed_out"
}

data class CodeExecutionResult(
    val executionId: String,
    val status: String,
    val result: Any?,
    val resultRef: String,
    val stdoutRef: String,
    val stderrRef: String,
    val error: String? = null,
    val workerPid: Int? = null
)

data class CodeLogPage(val reference: String, val text: String, val offset: Int, val nextOffset: Int?)

internal data class CodeWorkerRequest(
    val executionId: String, val codeFile: File, val workspace: File, val params: JSONObject,
    val resultFile: File, val stdoutFile: File, val stderrFile: File
)

internal data class CodeWorkerCompletion(val status: String, val error: String? = null)

internal interface CodeWorkerConnection {
    val workerPid: Int?
    suspend fun run(request: CodeWorkerRequest, hostCall: CodeHostCall): CodeWorkerCompletion
    suspend fun closeAndAwaitExit()
}

/** One process-wide slot; it is returned only after the worker and its host operations have exited. */
class CodeExecutionManager internal constructor(private val createWorker: () -> CodeWorkerConnection) {
    constructor(context: Context) : this({ AndroidCodeWorkerConnection(context.applicationContext) })

    suspend fun execute(executionId: String, codeFile: File, workspace: File, params: JSONObject,
        timeoutMs: Long, hostCall: CodeHostCall): CodeExecutionResult = coroutineScope {
        require(executionId.matches(Regex("[A-Za-z0-9][A-Za-z0-9._-]*"))) { "Invalid execution ID" }
        require(codeFile.isFile) { "Code file does not exist: ${codeFile.name}" }
        require(workspace.isDirectory && timeoutMs > 0) { "Execution needs a workspace and a positive timeout" }
        val directory = File(workspace, ".executions/$executionId")
        check(directory.mkdirs()) { "Execution output directory already exists or cannot be created" }
        val request = CodeWorkerRequest(executionId, codeFile.absoluteFile, workspace.absoluteFile,
            JSONObject(params.toString()), File(directory, "result.json"), File(directory, "stdout.txt"), File(directory, "stderr.txt"))
        request.stdoutFile.writeText("")
        request.stderrFile.writeText("")
        val active = Active()
        val job = async(start = CoroutineStart.LAZY) {
            slot.withLock { runWorker(request, timeoutMs, hostCall, active) }
        }
        active.job = job
        if (executions.putIfAbsent(executionId, active) != null) {
            job.cancel()
            error("Execution ID is already active")
        }
        job.start()
        try {
            job.await()
        } catch (cancelled: CancellationException) {
            if (currentCoroutineContext().isActive && active.cancelled.get()) {
                result(request, CodeWorkerCompletion(CodeExecutionStatus.CANCELLED, "Execution cancelled"), active.worker?.workerPid)
            } else throw cancelled
        } finally {
            withContext(NonCancellable) {
                job.cancelAndJoin()
                executions.remove(executionId, active)
                active.finished.complete(Unit)
            }
        }
    }

    suspend fun cancel(executionId: String): Boolean {
        val active = executions[executionId] ?: return false
        active.cancelled.set(true)
        active.job.cancel(CancellationException("Code execution cancelled"))
        active.finished.await()
        return true
    }

    /** Offsets count decoded UTF-16 characters, matching Kotlin strings; no complete log is loaded. */
    fun readLog(reference: String, offset: Int = 0, limit: Int = 4800): CodeLogPage {
        require(offset >= 0 && limit in 1..48_000)
        val file = File(reference)
        require(file.isFile) { "Execution output does not exist" }
        return file.bufferedReader().use { reader ->
            var remaining = offset
            while (remaining > 0) {
                val skipped = reader.skip(remaining.toLong()).toInt()
                require(skipped > 0) { "Offset is beyond the end of the execution output" }
                remaining -= skipped
            }
            val buffer = CharArray(limit)
            var count = 0
            while (count < limit) {
                val read = reader.read(buffer, count, limit - count)
                if (read < 0) break
                count += read
            }
            CodeLogPage(reference, String(buffer, 0, count), offset,
                if (reader.read() >= 0) offset + count else null)
        }
    }

    private suspend fun runWorker(request: CodeWorkerRequest, timeoutMs: Long, hostCall: CodeHostCall,
        active: Active): CodeExecutionResult {
        val completion = try {
            val worker = createWorker().also { active.worker = it }
            withTimeoutOrNull(timeoutMs) { worker.run(request, hostCall) }
                ?: CodeWorkerCompletion(CodeExecutionStatus.TIMED_OUT, "Execution exceeded ${timeoutMs}ms")
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            request.stderrFile.appendText(failure.stackTraceToString())
            CodeWorkerCompletion(CodeExecutionStatus.FAILED, failure.message ?: failure.javaClass.simpleName)
        } finally {
            withContext(NonCancellable) { active.worker?.closeAndAwaitExit() }
        }
        return result(request, completion, active.worker?.workerPid)
    }

    private fun result(request: CodeWorkerRequest, completion: CodeWorkerCompletion, pid: Int?): CodeExecutionResult {
        var inline: Any? = null
        var status = completion.status
        var error = completion.error
        if (status == CodeExecutionStatus.COMPLETED) {
            try {
                check(request.resultFile.isFile) { "Worker completed without a result file" }
                if (request.resultFile.length() <= MAX_INLINE_RESULT_BYTES) {
                    inline = JSONObject(request.resultFile.readText()).get("result").takeUnless { it == JSONObject.NULL }
                }
            } catch (failure: Exception) {
                status = CodeExecutionStatus.FAILED
                error = failure.message
                request.stderrFile.appendText(failure.stackTraceToString())
            }
        }
        if (status != CodeExecutionStatus.COMPLETED) {
            request.resultFile.writeText(JSONObject().put("result", JSONObject.NULL)
                .put("status", status).put("error", error ?: JSONObject.NULL).toString())
        }
        return CodeExecutionResult(request.executionId, status, inline, request.resultFile.absolutePath,
            request.stdoutFile.absolutePath, request.stderrFile.absolutePath, error, pid)
    }

    private class Active {
        lateinit var job: Deferred<CodeExecutionResult>
        val cancelled = AtomicBoolean()
        val finished = CompletableDeferred<Unit>()
        @Volatile var worker: CodeWorkerConnection? = null
    }

    companion object {
        private const val MAX_INLINE_RESULT_BYTES = 64 * 1024
        private val slot = Mutex()
        private val executions = ConcurrentHashMap<String, Active>()
    }
}
