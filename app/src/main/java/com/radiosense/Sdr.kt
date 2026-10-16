package com.radiosense

import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket

const val FS = 960000
const val FFT_N = 1024
const val LO_OFFSET = 150_000L
const val SCAN_FRAMES = 16
const val CHUNK = 16384

class Station(val hz: Long, val db: Float, val snr: Float)
class ScanSpec(val startHz: Long, val stopHz: Long, val stepHz: Long, val halfW: Double)
class Band(val name: String, val a: Double, val b: Double, val stepKHz: Double, val mode: Mode)

/** کلاینت پروتکل rtl_tcp (درایور RTL-SDR اندروید روی 127.0.0.1:1234 سرور می‌سازد). */
class SdrClient {
    private var sock: Socket? = null
    private var inp: InputStream? = null
    private var out: OutputStream? = null
    var tunerType = 0
    private val scratch = ByteArray(16384)

    fun connect(host: String, port: Int) {
        val s = Socket()
        s.connect(InetSocketAddress(host, port), 3000)
        s.soTimeout = 5000
        s.tcpNoDelay = true
        sock = s
        inp = s.getInputStream()
        out = s.getOutputStream()
        val h = ByteArray(12)
        readFully(h, 0, 12)
        if (h[0] != 'R'.code.toByte() || h[1] != 'T'.code.toByte() || h[2] != 'L'.code.toByte())
            throw IOException("پاسخ سرور rtl_tcp نیست")
        tunerType = ((h[4].toInt() and 0xFF) shl 24) or ((h[5].toInt() and 0xFF) shl 16) or
                ((h[6].toInt() and 0xFF) shl 8) or (h[7].toInt() and 0xFF)
    }

    fun tunerName() = when (tunerType) {
        1 -> "E4000"; 2 -> "FC0012"; 3 -> "FC0013"; 4 -> "FC2580"; 5 -> "R820T"; 6 -> "R828D"; else -> "نامشخص"
    }

    fun send(cmd: Int, param: Int) {
        val b = ByteArray(5)
        b[0] = cmd.toByte()
        b[1] = (param ushr 24).toByte(); b[2] = (param ushr 16).toByte()
        b[3] = (param ushr 8).toByte(); b[4] = param.toByte()
        val o = out ?: throw IOException("قطع")
        o.write(b); o.flush()
    }

    fun setFreq(hz: Long) = send(0x01, hz.toInt())
    fun setRate(hz: Int) = send(0x02, hz)
    fun setGainMode(manual: Boolean) = send(0x03, if (manual) 1 else 0)
    fun setGain(tenthsDb: Int) = send(0x04, tenthsDb)
    fun setAgc(on: Boolean) = send(0x08, if (on) 1 else 0)

    fun readFully(b: ByteArray, off: Int, len: Int) {
        val i = inp ?: throw IOException("قطع")
        var got = 0
        while (got < len) {
            val n = i.read(b, off + got, len - got)
            if (n < 0) throw IOException("اتصال بسته شد")
            got += n
        }
    }

    fun skip(bytes: Long) {
        var left = bytes
        while (left > 0) {
            val n = minOf(left, scratch.size.toLong()).toInt()
            readFully(scratch, 0, n)
            left -= n
        }
    }

    fun close() { try { sock?.close() } catch (e: Exception) {} }
}
