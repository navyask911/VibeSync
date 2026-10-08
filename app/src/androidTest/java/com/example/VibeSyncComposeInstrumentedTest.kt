package com.example

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertDoesNotExist
import androidx.compose.ui.test.assertExists
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.ui.screens.AuthLoginScreen
import com.example.ui.screens.RegistrationProfileScreen
import com.example.ui.theme.MyApplicationTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class VibeSyncComposeInstrumentedTest {

    @get:Rule
    val composeTestRule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun login_screen_is_truecaller_only() {
        composeTestRule.activity.setContent {
            MyApplicationTheme {
                AuthLoginScreen()
            }
        }

        composeTestRule.onNodeWithText("Welcome to VibeSync").assertExists()
        composeTestRule.onNodeWithText("Truecaller is the only sign-in method").assertExists()
        composeTestRule.onNodeWithTag("btn_truecaller_one_tap_main")
            .assertExists()
            .assertIsEnabled()

        composeTestRule.onNodeWithTag("input_mobile_number").assertDoesNotExist()
        composeTestRule.onNodeWithTag("btn_continue_otp_main").assertDoesNotExist()
    }

    @Test
    fun login_screen_surfaces_truecaller_failure_with_retry() {
        composeTestRule.activity.setContent {
            MyApplicationTheme {
                AuthLoginScreen(truecallerError = "Truecaller could not be opened.")
            }
        }

        composeTestRule.onNodeWithTag("truecaller_error_state").assertExists()
        composeTestRule.onNodeWithText("Truecaller verification failed").assertExists()
        composeTestRule.onNodeWithText("Retry with Truecaller").assertExists()
        composeTestRule.onNodeWithTag("btn_retry_truecaller")
            .assertExists()
            .assertIsEnabled()
    }

    @Test
    fun registration_screen_blocks_completion_without_date_of_birth() {
        composeTestRule.activity.setContent {
            MyApplicationTheme {
                RegistrationProfileScreen(
                    onCompleteRegistration = { _, _, _, _, _, _, _, _, _, _, _, _, _, _, _, _, _, _, _, _ -> }
                )
            }
        }

        composeTestRule.onNodeWithTag("full_name_input").assertExists()
        composeTestRule.onNodeWithTag("dob_input").assertExists()
        composeTestRule.onNodeWithTag("save_profile_button").assertIsNotEnabled()

        composeTestRule.onNodeWithTag("full_name_input").performTextInput("Navya")
        composeTestRule.onNodeWithTag("save_profile_button").assertIsNotEnabled()
    }
}
