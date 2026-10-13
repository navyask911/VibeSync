package com.example.data.model

/** A server-authoritative request to disclose a profile phone number. */
data class PhoneNumberAccessRequest(
    val id: String,
    val requesterProfileId: String,
    val targetProfileId: String,
    val requesterName: String,
    val status: String = "PENDING",
    val createdAt: String = ""
)
