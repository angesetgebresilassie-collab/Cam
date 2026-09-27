package com.cam.photos.ui.theme

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color

/**
 * "Liquid Glass" look-alike for the iOS Photos style UI.
 *
 * IMPORTANT: this must only tint/shade what's BEHIND the content, never
 * blur the content itself. An earlier version applied Modifier.blur()
 * to this Box, which blurs everything drawn inside it — icons, labels,
 * all of it — turning legible UI into smudged blobs. True backdrop blur
 * (blurring only what's visually behind a surface) needs a dedicated
 * capture-and-blur pass; until that's added, a translucent tinted
 * gradient gives the "frosted glass" look without touching the content.
 */

val GlassTint = Color(0x40FFFFFF)
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
    val shape = RoundedCornerShape(cornerRadius.dp())

    Box(
        modifier = modifier.background(
            brush = Brush.verticalGradient(
                listOf(GlassTint, GlassTint.copy(alpha = 0.2f))
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

private fun Int.dp() = androidx.compose.ui.unit.Dp(this.toFloat())
