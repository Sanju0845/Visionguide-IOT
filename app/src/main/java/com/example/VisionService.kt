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
    private var hasAnnouncedStartup = false
    private var pollingThread: Thread? = null

    companion object {
        var isRunning = false
        var pythonServerStarted = false
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
            serverModule.callAttr("configure", camIp, groqKey, triggerCm, cooldownMs, port)
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
            if (!hasAnnouncedStartup) {
                tts?.speak("VisionGuide background service active", TextToSpeech.QUEUE_FLUSH, null, null)
                hasAnnouncedStartup = true
            }
            startPollingThread()
        }
    }

    private fun speak(text: String) {
        tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, null)
    }

    private fun startPollingThread() {
        if (pollingThread?.isAlive == true) return
        
        pollingThread = thread {
            while (isRunning) {
                try {
                    val url = URL("http://localhost:$port/status")
                    val conn = url.openConnection() as HttpURLConnection
                    conn.connectTimeout = 1000
                    conn.readTimeout = 1000
                    if (conn.responseCode == 200) {
                        val stream = conn.inputStream
                        val response = stream.bufferedReader().use { it.readText() }
                        val json = JSONObject(response)
                        
                        val lastResult = json.optString("last_result", "")
                        
                        if (lastResult.isNotEmpty() && lastResult != lastSpokenResult) {
                            lastSpokenResult = lastResult
                            speak(lastResult)
                        }
                    }
                } catch (e: Exception) {
                    // Ignore background polling errors
                }
                
                try {
                    Thread.sleep(1000)
                } catch (e: InterruptedException) {
                    break
                }
            }
        }
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
}
