# InkBridge

> Fork of [dagaza/InkBridge](https://github.com/dagaza/InkBridge). Installation, build instructions, Qt 6 compatibility, and USB screen mirroring modified on 2026-10-10. Original copyright and component license notices are preserved.

**The high-performance bridge between your Android device and your Linux desktop.**

*(Because Tux deserves a super display, too. If you just switched from Windows and are desperately Googling for a "SuperDisplay alternative for Linux"—welcome home.)*

InkBridge transforms any Android device into a high-precision, system-wide input surface for Linux. Whether you are using a professional S-Pen on a Galaxy Tab, a basic capacitive stylus, or your fingertips, InkBridge maps your raw touch data directly to a virtual Linux digitizer at the kernel level. 

Because it acts as a native hardware input device, it works flawlessly with **any** software on your computer—from creative powerhouses like **Krita, Blender, GIMP, and Inkscape**, to simple whiteboarding tools, PDF annotators, and even general desktop navigation.

### 🌐 Wired & Wireless Freedom
Engineered for near-zero latency and professional-grade control, InkBridge offers three dedicated connection protocols to fit your workflow:
* **USB (AOA):** The gold standard. Bypasses standard network layers for rock-solid, absolute zero-latency wired performance.
* **Wi-Fi Direct:** High-speed, tether-free creativity. Creates a secure, peer-to-peer tunnel that automatically bypasses complex Linux firewall and router configurations.
* **Bluetooth:** A convenient, completely wireless backup option for quick sketching and casual navigation on the go.

#### Desktop UI
![InkBridge-Desktop UI Screenshot](docs/screenshot.png)

#### Android Tablet UI
![Inkbridge-Android UI Screenshot](docs/android_screenshot.png)

## ✨ Key Features

* **Universal Input:** Supports S-Pen (Pressure/Tilt), capacitive styluses, and multi-touch finger input.
* **USB Wired Connection:** Uses the **Android Open Accessory (AOA)** protocol for near-zero latency input.
* **USB Screen Mirroring:** Show one existing Linux monitor on Android while drawing with real pen pressure, using portal-approved capture and native H.264 decoding.
* **Professional-Grade Input Support:**
    * **Logarithmic Pressure Curve:** Custom-tuned math for natural, responsive strokes (4096 levels).
    * **True 3D Tilt:** Projects Android sensor data to standard Linux EV_ABS tilt axes (-90° to +90°).
    * **Reliable Eraser:** "Clean Handover" protocol ensures 100% reliable tool switching between Pen and Eraser during hover or active strokes.
    * **Finger/Stylus-only Input Options:** Choose between finger or stylus-only input to ensure touching the screen with your hand won't cause unwanted inputs.
* **Linux Native Virtual Driver:** Creates a system-level `uinput` device ("pen-emu") compatible with X11 and Wayland (libinput). Use your stylus as a mouse.
* **Modern Desktop UI (Qt/QML):**
    * **Multi-Monitor Mapping:** Select exactly which screen the tablet maps to.
    * **Customizable Physics:** Adjust Pressure Sensitivity and Minimum Threshold (Deadzone).
    * **Rotation Correction:** Swap X/Y axes for landscape/portrait mismatches.
    * **Theming:** Built-in Dark and Light modes for both the desktop and Android apps.
* **Zero Bloat:** Minimalistic UI and software implementation

## 🛠️ Architecture

InkBridge is a complete modernization of the Linux-Android drawing pipeline:

1.  **Android App:** Written in **Kotlin**. Captures raw `MotionEvent` data, converts Tilt/Orientation radians to degrees, and streams packed binary structs via USB. The USB drawing surface also decodes and displays H.264 video with Android MediaCodec.
2.  **Linux Desktop Client:** Written in **C++20** with **Qt/QML**. Claims the USB device, decodes pen packets, and injects events into the Linux kernel via `uinput`. For mirroring, the ScreenCast portal/PipeWire captures a monitor and GStreamer sends encoded video in the reverse USB direction.

### Why Wi-Fi Direct instead of standard Wi-Fi?
Connecting Android to Linux over a standard home network is notoriously unreliable. Different Linux distributions (Ubuntu vs. Fedora vs. Arch) handle firewalls (ufw, firewalld, iptables) and hotspot configurations differently. To prevent users from having to debug their local router and firewall settings, InkBridge uses Wi-Fi Direct. Android creates the network and acts as the TCP Server, while the Linux client connects *outbound*, naturally bypassing strict Linux inbound firewalls.

## 🚀 Installation & Build

### Building and installing the desktop app

#### Prerequisites
* **Qt 5 or Qt 6** (Core, Gui, Qml, Quick, Svg, Widgets, Network, Bluetooth, DBus)
* **Qt 6 builds:** also require the **Qt5Compat GraphicalEffects** QML runtime (`qt6-qt5compat` on Fedora).
* **libusb-1.0** development headers
* **GStreamer 1.20+** development headers (core, app, video), plus PipeWire, OpenH264, and H.264 parser runtime plugins
* **CMake 3.16+**, pkg-config, and a **C++20** compiler

On Fedora 44:
```bash
sudo dnf install -y gcc-c++ cmake make pkgconf-pkg-config \
  qt6-qtbase-devel qt6-qtdeclarative-devel qt6-qtsvg-devel \
  qt6-qtconnectivity-devel qt6-qt5compat libusb1-devel \
  gstreamer1-devel gstreamer1-plugins-base-devel pipewire-gstreamer \
  gstreamer1-plugins-bad-free gstreamer1-plugin-openh264
```

```bash
git clone https://github.com/Glaicer/InkBridge.git
cd InkBridge
cmake -S inkbridge-desktop -B inkbridge-desktop/build -DCMAKE_BUILD_TYPE=Release
cmake --build inkbridge-desktop/build --parallel
sudo ./INSTALL.sh
```

The installer uses only `inkbridge-desktop/build/InkBridge`, not the older prebuilt binary committed upstream. It installs the app, udev rules, icon, and desktop entry. Script paths are resolved relative to the installer, not your current directory. Any failed command stops installation instead of reporting success.

Log out and back in once if the installer changed your group memberships.

#### Uninstalling
```bash
sudo ./inkbridge-desktop/UNINSTALL.sh
```

This removes the installed app, udev rules, icon, and desktop entry. Build/runtime packages and group memberships are retained. Only use `--remove-groups` if the installer added you to `input`/`plugdev` and those memberships are not needed by other software.

#### Startup regression check
With Python 3, `xprop`, and a running X11/XWayland session:
```bash
python3 inkbridge-desktop/tests/check_startup.py
```
The check opens the built app, verifies that its window appears and stays running, then closes the test instance.

#### Building the Android APK
Install the Android SDK first. A full JDK with `javac` is required; a Java runtime alone is insufficient. On Fedora 44, the build was verified with `java-25-openjdk-devel`:
```bash
sudo dnf install -y java-25-openjdk-devel
export JAVA_HOME=/usr/lib/jvm/java-25-openjdk
export ANDROID_HOME="$HOME/Android/Sdk"
cd inkbridge-android
./gradlew assembleDebug --console=plain
```
Set `ANDROID_HOME` to your actual SDK location. The debug-signed APK is generated at `inkbridge-android/app/build/outputs/apk/debug/app-debug.apk` relative to the repository root. If Gradle still reports a missing Java compiler after installing the JDK, run `./gradlew --stop` and retry.

Local SDK paths, generated builds, and APKs are not committed.

### USB screen mirroring

Install both updated desktop and Android builds. Video and pen input travel in opposite directions of the existing Android Open Accessory USB connection; ADB, USB debugging, Wi-Fi, and network ports are not required for use.

1. Connect the tablet with a data-capable USB cable and allow the Android accessory permission.
2. Select the monitor in **Map to Screen** on the desktop. When the Android video surface is ready, **Mirror via USB** becomes available.
3. Start with **720p / 30 fps**, click **Mirror via USB**, and approve sharing the same monitor in the system dialog. Monitor geometry is matched automatically when the portal supplies position and size; otherwise the selected input mapping is retained, so select the same monitor in both places.
4. Open your drawing application on that monitor. Android shows the mirrored image with its aspect ratio preserved, and pen coordinates are normalized inside the image rather than the surrounding black bars.
   The Android app retains its existing landscape orientation when the tablet is turned; the desktop image is not rotated into portrait.
5. **Stop Sharing** stops capture and video without disconnecting pen input. Unplugging the cable closes the session; reconnect and approve screen sharing again when needed.

The presets bound the video to 1280x720 at 30/60 fps or 1920x1080 at 30 fps; portrait and other aspect ratios use smaller dimensions without stretching. The current desktop encoder is software OpenH264. Android uses its native H.264 decoder and enables low-latency decoding when supported. Actual frame rate and delay depend on the devices and must be measured; 60 fps is a ceiling, not a guarantee.

Xiaomi Pad 5 with Xiaomi Smart Pen supports pressure sensitivity. Existing pressure, eraser/button, and tilt packets remain unchanged; video does not synthesize pressure or add missing hardware capabilities. Verify variable-width strokes with a pressure-sensitive brush on the actual tablet.

Screen capture uses the desktop ScreenCast portal and PipeWire, including on Wayland. A working portal backend is required. Sharing is explicitly approved by the user, limited to one existing monitor, and stops on USB disconnection or revoked permission. This is mirroring, not an additional virtual monitor. Wi-Fi and Bluetooth remain input-only.

#### Automated checks
```bash
ctest --test-dir inkbridge-desktop/build --output-on-failure
cd inkbridge-android
./gradlew :app:testDebugUnitTest --tests com.inkbridge.UsbVideoProtocolTest --console=plain
```
The desktop checks exercise the real OpenH264 pipeline for all presets, capability negotiation, wire framing, queue recovery, and isolated D-Bus portal permission/cancellation handling. Android tests check the same framing fixture, fragmented USB reads, unsupported accessory `available()` calls, input bounds, AVC configuration, and Xiaomi-sized aspect-ratio fitting. Live video, pen alignment, pressure, and stop/reconnect behavior were also verified on a Xiaomi Pad 5; remaining hardware checks and limitations are listed in the documentation below.

The wire format and hardware verification checklist are documented in [docs/usb-screen-mirroring.md](docs/usb-screen-mirroring.md).

#### Setting up Permissions (`uinput`)
To allow InkBridge to create a virtual tablet device without running as root (sudo), you must configure `udev` rules.

1.  Create the rule file:
    ```bash
    sudo nano /etc/udev/rules.d/99-inkbridge.rules
    ```
2.  Paste the following content:
    ```bash
    # Allow access to uinput for virtual device creation
    KERNEL=="uinput", MODE="0660", GROUP="input"

    # Optional: Allow access to Android USB devices (Samsung/Google/etc)
    SUBSYSTEM=="usb", ATTR{idVendor}=="18d1", MODE="0666", GROUP="plugdev"
    SUBSYSTEM=="usb", ATTR{idVendor}=="04e8", MODE="0666", GROUP="plugdev"
    ```
3.  Apply changes:
    ```bash
    sudo udevadm control --reload-rules
    sudo udevadm trigger
    sudo usermod -aG input $USER
    ```
    *(Note: You may need to logout and login for group changes to take effect).*

## 🎮 Usage

1.  **Install the Android App** on your tablet/phone.
2.  **Install the desktop App** on your Linux desktop.
3.  Choose your connection method:

### Via USB (Recommended for 0ms Latency):
* Connect your device via USB
* Accept the *"Open with InkBridge"* prompt on your Android device (confirmation window will appear when plugging in the USB cable)
* Start drawing

### Via Wi-Fi Direct (Recommended Wireless):
* Press Connect via *Wi-Fi Direct* on the desktop app
* Tap Connect via *Wi-Fi Direct* on the Android app (a pop-up with instructions will appear)
* Connect your PC to the DIRECT-IB-INKBRIDGE Wi-Fi Network using the provided password
* Tap *"Desktop is Connected"* on the Android app (inside the pop-up) to initiate the stream
* *Note: Your PC will disconnect from your current Wi-Fi connection. It is highly recommended to use an Ethernet connection for your PC if you need internet access while drawing.*

### Via Bluetooth (Backup Option):
* Pair your tablet and PC in your system Bluetooth settings
* Tap *"Connect via Bluetooth"* in the Android app and select your PC in the discovered devices list.


### Advanced Controls
* **Pressure Sensitivity:** Adjusts the logarithmic curve. Higher values make it easier to reach 100% pressure.
* **Minimum Pressure:** Sets a deadzone floor to ignore accidental light touches (ghosting).
* **Reset Connection:** If the device gets stuck or the USB handle is busy, click the "Reset Connection" button in the sidebar to force a USB bus reset and driver re-attachment.

## 🔧 Troubleshooting

**"Error claiming interface: LIBUSB_ERROR_BUSY"**
This happens if the Linux kernel (`cdc_acm` or `usbfs`) grabs the Android device before InkBridge does.
* *Fix:* InkBridge includes an auto-detach feature. If this persists, click the **Reset Connection** button in the sidebar.

**Stylus moves but doesn't click/draw**
* *Fix:* Ensure you have set the "Map to Screen" option correctly in the UI. If the coordinates are out of bounds relative to the selected monitor, clicks may be rejected.

**Tilt is inverted**
* *Fix:* Check the "Fix Rotation (Swap X/Y)" checkbox in the Advanced Settings if you are using the tablet in a different orientation than the PC screen.

**Bluetooth feels a bit laggy or "floaty"**
* Fix: This is unfortunately an unavoidable hardware limitation. Standard Bluetooth mice use the HID profile (7-15ms latency), which operates at the hardware controller level. InkBridge must use the SPP/RFCOMM profile (30-80ms with 150ms spikes) because Android does not expose HID for custom data streams. The delay happens inside Android's internal radio scheduler, not the app. If you need lower latency, switch to Wi-Fi Direct or USB.

## ❤️ Acknowledgments & Credits

This project was originally inspired by the "Android Virtual Pen" application.

While the original project is no longer maintained/deprecated, it served as the proof-of-concept for USB AOA digitizer communication. **InkBridge** represents a total rewrite and modernization of that original concept:

* **Refactored Android App:** Complete rewrite from Java to **Kotlin**, optimizing the event loop and sensor data processing.
* **Refactored Desktop Client:** Complete rewrite from legacy C++ to **C++20**, improving memory safety and concurrency.
* **Modern UI:** Replaced the archaic GTK/X11 interface with a fluid, hardware-accelerated **Qt Quick (QML)** interface.
* **New In-App Features:** Added support for **Multi-Monitor Mapping**, **3D Tilt** (Tilt X/Y), and **Eraser Button** support (Linux Kernel Tool Switching).
* **New Connectivity Features:** Beyond vastly improving the over-USB experience, we have also added full **Bluetooth** and **Wi-Fi Direct (P2P)** support.


## 📄 License

The desktop and Android components declare **GNU Affero General Public License v3.0 or later (AGPL-3.0-or-later)** in their `LICENSE.txt` files. This fork follows those declarations and preserves the original per-file license and copyright notices.

You are free to:

* Use the software for any purpose
* Study how the software works and modify it
* Redistribute copies
* Distribute modified versions

Under the following terms:
* Distributed derivative versions must comply with AGPL-3.0-or-later
* Corresponding source must be provided when distributing binaries; modified versions must also offer it to users interacting remotely over a network as required by AGPL section 13
* License notices and copyright statements must be preserved

This project is distributed **without any warranty**, including without implied warranties of merchantability or fitness for a particular purpose. See the full license text for details.

The complete license text is available in [inkbridge-desktop/LICENSE.txt](inkbridge-desktop/LICENSE.txt), [inkbridge-android/LICENSE.txt](inkbridge-android/LICENSE.txt), and at https://www.gnu.org/licenses/agpl-3.0.html.

Upstream metadata is inconsistent: its README stated GPL-3.0, while the inherited `PKGBUILD` still states MIT. Neither label matches the component `LICENSE.txt` files. The original license texts, including the notice about BSD-licensed contributions, remain unchanged.
