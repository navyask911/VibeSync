package com.example.util

import android.annotation.SuppressLint
import android.content.Context
import android.location.Address
import android.location.Geocoder
import android.location.Location
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.android.gms.tasks.CancellationTokenSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import java.util.Locale

/**
 * Retrieves a fresh, accuracy-checked fix. It intentionally has no hard-coded city/coordinate
 * fallback so a missing fix can never silently share an unrelated location.
 */
object FreshLocationHelper {
    @SuppressLint("MissingPermission")
    suspend fun fetchCurrentLocation(context: Context): DeviceLocationResult = withContext(Dispatchers.IO) {
        val appContext = context.applicationContext
        val fineGranted = androidx.core.content.ContextCompat.checkSelfPermission(
            appContext, android.Manifest.permission.ACCESS_FINE_LOCATION
        ) == android.content.pm.PackageManager.PERMISSION_GRANTED
        val coarseGranted = androidx.core.content.ContextCompat.checkSelfPermission(
            appContext, android.Manifest.permission.ACCESS_COARSE_LOCATION
        ) == android.content.pm.PackageManager.PERMISSION_GRANTED
        if (!fineGranted && !coarseGranted) throw SecurityException("Location permission is required.")

        val cancellation = CancellationTokenSource()
        val fix: Location? = try {
            LocationServices.getFusedLocationProviderClient(appContext)
                .getCurrentLocation(Priority.PRIORITY_HIGH_ACCURACY, cancellation.token)
                .await()
        } finally {
            cancellation.cancel()
        } ?: throw IllegalStateException("No fresh GPS fix is available. Move to an open area and try again.")

        val ageMs = (System.currentTimeMillis() - fix.time).coerceAtLeast(0L)
        if (ageMs > 120_000L) throw IllegalStateException("The GPS fix is stale. Tap Refresh GPS to try again.")
        if (fix.accuracy <= 0f || fix.accuracy > 100f) {
            throw IllegalStateException("GPS accuracy is currently too low. Tap Refresh GPS and try again.")
        }

        val latitude = fix.latitude
        val longitude = fix.longitude
        var city = "Current location"
        var addressText = String.format(Locale.US, "%.5f, %.5f", latitude, longitude)
        var country = ""
        try {
            if (Geocoder.isPresent()) {
                val results: List<Address>? = Geocoder(appContext, Locale.getDefault()).getFromLocation(latitude, longitude, 1)
                val address = results?.firstOrNull()
                if (address != null) {
                    val locality = address.locality ?: address.subAdminArea ?: address.adminArea
                    val administrativeArea = address.adminArea
                    if (!locality.isNullOrBlank()) {
                        city = if (!administrativeArea.isNullOrBlank() && locality != administrativeArea) "$locality, $administrativeArea" else locality
                    }
                    if (address.maxAddressLineIndex >= 0) {
                        address.getAddressLine(0)?.takeIf { it.isNotBlank() }?.let { addressText = it }
                    }
                    country = address.countryName.orEmpty()
                }
            }
        } catch (_: Exception) {
            // Coordinates and measured accuracy remain available when reverse geocoding is offline.
        }
        DeviceLocationResult(
            latitude = latitude,
            longitude = longitude,
            city = city,
            fullAddress = addressText,
            country = country,
            accuracyMeters = fix.accuracy
        )
    }
}
