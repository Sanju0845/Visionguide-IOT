package com.example

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.widget.Button
import android.widget.EditText

class SetupActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_setup)

        val prefs = getSharedPreferences("VisionGuidePrefs", Context.MODE_PRIVATE)

        val etCamIp = findViewById<EditText>(R.id.etCamIp)
        val etGroqKey = findViewById<EditText>(R.id.etGroqKey)
        val etTriggerCm = findViewById<EditText>(R.id.etTriggerCm)
        val etCooldown = findViewById<EditText>(R.id.etCooldown)
        val etPort = findViewById<EditText>(R.id.etPort)
        val btnSaveLaunch = findViewById<Button>(R.id.btnSaveLaunch)

        etCamIp.setText(prefs.getString("cam_ip", ""))
        etGroqKey.setText(prefs.getString("groq_api_key", ""))
        etTriggerCm.setText(prefs.getString("trigger_cm", "30"))
        etCooldown.setText(prefs.getString("cooldown_ms", "6000"))
        etPort.setText(prefs.getString("port", "5000"))

        btnSaveLaunch.setOnClickListener {
            prefs.edit().apply {
                putString("cam_ip", etCamIp.text.toString())
                putString("groq_api_key", etGroqKey.text.toString())
                putString("trigger_cm", etTriggerCm.text.toString())
                putString("cooldown_ms", etCooldown.text.toString())
                putString("port", etPort.text.toString())
                putBoolean("is_setup", true)
                apply()
            }
            
            // Restart the service to apply new settings immediately
            val serviceIntent = Intent(this, VisionService::class.java).apply {
                action = "REFRESH"
            }
            startService(serviceIntent)
            
            startActivity(Intent(this, MainActivity::class.java))
            finish()
        }
    }
}
