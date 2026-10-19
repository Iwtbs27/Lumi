package ai.opencyvis.memory

import android.content.Context
import ai.opencyvis.db.AppDatabase
import ai.opencyvis.learning.LearningFiles
import androidx.work.WorkManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

class MemoryRepository(context: Context, private val database: AppDatabase = AppDatabase.getInstance(context)) {
    private val dao = database.memoryDao()
    private val appContext = context.applicationContext
    private val filesDir = appContext.filesDir

    suspend fun get(id: String): MemoryItem? = io { dao.get(id) }
    suspend fun revisionToken(): Long = io { dao.revisionToken() }
    suspend fun list(query: String = ""): List<MemoryItem> = io {
        dao.all().filter { query.isBlank() || (it.title + " " + it.summary + " " + it.appScope).contains(query, true) }
    }
    suspend fun search(query: String, appScope: String? = null, limit: Int = 20): List<MemoryItem> = io {
        val match = MemorySearch.query(query)
        val textMatches = if (match.isEmpty()) emptyList() else dao.match(match, 80)
        val scoped = appScope?.takeIf { it.isNotBlank() }?.let { dao.byApp(it, 20) }.orEmpty()
        (textMatches + scoped).distinctBy { it.id }.filter { item ->
            !packageConflict(item.appScope, appScope) && !expired(item)
        }.sortedWith(compareByDescending<MemoryItem> { MemorySearch.score(it, query, appScope) }
            .thenByDescending { it.lastVerifiedAt ?: 0L }).take(limit.coerceAtMost(20))
    }

    suspend fun searchForConsolidation(query: String, appScope: String? = null, limit: Int = 20): List<MemoryItem> = io {
        val match = MemorySearch.query(query)
        if (match.isBlank()) emptyList() else dao.matchAll(match, 80)
            .filter { !packageConflict(it.appScope, appScope) }
            .sortedByDescending { MemorySearch.score(it, query, appScope) }.take(limit)
    }

    suspend fun saveExplicit(title: String, value: String, appScope: String? = null,
        sourceRef: String? = null, bodyJson: String? = null): MemoryItem = io {
        require(title.isNotBlank() && value.isNotBlank()) { "Memory title and value are required" }
        transaction {
            val old = dao.sameIdentity(title.trim(), "fact", appScope.orEmpty(), "").firstOrNull()
            val item = MemoryItem(id = old?.id ?: UUID.randomUUID().toString(), title = title.trim(), summary = value.trim(),
                appScope = appScope, status = "active", origin = "explicit_user", evidenceLevel = "user_stated",
                bodyJson = bodyJson ?: MemoryBodies.fact(value.trim(), sourceRef = sourceRef),
                revision = (old?.revision ?: 0) + 1, createdAt = old?.createdAt ?: System.currentTimeMillis())
            dao.clearBlock(MemoryBodies.identity(item))
            save(item, old, "Explicit user instruction")
            item
        }
    }

    suspend fun edit(id: String, title: String, summary: String, bodyJson: String,
        expectedRevision: Int, appScope: String? = null): MemoryItem = io { transaction {
        val old = requireNotNull(dao.get(id)) { "Memory no longer exists" }
        check(old.revision == expectedRevision) { "Memory changed; reopen it before saving" }
        require(title.isNotBlank() && summary.isNotBlank())
        val editedBody = JSONObject(bodyJson)
        if (old.type == "procedure") editedBody.put("maturity", "observed")
        val item = old.copy(title = title.trim(), summary = summary.trim(), bodyJson = editedBody.toString(),
            appScope = appScope, origin = "user_edit", evidenceLevel = "user_stated",
            status = if (old.status in setOf("disabled", "superseded")) old.status else "active",
            revision = old.revision + 1, updatedAt = System.currentTimeMillis(), lastVerifiedAt = null)
        save(item, old, "User edit")
        item
    } }

    suspend fun setEnabled(id: String, enabled: Boolean) = io { transaction {
        val old = requireNotNull(dao.get(id))
        val item = old.copy(status = if (enabled) "active" else "disabled", revision = old.revision + 1,
            updatedAt = System.currentTimeMillis())
        save(item, old, if (enabled) "User enabled" else "User disabled")
    } }

