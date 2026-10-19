package ai.opencyvis.learning

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.work.WorkManager
import ai.opencyvis.db.AppDatabase
import ai.opencyvis.memory.MemoryBodies
import ai.opencyvis.memory.MemoryEpisode
import ai.opencyvis.memory.MemoryEvidence
import ai.opencyvis.memory.MemoryItem
import ai.opencyvis.memory.MemoryJob
import ai.opencyvis.memory.MemoryProposal
import ai.opencyvis.memory.MemoryRepository
import app.cash.turbine.test
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.mockkStatic
import io.mockk.unmockkObject
import io.mockk.unmockkStatic
import io.mockk.verify
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@ConscryptMode(ConscryptMode.Mode.OFF)
class LearningRepositoryDeletionTest {
    private lateinit var context: Context
    private lateinit var database: AppDatabase
    private lateinit var repository: LearningRepository
    private lateinit var memory: MemoryRepository
    private lateinit var workManager: WorkManager
    private val active = MutableStateFlow<LearningSession?>(null)

    @Before fun setup() {
        context = ApplicationProvider.getApplicationContext()
        database = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries().build()
        repository = LearningRepository(context, database)
        memory = MemoryRepository(context, database)
        workManager = mockk(relaxed = true)
        mockkStatic(WorkManager::class)
        every { WorkManager.getInstance(any()) } returns workManager
        val recorder = mockk<LearningRecorder>()
        every { recorder.active } returns active
        mockkObject(LearningRecorder.Companion)
        every { LearningRecorder.get(any()) } returns recorder
    }

    @After fun close() {
        unmockkObject(LearningRecorder.Companion)
        unmockkStatic(WorkManager::class)
        database.close()
        File(context.filesDir, "learning").deleteRecursively()
        File(context.filesDir, "memory/evidence").deleteRecursively()
    }

    @Test fun savedMemoryAndItsEvidenceSurviveDeletingTheLearningRecord() = runBlocking {
        val session = session("saved-record", "ready")
        repository.put(session)
        val directory = LearningFiles(context).create(session.id)
        val image = File(directory, "frame-1.jpg").apply { writeText("retained image") }
        val tree = File(directory, "frame-1.txt").apply { writeText("retained tree") }
        val source = MemoryEvidence(id = "source", episodeId = session.id, runId = session.id,
            step = 1, text = JSONObject().put("image_path", image.absolutePath)
                .put("tree_path", tree.absolutePath).toString(), imagePath = image.absolutePath,
            treePath = tree.absolutePath, actor = "human", sourceType = "human_demo")
        memory.saveEvidence(source)
        val item = MemoryItem(id = "saved-memory", title = "保存入口", summary = "点完成保存",
            bodyJson = MemoryBodies.fact("点完成保存", "app_knowledge"))
        memory.commitLearningProposals(session.id, session.proposalsJson, listOf(MemoryProposal(
            item, reason = "用户确认", sourceRunId = session.id, sourceEvidenceIds = listOf(source.id))))
        memory.saveEpisode(MemoryEpisode(id = session.id, instruction = session.goal))
        val saved = requireNotNull(memory.get(item.id))

        repository.delete(session.id)

        assertNull(repository.get(session.id))
        assertEquals(saved, memory.get(item.id))
        val retained = memory.evidence(item.id).first { it.step == 1 }
        assertEquals("retained image", File(requireNotNull(retained.imagePath)).readText())
        assertEquals("retained tree", File(requireNotNull(retained.treePath)).readText())
        assertEquals(File(context.filesDir, "memory/evidence"), File(retained.imagePath!!).parentFile)
        assertEquals(retained.imagePath, JSONObject(retained.text).getString("image_path"))
        assertEquals(retained.treePath, JSONObject(retained.text).getString("tree_path"))
        assertFalse(memory.getEvidence(session.id).any { it.memoryId == null })
        assertNull(memory.getEpisode(session.id))
        assertFalse(directory.exists())
        repository.delete(session.id)
        assertEquals(saved, memory.get(item.id))
        assertTrue(File(retained.imagePath!!).isFile)
    }

