package ai.opencyvis.team

import ai.opencyvis.config.ContextBudgetConfig
import ai.opencyvis.engine.ContextTextPage
import ai.opencyvis.llm.LLMClientInterface
import ai.opencyvis.llm.LlmCallInfo
import ai.opencyvis.llm.LlmCallPurpose
import ai.opencyvis.llm.LlmRequestSchema
import ai.opencyvis.llm.LlmUsage
import ai.opencyvis.llm.ModelBudgetExceededException
import ai.opencyvis.llm.ResponseParser
import java.io.File
import java.io.IOException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

@OptIn(ExperimentalCoroutinesApi::class)
class DelegationManagerTest {
    @get:Rule val temporary = TemporaryFolder()
    private val artifact = MaterialRef("artifact", "run-a:context:1", 2, "run-a", 3, "商品记录")
    private val evidence = MaterialRef("evidence", "run-a:context:2", 1, "run-a", 4, "原始价格页")
    private fun request(id: String = "child-1", goal: String = "核对价格") = DelegationRequest(
        id, 7, "root-run", "1:0", goal, listOf("保留原币种"), listOf(artifact, evidence))

    private fun manager(
        scope: CoroutineScope,
        directory: File = temporary.newFolder(),
        maxActive: Int = 1,
        maxSteps: Int = 8,
        revision: () -> String = { "1:0" },
        client: (DelegationRequest) -> LLMClientInterface,
        reader: suspend (DelegationRequest, MaterialRef, Int, Int) -> MaterialPage = { _, ref, offset, limit ->
            val page = ContextTextPage.from(ref.reference, "价格129.90 CNY", offset, limit)
            MaterialPage(ref, page.text, page.offset, page.nextOffset, page.totalChars)
        },
    ) = DelegationManager(directory, 7, "root-run", scope, maxActive, maxSteps, revision, client, reader)

    @Test fun childAutonomouslyReadsMoreMaterialAndReturnsAttributedFactsWithoutParentAcceptance() = runTest {
        val client = Replies(listOf(read(artifact, 0, 2), read(artifact, 2, 30), read(evidence),
            submit("completed", listOf(artifact, evidence))))
        val reads = mutableListOf<Pair<MaterialRef, Int>>()
        val manager = manager(backgroundScope, client = { client }, reader = { _, ref, offset, limit ->
            reads += ref to offset
            val page = ContextTextPage.from(ref.reference, "价格129.90 CNY", offset, limit)
            MaterialPage(ref, page.text, page.offset, page.nextOffset, page.totalChars,
                imageBase64 = if (ref.kind == "evidence") "AA==" else null)
        })
        manager.initialize()
        manager.delegate(request())
        runCurrent()

        val result = requireNotNull(manager.get("child-1"))
        assertEquals(DelegationStatus.COMPLETED, result.status)
        assertEquals("pending", result.decision)
        assertEquals(listOf(artifact to 0, artifact to 2, evidence to 0), reads)
        assertEquals(listOf(artifact, evidence), result.result!!.facts.single().refs)
        assertEquals(4, result.usage.calls)
        assertEquals(4, result.usage.unknownUsageAttempts)
        assertTrue(result.reads.last().includedImage)
        assertTrue(client.inputs.last().toString().contains("data:image/jpeg;base64,AA=="))
        assertEquals(1, client.schemas.map { it.tool.toString() }.distinct().size)
        assertTrue(client.closed)
        manager.close()
    }

    @Test fun citationToUnreadEvidenceIsRejectedUntilTheChildActuallyReadsIt() = runTest {
        val client = Replies(listOf(submit("completed", listOf(evidence)), read(evidence),
            submit("completed", listOf(evidence))))
        val manager = manager(backgroundScope, client = { client })
        manager.initialize()
        manager.delegate(request())
        runCurrent()

        val record = requireNotNull(manager.get("child-1"))
        assertEquals(3, client.inputs.size)
        assertEquals(DelegationStatus.COMPLETED, record.status)
        assertEquals(listOf(evidence), record.reads.map { it.reference })
        assertTrue(record.events.any { it.type == "tool_error" })
        manager.close()
    }

