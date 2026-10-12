package com.example.util

import android.app.Activity
import com.razorpay.Checkout
import org.json.JSONObject

/**
 * Razorpay Standard Checkout adapter. Orders and amounts must come from a trusted backend.
 * Keep the Razorpay Key Secret in Supabase Edge Function secrets; never bundle it in Android.
 */
object PaymentGatewayManager {
    const val API_KEY_ID_PLACEHOLDER = "rzp_test_REPLACE_WITH_KEY_ID"
    const val API_SECRET_PLACEHOLDER = "SERVER_SIDE_ONLY__NEVER_BUNDLE_IN_ANDROID"

    fun openCheckout(activity: Activity, amountPaise: Int, orderId: String, merchantName: String = "VibeSync",
        description: String = "VibeSync purchase", email: String = "", contact: String = "") {
        require(amountPaise > 0) { "A positive server-validated amount in paise is required." }
        require(orderId.isNotBlank()) { "A server-created Razorpay order ID is required." }
        require(!API_KEY_ID_PLACEHOLDER.contains("REPLACE_WITH")) {
            "Configure the Razorpay Key ID and create the order securely before checkout."
        }
        Checkout().apply { setKeyID(API_KEY_ID_PLACEHOLDER) }.open(activity, JSONObject().apply {
            put("name", merchantName); put("description", description); put("currency", "INR")
            put("amount", amountPaise); put("order_id", orderId); put("theme", JSONObject().put("color", "#2563EB"))
            put("prefill", JSONObject().apply { if (email.isNotBlank()) put("email", email); if (contact.isNotBlank()) put("contact", contact) })
        })
    }

    fun preload(activity: Activity) = Checkout.preload(activity.applicationContext)
}
