package com.example.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.LocalOffer
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.Store
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.example.data.model.BusinessEntity
import com.example.data.model.tierEnum
import kotlin.math.roundToInt

private val BusinessObsidian = androidx.compose.ui.graphics.Color(0xFF111214)
private val BusinessSapphire = androidx.compose.ui.graphics.Color(0xFF2563EB)

@Composable
fun BusinessCard(
    business: BusinessEntity,
    modifier: Modifier = Modifier,
    onFollowClick: () -> Unit,
    onViewProfileClick: () -> Unit,
    onSwipePass: (() -> Unit)? = null,
    onSwipeFollow: (() -> Unit)? = null,
    isSwipable: Boolean = true
) {
    var offsetX by remember { mutableFloatStateOf(0f) }
    val animatedOffsetX by androidx.compose.animation.core.animateFloatAsState(
        targetValue = offsetX,
        label = "business_card_offset"
    )
    val rotationAngle = (animatedOffsetX / 40f).coerceIn(-12f, 12f)

    Card(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 400.dp)
            .testTag("business_card_${business.id}")
            .offset { IntOffset(animatedOffsetX.roundToInt(), 0) }
            .rotate(rotationAngle)
            .then(
                if (isSwipable) Modifier.pointerInput(business.id) {
                    detectHorizontalDragGestures(
                        onDragEnd = {
                            if (offsetX > 250f) onSwipeFollow?.invoke() ?: onFollowClick()
                            else if (offsetX < -250f) onSwipePass?.invoke()
                            offsetX = 0f
                        },
                        onHorizontalDrag = { _, dragAmount ->
                            offsetX = (offsetX + dragAmount).coerceIn(-500f, 500f)
                        }
                    )
                } else Modifier
            ),
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(containerColor = BusinessObsidian),
        elevation = CardDefaults.cardElevation(defaultElevation = 5.dp)
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            // Photo only: no text, gradients, rank, rating or badges over the venue image.
            AsyncImage(
                model = ImageRequest.Builder(LocalContext.current)
                    .data(
                        business.bannerUrl.ifBlank {
                            "https://images.unsplash.com/photo-1517248135467-4c7edcad34c4?auto=format&fit=crop&w=1000&q=80"
                        }
                    )
                    .crossfade(true)
                    .build(),
                contentDescription = "${business.name} venue photo",
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(190.dp)
                    .clickable { onViewProfileClick() }
            )

            Column(
                modifier = Modifier.fillMaxWidth().padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(9.dp)
            ) {
                if (business.isCuratedLandmark) {
                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = androidx.compose.ui.graphics.Color(0xFFDCFCE7),
                        modifier = Modifier.testTag("badge_curated_landmark")
                    ) {
                        Text(
                            text = "📍 FREE PUBLIC LANDMARK · ${business.landmarkCategory.replace('_', ' ')}",
                            color = androidx.compose.ui.graphics.Color(0xFF166534),
                            fontSize = 10.sp,
                            fontWeight = FontWeight.ExtraBold,
                            modifier = Modifier.padding(horizontal = 9.dp, vertical = 5.dp)
                        )
                    }
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.Top
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = business.name,
                            color = androidx.compose.ui.graphics.Color.White,
                            fontSize = 21.sp,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            text = listOf(business.category, business.city).filter { it.isNotBlank() }.joinToString(" • "),
                            color = androidx.compose.ui.graphics.Color(0xFFB9C4D5),
                            fontSize = 12.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    Text(text = business.logoEmoji, fontSize = 23.sp, modifier = Modifier.padding(start = 8.dp))
                }

                if (business.tagline.isNotBlank()) {
                    Text(
                        text = business.tagline,
                        color = androidx.compose.ui.graphics.Color(0xFFD0D6E0),
                        fontSize = 13.sp,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(14.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Star, contentDescription = "Rating", tint = androidx.compose.ui.graphics.Color(0xFFFBBF24), modifier = Modifier.size(17.dp))
                        Spacer(Modifier.width(4.dp))
                        Text(
                            text = "${String.format(java.util.Locale.ROOT, "%.1f", business.rating)} • ${business.reviewCount} reviews",
                            color = androidx.compose.ui.graphics.Color.White,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.LocationOn, contentDescription = "Distance", tint = BusinessSapphire, modifier = Modifier.size(17.dp))
                        Spacer(Modifier.width(4.dp))
                        Text(
                            text = "${String.format(java.util.Locale.ROOT, "%.1f", business.distanceKm)} km away",
                            color = androidx.compose.ui.graphics.Color(0xFFD0D6E0),
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Medium
                        )
                    }
                }

                Text(
                    text = "${business.followerCount} followers" + if (business.priceRange.isNotBlank()) " • ${business.priceRange}" else "",
                    color = androidx.compose.ui.graphics.Color(0xFF9CA8B9),
                    fontSize = 11.sp
                )

                if (business.activeOfferSummary.isNotBlank()) {
                    Surface(
                        modifier = Modifier.fillMaxWidth().clickable { onViewProfileClick() },
                        color = androidx.compose.ui.graphics.Color(0xFF17243D),
                        contentColor = androidx.compose.ui.graphics.Color.White,
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Row(modifier = Modifier.padding(horizontal = 12.dp, vertical = 9.dp), verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.LocalOffer, contentDescription = "Current offer", tint = BusinessSapphire, modifier = Modifier.size(17.dp))
                            Spacer(Modifier.width(8.dp))
                            Text(
                                text = business.activeOfferSummary,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.SemiBold,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Button(
                        onClick = onFollowClick,
                        modifier = Modifier.weight(1f).height(44.dp).testTag("follow_business_${business.id}"),
                        colors = ButtonDefaults.buttonColors(containerColor = if (business.isFollowed) androidx.compose.ui.graphics.Color(0xFF1D4ED8) else BusinessSapphire),
                        shape = RoundedCornerShape(12.dp),
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp)
                    ) {
                        Icon(if (business.isFollowed) Icons.Default.Check else Icons.Default.Bookmark, contentDescription = null, modifier = Modifier.size(17.dp))
                        Spacer(Modifier.width(6.dp))
                        Text(if (business.isFollowed) "Following" else "Follow", fontWeight = FontWeight.Bold, fontSize = 12.sp, maxLines = 1)
                    }
                    OutlinedButton(
                        onClick = onViewProfileClick,
                        modifier = Modifier.weight(1.2f).height(44.dp).testTag("view_business_timeline_${business.id}"),
                        border = androidx.compose.foundation.BorderStroke(1.dp, BusinessSapphire),
                        shape = RoundedCornerShape(12.dp),
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 8.dp)
                    ) {
                        Icon(Icons.Default.Store, contentDescription = null, tint = BusinessSapphire, modifier = Modifier.size(17.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Profile & offers", fontWeight = FontWeight.Bold, fontSize = 12.sp, color = androidx.compose.ui.graphics.Color.White, maxLines = 1)
                    }
                }

                Row(
                    modifier = Modifier.fillMaxWidth().testTag("partner_rank_footer"),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Partner Rank • ${business.tierEnum}",
                        color = androidx.compose.ui.graphics.Color(0xFF93B4FF),
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }
    }
}
