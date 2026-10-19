package ai.opencyvis.learning

import android.content.Context
import ai.opencyvis.display.VirtualDisplayManager
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject

/** Records normal task takeovers and keeps their material pending until the parent run ends. */
class TaskHandoffRecording(context: Context, private val controller: LearningSessionController) {
    private val repository = LearningRepository(context)
    private val recorder = LearningRecorder.get(context)
    private val lifecycle = Mutex()
    @Volatile private var activeId: String? = null
    @Volatile private var parentRunId: String? = null

    val isRecording: Boolean get() = recorder.active.value?.let {
        it.id == controller.sessionId && TaskReviewPolicy.isAutomatic(it)
    } == true
    fun belongsTo(runId: String): Boolean = parentRunId == runId

    suspend fun start(display: VirtualDisplayManager, snapshot: Map<String, Any?>,
        trigger: String, kind: String, reason: String, step: Int?): Boolean = lifecycle.withLock {
        if (isRecording) return@withLock true
        if (controller.sessionId != null || !TaskReviewPolicy.shouldCapture(trigger, kind, reason)) return@withLock false
        val source = JSONObject(snapshot)
        val runId = source.optString("run_id").takeIf { it.isNotBlank() && it != "null" } ?: return@withLock false
        val goal = source.getString("task")
        val recent = source.optJSONArray("recent_steps")
        val lastStep = step ?: recent?.optJSONObject(recent.length() - 1)?.optInt("step") ?: 0
        source.put("automatic_review", true).put("trigger", trigger)
            .put("handoff_reason", reason).put("handoff_kind", kind).put("handoff_step", lastStep)
        activeId = controller.start("correction", goal, display, source)
        parentRunId = runId
        true
    }

    /** Returns a compact description for the next Agent observation; no model call runs here. */
    suspend fun finish(returnSource: String, discard: Boolean = false): String? = lifecycle.withLock {
        val id = activeId ?: return@withLock null
        try {
            val result = controller.finish("接管结束：$returnSource", discard)
            if (!discard) repository.get(id)?.let { session ->
                val source = JSONObject(session.sourceJson)
                    .put("returned_at", System.currentTimeMillis()).put("return_source", returnSource)
                repository.put(session.copy(sourceJson = source.toString(), updatedAt = System.currentTimeMillis()))
            }
            result?.second
        } finally {
            activeId = null
            parentRunId = null
        }
    }
}
