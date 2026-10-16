package com.radiosense

import android.Manifest
import android.app.Activity
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanSettings
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.location.GnssStatus
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.net.wifi.ScanResult
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.telephony.CellIdentityNr
import android.telephony.CellInfo
import android.telephony.CellInfoCdma
import android.telephony.CellInfoGsm
import android.telephony.CellInfoLte
import android.telephony.CellInfoNr
import android.telephony.CellInfoWcdma
import android.telephony.TelephonyManager
import android.view.View
import android.view.ViewGroup
import android.widget.HorizontalScrollView
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import kotlin.math.sqrt

class BtDev(var name: String, var rssi: Int, var ble: Boolean, var last: Long)
class Sat(val con: Int, val svid: Int, val cn0: Float, val used: Boolean)

@Suppress("DEPRECATION")
class MainActivity : Activity() {

    private val MP = ViewGroup.LayoutParams.MATCH_PARENT
    private val WC = ViewGroup.LayoutParams.WRAP_CONTENT

    private val ui = Handler(Looper.getMainLooper())
    private lateinit var wifi: WifiManager
    private var btAdapter: BluetoothAdapter? = null
    private lateinit var lm: LocationManager
    private lateinit var sm: SensorManager

    private var wifiList: List<ScanResult> = emptyList()
    private val btMap = LinkedHashMap<String, BtDev>()
    private var cells: List<CellInfo> = emptyList()
    private var sats: List<Sat> = emptyList()
    private var mag = 0f
    private var magMax = 0f
    private var tab = 0
    private var running = false
    private var asked = false
    private var lastWifi = 0L
    private var lastCell = 0L
    private var lastDisc = 0L

    private lateinit var summary: TextView
    private lateinit var content: LinearLayout
    private lateinit var tabRow: LinearLayout
    private lateinit var fm: FmTab

    private val tabNames = listOf("خلاصه", "رادیو", "وای‌فای", "بلوتوث", "سلولی", "ماهواره", "مغناطیس", "درباره")

    // ---------- lifecycle ----------
    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        wifi = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        btAdapter = (getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter
        lm = getSystemService(Context.LOCATION_SERVICE) as LocationManager
        sm = getSystemService(Context.SENSOR_SERVICE) as SensorManager
        buildUi()
        fm = FmTab(this)
    }

