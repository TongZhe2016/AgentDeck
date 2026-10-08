package com.worldcopy.agentdeck.feature.workspace

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.worldcopy.agentdeck.ui.theme.DeckWarningRed
import com.worldcopy.agentdeck.ui.theme.DeckWarningYellow

/** The fully selected permission track; Slider retains its native thumb and input handling. */
@Composable
internal fun FullAccessTrack(steps: Int) {
    // Compose follows the system animator duration scale, including zero.
    val transition = rememberInfiniteTransition(label = "full-access-warning")
    val phase = transition.animateFloat(0f, 1f,
        animationSpec = infiniteRepeatable(tween(1800, easing = LinearEasing), RepeatMode.Restart), label = "warning-stripes")
    Canvas(Modifier.fillMaxWidth().padding(end = 6.dp).height(16.dp)
        .clip(RoundedCornerShape(topStart = 8.dp, bottomStart = 8.dp, topEnd = 2.dp, bottomEnd = 2.dp))
        .testTag("full-access-track")) {
        drawRect(DeckWarningYellow)
        val band = 18.dp.toPx()
        val period = band * 2
        var x = -period + phase.value * period
        while (x < size.width + size.height) {
            val stripe = Path().apply {
                moveTo(x, 0f); lineTo(x + band, 0f)
                lineTo(x + band - size.height, size.height); lineTo(x - size.height, size.height); close()
            }
            drawPath(stripe, DeckWarningRed)
            x += period
        }
        val inset = size.height / 2
        repeat(steps + 1) { index ->
            val point = Offset(inset + (size.width - inset * 2) * index / (steps + 1), center.y)
            drawCircle(Color.White, 2.5.dp.toPx(), point)
            drawCircle(DeckWarningRed, 1.5.dp.toPx(), point)
        }
    }
}
