package ai.opencyvis.ui

import android.content.Context
import android.content.res.ColorStateList
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView
import ai.opencyvis.R
import ai.opencyvis.memory.MemoryItem
import com.google.android.material.chip.Chip
import org.json.JSONObject

class MemoryAdapter(
    private val onClick: (MemoryItem) -> Unit,
    private val onLongClick: (MemoryItem) -> Unit
) : RecyclerView.Adapter<MemoryAdapter.ViewHolder>() {
    private val items = mutableListOf<MemoryItem>()
    private var confirmations: Map<String, Int> = emptyMap()
    private var presentation: MemoryPresentation? = null

    fun submitList(memories: List<MemoryItem>, confirmedRevisions: Map<String, Int> = emptyMap()) {
        confirmations = confirmedRevisions
        items.clear(); items.addAll(memories); notifyDataSetChanged()
    }
    fun isConfirmed(memory: MemoryItem): Boolean = confirmations[memory.id] == memory.revision
    override fun getItemCount(): Int = items.size
    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) = ViewHolder(
        LayoutInflater.from(parent.context).inflate(R.layout.item_memory, parent, false))

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val memory = items[position]
        val context = holder.itemView.context
        val display = presentation ?: MemoryPresentation(context).also { presentation = it }
        val body = JSONObject(memory.bodyJson)
        holder.itemView.setBackgroundResource(if (memory.type == "procedure")
            R.drawable.bg_memory_method else R.drawable.bg_memory_knowledge)
        holder.itemView.findViewById<Chip>(R.id.text_memory_type).apply {
            text = display.type(memory)
            setChipIconResource(if (memory.type == "procedure") R.drawable.ic_ui_learning else R.drawable.ic_ui_memory)
        }
        holder.itemView.findViewById<TextView>(R.id.text_memory_classification).text = display.classification(memory, body)
        holder.itemView.findViewById<TextView>(R.id.text_key).text = memory.title
        holder.itemView.findViewById<TextView>(R.id.text_value).text = memory.summary
        holder.itemView.findViewById<TextView>(R.id.text_memory_source).text = display.row(R.string.mp_source, display.source(memory))
        holder.itemView.findViewById<TextView>(R.id.text_memory_confirmation).text =
            display.row(R.string.mp_confirmation, display.confirmation(memory, isConfirmed(memory)))
        holder.itemView.findViewById<TextView>(R.id.text_memory_observation).text =
            display.row(R.string.mp_run_verification, display.observation(memory))
        holder.itemView.findViewById<TextView>(R.id.text_memory_scope).text = display.row(R.string.mp_scope, display.appScope(memory))
        holder.itemView.findViewById<TextView>(R.id.text_meta).text = display.updated(memory)
        holder.itemView.findViewById<Chip>(R.id.text_enabled).apply {
            text = MemoryUi.label(context, memory.status)
            val (foreground, background) = when (memory.status) {
                "active" -> R.color.color_success to R.color.success_container
                "candidate", "needs_review" -> R.color.on_brand_container to R.color.brand_container
                else -> R.color.text_caption to R.color.surface_container
            }
            setTextColor(ContextCompat.getColor(context, foreground))
            chipBackgroundColor = ColorStateList.valueOf(ContextCompat.getColor(context, background))
        }
        holder.itemView.setOnClickListener { onClick(memory) }
        holder.itemView.setOnLongClickListener { onLongClick(memory); true }
    }

    fun metadata(context: Context, memory: MemoryItem): String =
        (presentation ?: MemoryPresentation(context).also { presentation = it }).metadata(memory, isConfirmed(memory))

    class ViewHolder(view: View) : RecyclerView.ViewHolder(view)
}
