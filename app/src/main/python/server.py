import os
import time
import threading
import base64
import requests

from flask import Flask, request, jsonify
from groq import Groq


# ============================================================
#                 VISIONGUIDE CONFIG
#                 VALUES COME FROM KOTLIN
# ============================================================

CAM_IP = os.environ.get("CAM_IP", "")
GROQ_API_KEY = os.environ.get("GROQ_API_KEY", "")
PORT = int(os.environ.get("PORT", "5000"))
TRIGGER_CM = float(os.environ.get("TRIGGER_CM", "30"))
COOLDOWN_MS = int(os.environ.get("COOLDOWN_MS", "6000"))


# ============================================================
#                 INITIALIZATION
# ============================================================

app = Flask(__name__)

state = {
    "cam_online": False,
    "cam_busy": False,
    "ai": "STARTING",
    "latency_ms": 0,
    "last_result": "",
    "distance_cm": "--",
    "event_log": []
}

client = None

if GROQ_API_KEY:
    try:
        client = Groq(api_key=GROQ_API_KEY)
    except Exception:
        client = None


# ============================================================
#                 EVENT LOG
# ============================================================

def log_event(msg):
    if len(state["event_log"]) >= 12:
        state["event_log"].pop(0)

    timestamp = time.strftime("%H:%M:%S")
    state["event_log"].append(f"{timestamp}  {msg}")

    print(f"[Event] {msg}")


# ============================================================
#                 CAMERA STATUS MONITOR
# ============================================================

def check_camera():
    global CAM_IP

    last_status = False

    while True:
        current_cam_ip = os.environ.get("CAM_IP", CAM_IP)
        if not current_cam_ip:
            time.sleep(1)
            continue

        try:
            url = f"http://{current_cam_ip}/status"

            response = requests.get(
                url,
                timeout=0.8
            )

            if response.status_code == 200:

                try:
                    data = response.json()

                    # Read distance if CAM provides it
                    distance = (
                        data.get("distance")
                        or data.get("distance_cm")
                    )

                    if distance is not None:
                        distance_string = str(distance).strip()

                        if distance_string not in (
                            "",
                            "--",
                            "None",
                            "null"
                        ):
                            state["distance_cm"] = distance_string

                except Exception:
                    pass

                state["cam_online"] = True

                if not last_status:
                    log_event("ESP32-CAM connected")

                last_status = True

            else:

                state["cam_online"] = False

                if last_status:
                    log_event("ESP32-CAM offline")

                last_status = False

        except Exception:

            state["cam_online"] = False

            if last_status:
                log_event("ESP32-CAM connection lost")

            last_status = False

        time.sleep(0.5)


# ============================================================
#                 STATUS API
# ============================================================

@app.route("/status", methods=["GET"])
def get_status():
    return jsonify(state)


# ============================================================
#                 DISTANCE API
# ============================================================

@app.route("/distance", methods=["GET", "POST"])
def update_distance():

    if request.method == "POST":

        data = request.get_json(
            force=True,
            silent=True
        ) or {}

        distance = data.get("distance")

        if distance is None:
            distance = request.form.get("distance")

        if distance is None:
            distance = request.args.get("distance")

        if distance is None and request.data:

            try:
                distance = request.data.decode("utf-8").strip()
            except Exception:
                pass

        if distance is not None:

            distance_string = str(distance).strip()

            if distance_string not in (
                "",
                "null",
                "None"
            ):

                state["distance_cm"] = distance_string

                log_event(
                    f"Ultrasonic distance: {distance_string} cm"
                )

        return jsonify({
            "status": "ok",
            "distance_cm": state["distance_cm"]
        })

    return jsonify({
        "distance_cm": state["distance_cm"]
    })


# ============================================================
#                 MAIN DASHBOARD
# ============================================================