    suspend fun confirmedRevisions(): Map<String, Int> = io {
        dao.userConfirmations().associate { requireNotNull(it.memoryId) to requireNotNull(it.revision) }
    }

    /** A human review confirms this content; it does not count as a successful task run. */
    suspend fun confirmAndEnable(id: String, expectedRevision: Int) = io { transaction {
        val old = requireNotNull(dao.get(id)) { "Memory no longer exists" }
        check(old.revision == expectedRevision) { "Memory changed; reopen it before confirming" }
        val item = old.copy(status = "active", revision = old.revision + 1, updatedAt = System.currentTimeMillis())
        save(item, old, "User confirmed content and enabled")
        val review = MemoryEvidence(memoryId = item.id, revision = item.revision,
            episodeId = "user-review:${item.id}", runId = "user-review:${item.id}", step = 0,
            kind = "user_confirmation", author = "user_review",
            text = "User reviewed and confirmed this memory content.", actor = "human", sourceType = "user_confirmation")
        dao.putEvidence(review.copy(id = evidenceVersionId(item, review)))
    } }

    suspend fun markNeedsReview(id: String, expectedRevision: Int, reason: String) = io { transaction {
        val old = dao.get(id) ?: return@transaction
        if (old.revision == expectedRevision && old.status == "active" && !isUserOwned(old))
            save(old.copy(status = "needs_review", revision = old.revision + 1, updatedAt = System.currentTimeMillis()), old, reason)
    } }

    suspend fun delete(id: String) = io {
        transaction { deleteItem(id) }
        removeUnreferencedFiles()
    }
    suspend fun revisions(id: String): List<MemoryRevision> = io { dao.revisions(id) }

    /** Restore content as an explicit user edit; the original and replaced revisions stay readable. */
    suspend fun restoreRevision(id: String, revision: Int, expectedRevision: Int): MemoryItem = io { transaction {
        val current = requireNotNull(dao.get(id)) { "记忆已不存在" }
        check(current.revision == expectedRevision) { "记忆已变化，请重新打开后恢复" }
        val previous = requireNotNull(dao.revisions(id).firstOrNull { it.revision == revision }) { "历史版本已不存在" }
        val snapshot = JSONObject(previous.snapshotJson)
        require(snapshot.getString("type") == current.type) { "历史版本的记忆类型不同" }
        val body = snapshot.getJSONObject("body")
        MemoryBodies.validate(current.type, body)
        if (current.type == "procedure") body.put("maturity", "observed")
        val restored = current.copy(title = snapshot.getString("title"), summary = snapshot.getString("summary"),
            bodyJson = body.toString(), appScope = snapshot.opt("app_scope") as? String,
            taskFamily = snapshot.opt("task_family") as? String, origin = "user_edit",
            evidenceLevel = "user_stated", lastVerifiedAt = null,
            revision = current.revision + 1, updatedAt = System.currentTimeMillis())
        save(restored, current, "User restored content from revision $revision")
        restored
    } }

    suspend fun evidence(id: String): List<MemoryEvidence> = io { dao.evidence(id) }
    suspend fun episodeEvidence(id: String): List<MemoryEvidence> = io { dao.episodeEvidence(id) }
    suspend fun getEvidence(runId: String, step: Int? = null): List<MemoryEvidence> = io {
        dao.runEvidence(runId).filter { step == null || it.step == step }
    }
    suspend fun saveEvidence(evidence: MemoryEvidence) = io { transaction {
        check(dao.blocked("run:${evidence.runId}") == 0) { "Memory source was deleted" }
        dao.putEvidence(evidence)
    } }
    suspend fun recordUsage(usage: MemoryUsage) = io { dao.putUsage(usage) }
    suspend fun usages(id: String): List<MemoryUsage> = io { dao.usages(id) }
    suspend fun runUsages(runId: String): List<MemoryUsage> = io { dao.runUsages(runId) }

