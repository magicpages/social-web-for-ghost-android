package co.magicpages.socialweb.ui.onboarding

import android.database.ContentObserver
import android.graphics.BlendMode
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.BlurEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.layer.CompositingStrategy
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.toIntSize
import co.magicpages.socialweb.R
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * The welcome page's silk 8: the same mark as [SilkMark], flowing, like the iOS app's looping
 * video. The strands are redrawn each frame from the icon's geometry (design/silkicon.swift) as
 * the twists travel along the ribbons, the loops undulate and the silk billows, in a loop that
 * ends exactly where it begins.
 *
 * It stays the still [SilkMark] where it cannot flow: with "Remove animations" on, and below
 * Android 12, which cannot blur a layer (the receding strokes and the glow need it). The frame
 * clock stops with the app's lifecycle, so nothing is drawn while the app is in the background.
 */
@Composable
fun SilkFlow(size: Dp, modifier: Modifier = Modifier) {
    if (!rememberAnimationsEnabled() || Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
        SilkMark(size, modifier)
        return
    }
    val time = rememberInfiniteTransition(label = "silkFlow").animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(LoopMillis, easing = LinearEasing), RepeatMode.Restart),
        label = "silkFlowTime",
    )
    val strands = remember { SilkStrands() }
    val back = rememberGraphicsLayer()
    val front = rememberGraphicsLayer()
    val wideGlow = rememberGraphicsLayer()
    val tightGlow = rememberGraphicsLayer()
    val recordBack: DrawScope.() -> Unit = remember(strands) { { drawIntoCanvas { strands.draw(it.nativeCanvas, SilkStrands.Back) } } }
    val recordFront: DrawScope.() -> Unit = remember(strands) { { drawIntoCanvas { strands.draw(it.nativeCanvas, SilkStrands.Front) } } }
    val recordGlow: DrawScope.() -> Unit = remember(front) { { drawLayer(front) } }
    // The still mark's layers, masked to a circle: the night behind the silk. The silk fills the
    // central 72 dp of the 108 dp layer, which is the mark's size.
    Box(modifier.size(size).clip(CircleShape), contentAlignment = Alignment.Center) {
        Image(painterResource(R.drawable.ic_launcher_background), contentDescription = null, modifier = Modifier.requiredSize(size * 1.5f))
        Spacer(
            Modifier.fillMaxSize().drawBehind {
                if (strands.prepare(this.size.width, density)) {
                    val unit = this.size.width / SilkStrands.Box
                    // Strands add up where they cross, so each layer composites on its own.
                    for (layer in listOf(back, front, wideGlow, tightGlow)) layer.compositingStrategy = CompositingStrategy.Offscreen
                    back.renderEffect = BlurEffect(radiusX = BackBlur * unit, radiusY = BackBlur * unit, edgeTreatment = TileMode.Decal)
                    back.alpha = BackOpacity
                    wideGlow.renderEffect = BlurEffect(radiusX = WideGlowBlur * unit, radiusY = WideGlowBlur * unit, edgeTreatment = TileMode.Decal)
                    wideGlow.alpha = WideGlowOpacity
                    tightGlow.renderEffect = BlurEffect(radiusX = TightGlowBlur * unit, radiusY = TightGlowBlur * unit, edgeTreatment = TileMode.Decal)
                    tightGlow.alpha = TightGlowOpacity
                }
                strands.update(time.value)
                val layerSize = this.size.toIntSize()
                back.record(layerSize, recordBack)
                front.record(layerSize, recordFront)
                wideGlow.record(layerSize, recordGlow)
                tightGlow.record(layerSize, recordGlow)
                drawLayer(back)
                drawLayer(wideGlow)
                drawLayer(tightGlow)
                drawLayer(front)
            },
        )
    }
}

/** One turn of the flow. Every motion completes whole cycles per loop, so it repeats without a seam. */
private const val LoopMillis = 15_000

// The icon's three-layer look (design/silkicon.swift `final`), in its 1024-point box: the receding
// stroke blurred and dim, then two glows from the S, then the S itself.
private const val BackBlur = 18f
private const val BackOpacity = 0.3f
private const val WideGlowBlur = 26f
private const val WideGlowOpacity = 0.45f
private const val TightGlowBlur = 10f
private const val TightGlowOpacity = 0.75f

/** Whether the system's animations are on; "Remove animations" (animator duration scale 0) turns them off. */
@Composable
private fun rememberAnimationsEnabled(): Boolean {
    val resolver = LocalContext.current.contentResolver
    var enabled by remember(resolver) { mutableStateOf(animationsOn(resolver)) }
    DisposableEffect(resolver) {
        val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) {
                enabled = animationsOn(resolver)
            }
        }
        resolver.registerContentObserver(Settings.Global.getUriFor(Settings.Global.ANIMATOR_DURATION_SCALE), false, observer)
        enabled = animationsOn(resolver)
        onDispose { resolver.unregisterContentObserver(observer) }
    }
    return enabled
}