    @Test fun missingGuiMaterialReturnsCollectionRequestAndDoesNotCreateAnExecutor() = runTest {
        val client = Replies(listOf(submit("needs_evidence", emptyList(), listOf("缺少运费"), listOf("请父任务打开运费说明页"))))
        val manager = manager(backgroundScope, client = { client }, reader = { _, _, _, _ -> error("No read requested") })
        manager.initialize()
        manager.delegate(request())
        runCurrent()

        val record = requireNotNull(manager.get("child-1"))
        assertEquals(DelegationStatus.NEEDS_EVIDENCE, record.status)
        assertEquals(listOf("请父任务打开运费说明页"), record.result!!.collectionRequests)
        assertEquals("pending", record.decision)
        assertTrue(record.reads.isEmpty())
        manager.close()
    }

    @Test fun identicalDelegationIsReusedAndOnlyTwoChildrenRunAtOnce() = runTest {
        val clients = mutableMapOf<String, WaitingClient>()
        val manager = manager(backgroundScope, maxActive = 2, client = { request ->
            WaitingClient().also { clients[request.childId] = it }
        })
        manager.initialize()
        manager.delegate(request("one", "核对A"))
        val duplicate = manager.delegate(request("duplicate", "核对A"))
        manager.delegate(request("two", "核对B"))
        manager.delegate(request("three", "核对C"))
        runCurrent()
        assertEquals("one", duplicate.request.childId)
        assertEquals(setOf("one", "two"), clients.keys)
        assertEquals(DelegationStatus.QUEUED, manager.get("three")!!.status)

        manager.cancel("one", "父任务不再需要A")
        runCurrent()

        assertTrue(clients.getValue("one").closed)
        assertEquals(1, clients.getValue("one").cancels)
        assertEquals(0, clients.getValue("two").cancels)
        assertFalse(clients.getValue("two").closed)
        assertEquals(setOf("one", "two", "three"), clients.keys)
        manager.close()
        assertTrue(clients.values.all { it.closed })
    }

    @Test fun parentCloseWaitsForActualChildExitAndNeverAcceptsLateCompletion() = runTest {
        val exited = CompletableDeferred<Unit>()
        val client = WaitingClient(exited)
        val manager = manager(backgroundScope, client = { client })
        manager.initialize()
        manager.delegate(request())
        runCurrent()
        val closing = async { manager.close("parent_cancelled") }
        runCurrent()
        try {
            assertFalse(closing.isCompleted)
            assertFalse(client.closed)
            exited.complete(Unit)
            closing.await()
            assertTrue(client.closed)
            assertEquals(DelegationStatus.CANCELLED, manager.get("child-1")!!.status)
            assertNull(manager.get("child-1")!!.result)
            assertTrue(manager.get("child-1")!!.events.any { it.type == "model_response" })
            assertTrue(runCatching { manager.delegate(request("late")) }.isFailure)
        } finally { exited.complete(Unit) }
    }

    @Test fun cancelledWorkCanBeDelegatedAgainWithANewChildIdentity() = runTest {
        val clients = mutableListOf<WaitingClient>()
        val manager = manager(backgroundScope, client = { WaitingClient().also { clients += it } })
        manager.initialize()
        manager.delegate(request("original"))
        runCurrent()
        manager.cancel("original", "父任务暂时收回")

        val retry = manager.delegate(request("retry"))
        runCurrent()

        assertEquals("retry", retry.request.childId)
        assertEquals(2, clients.size)
        assertEquals(DelegationStatus.CANCELLED, manager.get("original")!!.status)
        manager.close()
    }

    @Test fun parentReviewRequiresReasonAndGoalChangesMakeResultsStale() = runTest {
        var revision = "1:0"
        val client = Replies(listOf(read(evidence), submit("completed", listOf(evidence))))
        val manager = manager(backgroundScope, revision = { revision }, client = { client })
        manager.initialize()
        manager.delegate(request())
        runCurrent()
        assertTrue(runCatching { manager.review("child-1", true, " ") }.isFailure)
        val accepted = manager.review("child-1", true, "已核对原始价格与币种")
        assertEquals("accepted", accepted.decision)
        assertEquals("已核对原始价格与币种", accepted.reviewReason)

        revision = "2:1"
        manager.refreshApplicability()

        assertTrue(manager.get("child-1")!!.stale)
        assertTrue(runCatching { manager.review("child-1", true, "旧计划结果") }.isFailure)
        assertEquals("rejected", manager.review("child-1", false, "用户已改目标").decision)
        assertTrue(manager.readResult("child-1").text.contains("129.90"))
        manager.close()
    }

