package ai.opencyvis.ui

import android.content.Context
import android.os.Looper
import android.widget.EditText
import ai.opencyvis.App
import ai.opencyvis.R
import ai.opencyvis.SettingsActivity
import ai.opencyvis.SettingsFragment
import ai.opencyvis.config.ConfigRepository
import ai.opencyvis.config.TeamConfig
import androidx.appcompat.app.AlertDialog
import androidx.preference.Preference
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode
import org.robolectric.shadows.ShadowDialog

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@ConscryptMode(ConscryptMode.Mode.OFF)
class TeamSettingsUiTest {
    private val context get() = ApplicationProvider.getApplicationContext<Context>()

    @Before fun setUp() {
        context.getSharedPreferences("opencyvis_config", Context.MODE_PRIVATE).edit().clear().commit()
        App.imPairingManager = null
    }

    @After fun tearDown() { App.imPairingManager = null }

    @Test fun openingOrCancellingPresetsDoesNotEnableTeamModeButSavingVisibleValuesDoes() {
        withSettings { fragment, config ->
            val preference = requireNotNull(fragment.findPreference<Preference>("team_mode"))
            assertEquals(context.getString(R.string.pref_team_disabled), preference.summary.toString())
            val first = open(preference)
            assertEquals("1000000", field(first, "team_total_budget").text.toString())
            assertEquals("65536", field(first, "team_finalization_reserve").text.toString())
            assertEquals("1", field(first, "team_max_children").text.toString())
            assertEquals("12", field(first, "team_child_steps").text.toString())
            assertNull(config.teamConfig)
            first.getButton(AlertDialog.BUTTON_NEGATIVE).performClick()
            shadowOf(Looper.getMainLooper()).idle()
            assertNull(config.teamConfig)

            val editor = open(preference)
            field(editor, "team_total_budget").setText("2000000")
            field(editor, "team_finalization_reserve").setText("100000")
            field(editor, "team_max_children").setText("2")
            field(editor, "team_child_steps").setText("7")
            editor.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
            assertEquals(TeamConfig(2_000_000, 100_000, 2, 7), config.teamConfig)
            assertFalse(editor.isShowing)
            assertEquals(context.getString(R.string.pref_team_enabled_summary, 2_000_000L, 2), preference.summary.toString())
        }
    }

    @Test fun invalidEditsKeepSavedLimitsAndTurningOffRemovesThem() {
        val saved = TeamConfig(800_000, 50_000, 1, 10)
        ConfigRepository(context).teamConfig = saved
        withSettings { fragment, config ->
            val preference = requireNotNull(fragment.findPreference<Preference>("team_mode"))
            val editor = open(preference)
            field(editor, "team_max_children").setText("3")
            editor.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
            assertTrue(editor.isShowing)
            assertEquals(saved, config.teamConfig)
            editor.getButton(AlertDialog.BUTTON_NEUTRAL).performClick()
            shadowOf(Looper.getMainLooper()).idle()
            assertNull(config.teamConfig)
            assertEquals(context.getString(R.string.pref_team_disabled), preference.summary.toString())
        }
    }

    private fun withSettings(block: (SettingsFragment, ConfigRepository) -> Unit) {
        val controller = Robolectric.buildActivity(SettingsActivity::class.java).setup()
        try {
            val activity = controller.get()
            activity.supportFragmentManager.executePendingTransactions()
            shadowOf(Looper.getMainLooper()).idle()
            block(activity.supportFragmentManager.findFragmentById(R.id.settings_container) as SettingsFragment,
                ConfigRepository(activity))
        } finally { controller.pause().stop().destroy() }
    }

    private fun open(preference: Preference): AlertDialog {
        assertTrue(preference.onPreferenceClickListener!!.onPreferenceClick(preference))
        shadowOf(Looper.getMainLooper()).idle()
        return ShadowDialog.getLatestDialog() as AlertDialog
    }

    private fun field(dialog: AlertDialog, tag: String): EditText = dialog.window!!.decorView.findViewWithTag(tag)
}
