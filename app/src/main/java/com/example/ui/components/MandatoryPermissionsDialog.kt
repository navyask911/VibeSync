package com.example.ui.components

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.core.content.ContextCompat

@Composable
fun MandatoryPermissionsDialog(
    onPermissionsGranted: (Boolean) -> Unit,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    var showDisclosure by remember { mutableStateOf(true) }

    val permissionsToRequest = remember {
        buildList {
            add(Manifest.permission.ACCESS_FINE_LOCATION)
            add(Manifest.permission.ACCESS_COARSE_LOCATION)
            add(Manifest.permission.CAMERA)
            add(Manifest.permission.READ_CONTACTS)
            add(Manifest.permission.RECORD_AUDIO)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                add(Manifest.permission.READ_MEDIA_IMAGES)
                add(Manifest.permission.READ_MEDIA_VIDEO)
                add(Manifest.permission.READ_MEDIA_AUDIO)
                add(Manifest.permission.POST_NOTIFICATIONS)
            } else {
                add(Manifest.permission.READ_EXTERNAL_STORAGE)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                add(Manifest.permission.READ_PHONE_NUMBERS)
            }
            add(Manifest.permission.READ_PHONE_STATE)
        }.toTypedArray()
    }

    val permissionsStatus = remember {
        mutableStateMapOf<String, Boolean>().apply {
            permissionsToRequest.forEach { permission ->
                put(permission, ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED)
            }
        }
    }

    val launcher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        results.forEach { (permission, granted) -> permissionsStatus[permission] = granted }
        onPermissionsGranted(results.values.all { it })
        onDismiss()
    }

    if (showDisclosure) {
        AlertDialog(
            modifier = Modifier.testTag("dialog_mandatory_permissions"),
            onDismissRequest = {
                showDisclosure = false
                onPermissionsGranted(false)
                onDismiss()
            },
            title = { Text("Contact matching privacy") },
            text = {
                Text(
                    "If you grant contacts access, VibeSync sends normalized phone numbers securely " +
                        "over SSL/TLS (HTTPS) to Supabase only to match registered friends. " +
                        "Private chat messages remain End-to-End Encrypted (E2EE)."
                )
            },
            confirmButton = {
                Button(onClick = {
                    showDisclosure = false
                    launcher.launch(permissionsToRequest)
                }) { Text("Continue") }
            },
            dismissButton = {
                TextButton(onClick = {
                    showDisclosure = false
                    onPermissionsGranted(false)
                    onDismiss()
                }) { Text("Not now") }
            }
        )
    }
}
