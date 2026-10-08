package com.example

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.assertExists
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.performTextClearance
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
    fun login_screen_renders_brand_and_mobile_controls() {
        composeTestRule.activity.setContent {
            MyApplicationTheme {
                AuthLoginScreen(onVerifyPhoneDirect = {})
            }
        }

        composeTestRule.onNodeWithText("Welcome to VibeSync").assertExists()
        composeTestRule.onNodeWithTag("input_mobile_number").assertExists()
        composeTestRule.onNodeWithTag("btn_continue_otp_main").assertExists()
    }

    @Test
    fun registration_screen_blocks_completion_without_date_of_birth() {
        composeTestRule.activity.setContent {
            MyApplicationTheme {
                RegistrationProfileScreen(onCompleteRegistration = { _, _, _, _, _, _, _, _, _, _, _, _, _, _, _, _, _, _, _, _ -> })
            }
        }

        composeTestRule.onNodeWithTag("full_name_input").assertExists()
        composeTestRule.onNodeWithTag("dob_input").assertExists()
        composeTestRule.onNodeWithTag("save_profile_button").assertIsNotEnabled()

        composeTestRule.onNodeWithTag("full_name_input").performTextInput("Navya")
        composeTestRule.onNodeWithTag("save_profile_button").assertIsNotEnabled()
    }
}
