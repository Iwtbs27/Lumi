package ai.opencyvis.learning

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.graphics.Rect
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.view.Display
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.widget.Toast
import ai.opencyvis.R
import ai.opencyvis.App
import ai.opencyvis.accessibility.VdAccessibilityService
import ai.opencyvis.ui.LearningActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject

/** A user-started main-screen capture. Accessibility supplies semantics; it never intercepts touch. */
class LearningObservationService : Service() {
    companion object {
        const val ACTION_START = "learning.observe.start"
        const val ACTION_PAUSE = "learning.observe.pause"
        const val ACTION_RESUME = "learning.observe.resume"
        const val ACTION_FINISH = "learning.observe.finish"
        const val ACTION_DISCARD = "learning.observe.discard"
        const val EXTRA_PROJECTION = "projection_data"
        const val EXTRA_PACKAGES = "packages"
        const val EXTRA_GOAL = "goal"
        private const val CHANNEL = "learning_observation"
        private const val NOTIFICATION_ID = 4101
        @Volatile private var instance: LearningObservationService? = null
        val isObserving: Boolean get() = instance != null

        fun recordAccessibilityEvent(event: AccessibilityEvent) { instance?.recordEvent(event) }
        fun command(context: Context, action: String) {
            context.startService(Intent(context, LearningObservationService::class.java).setAction(action))
        }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val mainHandler = Handler(Looper.getMainLooper())
    private val imageThread = HandlerThread("LearningFrames")
    private lateinit var recorder: LearningRecorder
    private var projection: MediaProjection? = null
    private var display: VirtualDisplay? = null
    private var reader: ImageReader? = null
    private var sessionId: String? = null
    private var allowedPackages = emptySet<String>()
    private var width = 0
    private var height = 0
    @Volatile private var ending = false
    @Volatile private var paused = false
    @Volatile private var savingFrame = false
    @Volatile private var frameRequested = true
    @Volatile private var lastFrameAt = 0L
    @Volatile private var observedWindow: VdAccessibilityService.Companion.ObservationWindow? = null
    private var lastMessage = ""
    private var windowJob: Job? = null
    private var startJob: Job? = null
    private var frameJob: Job? = null
    private var starting = false
    private var lastContentEventAt = 0L
    private var pendingTextEvent: Pair<String, JSONObject>? = null
    private val flushText = Runnable {
        pendingTextEvent?.let { (packageName, details) ->
            recorder.event(Display.DEFAULT_DISPLAY, "accessibility", details, packageName)
        }
        pendingTextEvent = null
    }

    private val projectionCallback = object : MediaProjection.Callback() {
        override fun onStop() { finishObservation(getString(R.string.learning_observation_stopped)) }
        override fun onCapturedContentResize(width: Int, height: Int) {
            if (width != this@LearningObservationService.width || height != this@LearningObservationService.height) {
                resize(width, height)
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        recorder = LearningRecorder.get(this)
        imageThread.start()
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL, getString(R.string.learning_notify_channel), NotificationManager.IMPORTANCE_LOW)
        )
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> if (!starting && projection == null && !ending) {
                starting = true
                startForeground(NOTIFICATION_ID, notification(getString(R.string.learning_waiting_app)),
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
                startJob = scope.launch {
                    val agent = App.agentService
                    var reserved = false
                    try {
                        checkNotNull(agent) { getString(R.string.learning_backend_required) }.reserveObservationStart()
                        reserved = true
                        startObservation(intent)
                    }
                    catch (error: Exception) {
                        val message = getString(R.string.learning_observation_error, error.message)
                        Toast.makeText(this@LearningObservationService, message, Toast.LENGTH_LONG).show()
                        finishObservation(message)
                    } finally {
                        if (reserved) agent?.releaseObservationStart()
                        starting = false
                    }
                }
            }
            ACTION_PAUSE -> setPaused(true)
            ACTION_RESUME -> setPaused(false)
            ACTION_FINISH -> finishObservation(intent.getStringExtra("note").orEmpty())
            ACTION_DISCARD -> finishObservation(discard = true)
        }
        return START_NOT_STICKY
    }

    private suspend fun startObservation(intent: Intent) {
        check(VdAccessibilityService.instance != null) { getString(R.string.learning_a11y_required) }
        check(recorder.active.value == null) { getString(R.string.learning_busy) }
        allowedPackages = intent.getStringArrayListExtra(EXTRA_PACKAGES).orEmpty().toSet()
        require(allowedPackages.isNotEmpty()) { getString(R.string.learning_scope_required) }
        @Suppress("DEPRECATION")
        val authorization = requireNotNull(intent.getParcelableExtra<Intent>(EXTRA_PROJECTION))
        val session = recorder.start("observation", intent.getStringExtra(EXTRA_GOAL).orEmpty(),
            Display.DEFAULT_DISPLAY, allowedPackages = allowedPackages.toList(),
            source = JSONObject().put("capture", "media_projection").put("actions", "accessibility_observed"))
        sessionId = session.id
        if (ending) return
        instance = this
        val bounds = getSystemService(WindowManager::class.java).maximumWindowMetrics.bounds
        width = bounds.width()
        height = bounds.height()
        projection = getSystemService(MediaProjectionManager::class.java)
            .getMediaProjection(android.app.Activity.RESULT_OK, authorization)
        projection!!.registerCallback(projectionCallback, mainHandler)
        createReader()
        display = projection!!.createVirtualDisplay("OpenCyvis learning", width, height,
            resources.configuration.densityDpi, DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            reader!!.surface, null, mainHandler)
        windowJob = scope.launch {
            while (!ending) { refreshWindow(); delay(700) }
        }
    }

    private fun refreshWindow() {
        observedWindow = VdAccessibilityService.observationWindow()
        val window = observedWindow
        val message = when {
            paused -> getString(R.string.learning_paused)
            window == null -> getString(R.string.learning_window_unknown)
            window.packageName !in allowedPackages -> getString(R.string.learning_waiting_app)
            window.passwordFocused -> getString(R.string.learning_sensitive_paused)
            else -> getString(R.string.learning_observation_active, appLabel(window.packageName))
        }
        if (message != lastMessage) {
            lastMessage = message
            getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification(message))
        }
    }

