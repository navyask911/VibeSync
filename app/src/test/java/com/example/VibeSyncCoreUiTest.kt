package com.example

import androidx.compose.ui.test.assertDoesNotExist
import androidx.compose.ui.test.assertExists
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performTextInput
import com.example.ui.screens.AuthLoginScreen
import com.example.ui.screens.RegistrationProfileScreen
import com.example.ui.theme.MyApplicationTheme
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class VibeSyncCoreUiTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun login_screen_is_truecaller_only() {
        composeTestRule.setContent {
            MyApplicationTheme {
                AuthLoginScreen()
            }
        }

        composeTestRule.onNodeWithText("Welcome to VibeSync").assertExists()
        composeTestRule.onNodeWithText("Truecaller is the only sign-in method").assertExists()
        composeTestRule.onNodeWithTag("btn_truecaller_one_tap_main").assertExists().assertIsEnabled()
        composeTestRule.onNodeWithTag("input_mobile_number").assertDoesNotExist()
        composeTestRule.onNodeWithTag("btn_continue_otp_main").assertDoesNotExist()
    }

    @Test
    fun login_screen_surfaces_truecaller_failure_with_retry() {
        composeTestRule.setContent {
            MyApplicationTheme {
                AuthLoginScreen(truecallerError = "Truecaller could not be opened.")
            }
        }

        composeTestRule.onNodeWithTag("truecaller_error_state").assertExists()
        composeTestRule.onNodeWithText("Truecaller verification failed").assertExists()
        composeTestRule.onNodeWithText("Retry with Truecaller").assertExists()
        composeTestRule.onNodeWithTag("btn_retry_truecaller").assertIsEnabled()
    }

    @Test
    fun registration_screen_enforces_name_and_date_of_birth_before_completion() {
        var completed = false
        composeTestRule.setContent {
            MyApplicationTheme {
                RegistrationProfileScreen(
                    onCompleteRegistration = { _, _, _, _, _, _, _, _, _, _, _, _, _, _, _, _, _, _, _, _ -> completed = true }
                )
            }
        }
        composeTestRule.onNodeWithText("Create Profile").assertExists()
        composeTestRule.onNodeWithTag("full_name_input").assertExists()
        composeTestRule.onNodeWithTag("dob_input").assertExists()
        composeTestRule.onNodeWithTag("save_profile_button").assertIsNotEnabled()
        composeTestRule.onNodeWithTag("full_name_input").performTextInput("A")
        composeTestRule.onNodeWithTag("save_profile_button").assertIsNotEnabled()
        assertTrue(!completed)
    }
}
