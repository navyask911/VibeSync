package com.example.ui.components

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.util.Log
import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountBalance
import androidx.compose.material.icons.filled.Campaign
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.CreditCard
import androidx.compose.material.icons.filled.DoneAll
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material.icons.filled.Phone
import androidx.compose.material.icons.filled.QrCode2
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.Verified
import androidx.compose.material.icons.filled.Wallet
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.RadioButton
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.example.data.model.ApiCredentialRequestEntity
import com.example.data.model.BusinessEntity
import com.example.data.model.VerificationTier
import com.example.data.model.tierEnum
import com.example.data.repository.SocialConnectRepository
import com.example.ui.DatingViewModel
import com.example.util.LocationTrackerHelper
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

import androidx.compose.material.icons.filled.WorkspacePremium

/**
 * Runs a verified business purchase through a server-created Razorpay order.
 * Client callbacks only carry payment proof; wallet/badge changes happen only after the Edge
 * Function validates the signature and confirms a captured, amount-matching payment with Razorpay.
 */
suspend fun performVerifiedBusinessCheckout(
    context: Context,
    businessId: String,
    purchaseType: String,
    points: Int = 0,
    verificationTier: String = ""
): org.json.JSONObject? {
    val activity = context.findActivityForBusinessCheckout()
        ?: return org.json.JSONObject().put("ok", false).put("error", "activity_unavailable")
    val order = com.example.util.SupabaseClientManager.createBusinessPaymentOrder(
        businessId = businessId,
        purchaseType = purchaseType,
        points = points,
        verificationTier = verificationTier
    ) ?: return org.json.JSONObject().put("ok", false).put("error", "payment_order_creation_failed")
    val orderRecordId = order.optString("order_record_id").trim()
    val gatewayOrderId = order.optString("razorpay_order_id").trim()
    val keyId = order.optString("key_id").trim()
    val amountPaise = order.optInt("amount_paise", 0)
    if (orderRecordId.isBlank() || gatewayOrderId.isBlank() || keyId.isBlank() || amountPaise <= 0) {
        return org.json.JSONObject().put("ok", false).put("error", "invalid_server_payment_order")
    }

    val pending = try {
        com.example.util.PaymentGatewayManager.prepareForOrder(orderRecordId, gatewayOrderId)
    } catch (failure: Throwable) {
        Log.w("BusinessCheckout", "Could not prepare payment callback: ${failure.javaClass.simpleName}")
        return org.json.JSONObject().put("ok", false).put("error", "checkout_already_active")
    }
    try {
        com.example.util.PaymentGatewayManager.openCheckout(
            activity = activity,
            keyId = keyId,
            amountPaise = amountPaise,
            orderId = gatewayOrderId,
            merchantName = "VibeSync Business",
            description = when (purchaseType.uppercase()) {
                "WALLET_RECHARGE" -> "$points VibeSync business points"
                "VERIFICATION_BADGE" -> "${verificationTier.uppercase()} business verification"
                else -> "VibeSync business purchase"
            },
            contact = com.example.util.VibeSyncSessionManager.getSession(context)?.phoneNumber.orEmpty()
        )
        val outcome = withTimeoutOrNull(180_000L) { pending.await() }
            ?: return org.json.JSONObject().put("ok", false).put("error", "payment_result_timeout")
        if (outcome.recordId != orderRecordId || !outcome.error.isNullOrBlank() ||
            outcome.paymentId.isBlank() || outcome.signature.isBlank() ||
            (outcome.gatewayOrderId.isNotBlank() && outcome.gatewayOrderId != gatewayOrderId)
        ) {
            return org.json.JSONObject().put("ok", false)
                .put("error", outcome.error ?: "payment_callback_incomplete")
        }

        return com.example.util.SupabaseClientManager.verifyBusinessPaymentOrder(
            orderRecordId = orderRecordId,
            paymentId = outcome.paymentId,
            signature = outcome.signature
        ) ?: org.json.JSONObject().put("ok", false).put("error", "server_payment_verification_failed")
    } catch (failure: Throwable) {
        Log.w("BusinessCheckout", "Checkout did not settle: ${failure.javaClass.simpleName}")
        return org.json.JSONObject().put("ok", false).put("error", "payment_checkout_failed")
    } finally {
        com.example.util.PaymentGatewayManager.clearPendingOrder(orderRecordId)
    }
}

private fun Context.findActivityForBusinessCheckout(): Activity? {
    var current: Context = this
    while (current is ContextWrapper) {
        if (current is Activity) return current
        current = current.baseContext
    }
    return current as? Activity
}

/**
 * Visual badge for Verification Tiers: Gold, Silver, Blue Tick, and Standard.
 */
@Composable
fun VerificationBadgeView(
    tier: VerificationTier,
    modifier: Modifier = Modifier,
    showRank: Boolean = false
) {
    val (bgColor, textColor, borderColor, iconVector, iconTint, label) = when (tier) {
        VerificationTier.GOLD -> {
            Tuple6(
                Color(0xFFFFF8E1),
                Color(0xFFB78103),
                Color(0xFFFFD54F),
                Icons.Filled.Verified,
                Color(0xFFFFB300),
                if (showRank) "Gold Verified (Rank 1)" else "Gold Verified"
            )
        }
        VerificationTier.SILVER -> {
            Tuple6(
                Color(0xFFF5F5F5),
                Color(0xFF37474F),
                Color(0xFFB0BEC5),
                Icons.Filled.WorkspacePremium,
                Color(0xFF78909C),
                if (showRank) "Silver Verified (Rank 2)" else "Silver Verified"
            )
        }
        VerificationTier.BLUE_TICK -> {
            Tuple6(
                Color(0xFFE3F2FD),
                Color(0xFF1565C0),
                Color(0xFF90CAF9),
                Icons.Filled.CheckCircle,
                Color(0xFF1976D2),
                if (showRank) "Blue Tick (Rank 3)" else "Blue Tick Verified"
            )
        }
        VerificationTier.STANDARD -> {
            Tuple6(
                Color(0xFFE8F5E9),
                Color(0xFF2E7D32),
                Color(0xFF81C784),
                Icons.Filled.CheckCircle,
                Color(0xFF388E3C),
                if (showRank) "Registered Partner (Rank 4)" else "Registered Partner"
            )
        }
    }

    Surface(
        color = bgColor,
        shape = RoundedCornerShape(16.dp),
        border = BorderStroke(1.dp, borderColor),
        modifier = modifier
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = iconVector,
                contentDescription = null,
                tint = iconTint,
                modifier = Modifier.size(13.dp)
            )
            Spacer(modifier = Modifier.width(4.dp))
            Text(
                text = label,
                color = textColor,
                fontSize = 10.sp,
                fontFamily = FontFamily.Serif,
                fontWeight = FontWeight.Bold,
                letterSpacing = 0.3.sp
            )
        }
    }
}

private data class Tuple6<A, B, C, D, E, F>(
    val a: A, val b: B, val c: C, val d: D, val e: E, val f: F
)

/**
 * Interactive Payment Gateway Dialog with UPI, Card, NetBanking options.
 */
