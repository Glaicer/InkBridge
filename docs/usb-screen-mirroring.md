# USB screen mirroring

This feature mirrors one existing Linux monitor to Android over the same Android Open Accessory connection that carries pen input. It does not create another monitor or stream over Wi-Fi/Bluetooth.

## Data flow

- Android to desktop: unchanged 22-byte little-endian pen packets (coordinates, pressure, tilt, tool and action), plus reserved control packets.
- Desktop to Android: framed H.264 access units, with SPS/PPS repeated on IDR frames. Capture uses the ScreenCast portal and PipeWire; encoding uses low-latency NVIDIA NVENC with CUDA scaling/color conversion when available, otherwise GStreamer OpenH264; rendering uses Android MediaCodec and a SurfaceView.
- Capture starts only after the updated tablet advertises support and the desktop user explicitly approves sharing. Older Android builds continue to receive no unsolicited video.
- USB bulk endpoint addresses are discovered from accessory interface 0 rather than assumed. The video writer and pen reader are separate threads/directions. A partial or stalled video transfer ends the connection rather than leaving the receiver at an ambiguous frame offset.

## Protocol v1

### Android control messages

Controls reuse the 22-byte pen packet layout. `toolType = 127` is reserved; controls and legacy all-127 heartbeat packets must never be injected as stylus events.

| Action | x | Other int32 fields | Meaning |
|---|---:|---:|---|
| 1 | 1 | 0 | Video reader/surface ready; protocol version 1 |
| 2 | 0 | 0 | Discard pending dependent frames and request a fresh IDR |
| 3 | 0 | 0 | Undo (`Ctrl+Z`), accepted only from a ready tablet and injected only while mirroring |

The normal pen packet bytes and pressure scaling are unchanged.

### Desktop video packets

All integers are little-endian. Each header is exactly 32 bytes and is followed immediately by the payload.

| Offset | Bytes | Field |
|---:|---:|---|
| 0 | 4 | Magic `IBV1` (`0x31564249`) |
| 4 | 4 | Unsigned payload length, at most 4 MiB |
| 8 | 4 | Encoded width |
| 12 | 4 | Encoded height |
| 16 | 4 | Flags: 0 = dependent frame; 1 = keyframe; 2 = stopped/status |
| 20 | 4 | Sequence number, wrapping uint32 |
| 24 | 8 | Nonnegative presentation timestamp in microseconds, normalized to pipeline running time |

Video dimensions are 2..4096; payloads contain one Annex-B H.264 access unit. Stopped packets have width and height zero and at most 4096 bytes of UTF-8 status text. Android validates the header before allocating the payload.

Desktop writes chunks no larger than 16 KiB. Android buffers raw accessory reads with a 16 KiB BufferedInputStream before parsing small headers, and handles fragmented/coalesced frames. Its input wrapper reports zero available bytes instead of querying the accessory descriptor: Xiaomi Pad 5 returns `EINVAL` from `FileInputStream.available()`. Reads remain blocking. Do not read just a 32-byte header directly from the accessory descriptor: short reads can truncate accessory transfers on device implementations.

Both encoded-frame queues hold at most three access units. Overflow discards the queued GOP, waits for an IDR and requests a new keyframe, rather than showing a growing backlog or rendering dependent frames with missing references. Decoder surfaces and stop/restart operations invalidate old decoder state.

## Geometry and lifecycle

The image is aspect-fitted into a centered Android input area. The image and that area's input coordinates share a uniform view transform: Android's child event dispatch applies its inverse before the unchanged pen packet encoder normalizes coordinates. Pinching two fingers zooms from 1x to 8x around their focus; moving both fingers pans. Panning is bounded to the image edges, and axes smaller than the view remain centered. Black bars do not generate pen samples. The existing Android `sensorLandscape` orientation is preserved: turning the tablet does not rotate the desktop image into portrait, which is the intended behavior. If the surface is recreated, it requests fresh codec configuration. Initial layout must retain match-parent sizing until the view has nonzero dimensions; a 0x0 surface cannot advertise video readiness. The manual swap-axis setting is disabled during mirroring, since rotating input independently of the displayed image would break alignment.