    @Test fun changedGoalDuringExecutionArchivesTheResultAsStale() = runTest {
        var revision = "1:0"
        val gate = CompletableDeferred<Unit>()
        val client = Replies(listOf(read(evidence), submit("completed", listOf(evidence))))
        val manager = manager(backgroundScope, revision = { revision }, client = { client }, reader = { _, ref, _, _ ->
            gate.await()
            MaterialPage(ref, "129.90 CNY", 0, null, 10)
        })
        manager.initialize()
        manager.delegate(request())
        runCurrent()
        revision = "2:1"
        gate.complete(Unit)
        runCurrent()
        assertTrue(manager.get("child-1")!!.stale)
        assertEquals("pending", manager.get("child-1")!!.decision)
        manager.close()
    }

    @Test fun reopeningArchiveInterruptsInflightChildrenAndKeepsCompletedResultReadable() = runTest {
        val directory = temporary.newFolder("original")
        val completed = Replies(listOf(read(evidence), submit("completed", listOf(evidence))))
        val manager = manager(backgroundScope, directory, maxActive = 2, client = {
            if (it.childId == "done") completed else WaitingClient()
        })
        manager.initialize()
        manager.delegate(request("done", "A"))
        manager.delegate(request("busy", "B"))
        runCurrent()
        val snapshot = temporary.newFolder("restart")
        directory.listFiles().orEmpty().filter { it.isFile }.forEach { it.copyTo(File(snapshot, it.name)) }
        manager.close()
        val restarted = this@DelegationManagerTest.manager(backgroundScope, snapshot,
            client = { error("Recovery must not restart a child") })
        restarted.initialize()

        assertEquals(DelegationStatus.INTERRUPTED, restarted.get("busy")!!.status)
        assertEquals(DelegationStatus.COMPLETED, restarted.get("done")!!.status)
        assertEquals(evidence, restarted.get("done")!!.result!!.facts.single().refs.single())
        val first = restarted.readResult("done", 0, 23)
        assertEquals(23, first.text.length)
        assertNotNull(first.nextOffset)
        assertEquals("pending", restarted.get("done")!!.decision)
        restarted.close()
    }

    @Test fun invalidGuiActionCannotEscapeTheBoundedReadonlyLoop() = runTest {
        val client = Replies(List(2) { mapOf("action_type" to "tap", "x" to 1, "y" to 2) })
        val manager = manager(backgroundScope, maxSteps = 2, client = { client })
        manager.initialize()
        manager.delegate(request())
        runCurrent()
        val record = manager.get("child-1")!!
        assertEquals(DelegationStatus.FAILED, record.status)
        assertEquals(2, client.inputs.size)
        assertNotNull(record.error)
        assertTrue(record.reads.isEmpty())
        manager.close()
    }

    @Test fun explicitContextBudgetStopsOversizedInputBeforeCallingTheModel() = runTest {
        val client = Replies(emptyList(), ContextBudgetConfig(128000, 16000, workingInputLimitTokens = 10))
        val manager = manager(backgroundScope, client = { client })
        manager.initialize()
        manager.delegate(request())
        runCurrent()
        assertEquals(DelegationStatus.FAILED, manager.get("child-1")!!.status)
        assertTrue(client.inputs.isEmpty())
        assertTrue(client.closed)
        manager.close()
    }

    @Test fun foreignOwnerAndChangedReferenceVersionNeverReachTheReader() = runTest {
        val client = Replies(listOf(read(evidence).toMutableMap().apply { put("version", 99) }))
        var reads = 0
        val manager = manager(backgroundScope, maxSteps = 1, client = { client }, reader = { _, _, _, _ ->
            reads++; error("Version must be checked before the host reader")
        })
        manager.initialize()
        assertTrue(runCatching { manager.delegate(request().copy(rootTaskId = 8)) }.isFailure)
        manager.delegate(request())
        runCurrent()
        assertEquals(0, reads)
        assertEquals(DelegationStatus.FAILED, manager.get("child-1")!!.status)
        manager.close()
    }

