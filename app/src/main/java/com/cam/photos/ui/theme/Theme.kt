package com.cam.photos.ui.theme

import android.os.Build
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

/**
 * "Liquid Glass" look-alike for the iOS Photos style UI.
 * Real backdrop blur needs Android 12+ (RenderEffect); below that we fall
 * back to a translucent tinted scrim so it still reads as "glass" without
 * tanking frame rate on older devices.
 */

val GlassTint = Color(0x33FFFFFF)
val GlassBorder = Color(0x55FFFFFF)

val PhotosDarkColorScheme = darkColorScheme(
    primary = Color(0xFF0A84FF), // iOS system blue
    background = Color.Black,
    surface = Color(0xFF1C1C1E),
    onBackground = Color.White,
    onSurface = Color.White
)

@Composable
fun GlassSurface(
    modifier: Modifier = Modifier,
    cornerRadius: Int = 24,
    content: @Composable () -> Unit
) {
    val shape = RoundedCornerShape(cornerRadius.dp)
    val supportsBlur = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S

    Box(
        modifier = modifier
            .then(
                if (supportsBlur) Modifier.blur(20.dp) else Modifier
            )
            .background(
                brush = Brush.verticalGradient(
                    listOf(GlassTint, GlassTint.copy(alpha = 0.15f))
                ),
                shape = shape
            )
    ) {
        content()
    }
}

@Composable
fun PhotosAppTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = PhotosDarkColorScheme,
        content = content
    )
}
