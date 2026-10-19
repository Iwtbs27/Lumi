package ai.opencyvis.overlay

import android.app.ActivityOptions
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.os.IBinder
import android.provider.AlarmClock
import android.provider.Settings
import android.view.View
import android.view.inspector.WindowInspector
import android.widget.ImageView
import ai.opencyvis.AgentService
import ai.opencyvis.OverlayService
import ai.opencyvis.R
import ai.opencyvis.SettingsActivity
import ai.opencyvis.backend.PrivilegeBackend
import ai.opencyvis.backend.SystemBackend
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import java.io.File
import kotlin.math.abs
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Real overlay service, touch input and VD frames; the existing debug agent never calls a model API. */
@RunWith(AndroidJUnit4::class)
class OverlayPreviewDeviceTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val device get() = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
    private val output get() = File(context.getExternalFilesDir(null), "overlay-preview-validation").apply { mkdirs() }

    @Test fun floatingPreviewShowsAndUpdatesTheRealVirtualDisplay(): Unit = runBlocking {
        check(Settings.canDrawOverlays(context)) { "Grant overlay permission on the dedicated test device" }
        val result = JSONObject().put("status", "running").put("cloud_model_used", false)
        val connected = CompletableDeferred<AgentService>()
        val connection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
                connected.complete((binder as AgentService.AgentBinder).getService())
            }
            override fun onServiceDisconnected(name: ComponentName?) {}
        }
        var bound = false
        var service: AgentService? = null
        var scenario: ActivityScenario<SettingsActivity>? = null
        var debugStarted = false
        var firstFrame: Bitmap? = null
        var changedFrame: Bitmap? = null
        try {
            // Settings avoids the homepage's first-run backend/battery permission onboarding.
            val mainIntent = Intent(context, SettingsActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_MULTIPLE_TASK)
            scenario = ActivityScenario.launch<SettingsActivity>(mainIntent,
                ActivityOptions.makeBasic().setLaunchDisplayId(0).toBundle())
            withContext(Dispatchers.Main) {
                val intent = Intent(context, AgentService::class.java)
                context.startForegroundService(intent)
                bound = context.bindService(intent, connection, Context.BIND_AUTO_CREATE)
                check(bound)
                context.startService(Intent(context, OverlayService::class.java))
            }
            val active = withTimeout(WAIT_MS) { connected.await() }.also { service = it }
            waitUntil { active.activeBackendName != null }
            val backend = requireNotNull(active.getInputBackend())
            assertTrue(backend.capabilities.canInjectInput)
            withContext(Dispatchers.Main) { active.debugStartRunningAgent() }
            debugStarted = true
            val display = requireNotNull(active.getVirtualDisplayManager())
            assertTrue(display.displayId > 0)
            result.put("backend", backend.capabilities.name).put("display_id", display.displayId)
            // Remote VD creation initializes its mirror asynchronously, as in the existing device tests.
            delay(1400)
            result.put("initial_vd_action", AlarmClock.ACTION_SHOW_ALARMS)
            launchClock(backend, display.displayId, AlarmClock.ACTION_SHOW_ALARMS)
            delay(800)
            bringMainToFront(mainIntent)
            assertFalse("Foreground app must hide the overlay", withContext(Dispatchers.Main) { overlayVisible() })
            result.put("hidden_in_foreground", true)

            device.pressHome()
            awaitObject("chat_head")
            screenshot("01-ball")
            val ballBeforeDrag = awaitObject("chat_head").visibleBounds
            assertTrue(device.swipe(ballBeforeDrag.centerX(), ballBeforeDrag.centerY(),
                ballBeforeDrag.centerX() - device.displayWidth / 4,
                ballBeforeDrag.centerY() + device.displayHeight / 6, 30))
            waitUntil {
                device.findObject(By.res(context.packageName, "chat_head"))?.visibleBounds?.let {
                    abs(it.centerX() - ballBeforeDrag.centerX()) > 10 || abs(it.centerY() - ballBeforeDrag.centerY()) > 10
                } == true
            }
            result.put("ball_before_drag_bounds", ballBeforeDrag.toShortString())
                .put("ball_after_drag_bounds", awaitObject("chat_head").visibleBounds.toShortString())
            screenshot("01-ball-after-drag")
            tap("chat_head")
            awaitObject("pill_step")
            delay(6100)
            assertNotNull("Expanded text must remain after six seconds", device.findObject(By.res(context.packageName, "pill_step")))
            screenshot("02-step-text")
            result.put("text_open_after_six_seconds", true)

            tap("pill_preview_toggle")
            val first = awaitPreviewFrame().also { firstFrame = it }
            val previewBounds = requireNotNull(device.wait(Until.findObject(By.res(context.packageName, "pill_preview_image")), WAIT_MS)).visibleBounds
            assertTrue(previewBounds.width() in 1 until device.displayWidth)
            assertTrue(previewBounds.height() in 1 until device.displayHeight)
            screenshot("03-preview-alarms")
            save(first, "03-preview-frame")
            val initialMatch = compareWithVirtualDisplay(backend, display.displayId, first, "03-vd-alarms")
            assertTrue("Preview must contain the actual VD page: $initialMatch", initialMatch < 15)
            result.put("preview_bounds", previewBounds.toShortString()).put("initial_vd_difference", initialMatch)

            result.put("changed_vd_action", AlarmClock.ACTION_SHOW_TIMERS)
            launchClock(backend, display.displayId, AlarmClock.ACTION_SHOW_TIMERS)
            delay(1000)
            val changed = awaitPreviewFrame(first).also { changedFrame = it }
            screenshot("04-preview-timers")
            save(changed, "04-preview-frame")
            val changedMatch = compareWithVirtualDisplay(backend, display.displayId, changed, "04-vd-timers")
            val pageDifference = pixelDifference(first, changed)
            assertTrue("Changed preview must still match its VD: $changedMatch", changedMatch < 15)
            result.put("page_pixel_difference", pageDifference).put("changed_vd_difference", changedMatch)

            tap("pill_preview_toggle")
            waitUntil { withContext(Dispatchers.Main) { previewBitmap() == null } }
            assertNotNull(device.findObject(By.res(context.packageName, "pill_step")))
            screenshot("05-return-to-text")
            tap("pill_preview_toggle")
            awaitPreviewFrame().recycle()
            tap("pill_minimize")
            awaitObject("chat_head")
            screenshot("06-minimized-from-preview")
            result.put("preview_to_text_and_ball", true)

            tap("chat_head")
            assertTrue(withContext(Dispatchers.Main) {
                requireNotNull(overlayRoot()).findViewById<View>(R.id.pill_preview_container).visibility == View.GONE
            })
            tap("pill_preview_toggle")
            awaitPreviewFrame().recycle()
            bringMainToFront(mainIntent)
            screenshot("07-hidden-after-return")
            result.put("hidden_after_return", true).put("status", "passed")
        } catch (failure: Throwable) {
            result.put("status", "failed").put("failure", failure.toString())
            File(output, "failure.txt").writeText(failure.stackTraceToString())
            screenshot("failure")
            throw failure
        } finally {
            firstFrame?.recycle()
            changedFrame?.recycle()
            withContext(NonCancellable) {
                try {
                    try {
                        if (debugStarted) {
                            withContext(Dispatchers.Main) { service?.stopAgent() }
                            waitUntil { service?.getVirtualDisplayManager() == null }
                            result.put("debug_task_stopped", true)
                        }
                        // ActivityScenario blocks while the main thread destroys its activity.
                        scenario?.close()
                    } finally {
                        withContext(Dispatchers.Main) {
                            Stage.values().filter { it != Stage.DESTROYED }
                                .flatMap { ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(it) }
                                .filter { it.packageName == context.packageName }.distinct().forEach { it.finish() }
                            context.stopService(Intent(context, OverlayService::class.java))
                            if (bound) context.unbindService(connection)
                            context.stopService(Intent(context, AgentService::class.java))
                        }
                    }
                    waitUntil { service?.getInputBackend() == null }
                    result.put("services_stopped", true)
                } catch (failure: Throwable) {
                    result.put("status", "failed").put("cleanup_failure", failure.toString())
                    throw failure
                } finally { File(output, "result.json").writeText(result.toString(2)) }
            }
        }
    }

    private suspend fun launchClock(backend: PrivilegeBackend, displayId: Int, action: String) {
        val intent = Intent(action).setPackage("com.google.android.deskclock")
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_MULTIPLE_TASK)
        intent.component = requireNotNull(intent.resolveActivity(context.packageManager))
        if (backend is SystemBackend) withContext(Dispatchers.Main) {
            context.startActivity(intent, ActivityOptions.makeBasic().setLaunchDisplayId(displayId).toBundle())
        } else assertTrue(backend.startActivityOnDisplay(intent.toUri(Intent.URI_INTENT_SCHEME), displayId))
    }

    private suspend fun bringMainToFront(intent: Intent) {
        withContext(Dispatchers.Main) {
            context.startActivity(Intent(intent).addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT),
                ActivityOptions.makeBasic().setLaunchDisplayId(0).toBundle())
        }
        awaitObject("settings_subtitle")
        waitUntil { withContext(Dispatchers.Main) {
            ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED)
                .any { it is SettingsActivity && it.display?.displayId == 0 } && !overlayVisible()
        } }
    }

    private suspend fun awaitPreviewFrame(previous: Bitmap? = null): Bitmap = withTimeout(WAIT_MS) {
        while (true) {
            val bitmap = withContext(Dispatchers.Main) { previewBitmap()?.copy(Bitmap.Config.ARGB_8888, false) }
            if (bitmap != null) {
                if (previous == null || pixelDifference(previous, bitmap) > 1) return@withTimeout bitmap
                bitmap.recycle()
            }
            delay(100)
        }
        error("Unreachable")
    }

    private fun overlayRoot(): View? = WindowInspector.getGlobalWindowViews()
        .firstOrNull { it.isAttachedToWindow && it.findViewById<View>(R.id.pill_root) != null }

    private fun overlayVisible(): Boolean = WindowInspector.getGlobalWindowViews().any {
        it.isAttachedToWindow && (it.findViewById<View>(R.id.pill_root) != null || it.findViewById<View>(R.id.chat_head) != null)
    }

    private fun previewBitmap(): Bitmap? = (overlayRoot()?.findViewById<ImageView>(R.id.pill_preview_image)
        ?.drawable as? android.graphics.drawable.BitmapDrawable)?.bitmap?.takeUnless { it.isRecycled }

    private fun compareWithVirtualDisplay(backend: PrivilegeBackend, id: Int, preview: Bitmap, name: String): Double {
        val bytes = requireNotNull(backend.captureScreen(id, 480, 95)) { "Independent VD screenshot is required" }
        val bitmap = requireNotNull(BitmapFactory.decodeByteArray(bytes, 0, bytes.size))
        return try { save(bitmap, name); pixelDifference(bitmap, preview) } finally { bitmap.recycle() }
    }

    /** Mean RGB difference on a regular grid; compares actual pixels without retaining UI-owned frames. */
    private fun pixelDifference(first: Bitmap, second: Bitmap): Double {
        var difference = 0L
        for (row in 0 until 32) for (column in 0 until 24) {
            val a = first.getPixel((column * 2 + 1) * first.width / 48, (row * 2 + 1) * first.height / 64)
            val b = second.getPixel((column * 2 + 1) * second.width / 48, (row * 2 + 1) * second.height / 64)
            difference += abs(Color.red(a) - Color.red(b)) + abs(Color.green(a) - Color.green(b)) + abs(Color.blue(a) - Color.blue(b))
        }
        return difference.toDouble() / (32 * 24 * 3)
    }

    private fun awaitObject(name: String) = requireNotNull(device.wait(Until.findObject(By.res(context.packageName, name)), WAIT_MS)) {
        "Overlay control not visible: $name"
    }

    private fun tap(name: String) {
        val control = awaitObject(name)
        device.waitForIdle(1000)
        val bounds = control.visibleBounds
        assertTrue(device.click(bounds.centerX(), bounds.centerY()))
    }

    private fun screenshot(name: String) { assertTrue(device.takeScreenshot(File(output, "$name.png"))) }
    private fun save(bitmap: Bitmap, name: String) {
        File(output, "$name.png").outputStream().use { assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
    }

    private suspend fun waitUntil(predicate: suspend () -> Boolean) = withTimeout(WAIT_MS) {
        while (!predicate()) delay(100)
    }

    companion object { private const val WAIT_MS = 30_000L }
}
