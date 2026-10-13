package com.example.ui.screens

import android.os.Build
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import androidx.compose.runtime.Composable
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.platform.InterceptPlatformTextInput
import androidx.compose.ui.platform.PlatformTextInputMethodRequest

/**
 * Marks every Compose editor under this boundary as non-personalized input. This requests that
 * compliant IMEs avoid learning/caching private text; keyboard implementations may still vary.
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun IncognitoKeyboard(content: @Composable () -> Unit) {
    InterceptPlatformTextInput(
        interceptor = { request, nextHandler ->
            val modifiedRequest = object : PlatformTextInputMethodRequest {
                override fun createInputConnection(outAttributes: EditorInfo): InputConnection {
                    val inputConnection = request.createInputConnection(outAttributes)
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        outAttributes.imeOptions =
                            outAttributes.imeOptions or EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING
                    }
                    return inputConnection
                }
            }
            nextHandler.startInputMethod(modifiedRequest)
        }
    ) {
        content()
    }
}
