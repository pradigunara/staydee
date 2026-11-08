# Staydee

Steady out shaky phone videos, right on your Android phone. Pick a video (or share one to
Staydee from your gallery), tap **Stabilize**, and the smooth version lands in your gallery.
Nothing is uploaded: no account, no internet, no ads.

Status: early development. Plan: [docs/stabilize-android-plan.md](docs/stabilize-android-plan.md).

## Features

- Works offline: your videos never leave the phone.
- Three simple controls: how much shake to remove, how smooth the camera path should be, and quality.
- Side-by-side compare of the original and the stabilized video.
- Runs in the background with a progress notification and a time estimate.
- Saves to `Movies/Staydee`, visible in any gallery app; HDR videos are converted to SDR.
- Advanced settings (zoom, borders, tripod mode, output size, audio, sharpening) for those who want them.

## Install

Download the latest `staydee-<version>.apk` from [Releases](https://github.com/pradigunara/staydee/releases)
and install it. Needs Android 10 or newer on a 64-bit ARM phone (almost every phone from the last
several years).

## How it works

Two passes with [FFmpeg](https://ffmpeg.org) and [vid.stab](https://github.com/georgmartius/vid.stab):
the first analyzes the camera motion, the second smooths it and re-encodes with x264. The app is
Kotlin and Jetpack Compose.

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
