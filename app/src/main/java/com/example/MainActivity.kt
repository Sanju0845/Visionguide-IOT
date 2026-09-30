package com.example

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.util.Log
import android.view.View
import android.webkit.WebView
import android.widget.ImageView
import android.widget.ScrollView
import android.widget.TextView
import com.chaquo.python.Python
import com.chaquo.python.android.AndroidPlatform
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.Inet4Address
import java.net.NetworkInterface
import java.net.URL
import java.util.Locale
import kotlin.concurrent.thread
import kotlin.math.max

class MainActivity : Activity(), TextToSpeech.OnInitListener {
    
    private lateinit var tvCamStatus: TextView
    private lateinit var tvAiStatus: TextView
    private lateinit var tvHcStatus: TextView
    private lateinit var tvAiResult: TextView
    private lateinit var tvEventLog: TextView
    private lateinit var svEventLog: ScrollView
    private lateinit var tvLatency: TextView
    private lateinit var tvDistance: TextView
    private lateinit var tvPhoneIp: TextView
    private lateinit var ivSettings: ImageView
    private lateinit var ivRefresh: ImageView
    private lateinit var webViewCam: WebView

    private var tts: TextToSpeech? = null
    private val handler = Handler(Looper.getMainLooper())
    private var lastSpokenResult = ""
    private var lastCamOnline = false
    private var lastAiReady = false
    private var lastHcOnline = false
    private var hasSpokenStopForCurrentObstacle = false
    private var cachedIp = ""
    private var lastIpCheckTime = 0L

    companion object {
        var pythonServerStarted = false
    }

    private var camIp = ""
    private var groqKey = ""
    private var triggerCm = "30"
    private var cooldownMs = "6000"
    private var port = "5000"