    private fun recordEvent(event: AccessibilityEvent) {
        if (paused || ending) return
        refreshWindow()
        val window = observedWindow ?: return
        val packageName = event.packageName?.toString() ?: return
        if (packageName !in allowedPackages || window.packageName != packageName || window.passwordFocused || event.isPassword) return
        frameRequested = true
        val now = SystemClock.elapsedRealtime()
        if (event.eventType == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED) {
            if (now - lastContentEventAt < 1000) return
            lastContentEventAt = now
        }
        val source = event.source
        val details = try {
            if (source?.isPassword == true) return
            JSONObject().put("event_type", AccessibilityEvent.eventTypeToString(event.eventType))
                .put("text", event.text.joinToString(" ").take(2000))
                .put("description", event.contentDescription?.toString()?.take(1000))
                .put("class_name", event.className?.toString())
                .put("view_id", source?.viewIdResourceName)
                .put("window_id", event.windowId).put("source", "accessibility")
                .put("scroll_x", event.scrollX).put("scroll_y", event.scrollY)
        } finally { source?.recycle() }
        if (event.eventType == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED) {
            recorder.event(Display.DEFAULT_DISPLAY, "accessibility", details, packageName)
        } else if (event.eventType == AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED) {
            mainHandler.removeCallbacks(flushText)
            if (pendingTextEvent?.first != packageName) flushText.run()
            pendingTextEvent = packageName to details
            mainHandler.postDelayed(flushText, 400)
        } else {
            mainHandler.removeCallbacks(flushText)
            flushText.run()
            recorder.event(Display.DEFAULT_DISPLAY, "accessibility", details, packageName)
        }
    }