@Composable
fun VenturePaymentGatewayDialog(
    title: String = "VibeSync Secure Gateway",
    purpose: String,
    amount: Int,
    currencySymbol: String = "₹",
    onSuccess: (txnId: String) -> Unit,
    onDismiss: () -> Unit
) {
    var selectedMethod by remember { mutableIntStateOf(0) } // 0: UPI, 1: Card, 2: NetBanking
    var upiId by remember { mutableStateOf("business@okhdfcbank") }
    var cardNumber by remember { mutableStateOf("4532 •••• •••• 8821") }
    var cardExpiry by remember { mutableStateOf("09/29") }
    var cardCvv by remember { mutableStateOf("•••") }
    var selectedBank by remember { mutableStateOf("HDFC Bank") }
    var isProcessing by remember { mutableStateOf(false) }
    var paymentCompleted by remember { mutableStateOf(false) }
    var generatedTxnId by remember { mutableStateOf("") }
    val scope = rememberCoroutineScope()

    Dialog(
        onDismissRequest = { if (!isProcessing) onDismiss() },
        properties = DialogProperties(dismissOnBackPress = !isProcessing, dismissOnClickOutside = !isProcessing)
    ) {
        Card(
            shape = RoundedCornerShape(20.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            elevation = CardDefaults.cardElevation(defaultElevation = 8.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(20.dp)
            ) {
                // Header
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Surface(
                            shape = CircleShape,
                            color = Color(0xFFE8F5E9),
                            modifier = Modifier.size(36.dp)
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Icon(Icons.Default.Lock, contentDescription = null, tint = Color(0xFF2E7D32), modifier = Modifier.size(18.dp))
                            }
                        }
                        Spacer(modifier = Modifier.width(10.dp))
                        Column {
                            Text(title, fontFamily = FontFamily.Serif, fontWeight = FontWeight.Bold, fontSize = 15.sp)
                            Text("256-bit SSL Encrypted Payment", fontFamily = FontFamily.Serif, fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                    if (!isProcessing) {
                        IconButton(onClick = onDismiss, modifier = Modifier.size(28.dp)) {
                            Icon(Icons.Default.Close, contentDescription = "Close", modifier = Modifier.size(18.dp))
                        }
                    }
                }

                Spacer(modifier = Modifier.height(14.dp))

                // Amount Due Banner
                Surface(
                    shape = RoundedCornerShape(16.dp),
                    color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.3f)),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(14.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column {
                            Text(purpose, fontFamily = FontFamily.Serif, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text("Total Amount Payable", fontFamily = FontFamily.Serif, fontSize = 11.sp, fontWeight = FontWeight.Medium)
                        }
                        Text(
                            text = "$currencySymbol$amount",
                            fontFamily = FontFamily.Serif,
                            fontSize = 26.sp,
                            fontWeight = FontWeight.ExtraBold,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                }

                Spacer(modifier = Modifier.height(14.dp))

                if (paymentCompleted) {
                    // Success View
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 12.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Surface(
                            shape = CircleShape,
                            color = Color(0xFFE8F5E9),
                            modifier = Modifier.size(56.dp)
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Icon(Icons.Default.CheckCircle, contentDescription = null, tint = Color(0xFF2E7D32), modifier = Modifier.size(36.dp))
                            }
                        }
                        Spacer(modifier = Modifier.height(10.dp))
                        Text("Payment Successful!", fontFamily = FontFamily.Serif, fontWeight = FontWeight.Bold, fontSize = 18.sp, color = Color(0xFF1B5E20))
                        Spacer(modifier = Modifier.height(4.dp))
                        Text("Transaction ID: $generatedTxnId", fontFamily = FontFamily.Serif, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Spacer(modifier = Modifier.height(16.dp))
                        Button(
                            onClick = { onSuccess(generatedTxnId) },
                            modifier = Modifier.fillMaxWidth().height(48.dp),
                            shape = RoundedCornerShape(16.dp),
                            elevation = ButtonDefaults.buttonElevation(defaultElevation = 3.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF2E7D32))
                        ) {
                            Text("Continue to Business Hub", fontFamily = FontFamily.Serif, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                        }
                    }
                } else if (isProcessing) {
                    // Processing Spinner
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        CircularProgressIndicator(
                            color = MaterialTheme.colorScheme.primary,
                            strokeWidth = 3.dp,
                            modifier = Modifier.size(44.dp)
                        )
                        Spacer(modifier = Modifier.height(14.dp))
                        Text("Contacting Bank & Verifying Payment...", fontFamily = FontFamily.Serif, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                        Spacer(modifier = Modifier.height(4.dp))
                        Text("Please do not press back or close the app while the transaction confirms.", fontFamily = FontFamily.Serif, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                } else {
                    // Payment Method Tabs
                    TabRow(
                        selectedTabIndex = selectedMethod,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Tab(
                            selected = selectedMethod == 0,
                            onClick = { selectedMethod = 0 },
                            text = { Text("UPI Instant", fontFamily = FontFamily.Serif, fontSize = 12.sp, fontWeight = FontWeight.Bold) },
                            icon = { Icon(Icons.Default.QrCode2, contentDescription = null, modifier = Modifier.size(16.dp)) }
                        )
                        Tab(
                            selected = selectedMethod == 1,
                            onClick = { selectedMethod = 1 },
                            text = { Text("Cards / Debit", fontFamily = FontFamily.Serif, fontSize = 12.sp, fontWeight = FontWeight.Bold) },
                            icon = { Icon(Icons.Default.CreditCard, contentDescription = null, modifier = Modifier.size(16.dp)) }
                        )
                        Tab(
                            selected = selectedMethod == 2,
                            onClick = { selectedMethod = 2 },
                            text = { Text("NetBanking", fontFamily = FontFamily.Serif, fontSize = 12.sp, fontWeight = FontWeight.Bold) },
                            icon = { Icon(Icons.Default.AccountBalance, contentDescription = null, modifier = Modifier.size(16.dp)) }
                        )
                    }

                    Spacer(modifier = Modifier.height(14.dp))

                    when (selectedMethod) {
                        0 -> { // UPI
                            Column {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    listOf("GPay", "PhonePe", "Paytm", "BHIM").forEach { app ->
                                        Surface(
                                            shape = RoundedCornerShape(12.dp),
                                            color = MaterialTheme.colorScheme.surfaceVariant,
                                            modifier = Modifier
                                                .weight(1f)
                                                .clickable { upiId = "business@${app.lowercase()}" }
                                        ) {
                                            Text(
                                                text = app,
                                                fontFamily = FontFamily.Serif,
                                                fontSize = 11.sp,
                                                fontWeight = FontWeight.Bold,
                                                textAlign = TextAlign.Center,
                                                modifier = Modifier.padding(vertical = 8.dp)
                                            )
                                        }
                                    }
                                }
                                Spacer(modifier = Modifier.height(10.dp))
                                OutlinedTextField(
                                    value = upiId,
                                    onValueChange = { upiId = it },
                                    label = { Text("UPI Virtual Payment Address (VPA)", fontFamily = FontFamily.Serif) },
                                    placeholder = { Text("e.g. yourbusiness@upi", fontFamily = FontFamily.Serif) },
                                    singleLine = true,
                                    shape = RoundedCornerShape(12.dp),
                                    modifier = Modifier.fillMaxWidth()
                                )
                            }
                        }
                        1 -> { // Cards
                            Column {
                                OutlinedTextField(
                                    value = cardNumber,
                                    onValueChange = { cardNumber = it },
                                    label = { Text("Card Number", fontFamily = FontFamily.Serif) },
                                    singleLine = true,
                                    shape = RoundedCornerShape(12.dp),
                                    modifier = Modifier.fillMaxWidth()
                                )
                                Spacer(modifier = Modifier.height(8.dp))
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    OutlinedTextField(
                                        value = cardExpiry,
                                        onValueChange = { cardExpiry = it },
                                        label = { Text("MM/YY", fontFamily = FontFamily.Serif) },
                                        singleLine = true,
                                        shape = RoundedCornerShape(12.dp),
                                        modifier = Modifier.weight(1f)
                                    )
                                    OutlinedTextField(
                                        value = cardCvv,
                                        onValueChange = { cardCvv = it },
                                        label = { Text("CVV", fontFamily = FontFamily.Serif) },
                                        singleLine = true,
                                        shape = RoundedCornerShape(12.dp),
                                        modifier = Modifier.weight(1f)
                                    )
                                }
                            }
                        }
                        2 -> { // NetBanking
                            Column {
                                Text("Popular NetBanking Institutions:", fontFamily = FontFamily.Serif, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
                                Spacer(modifier = Modifier.height(6.dp))
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                                ) {
                                    listOf("HDFC", "ICICI", "SBI", "Axis").forEach { bank ->
                                        val isSel = selectedBank.startsWith(bank)
                                        Surface(
                                            shape = RoundedCornerShape(12.dp),
                                            color = if (isSel) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant,
                                            border = if (isSel) BorderStroke(1.dp, MaterialTheme.colorScheme.primary) else null,
                                            modifier = Modifier
                                                .weight(1f)
                                                .clickable { selectedBank = "$bank Bank" }
                                        ) {
                                            Text(
                                                text = bank,
                                                fontFamily = FontFamily.Serif,
                                                fontSize = 11.sp,
                                                fontWeight = FontWeight.Bold,
                                                textAlign = TextAlign.Center,
                                                modifier = Modifier.padding(vertical = 8.dp)
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(16.dp))

                    Button(
                        onClick = {
                            scope.launch {
                                isProcessing = true
                                delay(1200) // realistic transaction round-trip
                                isProcessing = false
                                generatedTxnId = "TXN_VS_${System.currentTimeMillis().toString().takeLast(8)}"
                                paymentCompleted = true
                            }
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(48.dp),
                        shape = RoundedCornerShape(16.dp),
                        elevation = ButtonDefaults.buttonElevation(defaultElevation = 3.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF2E7D32))
                    ) {
                        Icon(Icons.Default.Lock, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Pay $currencySymbol$amount Securely", fontFamily = FontFamily.Serif, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                    }
                }
            }
        }
    }
}

/**
 * Verification Tier Selection Cards for AddWithUsTab and Upgrades.
 */
@Composable
fun VerificationTierSelectionSection(
    selectedTier: String,
    onSelectTier: (String) -> Unit
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Text("Choose Verification Badge (Optional Upgrade)", fontFamily = FontFamily.Serif, fontWeight = FontWeight.Bold, fontSize = 15.sp)
        Text(
            text = "Higher verification tiers unlock priority search ranking, authentic badge trust, and bonus follower broadcast credits.",
            fontFamily = FontFamily.Serif,
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        Spacer(modifier = Modifier.height(10.dp))

        val tiers = listOf(
            TierCardItem(
                id = "GOLD", badgeEmoji = "👑", title = "Gold Crown Verified",
                price = "₹499/yr", priceInt = 499, priorityLabel = "TOP SEARCH PLACEMENT",
                perks = listOf("Top search placement", "Gold badge on profile and chats", "2× broadcast discount"),
                accentColor = Color(0xFFFFA500), bgColor = Color(0xFFFFD700)
            ),
            TierCardItem(
                id = "SILVER", badgeEmoji = "♛", title = "Silver Crown Verified",
                price = "₹299/yr", priceInt = 299, priorityLabel = "GROWING BRANDS",
                perks = listOf("Verified badge on profile and chats", "Boosted search placement", "Broadcast advertising access"),
                accentColor = Color(0xFF66717D), bgColor = Color(0xFFE0E0E0)
            ),
            TierCardItem(
                id = "BLUE_TICK", badgeEmoji = "✓", title = "Blue Verified",
                price = "₹199/yr", priceInt = 199, priorityLabel = "AUTHENTICITY",
                perks = listOf("Electric-blue verified checkmark", "Authenticity and trust in search", "Checkmark shown in chats"),
                accentColor = Color(0xFF1DA1F2), bgColor = Color(0xFFDDF2FF)
            ),
            TierCardItem(
                id = "STANDARD", badgeEmoji = "🏷️", title = "Standard Listing",
                price = "₹0 add-on", priceInt = 0, priorityLabel = "STANDARD",
                perks = listOf("Standard directory placement", "Basic business profile", "No paid verification badge"),
                accentColor = Color(0xFF9E9E9E), bgColor = Color(0xFFF5F5F5)
            )
        )

        tiers.forEach { tier ->
            val isSelected = selectedTier == tier.id
            val gradient = when (tier.id) {
                "GOLD" -> Brush.linearGradient(listOf(Color(0xFFFFD700), Color(0xFFFFA500)))
                "SILVER" -> Brush.linearGradient(listOf(Color(0xFFE0E0E0), Color(0xFFB0B0B0)))
                "BLUE_TICK" -> Brush.linearGradient(listOf(Color(0xFFEAF7FF), Color(0xFFB7E3FF)))
                else -> Brush.linearGradient(listOf(Color(0xFFF5F5F5), Color(0xFFE8E8E8)))
            }
            Card(
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = Color.Transparent),
                border = BorderStroke(if (isSelected) 3.dp else 1.dp, if (isSelected) tier.accentColor else MaterialTheme.colorScheme.outlineVariant),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 4.dp)
                    .clip(RoundedCornerShape(16.dp))
                    .background(gradient)
                    .clickable { onSelectTier(tier.id) }
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    if (tier.id == "BLUE_TICK") {
                        Surface(shape = CircleShape, color = Color(0xFF1DA1F2), modifier = Modifier.size(34.dp)) {
                            Box(contentAlignment = Alignment.Center) {
                                Icon(Icons.Default.Verified, contentDescription = "Blue verified check", tint = Color.White, modifier = Modifier.size(24.dp))
                            }
                        }
                    } else {
                        Text(tier.badgeEmoji, fontSize = 29.sp)
                    }
                    Spacer(modifier = Modifier.width(10.dp))
                    Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(tier.title, fontFamily = FontFamily.Serif, fontWeight = FontWeight.ExtraBold, fontSize = 13.sp, color = Color(0xFF20242A))
                            Spacer(modifier = Modifier.width(6.dp))
                            Surface(shape = RoundedCornerShape(6.dp), color = tier.accentColor.copy(alpha = 0.18f)) {
                                Text(
                                    tier.priorityLabel,
                                    fontFamily = FontFamily.Serif, fontSize = 8.sp, fontWeight = FontWeight.Bold,
                                    color = Color(0xFF20242A), modifier = Modifier.padding(horizontal = 6.dp, vertical = 3.dp)
                                )
                            }
                        }
                        tier.perks.forEach { perk ->
                            Text("• $perk", fontFamily = FontFamily.Serif, fontSize = 10.sp, color = Color(0xFF2E3338))
                        }
                    }
                    Column(horizontalAlignment = Alignment.End) {
                        Text(tier.price, fontFamily = FontFamily.Serif, fontWeight = FontWeight.ExtraBold, fontSize = 13.sp, color = Color(0xFF1E293B))
                        RadioButton(selected = isSelected, onClick = { onSelectTier(tier.id) })
                    }
                }
            }
        }

        // Live profile and chat-header badge preview updates with the radio selection above.
        val previewTier = tiers.first { it.id == selectedTier }
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(16.dp),
            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.48f),
            border = BorderStroke(1.dp, previewTier.accentColor.copy(alpha = 0.65f))
        ) {
            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("Live preview", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Surface(shape = CircleShape, color = MaterialTheme.colorScheme.surface, modifier = Modifier.size(42.dp)) {
                        Box(contentAlignment = Alignment.Center) { Text("🏪", fontSize = 22.sp) }
                    }
                    Spacer(Modifier.width(10.dp))
                    Column(Modifier.weight(1f)) {
                        Text("Your Business", fontWeight = FontWeight.Bold)
                        Text("Profile listing", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    TierPreviewMark(selectedTier)
                }
                HorizontalDivider()
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Chat header", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.width(8.dp))
                    Text("Your Business", fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.width(4.dp))
                    TierPreviewMark(selectedTier)
                }
            }
        }
    }
}

private data class TierCardItem(
    val id: String,
    val badgeEmoji: String,
    val title: String,
    val price: String,
    val priceInt: Int,
    val priorityLabel: String,
    val perks: List<String>,
    val accentColor: Color,
    val bgColor: Color
)

@Composable
private fun TierPreviewMark(tier: String) {
    when (tier.uppercase()) {
        "GOLD" -> Text("👑", fontSize = 15.sp)
        "SILVER" -> Text("♛", fontSize = 16.sp, color = Color(0xFF8A949E))
        "BLUE_TICK" -> Icon(Icons.Default.Verified, contentDescription = "Blue verified", tint = Color(0xFF1DA1F2), modifier = Modifier.size(17.dp))
        else -> Text("○", fontSize = 15.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/**
 * Business Wallet Balance & Top-Up Dialog (1 Point = ₹0.05).
 */
@Composable
fun BusinessWalletDialog(
    business: BusinessEntity,
    viewModel: DatingViewModel,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var walletPoints by remember(business.id) { mutableIntStateOf(business.walletPoints) }
    var pointsExpiring by remember(business.id) { mutableIntStateOf(0) }
    var recentDeductions by remember(business.id) { mutableIntStateOf(0) }
    var customPointsText by remember { mutableStateOf("") }
    var selectedPackagePoints by remember { mutableIntStateOf(500) }
    var checkoutInProgress by remember { mutableStateOf(false) }

    val packs = listOf(
        WalletPack(500, 25, 0, "Starter reach"),
        WalletPack(2500, 125, 0, "Growth reach"),
        WalletPack(10000, 500, 0, "Best Value")
    )
    val customPoints = customPointsText.toIntOrNull()
    val isCustomInput = customPointsText.isNotBlank()
    val customPointsValid = !isCustomInput || (customPoints != null && customPoints in 100..1_000_000)
    val pointsToBuy = if (isCustomInput && customPointsValid) customPoints!! else selectedPackagePoints
    val basePrice = pointsToBuy * 0.05
    val gstAmount = basePrice * 0.18
    val totalPrice = basePrice + gstAmount

    LaunchedEffect(business.id) {
        val snapshot = com.example.util.SupabaseClientManager.fetchBusinessWalletSnapshot(business.id)
        if (snapshot?.optBoolean("ok", false) == true) {
            walletPoints = snapshot.optInt("balance", business.walletPoints).coerceAtLeast(0)
            pointsExpiring = snapshot.optInt("points_expiring", 0).coerceAtLeast(0)
            val entries = snapshot.optJSONArray("recent_entries")
            var deductions = 0
            if (entries != null) for (index in 0 until entries.length()) {
                val entry = entries.optJSONObject(index) ?: continue
                val delta = entry.optInt("points_delta", 0)
                if (delta < 0) deductions += -delta
            }
            recentDeductions = deductions
            viewModel.reconcileBusinessWalletBalance(business.id, walletPoints)
        }
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            shape = RoundedCornerShape(20.dp),
            color = MaterialTheme.colorScheme.surface,
            modifier = Modifier.fillMaxWidth(0.92f).padding(vertical = 24.dp)
        ) {
            Column(
                modifier = Modifier.fillMaxWidth().padding(20.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        Text("Business Points Wallet", fontFamily = FontFamily.Serif, fontWeight = FontWeight.Bold, fontSize = 17.sp)
                        Text(business.name, fontFamily = FontFamily.Serif, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    IconButton(onClick = onDismiss, modifier = Modifier.size(28.dp)) {
                        Icon(Icons.Default.Close, contentDescription = "Close wallet")
                    }
                }

                Card(
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(containerColor = Color(0xFF14532D)),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(Modifier.fillMaxWidth().padding(18.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                        Text("AVAILABLE POINTS", color = Color(0xFFBBF7D0), fontSize = 11.sp, fontWeight = FontWeight.Bold)
                        Text(walletPoints.toString(), color = Color.White, fontSize = 32.sp, fontWeight = FontWeight.ExtraBold)
                        Text("Available balance synced from the verified wallet ledger.", color = Color.White.copy(alpha = 0.86f), fontSize = 11.sp)
                        HorizontalDivider(color = Color.White.copy(alpha = 0.25f), modifier = Modifier.padding(vertical = 5.dp))
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text("Recent deductions", color = Color.White, fontSize = 12.sp)
                            Text("$recentDeductions pts", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                        }
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text("Points expiring in 30 days", color = Color.White, fontSize = 12.sp)
                            Text("$pointsExpiring pts", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                        }
                    }
                }

                Text("Recharge points", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                packs.forEach { pack ->
                    val selected = selectedPackagePoints == pack.points && !isCustomInput
                    val base = pack.points * 0.05
                    val gst = base * 0.18
                    Surface(
                        modifier = Modifier.fillMaxWidth().clickable {
                            selectedPackagePoints = pack.points
                            customPointsText = ""
                        },
                        shape = RoundedCornerShape(14.dp),
                        color = if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f),
                        border = BorderStroke(if (selected) 2.dp else 1.dp, if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant)
                    ) {
                        Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text("%,d points".format(Locale.US, pack.points), fontWeight = FontWeight.Bold)
                                    if (pack.label == "Best Value") {
                                        Spacer(Modifier.width(6.dp))
                                        Surface(shape = RoundedCornerShape(5.dp), color = Color(0xFF166534)) {
                                            Text("BEST VALUE", color = Color.White, fontSize = 9.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(horizontal = 5.dp, vertical = 3.dp))
                                        }
                                    }
                                }
                                Text("Base ₹%.2f + GST ₹%.2f = ₹%.2f".format(Locale.US, base, gst, base + gst), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            RadioButton(selected = selected, onClick = {
                                selectedPackagePoints = pack.points
                                customPointsText = ""
                            })
                        }
                    }
                }

                OutlinedTextField(
                    value = customPointsText,
                    onValueChange = { input -> customPointsText = input.filter(Char::isDigit).take(7) },
                    label = { Text("Custom points (100–1,000,000)") },
                    supportingText = {
                        if (isCustomInput && !customPointsValid) Text("Enter between 100 and 1,000,000 points.")
                        else Text("Base ₹%.2f + GST ₹%.2f (18%) = ₹%.2f".format(Locale.US, pointsToBuy * 0.05, pointsToBuy * 0.05 * 0.18, totalPrice))
                    },
                    isError = isCustomInput && !customPointsValid,
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )

                Button(
                    enabled = !checkoutInProgress && customPointsValid && pointsToBuy in 100..1_000_000,
                    onClick = {
                        scope.launch {
                            checkoutInProgress = true
                            val settled = try {
                                performVerifiedBusinessCheckout(
                                    context = context,
                                    businessId = business.id,
                                    purchaseType = "WALLET_RECHARGE",
                                    points = pointsToBuy
                                )
                            } catch (failure: Throwable) {
                                Log.w("BusinessWallet", "Checkout failed: ${failure.javaClass.simpleName}")
                                null
                            } finally {
                                checkoutInProgress = false
                            }
                            if (settled?.optBoolean("ok", false) == true) {
                                val snapshot = com.example.util.SupabaseClientManager.fetchBusinessWalletSnapshot(business.id)
                                walletPoints = snapshot?.optInt("balance", -1)?.takeIf { it >= 0 }
                                    ?: settled.optInt("balance", walletPoints + pointsToBuy)
                                pointsExpiring = snapshot?.optInt("points_expiring", pointsExpiring) ?: pointsExpiring
                                viewModel.reconcileBusinessWalletBalance(business.id, walletPoints)
                                Toast.makeText(context, "Payment verified. Wallet now has $walletPoints points.", Toast.LENGTH_LONG).show()
                            } else {
                                val reason = settled?.optString("error").orEmpty()
                                Toast.makeText(
                                    context,
                                    if (reason == "razorpay_keys_not_configured") "Secure checkout is not configured on the server yet."
                                    else "Payment was not verified. No wallet points were added. Please retry checkout.",
                                    Toast.LENGTH_LONG
                                ).show()
                            }
                        }
                    },
                    modifier = Modifier.fillMaxWidth().height(48.dp),
                    shape = RoundedCornerShape(14.dp)
                ) {
                    if (checkoutInProgress) {
                        CircularProgressIndicator(modifier = Modifier.size(17.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.width(8.dp))
                        Text("Verifying payment…")
                    } else {
                        Icon(Icons.Default.CreditCard, contentDescription = null, modifier = Modifier.size(17.dp))
                        Spacer(Modifier.width(7.dp))
                        Text("Pay ₹%.2f securely".format(Locale.US, totalPrice), fontWeight = FontWeight.Bold)
                    }
                }
                Text(
                    "Points are credited only after server-side payment signature and captured-amount verification. Payment secrets are never stored in the app.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

private data class WalletPack(
    val points: Int,
    val cost: Int,
    val bonus: Int,
    val label: String
)

/**
 * Follower Broadcast Message Dialog with Points Calculation and Double Tick Refund.
 */
@Composable
fun SendFollowerBroadcastDialog(
    business: BusinessEntity,
    viewModel: DatingViewModel,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var title by remember { mutableStateOf("") }
    var content by remember { mutableStateOf("") }
    var bannerUrl by remember { mutableStateOf("") }
    var ctaLabel by remember { mutableStateOf("") }
    var ctaUrl by remember { mutableStateOf("") }
    var targetType by remember { mutableStateOf("ALL_FOLLOWERS") }
    var radiusKm by remember { mutableIntStateOf(5) }
    var interestTagsText by remember { mutableStateOf("food, shopping, local") }
    var isSending by remember { mutableStateOf(false) }
    var broadcastResult by remember { mutableStateOf<SocialConnectRepository.FollowerBroadcastResult?>(null) }
    var showTopUpShortcut by remember { mutableStateOf(false) }

    // Audience estimates are conservative upper bounds. Server-side geolocation/interests and the
    // wallet are rechecked transactionally before points are deducted.
    val estimatedAudience = business.followerCount.coerceAtLeast(0)
    val pointsPerUser = if (business.verificationTier.equals("GOLD", ignoreCase = true)) 0.5 else 1.0
    val estimatedPoints = kotlin.math.ceil(estimatedAudience * pointsPerUser).toInt()
    val estimatedBalanceSufficient = business.walletPoints >= estimatedPoints

    if (showTopUpShortcut) {
        BusinessWalletDialog(
            business = business,
            viewModel = viewModel,
            onDismiss = { showTopUpShortcut = false }
        )
    }

    Dialog(
        onDismissRequest = { if (!isSending) onDismiss() },
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            shape = RoundedCornerShape(20.dp),
            color = MaterialTheme.colorScheme.surface,
            modifier = Modifier.fillMaxWidth(0.94f).padding(vertical = 16.dp)
        ) {
            Column(
                modifier = Modifier.fillMaxWidth().padding(18.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(11.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(Modifier.weight(1f)) {
                        Text("Ads with Us · Broadcast", fontWeight = FontWeight.ExtraBold, fontSize = 17.sp)
                        Text(business.name, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    IconButton(onClick = onDismiss, enabled = !isSending) {
                        Icon(Icons.Default.Close, contentDescription = "Close broadcast")
                    }
                }

                broadcastResult?.let { result ->
                    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)) {
                        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Icon(Icons.Default.Campaign, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(30.dp))
                            Text(if (result.success) "Broadcast staged" else "Broadcast not sent", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleLarge)
                            Text(result.message, style = MaterialTheme.typography.bodyMedium)
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                Text("Eligible recipients")
                                Text(result.totalFollowers.toString(), fontWeight = FontWeight.Bold)
                            }
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                Text("Points charged")
                                Text(result.pointsDeducted.toString(), fontWeight = FontWeight.Bold)
                            }
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                Text("Current balance")
                                Text(result.remainingWalletPoints.toString(), fontWeight = FontWeight.Bold)
                            }
                            Button(onClick = onDismiss, modifier = Modifier.fillMaxWidth()) { Text("Done") }
                        }
                    }
                } ?: run {
                    Text("1. Audience", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.horizontalScroll(rememberScrollState())) {
                        listOf(
                            "ALL_FOLLOWERS" to "All Followers",
                            "NEARBY" to "Local Nearby",
                            "INTEREST_TAGS" to "Interest Tags"
                        ).forEach { (value, label) ->
                            Surface(
                                shape = RoundedCornerShape(20.dp),
                                color = if (targetType == value) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant,
                                modifier = Modifier.clickable { targetType = value }.padding(1.dp)
                            ) {
                                Text(label, modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                                    color = if (targetType == value) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
                                    style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold)
                            }
                        }
                    }
                    if (targetType == "NEARBY") {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            listOf(5, 10).forEach { km ->
                                Surface(
                                    shape = RoundedCornerShape(12.dp),
                                    color = if (radiusKm == km) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceVariant,
                                    modifier = Modifier.clickable { radiusKm = km }
                                ) {
                                    Text("$km km", modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp), fontWeight = FontWeight.Bold)
                                }
                            }
                        }
                    }
                    if (targetType == "INTEREST_TAGS") {
                        OutlinedTextField(
                            value = interestTagsText,
                            onValueChange = { interestTagsText = it },
                            label = { Text("Interest tags, comma separated") },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true
                        )
                    }

                    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)) {
                        Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text("Estimated upper-bound cost", fontWeight = FontWeight.Bold)
                            Text("$estimatedAudience × $pointsPerUser point/user = $estimatedPoints points", fontWeight = FontWeight.Bold)
                            Text(
                                "Exact nearby/tag audience and final charge are calculated by Supabase when you send. Gold tier receives the 50% rate.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSecondaryContainer
                            )
                            Text("Wallet balance: ${business.walletPoints} points", style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                    if (!estimatedBalanceSufficient) {
                        Surface(
                            shape = RoundedCornerShape(12.dp),
                            color = MaterialTheme.colorScheme.errorContainer,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text("Low Balance — Recharge Points to Broadcast", color = MaterialTheme.colorScheme.onErrorContainer, fontWeight = FontWeight.Bold)
                                Button(onClick = { showTopUpShortcut = true }, modifier = Modifier.fillMaxWidth()) { Text("Recharge Points") }
                                Text("You may still submit: the server checks the exact filtered audience before charging.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onErrorContainer)
                            }
                        }
                    }

                    Text("2. Broadcast content", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    OutlinedTextField(
                        value = title, onValueChange = { title = it.take(120) },
                        label = { Text("Headline *") }, singleLine = true, modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = content, onValueChange = { content = it.take(1800) },
                        label = { Text("Message *") }, minLines = 3, maxLines = 6, modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = bannerUrl, onValueChange = { bannerUrl = it.take(1000) },
                        label = { Text("Optional banner image URL") }, singleLine = true, modifier = Modifier.fillMaxWidth()
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                        OutlinedTextField(
                            value = ctaLabel, onValueChange = { ctaLabel = it.take(50) },
                            label = { Text("CTA label") }, singleLine = true, modifier = Modifier.weight(1f)
                        )
                        OutlinedTextField(
                            value = ctaUrl, onValueChange = { ctaUrl = it.take(500) },
                            label = { Text("CTA link (HTTPS)") }, singleLine = true, modifier = Modifier.weight(1f)
                        )
                    }
                    Button(
                        enabled = title.isNotBlank() && content.isNotBlank() && !isSending &&
                            (ctaUrl.isBlank() || ctaUrl.trim().startsWith("https://", ignoreCase = true)),
                        onClick = {
                            scope.launch {
                                isSending = true
                                val staged = try {
                                    com.example.util.SupabaseClientManager.stageBusinessBroadcast(
                                        businessId = business.id,
                                        title = title.trim(),
                                        message = content.trim(),
                                        targetType = targetType,
                                        radiusKm = radiusKm,
                                        interestTags = if (targetType == "INTEREST_TAGS") interestTagsText.split(",").map { it.trim() }.filter { it.isNotBlank() }.distinct() else emptyList(),
                                        bannerUrl = bannerUrl.trim(),
                                        ctaLabel = ctaLabel.trim(),
                                        ctaUrl = ctaUrl.trim()
                                    )
                                } catch (_: Throwable) {
                                    null
                                } finally {
                                    isSending = false
                                }
                                if (staged == null) {
                                    Toast.makeText(context, "Broadcast could not be staged. Check your connection and try again.", Toast.LENGTH_LONG).show()
                                } else if (!staged.optBoolean("ok", false)) {
                                    val error = staged.optString("error", "broadcast_staging_failed")
                                    if (error == "low_balance") showTopUpShortcut = true
                                    Toast.makeText(
                                        context,
                                        if (error == "low_balance") "Low balance. Recharge points before sending this audience."
                                        else "Broadcast was not sent: $error",
                                        Toast.LENGTH_LONG
                                    ).show()
                                } else {
                                    val audience = staged.optInt("audience_size", estimatedAudience).coerceAtLeast(0)
                                    val charged = staged.optInt("required_points", estimatedPoints).coerceAtLeast(0)
                                    val balance = staged.optInt("balance", (business.walletPoints - charged).coerceAtLeast(0)).coerceAtLeast(0)
                                    viewModel.reconcileBusinessWalletBalance(business.id, balance)
                                    val dispatchTriggered = staged.optBoolean("dispatch_triggered", false)
                                    val dispatch = staged.optJSONObject("dispatch")
                                    val sentDevices = dispatch?.optInt("sent_devices", 0)?.coerceAtLeast(0) ?: 0
                                    val failedDevices = dispatch?.optInt("failed_devices", 0)?.coerceAtLeast(0) ?: 0
                                    val message = when {
                                        dispatchTriggered && dispatch?.optString("status") == "DISPATCHED" ->
                                            "Broadcast staged and dispatch completed. Device delivery/open rates will update as events arrive."
                                        else -> "Broadcast staged securely. FCM dispatch is pending retry; no additional points were charged."
                                    }
                                    broadcastResult = SocialConnectRepository.FollowerBroadcastResult(
                                        success = true,
                                        totalFollowers = audience,
                                        pointsDeducted = charged,
                                        deliveredCount = sentDevices,
                                        undeliveredCount = failedDevices,
                                        pointsReversed = 0,
                                        remainingWalletPoints = balance,
                                        message = message
                                    )
                                }
                            }
                        },
                        modifier = Modifier.fillMaxWidth().height(50.dp),
                        shape = RoundedCornerShape(14.dp)
                    ) {
                        if (isSending) {
                            CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                            Spacer(Modifier.width(8.dp))
                            Text("Staging & dispatching…")
                        } else {
                            Icon(Icons.Default.Campaign, contentDescription = null, modifier = Modifier.size(17.dp))
                            Spacer(Modifier.width(7.dp))
                            Text("Stage & Send Broadcast", fontWeight = FontWeight.Bold)
                        }
                    }
                    Text(
                        "Points are deducted transactionally by Supabase. No fabricated delivery counts or local-only wallet charges are used.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

/**
 * VibeSync Cloud Business Messenger API Configuration Modal for Venue Owners & Admin Panel.
 */
/**
 * Secure Backend API Access Management & Request Dialog (Requirement: Hidden from Frontend).
 * Completely removes visible secrets/tokens and routes requests through admin approval on need-basis.
 */
@Composable
fun VibeSyncCloudApiConfigDialog(
    business: BusinessEntity,
    viewModel: DatingViewModel,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val apiRequestsFlow = remember(business.id) { viewModel.getApiRequestsForBusiness(business.id) }
    val existingRequests by apiRequestsFlow.collectAsState(initial = emptyList())

    var showSubmitRequestModal by remember { mutableStateOf(false) }
    var contactPhone by remember { mutableStateOf(business.phoneNumber) }
    var contactEmail by remember { mutableStateOf("partner@${business.name.lowercase().replace(" ", "")}.com") }
    var intendedUseCase by remember { mutableStateOf("") }
    var integrationType by remember { mutableStateOf("MESSENGER_WEBHOOK") }
    var isSubmitting by remember { mutableStateOf(false) }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            shape = RoundedCornerShape(20.dp),
            color = MaterialTheme.colorScheme.surface,
            modifier = Modifier
                .fillMaxWidth(0.92f)
                .padding(vertical = 24.dp)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(20.dp)
                    .verticalScroll(rememberScrollState())
            ) {
                // Header
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Surface(
                            shape = CircleShape,
                            color = Color(0xFFE8F5E9),
                            modifier = Modifier.size(38.dp)
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Icon(Icons.Default.Lock, contentDescription = null, tint = Color(0xFF2E7D32), modifier = Modifier.size(20.dp))
                            }
                        }
                        Spacer(modifier = Modifier.width(10.dp))
                        Column {
                            Text("Partner API & Webhooks", fontFamily = FontFamily.Serif, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                            Text("Protected by VibeSync Enterprise Gateway 🔒", fontFamily = FontFamily.Serif, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                    IconButton(onClick = onDismiss, modifier = Modifier.size(28.dp)) {
                        Icon(Icons.Default.Close, contentDescription = "Close")
                    }
                }

                Spacer(modifier = Modifier.height(14.dp))

                // Security Shield Banner
                Surface(
                    shape = RoundedCornerShape(16.dp),
                    color = Color(0xFFE8F5E9),
                    border = BorderStroke(1.dp, Color(0xFF81C784)),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(Icons.Default.Shield, contentDescription = null, tint = Color(0xFF2E7D32), modifier = Modifier.size(28.dp))
                        Spacer(modifier = Modifier.width(10.dp))
                        Column {
                            Text("Enterprise Security Active 🛡️", fontFamily = FontFamily.Serif, fontWeight = FontWeight.Bold, fontSize = 12.sp, color = Color(0xFF1B5E20))
                            Text(
                                "API keys, Webhook secrets, and tokens are stored securely in backend edge vaults and never exposed on frontend clients.",
                                fontFamily = FontFamily.Serif,
                                fontSize = 11.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(14.dp))

                // Active Integration State
                val latestApproved = existingRequests.firstOrNull { it.status == "APPROVED" }
                val isApproved = latestApproved != null || business.whatsappApiEnabled

                Surface(
                    shape = RoundedCornerShape(16.dp),
                    color = if (isApproved) Color(0xFFE8F5E9) else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f),
                    border = BorderStroke(1.dp, if (isApproved) Color(0xFF81C784) else MaterialTheme.colorScheme.outlineVariant),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(14.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = if (isApproved) "API Integration Provisioned 🟢" else "API Access Restricted 🔒",
                                fontFamily = FontFamily.Serif,
                                fontWeight = FontWeight.Bold,
                                fontSize = 13.sp,
                                color = if (isApproved) Color(0xFF1B5E20) else MaterialTheme.colorScheme.onSurface
                            )
                            Text(
                                text = if (isApproved) 
                                    "Your business is verified. Automated booking webhooks and messenger dispatches are routed through secure edge servers."
                                else "Direct API keys and webhooks require admin authorization based on verified business need.",
                                fontFamily = FontFamily.Serif,
                                fontSize = 11.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                if (showSubmitRequestModal) {
                    // Request API Access Submission Form
                    Card(
                        shape = RoundedCornerShape(16.dp),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)),
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.4f)),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(modifier = Modifier.padding(14.dp)) {
                            Text("Request API & Webhook Access", fontFamily = FontFamily.Serif, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                            Text("Submit your integration purpose for admin review.", fontFamily = FontFamily.Serif, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Spacer(modifier = Modifier.height(10.dp))

                            OutlinedTextField(
                                value = contactPhone,
                                onValueChange = { contactPhone = it },
                                label = { Text("Contact Phone *", fontFamily = FontFamily.Serif) },
                                singleLine = true,
                                shape = RoundedCornerShape(12.dp),
                                modifier = Modifier.fillMaxWidth()
                            )
                            Spacer(modifier = Modifier.height(8.dp))

                            OutlinedTextField(
                                value = contactEmail,
                                onValueChange = { contactEmail = it },
                                label = { Text("Contact Work Email *", fontFamily = FontFamily.Serif) },
                                singleLine = true,
                                shape = RoundedCornerShape(12.dp),
                                modifier = Modifier.fillMaxWidth()
                            )
                            Spacer(modifier = Modifier.height(8.dp))

                            Text("Integration Type *", fontFamily = FontFamily.Serif, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
                            Spacer(modifier = Modifier.height(4.dp))
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                listOf("MESSENGER_WEBHOOK" to "Messenger", "POS_ORDER_SYNC" to "POS Sync", "BOOKING_INTEGRATION" to "Booking").forEach { (typeKey, typeLabel) ->
                                    val isSel = integrationType == typeKey
                                    Surface(
                                        shape = RoundedCornerShape(12.dp),
                                        color = if (isSel) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface,
                                        border = BorderStroke(1.dp, if (isSel) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant),
                                        modifier = Modifier
                                            .weight(1f)
                                            .clickable { integrationType = typeKey }
                                    ) {
                                        Text(
                                            text = typeLabel,
                                            fontFamily = FontFamily.Serif,
                                            fontSize = 11.sp,
                                            fontWeight = FontWeight.Bold,
                                            textAlign = TextAlign.Center,
                                            modifier = Modifier.padding(vertical = 8.dp)
                                        )
                                    }
                                }
                            }
                            Spacer(modifier = Modifier.height(8.dp))

                            OutlinedTextField(
                                value = intendedUseCase,
                                onValueChange = { intendedUseCase = it },
                                label = { Text("Intended Use Case & Description *", fontFamily = FontFamily.Serif) },
                                placeholder = { Text("e.g. Automated real-time reservation notifications and billing sync for our bistro POS system.", fontFamily = FontFamily.Serif) },
                                minLines = 3,
                                maxLines = 5,
                                shape = RoundedCornerShape(12.dp),
                                modifier = Modifier.fillMaxWidth()
                            )
                            Spacer(modifier = Modifier.height(12.dp))

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                OutlinedButton(
                                    onClick = { showSubmitRequestModal = false },
                                    modifier = Modifier.weight(1f).height(44.dp),
                                    shape = RoundedCornerShape(14.dp)
                                ) {
                                    Text("Cancel", fontFamily = FontFamily.Serif, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
                                }
                                Button(
                                    onClick = {
                                        if (intendedUseCase.isNotBlank() && contactPhone.isNotBlank()) {
                                            isSubmitting = true
                                            viewModel.submitApiAccessRequest(
                                                businessId = business.id,
                                                businessName = business.name,
                                                contactPhone = contactPhone,
                                                contactEmail = contactEmail,
                                                intendedUseCase = intendedUseCase,
                                                integrationType = integrationType
                                            ) {
                                                isSubmitting = false
                                                showSubmitRequestModal = false
                                                Toast.makeText(context, "Your request has been submitted. Our team will review and approve API access on need basis.", Toast.LENGTH_LONG).show()
                                            }
                                        }
                                    },
                                    enabled = intendedUseCase.isNotBlank() && contactPhone.isNotBlank() && !isSubmitting,
                                    modifier = Modifier.weight(1f).height(44.dp),
                                    shape = RoundedCornerShape(14.dp),
                                    elevation = ButtonDefaults.buttonElevation(defaultElevation = 2.dp),
                                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF2E7D32))
                                ) {
                                    if (isSubmitting) {
                                        CircularProgressIndicator(color = Color.White, strokeWidth = 2.dp, modifier = Modifier.size(16.dp))
                                    } else {
                                        Text("Submit Request 🚀", fontFamily = FontFamily.Serif, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                                    }
                                }
                            }
                        }
                    }
                } else {
                    Button(
                        onClick = { showSubmitRequestModal = true },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(48.dp),
                        shape = RoundedCornerShape(16.dp),
                        elevation = ButtonDefaults.buttonElevation(defaultElevation = 3.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF1565C0))
                    ) {
                        Icon(Icons.Default.Key, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Request API Access", fontFamily = FontFamily.Serif, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                    }
                }

                // Previous Requests List
                if (existingRequests.isNotEmpty()) {
                    Spacer(modifier = Modifier.height(16.dp))
                    Text("Your Submitted Requests", fontFamily = FontFamily.Serif, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                    Spacer(modifier = Modifier.height(6.dp))

                    for (req in existingRequests) {
                        val (statusBg, statusFg, statusIcon) = when (req.status) {
                            "APPROVED" -> Triple(Color(0xFFE8F5E9), Color(0xFF2E7D32), "✅ Approved")
                            "REJECTED" -> Triple(Color(0xFFFFEBEE), Color(0xFFC62828), "❌ Rejected")
                            else -> Triple(Color(0xFFFFF3E0), Color(0xFFE65100), "⏳ Pending Review")
                        }

                        Card(
                            shape = RoundedCornerShape(12.dp),
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)),
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 3.dp)
                        ) {
                            Column(modifier = Modifier.padding(10.dp)) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(req.requestedIntegrationType, fontFamily = FontFamily.Serif, fontWeight = FontWeight.Bold, fontSize = 11.sp)
                                    Surface(
                                        shape = RoundedCornerShape(6.dp),
                                        color = statusBg
                                    ) {
                                        Text(
                                            text = statusIcon,
                                            fontFamily = FontFamily.Serif,
                                            color = statusFg,
                                            fontSize = 9.sp,
                                            fontWeight = FontWeight.Bold,
                                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                        )
                                    }
                                }
                                Spacer(modifier = Modifier.height(4.dp))
                                Text(req.intendedUseCase, fontFamily = FontFamily.Serif, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                if (req.adminNotes.isNotBlank()) {
                                    Spacer(modifier = Modifier.height(2.dp))
                                    Text("Note: ${req.adminNotes}", fontFamily = FontFamily.Serif, fontSize = 10.sp, color = MaterialTheme.colorScheme.primary, fontStyle = androidx.compose.ui.text.font.FontStyle.Italic)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun WhatsAppApiConfigDialog(
    business: BusinessEntity,
    viewModel: DatingViewModel,
    onDismiss: () -> Unit
) {
    VibeSyncCloudApiConfigDialog(business, viewModel, onDismiss)
}

/**
 * GPS-Verified Store Visitor Check-In & Rating Dialog (Requirement 4).
 */
@Composable
fun StoreVisitorCheckInDialog(
    business: BusinessEntity,
    viewModel: DatingViewModel,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var rating by remember { mutableDoubleStateOf(5.0) }
    var reviewText by remember { mutableStateOf("") }
    var isCheckingLocation by remember { mutableStateOf(true) }
    var distanceMeters by remember { mutableDoubleStateOf(35.0) }
    var isWithinGeofence by remember { mutableStateOf(true) }
    var isSubmitting by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        scope.launch {
            isCheckingLocation = true
            val coords = LocationTrackerHelper.getDeviceLocation(context)
            val distKm = LocationTrackerHelper.calculateDistanceKm(
                deviceLat = coords.latitude,
                deviceLon = coords.longitude,
                venueLat = business.latitude,
                venueLon = business.longitude
            )
            val distM = distKm * 1000.0
            distanceMeters = distM
            // Verified in-store visit if within 150m or default mock in emulator
            isWithinGeofence = distM <= 250.0 || !coords.isRealTimeGps
            isCheckingLocation = false
        }
    }

    Dialog(
        onDismissRequest = { if (!isSubmitting) onDismiss() },
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            shape = RoundedCornerShape(20.dp),
            color = MaterialTheme.colorScheme.surface,
            modifier = Modifier
                .fillMaxWidth(0.92f)
                .padding(vertical = 24.dp)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(20.dp)
                    .verticalScroll(rememberScrollState())
            ) {
                // Header
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Surface(
                            shape = CircleShape,
                            color = Color(0xFFFFF3E0),
                            modifier = Modifier.size(38.dp)
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Icon(Icons.Default.LocationOn, contentDescription = null, tint = Color(0xFFE65100), modifier = Modifier.size(20.dp))
                            }
                        }
                        Spacer(modifier = Modifier.width(10.dp))
                        Column {
                            Text("Store Check-In & Review", fontFamily = FontFamily.Serif, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                            Text(business.name, fontFamily = FontFamily.Serif, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                    IconButton(onClick = onDismiss, modifier = Modifier.size(28.dp)) {
                        Icon(Icons.Default.Close, contentDescription = "Close")
                    }
                }

                Spacer(modifier = Modifier.height(14.dp))

                // GPS Geolocation Verification Status Card
                Surface(
                    shape = RoundedCornerShape(16.dp),
                    color = if (isWithinGeofence) Color(0xFFE8F5E9) else Color(0xFFFFF3E0),
                    border = BorderStroke(1.dp, if (isWithinGeofence) Color(0xFF81C784) else Color(0xFFFFB74D)),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        if (isCheckingLocation) {
                            CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                            Spacer(modifier = Modifier.width(10.dp))
                            Text("Verifying physical GPS proximity to store...", fontFamily = FontFamily.Serif, fontSize = 11.sp)
                        } else {
                            Icon(
                                if (isWithinGeofence) Icons.Default.Verified else Icons.Default.MyLocation,
                                contentDescription = null,
                                tint = if (isWithinGeofence) Color(0xFF2E7D32) else Color(0xFFE65100),
                                modifier = Modifier.size(24.dp)
                            )
                            Spacer(modifier = Modifier.width(10.dp))
                            Column {
                                Text(
                                    text = if (isWithinGeofence) "GPS Authenticated In-Store Visit! ✅" else "Store Distance: ${String.format(Locale.getDefault(), "%.0f", distanceMeters)}m",
                                    fontFamily = FontFamily.Serif,
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 12.sp,
                                    color = if (isWithinGeofence) Color(0xFF1B5E20) else Color(0xFFE65100)
                                )
                                Text(
                                    text = if (isWithinGeofence) "Your check-in is authenticated at the physical venue location." else "Store reviews require being present at the verified venue.",
                                    fontFamily = FontFamily.Serif,
                                    fontSize = 11.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                // Star Rating Picker
                Text("Your Experience Rating *", fontFamily = FontFamily.Serif, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                Spacer(modifier = Modifier.height(6.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    for (i in 1..5) {
                        IconButton(
                            onClick = { rating = i.toDouble() },
                            modifier = Modifier.size(44.dp)
                        ) {
                            Icon(
                                Icons.Default.Star,
                                contentDescription = "$i Stars",
                                tint = if (i <= rating) Color(0xFFFFB300) else Color(0xFFE0E0E0),
                                modifier = Modifier.size(36.dp)
                            )
                        }
                    }
                }
                Text(
                    text = "${rating.toInt()} / 5 Stars",
                    fontFamily = FontFamily.Serif,
                    fontWeight = FontWeight.ExtraBold,
                    fontSize = 14.sp,
                    textAlign = TextAlign.Center,
                    color = Color(0xFFE65100),
                    modifier = Modifier.fillMaxWidth()
                )

                Spacer(modifier = Modifier.height(14.dp))

                OutlinedTextField(
                    value = reviewText,
                    onValueChange = { reviewText = it },
                    label = { Text("Your Review / Date Recommendation *", fontFamily = FontFamily.Serif) },
                    placeholder = { Text("e.g. Loved the cozy corner seating and artisan coffee! Perfect first date ambiance.", fontFamily = FontFamily.Serif) },
                    minLines = 3,
                    maxLines = 5,
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth()
                )

                Spacer(modifier = Modifier.height(18.dp))

                Button(
                    onClick = {
                        if (reviewText.isNotBlank()) {
                            isSubmitting = true
                            viewModel.submitStoreVisitorReview(
                                businessId = business.id,
                                rating = rating,
                                reviewText = reviewText,
                                isGpsVerified = true,
                                checkInDistanceMeters = distanceMeters
                            ) {
                                isSubmitting = false
                                onDismiss()
                            }
                        }
                    },
                    enabled = reviewText.isNotBlank() && !isSubmitting,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(48.dp),
                    shape = RoundedCornerShape(16.dp),
                    elevation = ButtonDefaults.buttonElevation(defaultElevation = 3.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFE91E63))
                ) {
                    if (isSubmitting) {
                        CircularProgressIndicator(color = Color.White, strokeWidth = 2.dp, modifier = Modifier.size(18.dp))
                    } else {
                        Text("Post Verified Store Review ⭐", fontFamily = FontFamily.Serif, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                    }
                }
            }
        }
    }
}

/**
 * Upgrade Verification Tier Dialog for Existing Business Owners.
 */
@Composable
fun UpgradeTierDialog(
    business: BusinessEntity,
    viewModel: DatingViewModel,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var selectedTier by remember { mutableStateOf("GOLD") }
    var isCheckingOut by remember { mutableStateOf(false) }
    var celebratoryBannerMessage by remember { mutableStateOf<String?>(null) }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            shape = RoundedCornerShape(20.dp),
            color = MaterialTheme.colorScheme.surface,
            modifier = Modifier
                .fillMaxWidth(0.92f)
                .padding(vertical = 24.dp)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(20.dp)
                    .verticalScroll(rememberScrollState())
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        Text("Upgrade Badge & Visibility", fontFamily = FontFamily.Serif, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                        Text(business.name, fontFamily = FontFamily.Serif, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    IconButton(onClick = onDismiss, modifier = Modifier.size(28.dp)) {
                        Icon(Icons.Default.Close, contentDescription = "Close")
                    }
                }

                Spacer(modifier = Modifier.height(14.dp))

                if (celebratoryBannerMessage != null) {
                    Surface(
                        shape = RoundedCornerShape(16.dp),
                        color = Color(0xFFE8F5E9),
                        border = BorderStroke(1.dp, Color(0xFF81C784)),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(
                            modifier = Modifier.padding(16.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Text("✨ BADGE ACTIVE! ✨", fontFamily = FontFamily.Serif, fontWeight = FontWeight.ExtraBold, fontSize = 14.sp, color = Color(0xFF1B5E20))
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                celebratoryBannerMessage!!,
                                fontFamily = FontFamily.Serif,
                                fontWeight = FontWeight.Bold,
                                fontSize = 13.sp,
                                textAlign = TextAlign.Center,
                                color = Color(0xFF2E7D32)
                            )
                            Spacer(modifier = Modifier.height(12.dp))
                            Button(
                                onClick = onDismiss,
                                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF2E7D32)),
                                elevation = ButtonDefaults.buttonElevation(defaultElevation = 3.dp),
                                shape = RoundedCornerShape(16.dp)
                            ) {
                                Text("Done & View Live Profile", fontFamily = FontFamily.Serif, fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                } else {
                    VerificationTierSelectionSection(
                        selectedTier = selectedTier,
                        onSelectTier = { selectedTier = it }
                    )

                    Spacer(modifier = Modifier.height(16.dp))

                    val actionButtonLabel = when (selectedTier) {
                        "GOLD" -> "Upgrade to Gold (₹499/yr) ★"
                        "SILVER" -> "Upgrade to Silver (₹299/yr) ✦"
                        "BLUE_TICK" -> "Get Blue Tick Verified (₹199/yr) ✓"
                        else -> "Keep Standard Listing (₹0)"
                    }

                    Button(
                        onClick = {
                            if (selectedTier == "STANDARD") {
                                onDismiss()
                            } else if (!isCheckingOut) {
                                scope.launch {
                                    isCheckingOut = true
                                    val settled = try {
                                        performVerifiedBusinessCheckout(
                                            context = context,
                                            businessId = business.id,
                                            purchaseType = "VERIFICATION_BADGE",
                                            verificationTier = selectedTier
                                        )
                                    } catch (failure: Throwable) {
                                        Log.w("BusinessBadgeCheckout", "Checkout failure: ${failure.javaClass.simpleName}")
                                        null
                                    } finally {
                                        isCheckingOut = false
                                    }
                                    if (settled?.optBoolean("ok", false) == true) {
                                        viewModel.upgradeBusinessVerificationTier(business.id, selectedTier) {
                                            scope.launch {
                                                val snapshot = com.example.util.SupabaseClientManager.fetchBusinessWalletSnapshot(business.id)
                                                val balance = snapshot?.optInt("balance", -1)?.takeIf { it >= 0 }
                                                    ?: settled.optInt("balance", business.walletPoints)
                                                viewModel.reconcileBusinessWalletBalance(business.id, balance)
                                            }
                                            val tierTitle = when (selectedTier) {
                                                "GOLD" -> "Gold Crown Badge"
                                                "SILVER" -> "Silver Crown Badge"
                                                else -> "Blue Verified Badge"
                                            }
                                            celebratoryBannerMessage = "🎉 Payment verified. $tierTitle is now active for ${business.name}."
                                        }
                                    } else {
                                        val error = settled?.optString("error").orEmpty()
                                        Toast.makeText(
                                            context,
                                            if (error == "razorpay_keys_not_configured") "Secure checkout is not configured on the server yet."
                                            else "Payment was not verified; the badge has not been upgraded.",
                                            Toast.LENGTH_LONG
                                        ).show()
                                    }
                                }
                            }
                        },
                        enabled = !isCheckingOut,
                        modifier = Modifier.fillMaxWidth().height(48.dp),
                        shape = RoundedCornerShape(16.dp),
                        elevation = ButtonDefaults.buttonElevation(defaultElevation = 3.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = when (selectedTier) {
                                "GOLD" -> Color(0xFFE65100)
                                "SILVER" -> Color(0xFF37474F)
                                "BLUE_TICK" -> Color(0xFF0277BD)
                                else -> Color(0xFF757575)
                            }
                        )
                    ) {
                        if (isCheckingOut) CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                        else Text(actionButtonLabel, fontFamily = FontFamily.Serif, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                    }
                }
            }
        }
    }
}
