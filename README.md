# Staydee

Local video stabilization for Android: two-pass FFmpeg + vid.stab, no network, result saved to the gallery.

Status: early development. Plan: [docs/stabilize-android-plan.md](docs/stabilize-android-plan.md).

## Build

Needs JDK 17+ and the Android SDK (compileSdk 37).

```sh
./gradlew :core:test :app:assembleDebug
```

ffmpeg is bundled: `vendor/ffmpeg/arm64-v8a/{ffmpeg,ffprobe}` (ffmpeg 9.0.2 + vid.stab 1.1.2 + x264 +
zimg, static arm64), rebuilt with `native/ffmpeg/build.sh`; see `vendor/ffmpeg/BUILDINFO.md`.

The debug APK lands in `app/build/outputs/apk/debug/`.

## Release

Release APKs are signed from the environment; the keystore is never in the repo:

```sh
STAYDEE_KEYSTORE=/path/release.jks STAYDEE_KEYSTORE_PASSWORD=… STAYDEE_KEY_ALIAS=staydee \
  ./gradlew :app:assembleRelease
```

CI signs with the `STAYDEE_KEYSTORE_B64` / `STAYDEE_KEYSTORE_PASSWORD` / `STAYDEE_KEY_ALIAS`
repository secrets. Pushing a `v*` tag publishes `staydee-<tag>.apk` as a GitHub release.
Keep a backup of the keystore: an APK signed with another key cannot update an installed app.

## License

GPL-3.0. The bundled ffmpeg is built with `--enable-gpl` (libx264, vid.stab; GPL-2.0-or-later).