    @Test fun explicitSourceReplacementMarksTheArchivedResultStaleWithAReason() = runTest {
        val client = Replies(listOf(read(evidence), submit("completed", listOf(evidence))))
        val manager = manager(backgroundScope, client = { client })
        manager.initialize()
        manager.delegate(request())
        runCurrent()

        val stale = manager.markStale("child-1", "父任务确认该产物已有替代版本")

        assertTrue(stale.stale)
        assertNotNull(stale.result)
        assertEquals("父任务确认该产物已有替代版本", stale.events.last().data["reason"])
        assertTrue(runCatching { manager.review("child-1", true, "试图采用旧版本") }.isFailure)
        manager.close()
    }

    @Test fun completeAndPartialUsageStayDistinctAndEveryCallUsesTheChildPurposeAndOutputLimit() = runTest {
        val client = Replies(listOf(read(evidence), submit("completed", listOf(evidence))),
            ContextBudgetConfig(32000, 512), listOf(
                listOf(LlmCallInfo(emptyMap(), LlmUsage(10, 2), 1, usageComplete = true)),
                listOf(LlmCallInfo(emptyMap(), LlmUsage(4, null), 1, usageComplete = false))))
        val manager = manager(backgroundScope, client = { client })
        manager.initialize()
        manager.delegate(request())
        runCurrent()

        val usage = manager.get("child-1")!!.usage
        assertEquals(2, usage.attempts)
        assertEquals(14L, usage.knownInputTokens)
        assertEquals(2L, usage.knownOutputTokens)
        assertEquals(1, usage.unknownUsageAttempts)
        assertTrue(client.schemas.all { it.purpose == LlmCallPurpose.CHILD_ANALYSIS && it.maxOutputTokens == 512 })
        manager.close()
    }

    @Test fun actualRequestsIncludingRetryFailureAndCancellationAreArchivedSeparatelyFromEvents() = runTest {
        for (outcome in listOf("completed", "failed", "cancelled")) {
            val directory = temporary.newFolder("requests-$outcome")
            val sentRequests = mutableListOf<Map<String, Any?>>()
            val client = object : LLMClientInterface {
                var calls = 0
                val attempts = mutableListOf<LlmCallInfo>()
                override val callAttempts get() = attempts.toList()
                override suspend fun chatWithTools(messages: List<Map<String, Any>>): Map<String, Any?> = error("Use the child schema")
                override suspend fun chatWithSchema(messages: List<Map<String, Any>>, schema: LlmRequestSchema): Map<String, Any?> {
                    calls++
                    attempts.clear()
                    repeat(if (calls == 1) 1 else 2) { attempt ->
                        val payload = ResponseParser.jsonObjectToMap(JSONObject(mapOf(
                            "model" to "local-fixture", "messages" to messages,
                            "tools" to listOf(schema.tool), "max_tokens" to (512 * (attempt + 1)))))
                        sentRequests += payload
                        attempts += LlmCallInfo(payload, null, attempt + 1,
                            reservationId = sentRequests.size.toLong(), ownerId = "child-1", purpose = LlmCallPurpose.CHILD_ANALYSIS)
                    }
                    if (calls == 1) return read(evidence)
                    return when (outcome) {
                        "completed" -> submit("completed", listOf(evidence))
                        "failed" -> throw IOException("Local attempt failed")
                        else -> awaitCancellation()
                    }
                }
                override fun shutdown() = Unit
            }
            val manager = manager(backgroundScope, directory, client = { client }, reader = { _, ref, offset, _ ->
                MaterialPage(ref, "129.90 CNY", offset, null, 10, imageBase64 = "AA==")
            })
            manager.initialize()
            manager.delegate(request())
            runCurrent()
            if (outcome == "cancelled") manager.cancel("child-1", "User cancelled this check")
            val record = manager.get("child-1")!!
            assertEquals(outcome, record.status)
            val requests = record.events.filter { it.type == "call.request" }
            assertEquals("Every actual attempt must retain its exact request", 3, requests.size)
            requests.forEachIndexed { index, event ->
                assertFalse(event.data.containsKey("request"))
                val reference = event.data["request_ref"] as String
                val stored = JSONObject(File(directory, reference).readText(Charsets.UTF_8))
                assertTrue(stored.similar(JSONObject(sentRequests[index])))
            }
            assertEquals(3, requests.map { it.data["request_ref"] }.distinct().size)
            val usageAttempts = record.events.filter { it.type == "usage_recorded" }.flatMap {
                it.data["call_attempts"] as List<*>
            }.map { it as Map<*, *> }
            assertEquals(requests.map { it.data["request_ref"] }, usageAttempts.map { it["request_ref"] })
            assertTrue(usageAttempts.all { it["owner_id"] == "child-1" && it["purpose"] == "CHILD_ANALYSIS" })
            assertFalse(File(directory, "delegations.json").readText().contains("data:image/"))
            assertEquals(3, DelegationArchive(directory, 7).load().single().events.count { it.type == "call.request" })
            manager.close()
        }
    }

