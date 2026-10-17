package com.example.util

/** Creates a compact standards-compliant vCard 3.0 payload for the encrypted chat pipeline. */
object ContactVCardFormatter {
    fun toVCard(name: String, phone: String): String {
        require(name.isNotBlank()) { "Contact name is required." }
        require(phone.isNotBlank()) { "Contact phone is required." }
        return buildString {
            append("BEGIN:VCARD\n")
            append("VERSION:3.0\n")
            append("FN:").append(escape(name.trim())).append('\n')
            append("TEL;TYPE=CELL:").append(escape(phone.trim())).append('\n')
            append("END:VCARD")
        }
    }

    private fun escape(value: String): String = value
        .replace("\\", "\\\\")
        .replace("\r\n", "\n")
        .replace("\r", "\n")
        .replace("\n", "\\n")
        .replace(";", "\\;")
        .replace(",", "\\,")
}
