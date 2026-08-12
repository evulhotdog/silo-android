package org.siloserver.silo.tv.ui.screens.player

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * One D-pad quick-skip: which side ripples, the running total seconds for
 * this rapid-fire burst (repeats add up instead of restarting), plus a nonce
 * forcing a fresh replay.
 */
internal data class TvSeekVisualizerCue(
    val direction: Int,
    val nonce: Int,
    val accumulatedSeconds: Int,
)

// Also reused by TvPlayerScreen as the accumulated-burst reset window, so a
// quiet gap this long both hides the ripple and drops the running total.
internal const val SEEK_VISUALIZER_VISIBLE_MS = 1400L
// Must match the AnimatedVisibility exit fadeOut duration below.
private const val SEEK_VISUALIZER_EXIT_MS = 400L
private const val SEEK_CHEVRON_SLIDE_DURATION_MS = 1000
private const val SEEK_CHEVRON_WIDTH_DP = 28
private const val SEEK_CHEVRON_STROKE_WIDTH_DP = 6
// The chevron slides from this far inward out to its laid-out resting spot.
// Modifier.offset draws outside the parent's padding, so travel must run
// inward -> 0 and never past 0; otherwise the offset walks it off the edge
// no matter how much padding the Row has.
private const val SEEK_CHEVRON_SLIDE_DISTANCE_DP = 40f
// Per-press accent. Unlike the slide, this animates a value that starts and
// ends at rest, so re-firing it mid-flight has no discontinuity to cover and
// every press in a burst can safely replay it.
private const val SEEK_CHEVRON_PULSE_MS = 220
private const val SEEK_CHEVRON_PULSE_SCALE = 0.12f

// Fixed minimum so the digit count changing (e.g. "5s" -> "15s") doesn't
// reflow the row and shift the number; it just grows away from the chevron.
private const val SEEK_LABEL_MIN_WIDTH_DP = 72
// At and above a minute the raw second count stops being readable at a
// glance, so the total switches to m:ss. Minutes are left to grow rather
// than rolling into hours; a burst that long is not reachable in practice.
private const val SEEK_LABEL_CLOCK_THRESHOLD_SEC = 60
// Stroked text draws past the glyph advance that Compose measured and clips
// to, so the outermost keyline needs slack or it gets sliced.
private const val SEEK_LABEL_OUTLINE_BLEED_DP = 4

// Dark keyline behind each glyph, centered on its edge and NOT offset — half
// the width lands outside the glyph, half is covered by the fill on top, so
// it reads as a caption-style outline rather than a drop shadow.
private const val SEEK_OUTLINE_STROKE_SCALE = 1.5f
private const val SEEK_LABEL_OUTLINE_WIDTH_DP = 3
private val SEEK_OUTLINE_COLOR = Color.Black.copy(alpha = 0.25f)

/**
 * Side "ripple" for a discrete D-pad quick-skip: a tall, thin chevron slowly
 * slides fluidly on the seek's side with the burst's accumulated total
 * next to it. TvPlayerScreen only feeds this a cue for the quick-skip path.
 *
 * Pinned to physical left/right via a forced LTR provider rather than
 * Alignment.CenterStart/End.
 */
