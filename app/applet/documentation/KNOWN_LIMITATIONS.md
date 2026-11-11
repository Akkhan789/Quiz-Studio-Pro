# Quiz Studio Pro — Known Limitations & System Requirements

## 1. WebCodecs Requirements
- **Chromium Version**: WebCodecs export requires Chromium 94+ / Android WebView 100+.
- **Hardware Acceleration**: Devices must support hardware H.264 profile encoding (`avc1.640028`, `avc1.4d401f`, or `avc1.42e01f`).
- **Fallback**: Devices with older WebViews automatically fall back to Native `StreamingVideoEncoderSession.kt` hardware export.

## 2. Storage & Permissions
- Videos are saved to `Movies/QuizStudioPro` via `MediaStore` API.
- Storage permission is not required on Android 10+ (API 29+).
