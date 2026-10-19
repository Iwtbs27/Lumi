package ai.opencyvis.execution.gui

import android.Manifest
import android.app.UiAutomation
import android.content.ComponentName
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Point
import android.provider.Settings
import android.util.Log
import ai.opencyvis.R
import ai.opencyvis.accessibility.VdAccessibilityService
import ai.opencyvis.backend.BackendDetector
import ai.opencyvis.backend.DetectionResult
import ai.opencyvis.backend.PrivilegeBackend
import ai.opencyvis.debug.GuiNoteFixtureActivity
import ai.opencyvis.display.VirtualDisplayManager
import ai.opencyvis.input.InputInjector
import ai.opencyvis.task.TaskRepository
import ai.opencyvis.task.TaskStatus
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import java.io.File
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Real VD, accessibility and input backend on the dedicated device; no model or Engine is simulated. */
@RunWith(AndroidJUnit4::class)
class GuiBlockDeviceTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val output get() = File(context.getExternalFilesDir(null), "m4-gui-e2e").apply { mkdirs() }

    @Test fun parametersFillAndSaveThroughTheRealVirtualDisplay(): Unit = runBlocking {
        session("fill-save") { backend, display ->
            launch(backend, display)
            val gui = access(backend, display)
            val epoch = AtomicLong(7)
            val executor = GuiBlockExecutor(gui, epoch::get)
            val title = selector(display, R.id.gui_fixture_title)
            val body = selector(display, R.id.gui_fixture_body)
            val anchor = selector(display, R.id.gui_fixture_page, "Note editor")
            val values = listOf(
                "学习记录一" to "第一版完整正文：" + "保留中文、标点和全文读回。".repeat(12),
                "学习记录二" to "第二版参数：\n" + "Changed parameters remain exact. ".repeat(8),
            )
            values.forEachIndexed { round, (titleValue, bodyValue) ->
                val filled = executor.execute(GuiBlockRequest(anchor, listOf(
                    GuiBlockAction.SetText(title, titleValue), GuiBlockAction.SetText(body, bodyValue))), 7,
                    recorder("fill-save", "fill-$round"))
                assertEquals(filled.toString(), "completed", filled.status)
                assertEquals(listOf(titleValue, bodyValue), filled.operations.map { it.after!!.text })
                assertTrue(filled.operations.all { it.delivered && it.verified })
                val saved = executor.execute(GuiBlockRequest(anchor, listOf(GuiBlockAction.Tap(
                    selector(display, R.id.gui_fixture_save), GuiExpectedState(
                        selector(display, R.id.gui_fixture_status), text = "Saved")))), 7,
                    recorder("fill-save", "save-$round"))
                assertEquals(saved.toString(), "completed", saved.status)
                assertEquals("Saved", saved.operations.single().after!!.text)
                assertTrue(saved.operations.single().verified)
                assertEquals(titleValue, exactText(gui, selector(display, R.id.gui_fixture_saved_title)))
                assertEquals(bodyValue, exactText(gui, selector(display, R.id.gui_fixture_saved_body)))
                capture(display, "saved-$round")
            }
            val records = File(output, "fill-save.jsonl").readLines().map(::JSONObject)
            assertEquals(6, records.count { it.getString("phase") == "issued" })
            assertEquals(6, records.count { it.getString("phase") == "checked" && it.getBoolean("verified") })
        }
    }

    @Test fun changingPageInTheSameWindowStopsBeforeEditingTheBody(): Unit = runBlocking {
        session("page-change") { backend, display ->
            launch(backend, display, switchPage = true)
            val gui = access(backend, display)
            val anchor = selector(display, R.id.gui_fixture_page, "Note editor")
            val oldPage = gui.query(anchor).page
            val result = GuiBlockExecutor(gui) { 11L }.execute(GuiBlockRequest(anchor, listOf(
                GuiBlockAction.SetText(selector(display, R.id.gui_fixture_title), "Preview title"),
                GuiBlockAction.SetText(selector(display, R.id.gui_fixture_body), "Must not be written"))),
                11, recorder("page-change", "fill"))
            assertEquals(result.toString(), "partial", result.status)
            assertEquals(1, result.operations.size)
            assertTrue(result.operations.single().delivered)
            assertFalse(result.operations.single().verified)
            assertNotNull(result.reason)
            val preview = gui.query(selector(display, R.id.gui_fixture_page, "Preview"))
            assertEquals(oldPage, preview.page)
            assertEquals(1, preview.matches.size)
            assertEquals("Seed body", exactText(gui, selector(display, R.id.gui_fixture_body)))
            assertEquals(1, File(output, "page-change.jsonl").readLines().map(::JSONObject)
                .count { it.getString("phase") == "issued" })
            capture(display, "page-change")
        }
    }

    private fun selector(display: VirtualDisplayManager, id: Int, text: String? = null) =
        GuiSelector(display.displayId, context.packageName, context.resources.getResourceName(id), text)

    private fun access(backend: PrivilegeBackend, display: VirtualDisplayManager): GuiAccess {
        val injector = InputInjector(context, display.displayId, Point(display.width, display.height), backend)
        return AccessibilityGuiAccess { node ->
            val bounds = node.bounds
            injector.tap(((bounds.left + bounds.right) * 500.0 / display.width).toInt(),
                ((bounds.top + bounds.bottom) * 500.0 / display.height).toInt())
        }
    }

    private suspend fun exactText(gui: GuiAccess, selector: GuiSelector): String {
        val result = gui.query(selector)
        assertNull(result.toString(), result.reason)
        assertEquals(result.toString(), 1, result.matches.size)
        return requireNotNull(result.matches.single().text)
    }

    private fun recorder(name: String, stage: String): (Int, String, GuiOperationEvidence) -> Unit =
        { index, phase, evidence ->
            File(output, "$name.jsonl").appendText(JSONObject().put("stage", stage).put("index", index)
                .put("phase", phase).put("resource_id", evidence.action.selector.resourceId)
                .put("delivered", evidence.delivered).put("verified", evidence.verified)
                .put("before", evidence.before?.text).put("after", evidence.after?.text)
                .put("reason", evidence.reason).toString() + "\n")
        }

    private suspend fun launch(backend: PrivilegeBackend, display: VirtualDisplayManager, switchPage: Boolean = false) {
        // The existing shell launcher preserves Intent action, but does not forward extras.
        val action = if (switchPage) GuiNoteFixtureActivity.ACTION_SWITCH_PAGE_AFTER_TITLE else Intent.ACTION_MAIN
        val intent = Intent(context, GuiNoteFixtureActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_MULTIPLE_TASK)
            .setAction(action)
        assertTrue(backend.startActivityOnDisplay(intent.toUri(Intent.URI_INTENT_SCHEME), display.displayId))
        waitUntil { VdAccessibilityService.queryGui(selector(display, R.id.gui_fixture_page, "Note editor"))
            .matches.size == 1 }
        val receivedAction = VdAccessibilityService.queryGui(selector(display, R.id.gui_fixture_page, "Note editor"))
            .matches.single().contentDescription
        assertEquals("The real fixture must receive its requested test mode", action, receivedAction)
    }

    private fun capture(display: VirtualDisplayManager, name: String) {
        val frame = requireNotNull(display.captureFrame()) { "A real VD screenshot is required" }
        try {
            assertEquals(display.displayId, frame.displayId)
            assertTrue(frame.bitmap.width > 0 && frame.bitmap.height > 0)
            File(output, "$name.png").outputStream().use {
                assertTrue(frame.bitmap.compress(Bitmap.CompressFormat.PNG, 100, it))
            }
        } finally { frame.bitmap.recycle() }
    }

    private suspend fun session(name: String, block: suspend (PrivilegeBackend, VirtualDisplayManager) -> Unit) {
        check(TaskRepository(context).tasks.first().none {
            it.status == TaskStatus.QUEUED || it.status in TaskStatus.ACTIVE && it.guiHeld
        }) { "Use the dedicated device without active or queued tasks" }
        rebindAccessibility()
        val detected = BackendDetector.detect(context)
        check(detected is DetectionResult.Ready) { "The test device's real privilege backend must be connected: $detected" }
        val backend = detected.backend
        val display = VirtualDisplayManager(context, backend)
        File(output, "$name.jsonl").writeText("")
        try {
            assertTrue(backend.capabilities.toString(), backend.capabilities.canCreateVirtualDisplay && backend.capabilities.canInjectInput)
            assertTrue(withContext(Dispatchers.Main) { display.create() } > 0)
            // Match AgentEngine's startup prerequisite. The shell backend's asynchronous VD init
            // drains frames at 800 ms and moves this package's tasks back to display 0, then
            // enables the mirror 300 ms later. Its private mirrorReady flag has no Binder getter.
            delay(1200)
            block(backend, display)
        } catch (failure: Throwable) {
            Log.e("GuiBlockDeviceTest", name, failure)
            File(output, "$name-failure.txt").writeText(failure.stackTraceToString())
            throw failure
        } finally {
            withContext(NonCancellable) {
                try { finishFixtureActivities() } finally {
                    try { display.destroy() } finally { backend.destroy() }
                }
            }
        }
    }

    private suspend fun finishFixtureActivities() {
        val activities = withContext(Dispatchers.Main) {
            Stage.values().filter { it != Stage.DESTROYED }.flatMap {
                ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(it)
            }.filterIsInstance<GuiNoteFixtureActivity>().distinct().also { list -> list.forEach { it.finish() } }
        }
        withTimeout(10_000) {
            while (withContext(Dispatchers.Main) { activities.any { !it.isDestroyed } }) delay(100)
        }
    }

    private suspend fun rebindAccessibility() {
        val automation = InstrumentationRegistry.getInstrumentation()
            .getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES)
        val resolver = context.contentResolver
        val original = requireNotNull(Settings.Secure.getString(resolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES))
        val target = ComponentName(context, VdAccessibilityService::class.java)
        val entries = original.split(':')
        check(entries.any { ComponentName.unflattenFromString(it) == target }) {
            "Enable the real VdAccessibilityService before running this test"
        }
        val enabled = Settings.Secure.getInt(resolver, Settings.Secure.ACCESSIBILITY_ENABLED)
        automation.adoptShellPermissionIdentity(Manifest.permission.WRITE_SECURE_SETTINGS)
        try {
            check(Settings.Secure.putString(resolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
                entries.filter { ComponentName.unflattenFromString(it) != target }.joinToString(":")))
            delay(500)
        } finally {
            try {
                check(Settings.Secure.putString(resolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, original))
                check(Settings.Secure.putInt(resolver, Settings.Secure.ACCESSIBILITY_ENABLED, enabled))
            } finally { automation.dropShellPermissionIdentity() }
        }
        waitUntil { VdAccessibilityService.instance != null }
    }

    private suspend fun waitUntil(predicate: () -> Boolean) = withTimeout(30_000) {
        while (!predicate()) delay(100)
    }
}
