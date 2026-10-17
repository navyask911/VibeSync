package com.example.security

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.view.Window
import android.view.WindowManager
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.window.DialogWindowProvider
import com.example.MainActivity

/**
 * Enforces FLAG_SECURE while a sensitive surface is composed.
 *
 * The activity-level token is reference-counted by identity so a protected dialog cannot
 * accidentally clear protection while another protected surface is still visible. Dialogs also
 * have their own Window; secure it directly and restore its prior state on disposal.
 */
@Composable
fun ForceSecureWindowEffect(enabled: Boolean = true) {
    val context = LocalContext.current
    val view = LocalView.current
    val activity = remember(context) { context.findActivity() }
    val dialogWindow = view.parent.let { it as? DialogWindowProvider }?.window
    val token = remember { Any() }

    DisposableEffect(enabled, activity, dialogWindow) {
        if (!enabled) {
            onDispose { }
        } else {
            val dialogWasAlreadySecure = dialogWindow?.hasSecureFlag() == true
            (activity as? MainActivity)?.setForcedSecureSurface(token, true)
            dialogWindow?.addFlags(WindowManager.LayoutParams.FLAG_SECURE)

            onDispose {
                (activity as? MainActivity)?.setForcedSecureSurface(token, false)
                if (dialogWindow != null && !dialogWasAlreadySecure) {
                    dialogWindow.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
                }
            }
        }
    }
}

private fun Window.hasSecureFlag(): Boolean =
    (attributes.flags and WindowManager.LayoutParams.FLAG_SECURE) != 0

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}
