package art.graphite.vpn.ui

import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import art.graphite.vpn.R
import art.graphite.vpn.ui.screens.GraphiteApp
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/** Real composables on Robolectric's Android runtime, not a VPN/server test. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], qualifiers = "w412dp-h915dp")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class GraphiteAppHostTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Before fun emptyLocalProfileInventoryAndDeterministicAppearance() {
        compose.activity.getSharedPreferences("appearance", 0).edit()
            .clear().putBoolean("animations", false).putBoolean("haptics", false).commit()
        // Each Robolectric test has an isolated application sandbox. No keys are
        // generated or decrypted: the empty inventory flow never opens Keystore.
        compose.setContent { GraphiteApp() }
        compose.waitForIdle()
    }

    private fun text(id: Int) = compose.activity.getString(id)
    private fun bottomTab(id: Int) {
        val nodes = compose.onAllNodes(hasText(text(id)) and hasClickAction())
        nodes[nodes.fetchSemanticsNodes().lastIndex].performClick()
        compose.waitForIdle()
    }

    @Test fun allFiveTabsNavigateAndHomeRendersUsingActualAndroidCanvas() {
        compose.onNodeWithText(text(R.string.disconnected)).assertIsDisplayed()
        saveActualCanvas("robolectric-software-home.png")
        bottomTab(R.string.protocols)
        compose.onNodeWithText(text(R.string.protocol_intro)).assertIsDisplayed()
        bottomTab(R.string.servers)
        compose.onNodeWithText(text(R.string.profile_empty)).performScrollTo().assertIsDisplayed()
        compose.onAllNodes(androidx.compose.ui.test.SemanticsMatcher.expectValue(androidx.compose.ui.semantics.SemanticsProperties.Role,androidx.compose.ui.semantics.Role.Tab)).assertCountEquals(5)
        saveActualCanvas("robolectric-software-servers.png")
        bottomTab(R.string.profile)
        compose.onNodeWithText(text(R.string.local_profile)).assertIsDisplayed()
        bottomTab(R.string.menu)
        compose.onNodeWithText(text(R.string.appearance)).assertIsDisplayed()
        bottomTab(R.string.home)
        compose.onNodeWithText(text(R.string.disconnected)).assertIsDisplayed()
    }

    @Test fun advancedDraftRequiresExplicitDiscardBeforeBottomTabNavigation() {
        bottomTab(R.string.menu)
        compose.onNodeWithText(text(R.string.advanced_mode)).performScrollTo().performClick()
        compose.onNodeWithText(text(R.string.advanced_profiles)).performScrollTo().performClick()
        compose.onNodeWithText(text(R.string.connection_create)).performScrollTo().performClick()
        compose.onNodeWithText(text(R.string.connection_manual_wg)).performScrollTo().performClick()
        compose.onNode(hasSetTextAction() and hasText(text(R.string.advanced_profile_name)))
            .performScrollTo().performTextInput("My lab profile")
        compose.waitForIdle()
        bottomTab(R.string.menu)
        compose.onNodeWithText(text(R.string.discard_title)).assertIsDisplayed()
        compose.onNode(hasText(text(R.string.cancel)) and hasAnyAncestor(isDialog())).performClick()
        compose.onNode(hasSetTextAction() and hasText(text(R.string.advanced_profile_name)))
            .assertTextContains("My lab profile")
        bottomTab(R.string.menu)
        compose.onNode(hasText(text(R.string.discard)) and hasAnyAncestor(isDialog())).performClick()
        compose.waitForIdle()
        compose.onNodeWithText(text(R.string.appearance)).assertIsDisplayed()
    }

    @Test fun blankAdvancedProfileCanOpenRawPasteAndApplyWithoutSaving() {
        bottomTab(R.string.menu)
        compose.onNodeWithText(text(R.string.advanced_mode)).performScrollTo().performClick()
        compose.onNodeWithText(text(R.string.advanced_profiles)).performScrollTo().performClick()
        compose.onNodeWithText(text(R.string.connection_create)).performScrollTo().performClick()
        compose.onNodeWithText(text(R.string.connection_manual_wg)).performScrollTo().performClick()
        compose.onNodeWithText(text(R.string.advanced_raw)).performScrollTo().performClick()
        compose.onNode(hasText(text(R.string.advanced_raw_open)) and hasAnyAncestor(isDialog())).performClick()
        val rawField = compose.onNode(hasSetTextAction() and hasText(text(R.string.advanced_raw_field)))
        rawField.assertExists().performScrollTo()
        // Public synthetic fixture and documentation-only address; never connected.
        val config = """
            [Interface]
            PrivateKey = AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAEE=
            Address = 10.0.0.2/32

            [Peer]
            PublicKey = AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAE=
            Endpoint = 192.0.2.1:51820
            AllowedIPs = 0.0.0.0/0
        """.trimIndent()
        rawField.performTextReplacement(config)
        compose.onNodeWithText(text(R.string.advanced_raw_apply)).performScrollTo().performClick()
        compose.onNode(hasSetTextAction() and hasText(text(R.string.advanced_address)))
            .performScrollTo().assertTextContains("10.0.0.2/32")
        compose.onNode(hasSetTextAction() and hasText(text(R.string.advanced_endpoint)))
            .performScrollTo().assertTextContains("192.0.2.1:51820")
        compose.onNodeWithText(text(R.string.advanced_error_config)).assertDoesNotExist()
    }

    @Test fun networkSettingsAreAvailableWithoutAdvancedAndSaveActualChoices() {
        bottomTab(R.string.menu)
        compose.onNodeWithText(text(R.string.connection_auto_retry)).performScrollTo().performClick()
        compose.runOnIdle {
            assertTrue(art.graphite.vpn.data.settings.ConnectionSettingsStore(compose.activity).load().autoReconnect)
        }
        compose.onNodeWithText(text(R.string.system_vpn_open)).performScrollTo().assertIsDisplayed()
        saveActualCanvas("robolectric-software-protection-settings.png")
        compose.onNodeWithText(text(R.string.ping_family_ipv6)).performScrollTo().performClick()
        compose.runOnIdle {
            org.junit.Assert.assertEquals("IPV6",compose.activity.getSharedPreferences("appearance",0).getString("pingFamily",null))
            org.junit.Assert.assertFalse(compose.activity.getSharedPreferences("appearance",0).getBoolean("advanced",false))
        }
        saveActualCanvas("robolectric-software-ping-settings.png")
    }

    @Test fun tunnelSwitchesSaveOverridesAndRejectHostnameDns() {
        bottomTab(R.string.menu)
        compose.onNodeWithText(text(R.string.tunneling_screen)).performScrollTo().performClick()
        compose.onNodeWithText(text(R.string.override_mtu)).performScrollTo().performClick()
        compose.onNodeWithText(text(R.string.override_dns)).performScrollTo().performClick()
        compose.onNode(hasSetTextAction() and hasText(text(R.string.custom_dns_addresses))).performScrollTo().performTextReplacement("8.8.8.8")
        compose.onNodeWithText(text(R.string.options_save)).performScrollTo().performClick()
        compose.runOnIdle {
            val saved=art.graphite.vpn.data.settings.TunnelOptionsStore(compose.activity).load()
            assertTrue(saved.overrideMtu && saved.overrideDns)
            org.junit.Assert.assertEquals("8.8.8.8",saved.dnsServers)
        }
        saveActualCanvas("robolectric-tunnel-overrides.png")
        compose.onNode(hasSetTextAction() and hasText(text(R.string.custom_dns_addresses))).performScrollTo().performTextReplacement("example.invalid")
        compose.onNodeWithText(text(R.string.options_save)).performScrollTo().performClick()
        compose.onNodeWithText(text(R.string.options_invalid)).performScrollTo().assertIsDisplayed()
        compose.runOnIdle {org.junit.Assert.assertEquals("8.8.8.8",art.graphite.vpn.data.settings.TunnelOptionsStore(compose.activity).load().dnsServers)}
    }

    @Test fun qrImportHasDedicatedPageAndDoesNotStartVpn() {
        bottomTab(R.string.servers)
        compose.onNodeWithText(text(R.string.connection_create)).performScrollTo().performClick()
        compose.onNodeWithText(text(R.string.scan_camera)).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText(text(R.string.scan_image)).assertExists()
        compose.onNode(hasSetTextAction() and hasText(text(R.string.import_text))).performScrollTo().performTextReplacement("https://example.invalid/profile.conf?token=synthetic-only")
        compose.onNodeWithText(text(R.string.import_check)).performScrollTo().performClick()
        compose.onNodeWithText(text(R.string.import_download_confirm)).performScrollTo().assertIsDisplayed()
        compose.runOnIdle {org.junit.Assert.assertEquals(art.graphite.vpn.vpn.VpnPhase.DOWN,art.graphite.vpn.vpn.VpnRuntime.state.value.phase)}
    }

    @Test fun vlessCreationIsAvailableWithoutAdvancedAndChecksRequiredFields() {
        bottomTab(R.string.servers)
        compose.onNodeWithText(text(R.string.connection_create)).performScrollTo().performClick()
        compose.onNodeWithText(text(R.string.vless_create)).performScrollTo().performClick()
        compose.onNode(hasSetTextAction() and hasText(text(R.string.import_profile_name)))
            .performScrollTo().performTextInput("VLESS lab")
        compose.onNode(hasSetTextAction() and hasText(text(R.string.vless_address)))
            .performScrollTo().performTextInput("example.org")
        compose.onNodeWithText(text(R.string.options_save)).performScrollTo().performClick()
        compose.waitUntil(5000) { compose.onAllNodesWithText(text(R.string.vless_bad_uuid)).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText(text(R.string.vless_bad_uuid)).assertExists()
    }

    private fun saveActualCanvas(filename: String) {
        compose.runOnIdle {
            val view = compose.activity.window.decorView
            assertTrue("Host Android view must be laid out", view.width > 0 && view.height > 0)
            val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
            try {
                view.draw(Canvas(bitmap))
                val output = File("build/reports/ui-previews", filename)
                requireNotNull(output.parentFile).mkdirs()
                output.outputStream().use { assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
            } finally { bitmap.recycle() }
        }
    }
}
