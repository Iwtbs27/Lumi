package ai.opencyvis.learning

import android.content.Context
import android.graphics.Point
import android.util.Log
import android.view.KeyEvent
import android.view.MotionEvent
import ai.opencyvis.accessibility.VdAccessibilityService
import ai.opencyvis.display.VirtualDisplayManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONObject

/** Captures only human input forwarded to the controlled display. Persistence lives in Recorder. */
class LearningSessionController(context: Context) {
    private val recorder = LearningRecorder.get(context)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val captureMutex = Mutex()
    private val inputMutex = Mutex()
    private var display: VirtualDisplayManager? = null
    private var captureJob: Job? = null
    private var editJob: Job? = null
    private var gesture: JSONObject? = null
    private var edit: JSONObject? = null
    @Volatile var sessionId: String? = null
        private set
    @Volatile var paused = false
        private set
    @Volatile var captureError: String? = null
        private set
    val isRecording: Boolean get() = sessionId != null && !paused

    suspend fun input(block: suspend () -> Unit) = inputMutex.withLock {
        withContext(NonCancellable) { block() }
    }

    suspend fun start(mode: String, goal: String, vdm: VirtualDisplayManager, source: JSONObject, appScope: String? = null): String {
        check(sessionId == null) { "已有教学正在进行" }
        val session = recorder.start(mode, goal, vdm.displayId,
            source.optString("run_id").takeIf { it.isNotBlank() && it != "null" }, source, appScope)
        sessionId = session.id
        display = vdm
        paused = false
        captureError = null
        capture()
        return session.id
    }

    @Synchronized fun touch(event: MotionEvent, accepted: Boolean, preview: Point) {
        val vdm = display ?: return
        if (!isRecording || preview.x <= 0 || preview.y <= 0) return
        val pointers = JSONArray()
        repeat(event.pointerCount) { index ->
            pointers.put(JSONObject().put("id", event.getPointerId(index))
                .put("x", event.getX(index).toDouble() * vdm.width / preview.x)
                .put("y", event.getY(index).toDouble() * vdm.height / preview.y))
        }
        val point = JSONObject().put("x", event.x.toDouble() * vdm.width / preview.x)
            .put("y", event.y.toDouble() * vdm.height / preview.y).put("event_time", event.eventTime)
            .put("pointers", pointers).put("action", event.actionMasked)
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> gesture = JSONObject().put("started_at", System.currentTimeMillis())
                .put("points", JSONArray().put(point)).put("injected", accepted).put("pointer_count", event.pointerCount)
                .put("package_before", currentPackage())
            MotionEvent.ACTION_MOVE, MotionEvent.ACTION_POINTER_DOWN, MotionEvent.ACTION_POINTER_UP -> gesture?.let {
                val points = it.getJSONArray("points")
                if (points.length() < 48) points.put(point)
                it.put("injected", it.getBoolean("injected") && accepted)
                it.put("pointer_count", maxOf(it.getInt("pointer_count"), event.pointerCount))
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> gesture?.let {
                it.getJSONArray("points").put(point)
                it.put("ended_at", System.currentTimeMillis()).put("injected", it.getBoolean("injected") && accepted)
                    .put("cancelled", event.actionMasked == MotionEvent.ACTION_CANCEL)
                    .put("display_width", vdm.width).put("display_height", vdm.height)
                    .put("package_after", currentPackage())
                flushEdit()
                recorder.event(vdm.displayId, "gesture", it, it.optString("package_before").takeIf { name -> name.isNotBlank() && name != "null" })
                gesture = null
                requestCapture()
            }
        }
    }

    @Synchronized fun key(event: KeyEvent, accepted: Boolean) {
        val vdm = display ?: return
        if (!isRecording || event.action != KeyEvent.ACTION_UP) return
        flushEdit()
        recorder.event(vdm.displayId, "key", JSONObject().put("key_code", event.keyCode)
            .put("injected", accepted).put("event_time", event.eventTime), currentPackage())
        requestCapture()
    }

