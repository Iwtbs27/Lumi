package ai.opencyvis.memory

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import ai.opencyvis.llm.LLMClientInterface
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/** Room owns the job/input/checkpoint; WorkManager only wakes a durable job. */
class MemoryJobs(context: Context) {
    private val appContext = context.applicationContext
    private val repository = MemoryRepository(appContext)

    suspend fun enqueue(runId: String) {
        val episode = requireNotNull(repository.getEpisode(runId)) { "整理前必须先保存任务经历" }
        require(episode.sourceType == "agent_run") { "人类示范通过学习会话整理" }
        val segments = JSONArray(episode.segmentsJson)
        if (episode.instruction.length > 2400) segments.put(JSONObject()
            .put("kind", "task_instruction").put("text", episode.instruction))
        if (episode.summary.isNotBlank()) segments.put(JSONObject()
            .put("kind", "task_outcome").put("text", episode.summary))
        val payload = JSONObject().put("instruction", episode.instruction)
            .put("app_scope", episode.appScope)
            .put("result_status", episode.resultStatus).put("segments", segments)
        val id = "$runId:v1"
        repository.enqueueJob(MemoryJob(id = id, runId = runId, payloadJson = payload.toString()))
        val job = repository.getJob(id) ?: return
        if (job.status in setOf("pending", "running")) schedule(id)
    }

    suspend fun status(): List<MemoryJob> = repository.listJobs()

    suspend fun resumePending() {
        if (isForegroundActive()) return
        for (job in repository.listJobs()) {
            if (job.status == "pending") schedule(job.id, replace = true)
        }
    }

    suspend fun retry(jobId: String) {
        val job = requireNotNull(repository.getJob(jobId)) { "整理任务不存在" }
        if (job.kind.startsWith("learning_")) {
            ai.opencyvis.learning.LearningRepository(appContext).requestLearning(job.runId)
            return
        }
        require(job.status != "cancelled") { "来源已删除，不能恢复这次整理" }
        if (job.status == "completed") return
        val checkpoint = JSONObject(job.checkpointJson).put("network_failures", 0)
        checkpoint.remove("repair_batch")
        repository.updateJob(job.copy(status = "pending", error = null,
            checkpointJson = checkpoint.toString(), updatedAt = System.currentTimeMillis()))
        schedule(jobId, replace = true)
    }

    internal fun schedule(id: String, replace: Boolean = false) {
        val request = OneTimeWorkRequestBuilder<MemoryWorker>()
            .setInputData(workDataOf(MemoryWorker.JOB_ID to id))
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .addTag("memory_consolidation").build()
        WorkManager.getInstance(appContext).enqueueUniqueWork("memory:$id",
            if (replace) ExistingWorkPolicy.REPLACE else ExistingWorkPolicy.KEEP, request)
    }

    companion object {
        private val foregroundRuns = mutableSetOf<String>()
        private var background: Lease? = null

        @Synchronized fun enterForeground(runId: String) {
            foregroundRuns.add(runId)
            background?.let {
                it.interrupted = true
                it.client.cancelRequests()
            }
        }

        @Synchronized fun leaveForeground(runId: String) { foregroundRuns.remove(runId) }
        @Synchronized internal fun isForegroundActive(): Boolean = foregroundRuns.isNotEmpty()

        internal class Lease(val client: LLMClientInterface) {
            @Volatile var interrupted = false
        }

        @Synchronized internal fun acquire(client: LLMClientInterface): Lease? {
            if (foregroundRuns.isNotEmpty() || background != null) return null
            return Lease(client).also { background = it }
        }

        @Synchronized internal fun release(lease: Lease) {
            if (background === lease) background = null
        }

        /** Invoked on application/service startup after process-local foreground state is restored. */
        suspend fun recover(context: Context) {
            val jobs = MemoryJobs(context)
            val repository = jobs.repository
            for (episode in repository.listEpisodes()) {
                if (episode.sourceType != "agent_run") continue
                val active = synchronized(this) { episode.runId in foregroundRuns }
                if (active) continue
                if (episode.status == "running") repository.saveEpisode(episode.copy(
                    status = "interrupted", resultStatus = "unknown", updatedAt = System.currentTimeMillis()))
                if (repository.getJob("${episode.runId}:v1") == null) jobs.enqueue(episode.runId)
            }
            for (job in repository.listJobs()) {
                if (job.status in setOf("pending", "running")) jobs.schedule(job.id)
            }
            ai.opencyvis.learning.LearningJobs(context).recover()
        }
    }
}
