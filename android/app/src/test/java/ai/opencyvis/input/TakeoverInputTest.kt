package ai.opencyvis.input

import android.graphics.Point
import android.view.InputDevice
import android.view.InputEvent
import android.view.KeyEvent
import android.view.MotionEvent
import ai.opencyvis.backend.PrivilegeBackend
import ai.opencyvis.backend.SystemBackend
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@ConscryptMode(ConscryptMode.Mode.OFF)
class TakeoverInputTest {
    @Test fun completeDragUsesProvidedBackendAndPreservesGestureTiming() {
        val received = mutableListOf<Triple<Int, Long, Long>>()
        val modes = mutableListOf<Int>()
        val backend = object : PrivilegeBackend by SystemBackend() {
            override fun injectInputEvent(event: InputEvent, displayId: Int, mode: Int): Boolean {
                assertEquals(16, displayId)
                val touch = event as MotionEvent
                assertEquals(InputDevice.SOURCE_TOUCHSCREEN, touch.source)
                received += Triple(touch.actionMasked, touch.downTime, touch.eventTime)
                modes += mode
                return true
            }
        }
        val actions = listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE, MotionEvent.ACTION_UP)
        actions.forEachIndexed { index, action ->
            val event = MotionEvent.obtain(100, 100L + index * 20, action, 50f + index * 10, 90f, 0)
            try {
                assertTrue(InputInjector.injectToDisplay(backend, event, 16, Point(1080, 2400), Point(1080, 2400)))
            } finally { event.recycle() }
        }
        assertEquals(actions, received.map { it.first })
        assertEquals(listOf(100L, 100L, 100L), received.map { it.second })
        assertEquals(listOf(100L, 120L, 140L), received.map { it.third })
        assertEquals(listOf(2, 0, 2), modes)
    }

    @Test fun previewCoordinatesAndHistoryAreMappedWithoutChangingOriginalEvent() {
        val backend = object : PrivilegeBackend by SystemBackend() {
            override fun injectInputEvent(event: InputEvent, displayId: Int, mode: Int): Boolean {
                val touch = event as MotionEvent
                assertEquals(240f, touch.x, 0.01f)
                assertEquals(600f, touch.y, 0.01f)
                assertEquals(1, touch.historySize)
                assertEquals(200f, touch.getHistoricalX(0), 0.01f)
                assertEquals(400f, touch.getHistoricalY(0), 0.01f)
                return true
            }
        }
        val event = MotionEvent.obtain(100, 120, MotionEvent.ACTION_MOVE, 100f, 200f, 0)
        event.addBatch(140, 120f, 300f, 1f, 1f, 0)
        try {
            assertTrue(InputInjector.injectToDisplay(backend, event, 16, Point(540, 1200), Point(1080, 2400)))
            assertEquals(120f, event.x, 0.01f)
            assertEquals(300f, event.y, 0.01f)
        } finally { event.recycle() }
    }

    @Test fun hardwareKeysUseTheSameBackendAndReportRejection() {
        var calls = 0
        val backend = object : PrivilegeBackend by SystemBackend() {
            override fun injectInputEvent(event: InputEvent, displayId: Int, mode: Int): Boolean {
                calls++
                assertEquals(16, displayId)
                assertEquals(KeyEvent.KEYCODE_DEL, (event as KeyEvent).keyCode)
                assertEquals(2, mode)
                return false
            }
        }
        assertFalse(InputInjector.injectKeyToDisplay(backend, KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DEL), 16))
        assertEquals(1, calls)
    }
}