    private fun createReader() {
        reader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2).also { current ->
            current.setOnImageAvailableListener({ captureFrame(it) }, Handler(imageThread.looper))
        }
    }

    private fun resize(newWidth: Int, newHeight: Int) {
        if (ending || newWidth <= 0 || newHeight <= 0 || display == null) {
            return
        }
        display?.surface = null
        closeReader()
        width = newWidth
        height = newHeight
        createReader()
        display?.resize(width, height, resources.configuration.densityDpi)
        display?.surface = reader?.surface
        frameRequested = true
    }

    private fun captureFrame(current: ImageReader) {
        val image = current.acquireLatestImage() ?: return
        val now = SystemClock.elapsedRealtime()
        val window = observedWindow
        if (ending || paused || savingFrame || window == null || window.packageName !in allowedPackages ||
            window.passwordFocused || now - lastFrameAt < 900 || (!frameRequested && now - lastFrameAt < 5000)) {
            image.close()
            return
        }
        val bitmap: Bitmap
        val crop = Rect(window.bounds)
        val sourceWidth = image.width
        val sourceHeight = image.height
        try {
            if (!crop.intersect(0, 0, sourceWidth, sourceHeight)) return
            val plane = image.planes[0]
            val paddedWidth = plane.rowStride / plane.pixelStride
            val padded = Bitmap.createBitmap(paddedWidth, sourceHeight, Bitmap.Config.ARGB_8888)
            padded.copyPixelsFromBuffer(plane.buffer)
            bitmap = Bitmap.createBitmap(padded, crop.left, crop.top, crop.width(), crop.height())
            if (bitmap !== padded) padded.recycle()
        } finally { image.close() }
        savingFrame = true
        frameRequested = false
        lastFrameAt = now
        frameJob = scope.launch {
            try {
                val currentWindow = VdAccessibilityService.observationWindow()
                if (currentWindow == null || currentWindow.packageName != window.packageName || currentWindow.passwordFocused) return@launch
                mainHandler.removeCallbacks(flushText)
                flushText.run()
                val tree = VdAccessibilityService.captureObservationTree(window.packageName, sourceWidth, sourceHeight)
                recorder.frame(bitmap, Display.DEFAULT_DISPLAY, tree, window.packageName,
                    JSONObject().put("source", "media_projection").put("captured_at", System.currentTimeMillis())
                        .put("original_width", sourceWidth).put("original_height", sourceHeight)
                        .put("crop_bounds", JSONArray(listOf(crop.left, crop.top, crop.right, crop.bottom))))
            } catch (error: Exception) { finishObservation(getString(R.string.learning_observation_error, error.message)) }
            finally { bitmap.recycle(); savingFrame = false }
        }
    }

    private fun setPaused(value: Boolean) {
        scope.launch {
            startJob?.join()
            if (ending || recorder.active.value?.id != sessionId) return@launch
            try {
                mainHandler.removeCallbacks(flushText)
                flushText.run()
                paused = value
                recorder.pause(value)
                frameRequested = true
                refreshWindow()
            } catch (error: Exception) {
                finishObservation(getString(R.string.learning_observation_error, error.message))
            }
        }
    }

    private fun finishObservation(note: String = "", discard: Boolean = false) {
        if (ending) return
        ending = true
        instance = null
        mainHandler.removeCallbacks(flushText)
        flushText.run()
        windowJob?.cancel()
        releaseCapture()
        scope.launch {
            try {
                startJob?.join()
                // Drain a conversion already running on the image thread before joining its writer.
                val imagesDrained = CompletableDeferred<Unit>()
                Handler(imageThread.looper).post { imagesDrained.complete(Unit) }
                imagesDrained.await()
                frameJob?.join()
                if (sessionId != null && recorder.active.value?.id == sessionId) recorder.finish(note, discard)
            } catch (error: Exception) {
                Toast.makeText(this@LearningObservationService, error.message, Toast.LENGTH_LONG).show()
            } finally { stopForeground(STOP_FOREGROUND_REMOVE); stopSelf() }
        }
    }

    private fun releaseCapture() {
        projection?.unregisterCallback(projectionCallback)
        display?.release()
        display = null
        closeReader()
        projection?.stop()
        projection = null
    }

    private fun closeReader() {
        val previous = reader ?: return
        reader = null
        previous.setOnImageAvailableListener(null, null)
        Handler(imageThread.looper).post { previous.close() }
    }

    private fun notification(message: String): Notification {
        val content = PendingIntent.getActivity(this, 0,
            Intent(this, LearningActivity::class.java).putExtra("session_id", sessionId),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        fun action(value: String, request: Int) = PendingIntent.getService(this, request,
            Intent(this, javaClass).setAction(value), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        return Notification.Builder(this, CHANNEL).setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(getString(R.string.learning_notify_title)).setContentText(message)
            .setContentIntent(content).setOngoing(true)
            .addAction(Notification.Action.Builder(null, getString(if (paused) R.string.learning_resume else R.string.learning_pause),
                action(if (paused) ACTION_RESUME else ACTION_PAUSE, 1)).build())
            .addAction(Notification.Action.Builder(null, getString(R.string.learning_finish), action(ACTION_FINISH, 2)).build())
            .build()
    }

    private fun appLabel(packageName: String): String = try {
        packageManager.getApplicationLabel(packageManager.getApplicationInfo(packageName, 0)).toString()
    } catch (_: android.content.pm.PackageManager.NameNotFoundException) { packageName }

    override fun onDestroy() {
        instance = null
        releaseCapture()
        imageThread.quitSafely()
        scope.cancel()
        super.onDestroy()
    }
}
