package ai.opencyvis.execution

import android.app.UiAutomation
import android.content.Intent
import android.os.Build
import android.os.SystemClock
import android.provider.DocumentsContract
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Configurator
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.UiObject2
import androidx.test.uiautomator.UiScrollable
import androidx.test.uiautomator.UiSelector
import androidx.test.uiautomator.Until
import ai.opencyvis.R
import ai.opencyvis.SettingsActivity
import ai.opencyvis.SettingsFragment
import java.io.File
import java.util.regex.Pattern
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Reproduces the real picker return path. Only raw coordinate touch can satisfy the assertion. */
@RunWith(AndroidJUnit4::class)
class ExecutionAccessDeviceTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext

    @Test fun taskFoldersRemainTouchableAfterRealSystemPickerReturns() {
        val configurator = Configurator.getInstance()
        val previousFlags = configurator.getUiAutomationFlags()
        configurator.setUiAutomationFlags(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES)
        val device = UiDevice.getInstance(instrumentation)
        val automation = instrumentation.getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES)
        val output = File(context.getExternalFilesDir(null), "m4-settings-e2e/picker-${System.currentTimeMillis()}").apply { check(mkdirs()) }
        val diagnostic = JSONObject().put("test_tree_id", TEST_TREE_ID)
        var scenario: ActivityScenario<SettingsActivity>? = null
        try {
            releaseTestGrant()
            val settings = ActivityScenario.launch(SettingsActivity::class.java)
            scenario = settings
            val folders = context.getString(R.string.pref_execution_files)
            val addFolder = context.getString(R.string.pref_execution_add_folder)
            val firstRow = folderTitle(device, folders)
            capture(device, output, "before-picker")
            tap(device, firstRow)
            tap(device, requireNotNull(device.wait(Until.findObject(By.text(addFolder)), WAIT_MS)))
            assertTrue("System DocumentsUI did not open", waitUntil {
                device.currentPackageName?.endsWith(".documentsui") == true
            })
            val pickerPackage = requireNotNull(device.currentPackageName)
            enterTestDirectory(device, pickerPackage)
            capture(device, output, "picker-directory")
            tap(device, requireNotNull(device.wait(Until.findObject(By.text(Pattern.compile("(?i)use this folder"))), WAIT_MS)))
            tap(device, requireNotNull(device.wait(Until.findObject(By.text(Pattern.compile("(?i)allow"))), WAIT_MS)))
            assertTrue("Specific fixture directory was not persistently authorized", waitUntil { testGrantExists() })
            assertTrue(device.wait(Until.hasObject(By.pkg(context.packageName)), WAIT_MS))
            val treeCount = context.contentResolver.persistedUriPermissions.count { DocumentsContract.isTreeUri(it.uri) }
            val summary = context.getString(R.string.pref_execution_files_count, treeCount)
            assertTrue("Settings did not display the updated grant count", device.wait(Until.hasObject(By.text(summary)), WAIT_MS))
            diagnostic.put("summary", summary).put("picker_package", pickerPackage)
            capture(device, output, "after-picker")
            settings.onActivity { activity ->
                val fragment = activity.supportFragmentManager.findFragmentById(R.id.settings_container) as SettingsFragment
                diagnostic.put("window_has_focus", activity.hasWindowFocus()).put("window_flags", activity.window.attributes.flags)
                    .put("list_scroll_state", fragment.listView.scrollState).put("list_animating", fragment.listView.isAnimating)
                    .put("activity_display_id", activity.display?.displayId)
            }
            val tapBounds = folderTitle(device, folders).visibleBounds
            diagnostic.put("touch_bounds", tapBounds.toShortString())
            diagnostic.put("touch_injected", device.click(tapBounds.centerX(), tapBounds.centerY()))
            val touchOpened = device.wait(Until.hasObject(By.text(addFolder)), 5_000)
            diagnostic.put("touch_opened_folder_dialog", touchOpened)
            capture(device, output, "after-second-touch")
            if (!touchOpened) {
                // Diagnostic only: never turn a failed touch assertion into a passing test.
                var node = automation.rootInActiveWindow?.findAccessibilityNodeInfosByText(folders)
                    ?.firstOrNull { it.isVisibleToUser && it.text?.toString() == folders }
                while (node != null && !node.isClickable) node = node.parent
                diagnostic.put("action_click_sent", node?.performAction(AccessibilityNodeInfo.ACTION_CLICK) == true)
                diagnostic.put("action_click_opened_folder_dialog", device.wait(Until.hasObject(By.text(addFolder)), 3_000))
                capture(device, output, "after-diagnostic-action-click")
            } else {
                assertTrue("Granted fixture folder is not shown in the dialog", device.wait(Until.hasObject(By.text(TEST_TREE_ID)), WAIT_MS))
                tap(device, requireNotNull(device.findObject(By.text(TEST_TREE_ID))))
                assertTrue(device.wait(Until.hasObject(By.text(context.getString(R.string.pref_execution_remove_folder))), WAIT_MS))
                tap(device, requireNotNull(device.findObject(By.res("android:id/button1"))))
                assertTrue("Fixture folder grant was not removed", waitUntil { !testGrantExists() })
                diagnostic.put("removed_through_ui", true)
            }
            File(output, "result.json").writeText(diagnostic.toString(2))
            assertTrue("Touch stopped working after picker return; diagnostics: $diagnostic", touchOpened)
        } catch (failure: Throwable) {
            diagnostic.put("failure", failure.message ?: failure.javaClass.simpleName)
            File(output, "result.json").writeText(diagnostic.toString(2))
            throw failure
        } finally {
            try {
                // Includes the failing-touch path; never revoke unrelated persisted directories.
                releaseTestGrant()
                diagnostic.put("fixture_grant_remaining", testGrantExists())
                File(output, "result.json").writeText(diagnostic.toString(2))
                scenario?.close()
            } finally { configurator.setUiAutomationFlags(previousFlags) }
        }
    }

    private fun folderTitle(device: UiDevice, title: String): UiObject2 {
        assertTrue(UiScrollable(UiSelector().resourceId("${context.packageName}:id/recycler_view"))
            .scrollIntoView(UiSelector().text(title)))
        return requireNotNull(device.wait(Until.findObject(By.text(title)), WAIT_MS))
    }

    private fun enterTestDirectory(device: UiDevice, pickerPackage: String) {
        fun inTestDirectory(): Boolean = device.findObject(By.res(pickerPackage, "toolbar"))
            ?.findObject(By.text(TEST_FOLDER)) != null
        if (inTestDirectory()) return
        // On the dedicated AVD the picker either remembers the fixture directory, its Documents
        // parent, or internal storage. All navigation remains real coordinate touch.
        if (!device.hasObject(By.text(TEST_FOLDER)) && !device.hasObject(By.text("Documents"))) {
            tap(device, requireNotNull(device.wait(Until.findObject(By.desc("Show roots")), WAIT_MS)))
            tap(device, requireNotNull(device.wait(Until.findObject(By.text(Build.MODEL)), WAIT_MS)))
        }
        if (!device.hasObject(By.text(TEST_FOLDER))) {
            tap(device, requireNotNull(device.wait(Until.findObject(By.text("Documents")), WAIT_MS)))
        }
        tap(device, requireNotNull(device.wait(Until.findObject(By.text(TEST_FOLDER)), WAIT_MS)))
        assertTrue("DocumentsUI is not in the exact fixture directory", waitUntil { inTestDirectory() })
    }

    private fun tap(device: UiDevice, target: UiObject2) {
        val bounds = target.visibleBounds
        assertTrue("Target has no visible touch area", bounds.width() > 0 && bounds.height() > 0)
        assertTrue(device.click(bounds.centerX(), bounds.centerY()))
    }

    private fun testGrantExists(): Boolean = context.contentResolver.persistedUriPermissions.any {
        it.uri.authority == "com.android.externalstorage.documents" && DocumentsContract.isTreeUri(it.uri) &&
            DocumentsContract.getTreeDocumentId(it.uri) == TEST_TREE_ID
    }

    private fun releaseTestGrant() {
        context.contentResolver.persistedUriPermissions.filter {
            it.uri.authority == "com.android.externalstorage.documents" && DocumentsContract.isTreeUri(it.uri) &&
                DocumentsContract.getTreeDocumentId(it.uri) == TEST_TREE_ID
        }.forEach { grant ->
            val flags = (if (grant.isReadPermission) Intent.FLAG_GRANT_READ_URI_PERMISSION else 0) or
                (if (grant.isWritePermission) Intent.FLAG_GRANT_WRITE_URI_PERMISSION else 0)
            context.contentResolver.releasePersistableUriPermission(grant.uri, flags)
        }
    }

    private fun capture(device: UiDevice, directory: File, name: String) {
        device.dumpWindowHierarchy(File(directory, "$name.xml"))
        assertTrue(device.takeScreenshot(File(directory, "$name.png")))
    }

    private fun waitUntil(condition: () -> Boolean): Boolean {
        val deadline = SystemClock.uptimeMillis() + WAIT_MS
        while (SystemClock.uptimeMillis() < deadline) {
            if (condition()) return true
            SystemClock.sleep(100)
        }
        return condition()
    }

    companion object {
        private const val TEST_FOLDER = "OpencyvisM4Test"
        private const val TEST_TREE_ID = "primary:Documents/$TEST_FOLDER"
        private const val WAIT_MS = 10_000L
    }
}
