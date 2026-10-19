package ai.opencyvis.learning

import android.content.Context
import ai.opencyvis.db.AppDatabase
import ai.opencyvis.memory.MemoryBlock
import ai.opencyvis.memory.MemoryEvidence
import ai.opencyvis.memory.MemoryRepository
import ai.opencyvis.memory.MemoryWriteResult
import androidx.work.WorkManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class LearningRepository(context: Context, private val database: AppDatabase = AppDatabase.getInstance(context)) {
    private val appContext = context.applicationContext
    private val dao = database.learningDao()
    private val memory = MemoryRepository(appContext, database)
    private val files = LearningFiles(appContext)

    fun observeAll(): Flow<List<LearningSession>> = dao.observeAll()
    suspend fun get(id: String): LearningSession? = withContext(Dispatchers.IO) { dao.get(id) }
    internal suspend fun all(): List<LearningSession> = withContext(Dispatchers.IO) { dao.all() }
    internal suspend fun put(session: LearningSession) = withContext(Dispatchers.IO) {
        database.runInTransaction {
            // A deleted source stays blocked even after its learning_sessions row is gone.
            val current = dao.get(session.id)
            if (database.memoryDao().blocked("run:${session.id}") == 0 &&
                current?.status !in setOf("discarded", "saved")) dao.put(session)
        }
    }
    suspend fun events(id: String): List<JSONObject> = withContext(Dispatchers.IO) { files.events(id) }
    suspend fun frames(id: String): List<LearningFrame> = withContext(Dispatchers.IO) { files.frames(id) }

    suspend fun requestLearning(id: String) {
        val session = requireNotNull(get(id)) { "学习记录不存在" }
        require(LearningRecorder.get(appContext).active.value?.id != id) { "请先结束当前录制" }
        require(session.status in setOf("queued", "interrupted", "failed", "ready", "awaiting_input")) { "当前记录不能重新整理" }
        requireCompletedTask(session)
        require(withContext(Dispatchers.IO) { files.hasMaterial(session) }) { "没有采集到可学习的操作或画面，请重新录制" }
        put(session.copy(status = "queued", error = null, updatedAt = System.currentTimeMillis()))
        LearningJobs(appContext).enqueue(id, recompile = session.status in setOf("ready", "awaiting_input"))
    }

    suspend fun answer(id: String, questionId: String, answer: String) {
        require(answer.isNotBlank()) { "请填写补充说明" }
        val session = requireNotNull(get(id))
        require(session.status in setOf("ready", "awaiting_input", "failed")) { "请等待本次整理结束" }
        val questions = JSONArray(session.questionsJson)
        require((0 until questions.length()).any { questions.getJSONObject(it).getString("id") == questionId }) { "问题已更新，请重新打开记录" }
        val answers = JSONArray(session.answersJson)
        val evidenceId = "$id:answer:${System.currentTimeMillis()}"
        val step = (memory.getEvidence(id).maxOfOrNull { it.step } ?: 0) + 1
        answers.put(JSONObject().put("question_id", questionId).put("text", answer.trim()).put("evidence_id", evidenceId))
        memory.saveEvidence(MemoryEvidence(id = evidenceId, episodeId = id, runId = id, step = step,
            kind = "user_explanation", text = answer.trim(), author = "user", actor = "human",
            sourceType = session.sourceType, observationType = "user_stated"))
        put(session.copy(answersJson = answers.toString(), status = "queued", error = null,
            updatedAt = System.currentTimeMillis()))
        LearningJobs(appContext).enqueue(id, recompile = true)
    }

    suspend fun supplement(id: String, note: String) {
        require(note.isNotBlank()) { "请填写补充说明" }
        val session = requireNotNull(get(id))
        require(session.status in setOf("ready", "awaiting_input", "failed", "interrupted")) { "请先结束当前整理" }
        requireCompletedTask(session)
        val evidenceId = "$id:explanation:${System.currentTimeMillis()}"
        val step = (memory.getEvidence(id).maxOfOrNull { it.step } ?: 0) + 1
        memory.saveEvidence(MemoryEvidence(id = evidenceId, episodeId = id, runId = id, step = step,
            kind = "user_explanation", text = note.trim(), author = "user", actor = "human",
            sourceType = session.sourceType, observationType = "user_stated"))
        val answers = JSONArray(session.answersJson).put(JSONObject().put("question_id", "supplement")
            .put("text", note.trim()).put("evidence_id", evidenceId))
        put(session.copy(note = listOf(session.note, note.trim()).filter(String::isNotBlank).joinToString("\n"),
            answersJson = answers.toString(), status = "queued", error = null, updatedAt = System.currentTimeMillis()))
        LearningJobs(appContext).enqueue(id, recompile = true)
    }

    suspend fun updateDraft(id: String, proposalsJson: String) = withContext(Dispatchers.IO) {
        database.runInTransaction {
            val session = requireNotNull(dao.get(id))
            require(session.status in setOf("ready", "awaiting_input")) { "请等待学习结果准备完成" }
            val proposals = LearningProposals.decode(id, proposalsJson)
            val evidence = database.memoryDao().runEvidence(id)
            require(proposals.all { p -> p.sourceEvidenceIds.all { ref -> evidence.any { it.id == ref } } }) { "修改中包含不存在的来源" }
            val rows = JSONArray(proposalsJson)
            val edits = LearningProposals.recordEdits(session, rows, (evidence.maxOfOrNull { it.step } ?: 0) + 1)
            val answers = JSONArray(session.answersJson)
            edits.forEach {
                database.memoryDao().putEvidence(it)
                answers.put(JSONObject().put("question_id", "draft_edit").put("text", it.text).put("evidence_id", it.id))
            }
            dao.put(session.copy(proposalsJson = rows.toString(), answersJson = answers.toString(), updatedAt = System.currentTimeMillis()))
        }
    }

    suspend fun recover() = LearningJobs(appContext).recover()

    private fun requireCompletedTask(session: LearningSession) {
        require(!TaskReviewPolicy.isAutomatic(session) || JSONObject(session.sourceJson).has("task_completion")) {
            "这次接管会在任务结束后自动复盘，请先完成或停止原任务"
        }
    }

    suspend fun accept(id: String, enable: Boolean = true): List<MemoryWriteResult> {
        val session = requireNotNull(get(id))
        val proposals = LearningProposals.decode(id, session.proposalsJson)
        require(proposals.isNotEmpty()) { "这次没有可保存的知识；可以补充说明或保留经历" }
        return memory.commitLearningProposals(id, session.proposalsJson, proposals, enable)
    }

    /** Discard applies to unpublished sessions. Published knowledge is managed in Memory. */
    suspend fun discard(id: String) = withContext(Dispatchers.IO) {
        val session = dao.get(id) ?: return@withContext
        require(session.status != "saved") { "已保存的知识请在记忆页面管理" }
        require(LearningRecorder.get(appContext).active.value?.id != id) { "请先结束当前录制" }
        WorkManager.getInstance(appContext).cancelUniqueWork("memory:${LearningJobs.jobId(id)}")
        database.runInTransaction {
            val memoryDao = database.memoryDao()
            memoryDao.putBlock(MemoryBlock("run:$id"))
            memoryDao.cancelJobs(id, System.currentTimeMillis())
            memoryDao.runEvidence(id).forEach { memoryDao.deleteEvidence(it.id) }
            memoryDao.deleteEpisode(id)
            memoryDao.deleteEpisodeFts(id)
            dao.put(session.copy(status = "discarded", sourceJson = "{}", analysisJson = "{}",
                proposalsJson = "[]", questionsJson = "[]", answersJson = "[]", note = "",
                error = null, updatedAt = System.currentTimeMillis()))
        }
        val directory = files.directory(id)
        check(!directory.exists() || directory.deleteRecursively()) { "学习记录文件未能全部删除" }
    }

    /** Remove a recording while keeping knowledge the user has already saved. */
    suspend fun delete(id: String) = withContext(Dispatchers.IO) {
        require(LearningRecorder.get(appContext).active.value?.id != id) { "请先结束当前录制" }
        val directory = files.directory(id)
        val jobIds = mutableSetOf(LearningJobs.jobId(id))
        database.runInTransaction {
            val memoryDao = database.memoryDao()
            memoryDao.putBlock(MemoryBlock("run:$id"))
            jobIds += memoryDao.jobs().filter { it.runId == id }.map { it.id }
            memoryDao.cancelJobs(id, System.currentTimeMillis())
            val memoryIds = memoryDao.all().map { it.id }.toSet()
            val evidence = memoryDao.allEvidence()
            val retained = evidence.filter { it.memoryId in memoryIds }
            files.retainEvidence(id, retained).zip(retained).forEach { (updated, previous) ->
                if (updated != previous) memoryDao.putEvidence(updated)
            }
            evidence.filter { it.runId == id && it.memoryId !in memoryIds }
                .forEach { memoryDao.deleteEvidence(it.id) }
            memoryDao.deleteEpisode(id)
            memoryDao.deleteEpisodeFts(id)
            dao.delete(id)
        }
        jobIds.forEach { WorkManager.getInstance(appContext).cancelUniqueWork("memory:$it") }
        check(!directory.exists() || directory.deleteRecursively()) { "学习记录文件未能全部删除" }
    }

    suspend fun export(id: String): File = withContext(Dispatchers.IO) {
        val session = requireNotNull(dao.get(id))
        require(session.status !in setOf("recording", "paused", "discarded")) { "请先结束记录" }
        val output = File(appContext.cacheDir, "run_exports/learning-$id.zip")
        check(output.parentFile!!.isDirectory || output.parentFile!!.mkdirs())
        ZipOutputStream(output.outputStream()).use { zip ->
            fun text(name: String, value: String) {
                zip.putNextEntry(ZipEntry(name)); zip.write(value.toByteArray(Charsets.UTF_8)); zip.closeEntry()
            }
            text("session.json", JSONObject().put("id", id).put("mode", session.mode)
                .put("goal", session.goal).put("goal_source", session.goalSource).put("status", session.status)
                .put("parent_run_id", session.parentRunId).put("source", JSONObject(session.sourceJson))
                .put("note", session.note).put("analysis", JSONObject(session.analysisJson))
                .put("questions", JSONArray(session.questionsJson)).put("answers", JSONArray(session.answersJson))
                .put("proposals", JSONArray(session.proposalsJson)).put("commits", JSONArray(session.commitsJson))
                .put("created_at", session.createdAt).put("finished_at", session.finishedAt).put("error", session.error).toString(2))
            val jobs = JSONArray(memory.listJobs().filter { it.runId == id }.map {
                JSONObject().put("id", it.id).put("kind", it.kind).put("status", it.status)
                    .put("checkpoint", JSONObject(it.checkpointJson)).put("error", it.error)
            })
            text("jobs.json", jobs.toString(2))
            files.directory(id).listFiles()?.filter { it.isFile }?.forEach { file ->
                zip.putNextEntry(ZipEntry("capture/${file.name}"))
                file.inputStream().use { it.copyTo(zip) }; zip.closeEntry()
            }
        }
        output
    }
}
