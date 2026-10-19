package ai.opencyvis.execution

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.provider.DocumentsContract
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.preference.Preference
import androidx.preference.PreferenceFragmentCompat
import ai.opencyvis.R
import ai.opencyvis.execution.api.AndroidApiCapabilities

/** User-selected grants are reusable by direct tools and code through the same Android adapters. */
class ExecutionAccessPreferences(private val fragment: PreferenceFragmentCompat) {
    private val context get() = fragment.requireContext()
    private val treePicker = fragment.registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            val intent = result.data
            val uri = intent?.data
            if (uri != null) {
                try { AndroidApiCapabilities.persistTreeGrant(context, uri, intent.flags) }
                catch (failure: Exception) { Toast.makeText(context, failure.message, Toast.LENGTH_LONG).show() }
            }
        }
        refresh()
    }
    private val calendarPermission = fragment.registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { refresh() }

    fun bind() {
        fragment.findPreference<Preference>("execution_files")?.setOnPreferenceClickListener { showFolders(); true }
        fragment.findPreference<Preference>("execution_calendar")?.setOnPreferenceClickListener {
            calendarPermission.launch(arrayOf(Manifest.permission.READ_CALENDAR, Manifest.permission.WRITE_CALENDAR))
            true
        }
        refresh()
    }

    fun refresh() {
        if (!fragment.isAdded) return
        val grants = context.contentResolver.persistedUriPermissions.filter { DocumentsContract.isTreeUri(it.uri) }
        fragment.findPreference<Preference>("execution_files")?.summary = if (grants.isEmpty())
            fragment.getString(R.string.pref_execution_files_unset)
            else fragment.getString(R.string.pref_execution_files_count, grants.size)
        val calendarReady = listOf(Manifest.permission.READ_CALENDAR, Manifest.permission.WRITE_CALENDAR)
            .all { context.checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED }
        fragment.findPreference<Preference>("execution_calendar")?.setSummary(
            if (calendarReady) R.string.pref_execution_calendar_ready else R.string.pref_execution_calendar_unset)
    }

    private fun showFolders() {
        val grants = context.contentResolver.persistedUriPermissions.filter { DocumentsContract.isTreeUri(it.uri) }
        val dialog = AlertDialog.Builder(context).setTitle(R.string.pref_execution_files)
            .setPositiveButton(R.string.pref_execution_add_folder) { _, _ ->
                treePicker.launch(Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or
                    Intent.FLAG_GRANT_WRITE_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION or Intent.FLAG_GRANT_PREFIX_URI_PERMISSION))
            }.setNegativeButton(android.R.string.cancel, null)
        if (grants.isEmpty()) dialog.setMessage(R.string.pref_execution_files_unset)
        else dialog.setItems(grants.map { DocumentsContract.getTreeDocumentId(it.uri) }.toTypedArray()) { _, index ->
            val grant = grants[index]
            AlertDialog.Builder(context).setTitle(R.string.pref_execution_remove_folder)
                .setMessage(DocumentsContract.getTreeDocumentId(grant.uri))
                .setPositiveButton(R.string.pref_execution_remove) { _, _ ->
                    val flags = (if (grant.isReadPermission) Intent.FLAG_GRANT_READ_URI_PERMISSION else 0) or
                        (if (grant.isWritePermission) Intent.FLAG_GRANT_WRITE_URI_PERMISSION else 0)
                    context.contentResolver.releasePersistableUriPermission(grant.uri, flags)
                    refresh()
                }.setNegativeButton(android.R.string.cancel, null).show()
        }
        dialog.show()
    }
}
