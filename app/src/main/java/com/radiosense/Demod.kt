package com.radiosense

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.sin
import kotlin.math.sqrt

enum class Mode(val fa: String) { WFM("FM پهن (رادیو)"), NFM("FM باریک"), AM("AM") }

fun lowpass(n: Int, fc: Double): FloatArray {
    val m = (n - 1) / 2.0
    val h = DoubleArray(n)
    var sum = 0.0
    for (i in 0 until n) {
        val x = i - m
        val s = if (abs(x) < 1e-9) 2 * fc else sin(2 * PI * fc * x) / (PI * x)
        val w = 0.54 - 0.46 * cos(2 * PI * i / (n - 1))
        h[i] = s * w; sum += h[i]
    }
    return FloatArray(n) { (h[it] / sum).toFloat() }
}

/** فیلتر FIR مختلط با کاهش نرخ نمونه‌برداری (جریانی). */
class FirDecim(private val h: FloatArray, private val dec: Int) {
    private val n = h.size
    private var wr = FloatArray(0)
    private var wi = FloatArray(0)
    private val histR = FloatArray(n - 1)
    private val histI = FloatArray(n - 1)
    private var phase = 0

    fun process(inR: FloatArray, inI: FloatArray, len: Int, outR: FloatArray, outI: FloatArray): Int {
        val total = n - 1 + len
        if (wr.size < total) { wr = FloatArray(total); wi = FloatArray(total) }
        System.arraycopy(histR, 0, wr, 0, n - 1); System.arraycopy(histI, 0, wi, 0, n - 1)
        System.arraycopy(inR, 0, wr, n - 1, len); System.arraycopy(inI, 0, wi, n - 1, len)
        var pos = phase
        var o = 0
        while (pos + n <= total) {
            var sr = 0f; var si = 0f
            for (j in 0 until n) { val c = h[j]; sr += c * wr[pos + j]; si += c * wi[pos + j] }
            outR[o] = sr; outI[o] = si; o++
            pos += dec
        }
        System.arraycopy(wr, len, histR, 0, n - 1); System.arraycopy(wi, len, histI, 0, n - 1)
        phase = pos - len
        return o
    }
}

/** دمدولاتور WFM / NFM / AM از جریان IQ هشت‌بیتی rtl_tcp به صدای 48kHz. */
class Demod(private val fs: Int) {
    @Volatile var volume = 0.7f
    var mode = Mode.WFM
        private set

    private val cosT = FloatArray(32) { cos(2 * PI * 5 * it / 32).toFloat() }
    private val sinT = FloatArray(32) { sin(2 * PI * 5 * it / 32).toFloat() }
    private var mixN = 0

    private val iR = FloatArray(16384); private val iI = FloatArray(16384)
    private val c1R = FloatArray(4096); private val c1I = FloatArray(4096)
    private val dR = FloatArray(4096); private val dI = FloatArray(4096)
    private val a2R = FloatArray(1024); private val a2I = FloatArray(1024)

    private var s1 = FirDecim(lowpass(41, 100000.0 / fs), 4)
    private var s2 = FirDecim(lowpass(41, 16000.0 / (fs / 4)), 5)
    private var pRe = 0f; private var pIm = 0f
    private var deemph = 0f; private var avg = 0f; private var dcN = 0f
    private val alpha = (1.0 - exp(-1.0 / (48000.0 * 50e-6))).toFloat()

    init { rebuild() }

    fun setMode(m: Mode) { mode = m; rebuild() }
    fun reset() = rebuild()

    private fun rebuild() {
        s1 = FirDecim(lowpass(41, 100000.0 / fs), 4)
        s2 = if (mode == Mode.WFM) FirDecim(lowpass(41, 16000.0 / (fs / 4)), 5)
        else FirDecim(lowpass(61, 6000.0 / (fs / 4)), 5)
        pRe = 0f; pIm = 0f; deemph = 0f; avg = 0f; dcN = 0f
    }

    private fun sh(x: Float): Short = x.coerceIn(-32768f, 32767f).toInt().toShort()

    fun process(buf: ByteArray, len: Int, out: ShortArray): Int {
        val ns = len / 2
        for (k in 0 until ns) {
            val i0 = ((buf[2 * k].toInt() and 0xFF) - 127.5f) / 127.5f
            val q0 = ((buf[2 * k + 1].toInt() and 0xFF) - 127.5f) / 127.5f
            val idx = (mixN * 5) and 31; mixN++
            val cs = cosT[idx]; val sn = sinT[idx]
            iR[k] = i0 * cs - q0 * sn
            iI[k] = i0 * sn + q0 * cs
        }
        val n1 = s1.process(iR, iI, ns, c1R, c1I)
        val vol = volume * 32767f
        if (mode == Mode.WFM) {
            for (k in 0 until n1) {
                val re = c1R[k]; val im = c1I[k]
                val xr = re * pRe + im * pIm
                val xi = im * pRe - re * pIm
                dR[k] = atan2(xi, xr) * 0.509f
                dI[k] = 0f
                pRe = re; pIm = im
            }
            val n2 = s2.process(dR, dI, n1, a2R, a2I)
            for (k in 0 until n2) {
                deemph += alpha * (a2R[k] - deemph)
                out[k] = sh(deemph * vol)
            }
            return n2
        }
        val n2 = s2.process(c1R, c1I, n1, a2R, a2I)
        for (k in 0 until n2) {
            val re = a2R[k]; val im = a2I[k]
            if (mode == Mode.NFM) {
                val xr = re * pRe + im * pIm
                val xi = im * pRe - re * pIm
                var x = atan2(xi, xr) * 1.528f
                pRe = re; pIm = im
                dcN += 0.0005f * (x - dcN); x -= dcN
                out[k] = sh(x.coerceIn(-1f, 1f) * vol)
            } else {
                val m = sqrt(re * re + im * im)
                avg += 0.001f * (m - avg)
                val v = if (avg > 1e-4f) (m / avg - 1f) else 0f
                out[k] = sh(v.coerceIn(-1f, 1f) * vol)
            }
        }
        return n2
    }
}
