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
import android.webkit.WebView
import android.widget.ImageView
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
    private lateinit var tvAiResult: TextView
    private lateinit var tvEventLog: TextView
    private lateinit var tvLatency: TextView
    private lateinit var tvDistance: TextView
    private lateinit var tvPhoneIp: TextView
    private lateinit var ivSettings: ImageView
    private lateinit var webViewCam: WebView

    private var tts: TextToSpeech? = null
    private val handler = Handler(Looper.getMainLooper())
    private var lastSpokenResult = ""
    private var lastCamOnline = false
    private var lastAiReady = false
    private var hasAnnouncedLive = false

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
        tvAiResult = findViewById(R.id.tvAiResult)
        tvEventLog = findViewById(R.id.tvEventLog)
        tvLatency = findViewById(R.id.tvLatency)
        tvDistance = findViewById(R.id.tvDistance)
        tvPhoneIp = findViewById(R.id.tvPhoneIp)
        ivSettings = findViewById(R.id.ivSettings)
        webViewCam = findViewById(R.id.webViewCam)

        ivSettings.setOnClickListener {
            startActivity(Intent(this, SetupActivity::class.java))
        }

        // Setup WebView for MJPEG stream
        webViewCam.settings.javaScriptEnabled = true
        webViewCam.settings.loadWithOverviewMode = true
        webViewCam.settings.useWideViewPort = true

        tts = TextToSpeech(this, this)

        if (!Python.isStarted()) {
            Python.start(AndroidPlatform(this))
        }
    }

    override fun onResume() {
        super.onResume()
        val prefs = getSharedPreferences("VisionGuidePrefs", Context.MODE_PRIVATE)
        camIp = prefs.getString("cam_ip", "") ?: ""
        groqKey = prefs.getString("groq_api_key", "") ?: ""
        triggerCm = prefs.getString("trigger_cm", "30") ?: "30"
        cooldownMs = prefs.getString("cooldown_ms", "6000") ?: "6000"
        port = prefs.getString("port", "5000") ?: "5000"

        val py = Python.getInstance()
        val os = py.getModule("os")
        val environ = os["environ"]
        environ?.callAttr("__setitem__", "CAM_IP", camIp)
        environ?.callAttr("__setitem__", "GROQ_API_KEY", groqKey)
        environ?.callAttr("__setitem__", "TRIGGER_CM", triggerCm)
        environ?.callAttr("__setitem__", "COOLDOWN_MS", cooldownMs)
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
        val html = """
            <html>
            <body style="margin:0;padding:0;background:#111;overflow:hidden;display:flex;align-items:center;justify-content:center;">
                <img src="http://$camIp:81/stream" style="width:100%;height:100%;object-fit:cover;" onerror="this.src='data:image/svg+xml;utf8,<svg xmlns=\'http://www.w3.org/2000/svg\' viewBox=\'0 0 100 100\'><rect width=\'100%\' height=\'100%\' fill=\'%23222\'/><text x=\'50\' y=\'50\' fill=\'%23666\' text-anchor=\'middle\' font-family=\'sans-serif\' font-size=\'10\'>Stream Offline</text></svg>'">
            </body>
            </html>
        """.trimIndent()
        webViewCam.loadDataWithBaseURL(null, html, "text/html", "UTF-8", null)
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            tts?.language = Locale.US
            speak("Starting VisionGuide")
            handler.post(pollRunnable)
        }
    }

    private fun speak(text: String) {
        tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, null)
    }

    private fun pollStatus() {
        thread {
            try {
                val url = URL("http://localhost:$port/status")
                val conn = url.openConnection() as HttpURLConnection
                conn.connectTimeout = 1000
                conn.readTimeout = 1000
                if (conn.responseCode == 200) {
                    val stream = conn.inputStream
                    val response = stream.bufferedReader().use { it.readText() }
                    val json = JSONObject(response)
                    
                    val camOnline = json.optBoolean("cam_online", false)
                    val aiStatus = json.optString("ai", "OFFLINE")
                    val aiReady = aiStatus == "READY"
                    val lastResult = json.optString("last_result", "")
                    val latency = json.optInt("latency_ms", 0)
                    val distance = json.optString("distance_cm", "--")
                    val eventLog = json.optJSONArray("event_log") ?: JSONArray()
                    
                    runOnUiThread { updateUI(camOnline, aiStatus, lastResult, latency, distance, eventLog) }
                }
            } catch (e: Exception) {
                Log.e("VisionGuide", "Poll error", e)
            }
        }
    }

    private fun updateUI(camOnline: Boolean, aiStatus: String, lastResult: String, latency: Int, distance: String, eventLog: JSONArray) {
        // Cam Status
        if (camOnline) {
            tvCamStatus.backgroundTintList = ColorStateList.valueOf(Color.parseColor("#22C55E"))
            if (!lastCamOnline) speak("Camera connected")
        } else {
            tvCamStatus.backgroundTintList = ColorStateList.valueOf(Color.parseColor("#EF4444"))
        }
        lastCamOnline = camOnline

        // AI Status
        val aiReady = aiStatus == "READY"
        when (aiStatus) {
            "READY" -> {
                tvAiStatus.backgroundTintList = ColorStateList.valueOf(Color.parseColor("#22C55E"))
                if (!lastAiReady) speak("AI ready")
            }
            "PROCESSING", "BUSY" -> {
                tvAiStatus.backgroundTintList = ColorStateList.valueOf(Color.parseColor("#F59E0B"))
                if (lastAiReady) {
                    speak("Stop.")
                }
            }
            else -> {
                tvAiStatus.backgroundTintList = ColorStateList.valueOf(Color.parseColor("#EF4444"))
            }
        }
        lastAiReady = aiReady

        // Live announcement
        if (camOnline && aiReady && !hasAnnouncedLive) {
            hasAnnouncedLive = true
            speak("All systems connected. VisionGuide is live.")
        }

        // Result handling
        if (lastResult.isNotEmpty()) {
            tvAiResult.text = lastResult
            if (lastResult != lastSpokenResult) {
                speak(lastResult)
                lastSpokenResult = lastResult
            }
        }
        
        // Latency, Distance, and Server IP
        tvLatency.text = "Latency: ${latency}ms"
        tvDistance.text = if (distance.isNotEmpty() && distance != "--") "US: $distance cm" else "US: -- cm"
        val myIp = getLocalIpAddress()
        tvPhoneIp.text = "Phone IP: $myIp:$port"

        // Event log
        val logStringBuilder = StringBuilder()
        // Get last 4 elements
        val startIdx = max(0, eventLog.length() - 4)
        for (i in startIdx until eventLog.length()) {
            logStringBuilder.append(eventLog.getString(i)).append("\n")
        }
        tvEventLog.text = logStringBuilder.toString().trim()
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

    override fun onDestroy() {
        tts?.stop()
        tts?.shutdown()
        handler.removeCallbacks(pollRunnable)
        super.onDestroy()
    }
}