    @Test fun unfinishedRecordsAndCaptureAreRemovedWithoutDeletingTheirParentTask() = runBlocking {
        val parent = MemoryEpisode(id = "parent-run", instruction = "原任务")
        memory.saveEpisode(parent)
        val parentLog = File(context.filesDir, "agent_runs/parent-run/run.json").apply {
            parentFile!!.mkdirs()
            writeText("original task log")
        }
        val unrelated = memory.saveExplicit("其它记忆", "保留")
        for (status in listOf("queued", "failed", "skipped", "discarded", "awaiting_task", "compiling")) {
            val session = session("record-$status", status).copy(parentRunId = parent.id)
            repository.put(session)
            val directory = LearningFiles(context).create(session.id)
            File(directory, "events.jsonl").writeText("{}\n")
            memory.saveEvidence(MemoryEvidence(episodeId = session.id, runId = session.id,
                step = 1, text = "未保存的原始记录"))
            memory.saveEpisode(MemoryEpisode(id = session.id, instruction = session.goal))
            memory.enqueueJob(MemoryJob(id = LearningJobs.jobId(session.id), runId = session.id,
                status = "running", kind = "learning_compile", payloadJson = "{\"draft\":true}"))

            repository.delete(session.id)

            assertNull(repository.get(session.id))
            assertNull(memory.getEpisode(session.id))
            assertTrue(memory.getEvidence(session.id).isEmpty())
            assertEquals("cancelled", memory.getJob(LearningJobs.jobId(session.id))?.status)
            assertEquals("{}", memory.getJob(LearningJobs.jobId(session.id))?.payloadJson)
            verify { workManager.cancelUniqueWork("memory:${LearningJobs.jobId(session.id)}") }
            assertFalse(directory.exists())
        }
        assertEquals(parent, memory.getEpisode(parent.id))
        assertEquals(unrelated, memory.get(unrelated.id))
        assertEquals("original task log", parentLog.readText())
    }

    @Test fun lateWorkerWritesCannotRestoreADeletedSessionOrEpisode() = runBlocking {
        val session = session("late-worker", "compiling")
        val job = MemoryJob(id = LearningJobs.jobId(session.id), runId = session.id, status = "running")
        repository.put(session)
        memory.enqueueJob(job)
        repository.observeAll().test {
            assertEquals(listOf(session.id), awaitItem().map { it.id })
            repository.delete(session.id)
            assertTrue(awaitItem().isEmpty())
            repository.put(session.copy(status = "ready"))
            memory.saveEpisode(MemoryEpisode(id = session.id, instruction = "迟到的整理结果"))
            memory.updateJob(job.copy(status = "completed"))
            cancelAndIgnoreRemainingEvents()
        }

        assertNull(repository.get(session.id))
        assertNull(memory.getEpisode(session.id))
        assertEquals("cancelled", memory.getJob(job.id)?.status)
        assertTrue(database.memoryDao().blocked("run:${session.id}") > 0)
    }

    @Test fun activeRecorderMustBeFinishedBeforeItsRecordCanBeDeleted() = runBlocking {
        val session = session("active-record", "paused")
        repository.put(session)
        val directory = LearningFiles(context).create(session.id)
        active.value = session

        val failure = runCatching { repository.delete(session.id) }.exceptionOrNull()

        assertTrue(failure is IllegalArgumentException)
        assertEquals("请先结束当前录制", failure?.message)
        assertEquals(session, repository.get(session.id))
        assertTrue(directory.exists())
        assertEquals(0, database.memoryDao().blocked("run:${session.id}"))
        verify(exactly = 0) { workManager.cancelUniqueWork(any()) }
    }

    private fun session(id: String, status: String) = LearningSession(id = id, mode = "demonstration",
        goal = "保存笔记", goalSource = "user", displayId = 0, status = status,
        proposalsJson = JSONArray().toString())
}
