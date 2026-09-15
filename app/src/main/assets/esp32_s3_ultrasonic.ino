/*
 * =========================================================================
 * VisionGuide - ESP32-S3 Ultrasonic Sensor Node (HC-SR04)
 * =========================================================================
 * Pins:
 *   TRIG_PIN : GPIO 5
 *   ECHO_PIN : GPIO 19
 *   VCC      : 5V (VIN / 5V pin on ESP32-S3)
 *   GND      : GND
 * 
 * Logic & Network:
 *   1. Regular Distance Heartbeat (every 1.5s):
 *      POST http://<PHONE_IP>:5000/distance -> {"distance": 75.4}
 *      Keeps the "HC SENSOR" capsule GREEN and updates the live distance.
 * 
 *   2. Obstacle Trigger (<= 30.0 cm):
 *      POST http://<PHONE_IP>:5000/vision_trigger -> {"distance": 22.1}
 *      Triggers ESP32-CAM capture and Groq Qwen Vision AI.
 *      Has a 5-second cooldown to avoid spamming AI.
 * =========================================================================
 */

#include <WiFi.h>
#include <HTTPClient.h>

// ================= USER CONFIGURATION =================
const char* WIFI_SSID     = "YOUR_WIFI_NAME";        // <-- Put your Wi-Fi name
const char* WIFI_PASSWORD = "YOUR_WIFI_PASSWORD";    // <-- Put your Wi-Fi password

// Put your Phone's IP (as displayed on the phone app screen under "Phone IP:")
const char* PHONE_IP      = "192.168.1.100";         // <-- Put your Phone IP here
const int   FLASK_PORT    = 5000;

// Obstacle threshold & Cooldown
const float TRIGGER_DISTANCE_CM = 30.0;  // Obstacle detection range (< 30 cm)
const unsigned long COOLDOWN_MS = 5000;  // 5 seconds between AI triggers
const unsigned long HEARTBEAT_MS = 1500; // 1.5 seconds between distance updates

// Pin definitions
#define TRIG_PIN 5
#define ECHO_PIN 19

// Sound speed: 0.0343 cm / microsecond
#define SOUND_SPEED 0.0343
// Max timeout for pulseIn: 25000 microseconds (~4.2 meters max range)
// This prevents pulseIn from freezing the ESP32 CPU!
#define PULSE_TIMEOUT_US 25000

// State variables
unsigned long lastTriggerTime = 0;
unsigned long lastHeartbeatTime = 0;

// Function prototypes
float measureDistance();
void sendDistanceUpdate(float distance);
void sendVisionTrigger(float distance);

void setup() {
  Serial.begin(115200);
  delay(1000);
  
  Serial.println("\n======================================");
  Serial.println("  VisionGuide ESP32-S3 HC-SR04 Node   ");
  Serial.println("======================================");
  Serial.printf("TRIG: GPIO %d | ECHO: GPIO %d\n", TRIG_PIN, ECHO_PIN);

  pinMode(TRIG_PIN, OUTPUT);
  digitalWrite(TRIG_PIN, LOW);
  pinMode(ECHO_PIN, INPUT);

  // Connect to Wi-Fi
  Serial.printf("Connecting to Wi-Fi: %s ", WIFI_SSID);
  WiFi.mode(WIFI_STA);
  WiFi.begin(WIFI_SSID, WIFI_PASSWORD);

  int attempts = 0;
  while (WiFi.status() != WL_CONNECTED && attempts < 30) {
    delay(500);
    Serial.print(".");
    attempts++;
  }

  if (WiFi.status() == WL_CONNECTED) {
    Serial.println("\n[Wi-Fi] Connected successfully!");
    Serial.print("[Wi-Fi] ESP32-S3 IP Address: ");
    Serial.println(WiFi.localIP());
    Serial.printf("[Target] Central Phone Hub: http://%s:%d\n", PHONE_IP, FLASK_PORT);
  } else {
    Serial.println("\n[Wi-Fi] Connection failed! Will auto-retry in loop.");
  }
}

