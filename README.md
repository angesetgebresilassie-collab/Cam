# Cam

Android camera app (CameraX + Kotlin) with AI auto-enhance applied after capture.

- Capture a photo, then enhancement runs in the background (target: 3-5 seconds)
- Result is saved to `Pictures/Cam`
- `Enhancer` is an interface, so the built-in auto-enhancer can be swapped for a TFLite model (e.g. Zero-DCE) later

## Build
Push to GitHub and the **Build APK** workflow produces a debug APK artifact, or open in Android Studio.
