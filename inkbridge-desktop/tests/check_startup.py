import os
import re
import subprocess
import sys
import tempfile
import time
from pathlib import Path

binary = (
    Path(sys.argv[1]).resolve()
    if len(sys.argv) > 1
    else Path(__file__).resolve().parents[1] / "build" / "InkBridge"
)
env = dict(os.environ, QT_QPA_PLATFORM="xcb", QT_FORCE_STDERR_LOGGING="1")
result = 1

with tempfile.TemporaryFile(mode="w+") as log:
    app = subprocess.Popen([str(binary)], env=env, stdout=log, stderr=log)
    try:
        deadline = time.monotonic() + 8
        while app.poll() is None and time.monotonic() < deadline:
            clients = subprocess.run(
                ["xprop", "-root", "_NET_CLIENT_LIST"],
                capture_output=True,
                text=True,
                check=True,
            ).stdout
            for window_id in re.findall(r"0x[0-9a-fA-F]+", clients):
                window = subprocess.run(
                    [
                        "xprop",
                        "-id",
                        window_id,
                        "_NET_WM_PID",
                        "_NET_WM_NAME",
                        "_NET_WM_STATE",
                    ],
                    capture_output=True,
                    text=True,
                    check=True,
                ).stdout
                if (
                    re.search(
                        rf"^_NET_WM_PID\(CARDINAL\) = {app.pid}$", window, re.MULTILINE
                    )
                    and '"InkBridge Desktop"' in window
                    and "_NET_WM_STATE_HIDDEN" not in window
                ):
                    time.sleep(0.5)
                    if app.poll() is None:
                        print(
                            "PASS: InkBridge Desktop window appeared and the app stayed running."
                        )
                        result = 0
                    break
            if result == 0:
                break
            time.sleep(0.1)
        if result != 0:
            print(
                f"FAIL: no visible InkBridge Desktop window; exit status {app.poll()}."
            )
    finally:
        if app.poll() is None:
            app.terminate()
            try:
                app.wait(timeout=3)
            except subprocess.TimeoutExpired:
                app.kill()
                app.wait()
        if result != 0:
            log.seek(0)
            print(log.read(), end="")

sys.exit(result)
