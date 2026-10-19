package ai.opencyvis.execution.code

import android.app.Service
import android.content.Intent
import android.os.IBinder
import android.os.Process
import android.util.Log
import com.chaquo.python.Python
import com.chaquo.python.android.AndroidPlatform
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import org.json.JSONObject

/** A fresh :code_worker process executes one program; the host ends it before releasing its slot. */
class CodeWorkerService : Service() {
    private val started = AtomicBoolean()
    private val executor = Executors.newSingleThreadExecutor()
    private val binder = object : ICodeWorker.Stub() {
        override fun processId(): Int = Process.myPid()

        override fun execute(executionId: String, codeFile: String, workspace: String, paramsJson: String,
            resultFile: String, stdoutFile: String, stderrFile: String, host: ICodeHostBridge, callback: ICodeResultCallback) {
            check(started.compareAndSet(false, true)) { "This worker already owns an execution" }
            executor.execute {
                val outcome = try {
                    if (!Python.isStarted()) Python.start(AndroidPlatform(this@CodeWorkerService))
                    val metadata = Python.getInstance().getModule("opencyvis_code").callAttr("execute",
                        codeFile, workspace, paramsJson, resultFile, stdoutFile, stderrFile,
                        PythonHostBridge(executionId, host)).toString()
                    JSONObject(metadata)
                } catch (failure: Throwable) {
                    File(stderrFile).appendText(Log.getStackTraceString(failure))
                    JSONObject().put("status", CodeExecutionStatus.FAILED)
                        .put("error", failure.message ?: failure.javaClass.simpleName)
                }
                try {
                    callback.completed(executionId, outcome.getString("status"),
                        outcome.opt("error")?.takeUnless { it == JSONObject.NULL }?.toString()?.take(4000))
                } catch (_: android.os.RemoteException) {
                    // The host already ended this execution; no additional operation is issued.
                }
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder = binder
    override fun onDestroy() {
        executor.shutdown()
        super.onDestroy()
    }
}

/** Public methods are called directly by Chaquopy's Java bridge. */
class PythonHostBridge(private val executionId: String, private val host: ICodeHostBridge) {
    fun call(capability: String, argumentsJson: String): String = host.call(executionId, capability, argumentsJson)
}