While video is available, finger contacts are reserved for local navigation, regardless of the stylus-only setting; even the first finger never sends a drawing/click packet. Stylus/eraser samples use the same inverse transform for contact, hover, and historical samples, without modifying pressure, tilt, or button state. Mixed finger/pen events are reduced to the pen pointer using public MotionEvent APIs, including historical coordinates and sensor data. Navigation stops during pen contact and remains suspended until all fingers lift, so palm contacts cannot move the image during a stroke. Without video, the existing finger/stylus input preference remains in effect.

Zoom affects only the tablet, not desktop capture or monitor mapping. Changing source resolution or view dimensions, stopping sharing, and reconnecting restore the fitted 1x view. Zoom enlarges the existing encoded image and adds no source detail; the 1080p and 1440p presets can provide more detail than 720p. SurfaceView positioning/scaling is synchronized with view rendering starting with Android 7.0, the application's existing minimum supported version.

When the portal supplies monitor position and size, the desktop maps input to that monitor. Those values are compositor/logical coordinates, not necessarily encoded pixel dimensions. If either is unavailable, the user must select the same monitor in Map to Screen and the sharing dialog. Unrecognized returned geometry stops sharing rather than silently sending input to a different screen.

Stop Sharing, rejected/revoked capture permission and decoder errors leave pen input available. USB disconnection stops capture, drains/closes the USB streams and releases the decoder. Reconnection negotiates support again and requires a new sharing session.

## Xiaomi Smart Pen buttons

During active USB mirroring in foreground InkBridge, the Xiaomi Smart Pen notes button (`KEYCODE_PAGE_DOWN`, 93) holds the desktop's virtual eraser; releasing it restores the pen. The screenshot/selection button (`KEYCODE_PAGE_UP`, 92) sends one `Ctrl+Z` shortcut per press to the focused desktop application. Keep Krita focused to undo in Krita. Key auto-repeat does not repeat Undo, and page keys from ordinary keyboards are not intercepted. Leaving InkBridge, stopping video, removing the pen or disconnecting USB clears held button state. The eraser uses the existing pen action bit, with unchanged coordinates, pressure and tilt; a stationary pen's last sample is replayed on button transitions so no movement is needed to change tools.

On the tested Xiaomi Pad 5 (Android 13 / HyperOS 1.0, V816.0.6.0.TKXMIXM), Xiaomi handles these buttons before ordinary app key handling and opens Mi Canvas or the screenshot overlay. A local physical-button diagnostic confirmed that setting `Settings.Secure.gb_boosting` to `1` suppresses both menus while both key-down and key-up still reach InkBridge. This is Xiaomi's **global game-mode flag**, not a dedicated pen setting: it can also affect power, notifications, gestures and display policies. The behavior is firmware-specific and is not guaranteed on other Xiaomi versions.

The optional workaround is **off by default**. To opt in:

1. Install both updated builds, connect the tablet with USB debugging enabled, and record the original value:
   ```bash
   adb shell settings get secure gb_boosting
   ```
2. Explicitly grant the broad secure-settings permission once:
   ```bash
   adb shell pm grant com.inkbridge android.permission.WRITE_SECURE_SETTINGS
   ```
3. In the Android dashboard, open **Xiaomi pen buttons** (keyboard icon) and enable **Suppress Xiaomi pen shortcuts**. Start USB mirroring and keep InkBridge in the foreground.

InkBridge saves the original value before writing `1`, restores it on normal stop/pause/disconnection, and preserves an already-enabled external game mode. A different externally-written value is not overwritten on restore. If restoring fails, the saved value is retained for retry. After a crash or force-stop, immediate cleanup is not guaranteed: **reopen InkBridge to restore the saved value**. Avoid combining this workaround with another application managing the same game-mode flag. USB debugging is only needed for setup/recovery, not for button or video transport during normal use.

To remove access, stop sharing or leave InkBridge first, verify the original value has returned, turn off the workaround, then run:

```bash
adb shell pm revoke com.inkbridge android.permission.WRITE_SECURE_SETTINGS
```

Do not revoke the permission while an owned setting still needs restoration. Manual recovery should restore the value recorded before opting in (or delete the setting if it was originally absent), not blindly force a particular value. Without this opt-in or permission, InkBridge does not change the Xiaomi flag; button mappings still work if firmware delivers the keys, but system menus may interrupt them.

