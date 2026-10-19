package ai.opencyvis.execution.code

import android.app.ActivityManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import android.os.Process
import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject

/** A bound service owns one interpreter lifetime. The host and worker have the same app UID. */
internal class AndroidCodeWorkerConnection(private val context: Context) : CodeWorkerConnection {
    private val connected = CompletableDeferred<ICodeWorker>()
    private val dead = CompletableDeferred<Unit>()
    private val completed = CompletableDeferred<CodeWorkerCompletion>()
    private val acceptingHostCalls = AtomicBoolean(true)
    private val hostJob = SupervisorJob()
    private val hostScope = CoroutineScope(hostJob + Dispatchers.IO)
    @Volatile private var bound = false
    @Volatile private var remote: ICodeWorker? = null
    @Volatile override var workerPid: Int? = null
        private set

    private val deathRecipient = IBinder.DeathRecipient { workerDied() }
    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, service: IBinder) {
            try {
                val worker = ICodeWorker.Stub.asInterface(service)
                val pid = worker.processId()
                check(pid != Process.myPid()) { "CodeWorkerService must run in :code_worker" }
                workerPid = pid
                remote = worker
                service.linkToDeath(deathRecipient, 0)
                connected.complete(worker)
            } catch (failure: Exception) {
                connected.completeExceptionally(failure)
            }
        }
        override fun onServiceDisconnected(name: ComponentName) = workerDied()
        override fun onBindingDied(name: ComponentName) = workerDied()
        override fun onNullBinding(name: ComponentName) {
            connected.completeExceptionally(IllegalStateException("Code worker returned no Binder"))
        }
    }

    override suspend fun run(request: CodeWorkerRequest, hostCall: CodeHostCall): CodeWorkerCompletion {
        // Establish the short service handshake before accepting cancellation, so cleanup knows
        // which process it must terminate. Python starts only after this handshake.
        val worker = withContext(NonCancellable) {
            check(context.bindService(Intent(context, CodeWorkerService::class.java), connection, Context.BIND_AUTO_CREATE)) {
                "Cannot bind code worker"
            }
            bound = true
            withTimeoutOrNull(10_000) { connected.await() }
                ?: throw IllegalStateException("Code worker connection timed out")
        }
        currentCoroutineContext().ensureActive()
        val host = object : ICodeHostBridge.Stub() {
            override fun call(executionId: String, capability: String, argumentsJson: String): String = try {
                check(executionId == request.executionId && acceptingHostCalls.get()) { "Execution is no longer active" }
                val value = runBlocking {
                    hostScope.async {
                        currentCoroutineContext().ensureActive()
                        hostCall(capability, JSONObject(argumentsJson))
                    }.await()
                }
                JSONObject().put("ok", true).put("value", value).toString()
            } catch (failure: Exception) {
                JSONObject().put("ok", false).put("error", failure.message ?: failure.javaClass.simpleName).toString()
            }
        }
        val callback = object : ICodeResultCallback.Stub() {
            override fun completed(executionId: String, status: String, error: String?) {
                if (executionId == request.executionId) this@AndroidCodeWorkerConnection.completed.complete(CodeWorkerCompletion(status, error))
            }
        }
        worker.execute(request.executionId, request.codeFile.absolutePath, request.workspace.absolutePath,
            request.params.toString(), request.resultFile.absolutePath, request.stdoutFile.absolutePath,
            request.stderrFile.absolutePath, host, callback)
        return completed.await()
    }

    override suspend fun closeAndAwaitExit() {
        acceptingHostCalls.set(false)
        hostJob.cancel(CancellationException("Code execution ended"))
        // Unbind before killing: BIND_AUTO_CREATE must not restart the worker we just ended.
        if (bound) {
            context.unbindService(connection)
            bound = false
        }
        val pid = workerPid ?: (context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager)
            .runningAppProcesses?.firstOrNull { it.processName == "${context.applicationInfo.processName}:code_worker" }?.pid
        if (pid != null) {
            check(pid != Process.myPid()) { "Refusing to terminate the host process" }
            Process.killProcess(pid)
            if (remote?.asBinder()?.isBinderAlive == true) dead.await()
            // Binder death is the IPC boundary; also wait for the process to leave the process table.
            while (isAlive(pid)) delay(10)
        }
        hostJob.cancelAndJoin()
    }

    private fun workerDied() {
        dead.complete(Unit)
        val failure = IllegalStateException("Code worker process exited before returning its result")
        connected.completeExceptionally(failure)
        completed.completeExceptionally(failure)
    }

    private fun isAlive(pid: Int): Boolean = try { Os.kill(pid, 0); true }
    catch (failure: ErrnoException) { if (failure.errno == OsConstants.ESRCH) false else throw failure }
}