@app.route("/")
def index():
    cam_ip = os.environ.get("CAM_IP", CAM_IP)
    html = f"""<!DOCTYPE html>
<html lang="en">
<head>
    <meta charset="UTF-8">
    <meta name="viewport" content="width=device-width, initial-scale=1.0, maximum-scale=1.0, user-scalable=no">
    <title>VisionGuide Mobile</title>
    <style>
        * {{ box-sizing: border-box; margin: 0; padding: 0; }}
        body {{
            background-color: #000000;
            color: #ffffff;
            font-family: sans-serif;
            height: 100vh;
            display: flex;
            flex-direction: column;
            overflow: hidden;
        }}
        /* TOP HALF */
        .top-half {{
            height: 50vh;
            position: relative;
            background-color: #111;
            overflow: hidden;
        }}
        .stream-img {{
            width: 100%;
            height: 100%;
            object-fit: cover;
        }}
        .live-badge {{
            position: absolute;
            top: 16px;
            left: 16px;
            background-color: #ef4444;
            color: white;
            font-size: 12px;
            font-weight: bold;
            padding: 4px 8px;
            border-radius: 4px;
            letter-spacing: 1px;
            z-index: 10;
        }}
        .scanlines {{
            position: absolute;
            top: 0; left: 0; right: 0; bottom: 0;
            background: linear-gradient(to bottom, rgba(255,255,255,0), rgba(255,255,255,0) 50%, rgba(0,0,0,0.2) 50%, rgba(0,0,0,0.2));
            background-size: 100% 4px;
            z-index: 5;
            pointer-events: none;
        }}
        /* BOTTOM HALF */
        .bottom-half {{
            height: 50vh;
            display: flex;
            flex-direction: column;
            align-items: center;
            justify-content: center;
            padding: 16px;
            text-align: center;
        }}
        .ai-instruction {{
            font-size: 24px;
            font-weight: bold;
            margin-bottom: 24px;
            flex-grow: 1;
            display: flex;
            align-items: center;
            justify-content: center;
            width: 100%;
        }}
        .pill-row {{
            display: flex;
            gap: 16px;
            margin-bottom: 16px;
        }}
        .pill {{
            padding: 6px 16px;
            border-radius: 999px;
            font-weight: bold;
            font-size: 14px;
            color: #000;
        }}
        .pill.green {{ background-color: #22c55e; }}
        .pill.red {{ background-color: #ef4444; }}
        .pill.amber {{ background-color: #f59e0b; }}
        
        .distance {{ color: #22c55e; font-size: 18px; font-weight: bold; margin-bottom: 12px; }}
        
        .latency {{
            color: #888888;
            font-size: 12px;
            margin-bottom: 16px;
        }}
        .event-log {{
            color: #555555;
            font-family: monospace;
            font-size: 11px;
            text-align: left;
            width: 100%;
            height: 60px;
            overflow: hidden;
            display: flex;
            flex-direction: column;
            justify-content: flex-end;
        }}
        .event-log div {{ margin-top: 2px; }}
    </style>
</head>
<body>
    <div class="top-half">
        <div class="live-badge">LIVE</div>
        <div class="scanlines"></div>
        <img class="stream-img" src="http://{cam_ip}:81/stream" onerror="this.src='data:image/svg+xml;utf8,<svg xmlns=\\'http://www.w3.org/2000/svg\\'><rect width=\\'100%\\' height=\\'100%\\' fill=\\'%23222\\'/><text x=\\'50%\\' y=\\'50%\\' fill=\\'%23666\\' text-anchor=\\'middle\\'>Stream Offline</text></svg>'">
    </div>
    <div class="bottom-half">
        <div class="ai-instruction" id="ai-instruction">Waiting for AI...</div>
        <div class="pill-row">
            <div class="pill red" id="cam-pill">CAM</div>
            <div class="pill amber" id="ai-pill">AI</div>
        </div>
        <div class="distance" id="distance">Distance: -- cm</div>
        <div class="latency" id="latency">Latency: -- ms</div>
        <div class="event-log" id="event-log"></div>
    </div>

    <script>
        let lastResult = "";
        
        function speak(text) {{
            if (!text) return;
            window.speechSynthesis.cancel();
            const msg = new SpeechSynthesisUtterance(text);
            window.speechSynthesis.speak(msg);
        }}

        async function pollStatus() {{
            try {{
                const res = await fetch('/status');
                const state = await res.json();
                
                // Camera status
                const camPill = document.getElementById('cam-pill');
                if (state.cam_online) {{
                    camPill.className = 'pill green';
                }} else {{
                    camPill.className = 'pill red';
                }}
                
                // AI Status
                const aiPill = document.getElementById('ai-pill');
                if (state.ai === 'READY') {{
                    aiPill.className = 'pill green';
                }} else if (state.ai === 'PROCESSING' || state.ai === 'BUSY') {{
                    aiPill.className = 'pill amber';
                }} else {{
                    aiPill.className = 'pill red';
                }}
                
                // Instruction and TTS
                if (state.last_result && state.last_result !== lastResult) {{
                    lastResult = state.last_result;
                    document.getElementById('ai-instruction').innerText = lastResult;
                    speak(lastResult);
                }}
                
                // Latency
                document.getElementById('latency').innerText = `Latency: ${{state.latency_ms}} ms`;
                
                // Distance
                document.getElementById('distance').innerText = 
                    state.distance_cm ? `Distance: ${{state.distance_cm}} cm` : "Distance: -- cm";
                
                // Event Log (last 4)
                const logDiv = document.getElementById('event-log');
                const recentLogs = state.event_log.slice(-4);
                logDiv.innerHTML = recentLogs.map(log => `<div>${{log}}</div>`).join('');
                
            }} catch(e) {{
                console.error("Poll error", e);
            }}
            setTimeout(pollStatus, 1000);
        }}
        
        // Initial tap to enable TTS on mobile
        document.body.addEventListener('click', () => {{
            const msg = new SpeechSynthesisUtterance("");
            window.speechSynthesis.speak(msg);
        }}, {{once: true}});

        pollStatus();
    </script>
</body>
</html>"""
    return html


