package art.graphite.vpn.ui

import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.ViewGroup
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import art.graphite.vpn.ui.components.NotebookPager
import art.graphite.vpn.ui.components.TearableSheet
import art.graphite.vpn.ui.components.PageTurnStyle
import art.graphite.vpn.ui.components.SketchReveal
import art.graphite.vpn.ui.theme.GraphiteTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.roundToInt

/** Host Android rendering/semantics tests; these are not physical-device FPS tests. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], qualifiers = "w412dp-h915dp")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class NotebookMotionTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private fun change(block: () -> Unit) {
        compose.runOnIdle {
            Snapshot.withMutableSnapshot { block() }
            Snapshot.sendApplyNotifications()
        }
        compose.mainClock.advanceTimeByFrame()
        compose.waitForIdle()
    }

    @Test fun pageCurlRevealsFlatIncomingPageAndHidesOldPageFromAccessibility() {
        val page = mutableStateOf("one")
        compose.mainClock.autoAdvance = false
        compose.setContent {
            GraphiteTheme { NotebookPager(page.value, true, Modifier.fillMaxSize().testTag("book"),durationMillis=480) { value ->
                Box(Modifier.fillMaxSize().background(if(value=="one") Color.Red else Color.Blue).padding(40.dp)) { Text("Page $value") }
            } }
        }
        compose.waitForIdle()
        change { page.value = "two" }
        compose.mainClock.advanceTimeByFrame()
        compose.mainClock.advanceTimeBy(160)
        compose.onNodeWithText("Page one").assertDoesNotExist()
        compose.onNodeWithText("Page two").assertExists()
        val turningCenter = compose.onNodeWithText("Page two").fetchSemanticsNode().boundsInRoot.center.x
        // captureToImage() requests another frame, which cannot finish with this deliberately
        // frozen animation clock. Draw the actual Android view synchronously at this frame.
        val frame=drawBookAtCurrentFrame()
        try {
            assertTrue("Old paper remains on the left while the corner turns",
                android.graphics.Color.red(frame.getPixel((frame.width*.06f).toInt(),frame.height/2))>204)
            assertTrue("The arriving page is revealed on the right",
                android.graphics.Color.blue(frame.getPixel((frame.width*.95f).toInt(),frame.height/2))>204)
            val output=File("build/reports/ui-previews/robolectric-curl-intermediate.png")
            requireNotNull(output.parentFile).mkdirs()
            output.outputStream().use { assertTrue(frame.compress(Bitmap.CompressFormat.PNG,100,it)) }
        } finally { frame.recycle() }
        compose.mainClock.advanceTimeBy(1_200)
        compose.waitForIdle()
        compose.onNodeWithText("Page one").assertDoesNotExist()
        compose.onNodeWithText("Page two").assertIsDisplayed()
        val settledCenter = compose.onNodeWithText("Page two").fetchSemanticsNode().boundsInRoot.center.x
        assertEquals("Receiving paper stays flat at its final layout throughout the curl",settledCenter,turningCenter,.1f)
    }

    private fun drawBookAtCurrentFrame():Bitmap {
        val bounds=compose.onNodeWithTag("book").fetchSemanticsNode().boundsInRoot
        lateinit var frame:Bitmap
        compose.runOnIdle {
            // ComposeView is the content child; its drawing origin matches boundsInRoot.
            val host=compose.activity.findViewById<ViewGroup>(android.R.id.content).getChildAt(0)
            assertTrue("Compose host must have a real Android layout",host.width>0 && host.height>0)
            val full=Bitmap.createBitmap(host.width,host.height,Bitmap.Config.ARGB_8888)
            try {
                host.draw(Canvas(full))
                val left=bounds.left.roundToInt().coerceIn(0,full.width-1)
                val top=bounds.top.roundToInt().coerceIn(0,full.height-1)
                val width=bounds.width.roundToInt().coerceIn(1,full.width-left)
                val height=bounds.height.roundToInt().coerceIn(1,full.height-top)
                // createBitmap can return its input for a full-size crop; copy keeps ownership clear.
                val cropped=Bitmap.createBitmap(full,left,top,width,height)
                frame=cropped.copy(Bitmap.Config.ARGB_8888,false)
                if(cropped!==full)cropped.recycle()
            } finally {full.recycle()}
        }
        return frame
    }

    @Test fun coveredReceivingControlsCannotBeTappedUntilCurlFinishes() {
        val page=mutableStateOf("one")
        val callbacks=AtomicInteger()
        compose.mainClock.autoAdvance=false
        compose.setContent { GraphiteTheme {
            NotebookPager(page.value,true,Modifier.fillMaxSize().testTag("book"),durationMillis=480) {
                Box(Modifier.fillMaxSize().clickable { callbacks.incrementAndGet() }) {Text("Page $it")}
            }
        } }
        change {page.value="two"}
        compose.mainClock.advanceTimeBy(100)
        compose.onNodeWithTag("book").performTouchInput {click(center)}
        assertEquals(0,callbacks.get())
        compose.mainClock.advanceTimeBy(1_000)
        compose.waitForIdle()
        compose.onNodeWithTag("book").performTouchInput {click(center)}
        compose.waitForIdle()
        assertEquals(1,callbacks.get())
    }

    @Test fun requestedDefaultTurnLastsThreeAndAHalfSeconds() {
        val page=mutableStateOf("one")
        val callbacks=AtomicInteger()
        compose.mainClock.autoAdvance=false
        compose.setContent { GraphiteTheme {
            NotebookPager(page.value,true,Modifier.fillMaxSize().testTag("book")) {
                Box(Modifier.fillMaxSize().clickable { callbacks.incrementAndGet() }) {Text("Page $it")}
            }
        } }
        change {page.value="two"}
        compose.mainClock.advanceTimeBy(3_000)
        compose.onNodeWithTag("book").performTouchInput {click(center)}
        assertEquals("Still turning at3seconds",0,callbacks.get())
        compose.mainClock.advanceTimeBy(800)
        compose.waitForIdle()
        compose.onNodeWithTag("book").performTouchInput {click(center)}
        compose.waitForIdle()
        assertEquals("Settled after3.5seconds",1,callbacks.get())
    }

    @Test fun reverseRollingSheetRevealsLeftAndKeepsOldPaperOnRight() {
        val page=mutableStateOf("one")
        compose.mainClock.autoAdvance=false
        compose.setContent { GraphiteTheme {
            NotebookPager(page.value,true,Modifier.fillMaxSize().testTag("book"),direction=-1,durationMillis=480,style=PageTurnStyle.ROLL) {value->
                Box(Modifier.fillMaxSize().background(if(value=="one")Color.Red else Color.Blue)) {Text("Page $value")}
            }
        } }
        change {page.value="two"}
        compose.mainClock.advanceTimeBy(160)
        val frame=drawBookAtCurrentFrame()
        try {
            assertTrue(android.graphics.Color.blue(frame.getPixel((frame.width*.06f).toInt(),frame.height/2))>204)
            assertTrue(android.graphics.Color.red(frame.getPixel((frame.width*.95f).toInt(),frame.height/2))>204)
        } finally {frame.recycle()}
        compose.mainClock.advanceTimeBy(1_000)
        compose.waitForIdle()
        compose.onNodeWithText("Page two").assertIsDisplayed()
    }

    @Test fun rapidDestinationChangesDiscardOldTurnAndShowLatestPage() {
        val page=mutableStateOf("one")
        compose.mainClock.autoAdvance=false
        compose.setContent { GraphiteTheme {
            NotebookPager(page.value,true,Modifier.fillMaxSize(),style=PageTurnStyle.ROLL) {Text("Page $it")}
        } }
        change {page.value="two"}
        compose.mainClock.advanceTimeBy(200)
        change {page.value="three"}
        compose.mainClock.advanceTimeBy(4_000)
        compose.waitForIdle()
        compose.onNodeWithText("Page one").assertDoesNotExist()
        compose.onNodeWithText("Page two").assertDoesNotExist()
        compose.onNodeWithText("Page three").assertIsDisplayed()
    }

    @Test fun menuRefreshDrawsAgainAndReleasesControlsWhenComplete() {
        val key=mutableStateOf(0)
        compose.mainClock.autoAdvance=false
        compose.setContent {GraphiteTheme {SketchReveal(key.value,true,durationMillis=400) {Text("Drawn menu")}}}
        compose.waitForIdle()
        compose.onNodeWithText("Drawn menu").assertDoesNotExist()
        compose.mainClock.advanceTimeBy(800)
        compose.waitForIdle()
        compose.onNodeWithText("Drawn menu").assertIsDisplayed()
        change {key.value++}
        compose.onNodeWithText("Drawn menu").assertDoesNotExist()
        compose.mainClock.advanceTimeBy(800)
        compose.waitForIdle()
        compose.onNodeWithText("Drawn menu").assertIsDisplayed()
    }

    @Test fun reducedMotionMenuRevealExposesControlsImmediately() {
        val key=mutableStateOf(0)
        compose.mainClock.autoAdvance=false
        compose.setContent {GraphiteTheme {SketchReveal(key.value,false) {Text("Drawn menu")}}}
        compose.waitForIdle()
        compose.onNodeWithText("Drawn menu").assertIsDisplayed()
        change {key.value++}
        compose.mainClock.advanceTimeByFrame()
        compose.waitForIdle()
        compose.onNodeWithText("Drawn menu").assertIsDisplayed()
    }

    @Test fun disablingPageAnimationsChangesPageWithoutRetainingOutgoingSheet() {
        val page = mutableStateOf("one")
        compose.mainClock.autoAdvance = false
        compose.setContent { GraphiteTheme { NotebookPager(page.value, false, Modifier.fillMaxSize()) { Text("Page $it") } } }
        change { page.value = "two" }
        compose.mainClock.advanceTimeByFrame()
        compose.mainClock.advanceTimeByFrame()
        compose.waitForIdle()
        compose.onNodeWithText("Page one").assertDoesNotExist()
        compose.onNodeWithText("Page two").assertIsDisplayed()
    }

    @Test fun tearFinishesOnceAfterFlightEvenWhenAnimationPreferenceChanges() {
        val deleting = mutableStateOf(false)
        val animations = mutableStateOf(true)
        val callbacks = AtomicInteger()
        compose.mainClock.autoAdvance = false
        compose.setContent { GraphiteTheme {
            TearableSheet(deleting.value, animations.value, onGone = { callbacks.incrementAndGet() }) { Text("Deleted profile") }
        } }
        change { deleting.value = true }
        compose.mainClock.advanceTimeByFrame()
        compose.mainClock.advanceTimeBy(200)
        assertEquals("Deletion completion must wait for the sheet flight", 0, callbacks.get())
        compose.mainClock.advanceTimeBy(1_000)
        compose.waitForIdle()
        assertEquals(1, callbacks.get())
        change { animations.value = false }
        compose.mainClock.advanceTimeByFrame()
        compose.waitForIdle()
        assertEquals("A preference change must not repeat a completed deletion", 1, callbacks.get())
    }

    @Test fun reducedMotionCompletesTearImmediatelyAndOnlyOnce() {
        val deleting = mutableStateOf(false)
        val callbacks = AtomicInteger()
        compose.mainClock.autoAdvance = false
        compose.setContent { GraphiteTheme { TearableSheet(deleting.value, false, onGone = { callbacks.incrementAndGet() }) { Text("Profile") } } }
        change { deleting.value = true }
        compose.mainClock.advanceTimeByFrame()
        compose.waitForIdle()
        assertEquals(1, callbacks.get())
        compose.mainClock.advanceTimeBy(1_000)
        compose.waitForIdle()
        assertEquals(1, callbacks.get())
    }
}
