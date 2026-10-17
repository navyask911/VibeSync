package com.example.util

import android.app.Activity
import com.razorpay.Checkout
import kotlinx.coroutines.CompletableDeferred
import org.json.JSONObject

/**
 * Razorpay Standard Checkout adapter.
 *
 * Order IDs, amount and key ID are provided by the authenticated Supabase Edge Function.
 * The API secret is never shipped to the Android client, and a UI success callback is not
 * considered settled until Supabase verifies the signature and confirms capture with Razorpay.
 */
object PaymentGatewayManager {
    data class PaymentOutcome(
        val recordId: String,
        val paymentId: String = "",
        val gatewayOrderId: String = "",
        val signature: String = "",
        val error: String? = null
    )

    @Volatile private var pendingRecordId: String = ""
    @Volatile private var pendingGatewayOrderId: String = ""
    @Volatile private var pendingResult: CompletableDeferred<PaymentOutcome>? = null

    @Synchronized
    fun prepareForOrder(recordId: String, gatewayOrderId: String): CompletableDeferred<PaymentOutcome> {
        require(recordId.isNotBlank() && gatewayOrderId.isNotBlank()) {
            "A verified server-created Razorpay order is required."
        }
        pendingResult?.takeIf { !it.isCompleted }?.complete(
            PaymentOutcome(pendingRecordId, error = "A newer payment checkout replaced this one.")
        )
        pendingRecordId = recordId
        pendingGatewayOrderId = gatewayOrderId
        return CompletableDeferred<PaymentOutcome>().also { pendingResult = it }
    }

    @Synchronized
    fun reportSuccess(paymentId: String, gatewayOrderId: String, signature: String) {
        val recordId = pendingRecordId
        val expectedOrderId = pendingGatewayOrderId
        val actualOrderId = gatewayOrderId.trim()
        val error = when {
            paymentId.isBlank() -> "Razorpay did not return a payment ID."
            signature.isBlank() -> "Razorpay did not return a payment signature."
            actualOrderId.isNotBlank() && actualOrderId != expectedOrderId ->
                "Payment callback order does not match the active checkout."
            else -> null
        }
        pendingResult?.complete(
            PaymentOutcome(recordId, paymentId.trim(), actualOrderId, signature.trim(), error)
        )
    }

    @Synchronized
    fun reportError(code: Int, response: String?, gatewayOrderId: String = "") {
        val recordId = pendingRecordId
        val expectedOrderId = pendingGatewayOrderId
        val actualOrderId = gatewayOrderId.trim()
        val error = if (actualOrderId.isNotBlank() && actualOrderId != expectedOrderId) {
            "Payment callback order did not match the active checkout."
        } else {
            "Razorpay checkout failed (code $code): ${response.orEmpty().take(180)}"
        }
        pendingResult?.complete(PaymentOutcome(recordId, gatewayOrderId = actualOrderId, error = error))
    }

    @Synchronized
    fun clearPendingOrder(recordId: String) {
        if (pendingRecordId != recordId) return
        pendingRecordId = ""
        pendingGatewayOrderId = ""
        pendingResult = null
    }

    fun openCheckout(
        activity: Activity,
        keyId: String,
        amountPaise: Int,
        orderId: String,
        merchantName: String = "VibeSync",
        description: String = "VibeSync business purchase",
        email: String = "",
        contact: String = ""
    ) {
        require(amountPaise > 0) { "A positive server-validated amount in paise is required." }
        require(orderId.isNotBlank()) { "A server-created Razorpay order ID is required." }
        require(keyId.startsWith("rzp_") && !keyId.contains("REPLACE", ignoreCase = true)) {
            "The server did not return a valid Razorpay Key ID. Configure Razorpay on the backend."
        }
        Checkout().apply { setKeyID(keyId) }.open(activity, JSONObject().apply {
            put("name", merchantName)
            put("description", description)
            put("currency", "INR")
            put("amount", amountPaise)
            put("order_id", orderId)
            put("theme", JSONObject().put("color", "#2563EB"))
            put("prefill", JSONObject().apply {
                if (email.isNotBlank()) put("email", email)
                if (contact.isNotBlank()) put("contact", contact)
            })
        })
    }

    fun preload(activity: Activity) = Checkout.preload(activity.applicationContext)
}