    suspend fun saveEpisode(episode: MemoryEpisode) = io { transaction {
        if (dao.blocked("run:${episode.runId}") > 0) return@transaction
        if (episode.conversationId != null && dao.blocked("conversation:${episode.conversationId}") > 0) {
            dao.putBlock(MemoryBlock("run:${episode.runId}"))
            return@transaction
        }
        if (dao.job("${episode.runId}:v1")?.status == "cancelled") return@transaction
        dao.putEpisode(episode)
        dao.deleteEpisodeFts(episode.id)
        dao.putEpisodeFts(EpisodeFts(episode.id, MemorySearch.index(
            "${episode.instruction} ${episode.summary} ${episode.segmentsJson} ${episode.appScope.orEmpty()} ${java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.ROOT).format(java.util.Date(episode.createdAt))}")))
    } }
    suspend fun getEpisode(id: String): MemoryEpisode? = io { dao.episode(id) }
    suspend fun listEpisodes(query: String = ""): List<MemoryEpisode> = searchEpisodes(query, limit = 100)
    suspend fun searchEpisodes(query: String, appScope: String? = null, limit: Int = 20,
        since: Long? = null, until: Long? = null): List<MemoryEpisode> = io {
        val match = MemorySearch.query(query)
        val found = if (match.isEmpty()) dao.episodes(limit) else dao.matchEpisodes(match, limit * 3)
        found.filter { !packageConflict(it.appScope, appScope) &&
            (since == null || it.createdAt >= since) && (until == null || it.createdAt <= until) }.take(limit)
    }
    suspend fun enqueueJob(job: MemoryJob): Boolean = io { transaction {
        check(dao.blocked("run:${job.runId}") == 0) { "Memory source was deleted" }
        dao.enqueue(job) != -1L
    } }
    suspend fun getJob(id: String): MemoryJob? = io { dao.job(id) }
    suspend fun nextPendingJob(): MemoryJob? = io { dao.nextPendingJob() }
    suspend fun listJobs(): List<MemoryJob> = io { dao.jobs() }
    suspend fun updateJob(job: MemoryJob) = io { transaction {
        val current = dao.job(job.id)
        if (current?.status != "cancelled") dao.putJob(job.copy(updatedAt = System.currentTimeMillis()))
    } }

    /** Commit suggestions and the completed batch marker together, so retries cannot count twice. */
    suspend fun applyProposals(jobId: String, proposals: List<MemoryProposal>, batchId: String = "0",
        checkpointJson: String? = null): List<MemoryWriteResult> = io { transaction {
        val job = requireNotNull(dao.job(jobId))
        check(job.status != "cancelled" && dao.blocked("run:${job.runId}") == 0) { "Source was deleted" }
        val checkpoint = JSONObject(job.checkpointJson)
        val done = checkpoint.optJSONArray("completed_batches") ?: JSONArray()
        if ((0 until done.length()).any { done.optString(it) == batchId }) return@transaction emptyList()
        val results = proposals.map { applyProposal(it, job.runId) }
        val next = checkpointJson?.let(::JSONObject) ?: checkpoint
        done.put(batchId)
        next.put("completed_batches", done)
        val commits = next.optJSONArray("commits") ?: JSONArray()
        results.forEach { result -> commits.put(JSONObject().put("batch_id", batchId)
            .put("id", result.id).put("status", result.status).put("reason", result.reason)) }
        next.put("commits", commits)
        dao.putJob(job.copy(checkpointJson = next.toString(), updatedAt = System.currentTimeMillis()))
        results
    } }

