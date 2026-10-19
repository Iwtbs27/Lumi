package ai.opencyvis.ui

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.widget.doAfterTextChanged
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import ai.opencyvis.R
import ai.opencyvis.db.ChatHistoryRepository
import ai.opencyvis.db.ConversationEntity
import com.google.android.material.chip.ChipGroup
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

class ConversationHistoryActivity : AppCompatActivity() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private lateinit var historyRepo: ChatHistoryRepository
    private lateinit var adapter: ConversationHistoryAdapter
    private var conversations: List<ConversationEntity> = emptyList()
    private var hasLoaded = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_conversation_history)
        AppNavigation.bind(this, R.id.nav_history)

        historyRepo = ChatHistoryRepository(this)

        adapter = ConversationHistoryAdapter(
            context = this,
            onClick = { conv -> openDetail(conv) },
            onLongClick = { conv -> confirmDelete(conv) }
        )

        findViewById<RecyclerView>(R.id.recycler_conversations).apply {
            layoutManager = LinearLayoutManager(this@ConversationHistoryActivity)
            adapter = this@ConversationHistoryActivity.adapter
        }

        findViewById<EditText>(R.id.history_search).doAfterTextChanged { applyFilters() }
        findViewById<ChipGroup>(R.id.history_filters).setOnCheckedStateChangeListener { _, _ -> applyFilters() }
    }

    override fun onResume() {
        super.onResume()
        loadConversations()
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private fun loadConversations() {
        scope.launch {
            conversations = historyRepo.getAllConversations()
            hasLoaded = true
            applyFilters()
        }
    }

    private fun applyFilters() {
        if (!hasLoaded) return
        val query = findViewById<EditText>(R.id.history_search).text.toString().trim()
        val status = when (findViewById<ChipGroup>(R.id.history_filters).checkedChipId) {
            R.id.history_filter_completed -> "completed"
            R.id.history_filter_running -> "running"
            R.id.history_filter_failed -> "failed"
            R.id.history_filter_stopped -> "stopped"
            else -> null
        }
        val filtered = conversations.filter {
            (status == null || it.status == status) && it.title.contains(query, ignoreCase = true)
        }
        adapter.submitList(filtered)
        findViewById<View>(R.id.history_empty).visibility = if (filtered.isEmpty()) View.VISIBLE else View.GONE
        findViewById<View>(R.id.history_help).visibility = if (filtered.isEmpty()) View.GONE else View.VISIBLE
        findViewById<TextView>(R.id.history_empty_title).setText(
            if (conversations.isEmpty()) R.string.hs_history_empty_title else R.string.hs_history_no_results_title
        )
        findViewById<TextView>(R.id.history_empty_body).setText(
            if (conversations.isEmpty()) R.string.hs_history_empty_body else R.string.hs_history_no_results_body
        )
    }

    private fun openDetail(conv: ConversationEntity) {
        val intent = Intent(this, ConversationDetailActivity::class.java).apply {
            putExtra("conversation_id", conv.id)
            putExtra("conversation_title", conv.title)
            putExtra("conversation_status", conv.status)
            putExtra("conversation_created_at", conv.createdAt)
        }
        startActivity(intent)
    }

    private fun confirmDelete(conv: ConversationEntity) {
        if (conv.status == "running") {
            Toast.makeText(this, R.string.mem_stop_before_delete, Toast.LENGTH_LONG).show()
            return
        }
        MemoryUi.deleteConversation(this) { deleteMemories ->
            scope.launch {
                try {
                    historyRepo.deleteConversation(conv.id, deleteMemories)
                    conversations = conversations.filterNot { it.id == conv.id }
                    applyFilters()
                } catch (error: Exception) { MemoryUi.error(this@ConversationHistoryActivity, error) }
            }
        }
    }
}
