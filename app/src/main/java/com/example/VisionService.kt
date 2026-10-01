package com.example

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.util.Log
import androidx.core.app.NotificationCompat
import com.chaquo.python.Python
import com.chaquo.python.android.AndroidPlatform
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale
import kotlin.concurrent.thread

class VisionService : Service(), TextToSpeech.OnInitListener {
    private var tts: TextToSpeech? = null
    private var lastSpokenResult = ""
    private var port = "5000"
    private var hasSpokenStopForCurrentObstacle = false
    private var pollingThread: Thread? = null

    companion object {
        var isRunning = false
        var pythonServerStarted = false
        var isMainActivityForeground = false
    }

    override fun onCreate() {
        super.onCreate()
        isRunning = true
        
        if (!Python.isStarted()) {
            Python.start(AndroidPlatform(this))
        }

        createNotificationChannel()
        val intent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(this, 0, intent, PendingIntent.FLAG_IMMUTABLE)

        val notification = NotificationCompat.Builder(this, "VisionServiceChannel")
            .setContentTitle("VisionGuide Active")
            .setContentText("Monitoring surroundings in background...")
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentIntent(pendingIntent)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()

        startForeground(1, notification)

        tts = TextToSpeech(this, this)
        
        startPythonServer()
    }

    private fun startPythonServer() {
        val prefs = getSharedPreferences("VisionGuidePrefs", Context.MODE_PRIVATE)
        val camIp = prefs.getString("cam_ip", "") ?: ""
        val groqKey = prefs.getString("groq_api_key", "") ?: ""
        val triggerCm = prefs.getString("trigger_cm", "30") ?: "30"
        val cooldownMs = prefs.getString("cooldown_ms", "6000") ?: "6000"
        val camQuality = prefs.getString("cam_quality", "25") ?: "25"
        port = prefs.getString("port", "5000") ?: "5000"

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
            serverModule.callAttr("configure", camIp, groqKey, triggerCm, cooldownMs, port)
            serverModule.callAttr("set_obstacle_callback", ServiceObstacleListener(this))
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
                }
            }
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                "VisionServiceChannel",
                "VisionGuide Service",
                NotificationManager.IMPORTANCE_LOW
            )
            val manager = getSystemService(NotificationManager::class.java)
            manager?.createNotificationChannel(channel)
        }
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            tts?.language = Locale.US
            startPollingThread()
        }
    }

    private fun startPollingThread() {
        if (pollingThread?.isAlive == true) return
        
        pollingThread = thread {
            while (isRunning) {
                try {
                    val url = URL("http://localhost:$port/status")
                    val conn = url.openConnection() as HttpURLConnection
                    conn.connectTimeout = 800
                    conn.readTimeout = 800
                    if (conn.responseCode == 200) {
                        val stream = conn.inputStream
                        val response = stream.bufferedReader().use { it.readText() }
                        val json = JSONObject(response)
                        
                        val aiStatus = json.optString("ai", "OFFLINE")
                        val distanceStr = json.optString("distance_cm", "")
                        val lastResult = json.optString("last_result", "")
                        val prefs = getSharedPreferences("VisionGuidePrefs", Context.MODE_PRIVATE)
                        val triggerThreshold = prefs.getString("trigger_cm", "30")?.toFloatOrNull() ?: 30f
                        val d = distanceStr.toFloatOrNull()
                        
                        if (!isMainActivityForeground) {
                            val isObstacle = (d != null && d <= triggerThreshold) || aiStatus == "PROCESSING"

                            // 1. Say "Stop." immediately when obstacle detected
                            if (isObstacle) {
                                if (!hasSpokenStopForCurrentObstacle) {
                                    hasSpokenStopForCurrentObstacle = true
                                    tts?.speak("Stop.", TextToSpeech.QUEUE_FLUSH, null, null)
                                }
                            } else if (d != null && d > triggerThreshold + 5f && aiStatus != "PROCESSING") {
                                hasSpokenStopForCurrentObstacle = false
                            }

                            // 2. Say AI output out loud immediately
                            if (lastResult.isNotEmpty() && lastResult != lastSpokenResult && isInstructionSpeech(lastResult)) {
                                lastSpokenResult = lastResult
                                tts?.speak(lastResult, TextToSpeech.QUEUE_ADD, null, null)
                                hasSpokenStopForCurrentObstacle = false
                            }
                        }
                    }
                } catch (e: Exception) {
                    // Ignore background polling errors
                }
                
                try {
                    Thread.sleep(800)
                } catch (e: InterruptedException) {
                    break
                }
            }
        }
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

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == "REFRESH") {
            startPythonServer()
        }
        return START_STICKY
    }

    override fun onDestroy() {
        isRunning = false
        pollingThread?.interrupt()
        tts?.stop()
        tts?.shutdown()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    class ServiceObstacleListener(private val service: VisionService) {
        fun onObstacle() {
            if (!isMainActivityForeground && !service.hasSpokenStopForCurrentObstacle) {
                service.hasSpokenStopForCurrentObstacle = true
                service.tts?.speak("Stop.", TextToSpeech.QUEUE_FLUSH, null, null)
            }
        }

        fun onAiResult(result: String) {
            if (!isMainActivityForeground && service.isInstructionSpeech(result)) {
                service.lastSpokenResult = result
                service.tts?.speak(result, TextToSpeech.QUEUE_ADD, null, null)
                service.hasSpokenStopForCurrentObstacle = false
            }
        }
    }
}
