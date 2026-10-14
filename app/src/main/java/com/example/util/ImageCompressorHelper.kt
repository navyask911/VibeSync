package com.example.util

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.util.Base64
import android.util.Log
import java.io.ByteArrayOutputStream

object ImageCompressorHelper {

    private const val TAG = "ImageCompressorHelper"

    /**
     * Image gateway used before message/story encryption: fit within 1600x1600, encode as lossy
     * WebP on Android 30+ (JPEG on older Android), and apply 80% quality by default.
     * Encoded bytes keep the existing IMG_B64 envelope so encryption callers need no format changes.
     */
    fun compressAndEncodeImageUriToBase64(
        context: Context,
        uriString: String,
        maxDimension: Int = 1600,
        initialQuality: Int = 80
    ): String? {
        if (uriString.isBlank()) return null
        if (uriString.startsWith("IMG_B64:") || uriString.startsWith("IMG_URL:") || uriString.startsWith("http")) {
            return uriString
        }

        return try {
            val uri = Uri.parse(uriString)
            val inputStream1 = context.contentResolver.openInputStream(uri) ?: return uriString
            val boundsOptions = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeStream(inputStream1, null, boundsOptions)
            inputStream1.close()

            val origWidth = boundsOptions.outWidth
            val origHeight = boundsOptions.outHeight
            if (origWidth <= 0 || origHeight <= 0) return uriString

            var sampleSize = 1
            while ((origWidth / sampleSize) > maxDimension || (origHeight / sampleSize) > maxDimension) {
                sampleSize *= 2
            }

            val decodeOptions = BitmapFactory.Options().apply { inSampleSize = sampleSize }
            val inputStream2 = context.contentResolver.openInputStream(uri) ?: return uriString
            var bitmap = BitmapFactory.decodeStream(inputStream2, null, decodeOptions)
            inputStream2.close()

            var activeBitmap: Bitmap = bitmap ?: return uriString

            // Ensure exact max dimension scaling if still above maxDimension
            if (activeBitmap.width > maxDimension || activeBitmap.height > maxDimension) {
                val scale = maxDimension.toFloat() / Math.max(activeBitmap.width, activeBitmap.height)
                val newWidth = (activeBitmap.width * scale).toInt()
                val newHeight = (activeBitmap.height * scale).toInt()
                activeBitmap = Bitmap.createScaledBitmap(activeBitmap, newWidth, newHeight, true)
            }

            val baos = ByteArrayOutputStream()
            val format = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                Bitmap.CompressFormat.WEBP_LOSSY
            } else {
                @Suppress("DEPRECATION")
                Bitmap.CompressFormat.WEBP
            }
            activeBitmap.compress(format, initialQuality.coerceIn(1, 100), baos)
            val bytes = baos.toByteArray()
            if (bytes.isEmpty()) return null

            val b64 = Base64.encodeToString(bytes, Base64.NO_WRAP)
            "IMG_B64:$b64"
        } catch (e: Exception) {
            Log.w(TAG, "Notice compressing image Uri '$uriString': ${e.message}")
            uriString
        }
    }

    /**
     * Decodes Base64 data string (from "IMG_B64:[data]") into Android Bitmap for rendering.
     */
    fun decodeBase64ToBitmap(b64Data: String): Bitmap? {
        return try {
            val cleanData = b64Data.removePrefix("IMG_B64:")
            val bytes = Base64.decode(cleanData, Base64.NO_WRAP)
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
        } catch (e: Exception) {
            Log.w(TAG, "Notice decoding Base64 image: ${e.message}")
            null
        }
    }
}
