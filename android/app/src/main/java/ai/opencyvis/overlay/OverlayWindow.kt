package ai.opencyvis.overlay

import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.Context
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.util.Log
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.animation.AccelerateDecelerateInterpolator
import android.view.animation.OvershootInterpolator
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.TextView
import ai.opencyvis.R
import ai.opencyvis.config.ConfigRepository
import ai.opencyvis.display.VirtualDisplayManager
import ai.opencyvis.engine.AgentState
import ai.opencyvis.engine.StepResult

/**
 * Floating chat-head → step panel ⇄ read-only virtual display preview.
 * Expanded panels remain visible until the user minimizes them.
 */
class OverlayWindow(
    private val context: Context,
    private val previewDisplay: () -> VirtualDisplayManager? = { null }
) {

    interface Callback {
        fun onReturnToApp()
        fun onStop()
    }

    private val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val debugMode = ConfigRepository(context).debugMode

    // Island (expanded) view — Dynamic Island style
    private var pillView: View? = null
    private var pillParams: WindowManager.LayoutParams? = null
    private var pillDot: View? = null
    private var pillTask: TextView? = null
    private var pillStep: TextView? = null
    private var pillStop: ImageButton? = null
    private var pillAction: TextView? = null
    private var previewToggle: ImageButton? = null
    private var previewContainer: View? = null
    private var preview: OverlayPreview? = null
    private var isPreviewVisible = false

    // Chat-head (minimized) view — Lumi mark with a task-state badge
    private var minimizedView: View? = null
    private var minimizedParams: WindowManager.LayoutParams? = null
    private var chatHead: ImageView? = null
    private var chatHeadStatus: View? = null

    var callback: Callback? = null
    var maxSteps: Int = 100

    var currentInstruction: String = ""
        set(value) {
            field = value
            pillTask?.text = trimTask(value)
        }

    private var isExpanded = false
    private var isPrepared = false
    private var isAttached = false
    private var currentStep: Int = 0
    private var _currentState: AgentState = AgentState.Idle()

    private val dragSlop: Int = (10 * context.resources.displayMetrics.density).toInt()
    private var dotAnimator: ObjectAnimator? = null

    internal var attachCount: Int = 0
        private set
    internal var detachCount: Int = 0
        private set

    private val colorIdle = 0xFF888888.toInt()
    private val colorRunning = 0xFF4ADE80.toInt()
    private val colorWaiting = 0xFFFA8C16.toInt()
    private val colorPaused = 0xFFFFCBA1.toInt()
    private val colorError = 0xFFEF4444.toInt()
    private val colorSuccess = 0xFF4CAF50.toInt()

    // ── Lifecycle ──────────────────────────────────────────────────────────

    @SuppressLint("ClickableViewAccessibility")
    fun prepare() {
        if (isPrepared) return

        val inflater = LayoutInflater.from(context)

        // Island view (expanded) — centered at top
        pillView = inflater.inflate(R.layout.overlay_window, null)
        pillDot = pillView?.findViewById(R.id.pill_dot)
        pillTask = pillView?.findViewById(R.id.pill_task)
        pillStep = pillView?.findViewById(R.id.pill_step)
        pillStop = pillView?.findViewById(R.id.pill_stop)
        pillAction = pillView?.findViewById(R.id.pill_action)
        previewToggle = pillView?.findViewById(R.id.pill_preview_toggle)
        previewContainer = pillView?.findViewById(R.id.pill_preview_container)
        preview = OverlayPreview(
            pillView!!.findViewById(R.id.pill_preview_image),
            pillView!!.findViewById(R.id.pill_preview_status),
            previewDisplay
        )

        pillTask?.text = trimTask(currentInstruction)

        pillView?.findViewById<View>(R.id.pill_minimize)?.setOnClickListener {
            setExpanded(false)
        }
        previewToggle?.setOnClickListener { setPreviewVisible(!isPreviewVisible) }
        pillStop?.setOnClickListener { callback?.onStop() }
        pillView?.findViewById<View>(R.id.pill_task)?.setOnLongClickListener {
            callback?.onReturnToApp()
            true
        }

        val metrics = context.resources.displayMetrics
        val panelWidth = minOf(dp(340), metrics.widthPixels - dp(24))
        previewContainer?.apply {
            clipToOutline = true
            layoutParams.height = (metrics.heightPixels * 0.42f).toInt()
        }

        pillParams = WindowManager.LayoutParams(
            panelWidth,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            x = 0
            y = 80
        }

        // Chat-head view (minimized) — corner ball
        minimizedView = inflater.inflate(R.layout.overlay_minimized, null)
        chatHead = minimizedView?.findViewById(R.id.chat_head)
        chatHeadStatus = minimizedView?.findViewById(R.id.chat_head_status)

        minimizedParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.END
            x = 12
            y = 120
        }

        chatHead?.setOnClickListener {
            if (_currentState is AgentState.Paused) {
                callback?.onReturnToApp()
            } else {
                setExpanded(true)
            }
        }
        chatHead?.setOnLongClickListener {
            callback?.onReturnToApp()
            setExpanded(false)
            true
        }
        setupDragging(chatHead, minimizedParams!!)

        applyStateColors()
        startStatusAnimation()
        isPrepared = true
        Log.d(TAG, "prepare() done")
    }

    fun attach() {
        if (!isPrepared) {
            Log.w(TAG, "attach() called before prepare(); ignoring")
            return
        }
        if (isAttached) return
        applyStateColors()
        refreshPillText()

        val view = if (isExpanded) pillView else minimizedView
        val params = if (isExpanded) pillParams else minimizedParams
        try {
            if (view != null && params != null && !view.isAttachedToWindow) {
                windowManager.addView(view, params)
                attachCount++
                // Entry animation
                if (isExpanded) {
                    // Island: scale from small + fade
                    view.scaleX = 0.5f
                    view.scaleY = 0.5f
                    view.alpha = 0f
                    view.animate()
                        .scaleX(1f).scaleY(1f).alpha(1f)
                        .setDuration(300)
                        .setInterpolator(AccelerateDecelerateInterpolator())
                        .start()
                } else {
                    // Chat-head: bounce in
                    view.scaleX = 0f
                    view.scaleY = 0f
                    view.animate()
                        .scaleX(1f).scaleY(1f)
                        .setDuration(400)
                        .setInterpolator(OvershootInterpolator(1.5f))
                        .start()
                }
                Log.d(TAG, "attach() added ${if (isExpanded) "island" else "chatHead"}")
            }
            isAttached = true
            updatePreview()
        } catch (e: Exception) {
            Log.w(TAG, "attach: addView failed: ${e.message}")
        }
    }

    fun detach() {
        preview?.stop()
        if (!isPrepared) return
        if (!isAttached) return
        try {
            pillView?.let {
                if (it.isAttachedToWindow) windowManager.removeViewImmediate(it)
            }
        } catch (_: Exception) {}
        try {
            minimizedView?.let {
                if (it.isAttachedToWindow) windowManager.removeViewImmediate(it)
            }
        } catch (_: Exception) {}
        isAttached = false
        detachCount++
        Log.d(TAG, "detach() done")
    }

    fun dismiss() {
        preview?.release()
        preview = null
        dotAnimator?.cancel()
        detach()
        pillView = null
        minimizedView = null
        isExpanded = false
        isPreviewVisible = false
        isPrepared = false
    }

    fun setExpanded(expanded: Boolean) {
        if (!expanded) setPreviewVisible(false)
        if (isExpanded == expanded) return
        isExpanded = expanded
        if (!isAttached) return

        val newView = if (expanded) pillView else minimizedView
        val newParams = if (expanded) pillParams else minimizedParams
        val oldView = if (expanded) minimizedView else pillView
        try {
            oldView?.let {
                if (it.isAttachedToWindow) windowManager.removeViewImmediate(it)
            }
        } catch (e: Exception) {
            Log.w(TAG, "setExpanded remove old: $e")
        }
        try {
            if (newView != null && newParams != null && !newView.isAttachedToWindow) {
                windowManager.addView(newView, newParams)
                // Transition animation
                if (expanded) {
                    newView.scaleX = 0.5f
                    newView.scaleY = 0.5f
                    newView.alpha = 0f
                    newView.animate()
                        .scaleX(1f).scaleY(1f).alpha(1f)
                        .setDuration(250)
                        .setInterpolator(AccelerateDecelerateInterpolator())
                        .start()
                } else {
                    newView.scaleX = 0f
                    newView.scaleY = 0f
                    newView.animate()
                        .scaleX(1f).scaleY(1f)
                        .setDuration(300)
                        .setInterpolator(OvershootInterpolator(1.5f))
                        .start()
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "setExpanded add new: $e")
        }
        updatePreview()
    }

    fun setPreviewVisible(visible: Boolean) {
        if (visible && !isExpanded) setExpanded(true)
        isPreviewVisible = visible
        previewContainer?.visibility = if (visible) View.VISIBLE else View.GONE
        previewToggle?.setImageResource(
            if (visible) R.drawable.ic_overlay_steps else R.drawable.ic_overlay_preview
        )
        previewToggle?.contentDescription = context.getString(
            if (visible) R.string.overlay_show_steps else R.string.overlay_show_preview
        )
        updatePreview()
    }

    private fun updatePreview() {
        if (isAttached && isExpanded && isPreviewVisible) preview?.start() else preview?.stop()
    }

    fun isAttachedForTest(): Boolean = isAttached
    fun isExpandedForTest(): Boolean = isExpanded
    fun isPreparedForTest(): Boolean = isPrepared
    fun isPreviewVisibleForTest(): Boolean = isPreviewVisible
    fun isPreviewRunningForTest(): Boolean = preview?.isRunning == true

    // ── State updates ──────────────────────────────────────────────────────

    fun updateState(state: AgentState) {
        Log.d(TAG, "updateState: $state isAttached=$isAttached isExpanded=$isExpanded")
        _currentState = state
        when (state) {
            is AgentState.Running -> {
                currentStep = state.step
                pillStep?.text = runningStepText(state.step)
                pillAction?.text = state.thought
                pillAction?.visibility = if (state.thought.isBlank()) View.GONE else View.VISIBLE
                pillStop?.visibility = View.VISIBLE
            }
            is AgentState.WaitingForUser -> {
                pillStep?.text = context.getString(R.string.overlay_waiting_answer)
                setExpanded(true)
            }
            is AgentState.WaitingForHandoff -> {
                pillStep?.text = context.getString(R.string.overlay_waiting_handoff)
                setExpanded(true)
            }
            is AgentState.Paused -> {
                pillStep?.text = if (debugMode) context.getString(R.string.overlay_paused_takeover) else context.getString(R.string.overlay_paused)
            }
            is AgentState.Idle -> {
                val idle = state as AgentState.Idle
                if (idle.resultMessage != null) {
                    pillStep?.text = context.getString(R.string.overlay_done)
                    pillStop?.visibility = View.GONE
                }
            }
            is AgentState.Error -> {
                pillStep?.text = context.getString(R.string.overlay_failed)
                pillStop?.visibility = View.GONE
            }
            else -> {}
        }
        applyStateColors()
    }

    fun addStepResult(result: StepResult) {
        currentStep = result.step
        if (_currentState is AgentState.Running) {
            pillStep?.text = runningStepText(result.step)
            pillAction?.text = result.detail
            pillAction?.visibility = if (result.detail.isBlank()) View.GONE else View.VISIBLE
        }
    }

    private fun applyStateColors() {
        val color = when (_currentState) {
            is AgentState.Running -> colorRunning
            is AgentState.WaitingForUser -> colorWaiting
            is AgentState.WaitingForHandoff -> colorWaiting
            is AgentState.Paused -> colorPaused
            is AgentState.Error -> colorError
            is AgentState.Idle -> if ((_currentState as AgentState.Idle).resultMessage != null) colorSuccess else colorIdle
            else -> colorIdle
        }
        (pillDot?.background as? GradientDrawable)?.setColor(color)
        (chatHeadStatus?.background as? GradientDrawable)?.setColor(color)
    }

    private fun refreshPillText() {
        pillTask?.text = trimTask(currentInstruction)
        when (val s = _currentState) {
            is AgentState.Running -> pillStep?.text = runningStepText(currentStep)
            is AgentState.WaitingForUser -> pillStep?.text = context.getString(R.string.overlay_waiting_answer)
            is AgentState.WaitingForHandoff -> pillStep?.text = context.getString(R.string.overlay_waiting_handoff)
            is AgentState.Paused -> pillStep?.text = if (debugMode) context.getString(R.string.overlay_paused_takeover) else context.getString(R.string.overlay_paused)
            else -> {}
        }
    }

    private fun startStatusAnimation() {
        dotAnimator = ObjectAnimator.ofFloat(pillDot, "alpha", 1f, 0.4f, 1f).apply {
            duration = 1500
            repeatCount = ValueAnimator.INFINITE
            interpolator = AccelerateDecelerateInterpolator()
            start()
        }
    }

    private fun trimTask(text: String): String {
        if (text.isBlank()) return context.getString(R.string.app_name)
        return if (text.length > 20) text.take(20) + "…" else text
    }

    private fun runningStepText(step: Int): String = if (debugMode) {
        context.getString(R.string.overlay_step_debug, step, maxSteps)
    } else {
        context.getString(R.string.overlay_step_running, step)
    }

    private fun dp(value: Int): Int = (value * context.resources.displayMetrics.density).toInt()

    // ── Drag handling ──────────────────────────────────────────────────────

    @SuppressLint("ClickableViewAccessibility")
    private fun setupDragging(view: View?, params: WindowManager.LayoutParams) {
        var initialX = 0
        var initialY = 0
        var initialTouchX = 0f
        var initialTouchY = 0f
        var isDragging = false

        view?.setOnTouchListener { v, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    initialX = params.x
                    initialY = params.y
                    initialTouchX = event.rawX
                    initialTouchY = event.rawY
                    isDragging = false
                    false
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = (event.rawX - initialTouchX).toInt()
                    val dy = (event.rawY - initialTouchY).toInt()
                    if (!isDragging) {
                        if (dx * dx + dy * dy > dragSlop * dragSlop) {
                            isDragging = true
                            v.cancelLongPress()
                            v.isPressed = false
                        } else return@setOnTouchListener false
                    }
                    params.x = initialX - dx
                    params.y = initialY + dy
                    try {
                        if (v.isAttachedToWindow) {
                            windowManager.updateViewLayout(minimizedView, params)
                        }
                    } catch (_: Exception) {}
                    true
                }
                MotionEvent.ACTION_UP -> isDragging
                else -> false
            }
        }
    }

    companion object {
        private const val TAG = "OverlayWindow"
    }
}