## Dependencies

The implementation uses existing Qt/libusb and adds Qt DBus and GStreamer core/app/video development libraries. Fedora package versions were checked against its repositories during implementation: GStreamer 1.28.7 and pipewire-gstreamer 1.6.9. The source accepts GStreamer 1.20 or newer; Fedora build commands are in README.md. Android adds no external libraries.

NVIDIA acceleration is optional and requires a supported GPU/driver plus compatible GStreamer `nvh264enc`, `cudaupload` and `cudaconvertscale` plugins. The NVENC path drops excess raw frames before uploading, scales/converts on the GPU and disables B-frames/lookahead. If plugins are absent or hardware initialization fails before producing video, capture uses software OpenH264; `INKBRIDGE_SOFTWARE_VIDEO=1` forces that path. The desktop status identifies the active encoder. Android chooses its platform AVC decoder; hardware acceleration and optional low-latency decoding depend on that device. Resolution/fps presets are upper bounds, not performance guarantees.

## Quality and latency

| Preset | Maximum video size | Target bitrate | Status |
|---|---|---|---|
| 720p / 30 fps | 1280x720 | 6 Mbit/s | Existing |
| 1080p / 30 fps | 1920x1080 | 12 Mbit/s | Existing |
| 720p / 60 fps | 1280x720 | 8 Mbit/s | User reported lower drawing delay than 30 fps |
| 1080p / 60 fps | 1920x1080 | 16 Mbit/s | Added; end-to-end hardware check pending |
| 1440p / 60 fps | 2560x1440 | 24 Mbit/s | Live NVENC stream measured on Xiaomi Pad 5 |
| 720p / 120 fps | 1280x720 | 12 Mbit/s | Experimental; live NVENC stream and pen checked |
| 1080p / 120 fps | 1920x1080 | 24 Mbit/s | NVENC, experimental; live stream, pressure and zoom/pan checked |
| 1440p / 120 fps | 2560x1440 | 40 Mbit/s | NVENC, experimental; live stream, pressure and zoom/pan checked |

These are bitrate targets, not strict peak-rate caps. USB carries compressed AVC, not uncompressed display pixels. On the tested system, the Xiaomi Pad 5 connection negotiated USB High-Speed at a nominal 480 Mbit/s; maximum useful accessory throughput was not measured. A 24 Mbit/s stream targets approximately 3 MB/s, and 40 Mbit/s approximately 5 MB/s, before framing and transport overhead, so WQHD does not inherently require USB 3. Successful live streams establish operation for the tested content, not spare bandwidth or sustained target bitrate under complex motion.

The monitor was running at 2560x1440 and approximately 144 Hz; the tablet reported 2560x1600 and an active 120 Hz display mode. Mirroring that monitor at 1440p preserves its 16:9 image within the tablet's 16:10 display; it does not stretch the image to 2560x1600 or create additional source detail. High display refresh rates alone do not guarantee that the capture, encoder or decoder supplies as many new images.

Short synthetic software encoding probes on a Ryzen 7 5800H sustained 1080p/60 and 1440p/60 for simple content. With a static black 2560x1440 source, 720p/120 sustained approximately 120 fps, while 1080p/120 and 1440p/120 produced approximately 98 and 86 fps respectively and dropped frames. NVENC on the RTX 3050 Laptop GPU sustained roughly 120 fps for the same simple synthetic source at all three resolutions, with approximately 4–6 ms from source timestamp to encoded output. These probes exclude portal capture, USB and Android rendering and are not guarantees for complex moving content. Higher-resolution 120 fps modes remain experimental and are intended for NVENC, not software encoding.

### Measured video delay

On 2026-10-10, a local diagnostic desktop build was tested with the RTX 3050 Laptop GPU and Xiaomi Pad 5 without replacing either installed application. Temporary keyframe timing anchors mapped GStreamer running time to the host monotonic clock; a persistent ADB clock probe synchronized Android `System.nanoTime()`. SurfaceFlinger presentation timestamps were then compared with each frame's capture timestamp. Clock-round-trip uncertainty was approximately 0.7–1.3 ms. This measures capture-timestamp-to-presentation delay, not physical pen-to-photon latency: input, drawing-application rendering, time before the capture timestamp and panel scanout are excluded.