    /** Accept exactly the draft the user reviewed; its marker and memory writes commit together. */
    suspend fun commitLearningProposals(sessionId: String, expectedDraft: String,
        proposals: List<MemoryProposal>, enable: Boolean = true): List<MemoryWriteResult> = io { transaction {
        val learning = database.learningDao()
        val session = requireNotNull(learning.get(sessionId)) { "学习会话已不存在" }
        check(session.proposalsJson == expectedDraft) { "学习提案已变化，请重新查看后确认" }
        if (session.status == "saved") {
            val commits = JSONArray(session.commitsJson)
            return@transaction (0 until commits.length()).map { index ->
                val value = commits.getJSONObject(index)
                MemoryWriteResult(value.getString("id"), value.getString("status"), value.optString("reason"))
            }
        }
        check(session.status in setOf("ready", "awaiting_input")) { "学习提案尚未就绪" }
        check(dao.blocked("run:$sessionId") == 0) { "学习来源已删除" }
        require(proposals.map { it.item.id }.distinct().size == proposals.size) { "同一提案中不能重复修改同一记忆" }
        val writer = LearningMemoryCommit(dao, ::save)
        val results = proposals.map { writer.apply(it, sessionId, enable) }
        val commits = JSONArray(results.map { mapOf("id" to it.id, "status" to it.status, "reason" to it.reason) })
        learning.put(session.copy(status = "saved", commitsJson = commits.toString(), updatedAt = System.currentTimeMillis()))
        results
    } }

    suspend fun deleteConversationSources(conversationId: Long) = io {
        val learningFiles = LearningFiles(appContext)
        val removedSessions = mutableListOf<String>()
        val cancelledJobs = mutableListOf<String>()
        transaction {
            dao.putBlock(MemoryBlock("conversation:$conversationId"))
            val conversationEpisodes = dao.conversationEpisodes(conversationId)
            val parentRuns = conversationEpisodes.map { it.runId }.toSet()
            val learning = database.learningDao()
            val sessions = learning.all().filter { it.parentRunId in parentRuns || it.id in parentRuns }
            val runs = parentRuns + sessions.map { it.id }
            val episodes = (conversationEpisodes + sessions.mapNotNull { dao.episode(it.id) }).distinctBy { it.id }
            cancelledJobs += dao.jobs().filter { it.runId in runs }.map { it.id }
            runs.forEach { dao.putBlock(MemoryBlock("run:$it")); dao.cancelJobs(it, System.currentTimeMillis()) }
            val affected = runs.flatMap { dao.runEvidence(it) }.mapNotNull { it.memoryId }.distinct()
            runs.flatMap { dao.runEvidence(it) }.forEach { dao.deleteEvidence(it.id) }
            affected.forEach { id ->
                val old = dao.get(id) ?: return@forEach
                val remaining = dao.evidence(id)
                if (remaining.isEmpty()) deleteItem(id)
                else {
                    // Shared text may include information solely contributed by the removed source.
                    // Drop that text rather than keep an unverifiable mixture of both sources.
                    dao.deleteRevisions(id)
                    val body = JSONObject().put("remaining_evidence", JSONArray(remaining.map { it.id }))
                    val item = old.copy(summary = "Source removed; content needs review", bodyJson = body.toString(),
                        status = "needs_review", evidenceLevel = "unverified", revision = old.revision + 1,
                        updatedAt = System.currentTimeMillis(), lastVerifiedAt = null)
                    save(item, null, "Source deletion requires reconstruction")
                }
            }
            episodes.forEach { dao.deleteEpisode(it.id); dao.deleteEpisodeFts(it.id) }
            sessions.forEach { session ->
                learning.put(session.copy(status = "discarded", goal = "", sourceJson = "{}", note = "",
                    appScope = null, allowedPackagesJson = "[]", analysisJson = "{}", proposalsJson = "[]",
                    questionsJson = "[]", answersJson = "[]", commitsJson = "[]", error = null,
                    eventCount = 0, frameCount = 0, updatedAt = System.currentTimeMillis()))
                removedSessions += session.id
            }
        }
        cancelledJobs.forEach { WorkManager.getInstance(appContext).cancelUniqueWork("memory:$it") }
        removedSessions.forEach { id ->
            val directory = learningFiles.directory(id)
            check(!directory.exists() || directory.deleteRecursively()) { "学习来源文件未能全部删除" }
        }
        removeUnreferencedFiles()
    }

