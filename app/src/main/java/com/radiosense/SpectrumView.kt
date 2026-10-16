package com.radiosense

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.view.MotionEvent
import android.view.View
import java.util.Locale
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

/** نمایش طیف به شکل میله‌های گرد باریک (شبیه موج صوتی). */
class SpectrumView(ctx: Context) : View(ctx) {
    var onTap: ((Long) -> Unit)? = null
    @Volatile var showSweep = false
        set(v) { field = v; postInvalidate() }

    @Volatile private var live = FloatArray(0)
    @Volatile private var liveCenter = 0L
    @Volatile private var liveSpan = 1
    @Volatile private var marker = 0L
    @Volatile private var sChs = LongArray(0)
    @Volatile private var sDb = FloatArray(0)
    @Volatile private var sStart = 0L
    @Volatile private var sStop = 1L
    @Volatile private var sSt: List<Station> = emptyList()
    private var yMin = -100f
    private var yMax = -20f
    private val d = ctx.resources.displayMetrics.density
    private val rect = RectF()

    private fun paint(color: String, style: Paint.Style = Paint.Style.FILL, sw: Float = 1f) =
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            this.color = Color.parseColor(color); this.style = style; strokeWidth = sw * d; textSize = 10f * d
        }

    private val pBar = paint("#D9FFFFFF")
    private val pAcc = paint("#5EEAD4")
    private val pSt = paint("#86EFAC")
    private val pGrid = paint("#1AFFFFFF", Paint.Style.STROKE, 1f)
    private val pText = paint("#99B6C6D8")
    private val pStT = paint("#C7F9D9")

    fun setLive(db: FloatArray, center: Long, span: Int, mark: Long) {
        live = db; liveCenter = center; liveSpan = span; marker = mark; postInvalidate()
    }

    fun setSweep(chs: LongArray, db: FloatArray, start: Long, stop: Long) {
        sChs = chs; sDb = db; sStart = start; sStop = if (stop > start) stop else start + 1; postInvalidate()
    }

    fun setStations(s: List<Station>) { sSt = s; postInvalidate() }
    fun setMarker(m: Long) { marker = m; postInvalidate() }

    private fun mhz(hz: Long) = String.format(Locale.US, "%.2f", hz / 1e6)

    override fun onDraw(cv: Canvas) {
        val w = width.toFloat(); val h = height.toFloat()
        val top = 6 * d; val bottom = h - 18 * d
        val sweep = showSweep
        val data = if (sweep) sDb else live
        var mn = Float.MAX_VALUE; var mx = -Float.MAX_VALUE
        for (v in data) if (!v.isNaN()) { if (v < mn) mn = v; if (v > mx) mx = v }
        if (mn > mx) { mn = -100f; mx = -20f }
        val tmin = mn - 4f
        val tmax = max(mx + 6f, tmin + 30f)
        yMin += (tmin - yMin) * 0.3f; yMax += (tmax - yMax) * 0.3f
        val range = yMax - yMin
        fun yOf(v: Float) = bottom - (v - yMin) / range * (bottom - top)

        var g = floor(yMin / 10f) * 10f
        while (g <= yMax) {
            if (g >= yMin) {
                val y = yOf(g)
                cv.drawLine(0f, y, w, y, pGrid)
                cv.drawText(g.toInt().toString(), 2 * d, y - 2 * d, pText)
            }
            g += 10f
        }

        val lo: Long; val hi: Long
        if (sweep) { lo = sStart; hi = sStop } else { lo = liveCenter - liveSpan / 2; hi = liveCenter + liveSpan / 2 }
        val span = (hi - lo).toFloat().coerceAtLeast(1f)
        fun xOf(hz: Long) = (hz - lo).toFloat() / span * w

        val nBars = 72
        val gap = 2.5f * d
        val bw = max(2f * d, (w - gap * (nBars - 1)) / nBars)
        val stepX = (w - bw) / (nBars - 1)
        val mk = marker
        val stations = sSt
        val len = data.size
        for (b in 0 until nBars) {
            var m = -Float.MAX_VALUE
            if (len > 0) {
                val i0 = (b.toLong() * len / nBars).toInt()
                val i1 = max(i0 + 1, ((b + 1).toLong() * len / nBars).toInt())
                for (i in i0 until min(i1, len)) { val v = data[i]; if (!v.isNaN() && v > m) m = v }
            }
            val bh = if (m == -Float.MAX_VALUE) 3f * d
            else min(bottom - top, max(3f * d, (m - yMin) / range * (bottom - top)))
            val x = b * stepX
            val f0 = lo + (span * b / nBars).toLong()
            val f1 = lo + (span * (b + 1) / nBars).toLong()
            val p = when {
                mk in f0..f1 -> pAcc
                sweep && stations.any { it.hz in f0..f1 } -> pSt
                else -> pBar
            }
            rect.set(x, bottom - bh, x + bw, bottom)
            cv.drawRoundRect(rect, bw / 2, bw / 2, p)
        }

        if (sweep) {
            var lastLabel = -1000f
            for (s in stations) {
                val x = xOf(s.hz); val y = yOf(s.db)
                if (x - lastLabel > 40 * d) {
                    cv.drawText(mhz(s.hz), x - 12 * d, max(y - 6 * d, top + 8 * d), pStT); lastLabel = x
                }
            }
        }
        if (mk in lo..hi) cv.drawText(mhz(mk), min(xOf(mk) + 3 * d, w - 40 * d), top + 10 * d, pAcc)

        cv.drawText(mhz(lo), 2 * d, h - 4 * d, pText)
        val mid = mhz((lo + hi) / 2); cv.drawText(mid, w / 2 - pText.measureText(mid) / 2, h - 4 * d, pText)
        val r = mhz(hi) + " MHz"; cv.drawText(r, w - pText.measureText(r) - 2 * d, h - 4 * d, pText)
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (e.action == MotionEvent.ACTION_DOWN) return true
        if (e.action == MotionEvent.ACTION_UP) {
            val f = (e.x / width).coerceIn(0f, 1f)
            val lo: Long; val hi: Long
            if (showSweep) { lo = sStart; hi = sStop } else { lo = liveCenter - liveSpan / 2; hi = liveCenter + liveSpan / 2 }
            var hz = lo + ((hi - lo) * f).toLong()
            if (showSweep) {
                val near = sSt.minByOrNull { abs(it.hz - hz) }
                if (near != null && abs(near.hz - hz).toFloat() / (hi - lo) * width < 24 * d) hz = near.hz
            }
            onTap?.invoke(hz)
            performClick()
            return true
        }
        return super.onTouchEvent(e)
    }

    override fun performClick(): Boolean { super.performClick(); return true }
}
