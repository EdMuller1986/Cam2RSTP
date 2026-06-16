# CamRTSP — Android phone as an RTSP camera

Android app: rear camera -> Wi-Fi -> `rtsp://IP:8554/live` (H.264 / RTP-over-TCP). Use VLC, ffplay, NVRs, or any RTSP client that supports interleaved TCP transport.

## Features

- H.264 encoder, target resolution 1280x720 @ 25 fps, 2.5 Mbps.
- Auto-resize: if the camera returns a different actual frame size, the encoder is re-created for the real size.
- NV12 input to Android H.264 encoder.
- RTP-over-TCP interleaved transport.
- Multiple simultaneous RTSP sessions.
- Foreground service with persistent notification.
- File-backed in-app log with crash reporting.

## Important fixes in this version

- RTSP requests are parsed as request line plus headers; `CSeq`, `Session`, and `Transport` are no longer confused with URI tokens.
- RTSP `SETUP` validates TCP interleaved transport and returns a usable `Session`.
- RTP H.264 timestamp uses the correct 90 kHz clock: `ptsUs * 90000 / 1000000`.
- RTP marker bit is set on the last RTP packet of each access unit.
- FU-A fragmentation preserves the original NAL F/NRI bits and uses a valid FU indicator.
- MediaCodec output buffers are read from `BufferInfo.offset` for exactly `BufferInfo.size` bytes.
- SPS/PPS are collected from both codec-config buffers and output-format CSD buffers.
- Microphone permission and microphone foreground-service type are removed because the app streams video only.
- Service running state uses an atomic flag; encoder drain state is separate from service lifecycle state.
- UI log callback is detached on pause/destroy to avoid leaking Activity instances.
- Service logs are persisted once and rotated at 512 KB.
- The crash receiver is registered as non-exported.
- Local IPv4 detection uses the active Android network first, then falls back to all non-loopback interfaces.

## Debugging in the app

The main screen shows:

- Status and RTSP URL.
- START / STOP controls.
- Copy log to clipboard.
- Live colored log output.

Logs are written to `filesDir/camrtsp.log`. When the file exceeds 512 KB it is rotated to `camrtsp.log.1`.

## Local build

This repository does not include a Gradle wrapper JAR. Install Gradle 8.5 locally, or open the project in Android Studio and let it use an installed Gradle distribution.

```bash
gradle assembleRelease   # signed with generated/debug signing config if present; otherwise debug signing
gradle assembleDebug
```

For command-line builds, create `local.properties` from `local.properties.example` and point `sdk.dir` to your Android SDK.

Minimum Android SDK: 24 (Android 7.0). Target SDK: 34 (Android 14).

## Usage

1. Install the APK on a phone.
2. Grant camera permission and notification permission on Android 13+.
3. Connect the phone and client/NVR/VLC to the same network.
4. Press START.
5. Connect to the shown URL, for example:

```bash
ffplay -rtsp_transport tcp rtsp://IP:8554/live
```

## Architecture

```text
MainActivity (UI + log view)
   -> RtspServerService (LifecycleService, foreground)
        -> RtspServer (ServerSocket :8554, per-client sessions)
        -> MediaCodec H.264 encoder
        -> CameraX ImageAnalysis (YUV_420_888 -> NV12 -> encoder)
        -> Drain thread (encoder output -> RTSP/RTP)
```

## Known limitations

- No audio stream.
- No RTSP authentication or network allowlist in this version.
- Only RTP-over-TCP interleaved transport is implemented.
- Encoder behavior depends on the device chipset and vendor camera stack.
- Release signing is debug-grade unless you provide your own keystore.

## License

MIT
