package ai.opencyvis.learning

import android.graphics.Point
import android.view.KeyEvent
import android.view.MotionEvent
import ai.opencyvis.display.VirtualDisplayManager
import ai.opencyvis.db.AppDatabase
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.After
import org.junit.runner.RunWith

/** Runs against the phone's org.json, so JVM-only overloads cannot hide linkage errors. */
@RunWith(AndroidJUnit4::class)
class LearningRecordingDeviceTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val preview = Point(1080, 2340)

    @After fun removeDiscardedTestSessions() = runBlocking {
        withContext(Dispatchers.IO) {
            val dao = AppDatabase.getInstance(context).learningDao()
            dao.all().filter { it.goal == "录制兼容性回归测试" && it.status == "discarded" }
                .forEach { dao.delete(it.id) }
        }
    }

    private fun recording(mode: String = "demonstration", block: suspend (LearningSessionController, String) -> Unit) = runBlocking {
        val controller = LearningSessionController(context)
        try {
            // An uncreated display provides geometry without opening apps or taking screenshots.
            val id = controller.start(mode, "录制兼容性回归测试", VirtualDisplayManager(context), JSONObject())
            block(controller, id)
        } finally {
            // Discard only this test's session; never consolidate it or touch existing memories.
            controller.finish("自动回归测试", discard = true)
            controller.destroy()
        }
    }

    private fun touch(controller: LearningSessionController, action: Int, x: Float = 201.5f,
        y: Float = 401.5f, time: Long = 120, accepted: Boolean = true) {
        val event = MotionEvent.obtain(100, time, action, x, y, 0)
        try { controller.touch(event, accepted, preview) }
        finally { event.recycle() }
    }

    private suspend fun gestures(id: String) = LearningRepository(context).events(id)
        .filter { it.getString("kind") == "gesture" }.map { it.getJSONObject("details") }

    @Test fun firstTapPersistsCoordinatesOnAndroid() = recording { controller, id ->
        touch(controller, MotionEvent.ACTION_DOWN)
        touch(controller, MotionEvent.ACTION_UP, time = 150)
        controller.pause(true) // Drains the ordered writer before assertions.
        val gesture = gestures(id).single()
        assertTrue(gesture.getBoolean("injected"))
        assertFalse(gesture.getBoolean("cancelled"))
        val points = gesture.getJSONArray("points")
        assertEquals(2, points.length())
        val first = points.getJSONObject(0)
        assertEquals(100.75, first.getDouble("x"), 0.001)
        assertEquals(200.75, first.getDouble("y"), 0.001)
        val pointer = first.getJSONArray("pointers").getJSONObject(0)
        assertEquals(100.75, pointer.getDouble("x"), 0.001)
        assertEquals(200.75, pointer.getDouble("y"), 0.001)
        assertEquals(120L, first.getLong("event_time"))
        assertEquals("paused", LearningRepository(context).get(id)?.status)
    }

    @Test fun correctionDragKeepsOrderAndRejectedInput() = recording("correction") { controller, id ->
        touch(controller, MotionEvent.ACTION_DOWN)
        touch(controller, MotionEvent.ACTION_MOVE, x = 401.5f, time = 130, accepted = false)
        touch(controller, MotionEvent.ACTION_UP, x = 601.5f, time = 150)
        controller.pause(true)
        val gesture = gestures(id).single()
        assertFalse(gesture.getBoolean("injected"))
        val points = gesture.getJSONArray("points")
        assertEquals(3, points.length())
        assertEquals(200.75, points.getJSONObject(1).getDouble("x"), 0.001)
        assertEquals(300.75, points.getJSONObject(2).getDouble("x"), 0.001)
    }

    @Test fun pauseResumeAndCancelKeepRecordingUsable() = recording { controller, id ->
        controller.pause(true)
        touch(controller, MotionEvent.ACTION_DOWN)
        touch(controller, MotionEvent.ACTION_UP)
        controller.pause(false)
        touch(controller, MotionEvent.ACTION_DOWN)
        touch(controller, MotionEvent.ACTION_CANCEL, time = 150)
        touch(controller, MotionEvent.ACTION_DOWN)
        touch(controller, MotionEvent.ACTION_UP, time = 160)
        controller.pause(true)
        val gestures = gestures(id)
        assertEquals(2, gestures.size)
        assertTrue(gestures[0].getBoolean("cancelled"))
        assertFalse(gestures[1].getBoolean("cancelled"))
    }

    @Test fun textAndBackKeyAreWrittenBeforePause() = recording { controller, id ->
        controller.text("学习测试", 0, true)
        controller.key(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_BACK), true)
        controller.pause(true)
        val events = LearningRepository(context).events(id).filter {
            it.getString("kind") in setOf("text_edit", "key")
        }
        assertEquals(listOf("text_edit", "key"), events.map { it.getString("kind") })
        assertEquals("学习测试", events[0].getJSONObject("details").getString("inserted"))
        assertEquals(KeyEvent.KEYCODE_BACK, events[1].getJSONObject("details").getInt("key_code"))
    }
}
