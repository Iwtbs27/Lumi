package ai.opencyvis.overlay

import android.content.Context
import android.graphics.Bitmap
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.GradientDrawable
import android.os.Looper
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
import ai.opencyvis.R
import ai.opencyvis.config.ConfigRepository
import ai.opencyvis.display.VirtualDisplayManager
import ai.opencyvis.engine.AgentState
import androidx.test.core.app.ApplicationProvider
import io.mockk.every
import io.mockk.mockk
import io.mockk.unmockkAll
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/** Exercises the real overlay views and WindowManager lifecycle; only VD capture is mocked. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "en")
@LooperMode(LooperMode.Mode.PAUSED)
class OverlayPreviewUiTest {
    private var overlay: OverlayWindow? = null

    @After fun tearDown() {
        overlay?.dismiss()
        shadowOf(Looper.getMainLooper()).idle()
        unmockkAll()
    }

    @Test fun realButtonsSwitchBetweenBallTextAndPreviewWithoutStoppingTheTask() {
        val window = createOverlay()
        var stops = 0
        var returns = 0
        window.callback = object : OverlayWindow.Callback {
            override fun onStop() { stops++ }
            override fun onReturnToApp() { returns++ }
        }
        window.attach()
        assertFalse(window.isExpandedForTest())

        ball().findViewById<View>(R.id.chat_head).performClick()
        assertTrue(window.isExpandedForTest())
        assertFalse(window.isPreviewVisibleForTest())
        assertTrue(pill().findViewById<TextView>(R.id.pill_step).text.contains("7"))

        click(R.id.pill_preview_toggle)
        assertTrue(window.isPreviewVisibleForTest())
        assertEquals(View.VISIBLE, pill().findViewById<View>(R.id.pill_preview_container).visibility)
        click(R.id.pill_preview_toggle)
        assertFalse(window.isPreviewVisibleForTest())
        assertTrue(window.isExpandedForTest())
        assertEquals(View.GONE, pill().findViewById<View>(R.id.pill_preview_container).visibility)

        click(R.id.pill_preview_toggle)
        click(R.id.pill_minimize)
        assertFalse(window.isExpandedForTest())
        assertFalse(window.isPreviewVisibleForTest())
        ball().findViewById<View>(R.id.chat_head).performClick()
        assertTrue(window.isExpandedForTest())
        assertFalse("Reopening the ball starts with the text view", window.isPreviewVisibleForTest())
        assertEquals(0, stops)
        assertEquals(0, returns)
    }

    @Test fun expandedStepTextStaysOpenUntilTheUserMinimizesIt() {
        val window = createOverlay()
        window.attach()
        ball().findViewById<View>(R.id.chat_head).performClick()
        shadowOf(Looper.getMainLooper()).idleFor(6, TimeUnit.SECONDS)
        assertTrue("The previous five-second collapse must be removed", window.isExpandedForTest())
        click(R.id.pill_minimize)
        assertFalse(window.isExpandedForTest())
    }

    @Test fun statusDotTracksAgentStateWhileTheLogoRemainsOpaque() {
        val window = createOverlay()
        window.attach()
        val logo = ball().findViewById<ImageView>(R.id.chat_head)
        val status = ball().findViewById<View>(R.id.chat_head_status)
        listOf<Pair<AgentState, Int>>(
            AgentState.Running(8, "Reading") to 0xFF4ADE80.toInt(),
            AgentState.Paused to 0xFFFFCBA1.toInt(),
            AgentState.WaitingForUser("Continue?", 8) to 0xFFFA8C16.toInt(),
            AgentState.Error("Connection lost") to 0xFFEF4444.toInt()
        ).forEach { (state, color) ->
            window.updateState(state)
            window.setExpanded(false)
            shadowOf(Looper.getMainLooper()).idleFor(1, TimeUnit.SECONDS)
            assertEquals(color, (status.background as GradientDrawable).color!!.defaultColor)
            assertEquals("Logo must remain clear instead of pulsing", 1f, logo.alpha, 0f)
        }
    }

    @Test fun draggingTheRealBallMovesItsWindowWithoutOpeningTheStepPanel() {
        val window = createOverlay()
        window.attach()
        shadowOf(Looper.getMainLooper()).idleFor(500, TimeUnit.MILLISECONDS)
        val root = ball()
        val logo = root.findViewById<ImageView>(R.id.chat_head)
        val initial = root.layoutParams as WindowManager.LayoutParams
        val initialX = initial.x
        val initialY = initial.y
        val downTime = SystemClock.uptimeMillis()
        listOf(
            Triple(MotionEvent.ACTION_DOWN, 16f, 16f),
            Triple(MotionEvent.ACTION_MOVE, 66f, 106f),
            Triple(MotionEvent.ACTION_UP, 66f, 106f)
        ).forEachIndexed { index, (action, x, y) ->
            val event = MotionEvent.obtain(downTime, downTime + index * 16, action, x, y, 0)
            try { assertTrue(logo.dispatchTouchEvent(event)) } finally { event.recycle() }
        }
        shadowOf(Looper.getMainLooper()).idle()
        val moved = root.layoutParams as WindowManager.LayoutParams
        assertEquals(initialX - 50, moved.x)
        assertEquals(initialY + 90, moved.y)
        assertTrue("Only the overlay root may receive WindowManager layout params", logo.layoutParams is FrameLayout.LayoutParams)
        assertFalse("A drag must not act as a tap", window.isExpandedForTest())
    }

    @Test fun unavailableVirtualDisplayShowsAStatusAndStillAllowsReturningToText() {
        val window = createOverlay()
        window.attach()
        window.setExpanded(true)
        click(R.id.pill_preview_toggle)
        await { pill().findViewById<TextView>(R.id.pill_preview_status).text ==
            ApplicationProvider.getApplicationContext<Context>().getString(R.string.overlay_preview_unavailable) }
        val status = pill().findViewById<TextView>(R.id.pill_preview_status)
        assertEquals(View.VISIBLE, status.visibility)
        assertNull(currentBitmap())
        click(R.id.pill_preview_toggle)
        assertFalse(window.isPreviewVisibleForTest())
        assertTrue(window.isExpandedForTest())
    }

    @Test fun stopAndMinimizeRemainSeparateActions() {
        val window = createOverlay()
        var stops = 0
        window.callback = object : OverlayWindow.Callback {
            override fun onStop() { stops++ }
            override fun onReturnToApp() {}
        }
        window.attach()
        window.setExpanded(true)
        click(R.id.pill_minimize)
        assertEquals(0, stops)
        ball().findViewById<View>(R.id.chat_head).performClick()
        click(R.id.pill_stop)
        assertEquals(1, stops)
        assertTrue(window.isExpandedForTest())
    }

    @Test fun captureRunsOnlyWhileThePreviewIsAttachedAndClearsItsFrameOnDetach() {
        val captures = AtomicInteger()
        val display = mockk<VirtualDisplayManager>(relaxed = true)
        every { display.capturePreviewBitmap(any()) } answers {
            captures.incrementAndGet()
            Bitmap.createBitmap(24, 40, Bitmap.Config.ARGB_8888)
        }
        val window = createOverlay { display }
        window.setExpanded(true)
        window.setPreviewVisible(true)
        shadowOf(Looper.getMainLooper()).idleFor(1, TimeUnit.SECONDS)
        assertEquals("Prepared overlays must not sample in the foreground", 0, captures.get())
        assertFalse(window.isPreviewRunningForTest())

        window.attach()
        await { currentBitmap() != null }
        assertTrue(window.isPreviewRunningForTest())
        val firstFrame = requireNotNull(currentBitmap())
        window.detach()
        assertNull(currentBitmap())
        assertTrue(firstFrame.isRecycled)
        assertFalse(window.isPreviewRunningForTest())
        val afterDetach = captures.get()
        shadowOf(Looper.getMainLooper()).idleFor(1, TimeUnit.SECONDS)
        assertEquals(afterDetach, captures.get())

        window.attach()
        await { captures.get() > afterDetach && currentBitmap() != null }
        val resumedFrame = requireNotNull(currentBitmap())
        click(R.id.pill_preview_toggle)
        assertNull(currentBitmap())
        assertTrue(resumedFrame.isRecycled)
        assertFalse(window.isPreviewRunningForTest())
        assertTrue(window.isExpandedForTest())
    }

    @Test fun minimizingDuringCaptureDiscardsAndRecyclesTheLateFrame() {
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        val frame = Bitmap.createBitmap(24, 40, Bitmap.Config.ARGB_8888)
        val display = mockk<VirtualDisplayManager>(relaxed = true)
        every { display.capturePreviewBitmap(any()) } answers {
            started.countDown()
            check(release.await(3, TimeUnit.SECONDS))
            frame
        }
        val window = createOverlay { display }
        try {
            window.attach()
            window.setExpanded(true)
            click(R.id.pill_preview_toggle)
            await { started.count == 0L }
            click(R.id.pill_minimize)
            release.countDown()
            await { frame.isRecycled }
            assertFalse(window.isExpandedForTest())
            assertFalse(window.isPreviewRunningForTest())
            assertNull(currentBitmap())
        } finally { release.countDown() }
    }

    private fun createOverlay(display: () -> VirtualDisplayManager? = { null }): OverlayWindow {
        val context = ApplicationProvider.getApplicationContext<Context>()
        ConfigRepository(context).debugMode = true
        return OverlayWindow(context, display).also {
            overlay = it
            it.currentInstruction = "Collect information"
            it.prepare()
            it.updateState(AgentState.Running(7, "Inspecting the page"))
        }
    }

    private fun click(id: Int) { assertTrue(pill().findViewById<View>(id).performClick()) }
    private fun image(): ImageView = pill().findViewById(R.id.pill_preview_image)
    private fun currentBitmap(): Bitmap? = (image().drawable as? BitmapDrawable)?.bitmap
    private fun pill(): View = view("pillView")
    private fun ball(): View = view("minimizedView")
    private fun view(field: String): View = OverlayWindow::class.java.getDeclaredField(field).let {
        it.isAccessible = true
        it.get(requireNotNull(overlay)) as View
    }

    private fun await(condition: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(4)
        while (System.nanoTime() < deadline) {
            shadowOf(Looper.getMainLooper()).idleFor(20, TimeUnit.MILLISECONDS)
            if (condition()) return
            Thread.sleep(10)
        }
        assertTrue("Timed out waiting for the preview UI", condition())
    }
}