| Encoder / selected mode | Presented FPS in short samples | Median video delay |
|---|---:|---:|
| Software OpenH264 / 720p/60, original pipeline | 55–56 | 59 ms |
| Software OpenH264 / 720p/60, scale before color conversion | 60 | 54 ms |
| NVENC / 720p/60 | 54–55 | 28–29 ms |
| NVENC / 720p/120 | 87–91 | 28–30 ms |
| NVENC / 1080p/120 | 83–84 | 31–33 ms |
| NVENC / 1440p/60 | 52–55 | 43–47 ms |
| NVENC / 1440p/120 | 78–80 | 33–34 ms |

Each valid timing sample used 127 presented frames; repeated samples agreed on the delay reduction. The 120 fps modes and 1440p/60 used a small animated desktop marker; earlier 60 fps checks included idle content, so cadence comparisons are not controlled throughput benchmarks. Desktop tracing showed approximately 35 ms of processing/queueing with the original software path versus about 5 ms with CUDA/NVENC. In the active 720p/120 check the desktop itself supplied around 90 fps, matching the tablet's cadence; that shortfall alone does not demonstrate a USB bottleneck. No tested live mode achieved sustained 120 fps. All results are short diagnostic samples, not guarantees for long drawing sessions or other systems.

The user confirmed working pen input in 720p/120 and working pressure, zoom/pan and drawing in 1080p/120 and 1440p/120. 1080p/120 offers a tested quality/delay compromise; 1440p/120 offers more source detail. 1080p/60 has not been checked end-to-end on the tablet. Full physical pen-to-photon delay and maximum USB accessory throughput remain unmeasured. An earlier screenshot-based probe was inconclusive and is not used as latency evidence. The timing instrumentation and marker are temporary diagnostics, not runtime dependencies.

SurfaceFlinger samples from the previously installed 720p/60 stream also showed approximately 60.5 presented frames/s, with a median frame interval of 16.66 ms and a 95th percentile of 24.91 ms. Those earlier samples measure cadence only. Native tablet drawing avoids the return path through desktop rendering, capture, encoding, USB and decoding. Quantifying the complete drawing delay requires a synchronized input/render trace or a high-speed camera recording of the pen and both displays.

## Hardware acceptance checklist

Use these checks when validating another device, compositor, or build:

1. Connect a Xiaomi Pad 5 with a data-capable USB cable, accept accessory permission, and confirm Mirror via USB becomes enabled without USB debugging or Wi-Fi.
2. Choose the intended monitor in Map to Screen. Start 720p/30 and approve that monitor in the system sharing dialog. Confirm its live image appears on Android, including after leaving the desktop idle briefly.
3. In Krita or another drawing application, use a pressure-sensitive brush. Draw lightly and firmly; check that stroke thickness changes and input remains under the pen, including near the image corners. Xiaomi Smart Pen hardware supports 4096 pressure levels; verify the application's actual pressure response rather than relying on those specifications.
4. Touch the letterbox bars: they must not draw. Turn the tablet and confirm the desktop image retains its landscape proportions. Check alignment again, including after any surface recreation.
   Spread two fingers to zoom, pan to another part of the image with both fingers, and draw fine strokes there. Verify that strokes stay under the pen and land at the original monitor coordinates, with varying pressure. Pinch back to 1x and repeat elsewhere. Finger gestures must not draw or click on the PC, including when stylus-only input is disabled; palm contacts during a pen stroke must not move the viewport. Verify that stopping/restarting sharing resets zoom and preserves pen input.
