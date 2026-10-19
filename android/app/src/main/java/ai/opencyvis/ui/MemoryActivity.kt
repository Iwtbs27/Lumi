package ai.opencyvis.ui

import android.app.AlertDialog
import android.content.Intent
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import ai.opencyvis.R
import ai.opencyvis.memory.MemoryItem
import ai.opencyvis.memory.MemoryRepository
import com.google.android.material.chip.ChipGroup
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import org.json.JSONObject

class MemoryActivity : AppCompatActivity() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private lateinit var repository: MemoryRepository
    private lateinit var adapter: MemoryAdapter
    private lateinit var searchInput: EditText
    private lateinit var editor: MemoryEditor
    private lateinit var inspector: MemoryInspector
    private var loadJob: Job? = null
    private var loadedMemories: List<MemoryItem> = emptyList()
    private var confirmedRevisions: Map<String, Int> = emptyMap()
    private var typeFilter: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_memory)
        AppNavigation.bind(this, R.id.nav_memory)
        repository = MemoryRepository(this)
        editor = MemoryEditor(this, scope, repository, ::loadMemories)
        inspector = MemoryInspector(this, scope, repository, ::loadMemories)
        searchInput = findViewById(R.id.edit_search)
        adapter = MemoryAdapter(::showMemory, ::showActions)
        findViewById<RecyclerView>(R.id.recycler_memories).apply {
            layoutManager = LinearLayoutManager(this@MemoryActivity)
            adapter = this@MemoryActivity.adapter
        }
        findViewById<Button>(R.id.btn_add_memory).setOnClickListener { editor.show(null) }
        findViewById<Button>(R.id.btn_memory_jobs).setOnClickListener { inspector.showJobs() }
        findViewById<Button>(R.id.btn_memory_episodes).setOnClickListener { inspector.showEpisodes(searchInput.text.toString()) }
        findViewById<Button>(R.id.btn_memory_learning).setOnClickListener {
            startActivity(Intent(this, LearningActivity::class.java))
        }
        findViewById<ChipGroup>(R.id.memory_type_filters).setOnCheckedStateChangeListener { _, checked ->
            typeFilter = when (checked.firstOrNull()) {
                R.id.filter_memory_knowledge -> "fact"
                R.id.filter_memory_methods -> "procedure"
                else -> null
            }
            renderMemories()
        }
        searchInput.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) { loadMemories() }
            override fun afterTextChanged(s: Editable?) {}
        })
    }

    override fun onResume() {
        super.onResume()
        loadMemories()
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private fun loadMemories() {
        loadJob?.cancel()
        loadJob = scope.launch {
            try {
                loadedMemories = repository.list(searchInput.text.toString())
                confirmedRevisions = repository.confirmedRevisions()
                renderMemories()
            } catch (error: Exception) { MemoryUi.error(this@MemoryActivity, error) }
        }
    }

    private fun renderMemories() {
        val memories = loadedMemories.filter { typeFilter == null || it.type == typeFilter }
        adapter.submitList(memories, confirmedRevisions)
        findViewById<TextView>(R.id.text_memory_count).text = getString(
            R.string.mui_memory_count, memories.size, memories.count { it.status == "active" })
        findViewById<TextView>(R.id.text_memory_empty).apply {
            setText(when {
                searchInput.text.isNotBlank() -> R.string.mui_search_empty
                typeFilter != null -> R.string.mp_filter_empty
                else -> R.string.mui_memory_empty
            })
            visibility = if (memories.isEmpty()) View.VISIBLE else View.GONE
        }
    }

    private fun showMemory(memory: MemoryItem) {
        val column = MemoryUi.column(this)
        var dialog: AlertDialog? = null
        val body = JSONObject(memory.bodyJson)
        MemoryDetails(this).populate(column, memory, body, adapter.isConfirmed(memory))
        fun button(label: Int, click: () -> Unit) {
            MemoryUi.action(column, label, primary = label == R.string.mem_confirm_enable, onClick = click)
        }
        if (!adapter.isConfirmed(memory)) {
            MemoryUi.text(column, getString(R.string.mp_confirm_help))
            button(R.string.mem_confirm_enable) {
                scope.launch {
                    try {
                        repository.confirmAndEnable(memory.id, memory.revision)
                        dialog?.dismiss()
                        loadMemories()
                    } catch (error: Exception) { MemoryUi.error(this@MemoryActivity, error) }
                }
            }
        }
        button(R.string.mem_sources) { inspector.showSources(memory) }
        button(R.string.mem_revisions) { dialog?.dismiss(); inspector.showRevisions(memory) }
        if (memory.type == "procedure" && memory.status == "active" && body.optString("maturity") == "validated") {
            button(R.string.mem_save_routine) { MemoryRoutine.save(this, scope, memory) }
        }
        val toggleLabel = when {
            memory.status == "active" -> R.string.memory_action_disable
            adapter.isConfirmed(memory) -> R.string.memory_action_enable
            else -> R.string.mem_enable_trial
        }
        dialog = AlertDialog.Builder(this).setTitle(memory.title).setView(MemoryUi.scroll(column))
            .setPositiveButton(R.string.mem_edit) { _, _ -> editor.show(memory) }
            .setNeutralButton(toggleLabel) { _, _ -> toggle(memory) }
            .setNegativeButton(R.string.mem_close, null).show()
    }

    private fun toggle(memory: MemoryItem) = scope.launch {
        try { repository.setEnabled(memory.id, memory.status != "active"); loadMemories() }
        catch (error: Exception) { MemoryUi.error(this@MemoryActivity, error) }
    }

    private fun showActions(memory: MemoryItem) {
        AlertDialog.Builder(this).setTitle(memory.title)
            .setItems(arrayOf(getString(R.string.mem_edit), getString(R.string.memory_action_delete))) { _, which ->
                if (which == 0) editor.show(memory) else AlertDialog.Builder(this)
                    .setTitle(R.string.mem_delete_title).setMessage(R.string.mem_delete_body)
                    .setPositiveButton(R.string.memory_action_delete) { _, _ ->
                        scope.launch {
                            try { repository.delete(memory.id); loadMemories() }
                            catch (error: Exception) { MemoryUi.error(this@MemoryActivity, error) }
                        }
                    }.setNegativeButton(R.string.memory_dialog_cancel, null).show()
            }.show()
    }
}
