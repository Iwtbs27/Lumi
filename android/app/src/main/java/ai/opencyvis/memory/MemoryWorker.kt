package ai.opencyvis.memory

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import ai.opencyvis.config.ConfigRepository
import ai.opencyvis.llm.LlmClientFactory
import ai.opencyvis.llm.LlmTransientException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject

/** At most one consolidation request runs; a foreground task can immediately cancel its transport. */
class MemoryWorker(context: Context, parameters: WorkerParameters) : CoroutineWorker(context, parameters) {
    override suspend fun doWork(): Result = serial.withLock {
        val id = inputData.getString(JOB_ID) ?: return@withLock Result.failure()
        val repository = MemoryRepository(applicationContext)
        val consolidator = MemoryConsolidator(repository)
        val started = System.currentTimeMillis()
        while (System.currentTimeMillis() - started < 4 * 60_000) {
            val job = repository.getJob(id) ?: return@withLock Result.success()
            if (job.status in setOf("completed", "cancelled")) return@withLock Result.success()
            if (job.status == "failed") return@withLock Result.failure()
            if (MemoryJobs.isForegroundActive()) return@withLock Result.retry()
            var lease: MemoryJobs.Companion.Lease? = null
            try {
                val config = ConfigRepository(applicationContext)
                val client = LlmClientFactory.create(config)
                lease = MemoryJobs.acquire(client)
                if (lease == null) {
                    client.shutdown()
                    return@withLock Result.retry()
                }
                repository.updateJob(job.copy(status = "running", attempts = job.attempts + 1,
                    error = null, updatedAt = System.currentTimeMillis()))
                if (job.kind.startsWith("learning_")) {
                    val finished = ai.opencyvis.learning.LearningProcessor(applicationContext).advance(job, client)
                    if (lease.interrupted) {
                        updatePending(repository, id)
                        return@withLock Result.retry()
                    }
                    if (finished) return@withLock Result.success()
                    continue
                }
                val batch = consolidator.run(job, client)
                if (lease.interrupted) {
                    updatePending(repository, id)
                    return@withLock Result.retry()
                }
                if (batch != null) repository.applyProposals(id, batch.proposals, batch.id, batch.checkpoint)
                val current = repository.getJob(id) ?: return@withLock Result.success()
                if (current.status == "cancelled") return@withLock Result.success()
                val finished = batch == null || batch.finished
                repository.updateJob(current.copy(status = if (finished) "completed" else "pending",
                    error = null, updatedAt = System.currentTimeMillis()))
                if (finished) {
                    val episode = repository.getEpisode(job.runId)
                    val summaries = JSONObject(current.checkpointJson).optJSONArray("batch_summaries")
                    if (episode != null && summaries != null && summaries.length() > 0) {
                        val summary = (0 until summaries.length()).joinToString("\n") {
                            summaries.getJSONObject(it).getString("summary")
                        }
                        repository.saveEpisode(episode.copy(summary = summary, updatedAt = System.currentTimeMillis()))
                    }
                    return@withLock Result.success()
                }
            } catch (cancelled: CancellationException) {
                withContext(NonCancellable) {
                    updatePending(repository, id)
                }
                throw cancelled
            } catch (failure: Exception) {
                if (lease?.interrupted == true || MemoryJobs.isForegroundActive()) {
                    updatePending(repository, id)
                    return@withLock Result.retry()
                }
                val current = repository.getJob(id) ?: return@withLock Result.success()
                if (current.status == "cancelled") return@withLock Result.success()
                val checkpoint = JSONObject(current.checkpointJson)
                val failures = checkpoint.optInt("network_failures") + 1
                val retry = failure is LlmTransientException && failures < 3
                checkpoint.put("network_failures", failures)
                repository.updateJob(current.copy(status = if (retry) "pending" else "failed",
                    checkpointJson = checkpoint.toString(), error = failure.message?.take(500) ?: failure.javaClass.simpleName,
                    updatedAt = System.currentTimeMillis()))
                if (current.kind.startsWith("learning_")) {
                    val learning = ai.opencyvis.learning.LearningRepository(applicationContext)
                    learning.get(current.runId)?.takeIf { it.status !in setOf("discarded", "saved") }?.let {
                        learning.put(it.copy(status = if (retry) "queued" else "failed", error = failure.message,
                            updatedAt = System.currentTimeMillis()))
                    }
                }
                return@withLock if (retry) Result.retry() else Result.failure()
            } finally {
                lease?.let { MemoryJobs.release(it); it.client.shutdown() }
            }
        }
        Result.retry()
    }

    private suspend fun updatePending(repository: MemoryRepository, id: String) {
        val current = repository.getJob(id) ?: return
        if (current.status !in setOf("completed", "cancelled", "failed")) repository.updateJob(
            current.copy(status = "pending", updatedAt = System.currentTimeMillis()))
    }

    companion object {
        const val JOB_ID = "memory_job_id"
        private val serial = Mutex()
    }
}
