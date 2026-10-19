package ai.opencyvis.ui

import android.app.Activity
import android.content.Intent
import ai.opencyvis.R
import ai.opencyvis.SettingsActivity
import com.google.android.material.bottomnavigation.BottomNavigationView

/** The four top-level destinations share a single, shallow activity stack. */
object AppNavigation {
    fun bind(activity: Activity, selected: Int) {
        val navigation = activity.findViewById<BottomNavigationView>(R.id.bottom_navigation)
        navigation.selectedItemId = selected
        navigation.setOnItemSelectedListener { item ->
            if (item.itemId == selected) return@setOnItemSelectedListener true
            val destination = when (item.itemId) {
                R.id.nav_home -> ControlPanelActivity::class.java
                R.id.nav_history -> ConversationHistoryActivity::class.java
                R.id.nav_memory -> MemoryActivity::class.java
                R.id.nav_settings -> SettingsActivity::class.java
                else -> return@setOnItemSelectedListener false
            }
            activity.startActivity(Intent(activity, destination).apply {
                addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            })
            if (activity !is ControlPanelActivity) activity.finish()
            // Keep this page selected when returning to its existing instance.
            false
        }
    }
}
