package com.radiosense

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

object Fft {
    private val cache = HashMap<Int, Pair<FloatArray, FloatArray>>()

    private fun tables(n: Int): Pair<FloatArray, FloatArray> = cache.getOrPut(n) {
        Pair(FloatArray(n / 2) { cos(-2.0 * PI * it / n).toFloat() },
            FloatArray(n / 2) { sin(-2.0 * PI * it / n).toFloat() })
    }

    @Synchronized
    fun fft(re: FloatArray, im: FloatArray) {
        val n = re.size
        val (cs, sn) = tables(n)
        var j = 0
        for (i in 1 until n) {
            var bit = n shr 1
            while ((j and bit) != 0) { j = j xor bit; bit = bit shr 1 }
            j = j xor bit
            if (i < j) {
                val tr = re[i]; re[i] = re[j]; re[j] = tr
                val ti = im[i]; im[i] = im[j]; im[j] = ti
            }
        }
        var len = 2
        while (len <= n) {
            val half = len / 2
            val step = n / len
            var i = 0
            while (i < n) {
                var k = 0
                for (m in 0 until half) {
                    val wr = cs[k]; val wi = sn[k]
                    val a = i + m; val b = a + half
                    val tr = re[b] * wr - im[b] * wi
                    val ti = re[b] * wi + im[b] * wr
                    re[b] = re[a] - tr; im[b] = im[a] - ti
                    re[a] += tr; im[a] += ti
                    k += step
                }
                i += len
            }
            len = len shl 1
        }
    }
}
