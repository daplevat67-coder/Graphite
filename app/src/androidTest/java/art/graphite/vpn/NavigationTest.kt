package art.graphite.vpn

import android.content.Context
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test

/**
 * V0.2 smoke tests for a dedicated, fresh debug install with no saved profiles or running tunnel.
 * These tests never request VPN permission, start the native backend, import/export user files,
 * save or delete profiles, or take screenshots of secret fields. Only local appearance preferences
 * and an unsaved name-only draft are changed. Do not run against a user's populated installation.
 */
class NavigationTest {
    @get:Rule val rule = createAndroidComposeRule<MainActivity>()
    private val tabRole = SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Tab)
    private val switchRole = SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Switch)
    private fun text(resource: Int) = rule.activity.getString(resource)
    // The diagnostic page also has a Ping tab; the bottom navigation comes last in the tree.
    private fun tab(resource: Int) = rule.onAllNodes(hasText(text(resource)) and tabRole).onLast()
    private fun toggle(resource: Int) = rule.onNode(hasText(text(resource)) and switchRole)
    private fun back() {
        rule.activityRule.scenario.onActivity { it.onBackPressedDispatcher.onBackPressed() }
        rule.waitForIdle()
    }

    @Before fun requireFreshInstallAndDisableDecorativeMotion() {
        val profileDirectory = File(rule.activity.noBackupFilesDir, "wireguard-profiles")
        assertFalse("Fresh debug install required; saved profiles must not be present",
            profileDirectory.listFiles()?.any { it.name.endsWith(".gwp") } == true)
        rule.activity.getSharedPreferences("appearance",Context.MODE_PRIVATE).edit()
            .putBoolean("light",false).putBoolean("animations",false)
            .putBoolean("advanced",false).putBoolean("swipe",false).putBoolean("haptics",false).commit()
        rule.activityRule.scenario.recreate()
        rule.waitForIdle()
    }

    @Test fun fiveTabsWireGuardDetailsAndEmptyProfiles() {
        tab(R.string.home).assertIsSelected()
        tab(R.string.protocols).performClick()
        tab(R.string.protocols).assertIsSelected()
        rule.onNodeWithText("WireGuard").performScrollTo().performClick()
        rule.onNodeWithText(text(R.string.available)).assertExists()
        rule.onNodeWithText(rule.activity.getString(R.string.protocol_servers, "WireGuard")).performScrollTo().assertIsDisplayed()
        rule.onNodeWithText(text(R.string.profile_empty)).performScrollTo().assertIsDisplayed()
        // Opening the profile list performs no native/VPN operation.
        tab(R.string.servers).performClick()
        tab(R.string.servers).assertIsSelected()
        rule.onNodeWithText(text(R.string.profile_empty)).performScrollTo().assertIsDisplayed()
        tab(R.string.profile).performClick()
        tab(R.string.profile).assertIsSelected()
        rule.onNodeWithText(text(R.string.local_profile)).performScrollTo().assertIsDisplayed()
        tab(R.string.menu).performClick()
        tab(R.string.menu).assertIsSelected()
        toggle(R.string.advanced_mode).performScrollTo().assertIsOff()
        tab(R.string.home).performClick()
        rule.onNodeWithText(text(R.string.disconnected)).assertExists()
    }

    @Test fun manualEditorBackRequiresDiscardAndKeepsDraftWhenCancelled() {
        tab(R.string.menu).performClick()
        toggle(R.string.advanced_mode).performScrollTo().performClick()
        rule.onNodeWithText(text(R.string.advanced_profiles)).performScrollTo().performClick()
        rule.onNodeWithText(text(R.string.connection_create)).performScrollTo().performClick()
        rule.onNodeWithText(text(R.string.connection_manual_wg)).performScrollTo().performClick()
        val name = rule.onNode(hasText(text(R.string.advanced_profile_name)) and hasSetTextAction())
        val draftName = "Несохранённый черновик"
        name.performScrollTo().performTextInput(draftName)
        rule.waitForIdle()
        back()
        rule.onNodeWithText(text(R.string.advanced_discard_title)).assertIsDisplayed()
        rule.onNodeWithText(text(R.string.advanced_continue)).performClick()
        name.assertTextContains(draftName)
        back()
        rule.onNodeWithText(text(R.string.advanced_discard)).performClick()
        back()
        rule.onNode(hasText(text(R.string.advanced_profile_name)) and hasSetTextAction()).assertDoesNotExist()
        rule.onNodeWithText(text(R.string.profile_empty)).performScrollTo().assertIsDisplayed()
        tab(R.string.menu).performClick()
        toggle(R.string.advanced_mode).performScrollTo().assertIsOn().performClick() // Keep tests order-independent.
    }

    @Test fun appearanceSwitchSurvivesActivityRecreation() {
        tab(R.string.menu).performClick()
        toggle(R.string.light_theme).performScrollTo().assertIsOff().performClick()
        toggle(R.string.light_theme).assertIsOn()
        rule.activityRule.scenario.recreate()
        rule.waitForIdle()
        tab(R.string.menu).assertIsSelected()
        toggle(R.string.light_theme).performScrollTo().assertIsOn()
        rule.runOnIdle {
            assertTrue(rule.activity.getSharedPreferences("appearance",Context.MODE_PRIVATE).getBoolean("light",false))
        }
        toggle(R.string.light_theme).performClick() // Restore the default appearance.
    }
}
