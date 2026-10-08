package com.example

import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import com.example.ui.screens.AuthLoginScreen
import com.example.ui.screens.OtpVerificationScreen
import com.example.ui.screens.RegistrationProfileScreen
import com.example.ui.theme.MyApplicationTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.atomic.AtomicReference

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class VibeSyncCoreUiTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun login_screen_accepts_mobile_number_and_emits_continue_action() {
        val submitted = AtomicReference<String?>(null)

        composeTestRule.setContent {
            MyApplicationTheme {
                AuthLoginScreen(
                    onVerifyPhoneDirect = { submitted.set(it) }
                )
            }
        }

        composeTestRule.onNodeWithText("Welcome to VibeSync").assertExists()
        composeTestRule.onNodeWithTag("input_mobile_number").assertExists()
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithTag("input_mobile_number").performTextClearance()
        composeTestRule.onNodeWithTag("input_mobile_number").performTextInput("9449878908")
        composeTestRule.onNodeWithTag("btn_continue_otp_main").assertIsEnabled().performClick()
        composeTestRule.waitForIdle()
        composeTestRule.waitUntil(timeoutMillis = 5_000) { submitted.get() != null }

        // AuthLoginScreen emits the selected country dial code plus the local
        // number with formatting. Compare digits so harmless spacing/prefix
        // presentation changes do not break the contract test.
        assertEquals("919449878908", submitted.get()?.filter(Char::isDigit))
    }

    @Test
    fun otp_screen_supports_secure_simulated_test_verification_and_mode_switching() {
        val verifiedPhone = AtomicReference<String?>(null)

        composeTestRule.setContent {
            MyApplicationTheme {
                OtpVerificationScreen(
                    phoneNumber = "+919449878908",
                    simulatedOtpCode = "123456",
                    onVerificationSuccess = { phone, _ -> verifiedPhone.set(phone) },
                    onBackToPhoneInput = {}
                )
            }
        }

        composeTestRule.onNodeWithTag("input_secure_otp_code").assertExists()
        composeTestRule.onNodeWithTag("btn_verify_secure_otp").assertIsNotEnabled()

        composeTestRule.onNodeWithTag("input_secure_otp_code").performTextInput("123456")
        composeTestRule.onNodeWithTag("btn_verify_secure_otp").assertIsEnabled().performClick()
        composeTestRule.waitForIdle()
        composeTestRule.waitUntil(timeoutMillis = 5_000) { verifiedPhone.get() != null }

        assertEquals("919449878908", verifiedPhone.get()?.filter(Char::isDigit))

        // Wait for the mode-switch content to be committed before asserting its
        // explanatory text; this avoids racing Compose recomposition.
        val disabledGatewayText = "Disabled until a server-side verification gateway is connected"
        composeTestRule.onNodeWithTag("opt_user_text_sms").assertExists().performClick()
        composeTestRule.waitUntil(timeoutMillis = 5_000) {
            composeTestRule.onAllNodesWithText(disabledGatewayText).fetchSemanticsNodes().isNotEmpty()
        }
        composeTestRule.onNodeWithText(disabledGatewayText).assertExists()
    }

    @Test
    fun registration_screen_enforces_name_and_date_of_birth_before_completion() {
        val completed = AtomicReference<String?>(null)

        composeTestRule.setContent {
            MyApplicationTheme {
                RegistrationProfileScreen(
                    onCompleteRegistration = { name, _, _, _, _, _, _, _, _, _, _, _, _, _, _, _, _, _, _, _ ->
                        completed.set(name)
                    }
                )
            }
        }

        composeTestRule.onNodeWithText("Create Profile").assertExists()
        composeTestRule.onNodeWithTag("full_name_input").assertExists()
        composeTestRule.onNodeWithTag("dob_input").assertExists()
        composeTestRule.onNodeWithTag("save_profile_button").assertIsNotEnabled()

        composeTestRule.onNodeWithTag("full_name_input").performTextInput("A")
        composeTestRule.onNodeWithTag("save_profile_button").assertIsNotEnabled()
        assertTrue(completed.get().isNullOrBlank())
    }
}
