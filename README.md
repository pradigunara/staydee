# Staydee

Local video stabilization for Android: two-pass FFmpeg + vid.stab, no network, result saved to the gallery.

Status: early development. Plan: [docs/stabilize-android-plan.md](docs/stabilize-android-plan.md).

## Build

Needs JDK 17+ and the Android SDK (compileSdk 36).

```sh
sh scripts/fetch-aar.sh          # vendored FFmpegKit AAR (release asset, sha256-checked)
./gradlew :core:test :app:assembleDebug
```

The debug APK lands in `app/build/outputs/apk/debug/`.

## License

GPL-3.0. The bundled FFmpegKit build is configured with `--enable-gpl --enable-version3` (libx264, vid.stab).
