package art.graphite.vpn

import android.content.Context
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import art.graphite.vpn.data.profile.ProfileStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/** Run only on a disposable debug installation. Uses a synthetic profile and Android Keystore. */
class VlessEditorDeviceTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private fun text(id: Int) = compose.activity.getString(id)
    private fun field(label: String) = compose.onNode(hasText(label) and hasSetTextAction())
    private fun waitFor(label: String) = compose.waitUntil(15000) {
        compose.onAllNodesWithText(label).fetchSemanticsNodes().isNotEmpty()
    }

    @Test fun createSaveReopenAndUpdateVless() {
        val store = ProfileStore(compose.activity)
        val inventory = store.inspect()
        assertTrue("Fresh debug installation required", inventory.profiles.isEmpty() && inventory.unreadableIds.isEmpty())
        compose.activity.getSharedPreferences("appearance", Context.MODE_PRIVATE).edit()
            .putBoolean("animations", false).putBoolean("haptics", false).commit()
        compose.activityRule.scenario.recreate()
        compose.onAllNodes(hasText(text(R.string.servers)) and hasClickAction()).onLast().performClick()
        try {
            compose.onNodeWithText(text(R.string.connection_create)).performScrollTo().performClick()
            compose.onNodeWithText(text(R.string.vless_create)).performScrollTo().performClick()
            field(text(R.string.import_profile_name)).performScrollTo().performTextInput("VLESS device fixture")
            field(text(R.string.vless_address)).performScrollTo().performTextInput("example.org")
            field("UUID").performScrollTo().performTextInput("11111111-1111-4111-8111-111111111111")
            compose.onNodeWithText(text(R.string.options_save)).performScrollTo().performClick()
            waitFor(text(R.string.vless_edit))
            val id = store.inspect().profiles.single().id
            assertEquals("example.org:443", store.xrayProfile(id).endpoint)

            compose.onNodeWithText(text(R.string.vless_edit)).performScrollTo().performClick()
            field(text(R.string.vless_address)).performScrollTo().assertTextContains("example.org")
            field(text(R.string.vless_port)).performScrollTo().performTextReplacement("8443")
            compose.onNodeWithText(text(R.string.options_save)).performScrollTo().performClick()
            waitFor("example.org:8443")
            assertEquals(id, store.inspect().profiles.single().id)
            assertEquals("example.org:8443", store.xrayProfile(id).endpoint)
        } finally {
            store.inspect().profiles.filter { it.name == "VLESS device fixture" }.forEach { store.delete(it.id) }
        }
    }
}
