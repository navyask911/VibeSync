package com.example

import android.content.Context
import android.content.pm.ProviderInfo
import android.util.Log
import androidx.core.content.FileProvider

/**
 * Isolate file-sharing setup failures so invalid provider metadata cannot kill the process on launch.
 * Share actions may fail if initialization is invalid, but the main activity remains available.
 */
class SafeFileProvider : FileProvider() {
    override fun attachInfo(context: Context, info: ProviderInfo) {
        try {
            super.attachInfo(context, info)
        } catch (t: Throwable) {
            Log.e("VibeSyncInit", "Safe init fallback: FileProvider attachInfo", t)
        }
    }

    override fun onCreate(): Boolean {
        return try {
            super.onCreate()
        } catch (t: Throwable) {
            Log.e("VibeSyncInit", "Safe init fallback: FileProvider onCreate", t)
            true
        }
    }
}
