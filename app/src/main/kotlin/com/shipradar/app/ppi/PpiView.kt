package com.shipradar.app.ppi

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import com.shipradar.contract.EchoSpoke
import com.shipradar.uicore.color.ColorMapper
import com.shipradar.uicore.ppi.BearingScale
import com.shipradar.uicore.ppi.BearingTickLevel
import com.shipradar.uicore.ppi.DisplaySize
import com.shipradar.uicore.ppi.PpiProjection
import com.shipradar.uicore.ppi.RangeModel
import com.shipradar.uicore.ppi.RangeRings
import com.shipradar.uicore.ppi.ScreenPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import kotlin.math.min

/**
 * T2.1r — the circular PPI **echo render surface**.
 *
 * A hardware-accelerated custom [View] that turns a stream of [EchoSpoke]s into the radar picture,
 * plus the calibrated graphics (range rings, bearing scale, heading line). It owns ONLY the Android
 * Canvas/GPU plumbing: every screen coordinate comes from the JVM-verified
 * [com.shipradar.uicore.ppi] geometry and every echo colour from [ColorMapper].
 *
 * ## Rendering model (scan-conversion with persistence)
 * Echoes are scan-converted into an offscreen ARGB persistence [Bitmap] as spokes arrive (one spoke
 * = one thin annular sector per colour-run). [onDraw] blits that bitmap (GPU-composited — the View
 * is hardware-accelerated) and draws the vector overlays crisply on top each frame. Each incoming
 * spoke first CLEARs its own wedge so the previous revolution's echoes at that bearing are replaced
 * (no smear), which also realises the radar afterglow = one-revolution persistence.
 *
 * ## Standards
 *  - Dark non-reflecting background, every ambient condition — IEC 62288 Ed.2 §5.4.1.1.
 *  - Range rings: equally spaced, separation indicated, centred at CCRP — IEC 62388 §9.11.2.1.
 *  - Bearing scale: numbered ≥30°, marks ≥5°, 5°/10° distinct — IEC 62388 §9.10.2.1.
 *  - Heading line: CCRP → bearing scale — IEC 62388 §8.2.3.1.
 *  - Linear range axis, displayed range +0 %…+8 % of scale at cardinals — IEC 62388 §9.4.1.2(e)
 *    (over-scan beyond fraction 1.0 is clipped via [RangeModel.maxDisplayableSampleIndex]).
 *  - Range-change blanking ≤ 1 scan — IEC 62388 §9.4.1.2(d) (see [setConfig]).
 *  - ≥ 320 mm equivalent operational area, CAT 1 — IEC 62388 §4.4 Table 1 (see [onSizeChanged]).
 *
 * Thread model: spokes are scan-converted on a background coroutine (single collector ⇒ no
 * concurrent writers); [onDraw] reads the bitmap on the UI thread. The two are serialised by
 * [bitmapLock].
 */
class PpiView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : View(context, attrs, defStyleAttr) {

    // ---- configuration --------------------------------------------------------------------------

    @Volatile
    var config: PpiConfig = PpiConfig()
        private set

    /**
     * Apply a new [PpiConfig]. If the **range scale changed**, the persistence bitmap is cleared so
     * stale-range echoes vanish immediately; the next revolution repaints within the DISP-02 budget
     * of ≤ 1 scan ([RangeModel.maxBlankingDurationMs]) and the overlays (rings/scale) recompute on
     * the very next frame, so full functionality is never blank for more than one scan
     * (IEC 62388 §9.4.1.2(d)).
     */
    fun setConfig(newConfig: PpiConfig) {
        val rangeChanged = newConfig.rangeScaleNm != config.rangeScaleNm
        config = newConfig
        if (rangeChanged) clearEchoes()
        rebuildProjections()
        postInvalidateOnAnimation()
    }

    // ---- pixel geometry (recomputed on size / config change) ------------------------------------

    /** Operational-area radius in pixels (half the inscribed circle of the view). */
    private var radiusPx: Float = 0f

    /** Projection used to scan-convert spokes INTO the bitmap (centre = bitmap centre). */
    private var echoProjection: PpiProjection? = null

    /** Projection used to draw overlays on the view canvas (centre = view centre). */
    private var overlayProjection: PpiProjection? = null

    // ---- persistence bitmap ---------------------------------------------------------------------