    private fun needed(): List<String> {
        val l = mutableListOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.READ_PHONE_STATE)
        if (Build.VERSION.SDK_INT >= 31) {
            l.add(Manifest.permission.BLUETOOTH_SCAN)
            l.add(Manifest.permission.BLUETOOTH_CONNECT)
        }
        if (Build.VERSION.SDK_INT >= 33) l.add(Manifest.permission.NEARBY_WIFI_DEVICES)
        return l
    }

    private fun missing() = needed().filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }

    override fun onResume() {
        super.onResume()
        val m = missing()
        if (m.isNotEmpty() && !asked) {
            asked = true
            requestPermissions(m.toTypedArray(), 1)
        }
        start()
        fm.resume()
        ui.post(tick)
    }

    override fun onPause() {
        super.onPause()
        ui.removeCallbacks(tick)
        fm.pause()
        stop()
    }

    override fun onRequestPermissionsResult(rc: Int, p: Array<out String>, r: IntArray) {
        super.onRequestPermissionsResult(rc, p, r)
        stop(); start()
    }

    // ---------- receivers / callbacks ----------
    private val wifiRx = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) { wifiList = safeWifi() }
    }

    private val btRx = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) {
            when (i.action) {
                BluetoothDevice.ACTION_FOUND -> {
                    val d: BluetoothDevice? = if (Build.VERSION.SDK_INT >= 33)
                        i.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
                    else i.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
                    val rssi = i.getShortExtra(BluetoothDevice.EXTRA_RSSI, Short.MIN_VALUE)
                    if (d != null && rssi != Short.MIN_VALUE) addBt(d.address, safeName(d), rssi.toInt(), false)
                }
                BluetoothAdapter.ACTION_DISCOVERY_FINISHED -> lastDisc = 0
            }
        }
    }

    private val bleCb = object : ScanCallback() {
        override fun onScanResult(t: Int, r: android.bluetooth.le.ScanResult) = handleBle(r)
        override fun onBatchScanResults(l: MutableList<android.bluetooth.le.ScanResult>) { l.forEach { handleBle(it) } }
    }

    private fun handleBle(r: android.bluetooth.le.ScanResult) {
        val n = r.scanRecord?.deviceName ?: safeName(r.device)
        addBt(r.device.address, n, r.rssi, true)
    }

    private fun safeName(d: BluetoothDevice): String? = try { d.name } catch (e: SecurityException) { null }

    private fun addBt(addr: String, name: String?, rssi: Int, ble: Boolean) {
        val now = SystemClock.elapsedRealtime()
        val e = btMap[addr]
        if (e == null) btMap[addr] = BtDev(name ?: "", rssi, ble, now)
        else {
            e.rssi = rssi; e.last = now
            if (!name.isNullOrEmpty()) e.name = name
            if (ble) e.ble = true
        }
    }

    private val gnssCb = object : GnssStatus.Callback() {
        override fun onSatelliteStatusChanged(s: GnssStatus) {
            sats = (0 until s.satelliteCount).map {
                Sat(s.getConstellationType(it), s.getSvid(it), s.getCn0DbHz(it), s.usedInFix(it))
            }
        }
    }

    private val locL = object : LocationListener {
        override fun onLocationChanged(loc: Location) {}
    }

    private val magL = object : SensorEventListener {
        override fun onSensorChanged(e: SensorEvent) {
            mag = sqrt(e.values[0] * e.values[0] + e.values[1] * e.values[1] + e.values[2] * e.values[2])
            if (mag > magMax) magMax = mag
        }
        override fun onAccuracyChanged(s: Sensor?, a: Int) {}
    }

    // ---------- start / stop ----------
    private fun reg(r: BroadcastReceiver, f: IntentFilter) {
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(r, f, Context.RECEIVER_EXPORTED) else registerReceiver(r, f)
    }

    private fun start() {
        if (running) return
        running = true
        try { reg(wifiRx, IntentFilter(WifiManager.SCAN_RESULTS_AVAILABLE_ACTION)) } catch (e: Exception) {}
        try {
            reg(btRx, IntentFilter().apply {
                addAction(BluetoothDevice.ACTION_FOUND)
                addAction(BluetoothAdapter.ACTION_DISCOVERY_FINISHED)
            })
        } catch (e: Exception) {}
        wifiList = safeWifi()
        try {
            val a = btAdapter
            if (a != null && a.isEnabled) {
                a.bluetoothLeScanner?.startScan(null,
                    ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build(), bleCb)
                a.startDiscovery()
            }
        } catch (e: Exception) {}
        try {
            lm.registerGnssStatusCallback(gnssCb, ui)
            lm.requestLocationUpdates(LocationManager.GPS_PROVIDER, 1000L, 0f, locL)
        } catch (e: Exception) {}
        sm.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD)?.let {
            sm.registerListener(magL, it, SensorManager.SENSOR_DELAY_UI)
        }
        lastWifi = 0; lastCell = 0; lastDisc = SystemClock.elapsedRealtime()
    }

    private fun stop() {
        if (!running) return
        running = false
        try { unregisterReceiver(wifiRx) } catch (e: Exception) {}
        try { unregisterReceiver(btRx) } catch (e: Exception) {}
        try { btAdapter?.bluetoothLeScanner?.stopScan(bleCb) } catch (e: Exception) {}
        try { btAdapter?.cancelDiscovery() } catch (e: Exception) {}
        try { lm.unregisterGnssStatusCallback(gnssCb) } catch (e: Exception) {}
        try { lm.removeUpdates(locL) } catch (e: Exception) {}
        try { sm.unregisterListener(magL) } catch (e: Exception) {}
    }

    private fun safeWifi(): List<ScanResult> = try { wifi.scanResults ?: emptyList() } catch (e: SecurityException) { emptyList() }

    private fun refreshCells() {
        try {
            val tm = getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager
            cells = tm.allCellInfo ?: emptyList()
            if (Build.VERSION.SDK_INT >= 29) {
                tm.requestCellInfoUpdate(mainExecutor, object : TelephonyManager.CellInfoCallback() {
                    override fun onCellInfo(l: MutableList<CellInfo>) { cells = l }
                    override fun onError(code: Int, t: Throwable?) {}
                })
            }
        } catch (e: Exception) {}
    }

    private val tick = object : Runnable {
        override fun run() {
            val n = SystemClock.elapsedRealtime()
            if (n - lastWifi > 12000) { lastWifi = n; try { wifi.startScan() } catch (e: Exception) {} }
            wifiList = safeWifi()
            if (n - lastCell > 5000) { lastCell = n; refreshCells() }
            if (n - lastDisc > 25000) {
                lastDisc = n
                try { btAdapter?.let { if (it.isEnabled && !it.isDiscovering) it.startDiscovery() } } catch (e: Exception) {}
            }
            render()
            ui.postDelayed(this, 1500)
        }
    }

    // ---------- UI helpers ----------
    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
    private fun c(h: String) = Color.parseColor(h)
    private fun pctColor(p: Int) = if (p >= 60) c("#22C55E") else if (p >= 35) c("#EAB308") else c("#EF4444")

    private fun label(t: String, size: Float = 14f, color: Int = Color.parseColor("#E2E8F0"), bold: Boolean = false) =
        TextView(this).apply {
            text = t; textSize = size; setTextColor(color)
            if (bold) setTypeface(null, Typeface.BOLD)
        }

    private fun buildUi() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(c("#0F172A"))
            setPadding(dp(12), dp(36), dp(12), dp(8))
        }
        root.addView(label("📡 رادار امواج اطراف", 22f, bold = true))
        summary = label("", 14f, c("#38BDF8"))
        summary.setPadding(0, dp(6), 0, dp(8))
        root.addView(summary)

        tabRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        tabNames.forEachIndexed { i, nme ->
            tabRow.addView(Button(this).apply {
                text = nme; setTextColor(Color.WHITE); isAllCaps = false
                setOnClickListener { tab = i; render() }
            }, LinearLayout.LayoutParams(WC, dp(44)).apply { setMargins(dp(3), 0, dp(3), 0) })
        }
        root.addView(HorizontalScrollView(this).apply { addView(tabRow) })

        content = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(0, dp(10), 0, dp(20)) }
        root.addView(ScrollView(this).apply { addView(content) }, LinearLayout.LayoutParams(MP, 0, 1f))
        setContentView(root)
        window.decorView.layoutDirection = View.LAYOUT_DIRECTION_RTL
    }

    private fun head(t: String) {
        content.addView(label(t, 15f, c("#38BDF8"), true).apply { setPadding(0, 0, 0, dp(8)) })
    }

    private fun note(t: String, color: String = "#94A3B8") {
        content.addView(label(t, 13f, c(color)).apply { setPadding(0, 0, 0, dp(8)) })
    }

    private fun card(title: String, sub: String, pct: Int, right: String) {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(10), dp(12), dp(10))
            setBackgroundColor(c("#1E293B"))
        }
        val top = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        top.addView(label(title, 15f, bold = true), LinearLayout.LayoutParams(0, WC, 1f))
        top.addView(label(right, 14f, pctColor(pct), true))
        box.addView(top)
        box.addView(label(sub, 12f, c("#94A3B8")))
        box.addView(ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100; progress = pct
            progressTintList = ColorStateList.valueOf(pctColor(pct))
        }, LinearLayout.LayoutParams(MP, dp(8)).apply { topMargin = dp(6) })
        content.addView(box, LinearLayout.LayoutParams(MP, WC).apply { bottomMargin = dp(8) })
    }

    private fun v(x: Int) = if (x == Int.MAX_VALUE || x == -1) "?" else x.toString()

    // ---------- render ----------
    private fun render() {
        val now = SystemClock.elapsedRealtime()
        val bt = btMap.entries.filter { now - it.value.last < 30000 }.sortedByDescending { it.value.rssi }
        val heard = sats.count { it.cn0 > 0f }
        summary.text = "وای‌فای: ${wifiList.size}  •  بلوتوث: ${bt.size}  •  سلولی: ${cells.size}  •  ماهواره: $heard"
        for (i in 0 until tabRow.childCount) tabRow.getChildAt(i).setBackgroundColor(if (i == tab) c("#2563EB") else c("#334155"))
        if (tab == 1) {
            if (fm.root.parent !== content) {
                content.removeAllViews()
                content.addView(fm.root, LinearLayout.LayoutParams(MP, WC))
            }
            return
        }
        content.removeAllViews()
        warnings()
        when (tab) {
            0 -> rSummary(bt.size, heard)
            2 -> rWifi()
            3 -> rBt(bt)
            4 -> rCell()
            5 -> rSat(heard)
            6 -> rMag()
            else -> rAbout()
        }
    }

    private fun warnings() {
        if (missing().isNotEmpty()) note("⚠ برخی مجوزها داده نشده؛ از تنظیمات گوشی > برنامه‌ها > رادار امواج > مجوزها همه را فعال کنید.", "#F87171")
        try { if (!lm.isLocationEnabled) note("⚠ سرویس مکان (Location) خاموش است. اندروید بدون آن نتیجه اسکن وای‌فای و بلوتوث نمی‌دهد.", "#F87171") } catch (e: Exception) {}
        if (!wifi.isWifiEnabled) note("⚠ وای‌فای خاموش است.", "#F87171")
        if (btAdapter?.isEnabled == false) note("⚠ بلوتوث خاموش است.", "#F87171")
    }

    private fun rSummary(btCount: Int, heard: Int) {
        head("تعداد واقعی منابع شناسایی‌شده")
        card("وای‌فای (Access Point)", "${wifiList.size} نقطه دسترسی", 0.coerceAtLeast(minOf(100, wifiList.size * 5)), "${wifiList.size}")
        card("بلوتوث (BLE + کلاسیک)", "دستگاه‌های فعال در ۳۰ ثانیه اخیر", minOf(100, btCount * 5), "$btCount")
        card("دکل‌های سلولی", "2G/3G/4G/5G", minOf(100, cells.size * 20), "${cells.size}")
        card("ماهواره‌های ناوبری", "GPS/GLONASS/Galileo/BeiDou ...", minOf(100, heard * 5), "$heard")
        card("میدان مغناطیسی", "نویز الکترومغناطیسی محیط", ((mag / 150f) * 100).toInt().coerceIn(0, 100), "%.0f µT".format(mag))
        head("سخت‌افزار رادیویی این گوشی")
        val pm = packageManager
        val feats = listOf(
            "وای‌فای" to PackageManager.FEATURE_WIFI,
            "Wi-Fi Direct" to PackageManager.FEATURE_WIFI_DIRECT,
            "Wi-Fi Aware" to "android.hardware.wifi.aware",
            "Wi-Fi RTT (فاصله‌یابی)" to "android.hardware.wifi.rtt",
            "بلوتوث" to PackageManager.FEATURE_BLUETOOTH,
            "بلوتوث کم‌مصرف (BLE)" to PackageManager.FEATURE_BLUETOOTH_LE,
            "NFC" to PackageManager.FEATURE_NFC,
            "مخابرات سلولی" to PackageManager.FEATURE_TELEPHONY,
            "GPS" to PackageManager.FEATURE_LOCATION_GPS,
            "UWB" to "android.hardware.uwb",
            "مادون قرمز (IR)" to PackageManager.FEATURE_CONSUMER_IR
        )
        feats.forEach { (n, f) -> note((if (pm.hasSystemFeature(f)) "✔ " else "✘ ") + n, if (pm.hasSystemFeature(f)) "#86EFAC" else "#94A3B8") }
        note((if (wifi.is5GHzBandSupported) "✔ " else "✘ ") + "باند 5GHz")
        if (Build.VERSION.SDK_INT >= 30) note((if (wifi.is6GHzBandSupported) "✔ " else "✘ ") + "باند 6GHz")
    }

    private fun channel(f: Int): Int = when {
        f == 2484 -> 14
        f < 3000 -> (f - 2407) / 5
        f >= 5925 -> (f - 5950) / 5
        else -> (f - 5000) / 5
    }

    private fun sec(cap: String): String = when {
        cap.contains("SAE") -> "WPA3"
        cap.contains("WPA2") || cap.contains("RSN") -> "WPA2"
        cap.contains("WPA") -> "WPA"
        cap.contains("WEP") -> "WEP"
        cap.contains("OWE") -> "OWE"
        else -> "باز"
    }

    private fun rWifi() {
        val l = wifiList.sortedByDescending { it.level }
        val b24 = l.count { it.frequency < 3000 }
        val b5 = l.count { it.frequency in 4900..5924 }
        val b6 = l.count { it.frequency >= 5925 }
        head("${l.size} شبکه  •  2.4GHz: $b24  •  5GHz: $b5  •  6GHz: $b6")
        if (l.isEmpty()) note("هنوز نتیجه‌ای نیامده. چند ثانیه صبر کنید (اندروید اسکن وای‌فای را محدود می‌کند).")
        l.forEach { r ->
            val w = when (r.channelWidth) { 0 -> "20"; 1 -> "40"; 2 -> "80"; 3 -> "160"; 4 -> "80+80"; else -> "?" }
            val pct = ((r.level + 100) * 100 / 70).coerceIn(0, 100)
            card(
                if (r.SSID.isNullOrEmpty()) "(شبکه مخفی)" else r.SSID,
                "${r.BSSID}\nکانال ${channel(r.frequency)} • ${r.frequency}MHz • پهنا ${w}MHz • ${sec(r.capabilities)}",
                pct, "${r.level} dBm"
            )
        }
    }

    private fun rBt(bt: List<Map.Entry<String, BtDev>>) {
        head("${bt.size} دستگاه بلوتوثی در اطراف")
        if (bt.isEmpty()) note("چیزی پیدا نشد. مجوز «دستگاه‌های اطراف» و روشن بودن بلوتوث لازم است.")
        bt.forEach { e ->
            val d = e.value
            card(if (d.name.isEmpty()) "(بدون نام)" else d.name,
                "${e.key} • ${if (d.ble) "BLE" else "کلاسیک"}",
                ((d.rssi + 100) * 100 / 70).coerceIn(0, 100), "${d.rssi} dBm")
        }
    }

    private fun rCell() {
        try {
            val tm = getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager
            head("${cells.size} سلول  •  اپراتور: ${tm.networkOperatorName.ifEmpty { "نامشخص" }}")
        } catch (e: Exception) { head("${cells.size} سلول") }
        if (cells.isEmpty()) note("اطلاعاتی نیست. سیم‌کارت و مجوز تلفن/مکان لازم است.")
        cells.forEach { cellRow(it) }
    }

    private fun cellRow(c: CellInfo) {
        var type = "نامشخص"; var det = ""; var dbm = Int.MAX_VALUE
        when {
            c is CellInfoLte -> { type = "LTE (4G)"; det = "PCI ${v(c.cellIdentity.pci)} • EARFCN ${v(c.cellIdentity.earfcn)} • TAC ${v(c.cellIdentity.tac)}"; dbm = c.cellSignalStrength.dbm }
            c is CellInfoGsm -> { type = "GSM (2G)"; det = "CID ${v(c.cellIdentity.cid)} • LAC ${v(c.cellIdentity.lac)}"; dbm = c.cellSignalStrength.dbm }
            c is CellInfoWcdma -> { type = "WCDMA (3G)"; det = "CID ${v(c.cellIdentity.cid)} • LAC ${v(c.cellIdentity.lac)} • PSC ${v(c.cellIdentity.psc)}"; dbm = c.cellSignalStrength.dbm }
            c is CellInfoCdma -> { type = "CDMA"; det = "BID ${v(c.cellIdentity.basestationId)}"; dbm = c.cellSignalStrength.dbm }
            Build.VERSION.SDK_INT >= 29 && c is CellInfoNr -> {
                type = "5G NR"
                val id = c.cellIdentity as CellIdentityNr
                det = "PCI ${v(id.pci)} • NRARFCN ${v(id.nrarfcn)}"
                dbm = c.cellSignalStrength.dbm
            }
        }
        val ok = dbm != Int.MAX_VALUE
        card(type + if (c.isRegistered) "  (متصل)" else "", det,
            if (ok) ((dbm + 140) * 100 / 96).coerceIn(0, 100) else 0, if (ok) "$dbm dBm" else "—")
    }

    private fun conName(t: Int) = when (t) {
        1 -> "GPS"; 2 -> "SBAS"; 3 -> "GLONASS"; 4 -> "QZSS"; 5 -> "BeiDou"; 6 -> "Galileo"; 7 -> "NavIC"; else -> "؟"
    }

    private fun rSat(heard: Int) {
        val used = sats.count { it.used }
        head("$heard ماهواره شنیده‌شده • $used در محاسبه موقعیت (از ${sats.size} ماهواره قابل‌دید)")
        note(sats.filter { it.cn0 > 0f }.groupBy { conName(it.con) }.entries.joinToString(" • ") { "${it.key}: ${it.value.size}" })
        if (sats.isEmpty()) note("برای دریافت ماهواره بیرون از ساختمان بروید و GPS را روشن کنید.")
        sats.sortedByDescending { it.cn0 }.forEach {
            card("${conName(it.con)} #${it.svid}", "استفاده در موقعیت‌یابی: ${if (it.used) "بله" else "خیر"}",
                (it.cn0 * 2).toInt().coerceIn(0, 100), "%.0f dB-Hz".format(it.cn0))
        }
    }

    private fun rMag() {
        if (sm.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD) == null) { note("این گوشی سنسور مغناطیسی ندارد."); return }
        head("شدت میدان مغناطیسی")
        card("اکنون", "بیشترین مقدار ثبت‌شده: %.0f µT".format(magMax), ((mag / 150f) * 100).toInt().coerceIn(0, 100), "%.1f µT".format(mag))
        note("مقدار عادی زمین ≈ ۲۵ تا ۶۵ µT است. نزدیک شدن به آهنربا، فلز بزرگ، بلندگو، موتور و ترانس برق عدد را بالا می‌برد. این سنسور فقط میدان مغناطیسی را می‌سنجد، نه فرکانس رادیویی.")
    }

    private fun rAbout() {
        head("درباره و محدودیت‌های واقعی اندروید")
        note("این برنامه فقط داده‌های واقعی سخت‌افزار گوشی را نشان می‌دهد؛ هیچ عددی ساختگی نیست.")
        note("• وای‌فای: همه Access Pointها با BSSID، قدرت (dBm)، کانال، باند و امنیت.\n• بلوتوث: BLE و کلاسیک با RSSI.\n• سلولی: دکل‌های 2G/3G/4G/5G.\n• ماهواره: GPS، GLONASS، Galileo، BeiDou، QZSS، NavIC با قدرت سیگنال.\n• سنسور مغناطیسی.", "#E2E8F0")
        note("رادیو FM/AM: اندروید هیچ API عمومی برای تیونر رادیو ندارد و سامسونگ هم دسترسی به تراشه FM را برای برنامه‌ها باز نکرده است؛ بنابراین هیچ برنامه‌ای (از جمله این یکی) نمی‌تواند بدون سخت‌افزار اضافه رادیوی واقعی بشنود یا فرکانس دلخواه بخواند. برای شنیدن/اسکن همه فرکانس‌ها باید دانگل SDR (مثل RTL-SDR) با کابل OTG به گوشی وصل شود.", "#FBBF24")
    }
}
