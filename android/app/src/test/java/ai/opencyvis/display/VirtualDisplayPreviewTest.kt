package ai.opencyvis.display

import ai.opencyvis.backend.PrivilegeBackend
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.hardware.display.VirtualDisplay
import android.view.Display
import android.view.Surface
import androidx.test.core.app.ApplicationProvider
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import java.io.ByteArrayOutputStream
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@ConscryptMode(ConscryptMode.Mode.OFF)
class VirtualDisplayPreviewTest {
    private val backend = mockk<PrivilegeBackend>(relaxed = true)
    private val manager = VirtualDisplayManager(ApplicationProvider.getApplicationContext<Context>(), backend)
    private val ownedBitmaps = mutableListOf<Bitmap>()

    @After fun cleanup() {
        manager.destroy()
        ownedBitmaps.filterNot { it.isRecycled }.forEach { it.recycle() }
    }

    @Test fun `local preview is an independent copy and leaves the rendering surface alone`() {
        val cached = bitmap(120, 240)
        val display = localDisplay(cached)

        val preview = requireNotNull(manager.capturePreviewBitmap()).also { ownedBitmaps += it }

        assertNotSame(cached, preview)
        assertEquals(Color.RED, preview.getPixel(10, 10))
        preview.recycle()
        assertFalse(cached.isRecycled)
        assertEquals(Color.RED, cached.getPixel(10, 10))
        verify(exactly = 0) { display.surface = any() }
        verify(exactly = 0) { backend.captureScreen(any(), any(), any()) }
        verify(exactly = 0) { backend.setVirtualDisplaySurface(any()) }
    }

    @Test fun `large local preview preserves aspect ratio without changing the agent cache`() {
        val cached = bitmap(540, 1170)
        localDisplay(cached)

        val preview = requireNotNull(manager.capturePreviewBitmap(180)).also { ownedBitmaps += it }

        assertEquals(180, preview.width)
        assertEquals(390, preview.height)
        assertFalse(cached.isRecycled)
        assertEquals(540, cached.width)
    }

    @Test fun `external surface returns no stale local frame and never switches surfaces`() {
        val display = localDisplay(bitmap(120, 240))
        field("isUsingExternalSurface", true)
        field("externalSurface", mockk<Surface>())

        assertNull(manager.capturePreviewBitmap())

        assertTrue(manager.isUsingExternalSurface)
        verify(exactly = 0) { display.surface = any() }
        verify(exactly = 0) { backend.captureScreen(any(), any(), any()) }
        verify(exactly = 0) { backend.setVirtualDisplaySurface(any()) }
    }

    @Test fun `remote preview uses the active VD and bounds the decoded frame`() {
        field("remoteVdDisplayId", 19)
        val source = bitmap(540, 1170)
        val encoded = ByteArrayOutputStream().also { source.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()
        every { backend.captureScreen(19, 180, 70) } returns encoded

        val preview = requireNotNull(manager.capturePreviewBitmap(180)).also { ownedBitmaps += it }

        assertEquals(180, preview.width)
        assertEquals(390, preview.height)
        verify(exactly = 1) { backend.captureScreen(19, 180, 70) }
        verify(exactly = 0) { backend.setVirtualDisplaySurface(any()) }
    }

    @Test fun `no virtual display does not capture the physical screen`() {
        assertNull(manager.capturePreviewBitmap())
        verify(exactly = 0) { backend.captureScreen(any(), any(), any()) }
    }

    @Test fun `local VD without a cached frame does not compete for agent capture`() {
        val display = localDisplay(bitmap(120, 240))
        field("cachedBitmap", null)

        assertNull(manager.capturePreviewBitmap())

        verify(exactly = 0) { display.surface = any() }
        verify(exactly = 0) { backend.captureScreen(any(), any(), any()) }
    }

    @Test fun `destroy releases the cache but preserves the caller owned preview`() {
        val cached = bitmap(120, 240)
        val display = localDisplay(cached)
        val preview = requireNotNull(manager.capturePreviewBitmap()).also { ownedBitmaps += it }

        manager.destroy()

        assertTrue(cached.isRecycled)
        assertFalse(preview.isRecycled)
        assertNull(manager.capturePreviewBitmap())
        verify(exactly = 1) { display.release() }
        verify(exactly = 0) { backend.captureScreen(any(), any(), any()) }
    }

    private fun localDisplay(cached: Bitmap): VirtualDisplay {
        val display = mockk<VirtualDisplay>(relaxed = true)
        val info = mockk<Display>()
        every { info.displayId } returns 7
        every { display.display } returns info
        field("virtualDisplay", display)
        field("cachedBitmap", cached)
        return display
    }

    private fun bitmap(width: Int, height: Int) = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        .apply { eraseColor(Color.RED) }.also { ownedBitmaps += it }

    private fun field(name: String, value: Any?) {
        VirtualDisplayManager::class.java.getDeclaredField(name).apply { isAccessible = true }.set(manager, value)
    }
}
