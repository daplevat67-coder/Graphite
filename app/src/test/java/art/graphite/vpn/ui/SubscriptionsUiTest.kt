package art.graphite.vpn.ui

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import art.graphite.vpn.R
import art.graphite.vpn.ui.screens.GraphiteApp
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[28], qualifiers="w412dp-h915dp")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class SubscriptionsUiTest {
    @get:Rule val compose=createAndroidComposeRule<ComponentActivity>()
    @Test fun opensSubscriptionFormFromCreateWithoutExperimentalInstaller() {
        compose.activity.getSharedPreferences("appearance",0).edit().clear()
            .putBoolean("animations",false).putBoolean("haptics",false).commit()
        compose.setContent { GraphiteApp() }
        compose.waitForIdle()
        val servers=compose.onAllNodes(hasText(compose.activity.getString(R.string.servers)) and hasClickAction())
        servers[servers.fetchSemanticsNodes().lastIndex].performClick()
        compose.onNodeWithText(compose.activity.getString(R.string.connection_create)).performScrollTo().performClick()
        compose.onAllNodesWithText(compose.activity.getString(R.string.selfhost_title)).assertCountEquals(0)
        compose.onNode(hasText(compose.activity.getString(R.string.subscriptions_title)) and hasClickAction()).performScrollTo().performClick()
        compose.onNode(hasSetTextAction() and hasText(compose.activity.getString(R.string.subscription_name))).performScrollTo().assertIsDisplayed()
        compose.onNode(hasSetTextAction() and hasText(compose.activity.getString(R.string.subscription_url))).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText(compose.activity.getString(R.string.subscription_add)).performScrollTo().assertIsNotEnabled()
    }
}
