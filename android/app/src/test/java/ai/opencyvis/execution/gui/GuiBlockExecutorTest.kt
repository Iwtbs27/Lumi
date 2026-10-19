package ai.opencyvis.execution.gui

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class GuiBlockExecutorTest {
    private val page = GuiPageIdentity(8, "fixture.notes", 12)
    private fun selector(id: String, text: String? = null) = GuiSelector(8, "fixture.notes", "fixture.notes:id/$id", text)
    private val anchor = selector("page", "Note editor")
    private val title = selector("title")
    private val body = selector("body")
    private fun block(titleValue: String = "Title", bodyValue: String = "Body") = GuiBlockRequest(anchor,
        listOf(GuiBlockAction.SetText(title, titleValue), GuiBlockAction.SetText(body, bodyValue)))
    private fun node(id: String, text: String, editable: Boolean = true) = GuiNodeSnapshot(
        page, "fixture.notes:id/$id", if (editable) "android.widget.EditText" else "android.widget.TextView",
        text, null, GuiBounds(10, 20, 300, 100), true, true, editable, !editable, false, false)

    @Test fun selectorMatchesEverySuppliedFieldExactly() {
        val target = node("title", "Full title")
        val exact = title.copy(text = "Full title", className = "android.widget.EditText")
        assertTrue(exact.matches(target))
        assertFalse(exact.copy(displayId = 0).matches(target))
        assertFalse(exact.copy(packageName = "other.notes").matches(target))
        assertFalse(exact.copy(resourceId = "fixture.notes:id/body").matches(target))
        assertFalse(exact.copy(text = "Full").matches(target))
        assertFalse(exact.copy(className = "EditText").matches(target))
        assertFalse(exact.copy(contentDescription = "Title").matches(target))
    }

    @Test fun fillsReadsFullTextAndReusesTheBlockWithDifferentParameters() = runTest {
        val gui = FakeGui()
        val executor = GuiBlockExecutor(gui) { 5L }
        val original = "第一份记录\n" + "完整正文，不应被50字符截断。".repeat(10)
        val first = executor.execute(GuiBlockRequest(anchor, listOf(
            GuiBlockAction.SetText(title.copy(text = "Old title"), "First"), GuiBlockAction.SetText(body, original))), 5)
        assertEquals("completed", first.status)
        assertEquals(listOf("First", original), first.operations.map { it.after!!.text })
        assertTrue(first.operations.all { it.delivered && it.verified })

        gui.afterAction = { action ->
            if (action.selector == title) gui.nodes[2] = gui.nodes[2].copy(bounds = GuiBounds(30, 300, 350, 700))
        }
        val second = executor.execute(block("Second", "Changed parameters"), 5)
        assertEquals("completed", second.status)
        assertEquals("Changed parameters", gui.nodes[2].text)
        assertEquals(GuiBounds(30, 300, 350, 700), second.operations[1].before!!.bounds)
        assertEquals(4, gui.performed.size)
    }

    @Test fun ambiguousTargetReturnsToAgentWithoutSendingAnyAction() = runTest {
        val gui = FakeGui().apply { nodes += node("title", "Another title") }
        val result = GuiBlockExecutor(gui) { 5L }.execute(block(), 5)
        assertEquals("needs_agent", result.status)
        assertTrue(gui.performed.isEmpty())
        assertTrue(result.operations.none { it.delivered })
        assertNotNull(result.reason)
    }

    @Test fun successfulDeliveryWithWrongReadbackStopsBeforeTheNextField() = runTest {
        val gui = FakeGui().apply {
            afterAction = { nodes[1] = nodes[1].copy(text = "App rejected the supplied value") }
        }
        val result = GuiBlockExecutor(gui) { 5L }.execute(block(), 5)
        assertEquals("partial", result.status)
        assertEquals(1, gui.performed.size)
        assertTrue(result.operations.single().delivered)
        assertFalse(result.operations.single().verified)
        assertEquals("App rejected the supplied value", result.operations.single().after!!.text)
        assertEquals("Old body", gui.nodes[2].text)
    }

    @Test fun changingThePageInsideTheSameWindowStopsTheRemainingActions() = runTest {
        val gui = FakeGui().apply { afterAction = { nodes[0] = nodes[0].copy(text = "Preview") } }
        val result = GuiBlockExecutor(gui) { 5L }.execute(block(), 5)
        assertEquals("partial", result.status)
        assertEquals(1, gui.performed.size)
        assertEquals(page, result.currentPage)
        assertEquals("Old body", gui.nodes[2].text)
        assertNotNull(result.reason)
    }

    @Test fun aControlEpochChangeStopsTheRemainingActionsWithoutLosingTheIssuedEvidence() = runTest {
        var epoch = 5L
        val gui = FakeGui().apply { afterAction = { epoch++ } }
        val result = GuiBlockExecutor(gui) { epoch }.execute(block(), 5)
        assertEquals("partial", result.status)
        assertEquals(1, gui.performed.size)
        assertTrue(result.operations.single().delivered)
        assertEquals("Old body", gui.nodes[2].text)
        assertNotNull(result.reason)
    }

    @Test fun tappingRequiresAndChecksTheCurrentResultState() = runTest {
        val save = selector("save")
        val status = selector("status")
        val gui = FakeGui().apply {
            nodes += node("save", "Save", editable = false)
            nodes += node("status", "Unsaved", editable = false)
            afterAction = { if (it is GuiBlockAction.Tap) nodes[4] = nodes[4].copy(text = "Saved") }
        }
        val request = GuiBlockRequest(anchor, listOf(GuiBlockAction.SetText(title, "New title"),
            GuiBlockAction.Tap(save, GuiExpectedState(status, text = "Saved"))))
        val result = GuiBlockExecutor(gui) { 5L }.execute(request, 5)
        assertEquals("completed", result.status)
        assertEquals("Saved", result.operations.last().after!!.text)
        assertTrue(result.operations.last().verified)
    }

    @Test fun cancellationKeepsCheckedOperationsAndJournalsTheUnconfirmedIssuedOperation() = runTest {
        val base = FakeGui()
        val secondIssued = CompletableDeferred<Unit>()
        val gui = object : GuiAccess {
            override suspend fun query(selector: GuiSelector) = base.query(selector)
            override suspend fun perform(action: GuiBlockAction, expectedPage: GuiPageIdentity, isCurrent: () -> Boolean): GuiActionAttempt {
                val attempt = base.perform(action, expectedPage, isCurrent)
                if (base.performed.size == 2) { secondIssued.complete(Unit); awaitCancellation() }
                return attempt
            }
        }
        val journal = mutableListOf<Pair<String, GuiOperationEvidence>>()
        val job = launch { GuiBlockExecutor(gui) { 5L }.execute(block(), 5) { index, phase, evidence ->
            assertEquals(index, evidence.index)
            if (phase == "issued") assertEquals(index - 1, base.performed.size)
            journal += phase to evidence
        } }
        runCurrent()
        secondIssued.await()
        job.cancelAndJoin()
        assertTrue(job.isCancelled)
        assertEquals(listOf("issued", "checked", "issued", "unknown"), journal.map { it.first })
        assertTrue(journal[1].second.verified)
        assertEquals(2, journal.last().second.index)
        assertFalse(journal.last().second.verified)
        assertEquals("cancelled_before_confirmation", journal.last().second.reason)
    }

    @Test fun aSeparateSaveStageUsesOneActionAndStillChecksItsResult() = runTest {
        val gui = FakeGui().apply {
            nodes += node("save", "Save", editable = false)
            nodes += node("status", "Unsaved", editable = false)
            afterAction = { if (it is GuiBlockAction.Tap) nodes[4] = nodes[4].copy(text = "Saved") }
        }
        val executor = GuiBlockExecutor(gui) { 5L }
        assertEquals("completed", executor.execute(block(), 5).status)
        val phases = mutableListOf<String>()
        val result = executor.execute(GuiBlockRequest(anchor, listOf(GuiBlockAction.Tap(
            selector("save"), GuiExpectedState(selector("status"), text = "Saved")))), 5) { _, phase, _ ->
            phases += phase
        }
        assertEquals("completed", result.status)
        assertEquals(1, result.operations.size)
        assertEquals("Saved", result.operations.single().after!!.text)
        assertTrue(result.operations.single().verified)
        assertEquals(listOf("issued", "checked"), phases)
        assertEquals(3, gui.performed.size)
    }

    @Test fun aBlockCannotExpandBeyondThreeActions() = runTest {
        val gui = FakeGui()
        val invalid = GuiBlockRequest(anchor, List(4) { GuiBlockAction.SetText(title, "Unexpected") })
        val failure = runCatching { GuiBlockExecutor(gui) { 5L }.execute(invalid, 5) }.exceptionOrNull()
        assertTrue(failure is IllegalArgumentException)
        assertTrue(gui.performed.isEmpty())
    }

    private inner class FakeGui : GuiAccess {
        val nodes = mutableListOf(node("page", "Note editor", false), node("title", "Old title"), node("body", "Old body"))
        val performed = mutableListOf<GuiBlockAction>()
        var afterAction: (GuiBlockAction) -> Unit = {}

        override suspend fun query(selector: GuiSelector): GuiQueryResult = GuiQueryResult(page, nodes.filter {
            it.page.displayId == selector.displayId && it.page.packageName == selector.packageName &&
                (selector.resourceId == null || it.resourceId == selector.resourceId) &&
                (selector.text == null || it.text == selector.text)
        })

        override suspend fun perform(action: GuiBlockAction, expectedPage: GuiPageIdentity, isCurrent: () -> Boolean): GuiActionAttempt {
            if (!isCurrent()) return GuiActionAttempt(false, null, "Control changed")
            val target = query(action.selector).matches.single()
            check(target.page == expectedPage)
            performed += action
            if (action is GuiBlockAction.SetText) nodes[nodes.indexOf(target)] = target.copy(text = action.text)
            afterAction(action)
            return GuiActionAttempt(true, target)
        }
    }
}
