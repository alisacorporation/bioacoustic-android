package com.whispercppdemo.ui.main

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.whispercppdemo.R
import com.whispercppdemo.recorder.AudioFrame
import com.whispercppdemo.recorder.BANDS
import com.whispercppdemo.ui.theme.V2Colors
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin

/** Frame clock in seconds; recomposes/redraws every display frame. */
@Composable
fun frameSeconds(): State<Long> {
    val t = remember { mutableLongStateOf(0L) }
    LaunchedEffect(Unit) { while (true) withFrameNanos { t.longValue = it } }
    return t
}

/**
 * Smoothed visual state driven by the latest [AudioFrame] — same model as the desktop widget:
 * fast attack / ~1 s critically-damped release for loudness, separately eased wave speed.
 */
class WaveMotion {
    var amp = 0f; private var ampVel = 0f
    var gain = 0.25f
    var speed = 2.2f
    var phase = 0f
    var centroid = 0.3f
    val bands = FloatArray(BANDS)
    val peaks = FloatArray(BANDS)
    private var last = 0L

    fun step(nanos: Long, frame: AudioFrame, active: Boolean) {
        val dt = if (last == 0L) 0.016f else min(0.1f, (nanos - last) / 1e9f)
        last = nanos
        gain += ((if (active) 1f else 0.25f) - gain) * (1 - exp(-dt * 1.8f))
        val target = ((frame.db + 52f) / 40f).coerceIn(0f, 1f) * gain
        val w = if (target > amp) 22f else 4.5f
        ampVel += (w * w * (target - amp) - 2 * w * ampVel) * dt
        amp = (amp + ampVel * dt).coerceIn(0f, 1.2f)
        var total = 0f; var weighted = 0f
        for (i in 0 until BANDS) {
            val v = frame.bands[i]
            val k = 1 - exp(-dt * (if (v > bands[i]) 14f else 3.5f))
            bands[i] += (v - bands[i]) * k
            peaks[i] = max(peaks[i] - dt * 0.35f, bands[i])
            total += bands[i]; weighted += bands[i] * i
        }
        if (total > 1e-3f) centroid += (weighted / total / BANDS - centroid) * (1 - exp(-dt * 4f))
        val ts = 2.2f + 4f * amp
        speed += (ts - speed) * (1 - exp(-dt * (if (ts > speed) 6f else 1.2f)))
        phase += dt * speed
    }
}

private val LINES = listOf(floatArrayOf(1.6f, 1f, 1f, 2.6f), floatArrayOf(2.3f, -1.3f, .75f, 2.1f),
    floatArrayOf(3.1f, 1.7f, .5f, 1.6f), floatArrayOf(1.1f, -.8f, .6f, 1.4f))

fun DrawScope.drawSineWaves(m: WaveMotion, c: V2Colors, timeS: Float) {
    val w = size.width; val h = size.height; val mid = h * 0.44f
    val breathe = 0.035f + 0.015f * sin(timeS * 1.3f)
    val amp = h * 0.36f * max(breathe, m.amp)
    val stretch = 0.8f + 0.9f * m.centroid
    LINES.forEachIndexed { i, (f, s, a, lw) ->
        val path = Path()
        val step = 3f
        var x = 0f
        while (x <= w) {
            val u = x / w
            val env = abs(sin(PI * u)).pow(2.2).toFloat()
            val ff = f * stretch
            val y = mid + env * amp * a * (sin(u * 2 * PI * ff + m.phase * s).toFloat() * 0.75f +
                    sin(u * 2 * PI * ff * 2.7 - m.phase * 0.66 * s).toFloat() * 0.25f)
            if (x == 0f) path.moveTo(x, y) else path.lineTo(x, y)
            x += step
        }
        val col = c.waves[i]
        for ((width, alpha) in listOf(lw * 6 to 0.07f, lw * 2.5f to 0.18f, lw to 0.95f)) {
            drawPath(
                path,
                Brush.horizontalGradient(listOf(Color.Transparent, col.copy(alpha = alpha), Color.Transparent)),
                style = Stroke(width = width * density, cap = StrokeCap.Round),
                blendMode = BlendMode.Plus,
            )
        }
    }
}

fun DrawScope.drawSpectrum(m: WaveMotion, frame: AudioFrame, c: V2Colors) {
    val w = size.width; val h = size.height
    val left = 20.dp.toPx(); val right = w - 20.dp.toPx()
    // waveform (upper half), auto-gained
    val wf = frame.waveform
    var peak = 1e-4f; for (v in wf) peak = max(peak, abs(v))
    val gain = min(1f / peak, 40f) * (0.15f + 0.85f * min(1f, m.amp * 1.6f))
    val top = h * 0.14f; val sh = h * 0.30f; val mid = top + sh / 2
    val path = Path(); val n = 220
    for (i in 0 until n) {
        val x = left + (right - left) * i / (n - 1)
        val y = mid - (wf[i * (wf.size - 1) / (n - 1)] * gain).coerceIn(-1f, 1f) * sh * 0.46f
        if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
    }
    drawPath(path, c.accent.copy(alpha = 0.25f), style = Stroke(5.dp.toPx()))
    drawPath(path, c.accent, style = Stroke(1.6.dp.toPx()))
    // bars (lower half)
    val by = h * 0.80f; val bh = h * 0.30f; val gap = 2.dp.toPx()
    val bw = (right - left - gap * (BANDS - 1)) / BANDS
    val brush = Brush.verticalGradient(listOf(c.accent, c.accent2), startY = by - bh, endY = by)
    for (i in 0 until BANDS) {
        val x = left + i * (bw + gap)
        val bar = max(2f, m.bands[i] * bh)
        drawRect(brush, Offset(x, by - bar), Size(bw, bar))
        val pk = by - max(4f, m.peaks[i] * bh) - 4.dp.toPx()
        drawRect(c.text.copy(alpha = 0.85f), Offset(x, pk), Size(bw, 1.5.dp.toPx()))
    }
}