    private fun applyProposal(proposal: MemoryProposal, jobRunId: String): MemoryWriteResult {
        val proposed = proposal.item
        fun reject(reason: String) = MemoryWriteResult(proposed.id, "conflict", reason)
        if (proposal.sourceRunId != jobRunId) return reject("Proposal source does not match job")
        if (dao.episode(jobRunId)?.sourceType?.let { it != "agent_run" } == true)
            return reject("Human learning drafts require explicit acceptance")
        val identity = MemoryBodies.identity(proposed)
        if (dao.blocked(identity) > 0) return reject("User deleted this memory")
        val byId = dao.get(proposed.id)
        val same = dao.sameIdentity(proposed.title, proposed.type, proposed.appScope.orEmpty(), proposed.taskFamily.orEmpty())
        val equivalent = if (byId == null && same.isEmpty()) dao.all().firstOrNull {
            it.type == proposed.type && it.appScope == proposed.appScope && it.taskFamily == proposed.taskFamily &&
                MemoryBodies.sameContent(it.bodyJson, proposed.bodyJson)
        } else null
        val old = byId ?: same.firstOrNull() ?: equivalent
        if (old != null) {
            if (isUserOwned(old)) return reject("User-owned memory cannot be replaced automatically")
            if (old.status in setOf("disabled", "superseded")) return reject("Memory is disabled or superseded")
            if (byId != null && proposal.expectedRevision != old.revision) return reject("Revision changed")
            if (byId == null && proposal.expectedRevision != null) return reject("Target memory was deleted")
            if (byId == null && !MemoryBodies.sameContent(old.bodyJson, proposed.bodyJson)) return reject("Existing identity requires an explicit revision")
        } else if (proposal.expectedRevision != null) return reject("Target memory was deleted")
        val source = dao.runEvidence(proposal.sourceRunId).filter { it.memoryId == null && it.step in proposal.sourceSteps }
        if (proposal.sourceSteps.any { step -> source.none { it.step == step } }) return reject("Missing source evidence")
        val hasObserved = source.any { independentObservation(it) }
        val body = JSONObject(proposed.bodyJson)
        if (old != null && JSONObject(old.bodyJson).has("data_dependencies") && !body.has("data_dependencies"))
            return reject("Existing data dependencies must be preserved or explicitly revised")
        val unchanged = old != null && MemoryBodies.sameContent(old.bodyJson, proposed.bodyJson)
        val scope = JSONObject(proposal.supportScopeJson).apply {
            if (!unchanged) put("whole_procedure", false)
        }.toString()
        if (unchanged && proposed.type == "procedure" && JSONObject(old!!.bodyJson).optString("maturity") == "validated")
            body.put("maturity", "validated")
        val inferredPreference = proposed.type == "fact" && body.optString("domain") == "user_preference"
        val active = hasObserved && !inferredPreference && proposed.evidenceLevel in setOf("observed_once", "observed_repeatedly") && proposed.status == "active"
        if (!active && unchanged && old?.status == "active") return reject("Unverified evidence cannot downgrade an active memory")
        val item = proposed.copy(id = old?.id ?: proposed.id, revision = (old?.revision ?: 0) + 1,
            origin = if (hasObserved) "observed" else "inferred", status = if (active) "active" else "candidate",
            evidenceLevel = if (active) "observed_once" else "unverified", createdAt = old?.createdAt ?: proposed.createdAt,
            bodyJson = body.toString(), updatedAt = System.currentTimeMillis(),
            lastVerifiedAt = if (active) source.maxOf { it.observedAt } else null)
        save(item, old, proposal.reason)
        val existing = dao.evidence(item.id)
        val images = source.filter { it.imagePath != null }.let { listOfNotNull(it.firstOrNull()?.id, it.lastOrNull()?.id).toSet() }
        source.distinctBy { it.step }.forEach { e ->
            dao.putEvidence(e.copy(id = evidenceVersionId(item, e), memoryId = item.id,
                revision = item.revision, imagePath = if (e.id in images) e.imagePath else null,
                supportScopeJson = scope))
        }
        // Independent runs count only when the actual semantic body is unchanged.
        if (active && unchanged && old?.status == "active" &&
            old.evidenceLevel in setOf("observed_once", "observed_repeatedly") &&
            (item.type != "procedure" || JSONObject(scope).optBoolean("whole_procedure")) &&
            existing.any { it.revision == old.revision && it.runId != proposal.sourceRunId && independentObservation(it) &&
                (item.type != "procedure" || JSONObject(it.supportScopeJson).optBoolean("whole_procedure")) }) {
            val promoted = if (item.type == "procedure") JSONObject(item.bodyJson).put("maturity", "validated").toString() else item.bodyJson
            save(item.copy(evidenceLevel = "observed_repeatedly", bodyJson = promoted), null, "Repeated independent observations")
        }
        return MemoryWriteResult(item.id, if (old == null) "created" else "updated")
    }