    @Synchronized fun text(inserted: String, deleted: Int, accepted: Boolean) {
        val vdm = display ?: return
        if (!isRecording) return
        if (VdAccessibilityService.observationWindow(vdm.displayId)?.passwordFocused == true) {
            recorder.event(vdm.displayId, "password_input_omitted", JSONObject().put("injected", accepted), currentPackage())
            return
        }
        val batch = edit ?: JSONObject().put("inserted", "").put("deleted", 0).put("injected", true).put("package", currentPackage()).also { edit = it }
        batch.put("inserted", batch.getString("inserted") + inserted)
            .put("deleted", batch.getInt("deleted") + deleted)
            .put("injected", batch.getBoolean("injected") && accepted)
        editJob?.cancel()
        editJob = scope.launch { delay(650); flushEdit(); requestCapture() }
    }

    @Synchronized private fun flushEdit() {
        val batch = edit ?: return
        edit = null
        display?.let { recorder.event(it.displayId, "text_edit", batch, batch.optString("package").takeIf { name -> name.isNotBlank() }) }
    }

    private fun currentPackage(): String? = display?.let { VdAccessibilityService.getTopPackageOnDisplay(it.displayId) }

    private fun requestCapture() {
        captureJob?.cancel()
        captureJob = scope.launch { delay(450); capture(); delay(1000); capture() }
    }

    private suspend fun capture() {
        try { captureFrame() }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: Exception) {
            captureError = "截图保存失败：${failure.message}"
            paused = true
            Log.e("LearningCapture", captureError, failure)
            display?.let { recorder.event(it.displayId, "capture_failed", JSONObject().put("message", failure.message)) }
            runCatching { recorder.pause(true) }
        }
    }

    private suspend fun captureFrame() = captureMutex.withLock {
        val vdm = display ?: return@withLock
        if (!isRecording) return@withLock
        if (VdAccessibilityService.observationWindow(vdm.displayId)?.passwordFocused == true) return@withLock
        val frame = vdm.captureFrame() ?: run {
            recorder.event(vdm.displayId, "capture_unavailable", JSONObject())
            return@withLock
        }
        try {
            val tree = VdAccessibilityService.captureViewTree(frame.displayId, frame.displayWidth, frame.displayHeight)
            withContext(NonCancellable) {
                recorder.frame(frame.bitmap, frame.displayId, tree, currentPackage(), JSONObject()
                    .put("capture_started_at", frame.startedAt).put("captured_at", frame.capturedAt)
                    .put("display_width", frame.displayWidth).put("display_height", frame.displayHeight)
                    .put("capture_source", frame.source))
            }
        } finally {
            frame.bitmap.recycle()
        }
    }

    suspend fun pause(value: Boolean) {
        flushEdit()
        captureJob?.cancel()
        paused = value
        if (!value) captureError = null
        recorder.pause(value)
        if (!value) capture()
    }

    suspend fun prepareAppChange() {
        editJob?.cancelAndJoin()
        flushEdit()
        capture()
    }

    suspend fun openedApp(appName: String, accepted: Boolean) {
        val vdm = display ?: return
        recorder.event(vdm.displayId, "open_app", JSONObject().put("app", appName).put("injected", accepted), currentPackage())
        delay(450)
        capture()
    }

    suspend fun finish(note: String, discard: Boolean): Pair<String, String>? = inputMutex.withLock {
        val id = sessionId ?: return@withLock null
        captureJob?.cancelAndJoin()
        editJob?.cancelAndJoin()
        flushEdit()
        if (!discard) capture()
        paused = true
        try {
            recorder.finish(note, discard)
            id to recorder.summary()
        } finally {
            sessionId = null
            display = null
            gesture = null
            paused = false
            captureError = null
        }
    }

    fun destroy() {
        scope.launch {
            try { finish("服务已停止；保留已录制片段，完成状态尚未确认", false) }
            catch (failure: Exception) { Log.e("LearningCapture", "Unable to finish stopped recording", failure) }
            finally { scope.cancel() }
        }
    }
}