/** Square viewfinder brackets in the four corners. */
fun DrawScope.drawBrackets(color: Color, inset: Float, len: Float, stroke: Float) {
    val w = size.width; val h = size.height
    fun l(a: Offset, b: Offset) = drawLine(color, a, b, stroke)
    l(Offset(inset, inset), Offset(inset + len, inset)); l(Offset(inset, inset), Offset(inset, inset + len))
    l(Offset(w - inset, inset), Offset(w - inset - len, inset)); l(Offset(w - inset, inset), Offset(w - inset, inset + len))
    l(Offset(inset, h - inset), Offset(inset + len, h - inset)); l(Offset(inset, h - inset), Offset(inset, h - inset - len))
    l(Offset(w - inset, h - inset), Offset(w - inset - len, h - inset)); l(Offset(w - inset, h - inset), Offset(w - inset, h - inset - len))
}

/**
 * Hold-to-talk / tap-for-hands-free record button. At rest a red square; while recording it turns
 * into a slowly spinning emblem (Umbrella themes) or a live equalizer (Neon), and morphs back
 * gracefully (~1 s) when recording stops.
 */
@Composable
fun RecordButton(
    c: V2Colors,
    recording: Boolean,
    enabled: Boolean,
    motion: WaveMotion,
    clock: androidx.compose.runtime.MutableLongState,
    size: Dp = 96.dp,
    onPress: () -> Unit,
    onRelease: (heldMs: Long) -> Unit,
) {
    val holder = remember { MorphState() }
    val frameNanos = clock.longValue
    holder.step(frameNanos, recording, motion.amp)
    val press by rememberUpdatedState(onPress)
    val release by rememberUpdatedState(onRelease)
    val e = holder.eased
    Box(
        Modifier
            .size(size)
            .pointerInput(enabled) {
                if (!enabled) return@pointerInput
                awaitEachGesture {
                    awaitFirstDown()
                    val t0 = System.currentTimeMillis()
                    press()
                    waitForUpOrCancellation()
                    release(System.currentTimeMillis() - t0)
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.size(size)) {
            val cx = this.size.width / 2; val cy = this.size.height / 2
            val half = if (c.corp) 36.dp.toPx() * (1 - 0.35f * e) else 36.dp.toPx() * (1 - 0.15f * e) + 4.dp.toPx() * motion.amp * e
            val body = if (c.corp) (1 - e).pow(1.2f) else 1f
            val alphaMul = if (enabled) 1f else 0.4f
            if (body > 0.01f) {
                drawRect(c.rec.copy(alpha = 0.22f * body * alphaMul), Offset(cx - half - 6.dp.toPx(), cy - half - 6.dp.toPx()),
                    Size(2 * half + 12.dp.toPx(), 2 * half + 12.dp.toPx()))
                drawRect(
                    Brush.radialGradient(listOf(lerpWhite(c.rec, 0.45f), c.rec), center = Offset(cx - half * 0.35f, cy - half * 0.45f), radius = half * 2f),
                    Offset(cx - half, cy - half), Size(2 * half, 2 * half), alpha = body * alphaMul,
                )
            }
            if (!c.corp && e > 0.01f) {
                val t = frameNanos / 1e9f
                val bw = 5.dp.toPx(); val gap = 4.dp.toPx(); val x0 = cx - (5 * bw + 4 * gap) / 2
                for (i in 0 until 5) {
                    val wob = 0.5f + 0.5f * sin(t * (7 + i * 1.7f) + i * 1.3f)
                    val bh = 6.dp.toPx() + (2 * half - 16.dp.toPx()) * (0.12f + 0.88f * motion.amp) * (0.35f + 0.65f * wob)
                    drawRect(Color.White.copy(alpha = e), Offset(x0 + i * (bw + gap), cy - bh / 2), Size(bw, bh))
                }
            }
        }
        if (c.corp && e > 0.01f) {
            Image(
                painterResource(R.drawable.emblem), contentDescription = null,
                modifier = Modifier.size(size * 0.72f).scale((0.85f + 0.15f * e) * (1f + 0.12f * motion.amp))
                    .rotate(holder.angle).alpha(e),
            )
        }
    }
}

private class MorphState {
    var mix = 0f; var angle = 0f; private var last = 0L
    val eased get() = mix * mix * (3 - 2 * mix)
    fun step(nanos: Long, active: Boolean, level: Float) {
        val dt = if (last == 0L) 0f else min(0.1f, (nanos - last) / 1e9f)
        last = nanos
        val rate = if (active) 9f else 2.6f
        mix += ((if (active) 1f else 0f) - mix) * (1 - exp(-dt * rate))
        angle = (angle + dt * (20f + 70f * level.coerceIn(0f, 1f)) * mix) % 360f
    }
}

private fun lerpWhite(c: Color, t: Float) = Color(c.red + (1 - c.red) * t, c.green + (1 - c.green) * t, c.blue + (1 - c.blue) * t, c.alpha)
