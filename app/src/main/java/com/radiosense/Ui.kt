package com.radiosense

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.Shader
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.View

/** ابزارهای ظاهر «شیشه‌ای» (glass) برای کل اپ. */
object Ui {
    private fun dens(ctx: Context) = ctx.resources.displayMetrics.density

    fun glass(
        ctx: Context, radiusDp: Int = 26,
        top: Int = 0x30FFFFFF, bottom: Int = 0x12FFFFFF, stroke: Int = 0x40FFFFFF
    ): GradientDrawable =
        GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(top, bottom)).apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = radiusDp * dens(ctx)
            setStroke(maxOf(1, dens(ctx).toInt()), stroke)
        }

    fun pill(ctx: Context, top: Int = 0x30FFFFFF, bottom: Int = 0x14FFFFFF, stroke: Int = 0x40FFFFFF) =
        glass(ctx, 60, top, bottom, stroke)

    /** دکمه‌ی شیشه‌ای رنگی. */
    fun tinted(ctx: Context, color: Int, radiusDp: Int = 60): GradientDrawable {
        val rgb = color and 0x00FFFFFF
        return glass(ctx, radiusDp, rgb or (0xCC shl 24), rgb or (0x80 shl 24), 0x55FFFFFF)
    }

    fun circle(color: Int, alpha: Int = 0x66): GradientDrawable =
        GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor((color and 0x00FFFFFF) or (alpha shl 24))
        }

    fun thin(): Typeface = Typeface.create("sans-serif-thin", Typeface.NORMAL)
    fun light(): Typeface = Typeface.create("sans-serif-light", Typeface.NORMAL)
}

/** نوار باریک گرد برای نمایش قدرت سیگنال. */
class GlassBar(ctx: Context) : View(ctx) {
    private var pct = 0
    private var col = Color.WHITE
    private val track = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x26FFFFFF }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)

    fun set(p: Int, c: Int) { pct = p.coerceIn(0, 100); col = c; invalidate() }

    override fun onDraw(cv: Canvas) {
        val h = height.toFloat(); val w = width.toFloat(); val r = h / 2
        cv.drawRoundRect(0f, 0f, w, h, r, r, track)
        if (pct > 0) {
            fill.color = col
            cv.drawRoundRect(0f, 0f, maxOf(h, w * pct / 100f), h, r, r, fill)
        }
    }
}

/** پس‌زمینه‌ی تیره با لکه‌های نوری محو، مثل پس‌زمینه‌ی شیشه‌ای تصویر. */
class GlassBg(ctx: Context) : View(ctx) {
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)

    private fun blob(cv: Canvas, cx: Float, cy: Float, r: Float, hex: String) {
        val col = Color.parseColor(hex)
        p.shader = RadialGradient(cx, cy, r, col, col and 0x00FFFFFF, Shader.TileMode.CLAMP)
        cv.drawCircle(cx, cy, r, p)
    }

    override fun onDraw(cv: Canvas) {
        val w = width.toFloat(); val h = height.toFloat()
        p.shader = LinearGradient(
            0f, 0f, 0f, h,
            intArrayOf(Color.parseColor("#0A0F1C"), Color.parseColor("#0B1B2B"), Color.parseColor("#081018")),
            null, Shader.TileMode.CLAMP
        )
        cv.drawRect(0f, 0f, w, h, p)
        blob(cv, w * 0.15f, h * 0.15f, w * 0.85f, "#555B7FA8")
        blob(cv, w * 0.95f, h * 0.55f, w * 0.85f, "#40158A9A")
        blob(cv, w * 0.15f, h * 1.0f, w * 1.0f, "#4D12486B")
        p.shader = null
    }
}
