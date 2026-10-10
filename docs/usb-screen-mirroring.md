# USB screen mirroring

This feature mirrors one existing Linux monitor to Android over the same Android Open Accessory connection that carries pen input. It does not create another monitor or stream over Wi-Fi/Bluetooth.

## Data flow

- Android to desktop: unchanged 22-byte little-endian pen packets (coordinates, pressure, tilt, tool and action), plus reserved control packets.
- Desktop to Android: framed H.264 access units, with SPS/PPS repeated on IDR frames. Capture uses the ScreenCast portal and PipeWire; encoding uses GStreamer OpenH264; rendering uses Android MediaCodec and a SurfaceView.
- Capture starts only after the updated tablet advertises support and the desktop user explicitly approves sharing. Older Android builds continue to receive no unsolicited video.
- USB bulk endpoint addresses are discovered from accessory interface 0 rather than assumed. The video writer and pen reader are separate threads/directions. A partial or stalled video transfer ends the connection rather than leaving the receiver at an ambiguous frame offset.

## Protocol v1

### Android control messages

Controls reuse the 22-byte pen packet layout. `toolType = 127` is reserved; controls and legacy all-127 heartbeat packets must never be injected as stylus events.

| Action | x | Other int32 fields | Meaning |
|---|---:|---:|---|
| 1 | 1 | 0 | Video reader/surface ready; protocol version 1 |
| 2 | 0 | 0 | Discard pending dependent frames and request a fresh IDR |

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
| 24 | 8 | Nonnegative presentation timestamp in microseconds |

Video dimensions are 2..4096; payloads contain one Annex-B H.264 access unit. Stopped packets have width and height zero and at most 4096 bytes of UTF-8 status text. Android validates the header before allocating the payload.

Desktop writes chunks no larger than 16 KiB. Android buffers raw accessory reads with a 16 KiB BufferedInputStream before parsing small headers, and handles fragmented/coalesced frames. Its input wrapper reports zero available bytes instead of querying the accessory descriptor: Xiaomi Pad 5 returns `EINVAL` from `FileInputStream.available()`. Reads remain blocking. Do not read just a 32-byte header directly from the accessory descriptor: short reads can truncate accessory transfers on device implementations.

Both encoded-frame queues hold at most three access units. Overflow discards the queued GOP, waits for an IDR and requests a new keyframe, rather than showing a growing backlog or rendering dependent frames with missing references. Decoder surfaces and stop/restart operations invalidate old decoder state.

## Geometry and lifecycle

The image is aspect-fitted into a centered Android input area. The image and that area's input coordinates share a uniform view transform: Android's child event dispatch applies its inverse before the unchanged pen packet encoder normalizes coordinates. Pinching two fingers zooms from 1x to 8x around their focus; moving both fingers pans. Panning is bounded to the image edges, and axes smaller than the view remain centered. Black bars do not generate pen samples. The existing Android `sensorLandscape` orientation is preserved: turning the tablet does not rotate the desktop image into portrait, which is the intended behavior. If the surface is recreated, it requests fresh codec configuration. Initial layout must retain match-parent sizing until the view has nonzero dimensions; a 0x0 surface cannot advertise video readiness. The manual swap-axis setting is disabled during mirroring, since rotating input independently of the displayed image would break alignment.

While video is available, finger contacts are reserved for local navigation, regardless of the stylus-only setting; even the first finger never sends a drawing/click packet. Stylus/eraser samples use the same inverse transform for contact, hover, and historical samples, without modifying pressure, tilt, or button state. Mixed finger/pen events are reduced to the pen pointer using public MotionEvent APIs, including historical coordinates and sensor data. Navigation stops during pen contact and remains suspended until all fingers lift, so palm contacts cannot move the image during a stroke. Without video, the existing finger/stylus input preference remains in effect.

Zoom affects only the tablet, not desktop capture or monitor mapping. Changing source resolution or view dimensions, stopping sharing, and reconnecting restore the fitted 1x view. Zoom enlarges the existing encoded image and adds no source detail; the 1080p preset can provide more detail than 720p. SurfaceView positioning/scaling is synchronized with view rendering starting with Android 7.0, the application's existing minimum supported version.

When the portal supplies monitor position and size, the desktop maps input to that monitor. Those values are compositor/logical coordinates, not necessarily encoded pixel dimensions. If either is unavailable, the user must select the same monitor in Map to Screen and the sharing dialog. Unrecognized returned geometry stops sharing rather than silently sending input to a different screen.

Stop Sharing, rejected/revoked capture permission and decoder errors leave pen input available. USB disconnection stops capture, drains/closes the USB streams and releases the decoder. Reconnection negotiates support again and requires a new sharing session.

## Dependencies

The implementation uses existing Qt/libusb and adds Qt DBus and GStreamer core/app/video development libraries. Fedora package versions were checked against its repositories during implementation: GStreamer 1.28.7 and pipewire-gstreamer 1.6.9. The source accepts GStreamer 1.20 or newer; Fedora build commands are in README.md. Android adds no external libraries.

