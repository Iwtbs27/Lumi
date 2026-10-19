package ai.opencyvis.memory

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import ai.opencyvis.db.AppDatabase
import ai.opencyvis.execution.OperationJournal
import ai.opencyvis.llm.LLMClientInterface
import ai.opencyvis.llm.LlmRequestSchema
import ai.opencyvis.llm.LlmCallInfo
import ai.opencyvis.llm.LlmUsage
import ai.opencyvis.llm.ResponseParser
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@ConscryptMode(ConscryptMode.Mode.OFF)
class MemoryConsolidatorTest {
    @get:Rule val operationFiles = TemporaryFolder()
    private lateinit var database: AppDatabase
    private lateinit var repository: MemoryRepository

    @Before fun setup() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).allowMainThreadQueries().build()
        repository = MemoryRepository(context, database)
    }

    @After fun close() { database.close() }

    @Test fun acceptedFeedbackUpdatesExactOldMemoryEvenWithoutSearchHit() = runBlocking {
        val old = feedbackMemory("memory_feedback")
        val batch = extract(false, responses = { output -> listOf(feedbackProposal(output, old)) },
            onRequest = { messages ->
                val input = JSONObject(messages.last()["content"] as String)
                assertEquals(0, input.getJSONArray("related_memories").length())
                val review = input.getJSONArray("segments").getJSONObject(0)
                assertEquals("memory_feedback_review", review.getString("kind"))
                assertEquals(old.id, review.getJSONObject("memory").getString("id"))
                assertEquals(listOf(1, 2), review.getJSONArray("observations").let { observations ->
                    (0 until observations.length()).map { observations.getJSONObject(it).getInt("step") }
                })
            })
        assertEquals("active", batch.proposals.single().item.status)
        assertEquals(old.revision, batch.proposals.single().expectedRevision)
        repository.applyProposals("run:v1", batch.proposals, batch.id, batch.checkpoint)
        val updated = requireNotNull(repository.get(old.id))
        assertEquals(old.revision + 1, updated.revision)
        assertEquals("observed_once", updated.evidenceLevel)
        assertTrue(MemoryBodies.sameContent(old.bodyJson, updated.bodyJson))
        assertEquals(2, repository.evidence(old.id).size)
        assertTrue(repository.applyProposals("run:v1", batch.proposals, batch.id, batch.checkpoint).isEmpty())
        assertEquals(2, repository.evidence(old.id).size)
    }

    @Test fun automaticActionOutcomeDoesNotByItselfValidateMemory() = runBlocking {
        val old = feedbackMemory("previous_action_result")
        val batch = extract(false, responses = { output -> listOf(feedbackProposal(output, old)) })
        assertEquals("candidate", batch.proposals.single().item.status)
        assertEquals("unverified", batch.proposals.single().item.evidenceLevel)
    }

    @Test fun acceptedHostOperationReadbackSupportsTheSameModelStep() = runBlocking {
        val old = operationFeedback("host_verified")
        val batch = extract(false, responses = { output -> listOf(feedbackProposal(output, old).apply {
            getJSONArray("proposals").getJSONObject(0).put("source_steps", JSONArray(listOf(1))).put("outcome_step", 1)
        }) })
        assertEquals("active", batch.proposals.single().item.status)
        assertEquals("observed_once", batch.proposals.single().item.evidenceLevel)
    }

    @Test fun modelReportedVerificationDoesNotBypassTheLaterObservationRule() = runBlocking {
        val old = operationFeedback("model_observed")
        val batch = extract(false, responses = { output -> listOf(feedbackProposal(output, old).apply {
            getJSONArray("proposals").getJSONObject(0).put("source_steps", JSONArray(listOf(1))).put("outcome_step", 1)
        }) })
        assertEquals("candidate", batch.proposals.single().item.status)
    }

    private suspend fun operationFeedback(author: String): MemoryItem {
        val item = feedbackMemory("program_operation")
        val journal = OperationJournal(operationFiles.newFolder())
        val id = journal.begin("run", 1, "program-execution", "gui.set_text", JSONObject())
        journal.finish(id, "succeeded", JSONObject().put("verified", true).put("text", "Exact saved note"))
        val operation = journal.get(id)
        val usage = repository.runUsages("run").single { it.event == "outcome" }
        repository.recordUsage(usage.copy(evidenceStep = 1, detailsJson = JSONObject(usage.detailsJson)
            .put("evidence_author", author).put("operation_id", id).put("execution_id", "program-execution")
            .put("issued_sequence", operation.getLong("issued_sequence"))
            .put("finished_sequence", operation.getLong("finished_sequence"))
            .put("operation_evidence", operation).toString()))
        return item
    }

    private suspend fun feedbackMemory(source: String): MemoryItem {
        val item = MemoryItem(id = "old", title = "保存入口", summary = "确认保存后的内容", status = "active",
            revision = 2, bodyJson = MemoryBodies.fact("点击完成后可在阅读页确认内容保留", "app_knowledge"))
        // Deliberately omit FTS: accepted usage must bring the exact memory into consolidation.
        database.memoryDao().put(item)
        repository.recordUsage(MemoryUsage(memoryId = item.id, revision = item.revision, runId = "run",
            step = 1, event = "outcome", outcome = "success", evidenceStep = 2,
            detailsJson = JSONObject().put("source", source).put("evidence", "阅读页内容保留")
                .put("scope", "action").put("evidence_author", "model_observed")
                .put("independent_execution", true).toString()))
        repository.saveEvidence(MemoryEvidence(episodeId = "run", runId = "run", step = 2,
            text = "阅读页内容保留", author = "model_observed", actor = "agent"))
        return item
    }

    private fun feedbackProposal(output: JSONObject, item: MemoryItem): JSONObject = output.apply {
        getJSONArray("proposals").getJSONObject(0).put("target_id", item.id)
            .put("target_revision", item.revision).put("type", item.type).put("title", item.title)
            .put("summary", item.summary).put("body", JSONObject(item.bodyJson))
    }

    @Test fun finishClaimWithoutLaterObservationRemainsCandidate() = runBlocking {
        val batch = extract(confirmed = false)
        assertEquals("candidate", batch.proposals.single().item.status)
        assertEquals("unverified", batch.proposals.single().item.evidenceLevel)
        repository.applyProposals("run:v1", batch.proposals, batch.id, batch.checkpoint)
        assertEquals("candidate", repository.get(batch.proposals.single().item.id)?.status)
    }

    @Test fun laterConfirmedResultCreatesUsableObservedMethod() = runBlocking {
        val batch = extract(confirmed = true)
        assertEquals("active", batch.proposals.single().item.status)
        assertEquals("observed_once", batch.proposals.single().item.evidenceLevel)
        assertEquals(listOf(1, 2), batch.proposals.single().sourceSteps)
        repository.applyProposals("run:v1", batch.proposals, batch.id, batch.checkpoint)
        assertEquals("active", repository.get(batch.proposals.single().item.id)?.status)
        assertEquals(2, repository.evidence(batch.proposals.single().item.id).size)
    }

    @Test fun humanInterventionBetweenActionAndResultDoesNotActivateMemory() = runBlocking {
        val batch = extract(confirmed = true, humanIntervention = true)
        assertEquals("candidate", batch.proposals.single().item.status)
        assertEquals("unverified", batch.proposals.single().item.evidenceLevel)
    }

    @Test fun missingTitleIsRepairedOnceAndBothCallsAreCounted() = runBlocking {
        var requests = 0
        val batch = extract(true, responses = { valid ->
            val invalid = JSONObject(valid.toString())
            invalid.getJSONArray("proposals").getJSONObject(0).remove("title")
            listOf(invalid, valid)
        }, onRequest = { messages ->
            requests++
            if (requests == 2) assertTrue(messages.toString().contains("title"))
        })
        assertEquals(2, requests)
        assertEquals("保存笔记", batch.proposals.single().item.title)
        val checkpoint = JSONObject(batch.checkpoint)
        assertEquals(1, checkpoint.getInt("format_repairs"))
        assertEquals(2, checkpoint.getInt("consolidation_calls"))
        assertEquals(12446L, checkpoint.getLong("input_tokens"))
        assertEquals(4464L, checkpoint.getLong("output_tokens"))
        repository.applyProposals("run:v1", batch.proposals, batch.id, batch.checkpoint)
        assertEquals(1, repository.list().size)
    }

    @Test fun repeatedInvalidOutputKeepsDiagnosticsWithoutWritingIncompleteMemories() = runBlocking {
        var requests = 0
        try {
            extract(true, responses = { valid ->
                valid.getJSONArray("proposals").getJSONObject(0).remove("title")
                listOf(valid, valid)
            }, onRequest = { requests++ })
            fail("Invalid output must remain an explicit failure")
        } catch (error: IllegalArgumentException) {
            assertTrue(error.message.orEmpty().contains("proposals[0]"))
            assertTrue(error.message.orEmpty().contains("title"))
        }
        assertEquals(2, requests)
        assertTrue(repository.list().isEmpty())
        val checkpoint = JSONObject(requireNotNull(repository.getJob("run:v1")).checkpointJson)
        assertEquals(1, checkpoint.getInt("format_repairs"))
        assertEquals(2, checkpoint.getInt("consolidation_calls"))
        assertTrue(checkpoint.getString("last_validation_error").contains("title"))
        assertFalse(checkpoint.getJSONObject("last_response").getJSONArray("proposals").getJSONObject(0).has("title"))
    }

    @Test fun backgroundRetryDoesNotIssueAnotherFormatRepairForTheSameBatch() = runBlocking {
        var requests = 0
        repeat(2) {
            try {
                extract(true, responses = { valid ->
                    valid.getJSONArray("proposals").getJSONObject(0).remove("title")
                    listOf(valid, valid)
                }, onRequest = { requests++ })
                fail("Invalid output must remain an explicit failure")
            } catch (_: IllegalArgumentException) { }
        }
        assertEquals(2, requests)
        val checkpoint = JSONObject(requireNotNull(repository.getJob("run:v1")).checkpointJson)
        assertEquals(1, checkpoint.getInt("format_repairs"))
        assertTrue(repository.list().isEmpty())
    }

    @Test fun pendingBatchReusesSavedValidResponseWithoutPayingForAnotherCall() = runBlocking {
        var requests = 0
        val first = extract(true, onRequest = { requests++ })
        val resumed = extract(true, onRequest = { requests++ })
        assertEquals(1, requests)
        assertEquals(first.id, resumed.id)
        assertEquals(first.proposals.single().item.title, resumed.proposals.single().item.title)
        assertEquals(1, JSONObject(resumed.checkpoint).getInt("consolidation_calls"))
    }

    private suspend fun extract(confirmed: Boolean, humanIntervention: Boolean = false,
        responses: (JSONObject) -> List<JSONObject> = { listOf(it) },
        onRequest: (List<Map<String, Any>>) -> Unit = {}): MemoryConsolidator.Batch {
        for (step in 1..2) repository.saveEvidence(MemoryEvidence(
            episodeId = "run", runId = "run", step = step, text = "Observed step $step",
            actor = if (humanIntervention && step == 2) "mixed" else "agent",
            author = if (confirmed && step == 2) "model_observed" else "model_report"
        ))
        val first = JSONObject().put("step", 1).put("action", JSONObject().put("action_type", "tap"))
            .put("human_intervention_count", 0)
            .put("execution", JSONObject().put("execution_status", "executed"))
        val last = JSONObject().put("step", 2).put("human_intervention_count", if (humanIntervention) 1 else 0)
            .put("action", JSONObject()
            .put("action_type", "finish").put("thought", "任务完成"))
        if (confirmed) {
            first.put("effect", JSONObject().put("status", "confirmed_success")
                .put("evidence_step", 2).put("evidence", "已退出编辑态，标题与正文仍然存在"))
            last.put("previous_action_result", JSONObject().put("step", 1)
                .put("status", "confirmed_success").put("evidence", "已退出编辑态，标题与正文仍然存在"))
        }
        val job = MemoryJob(id = "run:v1", runId = "run", payloadJson = JSONObject()
            .put("instruction", "在笔记中保存内容").put("result_status", "unknown")
            .put("segments", JSONArray().put(first).put(last)).toString())
        repository.enqueueJob(job)
        val output = JSONObject("""{
            "episode_summary":"本次尝试保存笔记。", "conflicts":[], "proposals":[{
                "type":"procedure", "title":"保存笔记", "summary":"保存后确认退出编辑态且内容保留",
                "app_scope":"原子笔记", "task_family":"note", "source_steps":[1,2], "outcome_step":2,
                "reason":"本次保存经历", "body":{
                    "goal":"保存已输入的笔记", "parameters":[], "preconditions":["处于编辑页"],
                    "nodes":[{"node_id":"save","intent":"定位并点击完成入口","expected":"内容保留且退出编辑态","next":"","branches":[]}],
                    "completion":"内容保留且退出编辑态", "failure_notes":[], "aliases":["保存内容"]
                }
            }]
        }""")
        val outputs = responses(output)
        var requestIndex = 0
        val client = object : LLMClientInterface {
            override val lastCallInfo get() = if (requestIndex == 0) null else
                LlmCallInfo(emptyMap(), LlmUsage(6223, 2232), 1, usageComplete = true)
            override suspend fun chatWithTools(messages: List<Map<String, Any>>): Map<String, Any?> =
                error("Consolidation must not call phone_action")
            override suspend fun chatWithSchema(messages: List<Map<String, Any>>, schema: LlmRequestSchema): Map<String, Any?> {
                assertEquals("consolidate_memory", schema.tool.getJSONObject("function").getString("name"))
                assertTrue(messages.sumOf { (it["content"] as String).length } <= 20_000)
                onRequest(messages)
                return ResponseParser.jsonObjectToMap(outputs[requestIndex++])
            }
            override fun shutdown() {}
        }
        return requireNotNull(MemoryConsolidator(repository).run(requireNotNull(repository.getJob(job.id)), client))
    }
}
