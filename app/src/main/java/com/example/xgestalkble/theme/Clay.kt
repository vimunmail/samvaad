package com.example.xgestalkble.theme

import androidx.compose.foundation.background
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.addOutline
import androidx.compose.ui.graphics.asAndroidPath
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

// ─────────────────────────────────────────────────────────────────────────────
// CLAYMORPHIC DESIGN TOKENS & MODIFIERS
// Centralizes the "bold clay" look so screens stay readable and consistent.
// Nothing here touches app logic — purely visual surface treatment.
// ─────────────────────────────────────────────────────────────────────────────

// Generous, pillowy corner radii — the signature of claymorphism.
val ClayRadiusXL = 30.dp
val ClayRadiusLarge = 26.dp
val ClayRadiusMedium = 20.dp
val ClayRadiusSmall = 16.dp
val ClayRadiusChip = 18.dp

val ClayShapeXL = RoundedCornerShape(ClayRadiusXL)
val ClayShapeLarge = RoundedCornerShape(ClayRadiusLarge)
val ClayShapeMedium = RoundedCornerShape(ClayRadiusMedium)
val ClayShapeSmall = RoundedCornerShape(ClayRadiusSmall)
val ClayShapeChip = RoundedCornerShape(ClayRadiusChip)

/**
 * Draws the dual offset clay shadow *behind* the node: a dark halo to the
 * bottom-right and a light highlight to the top-left. The fill is transparent so
 * only the blurred halos show; a real surface is painted on top by [clayCard].
 */
fun Modifier.clayShadow(
    shape: Shape,
    dark: Color,
    light: Color,
    offset: Dp = 7.dp,
    blur: Dp = 18.dp,
): Modifier = this.drawBehind {
    val outline = shape.createOutline(size, layoutDirection, this)
    val path = Path().apply { addOutline(outline) }
    drawIntoCanvas { canvas ->
        val paint = Paint()
        val frameworkPaint = paint.asFrameworkPaint()
        val o = offset.toPx()
        val b = blur.toPx().coerceAtLeast(0.1f)

        frameworkPaint.color = android.graphics.Color.TRANSPARENT
        // Dark halo, pushed bottom-right.
        frameworkPaint.setShadowLayer(b, o, o, dark.toArgb())
        canvas.nativeCanvas.drawPath(path.asAndroidPath(), frameworkPaint)
        // Light sheen, pushed top-left.
        frameworkPaint.setShadowLayer(b, -o, -o, light.toArgb())
        canvas.nativeCanvas.drawPath(path.asAndroidPath(), frameworkPaint)
        frameworkPaint.clearShadowLayer()
    }
}

/**
 * Full puffy clay surface: dual shadow + clipped rounded shape + a soft vertical
 * sheen so the top edge catches light like molded clay. Drop-in replacement for a
 * bordered [androidx.compose.material3.Card] container.
 */
@Composable
@ReadOnlyComposable
fun Modifier.clayCard(
    shape: Shape = ClayShapeLarge,
    color: Color = CardBg,
    elevated: Boolean = true,
): Modifier {
    val top = lerp(color, ClayHighlightTint(), if (ThemeConfig.isDarkTheme) 0.05f else 0.55f)
    val base = if (ThemeConfig.isDarkTheme) color else lerp(color, Color(0xFFF6F7FF), 0.18f)
    val sheen = Brush.verticalGradient(listOf(top, base))
    val shadowMod = if (elevated) {
        Modifier.clayShadow(shape, ClayShadow, ClayHighlight)
    } else {
        Modifier
    }
    return this
        .then(shadowMod)
        .clip(shape)
        .background(sheen)
}

/**
 * Recessed clay inset — for chips, tracks and pressed tiles. Reads as if pressed
 * into the surface rather than raised out of it.
 */
@Composable
@ReadOnlyComposable
fun Modifier.clayInset(
    shape: Shape = ClayShapeChip,
    color: Color = SubtleBg,
): Modifier {
    return this
        .clip(shape)
        .background(color)
}

// A near-white tint used for the top sheen of light-theme clay surfaces.
private fun ClayHighlightTint(): Color =
    if (ThemeConfig.isDarkTheme) Color(0xFFFFFFFF) else Color(0xFFFFFFFF)