    @Test fun budgetRejectionBeforeSendingDoesNotCountAsAnAttemptOrUnknownUsage() = runTest {
        val client = object : LLMClientInterface {
            override suspend fun chatWithTools(messages: List<Map<String, Any>>): Map<String, Any?> = error("Use the child schema")
            override suspend fun chatWithSchema(messages: List<Map<String, Any>>, schema: LlmRequestSchema): Map<String, Any?> =
                throw ModelBudgetExceededException("No remaining task budget")
            override fun shutdown() = Unit
        }
        val manager = manager(backgroundScope, client = { client })
        manager.initialize()
        manager.delegate(request())
        runCurrent()
        val record = manager.get("child-1")!!
        assertEquals(DelegationStatus.FAILED, record.status)
        assertEquals(1, record.usage.calls)
        assertEquals(0, record.usage.attempts)
        assertEquals(0, record.usage.unknownUsageAttempts)
        assertFalse(record.events.any { it.type == "call.request" })
        manager.close()
    }

    private class Replies(
        private val responses: List<Map<String, Any?>>,
        override val contextBudget: ContextBudgetConfig? = null,
        private val attemptsByCall: List<List<LlmCallInfo>> = responses.map { listOf(LlmCallInfo(emptyMap(), null, 1)) },
    ) : LLMClientInterface {
        val inputs = mutableListOf<List<Map<String, Any>>>()
        val schemas = mutableListOf<LlmRequestSchema>()
        var closed = false
        override val callAttempts: List<LlmCallInfo> get() = attemptsByCall.getOrNull(inputs.lastIndex).orEmpty()
        override suspend fun chatWithTools(messages: List<Map<String, Any>>): Map<String, Any?> = error("Child must use its own schema")
        override suspend fun chatWithSchema(messages: List<Map<String, Any>>, schema: LlmRequestSchema): Map<String, Any?> {
            inputs += messages.toList()
            schemas += schema
            return responses[inputs.lastIndex]
        }
        override fun shutdown() { closed = true }
    }

    private class WaitingClient(private val exitGate: CompletableDeferred<Unit>? = null) : LLMClientInterface {
        var cancels = 0
        var closed = false
        override suspend fun chatWithTools(messages: List<Map<String, Any>>): Map<String, Any?> = error("Unexpected phone request")
        override suspend fun chatWithSchema(messages: List<Map<String, Any>>, schema: LlmRequestSchema): Map<String, Any?> {
            try { awaitCancellation() }
            catch (cancelled: CancellationException) {
                if (exitGate == null) throw cancelled
                withContext(NonCancellable) { exitGate.await() }
                return submit("needs_evidence", emptyList(), listOf("late result"), listOf("collect later"))
            }
        }
        override fun cancelRequests() { cancels++ }
        override fun shutdown() { closed = true }
    }

    private companion object {
        fun read(ref: MaterialRef, offset: Int = 0, limit: Int = 4800): Map<String, Any?> = mapOf(
            "action_type" to "read_${ref.kind}", "reference" to ref.reference, "version" to ref.version,
            "offset" to offset, "limit" to limit)
        fun submit(status: String, refs: List<MaterialRef>, gaps: List<String> = emptyList(),
            requests: List<String> = emptyList()): Map<String, Any?> = mapOf(
            "action_type" to "submit_result", "status" to status,
            "facts" to if (refs.isEmpty()) emptyList<Any>() else listOf(mapOf("text" to "价格129.90 CNY",
                "refs" to refs.map { mapOf("reference" to it.reference, "version" to it.version) })),
            "gaps" to gaps, "collection_requests" to requests)
    }
}
