package com.example.ui.components

import android.graphics.Bitmap
import android.graphics.drawable.Drawable
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageShader
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.graphics.drawable.DrawableCompat
import com.example.ui.model.ChatWallpaperTheme
import com.example.ui.model.ChatWallpaperUiState

data class ChatWallpaperStyle(
    val backgroundColor: Color,
    val doodleBrush: Brush
)

/**
 * Builds one tinted bitmap shader per selected theme/mode. The shader repeats along both axes;
 * vector XML stays in the APK, so scrolling creates no network requests and no layout remeasure loop.
 */
@Composable
fun rememberChatWallpaperStyle(state: ChatWallpaperUiState): ChatWallpaperStyle {
    val context = LocalContext.current
    val lineColor = if (state.isDarkMode) Color(0xFF222E35) else Color(0xFFD1D7DB)
    val backgroundColor = if (state.isDarkMode) Color(0xFF0B141A) else Color(0xFFF0F2F5)
    val bitmap = remember(context, state.theme, state.isDarkMode) {
        val drawable: Drawable? = context.getDrawable(state.theme.drawableRes)?.mutate()
        if (drawable == null) {
            Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888)
        } else {
            DrawableCompat.setTint(drawable, android.graphics.Color.argb(
                (lineColor.alpha * 255f).toInt().coerceIn(0, 255),
                (lineColor.red * 255f).toInt().coerceIn(0, 255),
                (lineColor.green * 255f).toInt().coerceIn(0, 255),
                (lineColor.blue * 255f).toInt().coerceIn(0, 255)
            ))
            val width = drawable.intrinsicWidth.coerceIn(64, 1024)
            val height = drawable.intrinsicHeight.coerceIn(64, 1024)
            Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).also { tile ->
                drawable.setBounds(0, 0, width, height)
                drawable.draw(android.graphics.Canvas(tile))
            }
        }
    }
    val imageBitmap = remember(bitmap) { bitmap.asImageBitmap() }
    val brush = remember(imageBitmap) {
        object : ShaderBrush() {
            override fun createShader(size: androidx.compose.ui.geometry.Size): androidx.compose.ui.graphics.Shader =
                ImageShader(imageBitmap, TileMode.Repeated, TileMode.Repeated)
        }
    }
    return ChatWallpaperStyle(backgroundColor = backgroundColor, doodleBrush = brush)
}

@Composable
fun ChatWallpaperPickerDialog(
    state: ChatWallpaperUiState,
    onDismiss: () -> Unit,
    onApply: (ChatWallpaperUiState) -> Unit
) {
    var selectedTheme by remember(state) { mutableStateOf(state.theme) }
    var darkMode by remember(state) { mutableStateOf(state.isDarkMode) }
    val preview = rememberChatWallpaperStyle(ChatWallpaperUiState(selectedTheme, darkMode))

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text("Chat wallpaper", style = MaterialTheme.typography.titleLarge)
                Text(
                    "Local Bangalore doodles · no network storage",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(105.dp)
                        .clip(RoundedCornerShape(14.dp))
                        .background(preview.backgroundColor)
                        .background(preview.doodleBrush)
                )
                ChatWallpaperTheme.entries.forEach { theme ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .background(if (selectedTheme == theme) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent)
                            .padding(horizontal = 8.dp, vertical = 5.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        RadioButton(selected = selectedTheme == theme, onClick = { selectedTheme = theme })
                        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(1.dp)) {
                            Text(theme.title, style = MaterialTheme.typography.bodyMedium)
                            Text(theme.subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f)
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 7.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("Dark mode", style = MaterialTheme.typography.bodyMedium)
                            Text("Deep teal-black · #0B141A", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Switch(checked = darkMode, onCheckedChange = { darkMode = it })
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onApply(ChatWallpaperUiState(selectedTheme, darkMode)) }) { Text("Apply wallpaper") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}