    private val bitmapLock = Any()
    private var echoBitmap: Bitmap? = null
    private var echoCanvas: Canvas? = null

    // ---- paints (reused; no per-frame allocation) -----------------------------------------------

    private val echoPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val clearPaint = Paint().apply { xfermode = PorterDuffXfermode(PorterDuff.Mode.CLEAR) }
    private val backgroundPaint = Paint().apply { style = Paint.Style.FILL }
    private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(1f)
        color = GRAPHIC_COLOR
    }
    private val headingPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(1.5f)
        color = HEADING_COLOR
    }
    private val tickPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = GRAPHIC_COLOR
    }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = GRAPHIC_COLOR
        textSize = dp(11f)
    }

    private val arcRect = RectF()

    // ---- spoke ingestion ------------------------------------------------------------------------

    private var scope: CoroutineScope? = null
    private var collectJob: Job? = null
    private var pendingFlow: Flow<EchoSpoke>? = null

    /**
     * Drive this surface from a live spoke stream (the comms image channel, T1.2 → [EchoSpoke]).
     * Collection starts when the view is attached and is cancelled on detach. Safe to call before
     * attach (the flow is remembered).
     */
    fun attachSpokes(spokes: Flow<EchoSpoke>) {
        pendingFlow = spokes
        restartCollection()
    }

    /** Scan-convert a one-shot list of spokes synchronously (used by previews/tests). */
    fun renderSnapshot(spokes: List<EchoSpoke>) {
        spokes.forEach { drawSpoke(it) }
        postInvalidateOnAnimation()
    }

    private fun restartCollection() {
        collectJob?.cancel()
        val s = scope ?: return
        val flow = pendingFlow ?: return
        collectJob = s.launch {
            flow.collect { spoke ->
                drawSpoke(spoke)
                postInvalidateOnAnimation()
            }
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        restartCollection()
    }

    override fun onDetachedFromWindow() {
        scope?.cancel()
        scope = null
        collectJob = null
        super.onDetachedFromWindow()
    }

    // ---- sizing & projections -------------------------------------------------------------------

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        // IEC 62388 §9.10.2.1: the bearing scale shall be drawn OUTSIDE the operational display area.
        // Reserve a ring of margin for the scale ticks + numerals so they are not clipped at the view
        // edge and the operational area genuinely excludes the scale (also keeps the §4.4 operational-
        // area measurement to the echo circle, not the chrome). Margin is tunable with the T2.9 frame.
        val marginPx = dp(BEARING_SCALE_MARGIN_DP) * 2.0
        val diameterPx = (DisplaySize.fittedDiameterPx(w.toDouble(), h.toDouble()) - marginPx).coerceAtLeast(1.0)
        radiusPx = (diameterPx / 2.0).toFloat()

        // DISP-01 (IEC 62388 §4.4 Table 1): the CAT 1 operational area must be ≥ 320 mm in diameter.
        // Measured on the echo circle (scale margin excluded, per §9.10.2.1). We can only check it
        // here against the panel's reported DPI; the actual physical panel size is 待张建 input
        // (see delivery report). Surfaced as a flag, not a crash.
        val dpi = resources.displayMetrics.densityDpi.toDouble()
        if (!DisplaySize.meetsMinimumDisplayArea(diameterPx, dpi)) {
            android.util.Log.w(
                TAG,
                "DISP-01: operational area ${"%.1f".format(DisplaySize.effectiveDisplayDiameterMm(diameterPx, dpi))} mm " +
                    "< 320 mm at ${dpi.toInt()} dpi — CAT 1 requires ≥ 320 mm. Confirm panel DPI/size (待张建).",
            )
        }

        val side = diameterPx.toInt().coerceAtLeast(1)
        synchronized(bitmapLock) {
            echoBitmap?.recycle()
            val bmp = Bitmap.createBitmap(side, side, Bitmap.Config.ARGB_8888)
            echoBitmap = bmp
            echoCanvas = Canvas(bmp)
        }
        rebuildProjections()
    }

    private fun rebuildProjections() {
        val r = radiusPx.toDouble()
        if (r <= 0.0) return
        val o = config.effectiveOrientation
        echoProjection = PpiProjection.create(
            center = ScreenPoint(r, r), // bitmap is a (2r × 2r) square centred on the CCRP
            radiusPx = r,
            orientation = o,
            headingDeg = config.headingDeg,
            courseDeg = config.courseDeg,
        )
        overlayProjection = PpiProjection.create(
            center = ScreenPoint(width / 2.0, height / 2.0),
            radiusPx = r,
            orientation = o,
            headingDeg = config.headingDeg,
            courseDeg = config.courseDeg,
        )
    }

    private fun clearEchoes() {
        synchronized(bitmapLock) { echoBitmap?.eraseColor(android.graphics.Color.TRANSPARENT) }
    }

    // ---- scan conversion (background thread) ----------------------------------------------------

    private fun drawSpoke(spoke: EchoSpoke) {
        val proj = echoProjection ?: return
        val n = spoke.samples.size
        if (n == 0) return
        val table = ColorMapper.colorTable(spoke.encoding, config.palette) // sample 0..15 → ARGB
        val maxIndex = min(n - 1, RangeModel.maxDisplayableSampleIndex(n))  // clip over-scan to +8 %
        val half = config.spokeWidthDeg / 2f
        // drawArc angles are measured clockwise from +x (3 o'clock) in screen (y-down) space; our
        // screen angle is clockwise from up (12 o'clock) ⇒ subtract 90°.
        val startCanvasAngle = (proj.displayRotationDeg + spoke.azimuthDeg - half - 90.0).toFloat()
        val cx = proj.centerX.toFloat()
        val cy = proj.centerY.toFloat()

        synchronized(bitmapLock) {
            val c = echoCanvas ?: return
            // Clear this spoke's full wedge first → replace last revolution's echoes at this bearing.
            drawWedge(c, cx, cy, 0f, radiusPx, startCanvasAngle, config.spokeWidthDeg, clearPaint)

            var i = 0
            while (i <= maxIndex) {
                val color = table[(spoke.samples[i].toInt() and 0xFF).coerceIn(0, ColorMapper.LEVELS - 1)]
                var j = i
                while (j + 1 <= maxIndex &&
                    table[(spoke.samples[j + 1].toInt() and 0xFF).coerceIn(0, ColorMapper.LEVELS - 1)] == color
                ) j++
                if (color != ColorMapper.TRANSPARENT) {
                    val innerR = (RangeModel.sampleIndexToRangeFraction(i, n) * radiusPx).toFloat()
                    val outerR = (RangeModel.sampleIndexToRangeFraction(j + 1, n) * radiusPx).toFloat()
                    echoPaint.color = color
                    drawWedge(c, cx, cy, innerR, outerR, startCanvasAngle, config.spokeWidthDeg, echoPaint)
                }
                i = j + 1
            }
        }
    }

    /** Paint an annular sector [innerR,outerR] over [sweep]° as a stroked arc of mid radius. */
    private fun drawWedge(
        c: Canvas,
        cx: Float,
        cy: Float,
        innerR: Float,
        outerR: Float,
        startAngle: Float,
        sweep: Float,
        paint: Paint,
    ) {
        val midR = (innerR + outerR) / 2f
        if (midR <= 0f) return
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = (outerR - innerR).coerceAtLeast(1f)
        arcRect.set(cx - midR, cy - midR, cx + midR, cy + midR)
        c.drawArc(arcRect, startAngle, sweep, false, paint)
    }

    // ---- compositing (UI thread) ----------------------------------------------------------------

    override fun onDraw(canvas: Canvas) {
        // IEC 62288 §5.4.1.1 — dark non-reflecting background under every ambient condition.
        backgroundPaint.color = backgroundColorFor(config.palette)
        canvas.drawCircle(width / 2f, height / 2f, radiusPx, backgroundPaint)

        synchronized(bitmapLock) {
            echoBitmap?.let { bmp ->
                canvas.drawBitmap(bmp, width / 2f - radiusPx, height / 2f - radiusPx, null)
            }
        }

        val proj = overlayProjection ?: return
        if (config.showRangeRings) drawRangeRings(canvas, proj)
        if (config.showBearingScale) drawBearingScale(canvas, proj)
        if (config.showHeadingLine) drawHeadingLine(canvas, proj)
    }

    private fun drawRangeRings(canvas: Canvas, proj: PpiProjection) {
        // IEC 62388 §9.11.2.1: equally spaced rings, separation indicated, centred at CCRP.
        val rings = RangeRings.rangeRings(config.rangeScaleNm, proj.radiusPx)
        rings.forEach { ring ->
            canvas.drawCircle(proj.centerX.toFloat(), proj.centerY.toFloat(), ring.radiusPx.toFloat(), ringPaint)
        }
        // Indicate the ring separation (required when rings are displayed).
        rings.firstOrNull()?.let { first ->
            canvas.drawText(
                "RINGS ${fmtNm(first.separationNm)} NM",
                proj.centerX.toFloat() + dp(6f),
                proj.centerY.toFloat() - dp(4f),
                textPaint,
            )
        }
    }

    private fun drawBearingScale(canvas: Canvas, proj: PpiProjection) {
        // IEC 62388 §9.10.2.1: numbered ≥30°, division marks ≥5°, 5°/10° distinguishable.
        val ticks = BearingScale.bearingScaleTicks(
            orientation = config.effectiveOrientation,
            headingDeg = config.headingDeg,
            courseDeg = config.courseDeg,
        )
        ticks.forEach { tick ->
            val len = when (tick.level) {
                BearingTickLevel.MAJOR -> dp(12f)
                BearingTickLevel.MEDIUM -> dp(8f)
                BearingTickLevel.MINOR -> dp(5f)
                BearingTickLevel.FINE -> dp(3f)
            }
            tickPaint.strokeWidth = if (tick.level == BearingTickLevel.MAJOR) dp(2f) else dp(1f)
            val inner = proj.screenAngleToPoint(tick.screenAngleDeg, proj.radiusPx)
            val outer = proj.screenAngleToPoint(tick.screenAngleDeg, proj.radiusPx + len)
            canvas.drawLine(inner.x.toFloat(), inner.y.toFloat(), outer.x.toFloat(), outer.y.toFloat(), tickPaint)
            tick.label?.let { label ->
                val textPos = proj.screenAngleToPoint(tick.screenAngleDeg, proj.radiusPx + dp(26f))
                canvas.drawText(label, textPos.x.toFloat() - dp(9f), textPos.y.toFloat() + dp(4f), textPaint)
            }
        }
    }

    private fun drawHeadingLine(canvas: Canvas, proj: PpiProjection) {
        // IEC 62388 §8.2.3.1: heading line from CCRP to the bearing scale.
        val (c, edge) = BearingScale.headingLine(proj)
        canvas.drawLine(c.x.toFloat(), c.y.toFloat(), edge.x.toFloat(), edge.y.toFloat(), headingPaint)
    }

    private fun dp(v: Float): Float = v * resources.displayMetrics.density

    private fun fmtNm(nm: Double): String =
        if (nm >= 1.0) "%.0f".format(nm).let { if (nm % 1.0 == 0.0) it else "%.2f".format(nm) } else "%.2f".format(nm)

    companion object {
        private const val TAG = "PpiView"

        /**
         * Ring of margin (dp) reserved around the operational area for the bearing scale, which IEC
         * 62388 §9.10.2.1 requires to be OUTSIDE the operational display area. Sized for the 12 dp
         * major ticks + ~26 dp numerals; tune with the T2.9 framework chrome.
         */
        private const val BEARING_SCALE_MARGIN_DP = 30f

        /** Range rings / bearing scale colour. TODO(待标准/T2.9): align graphic palette per IEC 62288 §4.5.1. */
        private const val GRAPHIC_COLOR = 0xFF3FA34D.toInt()  // muted green graticule

        /** Heading-line colour, distinct from echoes and rings (IEC 62388 §8.2.3 — not to extinction). */
        private const val HEADING_COLOR = 0xFFE0E0E0.toInt()

        /**
         * Dark non-reflecting PPI background per palette (IEC 62288 §5.4.1.1). T2.1 owns this colour;
         * ColorMapper sample 0 is transparent over it. TODO(待标准: IHO S-52) exact chromaticity.
         */
        fun backgroundColorFor(palette: ColorMapper.Palette): Int = when (palette) {
            ColorMapper.Palette.DAY -> 0xFF04121C.toInt()   // very dark blue-black
            ColorMapper.Palette.DUSK -> 0xFF02080C.toInt()
            ColorMapper.Palette.NIGHT -> 0xFF000000.toInt() // pure black to preserve dark adaptation
        }
    }
}
