package ai.opencyvis.learning

import android.content.Context
import android.graphics.Bitmap
import android.os.SystemClock
import ai.opencyvis.memory.MemoryEvidence
import ai.opencyvis.memory.MemoryRepository
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** One ordered writer for human events and frames; no model calls during recording. */
class LearningRecorder private constructor(context: Context) {
    private val appContext = context.applicationContext
    private val repository = LearningRepository(appContext)
    private val memory = MemoryRepository(appContext)
    private val files = LearningFiles(appContext)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val writes = Channel<suspend () -> Unit>(Channel.UNLIMITED)
    private val current = MutableStateFlow<LearningSession?>(null)
    val active: StateFlow<LearningSession?> = current
    private var sequence = 0
    private var lastFrame: String? = null
    private val recent = ArrayDeque<String>()

    init {
        scope.launch {
            for (write in writes) {
                try { write() }
                catch (failure: Exception) {
                    current.value?.let { session ->
                        val failed = session.copy(status = "failed", error = failure.message,
                            updatedAt = System.currentTimeMillis())
                        current.value = failed
                        repository.put(failed)
                    }
                }
            }
        }
    }

    suspend fun start(mode: String, goal: String, displayId: Int, parentRunId: String? = null,
        source: JSONObject = JSONObject(), appScope: String? = null,
        allowedPackages: List<String> = emptyList()): LearningSession = ordered {
        check(current.value == null) { "请先结束当前学习记录" }
        require(mode in setOf("demonstration", "correction", "observation")) { "未知学习入口" }
        require(mode == "observation" || goal.isNotBlank()) { "请填写希望学习的目标" }
        val session = LearningSession(mode = mode, goal = goal.trim(),
            goalSource = if (mode == "correction") "task" else if (goal.isBlank()) "unknown" else "user",
            displayId = displayId, parentRunId = parentRunId, sourceJson = source.toString(),
            appScope = appScope, allowedPackagesJson = JSONArray(allowedPackages).toString())
        files.create(session.id)
        repository.put(session)
        sequence = 0
        lastFrame = null
        synchronized(recent) { recent.clear() }
        current.value = session
        session
    }

    fun event(displayId: Int, kind: String, details: JSONObject, packageName: String? = null,
        observed: Boolean = true) {
        val session = current.value ?: return
        if (session.status != "recording" || displayId != session.displayId) return
        val captured = JSONObject(details.toString())
        val time = SystemClock.elapsedRealtime()
        val wallTime = System.currentTimeMillis()
        check(writes.trySend {
            val latest = current.value?.takeIf { it.id == session.id } ?: return@trySend
            val step = ++sequence
            val id = "${session.id}:event:$step"
            val event = JSONObject().put("id", id).put("sequence", step).put("kind", kind)
                .put("time_ms", time).put("wall_time", wallTime).put("display_id", displayId)
                .put("actor", "human").put("observed", observed).put("package_name", packageName)
                .put("details", captured).put("before_frame", lastFrame).put("evidence_id", id)
            files.append(session.id, "events.jsonl", event)
            memory.saveEvidence(MemoryEvidence(id = id, episodeId = session.id, runId = session.id,
                step = step, kind = "human_action", text = event.toString(), author = "capture",
                actor = "human", sourceType = session.sourceType,
                observationType = if (observed) "direct" else "inferred", observedAt = wallTime))
            synchronized(recent) {
                recent.addLast("$kind: ${captured.toString().take(180)}")
                while (recent.size > 12) recent.removeFirst()
            }
            update(latest.copy(eventCount = latest.eventCount + 1))
        }.isSuccess) { "学习记录写入已停止" }
    }