The desktop encoder is software OpenH264, not a promise of GPU encoding. Android chooses its platform AVC decoder; hardware acceleration and optional low-latency decoding depend on that device. Resolution/fps presets are upper bounds, not performance guarantees.

## Hardware acceptance checklist

Use these checks when validating another device, compositor, or build:

1. Connect a Xiaomi Pad 5 with a data-capable USB cable, accept accessory permission, and confirm Mirror via USB becomes enabled without USB debugging or Wi-Fi.
2. Choose the intended monitor in Map to Screen. Start 720p/30 and approve that monitor in the system sharing dialog. Confirm its live image appears on Android, including after leaving the desktop idle briefly.
3. In Krita or another drawing application, use a pressure-sensitive brush. Draw lightly and firmly; check that stroke thickness changes and input remains under the pen, including near the image corners. Xiaomi Smart Pen hardware supports 4096 pressure levels; verify the application's actual pressure response rather than relying on those specifications.
4. Touch the letterbox bars: they must not draw. Turn the tablet and confirm the desktop image retains its landscape proportions. Check alignment again, including after any surface recreation.
   Spread two fingers to zoom, pan to another part of the image with both fingers, and draw fine strokes there. Verify that strokes stay under the pen and land at the original monitor coordinates, with varying pressure. Pinch back to 1x and repeat elsewhere. Finger gestures must not draw or click on the PC, including when stylus-only input is disabled; palm contacts during a pen stroke must not move the viewport. Verify that stopping/restarting sharing resets zoom and preserves pen input.
5. Stop sharing: the image must be covered by the status message, while pen-only input still works. Start sharing again and test both alternative quality presets.
6. Cancel the sharing dialog and revoke a running sharing session through the desktop. Verify that the app remains responsive and pen input is retained.
7. Unplug during drawing/video, then reconnect. Verify that capture stops, Android returns to its connection screen, no threads retain a stale session, and sharing can be approved again.
8. Close the desktop while streaming and disconnect from Android. Check that capture permission, video threads and codec resources are released. Reopen both apps and connect again.
9. Measure perceived pen-to-image delay on the actual devices; do not infer latency from successful compilation or encoder unit tests.

Automated protocol, encoder and isolated D-Bus portal checks are invoked with CTest. Android protocol/geometry/zoom checks use the existing JUnit dependency. `VideoTransformTest` checks focal-point preservation, simultaneous zoom/pan, bounds, and geometry resets. `UsbMirrorZoomTest` exercises Android's actual event dispatch and 22-byte pen packets after transformations, including batched pressure/tilt samples, hover/eraser input, mixed finger/pen contacts, letterbox rejection, and stop-sharing behavior. These checks do not prove USB throughput, MediaCodec behavior or drawing latency on the physical tablet.

With a connected device that permits test APK installation:

```bash
cd inkbridge-android
./gradlew :app:connectedDebugAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.class=com.inkbridge.UsbMirrorZoomTest,com.inkbridge.UsbMirrorViewTest \
  --console=plain
```

## Verified on Xiaomi Pad 5

The GNOME/Wayland desktop and Xiaomi Pad 5 were tested together over USB on 2026-10-10. The user confirmed a live desktop image, pen alignment, variable-width strokes from real pressure, retained landscape proportions when turning the tablet, pen input after Stop Sharing, and restored video/drawing after unplugging and reconnecting. Android selected `OMX.qcom.video.decoder.avc`; its logged rendering rate in the 720p/30 preset was approximately 30 fps. The tablet's 2560x1600 drawing view contained a 2560x1440 video surface without stretching.

Other presets, tilt/eraser behavior, letterbox pen rejection, permission revocation during streaming, and measured end-to-end latency have not been independently verified on hardware. ADB was enabled for APK installation and diagnostics; operation with USB debugging disabled has not been separately tested.

Tablet-local pinch zoom was added afterward. All 21 protocol/geometry unit tests passed, and the updated application was installed without clearing data. The user subsequently confirmed that local zoom, panning, and drawing work on the Xiaomi Pad 5. Palm handling has not been independently verified. Device-side zoom tests built, but Xiaomi twice rejected the separate instrumentation APK with `INSTALL_FAILED_USER_RESTRICTED: Install canceled by user`; they have not run. Full Android lint remains blocked by the existing `MissingConstraints` error in `activity_main.xml`, with no findings in the new zoom modules.

For an already-connected, streaming device with ADB available:

```bash
python3 inkbridge-desktop/tests/check_usb_video.py
python3 inkbridge-desktop/tests/check_usb_video.py --layout-only
```

The first command checks for a nonzero video surface and the absence of the decoder's waiting/error overlay. The second isolates the first-layout regression. These checks do not measure latency or verify changing image content. The first-layout instrumentation test is in `UsbMirrorViewTest`; its APK built, but Xiaomi rejected installing the separate test package with `INSTALL_FAILED_USER_RESTRICTED`. The actual application's failing 0x0 layout and fixed 2560x1600 layout were verified through ADB dumpsys instead. The unsupported `available()` failure is covered by a unit test that was observed failing before the fix and passing afterward.
