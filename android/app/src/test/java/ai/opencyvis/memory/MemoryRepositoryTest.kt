package ai.opencyvis.memory

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.work.Configuration
import androidx.work.WorkManager
import ai.opencyvis.db.AppDatabase
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@ConscryptMode(ConscryptMode.Mode.OFF)
class MemoryRepositoryTest {
    private lateinit var database: AppDatabase
    private lateinit var repository: MemoryRepository

    @Before fun setup() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).allowMainThreadQueries().build()
        repository = MemoryRepository(context, database)
    }
    @After fun close() { database.close() }

    @Test fun manualConfirmationSurvivesToggleWithoutClaimingRuntimeValidation() = runBlocking {
        val method = MemoryItem(type = "procedure", title = "Save note", summary = "Back saves the note",
            bodyJson = """{"goal":"save","nodes":[],"completion":"note visible","maturity":"observed"}""")
        database.memoryDao().put(method)
        repository.saveEvidence(MemoryEvidence(memoryId = method.id, revision = method.revision,
            episodeId = "candidate-run", runId = "candidate-run", step = 2,
            text = "Editor visible; saving has not been confirmed", author = "model_observed"))
        repository.setEnabled(method.id, true)
        assertFalse(repository.confirmedRevisions().containsKey(method.id))
        val active = requireNotNull(repository.get(method.id))
        repository.confirmAndEnable(method.id, active.revision)
        repository.setEnabled(method.id, false)
        repository.setEnabled(method.id, true)
        val confirmed = requireNotNull(repository.get(method.id))
        assertEquals(confirmed.revision, repository.confirmedRevisions()[method.id])
        assertEquals("active", confirmed.status)
        assertEquals("unverified", confirmed.evidenceLevel)
        assertNull(confirmed.lastVerifiedAt)
        assertEquals("observed", org.json.JSONObject(confirmed.bodyJson).getString("maturity"))
        assertTrue(repository.evidence(method.id).any {
            it.revision == confirmed.revision && it.kind == "user_confirmation" && it.author == "user_review"
        })

        repository.saveEvidence(MemoryEvidence(episodeId = "first-run", runId = "first-run", step = 2,
            text = "Saved note visible", actor = "agent"))
        repository.enqueueJob(MemoryJob(id = "first-run:v1", runId = "first-run"))
        repository.applyProposals("first-run:v1", listOf(MemoryProposal(
            confirmed.copy(evidenceLevel = "observed_once"), confirmed.revision, "Observed save",
            "first-run", listOf(2), supportScopeJson = """{"whole_procedure":true}""")))
        val observed = requireNotNull(repository.get(method.id))
        assertEquals("observed_once", observed.evidenceLevel)
        assertEquals("observed", org.json.JSONObject(observed.bodyJson).getString("maturity"))
        assertEquals(observed.revision, repository.confirmedRevisions()[method.id])
        repository.applyProposals("first-run:v1", listOf(MemoryProposal(observed, observed.revision,
            "Same run, later batch", "first-run", listOf(2),
            supportScopeJson = """{"whole_procedure":true}""")), batchId = "1")
        val sameRun = requireNotNull(repository.get(method.id))
        assertEquals("observed_once", sameRun.evidenceLevel)
        repository.saveEvidence(MemoryEvidence(episodeId = "second-run", runId = "second-run", step = 3,
            text = "Saved another note", actor = "agent"))
        repository.enqueueJob(MemoryJob(id = "second-run:v1", runId = "second-run"))
        repository.applyProposals("second-run:v1", listOf(MemoryProposal(sameRun, sameRun.revision,
            "Independent run", "second-run", listOf(3), supportScopeJson = """{"whole_procedure":true}""")))
        val repeated = requireNotNull(repository.get(method.id))
        assertEquals("observed_repeatedly", repeated.evidenceLevel)
        assertEquals("validated", org.json.JSONObject(repeated.bodyJson).getString("maturity"))
    }

    @Test fun confirmationIsForReviewedContentAndKeepsExistingRuntimeEvidence() = runBlocking {
        val item = MemoryItem(title = "Save note", summary = "Back saves", status = "active",
            evidenceLevel = "observed_once", lastVerifiedAt = 123L,
            bodyJson = MemoryBodies.fact("Back saves", "app_knowledge"))
        database.memoryDao().put(item)
        repository.confirmAndEnable(item.id, item.revision)
        val confirmed = requireNotNull(repository.get(item.id))
        assertEquals("observed_once", confirmed.evidenceLevel)
        assertEquals(123L, confirmed.lastVerifiedAt)
        repository.edit(item.id, item.title, "Check saves", MemoryBodies.fact("Check saves", "app_knowledge"), confirmed.revision)
        assertFalse(repository.confirmedRevisions().containsKey(item.id))
        assertTrue(repository.evidence(item.id).any { it.kind == "user_confirmation" })
    }

    @Test fun confirmationDoesNotCarryToChangedSummaryTitleOrAppScope() = runBlocking {
        for (field in listOf("summary", "title", "app")) {
            val item = MemoryItem(title = "Save note", summary = "Back saves", appScope = "Notes",
                bodyJson = MemoryBodies.fact("Back saves", "app_knowledge"))
            database.memoryDao().put(item)
            repository.confirmAndEnable(item.id, item.revision)
            val confirmed = requireNotNull(repository.get(item.id))
            repository.edit(item.id, if (field == "title") "Save any note" else item.title,
                if (field == "summary") "Back always saves" else item.summary, item.bodyJson,
                confirmed.revision, if (field == "app") "Other Notes" else item.appScope)
            assertFalse("Changed $field must be reviewed again", repository.confirmedRevisions().containsKey(item.id))
        }
    }

    @Test fun extractionCannotOverwriteUserPreferenceOrRepeatCommittedBatch() = runBlocking {
        val preference = repository.saveExplicit("笔记应用", "原子笔记")
        val job = MemoryJob(id = "run:v1", runId = "run")
        repository.enqueueJob(job)
        val proposal = MemoryProposal(preference.copy(summary = "其他应用"), preference.revision,
            "inferred", "run")
        assertEquals("conflict", repository.applyProposals(job.id, listOf(proposal)).single().status)
        assertEquals("原子笔记", repository.get(preference.id)?.summary)
        assertTrue(repository.applyProposals(job.id, listOf(proposal)).isEmpty())
    }

    @Test fun disabledAndDeletedMethodsCannotBeRegeneratedByBackground() = runBlocking {
        repository.saveEvidence(MemoryEvidence(episodeId = "run", runId = "run", step = 2, text = "Saved note visible"))
        val method = MemoryItem(type = "procedure", title = "保存笔记", summary = "确认保存结果", status = "active",
            evidenceLevel = "observed_once", bodyJson = """{"goal":"save","nodes":[],"completion":"visible"}""")
        suspend fun commit(id: String, item: MemoryItem, revision: Int? = null): MemoryWriteResult {
            repository.enqueueJob(MemoryJob(id = id, runId = "run"))
            return repository.applyProposals(id, listOf(MemoryProposal(item, revision, "observed", "run", listOf(2)))).single()
        }
        assertEquals("created", commit("first", method).status)
        repository.setEnabled(method.id, false)
        repository.setEnabled(method.id, true)
        val enabled = requireNotNull(repository.get(method.id))
        assertTrue(repository.evidence(method.id).any { it.revision == enabled.revision && it.step == 2 })
        assertTrue(repository.evidence(method.id).any { it.revision == 1 })
        repository.setEnabled(method.id, false)
        assertEquals("conflict", commit("disabled", method.copy(id = java.util.UUID.randomUUID().toString())).status)
        repository.delete(method.id)
        assertEquals("conflict", commit("deleted", method.copy(id = java.util.UUID.randomUUID().toString())).status)
        assertTrue(repository.search("保存笔记").isEmpty())
    }

    @Test fun chineseSearchAndSourceDeletionRespectIndependentMemoryLifecycle() = runBlocking {
        // Source deletion now cancels learning jobs as well as deleting their evidence.
        WorkManager.initialize(ApplicationProvider.getApplicationContext(), Configuration.Builder().build())
        repository.saveEpisode(MemoryEpisode(id = "run", conversationId = 12L, instruction = "比较无线鼠标"))
        repository.saveEvidence(MemoryEvidence(episodeId = "run", runId = "run", step = 2,
            text = "Note saved", actor = "agent"))
        val item = MemoryItem(title = "原子笔记保存入口", summary = "编辑完成后确认内容仍存在", status = "active",
            evidenceLevel = "observed_once", bodyJson = """{"domain":"app_knowledge","value":"完成按钮","conditions":[]}""")
        repository.enqueueJob(MemoryJob(id = "run:v1", runId = "run"))
        repository.applyProposals("run:v1", listOf(MemoryProposal(item, reason = "observation", sourceRunId = "run", sourceSteps = listOf(2))))
        assertEquals(item.id, repository.search("原子笔记").single().id)
        assertEquals("run", repository.searchEpisodes("无线鼠标").single().id)
        repository.deleteConversationSources(12L)
        assertNull(repository.get(item.id))
        assertNull(repository.getEpisode("run"))
        assertEquals("cancelled", repository.getJob("run:v1")?.status)
        repository.saveEpisode(MemoryEpisode(id = "run", conversationId = 12L, instruction = "late checkpoint"))
        assertNull(repository.getEpisode("run"))
    }
}