private fun animationsOn(resolver: android.content.ContentResolver): Boolean =
    Settings.Global.getFloat(resolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) > 0f

/**
 * The strands of the mark's three ribbons, flowing. Everything that does not depend on time is
 * tabulated once; a frame then costs a few multiplications per strand sample and no allocation.
 * Segments are sorted by brightness into groups, each drawn with one call, since the strands add
 * up (plus blending) and so the order they are drawn in does not matter.
 */
private class SilkStrands {
    private class Ribbon(
        val scale: Float,
        val phase: Float,
        val halfTurns: Int,
        val twistPhase: Float,
        val halfWidth: Float,
        val strands: Int,
        /** Whole turns of the twist and the wobble per loop; the sign sets the direction. */
        val twistCycles: Int,
        val wobbleCycles: Int,
    )

    private val ribbons = listOf(
        Ribbon(1.0f, 0.0f, 3, 0.4f, 0.15f * HalfHeight, 56, 1, 1),
        Ribbon(0.97f, 1.7f, 5, 2.1f, 0.11f * HalfHeight, 40, -1, -1),
        Ribbon(1.03f, 3.1f, 2, 1.0f, 0.08f * HalfHeight, 28, 2, 1),
    )

    // One entry per strand, ribbon after ribbon.
    private val firstStrand = IntArray(ribbons.size + 1)
    private val strandU: FloatArray
    private val strandJitter: FloatArray
    private val strandMirror: IntArray
    private val strandEdge: BooleanArray
    private val edgeStrands = IntArray(ribbons.size)
    private val bodyStrands = IntArray(ribbons.size)

    // Tables over the loop's samples, 0..Steps (t = 2π · i / Steps).
    private val sin2 = FloatArray(Steps + 1)
    private val cos2 = FloatArray(Steps + 1)
    private val sin3 = FloatArray(Steps + 1)
    private val cos3 = FloatArray(Steps + 1)
    private val sin5 = FloatArray(Steps + 1)
    private val cos5 = FloatArray(Steps + 1)
    /** sin t · cos t / d and cos t / d of the figure-8, d = 1 + sin² t. */
    private val loopX = FloatArray(Steps + 1)
    private val loopY = FloatArray(Steps + 1)
    /** How much of the S each sample is (1), against the receding back of the 8 (0). */
    private val frontWeight = FloatArray(Steps + 1)
    /** Per ribbon: cos and sin of halfTurns · t / 2, the twist's own angle at t. */
    private val turnCos: Array<FloatArray>
    private val turnSin: Array<FloatArray>

    // Per frame, per ribbon: the brightness group of each segment, or -1 for none.
    private val groupOf = Array(Layers) { Array(ribbons.size) { ByteArray(Steps) } }
    private val glint = FloatArray(Steps + 1)
    private val pointX = FloatArray(Steps + 1)
    private val pointY = FloatArray(Steps + 1)
    private val normalX = FloatArray(Steps + 1)
    private val normalY = FloatArray(Steps + 1)
    private val width = FloatArray(Steps + 1)
    private val twistCos = FloatArray(Steps + 1)
    private val twistSin2 = FloatArray(Steps + 1)
    private val strandX = FloatArray(Steps + 1)
    private val strandY = FloatArray(Steps + 1)

