package com.example.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.ScrollableTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.util.EmojiCatalog

/** Shared searchable-in-spirit full Unicode-category emoji sheet used for message and story reactions. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FullEmojiBottomSheet(
    onDismiss: () -> Unit,
    onEmojiSelected: (String) -> Unit
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var selectedCategory by remember { mutableIntStateOf(0) }
    val categories = remember { EmojiCatalog.categories }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surface,
        dragHandle = { }
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().heightIn(min = 400.dp, max = 620.dp)
                .padding(bottom = 12.dp)
        ) {
            Text(
                "Choose an emoji",
                modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 14.dp, bottom = 4.dp),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold
            )
            ScrollableTabRow(
                selectedTabIndex = selectedCategory,
                edgePadding = 12.dp
            ) {
                categories.forEachIndexed { index, category ->
                    Tab(
                        selected = selectedCategory == index,
                        onClick = { selectedCategory = index },
                        text = { Text(category.title) }
                    )
                }
            }
            val emojis = categories[selectedCategory].emojis
            LazyVerticalGrid(
                columns = GridCells.Adaptive(minSize = 44.dp),
                modifier = Modifier.fillMaxWidth().weight(1f),
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                items(emojis, key = { it }) { emoji ->
                    Box(
                        modifier = Modifier.height(44.dp).clip(CircleShape)
                            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
                            .clickable { onEmojiSelected(emoji) },
                        contentAlignment = Alignment.Center
                    ) {
                        Text(emoji, fontSize = 25.sp)
                    }
                }
            }
        }
    }
}