# ============================================================
#                 GROQ VISION PROCESSING (QWEN MODEL)
# ============================================================

def process_vision_data(image_data):

    if not image_data:
        return jsonify({
            "error": "empty image"
        }), 400

    state["cam_busy"] = True
    state["ai"] = "PROCESSING"

    start_time = time.time()

    try:

        if client is None:
            raise Exception(
                "Groq API key is not configured."
            )

        base64_image = base64.b64encode(
            image_data
        ).decode("utf-8")

        prompt = """
You are a navigation assistant for a visually impaired person.

Look at this single camera image.

Return ONE very short navigation instruction only.

Mention the main obstacle/object and a simple safe direction.

Use approximate steps only when visually reasonable.

Examples:

"Chair ahead. Move two steps right."

"Person ahead. Stop and wait."

"Clear ahead. Continue."

Do not explain your reasoning.
"""

        response = client.chat.completions.create(

            model="qwen/qwen3.8-27b",

            messages=[
                {
                    "role": "user",
                    "content": [
                        {
                            "type": "text",
                            "text": prompt
                        },
                        {
                            "type": "image_url",
                            "image_url": {
                                "url":
                                "data:image/jpeg;base64,"
                                + base64_image
                            }
                        }
                    ]
                }
            ],

            max_tokens=60,
            temperature=0
        )

        result_text = (
            response
            .choices[0]
            .message
            .content
            .strip()
        )

        state["last_result"] = result_text

        state["latency_ms"] = int(
            (time.time() - start_time) * 1000
        )

        state["ai"] = "READY"

        log_event(
            f"AI: {result_text} "
            f"({state['latency_ms']}ms)"
        )

        return jsonify({
            "result": result_text,
            "instruction": result_text,
            "latency_ms": state["latency_ms"]
        })

    except Exception as e:

        state["latency_ms"] = int(
            (time.time() - start_time) * 1000
        )

        state["ai"] = "ERROR"

        state["last_result"] = (
            "AI error: " + str(e)
        )

        log_event(
            "AI Error: " + str(e)
        )

        return jsonify({
            "error": str(e)
        }), 500

    finally:
        state["cam_busy"] = False


