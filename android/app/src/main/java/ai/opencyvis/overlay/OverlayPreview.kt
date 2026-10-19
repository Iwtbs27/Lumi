package ai.opencyvis.overlay

import android.graphics.Bitmap
import android.util.Log
import android.view.View
import android.widget.ImageView
import android.widget.TextView
import ai.opencyvis.R
import ai.opencyvis.display.VirtualDisplayManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Owns only preview copies; never switches the virtual display's rendering surface. */
internal class OverlayPreview(
    private val image: ImageView,
    private val status: TextView,
    private val displayProvider: () -> VirtualDisplayManager?
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var job: Job? = null
    private var displayedBitmap: Bitmap? = null

    val isRunning: Boolean get() = job?.isActive == true

    fun start() {
        if (isRunning) return
        status.setText(R.string.overlay_preview_loading)
        status.visibility = View.VISIBLE
        job = scope.launch {
            while (isActive) {
                val display = displayProvider()
                val displayId = display?.displayId
                // Keep ownership outside withContext so cancellation can release an in-flight copy.
                var frame: Bitmap? = null
                try {
                    withContext(Dispatchers.IO) {
                        frame = display?.capturePreviewBitmap()
                    }
                    ensureActive()
                    if (display === displayProvider() && displayId == display?.displayId) {
                        showFrame(frame)
                        frame = null
                    } else {
                        showFrame(null)
                    }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (failure: Exception) {
                    Log.w("OverlayPreview", "Could not read virtual display preview", failure)
                    showFrame(null)
                } finally {
                    frame?.recycle()
                }
                delay(400)
            }
        }
    }

    fun stop() {
        job?.cancel()
        job = null
        showFrame(null)
    }

    fun release() {
        stop()
        scope.cancel()
    }

    private fun showFrame(bitmap: Bitmap?) {
        val previous = displayedBitmap
        displayedBitmap = bitmap
        image.setImageBitmap(bitmap)
        status.setText(R.string.overlay_preview_unavailable)
        status.visibility = if (bitmap == null) View.VISIBLE else View.GONE
        previous?.recycle()
    }
}
