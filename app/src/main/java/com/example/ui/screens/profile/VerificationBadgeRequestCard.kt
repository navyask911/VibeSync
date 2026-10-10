package com.example.ui.screens.profile

import android.content.Intent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Verified
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.LocalContext
import com.example.util.SupabaseClientManager
import com.example.util.UpiPaymentManager
import kotlinx.coroutines.launch

private data class BadgeTierOption(val tier: String, val name: String, val annualFeeRupees: Int)

@Composable
fun VerificationBadgeRequestCard() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var applicantEmail by remember { mutableStateOf("") }
    var selectedTier by remember { mutableStateOf("BLUE") }
    var submitting by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf<String?>(null) }
    var requestId by remember { mutableStateOf<String?>(null) }

    // Matches the existing business verification annual fees: Blue ₹199, Silver ₹299, Gold ₹499.
    val tiers = listOf(
        BadgeTierOption("BLUE", "Blue", 199),
        BadgeTierOption("SILVER", "Silver", 299),
        BadgeTierOption("GOLD", "Gold", 499)
    )
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f))
    ) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Verified, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.width(6.dp))
                Text("Request Verification Badge", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            }
            Text(
                "Enter the applicant email first. Documents are reviewed through email after the request is submitted.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            OutlinedTextField(
                value = applicantEmail,
                onValueChange = { applicantEmail = it.take(320); status = null; requestId = null },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("Applicant email address") },
                placeholder = { Text("you@example.com") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email)
            )
            tiers.forEach { tier ->
                Row(
                    modifier = Modifier.fillMaxWidth().clickable { selectedTier = tier.tier }.padding(vertical = 3.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    RadioButton(selected = selectedTier == tier.tier, onClick = { selectedTier = tier.tier })
                    Text(tier.name, modifier = Modifier.weight(1f), fontWeight = FontWeight.Bold)
                    Text("₹${tier.annualFeeRupees}/year", fontWeight = FontWeight.Bold)
                }
            }
            Text(
                "Grey Government is not purchasable. It is reserved for verified government officials/institutions and assigned by a VibeSync super-admin only.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Button(
                onClick = {
                    if (submitting) return@Button
                    submitting = true
                    status = null
                    scope.launch {
                        val result = SupabaseClientManager.submitVerificationBadgeRequest(selectedTier, applicantEmail)
                        result.onSuccess {
                            requestId = it
                            status = "Request recorded. Continue to payment using the button below."
                        }.onFailure {
                            status = it.message ?: "Could not submit the request."
                        }
                        submitting = false
                    }
                },
                enabled = !submitting && applicantEmail.isNotBlank(),
                modifier = Modifier.fillMaxWidth()
            ) { Text(if (submitting) "Submitting…" else "Submit email and request") }
            status?.let {
                Text(
                    it + if (requestId != null) " Request ID: $requestId. Payment stays PENDING until receipt is checked; returning from UPI cannot mark it paid automatically." else "",
                    color = if (requestId != null) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall
                )
            }
            if (requestId != null) {
                Button(
                    onClick = {
                        val amount = tiers.firstOrNull { it.tier == selectedTier }?.annualFeeRupees ?: 199
                        val paymentUri = UpiPaymentManager.generateUpiPayUri(
                            amount = amount.toString(),
                            note = "VibeSync $selectedTier verification $requestId",
                            txnRef = requestId.replace("-", "").take(20)
                        )
                        try {
                            context.startActivity(UpiPaymentManager.launchUpiAppIntent(context, null, paymentUri))
                        } catch (_: Exception) {
                            status = "Request saved, but Android could not open a UPI app. Payment remains PENDING."
                        }
                    },
                    modifier = Modifier.fillMaxWidth()
                ) { Text("Continue to UPI payment") }
                Text(
                    "Only pay if the displayed payee matches VibeSync's verified merchant account. Badge status changes only after an administrator confirms payment and documents.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}
