#!/bin/sh
# Fetch the vendored FFmpegKit AAR (a release asset on this repo) into app/libs.
# Gradle verifies the sha256 again before every build.
set -eu

VERSION=8.1.9
SHA256=72094919ac812598ec3f77ba2631e5b2dbd50b1daa98dfa64e9fc565f2b52ede
REPO=pradigunara/staydee
TAG="ffmpegkit-$VERSION"
NAME="ffmpeg-kit-full-gpl-$VERSION.aar"
DEST="$(dirname "$0")/../app/libs"

mkdir -p "$DEST"
cd "$DEST"

if [ -f "$NAME" ] && echo "$SHA256  $NAME" | sha256sum -c --quiet - 2>/dev/null; then
    echo "$NAME: present"
    exit 0
fi

rm -f "$NAME"
if command -v gh >/dev/null 2>&1 && gh auth status >/dev/null 2>&1; then
    gh release download "$TAG" --repo "$REPO" --pattern "$NAME"
else
    # works once the repo is public
    curl -fL --retry 3 -o "$NAME" "https://github.com/$REPO/releases/download/$TAG/$NAME"
fi

echo "$SHA256  $NAME" | sha256sum -c -
