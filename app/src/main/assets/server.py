import os
import time
import threading
import base64
import requests

from flask import Flask, request, jsonify
from groq import Groq


app = Flask(__name__)


# ============================================================
# STATE
# ============================================================

state = {
    "cam_online": False,
    "cam_busy": False,
    "ai": "STARTING",
    "latency_ms": 0,
    "last_result": "",
    "distance_cm": "--",
    "event_log": []
}


# ============================================================
# CONFIG
# VALUES ARE PROVIDED BY KOTLIN
# ============================================================

CAM_IP = ""
GROQ_API_KEY = ""
PORT = 5000
TRIGGER_CM = 30.0
COOLDOWN_MS = 6000

client = None


# ============================================================
# KOTLIN → PYTHON CONFIGURATION
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
    global PORT
    global TRIGGER_CM
    global COOLDOWN_MS
    global client

    CAM_IP = str(cam_ip).strip()
    GROQ_API_KEY = str(groq_key).strip()

    TRIGGER_CM = float(trigger_cm)
    COOLDOWN_MS = int(cooldown_ms)
    PORT = int(port)

    if GROQ_API_KEY:
        client = Groq(
            api_key=GROQ_API_KEY
        )
    else:
        client = None

    print()
    print("==========================================")
    print("VISIONGUIDE PYTHON CONFIGURED")
    print("==========================================")
    print("CAM IP:", CAM_IP)
    print("PORT:", PORT)
    print("TRIGGER:", TRIGGER_CM, "cm")
    print("COOLDOWN:", COOLDOWN_MS, "ms")
    print(
        "GROQ:",
        "READY" if client else "MISSING"
    )
    print("==========================================")


# ============================================================
# EVENT LOG
# ============================================================

def log_event(msg):

    if len(state["event_log"]) >= 12:
        state["event_log"].pop(0)

    timestamp = time.strftime("%H:%M:%S")

    state["event_log"].append(
        f"{timestamp}  {msg}"
    )

    print(f"[Event] {msg}")


# ============================================================
# CAMERA MONITOR
# ============================================================

def check_camera():

    global CAM_IP

    last_status = False

    while True:

        try:

            if not CAM_IP:
                time.sleep(1)
                continue

            url = f"http://{CAM_IP}/status"

            response = requests.get(
                url,
                timeout=1
            )

            if response.status_code == 200:

                state["cam_online"] = True

                if not last_status:
                    log_event(
                        "ESP32-CAM connected"
                    )

                last_status = True

            else:

                state["cam_online"] = False

                if last_status:
                    log_event(
                        "ESP32-CAM offline"
                    )

                last_status = False

        except Exception:

            state["cam_online"] = False

            if last_status:
                log_event(
                    "ESP32-CAM connection lost"
                )

            last_status = False

        time.sleep(0.5)


# ============================================================
# STATUS
# ============================================================

@app.route(
    "/status",
    methods=["GET"]
)
def get_status():

    return jsonify(state)


# ============================================================
# WEB UI DASHBOARD
# ============================================================

@app.route("/")
def index():
    cam_ip = CAM_IP
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
                
                const camPill = document.getElementById('cam-pill');
                if (state.cam_online) {{
                    camPill.className = 'pill green';
                }} else {{
                    camPill.className = 'pill red';
                }}
                
                const aiPill = document.getElementById('ai-pill');
                if (state.ai === 'READY') {{
                    aiPill.className = 'pill green';
                }} else if (state.ai === 'PROCESSING' || state.ai === 'BUSY') {{
                    aiPill.className = 'pill amber';
                }} else {{
                    aiPill.className = 'pill red';
                }}
                
                if (state.last_result && state.last_result !== lastResult) {{
                    lastResult = state.last_result;
                    document.getElementById('ai-instruction').innerText = lastResult;
                    speak(lastResult);
                }}
                
                document.getElementById('latency').innerText = `Latency: ${{state.latency_ms}} ms`;
                document.getElementById('distance').innerText = 
                    state.distance_cm ? `Distance: ${{state.distance_cm}} cm` : "Distance: -- cm";
                
                const logDiv = document.getElementById('event-log');
                const recentLogs = state.event_log.slice(-4);
                logDiv.innerHTML = recentLogs.map(log => `<div>${{log}}</div>`).join('');
                
            }} catch(e) {{
                console.error("Poll error", e);
            }}
            setTimeout(pollStatus, 1000);
        }}
        
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
# DISTANCE
# ============================================================

