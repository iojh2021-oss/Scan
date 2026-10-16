package com.radiosense

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.view.MotionEvent
import android.view.View
import java.util.Locale
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.max

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

    private fun paint(color: String, style: Paint.Style = Paint.Style.FILL, sw: Float = 1f) =
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            this.color = Color.parseColor(color); this.style = style; strokeWidth = sw * d; textSize = 10f * d
        }

    private val pLine = paint("#38BDF8", Paint.Style.STROKE, 1.5f)
    private val pFill = paint("#5538BDF8")
    private val pGrid = paint("#1E293B", Paint.Style.STROKE, 1f)
    private val pText = paint("#94A3B8")
    private val pMark = paint("#EF4444", Paint.Style.STROKE, 2f)
    private val pSt = paint("#22C55E", Paint.Style.STROKE, 1.5f)
    private val pStT = paint("#86EFAC")

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
        cv.drawColor(Color.parseColor("#0B1220"))
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

        var g = (floor(yMin / 10f) * 10f)
        while (g <= yMax) {
            if (g >= yMin) { val y = yOf(g); cv.drawLine(0f, y, w, y, pGrid); cv.drawText(g.toInt().toString(), 2 * d, y - 2 * d, pText) }
            g += 10f
        }

        val lo: Long; val hi: Long
        if (sweep) { lo = sStart; hi = sStop } else { lo = liveCenter - liveSpan / 2; hi = liveCenter + liveSpan / 2 }
        fun xOf(hz: Long) = if (hi > lo) (hz - lo).toFloat() / (hi - lo).toFloat() * w else 0f

        if (sweep) {
            val chs = sChs; val db = sDb
            val path = Path(); var started = false; var lastX = 0f
            for (i in db.indices) {
                if (db[i].isNaN()) continue
                val x = xOf(chs[i]); val y = yOf(db[i])
                if (!started) { path.moveTo(x, bottom); path.lineTo(x, y); started = true } else path.lineTo(x, y)
                lastX = x
            }
            if (started) { val fill = Path(path); fill.lineTo(lastX, bottom); fill.close(); cv.drawPath(fill, pFill); cv.drawPath(path, pLine) }
            var lastLabel = -1000f
            for (s in sSt) {
                val x = xOf(s.hz); val y = yOf(s.db)
                cv.drawLine(x, y, x, bottom, pSt)
                if (x - lastLabel > 40 * d) { cv.drawText(mhz(s.hz), x - 12 * d, max(y - 4 * d, top + 8 * d), pStT); lastLabel = x }
            }
        } else if (live.isNotEmpty()) {
            val n = live.size
            val path = Path()
            for (m in 0 until n) {
                val x = m.toFloat() / (n - 1) * w; val y = yOf(live[m])
                if (m == 0) { path.moveTo(x, bottom); path.lineTo(x, y) } else path.lineTo(x, y)
            }
            val fill = Path(path); fill.lineTo(w, bottom); fill.close()
            cv.drawPath(fill, pFill); cv.drawPath(path, pLine)
        }

        val mk = marker
        if (mk in lo..hi) { val x = xOf(mk); cv.drawLine(x, top, x, bottom, pMark); cv.drawText(mhz(mk), x + 3 * d, top + 10 * d, pText) }

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
