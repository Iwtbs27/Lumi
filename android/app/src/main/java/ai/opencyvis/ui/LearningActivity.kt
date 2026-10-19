package ai.opencyvis.ui

import android.app.AlertDialog
import android.content.Intent
import android.media.projection.MediaProjectionConfig
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.Spinner
import android.widget.ArrayAdapter
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import ai.opencyvis.App
import ai.opencyvis.R
import ai.opencyvis.accessibility.VdAccessibilityService
import ai.opencyvis.learning.LearningObservationService
import ai.opencyvis.learning.LearningRecorder
import ai.opencyvis.learning.LearningRepository
import ai.opencyvis.learning.LearningSession
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject

/** Recording entrances and session history; result editing lives in LearningReview. */
class LearningActivity : AppCompatActivity() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private lateinit var repository: LearningRepository
    private lateinit var review: LearningReview
    private lateinit var content: LinearLayout
    private lateinit var title: TextView
    private var selectedId: String? = null
    private var taskReviewsOnly = false
    private var observationGoal = ""
    private var observationPackages = arrayListOf<String>()
    private var loadJob: Job? = null
    private var lastRevision = ""

    private val projectionConsent = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == RESULT_OK && result.data != null) {
            ContextCompat.startForegroundService(this, Intent(this, LearningObservationService::class.java)
                .setAction(LearningObservationService.ACTION_START)
                .putExtra(LearningObservationService.EXTRA_PROJECTION, result.data)
                .putExtra(LearningObservationService.EXTRA_GOAL, observationGoal)
                .putStringArrayListExtra(LearningObservationService.EXTRA_PACKAGES, observationPackages))
            Toast.makeText(this, R.string.learning_waiting_app, Toast.LENGTH_LONG).show()
        } else Toast.makeText(this, R.string.learning_observation_denied, Toast.LENGTH_LONG).show()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        repository = LearningRepository(this)
        review = LearningReview(this, scope, repository) { lastRevision = ""; reload() }
        selectedId = savedInstanceState?.getString("session_id") ?: intent.getStringExtra("session_id")
        taskReviewsOnly = savedInstanceState?.getBoolean("task_reviews_only") ?: false
        observationGoal = savedInstanceState?.getString("observation_goal").orEmpty()
        observationPackages = savedInstanceState?.getStringArrayList("observation_packages") ?: arrayListOf()
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            fitsSystemWindows = true
            setBackgroundColor(getColor(R.color.surface))
        }
        val header = LinearLayout(this).apply {
            gravity = android.view.Gravity.CENTER_VERTICAL
            minimumHeight = dp(72)
            setPadding(dp(12), dp(8), dp(20), dp(8))
        }
        header.addView(ImageButton(this).apply {
            setImageResource(R.drawable.ic_ui_back)
            imageTintList = android.content.res.ColorStateList.valueOf(getColor(R.color.on_surface))
            contentDescription = getString(R.string.learning_back)
            setBackgroundResource(R.drawable.bg_ui_tonal)
            setPadding(dp(12), dp(12), dp(12), dp(12))
            setOnClickListener { navigateBack() }
        }, LinearLayout.LayoutParams(dp(48), dp(48)))
        title = TextView(this).apply {
            setText(R.string.learning_title)
            textSize = 26f
            typeface = android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL)
            setTextColor(getColor(R.color.on_surface))
            isAccessibilityHeading = true
        }
        header.addView(title, LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = dp(16) })
        header.addView(ImageView(this).apply {
            setImageResource(R.drawable.ic_ui_logo)
            importantForAccessibility = android.view.View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }, LinearLayout.LayoutParams(dp(40), dp(40)).apply { marginStart = dp(12) })
        root.addView(header)
        content = MemoryUi.column(this)
        root.addView(MemoryUi.scroll(content), LinearLayout.LayoutParams(-1, 0, 1f))
        setContentView(root)
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() = navigateBack()
        })
    }

    private fun navigateBack() {
        when {
            selectedId != null -> { selectedId = null; lastRevision = ""; reload() }
            taskReviewsOnly -> { taskReviewsOnly = false; lastRevision = ""; reload() }
            else -> finish()
        }
    }

    override fun onStart() { super.onStart(); reload() }
    override fun onStop() { loadJob?.cancel(); super.onStop() }
    override fun onDestroy() { scope.cancel(); super.onDestroy() }
    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString("session_id", selectedId)
        outState.putBoolean("task_reviews_only", taskReviewsOnly)
        outState.putString("observation_goal", observationGoal)
        outState.putStringArrayList("observation_packages", observationPackages)
        super.onSaveInstanceState(outState)
    }

    private fun reload() {
        loadJob?.cancel()
        loadJob = scope.launch {
            try {
                repository.recover()
                repository.observeAll().collect { sessions ->
                    val selected = sessions.firstOrNull { it.id == selectedId }
                    val revision = selected?.let { "${it.id}:${it.updatedAt}:${it.status}" }
                        ?: sessions.joinToString { "${it.id}:${it.updatedAt}:${it.status}" }
                    if (revision == lastRevision && content.childCount > 0) return@collect
                    lastRevision = revision
                    content.removeAllViews()
                    when {
                        selected != null -> showSession(selected)
                        taskReviewsOnly -> showTaskReviews(sessions)
                        else -> showHome(sessions)
                    }
                }
            } catch (error: Exception) { MemoryUi.error(this@LearningActivity, error) }
        }
    }

    private fun showHome(sessions: List<LearningSession>) {
        title.setText(R.string.learning_title)
        MemoryUi.text(content, getString(R.string.learning_intro))
        heading(R.string.mui_choose_mode)
        val teaching = MemoryUi.card(content, tonal = true)
        modeHeading(teaching, R.string.learning_teach, R.drawable.ic_ui_learning)
        MemoryUi.text(teaching, getString(R.string.mui_teach_description))
        MemoryUi.action(teaching, R.string.learning_start, primary = true) { startTeaching() }
        modeRow(R.string.learning_task_review, R.string.mui_correct_description,
            R.drawable.ic_ui_check, R.string.lumi_learning_reviews_open) {
            taskReviewsOnly = true
            lastRevision = ""
            reload()
        }
        modeRow(R.string.learning_observe, R.string.mui_observe_description,
            R.drawable.ic_ui_search, R.string.lumi_learning_observation_start) { startObservation() }
        heading(R.string.learning_history)
        if (sessions.isEmpty()) MemoryUi.text(MemoryUi.card(content), getString(R.string.learning_empty))
        showSessions(sessions)
    }

    private fun showTaskReviews(sessions: List<LearningSession>) {
        title.setText(R.string.learning_task_review)
        MemoryUi.text(content, getString(R.string.learning_task_review_help))
        val reviews = sessions.filter { it.mode == "correction" }
        if (reviews.isEmpty()) MemoryUi.text(MemoryUi.card(content), getString(R.string.learning_task_review_empty))
        showSessions(reviews)
    }

    private fun showSessions(sessions: List<LearningSession>) {
        sessions.forEach { session ->
            val card = MemoryUi.card(content)
            val header = LinearLayout(this).apply { gravity = android.view.Gravity.CENTER_VERTICAL }
            card.addView(header)
            MemoryUi.caption(header, "${LearningReview.modeLabel(this, session)} · ${MemoryUi.date(session.createdAt)}").apply {
                layoutParams = LinearLayout.LayoutParams(0, -2, 1f)
            }
            header.addView(ImageButton(this).apply {
                setImageResource(R.drawable.ic_ui_delete)
                imageTintList = android.content.res.ColorStateList.valueOf(getColor(R.color.color_danger))
                setBackgroundResource(R.drawable.bg_ui_tonal)
                setPadding(dp(12), dp(12), dp(12), dp(12))
                contentDescription = getString(R.string.learning_delete_description, session.goal)
                setOnClickListener { confirmDelete(session) }
            }, LinearLayout.LayoutParams(dp(48), dp(48)).apply { marginStart = dp(12) })
            MemoryUi.heading(card, session.goal.ifBlank { getString(R.string.learning_unknown_goal) })
                .setPadding(0, dp(4), 0, dp(6))
            LearningReview.statusBadge(card, session.status)
            MemoryUi.action(card, R.string.mui_session_open) { selectedId = session.id; lastRevision = ""; reload() }
        }
    }

    private fun modeHeading(column: LinearLayout, label: Int, icon: Int) {
        MemoryUi.heading(column, getString(label)).apply {
            setPadding(0, 0, 0, dp(6))
            setCompoundDrawablesRelativeWithIntrinsicBounds(icon, 0, 0, 0)
            compoundDrawableTintList = android.content.res.ColorStateList.valueOf(getColor(R.color.color_accent))
            compoundDrawablePadding = dp(10)
        }
    }

    private fun modeRow(label: Int, description: Int, icon: Int, actionLabel: Int, action: () -> Unit) {
        val card = MemoryUi.card(content)
        modeHeading(card, label, icon)
        MemoryUi.text(card, getString(description))
        MemoryUi.action(card, actionLabel, onClick = action).apply {
            setCompoundDrawablesRelativeWithIntrinsicBounds(0, 0, R.drawable.ic_ui_chevron, 0)
            compoundDrawableTintList = android.content.res.ColorStateList.valueOf(getColor(R.color.on_brand_container))
            compoundDrawablePadding = dp(12)
        }
    }

    private fun showSession(session: LearningSession) {
        title.text = LearningReview.modeLabel(this, session)
        val automaticReview = JSONObject(session.sourceJson).optBoolean("automatic_review")
        val summary = MemoryUi.card(content, tonal = true)
        MemoryUi.heading(summary, session.goal.ifBlank { getString(R.string.learning_unknown_goal) })
            .setPadding(0, 0, 0, dp(8))
        LearningReview.statusBadge(summary, session.status)
        LearningReview.statusHelp(this, session.status)?.let { MemoryUi.text(summary, it) }
        MemoryUi.caption(summary, MemoryUi.date(session.createdAt))
        MemoryUi.caption(summary, getString(R.string.learning_counts, session.eventCount, session.frameCount))
        if (LearningRecorder.get(this).active.value?.id == session.id) {
            heading(R.string.mui_recording_controls)
            if (session.mode != "observation") button(if (automaticReview) R.string.learning_return_task else R.string.learning_return_demo) {
                startActivity(Intent(this, ViewActivity::class.java))
            }
            if (session.status in setOf("recording", "paused")) button(if (session.status == "paused") R.string.learning_resume else R.string.learning_pause) {
                run {
                    val paused = session.status != "paused"
                    if (session.mode == "observation") LearningObservationService.command(this@LearningActivity,
                        if (paused) LearningObservationService.ACTION_PAUSE else LearningObservationService.ACTION_RESUME)
                    else requireService().pauseLearning(paused)
                }
            }
            if (automaticReview) {
                MemoryUi.caption(content, getString(R.string.learning_task_review_recording_help))
                button(R.string.learning_return_agent) { run { requireService().completeUserHandoff("learning_page_return") } }
            } else button(R.string.learning_finish) { finishRecording(session) }
        } else {
            review.render(content, session)
        }
        if (session.status != "discarded") {
            heading(R.string.mui_source_records)
            MemoryUi.caption(content, getString(R.string.mui_source_description))
            button(R.string.learning_evidence) { review.showFrames(session) }
            button(R.string.learning_events) { review.showEvents(session) }
        }
        heading(R.string.mui_manage_session)
        MemoryUi.caption(content, getString(R.string.learning_discard_hint))
        if (session.status != "saved" && session.status != "discarded") button(R.string.learning_discard) {
            AlertDialog.Builder(this).setTitle(R.string.learning_discard).setMessage(R.string.learning_discard_body)
                .setPositiveButton(R.string.learning_discard) { _, _ -> run {
                    if (LearningRecorder.get(this@LearningActivity).active.value?.id == session.id) {
                        if (session.mode == "observation") LearningObservationService.command(this@LearningActivity, LearningObservationService.ACTION_DISCARD)
                        else requireService().finishLearning(discard = true)
                    } else repository.discard(session.id)
                } }.setNegativeButton(R.string.memory_dialog_cancel, null).show()
        }
        if (session.status !in setOf("recording", "paused", "discarded")) {
            button(R.string.learning_export) { review.export(session) }
        }
        MemoryUi.action(content, R.string.learning_delete_record, danger = true) { confirmDelete(session) }
    }

    private fun confirmDelete(session: LearningSession) {
        if (LearningRecorder.get(this).active.value?.id == session.id) {
            Toast.makeText(this, R.string.learning_delete_active, Toast.LENGTH_LONG).show()
            return
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.learning_delete_title)
            .setMessage(getString(R.string.learning_delete_body,
                session.goal.ifBlank { getString(R.string.learning_unknown_goal) }))
            .setPositiveButton(R.string.learning_delete_record) { _, _ -> run {
                repository.delete(session.id)
                if (selectedId == session.id) selectedId = null
                lastRevision = ""
                reload()
                Toast.makeText(this@LearningActivity, R.string.learning_delete_done, Toast.LENGTH_SHORT).show()
            } }
            .setNegativeButton(R.string.memory_dialog_cancel, null)
            .show()
    }

    private fun startTeaching() = run {
        requireIdleRecorder()
        val apps = apps()
        val column = MemoryUi.column(this@LearningActivity)
        val goal = MemoryUi.field(column, getString(R.string.learning_goal)).apply { setHint(R.string.learning_goal_hint) }
        MemoryUi.text(column, getString(R.string.learning_app))
        val picker = Spinner(this@LearningActivity).apply {
            adapter = ArrayAdapter(this@LearningActivity, android.R.layout.simple_spinner_dropdown_item, apps.map { it.first })
            column.addView(this)
        }
        val dialog = AlertDialog.Builder(this@LearningActivity).setTitle(R.string.learning_teach)
            .setView(MemoryUi.scroll(column)).setPositiveButton(R.string.learning_start, null)
            .setNegativeButton(R.string.memory_dialog_cancel, null).create()
        dialog.setOnShowListener { dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            if (goal.text.isBlank()) { goal.error = getString(R.string.learning_goal_required); return@setOnClickListener }
            val start = dialog.getButton(AlertDialog.BUTTON_POSITIVE)
            start.isEnabled = false
            start.setText(R.string.mem_loading)
            run {
                try {
                    requireService().startTeaching(goal.text.toString().trim(), apps[picker.selectedItemPosition].second)
                    dialog.dismiss()
                    startActivity(Intent(this@LearningActivity, ViewActivity::class.java))
                } finally {
                    start.isEnabled = true
                    start.setText(R.string.learning_start)
                }
            }
        } }
        dialog.show()
    }

    private fun startObservation() = run {
        requireIdleRecorder()
        val agent = App.agentService
        check(agent?.isAgentBusy != true && agent?.isLearningStarting != true) {
            getString(R.string.learning_observation_agent_busy)
        }
        if (VdAccessibilityService.instance == null) {
            AlertDialog.Builder(this@LearningActivity).setMessage(R.string.learning_a11y_required)
                .setPositiveButton(R.string.learning_open_settings) { _, _ -> startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
                .setNegativeButton(R.string.memory_dialog_cancel, null).show()
            return@run
        }
        val column = MemoryUi.column(this@LearningActivity)
        MemoryUi.text(column, getString(R.string.learning_scope_help))
        MemoryUi.text(column, getString(R.string.learning_local_only))
        val goal = MemoryUi.field(column, getString(R.string.learning_goal), observationGoal)
        AlertDialog.Builder(this@LearningActivity).setTitle(R.string.learning_observe).setView(MemoryUi.scroll(column))
            .setPositiveButton(R.string.learning_start_observation) { _, _ ->
                observationGoal = goal.text.toString().trim()
                chooseObservationApps()
            }.setNegativeButton(R.string.memory_dialog_cancel, null).show()
    }

    private fun chooseObservationApps() = run {
        val apps = apps()
        val selected = BooleanArray(apps.size) { apps[it].second in observationPackages }
        val dialog = AlertDialog.Builder(this@LearningActivity).setTitle(R.string.learning_scope)
            .setMultiChoiceItems(apps.map { it.first }.toTypedArray(), selected) { _, index, checked -> selected[index] = checked }
            .setPositiveButton(R.string.learning_start_observation, null)
            .setNegativeButton(R.string.memory_dialog_cancel, null).create()
        dialog.setOnShowListener { dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            observationPackages = ArrayList(apps.filterIndexed { index, _ -> selected[index] }.map { it.second })
            if (observationPackages.isEmpty()) {
                Toast.makeText(this@LearningActivity, R.string.learning_scope_required, Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            val manager = getSystemService(MediaProjectionManager::class.java)
            val request = if (Build.VERSION.SDK_INT >= 34) manager.createScreenCaptureIntent(MediaProjectionConfig.createConfigForDefaultDisplay())
                else manager.createScreenCaptureIntent()
            projectionConsent.launch(request)
            dialog.dismiss()
        } }
        dialog.show()
    }

    private fun finishRecording(session: LearningSession) {
        val column = MemoryUi.column(this)
        val note = MemoryUi.field(column, getString(R.string.learning_note)).apply { setHint(R.string.learning_note_hint) }
        AlertDialog.Builder(this).setTitle(R.string.learning_finish).setView(MemoryUi.scroll(column))
            .setPositiveButton(R.string.learning_finish) { _, _ -> run {
                if (session.mode == "observation") startService(Intent(this@LearningActivity, LearningObservationService::class.java)
                    .setAction(LearningObservationService.ACTION_FINISH).putExtra("note", note.text.toString()))
                else requireService().finishLearning(note.text.toString())
            } }.setNegativeButton(R.string.memory_dialog_cancel, null).show()
    }

    private suspend fun apps(): List<Pair<String, String>> = withContext(Dispatchers.IO) {
        packageManager.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0)
            .filter { it.activityInfo.packageName != packageName }
            .map { it.loadLabel(packageManager).toString() to it.activityInfo.packageName }
            .distinctBy { it.second }.sortedBy { it.first }
    }

    private fun requireIdleRecorder() { check(LearningRecorder.get(this).active.value == null) { getString(R.string.learning_busy) } }
    private fun requireService() = checkNotNull(App.agentService) { getString(R.string.learning_backend_required) }
    private fun run(block: suspend () -> Unit) = scope.launch {
        try { block() } catch (error: Exception) { MemoryUi.error(this@LearningActivity, error) }
    }
    private fun heading(label: Int) { MemoryUi.heading(content, getString(label)) }
    private fun button(label: Int, action: () -> Unit) {
        MemoryUi.action(content, label, primary = label == R.string.learning_finish || label == R.string.learning_return_agent,
            danger = label == R.string.learning_discard, onClick = action)
    }
    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
}
