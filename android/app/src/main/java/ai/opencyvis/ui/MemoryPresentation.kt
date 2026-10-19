package ai.opencyvis.ui

import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Typeface
import android.text.SpannableString
import android.text.Spanned
import android.text.style.StyleSpan
import ai.opencyvis.R
import ai.opencyvis.memory.MemoryItem
import org.json.JSONObject

/** Keeps provenance, human review and observed support independent in every memory view. */
internal class MemoryPresentation(private val context: Context) {
    private val appNames = mutableMapOf<String, String>()

    fun type(memory: MemoryItem): String = MemoryUi.label(context, memory.type)

    fun classification(memory: MemoryItem, body: JSONObject): String = when (memory.type) {
        "fact" -> when (body.optString("domain")) {
            "user_preference" -> context.getString(R.string.mem_user_preference)
            "app_knowledge" -> context.getString(R.string.mem_app_knowledge)
            else -> context.getString(R.string.mp_knowledge_description)
        }
        "procedure" -> context.getString(R.string.mp_method_structure,
            body.optJSONArray("nodes")?.length() ?: 0,
            body.optJSONArray("parameters")?.length() ?: 0)
        else -> memory.type
    }

    fun source(memory: MemoryItem): String {
        val resource = when (memory.origin) {
            "explicit_user" -> R.string.mp_source_user
            "user_edit" -> R.string.mp_source_edit
            "observed" -> R.string.mp_source_observed
            "inferred" -> R.string.mp_source_inferred
            "learned" -> R.string.mp_source_learned
            "legacy" -> R.string.mem_legacy
            else -> return MemoryUi.label(context, memory.origin)
        }
        return context.getString(resource)
    }

    fun confirmation(memory: MemoryItem, confirmed: Boolean): String = context.getString(when {
        confirmed -> R.string.mp_content_confirmed
        memory.evidenceLevel == "user_stated" -> R.string.mp_content_user_stated
        else -> R.string.mp_content_not_confirmed
    })

    fun observation(memory: MemoryItem): String = context.getString(when (memory.evidenceLevel) {
        "observed_once" -> R.string.mp_observed_once
        "observed_repeatedly" -> R.string.mp_observed_repeatedly
        else -> R.string.mp_not_run_verified
    })

    fun hasObservation(memory: MemoryItem): Boolean =
        memory.evidenceLevel in setOf("observed_once", "observed_repeatedly")

    fun appScope(memory: MemoryItem, includePackage: Boolean = false): String {
        val app = memory.appScope?.takeIf(String::isNotBlank)
            ?: return context.getString(R.string.mp_scope_general)
        val name = appNames.getOrPut(app) {
            try {
                val info = context.packageManager.getApplicationInfo(app, 0)
                context.packageManager.getApplicationLabel(info).toString()
            } catch (_: PackageManager.NameNotFoundException) { app }
        }
        return if (includePackage && name != app) "$name\n$app" else name
    }

    fun row(label: Int, value: String): CharSequence {
        val title = context.getString(label)
        return SpannableString(context.getString(R.string.mp_attribute, title, value)).apply {
            setSpan(StyleSpan(Typeface.BOLD), 0, title.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
    }

    fun updated(memory: MemoryItem): String = context.getString(R.string.mp_updated_revision,
        MemoryUi.date(memory.updatedAt), memory.revision)

    fun metadata(memory: MemoryItem, confirmed: Boolean): String = listOf(
        row(R.string.mp_source, source(memory)),
        row(R.string.mp_confirmation, confirmation(memory, confirmed)),
        row(R.string.mp_run_verification, observation(memory)),
        row(R.string.mp_scope, appScope(memory)),
        updated(memory)
    ).joinToString("\n")
}