@app.route(
    "/distance",
    methods=["POST", "GET"]
)
def update_distance():

    if request.method == "POST":

        data = (
            request.get_json(
                force=True,
                silent=True
            )
            or {}
        )

        distance = data.get("distance")

        if distance is not None:

            state["distance_cm"] = str(
                distance
            )

            log_event(
                f"Ultrasonic: {distance} cm"
            )

        return jsonify({
            "status": "ok",
            "distance_cm":
                state["distance_cm"]
        })

    return jsonify({
        "distance_cm":
            state["distance_cm"]
    })


# ============================================================
# VISION PROCESSING
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
You are VisionGuide, an AI navigation assistant
for a visually impaired person.

Analyze this camera image.

Return ONE very short spoken navigation instruction.

Mention the main obstacle and safe direction.

Examples:

"Chair ahead. Move right."

"Person ahead. Stop and wait."

"Wall ahead. Move left."

"Clear ahead. Continue."

Do not explain your reasoning.
Keep the response under 15 words.
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
            (
                time.time()
                - start_time
            ) * 1000
        )

        state["ai"] = "READY"

        log_event(
            f"AI: {result_text} "
            f"({state['latency_ms']}ms)"
        )


        return jsonify({

            "result": result_text,

            "instruction":
                result_text,

            "latency_ms":
                state["latency_ms"]

        })


    except Exception as e:

        state["ai"] = "ERROR"

        state["last_result"] = (
            "AI error: " + str(e)
        )

        state["latency_ms"] = int(
            (
                time.time()
                - start_time
            ) * 1000
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
# DIRECT VISION ENDPOINT
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
# ESP32-S3 → PHONE
# /vision_trigger
# ============================================================

@app.route(
    "/vision_trigger",
    methods=["POST", "GET"]
)
def vision_trigger():

    try:

        distance = None


        if request.method == "POST":

            data = (
                request.get_json(
                    force=True,
                    silent=True
                )
                or {}
            )

            distance = data.get(
                "distance"
            )


        if distance is None:

            distance = request.form.get(
                "distance"
            )


        if distance is None:

            distance = request.args.get(
                "distance"
            )


        # --------------------------------------------
        # SAVE ULTRASONIC READING
        # --------------------------------------------

        if distance is not None:

            state["distance_cm"] = str(
                distance
            )

            log_event(
                f"OBSTACLE DETECTED: "
                f"{distance} cm"
            )


        print(
            f"[S3] Trigger received | "
            f"Distance: {distance} cm"
        )


        # --------------------------------------------
        # ASK ESP32-CAM FOR FRESH IMAGE
        # --------------------------------------------

        if not CAM_IP:

            raise Exception(
                "CAM_IP is not configured."
            )


        capture_url = (
            f"http://{CAM_IP}/capture"
        )


        log_event(
            "Requesting fresh image from CAM"
        )


        image_response = requests.get(
            capture_url,
            timeout=5
        )


        if image_response.status_code != 200:

            raise Exception(
                "CAM capture HTTP "
                + str(
                    image_response.status_code
                )
            )


        log_event(
            "Fresh image received"
        )


        # --------------------------------------------
        # SEND IMAGE TO QWEN
        # --------------------------------------------

        return process_vision_data(
            image_response.content
        )


    except Exception as e:

        state["ai"] = "ERROR"

        state["last_result"] = (
            "Capture error: " + str(e)
        )

        log_event(
            "Trigger error: " + str(e)
        )

        return jsonify({
            "error": str(e)
        }), 500


# ============================================================
# FLASK
# ============================================================

def run_server():

    print(
        f"[Flask] Starting on port {PORT}"
    )

    app.run(
        host="0.0.0.0",
        port=PORT,
        debug=False,
        use_reloader=False
    )


# ============================================================
# MAIN
# ============================================================

def main():

    global PORT

    print()
    print("=" * 55)
    print("             VISIONGUIDE")
    print("=" * 55)

    print(
        "CAM:",
        CAM_IP
    )

    print(
        "SERVER:",
        f"0.0.0.0:{PORT}"
    )

    print(
        "AI:",
        "Qwen 3.8 27B"
        if client
        else "NO GROQ KEY"
    )

    print("=" * 55)


    if client:

        state["ai"] = "READY"

        log_event(
            "Qwen AI initialized"
        )

    else:

        state["ai"] = "ERROR"

        log_event(
            "Groq API Key missing"
        )


    threading.Thread(
        target=check_camera,
        daemon=True
    ).start()


    threading.Thread(
        target=run_server,
        daemon=True
    ).start()


    # Keep Python alive

    while True:

        time.sleep(1)