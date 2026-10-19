package ai.opencyvis.debug

import android.app.Activity
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import ai.opencyvis.R

/** Offline, debug-only screen for exercising real accessibility input and exact readback. */
class GuiNoteFixtureActivity : Activity() {
    companion object {
        const val ACTION_SWITCH_PAGE_AFTER_TITLE = "ai.opencyvis.debug.SWITCH_PAGE_AFTER_TITLE"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val content = page("Note editor")
        val title = EditText(this).apply {
            id = R.id.gui_fixture_title
            hint = "Title"
            setSingleLine(true)
            setText("Seed title")
        }
        val body = EditText(this).apply {
            id = R.id.gui_fixture_body
            hint = "Body"
            minLines = 3
            maxLines = 5
            setText("Seed body")
        }
        val status = TextView(this).apply { id = R.id.gui_fixture_status; text = "Unsaved" }
        val markUnsaved = object : TextWatcher {
            override fun beforeTextChanged(text: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(text: CharSequence?, start: Int, before: Int, count: Int) = Unit
            override fun afterTextChanged(text: Editable?) { status.text = "Unsaved" }
        }
        title.addTextChangedListener(markUnsaved)
        body.addTextChangedListener(markUnsaved)
        val savedTitle = TextView(this).apply { id = R.id.gui_fixture_saved_title }
        val savedBody = TextView(this).apply { id = R.id.gui_fixture_saved_body }
        content.addView(title)
        content.addView(body)
        content.addView(Button(this).apply {
            id = R.id.gui_fixture_save
            text = "Save note"
            setOnClickListener {
                savedTitle.text = title.text.toString()
                savedBody.text = body.text.toString()
                status.text = "Saved"
            }
        })
        content.addView(status)
        content.addView(savedTitle)
        content.addView(savedBody)
        if (intent.action == ACTION_SWITCH_PAGE_AFTER_TITLE) {
            title.addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(text: CharSequence?, start: Int, count: Int, after: Int) = Unit
                override fun onTextChanged(text: CharSequence?, start: Int, before: Int, count: Int) = Unit
                override fun afterTextChanged(text: Editable?) {
                    val preview = page("Preview")
                    preview.addView(TextView(this@GuiNoteFixtureActivity).apply {
                        id = R.id.gui_fixture_title
                        this.text = title.text.toString()
                    })
                    preview.addView(TextView(this@GuiNoteFixtureActivity).apply {
                        id = R.id.gui_fixture_body
                        this.text = body.text.toString()
                    })
                }
            })
        }
    }

    private fun page(name: String): LinearLayout {
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val padding = (24 * resources.displayMetrics.density).toInt()
            setPadding(padding, padding, padding, padding)
            addView(TextView(this@GuiNoteFixtureActivity).apply {
                id = R.id.gui_fixture_page
                text = name
                // Expose the received mode through the same real accessibility path used by the test.
                contentDescription = intent.action
                textSize = 22f
            })
        }
        setContentView(ScrollView(this).apply { addView(content,
            ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)) })
        return content
    }
}
