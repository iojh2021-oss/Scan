package com.radiosense

import android.app.Activity
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.SeekBar
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import java.util.Locale
import java.util.concurrent.ConcurrentLinkedQueue
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.roundToLong

private val BANDS = listOf(
    Band("FM رادیو (87.5–108)", 87.5, 108.0, 100.0, Mode.WFM),
    Band("هوانوردی AM (118–137)", 118.0, 137.0, 25.0, Mode.AM),
    Band("آماتور 2m (144–148)", 144.0, 148.0, 12.5, Mode.NFM),
    Band("دریایی VHF (156–163)", 156.0, 163.0, 25.0, Mode.NFM),
    Band("آماتور 70cm (430–440)", 430.0, 440.0, 25.0, Mode.NFM),
    Band("PMR446 (446–446.2)", 446.0, 446.2, 12.5, Mode.NFM),
    Band("سفارشی", 87.5, 108.0, 100.0, Mode.WFM)
)
private val STEPS = listOf(12.5, 25.0, 50.0, 100.0, 200.0)

class FmTab(private val act: Activity) {
    val root = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL }
    private val ui = Handler(Looper.getMainLooper())
    private val MP = ViewGroup.LayoutParams.MATCH_PARENT
    private val WC = ViewGroup.LayoutParams.WRAP_CONTENT

    @Volatile private var tunedHz = 98_500_000L
    @Volatile private var mode = Mode.WFM
    @Volatile private var gainTenths = 400
    @Volatile private var agc = false
    @Volatile private var volume = 0.7f
    @Volatile private var scanReq: ScanSpec? = null
    @Volatile private var engineScanning = false
    private var sens = 8
    private var stepKHz = 100.0
    private var engine: Engine? = null
    private var resumeConnect = false
    private var audioOn = false
    private var scanning = false
    private var stations: List<Station> = emptyList()
    private var lastChs = LongArray(0)
    private var lastDb = FloatArray(0)

    private lateinit var statusTv: TextView
    private lateinit var hostEt: EditText
    private lateinit var portEt: EditText
    private lateinit var connectBtn: Button
    private lateinit var freqTv: TextView
    private lateinit var modeTv: TextView
    private lateinit var meterTv: TextView
    private lateinit var meterBar: ProgressBar
    private lateinit var spectrum: SpectrumView
    private lateinit var manualEt: EditText
    private lateinit var startEt: EditText
    private lateinit var stopEt: EditText
    private lateinit var modeSp: Spinner
    private lateinit var stepSp: Spinner
    private lateinit var bandSp: Spinner
    private lateinit var scanBtn: Button
    private lateinit var progTv: TextView
    private lateinit var audioBtn: Button
    private lateinit var countTv: TextView
    private lateinit var listBox: LinearLayout

    init { build() }

    // ---------- helpers ----------
    private fun dp(v: Int) = (v * act.resources.displayMetrics.density).toInt()
    private fun c(h: String) = Color.parseColor(h)
    private fun fmt(hz: Long) = String.format(Locale.US, "%.3f", hz / 1e6)
    private fun toast(t: String) = Toast.makeText(act, t, Toast.LENGTH_LONG).show()
    private fun status(t: String) { ui.post { statusTv.text = t } }
    private fun lp(top: Int = 0) = LinearLayout.LayoutParams(MP, WC).apply { topMargin = dp(top) }
    private fun pctColor(p: Int) = if (p >= 60) c("#22C55E") else if (p >= 35) c("#EAB308") else c("#EF4444")

    private fun tv(t: String, size: Float = 14f, color: String = "#E2E8F0", bold: Boolean = false) =
        TextView(act).apply { text = t; textSize = size; setTextColor(c(color)); if (bold) setTypeface(null, Typeface.BOLD) }

    private fun btn(t: String, bg: String = "#334155", f: () -> Unit) = Button(act).apply {
        text = t; isAllCaps = false; setTextColor(Color.WHITE); setBackgroundColor(c(bg)); textSize = 13f
        setOnClickListener { f() }
    }

    private fun edit(text: String, type: Int, h: String = "") = EditText(act).apply {
        setText(text); inputType = type; setTextColor(Color.WHITE); setHintTextColor(c("#64748B")); hint = h
        textSize = 14f; setBackgroundColor(c("#1E293B")); setPadding(dp(10), dp(8), dp(10), dp(8)); setSingleLine()
    }

    private fun hrow(vararg vs: View) = LinearLayout(act).apply {
        orientation = LinearLayout.HORIZONTAL
        vs.forEach { addView(it, LinearLayout.LayoutParams(0, WC, 1f).apply { setMargins(dp(2), dp(2), dp(2), dp(2)) }) }
    }

    private fun section(t: String) { root.addView(tv(t, 15f, "#38BDF8", true), lp(14)) }

    private fun spinner(items: List<String>, sel: Int, onSel: (Int) -> Unit) = Spinner(act).apply {
        adapter = ArrayAdapter(act, android.R.layout.simple_spinner_item, items).also {
            it.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        }
        setSelection(sel, false)
        onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: AdapterView<*>?, v: View?, pos: Int, id: Long) { onSel(pos) }
            override fun onNothingSelected(p: AdapterView<*>?) {}
        }
    }

    private fun seek(label: String, max: Int, prog: Int, onChange: (Int) -> Unit) = SeekBar(act).apply {
        this.max = max; progress = prog
        setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(s: SeekBar?, p: Int, fromUser: Boolean) { onChange(p) }
            override fun onStartTrackingTouch(s: SeekBar?) {}
            override fun onStopTrackingTouch(s: SeekBar?) {}
        })
    }

    // ---------- UI ----------
    private fun build() {
        section("اتصال به دانگل RTL-SDR")
        statusTv = tv("قطع • برای شروع دانگل را با OTG وصل و درایور را Start کنید", 13f, "#FBBF24")
        root.addView(statusTv, lp())
        hostEt = edit("127.0.0.1", InputType.TYPE_CLASS_TEXT)
        portEt = edit("1234", InputType.TYPE_CLASS_NUMBER)
        root.addView(hrow(hostEt, portEt), lp())
        connectBtn = btn("اتصال", "#2563EB") { toggleConnect() }
        root.addView(hrow(connectBtn, btn("باز کردن درایور RTL-SDR") { openDriver() }), lp())

        freqTv = tv(fmt(tunedHz), 46f, "#F8FAFC", true).apply {
            gravity = Gravity.CENTER; typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
        }
        root.addView(freqTv, lp(14))
        modeTv = tv("MHz  •  ${mode.fa}", 14f, "#94A3B8").apply { gravity = Gravity.CENTER }
        root.addView(modeTv, lp())
        meterTv = tv("قدرت: —   SNR: —", 14f, "#E2E8F0").apply { gravity = Gravity.CENTER }
        root.addView(meterTv, lp(6))
        meterBar = ProgressBar(act, null, android.R.attr.progressBarStyleHorizontal).apply { max = 100 }
        root.addView(meterBar, LinearLayout.LayoutParams(MP, dp(10)).apply { topMargin = dp(4) })

        spectrum = SpectrumView(act)
        spectrum.onTap = { hz -> stopScan(); tune((hz / (stepKHz * 1000)).roundToLong() * (stepKHz * 1000).toLong()) }
        root.addView(spectrum, LinearLayout.LayoutParams(MP, dp(220)).apply { topMargin = dp(10) })
        root.addView(hrow(btn("نمای زنده") { spectrum.showSweep = false }, btn("نمای جاروب") { spectrum.showSweep = true }), lp())
        root.addView(tv("روی نمودار بزنید تا همان فرکانس تنظیم شود.", 12f, "#64748B"), lp())

        section("تنظیم دستی")
        root.addView(hrow(
            btn("◀◀ 1MHz") { tuneBy(-1000.0) }, btn("◀ گام") { tuneBy(-stepKHz) },
            btn("گام ▶") { tuneBy(stepKHz) }, btn("1MHz ▶▶") { tuneBy(1000.0) }), lp())
        manualEt = edit("", InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL, "فرکانس MHz مثل 98.5")
        root.addView(hrow(manualEt, btn("برو", "#2563EB") {
            val v = manualEt.text.toString().replace(',', '.').toDoubleOrNull()
            if (v == null || v < 24 || v > 1760) toast("فرکانس بین 24 تا 1760 MHz") else { stopScan(); tune((v * 1e6).roundToLong()) }
        }), lp())
        modeSp = spinner(Mode.values().map { it.fa }, 0) { p -> setMode(Mode.values()[p]) }
        stepSp = spinner(STEPS.map { "گام ${it} kHz" }, 3) { p -> stepKHz = STEPS[p] }
        root.addView(hrow(modeSp, stepSp), lp())

        section("جستجوی خودکار (جاروب طیف)")
        bandSp = spinner(BANDS.map { it.name }, 0) { p -> applyBand(BANDS[p]) }
        root.addView(bandSp, lp())
        startEt = edit("87.5", InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL, "شروع MHz")
        stopEt = edit("108", InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL, "پایان MHz")
        root.addView(hrow(startEt, stopEt), lp())
        scanBtn = btn("🔍 جستجوی خودکار", "#16A34A") { if (scanning) stopScan() else startScan() }
        root.addView(hrow(scanBtn, btn("⏮ قبلی") { seek(-1) }, btn("بعدی ⏭") { seek(1) }), lp())
        progTv = tv("", 12f, "#94A3B8"); root.addView(progTv, lp())

        val sensTv = tv("حساسیت: حداقل SNR برای تشخیص ایستگاه = $sens dB", 13f)
        root.addView(sensTv, lp(8))
        root.addView(seek("", 25, sens) { p ->
            sens = max(3, p); sensTv.text = "حساسیت: حداقل SNR برای تشخیص ایستگاه = $sens dB"; if (lastDb.isNotEmpty()) redetect()
        }, lp())

        val gainTv = tv("گین تیونر: 40 dB", 13f)
        root.addView(gainTv, lp(8))
        root.addView(seek("", 50, 40) { p ->
            gainTenths = p * 10; gainTv.text = "گین تیونر: $p dB"
            engine?.let { e -> e.cmds.add { cl -> e.applyGain(cl) } }
        }, lp())
        root.addView(CheckBox(act).apply {
            text = "AGC خودکار (برای سیگنال‌های خیلی قوی)"; setTextColor(c("#E2E8F0"))
            setOnCheckedChangeListener { _, on -> agc = on; engine?.let { e -> e.cmds.add { cl -> e.applyGain(cl) } } }
        }, lp())

        section("پخش صدا")
        audioBtn = btn("🔊 پخش صدا", "#7C3AED") { toggleAudio() }
        root.addView(audioBtn, lp())
        root.addView(tv("بلندی صدا", 13f), lp(6))
        root.addView(seek("", 100, 70) { p -> volume = p / 100f }, lp())

        section("ایستگاه‌ها / سیگنال‌های یافت‌شده")
        countTv = tv("هنوز جاروبی انجام نشده", 13f, "#94A3B8"); root.addView(countTv, lp())
        listBox = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL }
        root.addView(listBox, lp(6))

        section("راهنما")
        root.addView(tv(
            "• گوشی به‌تنهایی تیونر رادیو ندارد؛ این تب با دانگل RTL-SDR (کابل OTG + آنتن) کار می‌کند.\n" +
            "• اپ «RTL-SDR driver» (marto.rtl_tcp_andro) را نصب کنید، دانگل را وصل و Start بزنید، سپس اینجا «اتصال».\n" +
            "• قدرت‌ها به dBFS (نسبت به سقف دیجیتال) هستند، نه dBm کالیبره. مقایسه‌ی نسبی معتبر است.\n" +
            "• گین خیلی بالا روی ایستگاه قوی باعث سیگنال کاذب می‌شود؛ در صورت دیدن ایستگاه‌های عجیب گین را کم کنید.\n" +
            "• محدوده‌ی دانگل حدوداً 24 تا 1760 MHz است.", 12f, "#94A3B8"), lp())
        root.setPadding(0, 0, 0, dp(24))
    }

    // ---------- actions ----------
    private fun setMode(m: Mode) {
        mode = m
        modeTv.text = "MHz  •  ${m.fa}"
        engine?.let { e -> e.cmds.add { _ -> e.demodSetMode(m) } }
    }

    private fun applyBand(b: Band) {
        if (b.name != "سفارشی") {
            startEt.setText(b.a.toString()); stopEt.setText(b.b.toString())
            val si = STEPS.indexOf(b.stepKHz); if (si >= 0) { stepSp.setSelection(si); stepKHz = b.stepKHz }
            modeSp.setSelection(Mode.values().indexOf(b.mode)); setMode(b.mode)
        }
    }

    private fun tune(hz: Long) {
        val f = hz.coerceIn(24_000_000L, 1_760_000_000L)
        tunedHz = f
        freqTv.text = fmt(f)
        spectrum.setMarker(f)
        engine?.let { e -> e.cmds.add { cl -> e.retune(cl) } }
    }

    private fun tuneBy(kHz: Double) { stopScan(); tune(tunedHz + (kHz * 1000).roundToLong()) }

    private fun toggleConnect() { if (engine != null) disconnect() else connect() }

    private fun connect() {
        val host = hostEt.text.toString().trim().ifEmpty { "127.0.0.1" }
        val port = portEt.text.toString().toIntOrNull() ?: 1234
        val e = Engine(host, port)
        engine = e
        connectBtn.text = "قطع اتصال"
        e.start()
    }

    private fun disconnect() {
        scanReq = null
        engine?.stopEngine()
        engine = null
        connectBtn.text = "اتصال"
        audioOn = false; audioBtn.text = "🔊 پخش صدا"
        statusTv.text = "قطع"
    }

    fun pause() { resumeConnect = engine != null; if (engine != null) disconnect() }
    fun resume() { if (resumeConnect) { resumeConnect = false; connect() } }

    private fun openDriver() {
        val port = portEt.text.toString().toIntOrNull() ?: 1234
        try {
            act.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("iqsrc://-a 127.0.0.1 -p $port -s $FS")))
            toast("در درایور اگر Start نشد، دستی Start بزنید؛ بعد به این اپ برگردید و «اتصال» را بزنید.")
        } catch (e: Exception) {
            val li = act.packageManager.getLaunchIntentForPackage("marto.rtl_tcp_andro")
            try {
                if (li != null) act.startActivity(li)
                else act.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://play.google.com/store/apps/details?id=marto.rtl_tcp_andro")))
            } catch (e2: Exception) { toast("اپ RTL-SDR driver را نصب کنید.") }
        }
    }

    private fun toggleAudio() {
        if (engine == null) { toast("اول متصل شوید"); return }
        audioOn = !audioOn
        audioBtn.text = if (audioOn) "🔇 قطع صدا" else "🔊 پخش صدا"
        val on = audioOn
        engine?.let { e -> e.cmds.add { _ -> e.setAudio(on) } }
    }

    private fun startScan() {
        if (engine == null) { toast("اول به دانگل متصل شوید"); return }
        val a = startEt.text.toString().replace(',', '.').toDoubleOrNull()
        val b = stopEt.text.toString().replace(',', '.').toDoubleOrNull()
        if (a == null || b == null || a < 24 || b > 1760 || b <= a) { toast("محدوده نامعتبر (24 تا 1760 MHz)"); return }
        val stepHz = (stepKHz * 1000).toLong()
        val count = ((b - a) * 1e6 / stepHz).toInt() + 1
        if (count > 40000) { toast("محدوده خیلی بزرگ است؛ گام را بزرگ‌تر کنید"); return }
        val halfW = if (stepHz >= 100_000) 60_000.0 else stepHz * 0.45
        scanning = true
        scanBtn.text = "⏹ توقف جاروب"
        spectrum.showSweep = true
        listBox.removeAllViews(); countTv.text = "در حال جاروب…"
        scanReq = ScanSpec((a * 1e6).roundToLong(), (b * 1e6).roundToLong(), stepHz, halfW)
    }

    private fun stopScan() {
        scanReq = null
        if (scanning && !engineScanning) { scanning = false; scanBtn.text = "🔍 جستجوی خودکار" }
    }

    private fun seek(dir: Int) {
        val sorted = stations.sortedBy { it.hz }
        if (sorted.isEmpty()) { toast("ابتدا «جستجوی خودکار» را بزنید"); return }
        val cur = tunedHz
        val tol = (stepKHz * 500).toLong()
        val next = if (dir > 0) (sorted.firstOrNull { it.hz > cur + tol } ?: sorted.first())
        else (sorted.lastOrNull { it.hz < cur - tol } ?: sorted.last())
        stopScan(); tune(next.hz)
    }

    private fun detect(chs: LongArray, db: FloatArray, sensDb: Int): List<Station> {
        val valid = db.filter { !it.isNaN() }.sorted()
        if (valid.isEmpty()) return emptyList()
        val noise = valid[valid.size / 2]
        val res = ArrayList<Station>()
        for (k in db.indices) {
            val v = db[k]
            if (v.isNaN() || v - noise < sensDb) continue
            val l = if (k > 0 && !db[k - 1].isNaN()) db[k - 1] else Float.NEGATIVE_INFINITY
            val r = if (k < db.size - 1 && !db[k + 1].isNaN()) db[k + 1] else Float.NEGATIVE_INFINITY
            if (v > l && v >= r) res.add(Station(chs[k], v, v - noise))
        }
        return res
    }

    private fun redetect() {
        stations = detect(lastChs, lastDb, sens)
        val sorted = stations.sortedBy { it.hz }
        listBox.removeAllViews()
        countTv.text = "${sorted.size} ایستگاه/سیگنال یافت شد (SNR ≥ $sens dB)"
        sorted.forEach { listBox.addView(stationRow(it), LinearLayout.LayoutParams(MP, WC).apply { bottomMargin = dp(6) }) }
        spectrum.setStations(sorted)
    }

    private fun stationRow(s: Station): View {
        val box = LinearLayout(act).apply {
            orientation = LinearLayout.VERTICAL; setPadding(dp(12), dp(8), dp(12), dp(8)); setBackgroundColor(c("#1E293B"))
            isClickable = true; setOnClickListener { stopScan(); tune(s.hz) }
        }
        val pct = (s.snr * 100 / 40).roundToInt().coerceIn(0, 100)
        val top = LinearLayout(act).apply { orientation = LinearLayout.HORIZONTAL }
        top.addView(tv("${fmt(s.hz)} MHz", 16f, "#F8FAFC", true), LinearLayout.LayoutParams(0, WC, 1f))
        top.addView(TextView(act).apply {
            text = String.format(Locale.US, "SNR %.0f dB", s.snr); textSize = 14f; setTextColor(pctColor(pct)); setTypeface(null, Typeface.BOLD)
        })
        box.addView(top)
        box.addView(tv(String.format(Locale.US, "قدرت %.1f dBFS", s.db), 12f, "#94A3B8"))
        box.addView(ProgressBar(act, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100; progress = pct; progressTintList = ColorStateList.valueOf(pctColor(pct))
        }, LinearLayout.LayoutParams(MP, dp(6)).apply { topMargin = dp(4) })
        return box
    }

    private fun onScanDone(chs: LongArray, db: FloatArray, completed: Boolean) {
        lastChs = chs; lastDb = db
        scanning = false
        scanBtn.text = "🔍 جستجوی خودکار"
        spectrum.setSweep(chs, db, chs.first(), chs.last())
        redetect()
        progTv.text = if (completed) "جاروب کامل شد" else "جاروب متوقف شد (نتایج ناقص)"
        spectrum.showSweep = true
        if (completed && stations.isNotEmpty()) tune(stations.maxByOrNull { it.snr }!!.hz)
    }

    private fun showMeter(ch: Float, snr: Float) {
        val pct = (snr * 100 / 40).roundToInt().coerceIn(0, 100)
        meterTv.text = String.format(Locale.US, "قدرت: %.1f dBFS   •   SNR: %.1f dB", ch, snr)
        meterBar.progress = pct
        meterBar.progressTintList = ColorStateList.valueOf(pctColor(pct))
    }

    // ---------- engine ----------
    private inner class Engine(val host: String, val port: Int) : Thread("sdr-engine") {
        @Volatile var alive = true
        val cmds = ConcurrentLinkedQueue<(SdrClient) -> Unit>()
        @Volatile var client: SdrClient? = null
        private val buf = ByteArray(FFT_N * 2 * SCAN_FRAMES)
        private val pcm = ShortArray(2048)
        private var audio: AudioTrack? = null
        private val demod = Demod(FS)
        private val re = FloatArray(FFT_N)
        private val im = FloatArray(FFT_N)
        private val acc = FloatArray(FFT_N)
        private val win = FloatArray(FFT_N) { (0.5 - 0.5 * cos(2.0 * PI * it / (FFT_N - 1))).toFloat() }
        private val sumW = win.sum()
        private var disp: FloatArray? = null
        private var lastUi = 0L
        private val binHz = FS.toDouble() / FFT_N

        fun stopEngine() { alive = false; client?.close() }

        fun applyGain(c: SdrClient) {
            if (agc) { c.setGainMode(false); c.setAgc(true) }
            else { c.setAgc(false); c.setGainMode(true); c.setGain(gainTenths) }
        }

        fun retune(c: SdrClient) {
            c.setFreq(tunedHz + LO_OFFSET)
            c.skip((FS * 2 * 0.1).toLong())
            demod.reset(); disp = null
        }

        fun demodSetMode(m: Mode) { demod.setMode(m) }

        fun setAudio(on: Boolean) {
            if (on && audio == null) {
                val minBuf = AudioTrack.getMinBufferSize(48000, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT)
                val t = AudioTrack.Builder()
                    .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build())
                    .setAudioFormat(AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(48000).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
                    .setBufferSizeInBytes(max(minBuf, 48000))
                    .setTransferMode(AudioTrack.MODE_STREAM).build()
                t.play(); audio = t
            } else if (!on) releaseAudio()
        }

        private fun releaseAudio() { try { audio?.stop(); audio?.release() } catch (e: Exception) {}; audio = null }

        private fun drain(c: SdrClient) { while (true) { val r = cmds.poll() ?: break; r(c) } }

        override fun run() {
            val c = SdrClient()
            try {
                status("در حال اتصال به $host:$port …")
                c.connect(host, port)
                client = c
                c.setRate(FS)
                applyGain(c)
                demod.setMode(mode)
                retune(c)
                status("متصل ✔ • تیونر ${c.tunerName()} • نرخ ${FS / 1000} kHz")
                while (alive) {
                    drain(c)
                    if (scanReq != null) doScan(c) else doTuned(c)
                }
            } catch (e: Exception) {
                if (alive) status("قطع/خطا: ${e.message ?: e.javaClass.simpleName}\nدرایور RTL-SDR را Start کنید و دوباره «اتصال» بزنید.")
            } finally {
                releaseAudio(); c.close()
                val was = scanning
                scanReq = null
                ui.post {
                    if (engine === this@Engine) {
                        engine = null; connectBtn.text = "اتصال"; audioOn = false; audioBtn.text = "🔊 پخش صدا"
                        if (was) { scanning = false; scanBtn.text = "🔍 جستجوی خودکار" }
                    }
                }
            }
        }

        private fun analyze(frames: Int): FloatArray {
            java.util.Arrays.fill(acc, 0f)
            for (f in 0 until frames) {
                val base = f * FFT_N * 2
                for (k in 0 until FFT_N) {
                    re[k] = ((buf[base + 2 * k].toInt() and 0xFF) - 127.5f) / 127.5f * win[k]
                    im[k] = ((buf[base + 2 * k + 1].toInt() and 0xFF) - 127.5f) / 127.5f * win[k]
                }
                Fft.fft(re, im)
                for (k in 0 until FFT_N) acc[k] += re[k] * re[k] + im[k] * im[k]
            }
            val norm = 1f / (frames * sumW * sumW)
            return FloatArray(FFT_N) { m -> acc[(m + FFT_N / 2) % FFT_N] * norm }
        }

        private fun chanDb(p: FloatArray, offHz: Double, halfW: Double): Float {
            val lo = ((offHz - halfW) / binHz).roundToInt() + FFT_N / 2
            val hi = ((offHz + halfW) / binHz).roundToInt() + FFT_N / 2
            var s = 0.0
            for (m in max(1, lo)..min(FFT_N - 1, hi)) s += p[m]
            return (10 * log10(s + 1e-12)).toFloat()
        }

        private fun noiseDb(p: FloatArray, halfW: Double): Float {
            val seg = p.copyOfRange(80, FFT_N - 80); seg.sort()
            val med = seg[seg.size / 2].toDouble()
            return (10 * log10(med * (2 * halfW / binHz + 1) + 1e-12)).toFloat()
        }

        private fun doTuned(c: SdrClient) {
            c.readFully(buf, 0, CHUNK)
            val a = audio
            if (a != null) {
                demod.volume = volume
                val n = demod.process(buf, CHUNK, pcm)
                a.write(pcm, 0, n)
            }
            val now = System.nanoTime()
            if (now - lastUi > 90_000_000L) {
                lastUi = now
                val p = analyze(CHUNK / (FFT_N * 2))
                val halfW = if (mode == Mode.WFM) 60_000.0 else 8_000.0
                val ch = chanDb(p, -LO_OFFSET.toDouble(), halfW)
                val snr = ch - noiseDb(p, halfW)
                val cur = disp
                val nd = FloatArray(FFT_N) { m ->
                    val v = 10f * log10(p[m] + 1e-12f)
                    if (cur == null) v else cur[m] * 0.6f + v * 0.4f
                }
                disp = nd
                val f = tunedHz
                ui.post { spectrum.setLive(nd, f + LO_OFFSET, FS, f); showMeter(ch, snr) }
            }
        }

        private fun doScan(c: SdrClient) {
            val spec = scanReq ?: return
            engineScanning = true
            val count = ((spec.stopHz - spec.startHz) / spec.stepHz).toInt() + 1
            val chs = LongArray(count) { spec.startHz + it * spec.stepHz }
            val db = FloatArray(count) { Float.NaN }
            var i = 0
            var lastP = 0L
            while (i < count && alive && scanReq === spec) {
                drain(c)
                val f0 = chs[i]
                val center = f0 - (70_000L + spec.halfW.toLong())
                c.setFreq(center)
                c.skip((FS * 2 * 0.1).toLong())
                c.readFully(buf, 0, buf.size)
                val p = analyze(SCAN_FRAMES)
                var k = i
                while (k < count) {
                    val off = (chs[k] - center).toDouble()
                    if (off + spec.halfW > 400_000.0) break
                    db[k] = chanDb(p, off, spec.halfW)
                    k++
                }
                if (k == i) k = i + 1
                i = k
                val now = System.nanoTime()
                if (now - lastP > 150_000_000L) {
                    lastP = now
                    val snap = db.clone(); val pc = i * 100 / count; val fz = f0
                    ui.post {
                        progTv.text = "جاروب: $pc%  •  ${fmt(fz)} MHz"
                        spectrum.setSweep(chs, snap, spec.startHz, spec.stopHz)
                    }
                }
            }
            engineScanning = false
            val completed = alive && scanReq === spec
            if (scanReq === spec) scanReq = null
            if (alive) {
                retune(c)
                val snap = db.clone()
                ui.post { onScanDone(chs, snap, completed) }
            }
        }
    }
}
