package com.example.util

/**
 * Payment integration seam for the chat attachment menu.
 * No provider is activated until an approved payment flow and server-side verification exist.
 */
interface PaymentProvider {
    fun isAvailable(): Boolean
    fun availabilityMessage(): String
}

object ComingSoonPaymentProvider : PaymentProvider {
    override fun isAvailable(): Boolean = false
    override fun availabilityMessage(): String =
        "Coming soon. Payments are not initiated from chat yet."
}