    /** Segments per (layer, brightness, edge), where each group starts in its layer's array, and where it is filled to. */
    private val counts = IntArray(Layers * Groups)
    private val starts = IntArray(Layers * Groups)
    private val cursors = IntArray(Layers * Groups)
    private val segments: Array<FloatArray>

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.BUTT
        blendMode = BlendMode.PLUS
    }
    private var sizePx = 0f
    private var scale = 0f

    init {
        val u = ArrayList<Float>()
        val jitter = ArrayList<Float>()
        val mirror = ArrayList<Int>()
        val edge = ArrayList<Boolean>()
        for ((r, ribbon) in ribbons.withIndex()) {
            firstStrand[r] = u.size
            val count = max(6, (ribbon.strands * StrandScale).toInt())
            for (i in 0 until count) {
                val position = -1f + 2f * i / (count - 1)
                // A strand and its mirror (u, -u) share their jitter and sway, so they still
                // meet where an odd number of half-turns brings a ribbon round to itself.
                val m = min(i, count - 1 - i)
                u += position
                jitter += (sin(m * 12.9898) * 0.5).toFloat()
                mirror += m
                edge += abs(position) > 0.92f
                if (abs(position) > 0.92f) edgeStrands[r]++ else bodyStrands[r]++
            }
        }
        firstStrand[ribbons.size] = u.size
        strandU = u.toFloatArray()
        strandJitter = jitter.toFloatArray()
        strandMirror = mirror.toIntArray()
        strandEdge = edge.toBooleanArray()
        segments = Array(Layers) { FloatArray(u.size * Steps * 4) }

        // The S, as in the icon: everything within `reach` of the back crossing (t = π/2) recedes,
        // with short ramps at the S's ends.
        fun smoothstep(a: Double, b: Double, x: Double): Double {
            val v = ((x - a) / (b - a)).coerceIn(0.0, 1.0)
            return v * v * (3 - 2 * v)
        }
        val start = PI / 2 - BackReach
        val end = PI / 2 + BackReach
        for (i in 0..Steps) {
            val t = 2 * PI * i / Steps
            sin2[i] = sin(2 * t).toFloat()
            cos2[i] = cos(2 * t).toFloat()
            sin3[i] = sin(3 * t).toFloat()
            cos3[i] = cos(3 * t).toFloat()
            sin5[i] = sin(5 * t).toFloat()
            cos5[i] = cos(5 * t).toFloat()
            val d = 1 + sin(t) * sin(t)
            loopX[i] = (sin(t) * cos(t) / d).toFloat()
            loopY[i] = (cos(t) / d).toFloat()
            frontWeight[i] = (1 - smoothstep(start - BackRamp, start + BackRamp, t) * (1 - smoothstep(end - BackRamp, end + BackRamp, t))).toFloat()
        }
        turnCos = Array(ribbons.size) { r -> FloatArray(Steps + 1) { cos(ribbons[r].halfTurns * PI * it / Steps).toFloat() } }
        turnSin = Array(ribbons.size) { r -> FloatArray(Steps + 1) { sin(ribbons[r].halfTurns * PI * it / Steps).toFloat() } }
    }

    /**
     * Fits the strands to a mark [sizePx] wide; true when that changed, so the caller can
     * rescale what depends on it. The line is the still mark's: at least 0.6 dp.
     */
    fun prepare(sizePx: Float, density: Float): Boolean {
        if (sizePx == this.sizePx) return false
        this.sizePx = sizePx
        scale = sizePx / Box
        paint.strokeWidth = max(0.6f * density, 1.4f * 1.6f * scale)
        paint.shader = LinearGradient(
            sizePx / 2, (Center - HalfHeight) * scale, sizePx / 2, (Center + HalfHeight) * scale,
            intArrayOf(Top.toArgb(), Middle.toArgb(), Bottom.toArgb()), floatArrayOf(0f, 0.5f, 1f), Shader.TileMode.CLAMP,
        )
        return true
    }

    /** Lays the strands out for [time], 0 to 1 round the loop. */
    fun update(time: Float) {
        val turn = (2 * PI * time).toFloat()
        counts.fill(0)
        for (r in ribbons.indices) group(r, turn)
        for (layer in 0 until Layers) {
            var offset = 0
            for (g in 0 until Groups) {
                starts[layer * Groups + g] = offset
                cursors[layer * Groups + g] = offset
                offset += counts[layer * Groups + g] * 4
            }
        }
        for (r in ribbons.indices) lay(r, turn)
    }

    /** Sorts ribbon [r]'s segments into brightness groups, for the S and for the receding back. */
    private fun group(r: Int, turn: Float) {
        // Soft glints slide along the ribbon, like light catching silk.
        val angle = -turn + r * 2.1f
        val c = cos(angle)
        val s = sin(angle)
        for (i in 0..Steps) glint[i] = 0.8f + 0.2f * (0.5f + 0.5f * (sin3[i] * c + cos3[i] * s))
        for (i in 0 until Steps) {
            val light = 0.5f * (glint[i] + glint[i + 1])
            val front = 0.5f * (frontWeight[i] + frontWeight[i + 1])
            groupOf[Front][r][i] = sort(Front, front * light, r)
            groupOf[Back][r][i] = sort(Back, (1 - front) * light, r)
        }
    }

    private fun sort(layer: Int, weight: Float, ribbon: Int): Byte {
        val w = min(1f, max(0f, weight))
        if (w <= MinWeight) return -1
        val bucket = min(Buckets - 1, (w * Buckets).toInt())
        counts[layer * Groups + bucket * 2] += bodyStrands[ribbon]
        counts[layer * Groups + bucket * 2 + 1] += edgeStrands[ribbon]
        return bucket.toByte()
    }

    /** Writes ribbon [r]'s strands, in pixels, into the segment arrays. */
    private fun lay(r: Int, turn: Float) {
        val ribbon = ribbons[r]
        // The hand-drawn wobble turns, so the loops undulate.
        val wobble = ribbon.phase + turn * ribbon.wobbleCycles
        val wobbleCos = cos(wobble)
        val wobbleSin = sin(wobble)
        for (i in 0..Steps) {
            val wobbled = 1 + 0.025f * (sin3[i] * wobbleCos + cos3[i] * wobbleSin) + 0.015f * (cos5[i] * wobbleCos + sin5[i] * wobbleSin)
            val stretched = 1 + 0.01f * (sin2[i] * wobbleCos + cos2[i] * wobbleSin)
            pointX[i] = (Center + 1.6f * HalfHeight * loopX[i] * ribbon.scale * wobbled) * scale
            pointY[i] = (Center - HalfHeight * loopY[i] * ribbon.scale * stretched) * scale
        }
        // The ribbon's normal, from its neighbours; the loop is closed, so the ends wrap.
        for (i in 0..Steps) {
            val before = if (i == 0) Steps - 1 else i - 1
            val after = if (i == Steps) 1 else i + 1
            val dx = pointX[after] - pointX[before]
            val dy = pointY[after] - pointY[before]
            val length = max(1e-6f, sqrt(dx * dx + dy * dy))
            normalX[i] = -dy / length
            normalY[i] = dx / length
        }
        // The twist travels along the ribbon (edge-on turns squeeze the strands into bright
        // folds), and a wave of width runs along it, so the silk billows.
        val twist = ribbon.twistPhase + turn * ribbon.twistCycles
        val twistC = cos(twist)
        val twistS = sin(twist)
        val billow = -turn + r * 1.9f
        val billowC = cos(billow)
        val billowS = sin(billow)
        for (i in 0..Steps) {
            val c = turnCos[r][i] * twistC - turnSin[r][i] * twistS
            val s = turnSin[r][i] * twistC + turnCos[r][i] * twistS
            twistCos[i] = c
            twistSin2[i] = 2 * s * c
            width[i] = ribbon.halfWidth * scale * (1 + 0.09f * (sin2[i] * billowC + cos2[i] * billowS))
        }
        for (strand in firstStrand[r] until firstStrand[r + 1]) {
            val u = strandU[strand]
            val wander = 0.18f * strandJitter[strand]
            // Each strand drifts a little on its own, so the silk shimmers instead of moving as one sheet.
            val drift = turn + strandMirror[strand] * 2.3f
            val driftC = cos(drift)
            val driftS = sin(drift)
            for (i in 0..Steps) {
                val sway = 1 + 0.06f * (driftS * cos2[i] + driftC * sin2[i])
                val offset = width[i] * (u * sway * twistCos[i] + wander * twistSin2[i])
                strandX[i] = pointX[i] + normalX[i] * offset
                strandY[i] = pointY[i] + normalY[i] * offset
            }
            val edge = if (strandEdge[strand]) 1 else 0
            for (layer in 0 until Layers) {
                val groups = groupOf[layer][r]
                val out = segments[layer]
                for (i in 0 until Steps) {
                    val bucket = groups[i].toInt()
                    if (bucket < 0) continue
                    val g = layer * Groups + bucket * 2 + edge
                    val at = cursors[g]
                    out[at] = strandX[i]
                    out[at + 1] = strandY[i]
                    out[at + 2] = strandX[i + 1]
                    out[at + 3] = strandY[i + 1]
                    cursors[g] = at + 4
                }
            }
        }
    }

    /** Draws a layer, [Front] (the S) or [Back] (the receding strokes), one call per brightness. */
    fun draw(canvas: Canvas, layer: Int) {
        for (bucket in 0 until Buckets) {
            for (edge in 0..1) {
                val g = layer * Groups + bucket * 2 + edge
                if (counts[g] == 0) continue
                // A ribbon's edges are crisper than its sheet, like folded silk.
                val alpha = min(1f, StrandAlpha * (bucket + 0.5f) / Buckets * if (edge == 1) EdgeBoost else 1f)
                paint.alpha = (alpha * 255f + 0.5f).toInt()
                canvas.drawLines(segments[layer], starts[g], counts[g] * 4, paint)
            }
        }
    }

    companion object {
        const val Front = 0
        const val Back = 1
        /** The icon's 1024-point box, with the figure-8 centred in it. */
        const val Box = 1024f

        private const val Layers = 2
        private const val Center = 512f
        private const val HalfHeight = 338f

        /** A sample every 2π / Steps round the loop; fewer strands than the icon, as the small still mark has. */
        private const val Steps = 240
        private const val StrandScale = 0.2f
        private const val StrandAlpha = 0.45f
        private const val EdgeBoost = 3.4f

        /** Brightness steps; fine enough that a glint sliding along the silk never visibly jumps. */
        private const val Buckets = 16
        private const val Groups = Buckets * 2
        private const val MinWeight = 0.02f
        private const val BackReach = 1.17
        private const val BackRamp = 0.22

        // The icon's colours, top to bottom.
        private val Top = Color(0xFFFF6AF2)
        private val Middle = Color(0xFFC064FF)
        private val Bottom = Color(0xFF7A6BFF)
    }
}