    private val pollRunnable = object : Runnable {
        override fun run() {
            pollStatus()
            handler.postDelayed(this, 1000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        tvCamStatus = findViewById(R.id.tvCamStatus)
        tvAiStatus = findViewById(R.id.tvAiStatus)
        tvHcStatus = findViewById(R.id.tvHcStatus)
        tvAiResult = findViewById(R.id.tvAiResult)
        tvEventLog = findViewById(R.id.tvEventLog)
        svEventLog = findViewById(R.id.svEventLog)
        tvLatency = findViewById(R.id.tvLatency)
        tvDistance = findViewById(R.id.tvDistance)
        tvPhoneIp = findViewById(R.id.tvPhoneIp)
        ivSettings = findViewById(R.id.ivSettings)
        ivRefresh = findViewById(R.id.ivRefresh)
        webViewCam = findViewById(R.id.webViewCam)

        ivSettings.setOnClickListener {
            startActivity(Intent(this, SetupActivity::class.java))
        }

        ivRefresh.setOnClickListener {
            cachedIp = "" // Force fresh IP lookup
            val py = Python.getInstance()
            val serverModule = py.getModule("server")
            try {
                serverModule.callAttr(
                    "configure",
                    camIp,
                    groqKey,
                    triggerCm,
                    cooldownMs,
                    port
                )
            } catch (e: Exception) {
                Log.e("VisionGuide", "Error calling configure", e)
            }
            webViewCam.reload()
        }

        // Setup WebView: Use software layer to prevent MESA rendernode GPU context errors
        webViewCam.setLayerType(View.LAYER_TYPE_SOFTWARE, null)
        webViewCam.settings.apply {
            javaScriptEnabled = false
            loadWithOverviewMode = true
            useWideViewPort = true
            setSupportZoom(false)
        }

        tts = TextToSpeech(this, this)

        if (!Python.isStarted()) {
            Python.start(AndroidPlatform(this))
        }
    }

    override fun onResume() {
        super.onResume()
        VisionService.isMainActivityForeground = true

        val prefs = getSharedPreferences("VisionGuidePrefs", Context.MODE_PRIVATE)
        camIp = prefs.getString("cam_ip", "") ?: ""
        groqKey = prefs.getString("groq_api_key", "") ?: ""
        triggerCm = prefs.getString("trigger_cm", "30") ?: "30"
        cooldownMs = prefs.getString("cooldown_ms", "6000") ?: "6000"
        port = prefs.getString("port", "5000") ?: "5000"

        val camQuality = prefs.getString("cam_quality", "25") ?: "25"

        val py = Python.getInstance()
        val os = py.getModule("os")
        val environ = os["environ"]
        environ?.callAttr("__setitem__", "CAM_IP", camIp)
        environ?.callAttr("__setitem__", "GROQ_API_KEY", groqKey)
        environ?.callAttr("__setitem__", "TRIGGER_CM", triggerCm)
        environ?.callAttr("__setitem__", "COOLDOWN_MS", cooldownMs)
        environ?.callAttr("__setitem__", "CAM_QUALITY", camQuality)
        environ?.callAttr("__setitem__", "PORT", port)

        val serverModule = py.getModule("server")
        try {
            serverModule.callAttr(
                "configure",
                camIp,
                groqKey,
                triggerCm,
                cooldownMs,
                port
            )
        } catch (e: Exception) {
            Log.e("VisionGuide", "Error calling configure", e)
        }

        if (!pythonServerStarted) {
            pythonServerStarted = true
            thread {
                try {
                    serverModule.callAttr("main")
                } catch (e: Exception) {
                    Log.e("VisionGuide", "Error starting Python", e)
                    runOnUiThread { tvAiResult.text = "Error starting server" }
                }
            }
        }
        
        // Update WebView stream with potentially new IP
        if (camIp.isNotEmpty()) {
            val html = """
                <html>
                <body style="margin:0;padding:0;background:#111;overflow:hidden;display:flex;align-items:center;justify-content:center;">
                    <img src="http://$camIp:81/stream" style="width:100%;height:100%;object-fit:cover;" onerror="this.src='data:image/svg+xml;utf8,<svg xmlns=\'http://www.w3.org/2000/svg\' viewBox=\'0 0 100 100\'><rect width=\'100%\' height=\'100%\' fill=\'%23222\'/><text x=\'50\' y=\'50\' fill=\'%23666\' text-anchor=\'middle\' font-family=\'sans-serif\' font-size=\'10\'>Stream Offline</text></svg>'">
                </body>
                </html>
            """.trimIndent()
            webViewCam.loadDataWithBaseURL(null, html, "text/html", "UTF-8", null)
        }

        // Restart polling loop cleanly on resume
        handler.removeCallbacks(pollRunnable)
        handler.post(pollRunnable)
    }

    override fun onPause() {
        super.onPause()
        VisionService.isMainActivityForeground = false
        // Stop polling runnable when paused to prevent background CPU/audit loops
        handler.removeCallbacks(pollRunnable)
        // Stop streaming to free ESP32-CAM socket and save power
        try {
            webViewCam.stopLoading()
            webViewCam.loadUrl("about:blank")
        } catch (e: Exception) {
            Log.e("VisionGuide", "Error stopping WebView", e)
        }
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            tts?.language = Locale.US
            handler.removeCallbacks(pollRunnable)
            handler.post(pollRunnable)
        }
    }

    private fun speakImmediateStop() {
        tts?.speak("Stop.", TextToSpeech.QUEUE_FLUSH, null, null)
    }

    private fun speakAiResponse(text: String) {
        val clean = text.trim()
        if (clean.isEmpty()) return
        // QUEUE_ADD ensures the AI instruction plays right after Stop without being cut off
        tts?.speak(clean, TextToSpeech.QUEUE_ADD, null, null)
    }

    private fun pollStatus() {
        thread {
            try {
                val url = URL("http://localhost:$port/status")
                val conn = url.openConnection() as HttpURLConnection
                conn.connectTimeout = 800
                conn.readTimeout = 800
                if (conn.responseCode == 200) {
                    val stream = conn.inputStream
                    val response = stream.bufferedReader().use { it.readText() }
                    val json = JSONObject(response)
                    
                    val camOnline = json.optBoolean("cam_online", false)
                    val aiStatus = json.optString("ai", "OFFLINE")
                    val hcOnline = json.optBoolean("hc_online", false)
                    val aiReady = aiStatus == "READY"
                    val lastResult = json.optString("last_result", "")
                    val latency = json.optInt("latency_ms", 0)
                    val distance = json.optString("distance_cm", "--")
                    val eventLog = json.optJSONArray("event_log") ?: JSONArray()
                    
                    runOnUiThread { updateUI(camOnline, aiStatus, hcOnline, lastResult, latency, distance, eventLog) }
                }
            } catch (e: Exception) {
                // Silently handle expected network startup transitions; avoid filling logcat
            }
        }
    }

    private fun updateUI(
        camOnline: Boolean,
        aiStatus: String,
        hcOnline: Boolean,
        lastResult: String,
        latency: Int,
        distance: String,
        eventLog: JSONArray
    ) {
        // Cam Status
        if (camOnline) {
            tvCamStatus.backgroundTintList = ColorStateList.valueOf(Color.parseColor("#22C55E"))
        } else {
            tvCamStatus.backgroundTintList = ColorStateList.valueOf(Color.parseColor("#EF4444"))
        }
        lastCamOnline = camOnline

        // AI Status
        when (aiStatus) {
            "READY" -> {
                tvAiStatus.backgroundTintList = ColorStateList.valueOf(Color.parseColor("#22C55E"))
            }
            "PROCESSING", "BUSY" -> {
                tvAiStatus.backgroundTintList = ColorStateList.valueOf(Color.parseColor("#F59E0B"))
            }
            else -> {
                tvAiStatus.backgroundTintList = ColorStateList.valueOf(Color.parseColor("#EF4444"))
            }
        }
        lastAiReady = (aiStatus == "READY")

        // HC Sensor Status
        if (hcOnline) {
            tvHcStatus.backgroundTintList = ColorStateList.valueOf(Color.parseColor("#22C55E"))
        } else {
            tvHcStatus.backgroundTintList = ColorStateList.valueOf(Color.parseColor("#EF4444"))
        }
        lastHcOnline = hcOnline

        // 1. Immediately when obstacle detected: say "Stop." out loud (once per event)
        val d = distance.toFloatOrNull()
        val trig = triggerCm.toFloatOrNull() ?: 30f
        val isObstacleDetected = (d != null && d <= trig) || aiStatus == "PROCESSING"

        if (isObstacleDetected) {
            if (!hasSpokenStopForCurrentObstacle) {
                hasSpokenStopForCurrentObstacle = true
                speakImmediateStop()
            }
        } else if (d != null && d > trig + 5f && aiStatus != "PROCESSING") {
            hasSpokenStopForCurrentObstacle = false
        }

        // 2. When AI gives output: say out loud immediately (smoothly following Stop)
        if (lastResult.isNotEmpty()) {
            tvAiResult.text = lastResult
            if (lastResult != lastSpokenResult && isInstructionSpeech(lastResult)) {
                lastSpokenResult = lastResult
                speakAiResponse(lastResult)
                hasSpokenStopForCurrentObstacle = false
            }
        } else {
            tvAiResult.text = "VisionGuide Ready"
        }
        
        // Latency, Distance, and Server IP
        tvLatency.text = "Latency: ${latency}ms"
        if (distance.isNotEmpty() && distance != "--") {
            tvDistance.text = "US: $distance cm"
            try {
                val d = distance.toFloat()
                val trig = triggerCm.toFloatOrNull() ?: 30f
                if (d <= trig) {
                    tvDistance.setTextColor(Color.parseColor("#EF4444"))
                } else {
                    tvDistance.setTextColor(Color.parseColor("#22C55E"))
                }
            } catch (e: Exception) {
                tvDistance.setTextColor(Color.parseColor("#22C55E"))
            }
        } else {
            tvDistance.text = "US: -- cm"
            tvDistance.setTextColor(Color.parseColor("#8b949e"))
        }

        val myIp = getCachedOrFreshIp()
        tvPhoneIp.text = "Phone IP: $myIp:$port"

        // Live scrollable console outputs
        if (eventLog.length() > 0) {
            val logStringBuilder = StringBuilder()
            for (i in 0 until eventLog.length()) {
                logStringBuilder.append(eventLog.getString(i)).append("\n")
            }
            val newText = logStringBuilder.toString().trim()
            if (tvEventLog.text.toString() != newText) {
                tvEventLog.text = newText
                svEventLog.post {
                    svEventLog.fullScroll(View.FOCUS_DOWN)
                }
            }
        }
    }

    private fun getCachedOrFreshIp(): String {
        val now = System.currentTimeMillis()
        if (cachedIp.isEmpty() || (now - lastIpCheckTime) > 30000) {
            cachedIp = getLocalIpAddress()
            lastIpCheckTime = now
        }
        return cachedIp
    }

    private fun getLocalIpAddress(): String {
        try {
            var wifiIp: String? = null
            var apIp: String? = null
            var fallbackIp: String? = null

            val interfaces = NetworkInterface.getNetworkInterfaces()
            while (interfaces.hasMoreElements()) {
                val intf = interfaces.nextElement()
                val name = intf.name.lowercase()
                val addrs = intf.inetAddresses
                while (addrs.hasMoreElements()) {
                    val addr = addrs.nextElement()
                    if (!addr.isLoopbackAddress && addr is Inet4Address) {
                        val host = addr.hostAddress ?: continue
                        if (name.startsWith("wlan") || name.startsWith("eth")) {
                            wifiIp = host
                        } else if (name.startsWith("ap") || name.startsWith("softap") || name.startsWith("swlan") || name.startsWith("tether")) {
                            apIp = host
                        } else if (fallbackIp == null && !name.startsWith("rmnet") && !name.startsWith("dummy") && !name.startsWith("ccmni") && !name.startsWith("pdp")) {
                            fallbackIp = host
                        }
                    }
                }
            }
            return wifiIp ?: apIp ?: fallbackIp ?: "127.0.0.1"
        } catch (e: Exception) {
            Log.e("VisionGuide", "Error getting IP", e)
        }
        return "127.0.0.1"
    }

    private fun isInstructionSpeech(text: String): Boolean {
        val clean = text.trim()
        if (clean.isEmpty()) return false
        val lower = clean.lowercase()
        if (lower == "ai ready" ||
            lower.contains("ai ready") ||
            lower.startsWith("visionguide ready") ||
            lower.startsWith("capture error") ||
            lower.startsWith("ai error") ||
            lower.startsWith("error") ||
            lower.startsWith("capture failed") ||
            lower.contains("timed out") ||
            lower.contains("httpconnectionpool") ||
            lower.contains("connection refused") ||
            lower.contains("exception") ||
            lower.startsWith("refreshing connections") ||
            lower.startsWith("starting visionguide") ||
            lower.contains("all systems connected")) {
            return false
        }
        return true
    }

    override fun onDestroy() {
        tts?.stop()
        tts?.shutdown()
        handler.removeCallbacks(pollRunnable)
        super.onDestroy()
    }
}