# ============================================================
#                 CAMERA → PYTHON /VISION
# ============================================================

@app.route(
    "/vision",
    methods=["POST"]
)
def process_vision():
    return process_vision_data(
        request.get_data()
    )


# ============================================================
#                 MANUAL VISION TRIGGER
# ============================================================

@app.route(
    "/vision_trigger",
    methods=["POST", "GET"]
)
def vision_trigger():
    cam_ip = os.environ.get("CAM_IP", CAM_IP)
    try:

        distance = None

        if request.method == "POST":
            data = request.get_json(
                force=True,
                silent=True
            ) or {}

            distance = data.get("distance")

        if distance is None:
            distance = request.form.get("distance") or request.args.get("distance")

        if distance is None and request.data:
            try:
                distance = request.data.decode("utf-8").strip()
            except Exception:
                pass

        if distance is not None and str(distance).strip() not in ("", "null", "None"):
            state["distance_cm"] = str(distance)
            log_event(f"OBSTACLE DETECTED: {distance} cm")
            print(f"[S3] Trigger received | Distance: {distance} cm")

        if not cam_ip:
            raise Exception("CAM_IP is not configured")

        log_event("Requesting fresh image from CAM")

        image_response = requests.get(
            f"http://{cam_ip}/capture",
            timeout=5
        )

        if image_response.status_code != 200:
            raise Exception(
                f"CAM capture HTTP {image_response.status_code}"
            )

        log_event("Fresh image received")

        return process_vision_data(
            image_response.content
        )

    except Exception as e:
        state["ai"] = "ERROR"
        state["last_result"] = f"Capture error: {str(e)}"
        log_event(f"Capture failed: {str(e)}")
        return jsonify({"error": str(e)}), 500


# ============================================================
#                 CONFIGURATION METHOD (FROM KOTLIN)
# ============================================================

def configure(
    cam_ip,
    groq_key,
    trigger_cm,
    cooldown_ms,
    port
):
    global CAM_IP
    global GROQ_API_KEY
    global TRIGGER_CM
    global COOLDOWN_MS
    global PORT
    global client

    CAM_IP = str(cam_ip)
    GROQ_API_KEY = str(groq_key)

    try:
        TRIGGER_CM = float(trigger_cm)
    except Exception:
        TRIGGER_CM = 30.0

    try:
        COOLDOWN_MS = int(cooldown_ms)
    except Exception:
        COOLDOWN_MS = 6000

    try:
        PORT = int(port)
    except Exception:
        PORT = 5000

    if GROQ_API_KEY:
        try:
            client = Groq(api_key=GROQ_API_KEY)
            state["ai"] = "READY"
            log_event("Groq AI configured")
        except Exception as e:
            client = None
            state["ai"] = "ERROR"
            log_event(f"Groq config error: {e}")
    else:
        client = None
        state["ai"] = "ERROR: No Groq Key"
        log_event("Groq API Key missing")


# ============================================================
#                 START SERVER
# ============================================================

def run_server(port):
    app.run(
        host="0.0.0.0",
        port=port,
        debug=False,
        use_reloader=False
    )

def main():
    global CAM_IP, PORT, client
    cam_ip = os.environ.get("CAM_IP", CAM_IP)
    port = int(os.environ.get("PORT", str(PORT)))

    if client is None and GROQ_API_KEY:
        try:
            client = Groq(api_key=GROQ_API_KEY)
            state["ai"] = "READY"
            log_event("AI Ready")
        except Exception as e:
            state["ai"] = "ERROR"
            log_event(f"AI init error: {e}")
    elif client is None:
        state["ai"] = "ERROR: No Groq Key"
        log_event("Groq API Key missing")
    else:
        state["ai"] = "READY"
        log_event("AI Ready")

    threading.Thread(
        target=check_camera,
        daemon=True
    ).start()

    threading.Thread(
        target=run_server,
        args=(port,),
        daemon=True
    ).start()

    while True:
        time.sleep(1)

if __name__ == "__main__":
    main()