void loop() {
  // Ensure Wi-Fi remains connected
  if (WiFi.status() != WL_CONNECTED) {
    Serial.println("[Wi-Fi] Disconnected, reconnecting...");
    WiFi.disconnect();
    WiFi.reconnect();
    delay(2000);
    return;
  }

  // 1. Measure Distance
  float distance = measureDistance();

  // Validate reading (-1 means sensor timeout / glitch)
  if (distance > 0.0) {
    unsigned long now = millis();

    // 2. Check for OBSTACLE TRIGGER (< 30 cm and >= 3 cm)
    if (distance >= 3.0 && distance <= TRIGGER_DISTANCE_CM) {
      if (now - lastTriggerTime >= COOLDOWN_MS) {
        Serial.printf("\n[ALERT] Obstacle in range: %.1f cm! Sending trigger...\n", distance);
        sendVisionTrigger(distance);
        lastTriggerTime = millis();
        lastHeartbeatTime = millis(); // Reset heartbeat so we don't double send
      } else {
        Serial.printf("[COOLDOWN] Obstacle at %.1f cm (cooldown active)\n", distance);
      }
    } 
    // 3. Regular Heartbeat (Keeps "HC SENSOR" capsule GREEN on the phone)
    else if (now - lastHeartbeatTime >= HEARTBEAT_MS) {
      Serial.printf("[HEARTBEAT] Path clear: %.1f cm -> Sending distance update\n", distance);
      sendDistanceUpdate(distance);
      lastHeartbeatTime = millis();
    }
  }

  delay(100); // 10Hz sensor check frequency
}

// -------------------------------------------------------------
// Accurate, Non-Blocking Ultrasonic Measurement
// -------------------------------------------------------------
float measureDistance() {
  // Clear the trigger
  digitalWrite(TRIG_PIN, LOW);
  delayMicroseconds(2);

  // Send 10µs HIGH pulse
  digitalWrite(TRIG_PIN, HIGH);
  delayMicroseconds(10);
  digitalWrite(TRIG_PIN, LOW);

  // Measure echo pulse width with bounded 25ms timeout
  unsigned long duration = pulseIn(ECHO_PIN, HIGH, PULSE_TIMEOUT_US);

  if (duration == 0) {
    // Timeout or out of range
    return -1.0;
  }

  // Distance in cm = (duration / 2) * speed of sound
  float distanceCm = (duration * SOUND_SPEED) / 2.0;

  // Filter realistic HC-SR04 physical bounds (2 cm to 400 cm)
  if (distanceCm < 2.0 || distanceCm > 400.0) {
    return -1.0;
  }

  return distanceCm;
}

// -------------------------------------------------------------
// 1. Send Heartbeat to keep HC SENSOR Capsule GREEN
// -------------------------------------------------------------
void sendDistanceUpdate(float distance) {
  HTTPClient http;
  String url = String("http://") + PHONE_IP + ":" + FLASK_PORT + "/distance";

  http.begin(url);
  http.addHeader("Content-Type", "application/json");
  http.setTimeout(1200); // 1.2s timeout so it never hangs

  String payload = "{\"distance\":" + String(distance, 1) + "}";
  int httpCode = http.POST(payload);

  if (httpCode > 0) {
    Serial.printf("[HTTP /distance] Response code: %d\n", httpCode);
  } else {
    Serial.printf("[HTTP /distance] Error: %s\n", http.errorToString(httpCode).c_str());
  }

  http.end();
}

// -------------------------------------------------------------
// 2. Send Vision Trigger (Triggers Camera + Groq AI)
// -------------------------------------------------------------
void sendVisionTrigger(float distance) {
  HTTPClient http;
  String url = String("http://") + PHONE_IP + ":" + FLASK_PORT + "/vision_trigger";

  http.begin(url);
  http.addHeader("Content-Type", "application/json");
  http.setTimeout(4000); // Wait up to 4s for AI response

  String payload = "{\"distance\":" + String(distance, 1) + "}";
  int httpCode = http.POST(payload);

  if (httpCode == 200) {
    String response = http.getString();
    Serial.println("[HTTP /vision_trigger] SUCCESS!");
    Serial.println("[AI Response] " + response);
  } else {
    Serial.printf("[HTTP /vision_trigger] Code: %d, Error: %s\n", 
                  httpCode, http.errorToString(httpCode).c_str());
  }

  http.end();
}