@Composable
internal fun TvSeekVisualizer(cue: TvSeekVisualizerCue?, modifier: Modifier = Modifier) {
    if (cue == null) return
    
    var visible by remember { mutableStateOf(false) }
    var shownDirection by remember { mutableStateOf(0) }
    val chevronOffset = remember { Animatable(0f) }
    val chevronPulse = remember { Animatable(0f) }

    LaunchedEffect(cue.nonce) {
        if (cue.accumulatedSeconds > 0) {
            // Compose cancels the previous invocation's pending `delay` below
            // on every nonce change, so a rapid burst never actually reaches
            // `visible = false` mid-burst — the container just stays up.
            // A direction flip counts as fresh too, so the new side plays its
            // own slide instead of inheriting the opposite side's in-flight
            // position. TvPlayerScreen already restarts the running total on a
            // flip, so both halves of the burst reset together.
            val arrivingFresh = !visible || cue.direction != shownDirection
            shownDirection = cue.direction
            visible = true

            val inward = if (cue.direction < 0) {
                SEEK_CHEVRON_SLIDE_DISTANCE_DP
            } else {
                -SEEK_CHEVRON_SLIDE_DISTANCE_DP
            }
            launch {
                // Only re-seed the origin when the chevron is arriving fresh,
                // which is also the only time AnimatedVisibility runs fadeIn:
                // its enter transition needs a false -> true edge. Snapping
                // mid-burst would teleport the chevron with no fade to cover
                // it, so a repeat press instead keeps gliding from wherever
                // it currently sits.
                if (arrivingFresh) chevronOffset.snapTo(inward)
                chevronOffset.animateTo(
                    targetValue = 0f,
                    animationSpec = tween(durationMillis = SEEK_CHEVRON_SLIDE_DURATION_MS, easing = LinearOutSlowInEasing),
                )
            }
            launch {
                chevronPulse.snapTo(1f)
                chevronPulse.animateTo(
                    targetValue = 0f,
                    animationSpec = tween(durationMillis = SEEK_CHEVRON_PULSE_MS, easing = FastOutSlowInEasing),
                )
            }

            delay(SEEK_VISUALIZER_VISIBLE_MS)
            visible = false
        }
    }

    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
        Box(
            modifier = modifier.fillMaxSize(),
            contentAlignment = if (cue.direction < 0) {
                Alignment.CenterStart
            } else {
                Alignment.CenterEnd
            },
        ) {
            AnimatedVisibility(
                visible = visible,
                enter = fadeIn(tween(300)),
                exit = fadeOut(tween(SEEK_VISUALIZER_EXIT_MS.toInt())),
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 28.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    if (cue.direction < 0) {
                        SeekChevron(
                            direction = cue.direction,
                            offset = chevronOffset.value,
                            pulse = chevronPulse.value,
                        )
                        SeekAmountLabel(seconds = cue.accumulatedSeconds, textAlign = TextAlign.Start)
                    } else {
                        SeekAmountLabel(seconds = cue.accumulatedSeconds, textAlign = TextAlign.End)
                        SeekChevron(
                            direction = cue.direction,
                            offset = chevronOffset.value,
                            pulse = chevronPulse.value,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun SeekAmountLabel(seconds: Int, textAlign: TextAlign) {
    val label = if (seconds >= SEEK_LABEL_CLOCK_THRESHOLD_SEC) {
        "%d:%02d".format(seconds / 60, seconds % 60)
    } else {
        "${seconds}s"
    }
    val baseStyle = MaterialTheme.typography.headlineMedium.copy(fontWeight = FontWeight.Black)
    val outlineWidthPx = with(LocalDensity.current) { SEEK_LABEL_OUTLINE_WIDTH_DP.dp.toPx() }
    // Both copies share identical text, width and alignment so the fill lands
    // exactly on the outline.
    val labelModifier = Modifier
        .padding(horizontal = SEEK_LABEL_OUTLINE_BLEED_DP.dp)
        .widthIn(min = SEEK_LABEL_MIN_WIDTH_DP.dp)
    Box {
        Text(
            text = label,
            modifier = labelModifier,
            textAlign = textAlign,
            maxLines = 1,
            softWrap = false,
            color = SEEK_OUTLINE_COLOR,
            style = baseStyle.copy(
                drawStyle = Stroke(width = outlineWidthPx, join = StrokeJoin.Round),
            ),
        )
        Text(
            text = label,
            modifier = labelModifier,
            textAlign = textAlign,
            maxLines = 1,
            softWrap = false,
            color = Color.White,
            style = baseStyle,
        )
    }
}

// Custom-drawn chevron: tall (25% of screen height) and skinny (4dp stroke)
@Composable
private fun SeekChevron(direction: Int, offset: Float, pulse: Float) {
    Canvas(
        modifier = Modifier
            .offset(x = offset.dp)
            .scale(1f + pulse * SEEK_CHEVRON_PULSE_SCALE)
            .width(SEEK_CHEVRON_WIDTH_DP.dp)
            .fillMaxHeight(0.25f),
    ) {
        val strokeWidthPx = SEEK_CHEVRON_STROKE_WIDTH_DP.dp.toPx()
        val path = Path().apply {
            if (direction < 0) {
                // "<" — vertex on the left, arms open to the right.
                moveTo(size.width, 0f)
                lineTo(0f, size.height / 2f)
                lineTo(size.width, size.height)
            } else {
                // ">" — vertex on the right, arms open to the left.
                moveTo(0f, 0f)
                lineTo(size.width, size.height / 2f)
                lineTo(0f, size.height)
            }
        }
        drawPath(
            path = path,
            color = SEEK_OUTLINE_COLOR,
            style = Stroke(
                width = strokeWidthPx * SEEK_OUTLINE_STROKE_SCALE,
                cap = StrokeCap.Round,
                join = StrokeJoin.Round,
            ),
        )
        drawPath(
            path = path,
            color = Color.White,
            style = Stroke(width = strokeWidthPx, cap = StrokeCap.Round, join = StrokeJoin.Round),
        )
    }
}
