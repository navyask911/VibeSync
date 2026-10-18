package com.example.ui.components

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.core.content.ContextCompat

/** Contact access only; each other protected feature requests its permission on first use. */
@Composable
fun MandatoryPermissionsDialog(
    onPermissionsGranted: (Boolean) -> Unit,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val alreadyGranted = ContextCompat.checkSelfPermission(
        context, Manifest.permission.READ_CONTACTS
    ) == PackageManager.PERMISSION_GRANTED
    var showDisclosure by remember { mutableStateOf(true) }

    val launcher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        onPermissionsGranted(granted)
        onDismiss()
    }

    if (showDisclosure) {
        AlertDialog(
            modifier = Modifier.testTag("dialog_mandatory_permissions"),
            onDismissRequest = {
                showDisclosure = false
                onPermissionsGranted(alreadyGranted)
                onDismiss()
            },
            title = { Text("Contact matching privacy") },
            text = {
                Text(
                    "If you allow contacts access, VibeSync reads phone numbers on this device and sends " +
                        "normalized numbers securely over HTTPS only to match registered profiles. " +
                        "Profile discoverability is separate, and private chats remain end-to-end encrypted."
                )
            },
            confirmButton = {
                Button(onClick = {
                    showDisclosure = false
                    if (alreadyGranted) {
                        onPermissionsGranted(true)
                        onDismiss()
                    } else {
                        launcher.launch(Manifest.permission.READ_CONTACTS)
                    }
                }) { Text(if (alreadyGranted) "Continue" else "Allow contacts") }
            },
            dismissButton = {
                TextButton(onClick = {
                    showDisclosure = false
                    onPermissionsGranted(alreadyGranted)
                    onDismiss()
                }) { Text("Not now") }
            }
        )
    }
}