5. Stop sharing: the image must be covered by the status message, while pen-only input still works. Start sharing again and test each intended quality preset, including pressure and coordinate alignment after zoom/pan. Compare actual presented frame rates and drawing delay; fall back to 720p/60 if a higher preset stutters or increases delay.
6. Cancel the sharing dialog and revoke a running sharing session through the desktop. Verify that the app remains responsive and pen input is retained.
7. Unplug during drawing/video, then reconnect. Verify that capture stops, Android returns to its connection screen, no threads retain a stale session, and sharing can be approved again.
8. Close the desktop while streaming and disconnect from Android. Check that capture permission, video threads and codec resources are released. Reopen both apps and connect again.
9. Measure perceived pen-to-image delay on the actual devices; do not infer latency from successful compilation or encoder unit tests.
10. With the optional Xiaomi workaround enabled, verify the original `gb_boosting` value is restored after Stop Sharing, Home/app switching and unplugging USB. Hold the notes button while drawing, release it, and check eraser/pen transitions, pressure and zoomed alignment in Krita. Press and hold the screenshot button: it must undo only once. Confirm normal keyboard Page Up/Page Down still work. Repeat after reconnecting and after opening InkBridge following a forced stop; no held eraser state should survive.


Automated protocol, encoder, shortcut-keyboard and isolated D-Bus portal checks are invoked with CTest. Android protocol/geometry/zoom and button/setting-lease checks use the existing JUnit dependency. `PenButtonsTest` covers source filtering, press/release, auto-repeat and session/device resets; `PenShortcutLeaseTest` covers original-value preservation, crash recovery, failed writes/restores and unrelated external changes. `PenButtonInputTest` checks real Android MotionEvents and unchanged 22-byte sensor/coordinate bytes when switching the eraser, including stationary pen, lift and proximity exit. `VideoTransformTest` checks focal-point preservation, simultaneous zoom/pan, bounds, and geometry resets. `UsbMirrorZoomTest` exercises Android's actual event dispatch and 22-byte pen packets after transformations, including batched pressure/tilt samples, hover/eraser input, mixed finger/pen contacts, letterbox rejection, and stop-sharing behavior. These checks do not prove USB throughput, MediaCodec behavior or drawing latency on the physical tablet.

With a connected device that permits test APK installation:

```bash
cd inkbridge-android
./gradlew :app:connectedDebugAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.class=com.inkbridge.PenButtonInputTest,com.inkbridge.UsbMirrorZoomTest,com.inkbridge.UsbMirrorViewTest \
  --console=plain
```

## Verified on Xiaomi Pad 5

The GNOME/Wayland desktop and Xiaomi Pad 5 were tested together over USB on 2026-10-10. The user confirmed a live desktop image, pen alignment, variable-width strokes from real pressure, retained landscape proportions when turning the tablet, pen input after Stop Sharing, and restored video/drawing after unplugging and reconnecting. Android selected `OMX.qcom.video.decoder.avc`; its logged rendering rate in the 720p/30 preset was approximately 30 fps. The tablet's 2560x1600 drawing view contained a 2560x1440 video surface without stretching.

The later NVENC/preset checks are listed under [Measured video delay](#measured-video-delay). Tilt/eraser behavior, letterbox pen rejection, permission revocation during streaming, and full physical pen-to-photon latency have not been independently verified on hardware. ADB was enabled for APK installation and diagnostics; operation with USB debugging disabled has not been separately tested.

Tablet-local pinch zoom was added afterward. All 21 protocol/geometry unit tests passed, and the updated application was installed without clearing data. The user subsequently confirmed that local zoom, panning, and drawing work on the Xiaomi Pad 5. Palm handling has not been independently verified. Device-side zoom tests built, but Xiaomi twice rejected the separate instrumentation APK with `INSTALL_FAILED_USER_RESTRICTED: Install canceled by user`; they have not run. Full Android lint remains blocked by the existing `MissingConstraints` error in `activity_main.xml`, with no findings in the new zoom modules.

For an already-connected, streaming device with ADB available:

```bash
python3 inkbridge-desktop/tests/check_usb_video.py
python3 inkbridge-desktop/tests/check_usb_video.py --layout-only
```

The first command checks for a nonzero video surface and the absence of the decoder's waiting/error overlay. The second isolates the first-layout regression. These checks do not measure latency or verify changing image content. The first-layout instrumentation test is in `UsbMirrorViewTest`; its APK built, but Xiaomi rejected installing the separate test package with `INSTALL_FAILED_USER_RESTRICTED`. The actual application's failing 0x0 layout and fixed 2560x1600 layout were verified through ADB dumpsys instead. The unsupported `available()` failure is covered by a unit test that was observed failing before the fix and passing afterward.