    private fun save(item: MemoryItem, old: MemoryItem?, reason: String) {
        if (old != null) dao.putRevision(MemoryRevision(old.id, old.revision, MemoryBodies.snapshot(old), reason))
        dao.put(item)
        if (old != null && item.revision != old.revision && item.appScope == old.appScope &&
            item.taskFamily == old.taskFamily && MemoryBodies.sameContent(old.bodyJson, item.bodyJson)) {
            // Keep candidate observations in their original revision; only the new run
            // supports the first verified version. Human confirmation remains separate.
            val firstVerification = old.evidenceLevel == "unverified" &&
                item.evidenceLevel in setOf("observed_once", "observed_repeatedly")
            dao.evidence(old.id).filter { it.revision == old.revision &&
                (!firstVerification || it.kind == "user_confirmation" || it.sourceType.startsWith("human_")) &&
                (it.kind != "user_confirmation" || (item.title == old.title && item.summary == old.summary &&
                    MemoryBodies.sameReviewedContent(old.bodyJson, item.bodyJson)))
            }.forEach { evidence ->
                dao.putEvidence(evidence.copy(id = evidenceVersionId(item, evidence), revision = item.revision))
            }
        }
        dao.deleteFts(item.id)
        dao.putFts(MemoryFts(item.id, MemorySearch.index("${item.title} ${item.summary} ${item.appScope.orEmpty()} ${item.taskFamily.orEmpty()} ${item.bodyJson}")))
    }
    private fun evidenceVersionId(item: MemoryItem, evidence: MemoryEvidence): String = memoryEvidenceVersionId(item, evidence)
    private fun deleteItem(id: String) {
        val item = dao.get(id) ?: return
        dao.putBlock(MemoryBlock(MemoryBodies.identity(item)))
        dao.delete(id); dao.deleteFts(id); dao.deleteRevisions(id); dao.deleteItemEvidence(id); dao.deleteUsages(id)
    }
    private fun removeUnreferencedFiles() {
        val retained = dao.allEvidence().flatMap { listOfNotNull(it.imagePath, it.treePath) }.toSet()
        java.io.File(filesDir, "memory/evidence").listFiles()?.filter { it.isFile && it.absolutePath !in retained }?.forEach { it.delete() }
    }
    private fun expired(item: MemoryItem): Boolean {
        val expiry = JSONObject(item.bodyJson).optLong("valid_until", 0)
        return expiry > 0 && expiry <= System.currentTimeMillis()
    }
    private fun packageConflict(a: String?, b: String?): Boolean {
        val packageName = Regex("[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z][A-Za-z0-9_]*)+")
        return a != null && b != null && packageName.matches(a) && packageName.matches(b) && a != b
    }
    private fun isUserOwned(item: MemoryItem) = item.origin in setOf("explicit_user", "user_edit")
    private fun independentObservation(evidence: MemoryEvidence): Boolean = evidence.actor == "agent" &&
        evidence.sourceType == "agent_run" && evidence.observationType == "direct" &&
        evidence.author in setOf("model_observed", "user_confirmed")
    private fun <T> transaction(block: () -> T): T = database.runInTransaction(java.util.concurrent.Callable(block))
    private suspend fun <T> io(block: () -> T): T = withContext(Dispatchers.IO) { block() }
}
