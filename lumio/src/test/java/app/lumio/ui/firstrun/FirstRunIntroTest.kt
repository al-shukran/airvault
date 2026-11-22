package app.lumio.ui.firstrun

import androidx.activity.ComponentActivity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeLeft
import androidx.compose.ui.test.swipeRight
import app.lensly.ui.theme.ClearAirTheme
import app.lensly.ui.theme.LocalReducedMotion
import kotlin.test.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** The statements I1 to I4 as one pager: the first page on the first frame, swipes and capsules between pages, Back, and reduced motion. */
@RunWith(RobolectricTestRunner::class)
class FirstRunIntroTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private var left = 0
    private var noticed = 0

    private fun setIntro(reduced: Boolean = false) {
        compose.setContent {
            ClearAirTheme {
                CompositionLocalProvider(LocalReducedMotion provides reduced) {
                    FirstRunIntro(onLeave = { left++ }, onNotice = { noticed++ })
                }
            }
        }
    }

    @Test
    fun theFirstStatementIsDrawnOnTheFirstFrame() {
        setIntro()

        compose.onNodeWithText("Welcome to AirVault").assertIsDisplayed()
        compose.onNodeWithText("Get Started").assertIsDisplayed()
    }

    @Test
    fun theCapsuleOfAPageGoesOnToTheNextStatement() {
        setIntro()

        compose.onNodeWithText("Get Started").performClick()
        compose.waitForIdle()

        compose.onNodeWithText("Your Keys Never Leave Your Phone").assertIsDisplayed()
        compose.onNodeWithText("Welcome to AirVault").assertDoesNotExist()
    }

    @Test
    fun aSwipeMovesBetweenTheStatementsInBothDirections() {
        setIntro()

        compose.onNodeWithTag(FIRST_RUN_INTRO_TAG).performTouchInput { swipeLeft() }
        compose.waitForIdle()
        compose.onNodeWithText("Your Keys Never Leave Your Phone").assertIsDisplayed()

        compose.onNodeWithTag(FIRST_RUN_INTRO_TAG).performTouchInput { swipeLeft() }
        compose.waitForIdle()
        compose.onNodeWithText("Never Share Your Keys with Anyone").assertIsDisplayed()

        compose.onNodeWithTag(FIRST_RUN_INTRO_TAG).performTouchInput { swipeRight() }
        compose.waitForIdle()
        compose.onNodeWithText("Your Keys Never Leave Your Phone").assertIsDisplayed()
    }

    @Test
    fun theCapsuleOfTheLastStatementGoesOnToTheNotice() {
        setIntro()

        compose.onNodeWithText("Get Started").performClick()
        compose.waitForIdle()
        compose.onNodeWithText("Next").performClick()
        compose.waitForIdle()
        compose.onNodeWithText("Next").performClick()
        compose.waitForIdle()
        assertEquals(0, noticed)

        compose.onNodeWithText("Provided as Is").assertIsDisplayed()
        compose.onNodeWithText("Next").performClick()
        compose.waitForIdle()
        assertEquals(1, noticed)
    }

    @Test
    fun backGoesToThePreviousStatementAndLeavesFromTheFirst() {
        setIntro()
        compose.onNodeWithText("Get Started").performClick()
        compose.waitForIdle()

        compose.activity.onBackPressedDispatcher.onBackPressed()
        compose.waitForIdle()
        compose.onNodeWithText("Welcome to AirVault").assertIsDisplayed()
        assertEquals(0, left)

        compose.activity.onBackPressedDispatcher.onBackPressed()
        compose.waitForIdle()
        assertEquals(1, left)
    }

    @Test
    fun underReducedMotionAPageChangeIsACut() {
        setIntro(reduced = true)
        compose.mainClock.autoAdvance = false

        compose.onNodeWithText("Get Started").performClick()
        compose.waitForIdle()

        compose.onNodeWithText("Welcome to AirVault").assertDoesNotExist()
        compose.onNodeWithText("Your Keys Never Leave Your Phone").assertIsDisplayed()
    }

    @Test
    fun aPageChangeSlidesWhenMotionIsAllowed() {
        setIntro(reduced = false)
        compose.mainClock.autoAdvance = false

        compose.onNodeWithText("Get Started").performClick()
        compose.waitForIdle()
        compose.onNodeWithText("Welcome to AirVault").assertIsDisplayed()

        compose.mainClock.autoAdvance = true
        compose.waitForIdle()
        compose.onNodeWithText("Your Keys Never Leave Your Phone").assertIsDisplayed()
    }
}
