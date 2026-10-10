import os
import re
import shutil
import subprocess
import sys
import xml.etree.ElementTree as ET
from pathlib import Path

arguments = [argument for argument in sys.argv[1:] if argument != "--layout-only"]
adb = (
    arguments[0]
    if arguments
    else shutil.which("adb")
    or str(
        Path(os.environ.get("ANDROID_HOME", Path.home() / "Android/Sdk"))
        / "platform-tools/adb"
    )
)


def run(*arguments):
    return subprocess.run(
        [adb, *arguments], capture_output=True, text=True, check=True, timeout=15
    ).stdout


run("shell", "uiautomator", "dump", "/data/local/tmp/inkbridge-window.xml")
root = ET.fromstring(run("exec-out", "cat", "/data/local/tmp/inkbridge-window.xml"))
nodes = [node for node in root.iter("node") if node.get("package") == "com.inkbridge"]
layout = run("shell", "dumpsys", "activity", "com.inkbridge/.MainActivity")
surface = re.search(
    r"android\.view\.SurfaceView\{[^\n]*? (\d+),(\d+)-(\d+),(\d+)", layout
)
if surface is None:
    legacy = any("InkBridge Active" in node.get("text", "") for node in nodes)
    print(
        "FAIL: tablet is showing the legacy pen-only UI; install the new APK."
        if legacy
        else "FAIL: no USB video surface is visible; open the updated app and connect via USB."
    )
    sys.exit(1)
left, top, right, bottom = map(int, surface.groups())
if right <= left or bottom <= top:
    print(
        "FAIL: USB video SurfaceView is laid out at 0x0; video-ready handshake cannot start."
    )
    sys.exit(1)
if "--layout-only" in sys.argv:
    print(
        f"PASS: USB video SurfaceView has a nonzero layout: {right - left}x{bottom - top}."
    )
    sys.exit(0)
messages = [
    node.get("text", "")
    for node in nodes
    if node.get("class") == "android.widget.TextView" and node.get("text")
]
if messages:
    print("FAIL: video has not reached the rendering state.")
    for message in messages:
        if message.startswith(("USB", "Video", "Receiving", "Screen", "Desktop")):
            print(message)
    sys.exit(1)
print(
    "PASS: Android USB video surface is visible and the decoder's waiting/error overlay is gone."
)
