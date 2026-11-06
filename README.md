# Staydee

Local video stabilization for Android: two-pass FFmpeg + vid.stab, no network, result saved to the gallery.

Status: early development. Plan: [docs/stabilize-android-plan.md](docs/stabilize-android-plan.md).

## Build

Needs JDK 17+ and the Android SDK (compileSdk 37).

```sh
sh scripts/fetch-aar.sh          # vendored FFmpegKit AAR (release asset, sha256-checked)
./gradlew :core:test :app:assembleDebug
```

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

GPL-3.0. The bundled FFmpegKit build is configured with `--enable-gpl --enable-version3` (libx264, vid.stab).
