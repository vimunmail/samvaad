package com.example.xgestalkble.ui.main

import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.xgestalkble.R
import com.example.xgestalkble.theme.*
import kotlinx.coroutines.delay

@Composable
fun BootScreen(onBootComplete: () -> Unit) {
    // 1. One-time boot sequence duration & status text updates
    var statusText by remember { mutableStateOf("Initializing system...") }
    
    LaunchedEffect(Unit) {
        delay(600)
        statusText = "Loading offline AI gesture models..."
        delay(800)
        statusText = "Calibrating smart glove sensors..."
        delay(700)
        statusText = "Synchronizing BLE communication protocol..."
        delay(600)
        statusText = "System ready!"
        delay(300)
        onBootComplete()
    }

    // 2. Infinite transitions for pulsing/glowing animations
    val infiniteTransition = rememberInfiniteTransition(label = "BootAnimations")
    
    // Pulsing logo scale
    val logoScale by infiniteTransition.animateFloat(
        initialValue = 0.92f,
        targetValue = 1.08f,
        animationSpec = infiniteRepeatable(
            animation = tween(1200, easing = EaseInOutSine),
            repeatMode = RepeatMode.Reverse
        ),
        label = "LogoScale"
    )

    // Pulsing background glow opacity
    val glowAlpha by infiniteTransition.animateFloat(
        initialValue = 0.15f,
        targetValue = 0.45f,
        animationSpec = infiniteRepeatable(
            animation = tween(1200, easing = EaseInOutSine),
            repeatMode = RepeatMode.Reverse
        ),
        label = "GlowAlpha"
    )

    // Rotating outer tech ring angle
    val rotationAngle by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(4000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "RingRotation"
    )

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                brush = Brush.verticalGradient(
                    colors = listOf(ObsidianBg, MidnightBg)
                )
            ),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
            modifier = Modifier.padding(24.dp)
        ) {
            // Logo Container with glow and rotating ring
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier.size(220.dp)
            ) {
                // 1. Ambient Circular Glow
                Box(
                    modifier = Modifier
                        .size(160.dp)
                        .alpha(glowAlpha)
                        .clip(CircleShape)
                        .background(
                            Brush.radialGradient(
                                colors = listOf(ElectricCyan, Color.Transparent)
                            )
                        )
                )

                // 2. Tech Rotating Ring
                Canvas(
                    modifier = Modifier
                        .size(190.dp)
                        .graphicsLayer { rotationZ = rotationAngle }
                ) {
                    val strokeWidth = 3.dp.toPx()
                    drawArc(
                        color = ElectricCyan.copy(alpha = 0.6f),
                        startAngle = 0f,
                        sweepAngle = 90f,
                        useCenter = false,
                        style = Stroke(
                            width = strokeWidth,
                            pathEffect = PathEffect.dashPathEffect(
                                floatArrayOf(20f, 10f), 0f
                            )
                        )
                    )
                    drawArc(
                        color = SkyBlue.copy(alpha = 0.6f),
                        startAngle = 120f,
                        sweepAngle = 90f,
                        useCenter = false,
                        style = Stroke(
                            width = strokeWidth,
                            pathEffect = PathEffect.dashPathEffect(
                                floatArrayOf(20f, 10f), 0f
                            )
                        )
                    )
                    drawArc(
                        color = VioletAccent.copy(alpha = 0.4f),
                        startAngle = 240f,
                        sweepAngle = 90f,
                        useCenter = false,
                        style = Stroke(
                            width = strokeWidth,
                            pathEffect = PathEffect.dashPathEffect(
                                floatArrayOf(20f, 10f), 0f
                            )
                        )
                    )
                }

                // 3. The main Samvaad logo
                Box(
                    modifier = Modifier
                        .size(130.dp)
                        .scale(logoScale)
                        .clayShadow(
                            shape = CircleShape,
                            dark = ClayShadow,
                            light = ClayHighlight,
                            offset = 5.dp,
                            blur = 12.dp
                        )
                        .clip(CircleShape)
                        .background(CardBg)
                        .padding(7.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Image(
                        painter = painterResource(id = R.drawable.samvaad_logo),
                        contentDescription = "Samvaad Logo",
                        contentScale = ContentScale.Fit,
                        modifier = Modifier.fillMaxSize()
                    )
                }
            }

            Spacer(modifier = Modifier.height(32.dp))

            // Brand Typography
            Text(
                text = "Samvaad",
                style = MaterialTheme.typography.headlineLarge,
                fontWeight = FontWeight.Black,
                color = DarkSlateText,
                letterSpacing = (-0.5).sp
            )
            
            Spacer(modifier = Modifier.height(48.dp))

            // Animated Fading Loading Status Text
            Text(
                text = statusText,
                style = MaterialTheme.typography.bodyMedium,
                color = SlateText,
                fontWeight = FontWeight.Medium,
                modifier = Modifier.alpha(0.8f)
            )

            Spacer(modifier = Modifier.height(16.dp))

            // Sleek animated progress bar
            Box(
                modifier = Modifier
                    .width(180.dp)
                    .height(4.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(SlateText.copy(alpha = 0.2f))
            ) {
                // Determine width fraction based on status text phase
                val progressFraction = when (statusText) {
                    "Initializing system..." -> 0.15f
                    "Loading offline AI gesture models..." -> 0.45f
                    "Calibrating smart glove sensors..." -> 0.70f
                    "Synchronizing BLE communication protocol..." -> 0.90f
                    "System ready!" -> 1.0f
                    else -> 0.0f
                }
                
                val animatedProgressWidth by animateFloatAsState(
                    targetValue = progressFraction,
                    animationSpec = tween(500, easing = LinearOutSlowInEasing),
                    label = "ProgressBarWidth"
                )

                Box(
                    modifier = Modifier
                        .fillMaxHeight()
                        .fillMaxWidth(animatedProgressWidth)
                        .background(
                            Brush.horizontalGradient(
                                colors = listOf(ElectricCyan, VioletAccent)
                            )
                        )
                )
            }
        }
    }
}