    /** Caller retains the bitmap and may recycle it after this call returns. */
    suspend fun frame(bitmap: Bitmap, displayId: Int, tree: String?, packageName: String?,
        metadata: JSONObject = JSONObject()) {
        val session = current.value ?: return
        if (session.status != "recording" || session.displayId != displayId) return
        val time = SystemClock.elapsedRealtime()
        withContext(NonCancellable) { ordered {
            val latest = current.value?.takeIf { it.id == session.id } ?: return@ordered
            val step = ++sequence
            val directory = files.directory(session.id)
            val image = File(directory, "frame-$step.jpg")
            val treeFile = tree?.let { File(directory, "frame-$step.txt").apply { writeText(it, Charsets.UTF_8) } }
            val scale = minOf(1.0, 1280.0 / maxOf(bitmap.width, bitmap.height))
            val resized = if (scale < 1) Bitmap.createScaledBitmap(bitmap,
                (bitmap.width * scale).toInt(), (bitmap.height * scale).toInt(), true) else bitmap
            try { image.outputStream().use { check(resized.compress(Bitmap.CompressFormat.JPEG, 82, it)) } }
            finally { if (resized !== bitmap) resized.recycle() }
            val id = "${session.id}:frame:$step"
            val geometry = JSONObject(metadata.toString()).put("saved_width", (bitmap.width * scale).toInt())
                .put("saved_height", (bitmap.height * scale).toInt())
            val frame = LearningFrame("frame-$step", step, time, displayId, bitmap.width, bitmap.height,
                packageName, image.absolutePath, treeFile?.absolutePath, id, geometry.toString())
            files.append(session.id, "frames.jsonl", frame.json())
            memory.saveEvidence(MemoryEvidence(id = id, episodeId = session.id, runId = session.id,
                step = step, kind = "human_observation", text = frame.json().toString(),
                imagePath = image.absolutePath, treePath = treeFile?.absolutePath, author = "capture",
                actor = "human", sourceType = session.sourceType, observationType = "direct"))
            lastFrame = frame.id
            update(latest.copy(frameCount = latest.frameCount + 1))
        } }
    }

    suspend fun pause(paused: Boolean) = ordered {
        val session = requireNotNull(current.value) { "当前没有学习记录" }
        check(session.status in setOf("recording", "paused")) { "记录已停止" }
        update(session.copy(status = if (paused) "paused" else "recording"))
    }

    fun summary(): String = synchronized(recent) {
        "人工接管期间实际记录的操作（并非已验证成功）：\n" + recent.joinToString("\n")
    }

    suspend fun finish(note: String = "", discard: Boolean = false): LearningSession? = ordered {
        val session = current.value ?: return@ordered null
        val empty = !files.hasMaterial(session)
        val awaitTask = JSONObject(session.sourceJson).optBoolean("automatic_review")
        val finished = session.copy(note = note, status = when {
            discard -> "discarded"
            awaitTask -> "awaiting_task"
            empty -> "interrupted"
            else -> "queued"
        }, error = if (empty && !discard && !awaitTask) "未采集到操作或画面，请重新录制。" else session.error,
            finishedAt = System.currentTimeMillis(), updatedAt = System.currentTimeMillis())
        try { repository.put(finished) }
        finally { current.value = null }
        if (discard) repository.discard(session.id)
        else if (!empty && !awaitTask) {
            try { repository.requestLearning(session.id) }
            catch (failure: Exception) {
                repository.put(finished.copy(status = "failed", error = failure.message))
                throw failure
            }
        }
        repository.get(session.id)
    }

    private suspend fun update(session: LearningSession) {
        val next = session.copy(updatedAt = System.currentTimeMillis())
        repository.put(next)
        current.value = next
    }

    private suspend fun <T> ordered(block: suspend () -> T): T {
        val result = CompletableDeferred<T>()
        writes.send {
            try { result.complete(block()) }
            catch (failure: Exception) { result.completeExceptionally(failure) }
        }
        return result.await()
    }

    companion object {
        @Volatile private var instance: LearningRecorder? = null
        fun get(context: Context): LearningRecorder = instance ?: synchronized(this) {
            instance ?: LearningRecorder(context).also { instance = it }
        }
    }
}
