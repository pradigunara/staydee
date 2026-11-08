#!/usr/bin/env bash
# Smoke + pipeline verification of the vendored aarch64 binaries under qemu-aarch64.
# Runs inside the build container (called from build.sh).
#   verify.sh <bindir> <workdir>
# Test media is synthesised by the container's host-arch ffmpeg (Ubuntu package);
# every check of the vendored binaries goes through qemu-aarch64.
set -uo pipefail

BIN=$1
W=$2
mkdir -p "$W"
cd "$W"
export PYTHONDONTWRITEBYTECODE=1

FFMPEG=(qemu-aarch64 "$BIN/ffmpeg")
FFPROBE=(qemu-aarch64 "$BIN/ffprobe")
HOST_FF=ffmpeg   # container ffmpeg, only for test media
FAILS=0

pass() { echo "PASS: $*"; }
fail() { echo "FAIL: $*"; FAILS=$((FAILS + 1)); }
# check "<description>" <command...>: output goes to check.log, tail shown on failure
check() {
  local desc=$1; shift
  if "$@" >check.log 2>&1; then pass "$desc"; else fail "$desc"; tail -20 check.log; fi
}
probe_json() { # probe_json <media> <out.json>
  "${FFPROBE[@]}" -v error -print_format json -show_format -show_streams "$1" > "$2"
}
is_trf1() { [ "$(head -c4 "$1")" = TRF1 ]; }

DETECT='vidstabdetect=result=%s:shakiness=5:accuracy=15:stepsize=6:mincontrast=0.25'
TRANSFORM_OPTS='smoothing=15:optzoom=1:optalgo=gauss:zoomspeed=0.25:crop=keep:interpol=bicubic:relative=1:zoom=0:maxshift=-1:maxangle=-1'
UNSHARP='unsharp=5:5:0.8:3:3:0.4'

echo "== binary smoke =="
check "ffmpeg -version exits 0" "${FFMPEG[@]}" -hide_banner -version
check "ffprobe -version exits 0" "${FFPROBE[@]}" -hide_banner -version
"${FFMPEG[@]}" -hide_banner -version 2>/dev/null | head -1
"${FFMPEG[@]}" -hide_banner -filters > filters.txt 2>/dev/null
for f in vidstabdetect vidstabtransform zscale tonemap format; do
  check "filter present: $f" grep -qw "$f" filters.txt
done
"${FFMPEG[@]}" -hide_banner -h filter=vidstabdetect > h_vidstabdetect.txt 2>&1
echo "-- -h filter=vidstabdetect options: $(grep -oE '^ +[a-z_]+ +' h_vidstabdetect.txt | tr -s ' ' | tr '\n' ' ')"
check "vidstabdetect exposes 'fileformat' option (spec expectation)" grep -q 'fileformat' h_vidstabdetect.txt
"${FFMPEG[@]}" -hide_banner -encoders > encoders.txt 2>/dev/null
check "encoder present: libx264" grep -q ' libx264 ' encoders.txt

echo "== test media (container ffmpeg) =="
SHAKE="crop=w=800:h=450:x='(in_w-800)/2+40*sin(2*PI*t*1.3)':y='(in_h-450)/2+25*sin(2*PI*t*2.1)',scale=960:540"
$HOST_FF -hide_banner -loglevel error -y -f lavfi -i "testsrc2=s=1280x720:r=30:d=3" \
  -vf "$SHAKE" -c:v libx264 -preset veryfast -crf 18 -pix_fmt yuv420p shaky_land.mp4
# Portrait: 960x540 H.265, then stream-copy remux with a 90-degree display matrix (-display_rotation is an input option) => displays 540x960.
$HOST_FF -hide_banner -loglevel error -y -f lavfi -i "testsrc2=s=1280x720:r=30:d=3" \
  -vf "$SHAKE" -c:v libx265 -preset veryfast -crf 22 -pix_fmt yuv420p \
  port_coded.mp4
$HOST_FF -hide_banner -loglevel error -y -display_rotation:v:0 90 -i port_coded.mp4 -c copy shaky_port.mp4
check "test clips created" test -s shaky_land.mp4 -a -s shaky_port.mp4

echo "== two-pass pipeline: landscape H.264 =="
check "pass 1 (vidstabdetect) landscape" "${FFMPEG[@]}" -hide_banner -y -i shaky_land.mp4 \
  -vf "$(printf "$DETECT" t_land.trf)" -f null -
check "t_land.trf starts with TRF1" is_trf1 t_land.trf
check "pass 2 (transform+unsharp+x264) landscape" "${FFMPEG[@]}" -hide_banner -y -i shaky_land.mp4 \
  -vf "vidstabtransform=input=t_land.trf:${TRANSFORM_OPTS},${UNSHARP}" -c:v libx264 -preset veryfast -crf 22 \
  -movflags +faststart -brand mp42 out_land.mp4
check "ffprobe out_land.mp4 JSON" probe_json out_land.mp4 out_land.json

echo "== two-pass pipeline: portrait (display rotation 90, H.265 source) =="
check "pass 1 (vidstabdetect) portrait" "${FFMPEG[@]}" -hide_banner -y -i shaky_port.mp4 \
  -vf "$(printf "$DETECT" t_port.trf)" -f null -
check "t_port.trf starts with TRF1" is_trf1 t_port.trf
check "pass 2 (transform+unsharp+x264) portrait" "${FFMPEG[@]}" -hide_banner -y -i shaky_port.mp4 \
  -vf "vidstabtransform=input=t_port.trf:${TRANSFORM_OPTS},${UNSHARP}" -c:v libx264 -preset veryfast -crf 22 \
  -movflags +faststart -brand mp42 out_port.mp4
check "ffprobe out_port.mp4 JSON" probe_json out_port.mp4 out_port.json
check "portrait output is 540x960 after autorotate" bash -c 'test "$(qemu-aarch64 "$1" -v error -show_entries stream=width,height -of csv=p=0 "$2")" = "540,960"' _ "$BIN/ffprobe" out_port.mp4

echo "== repeatability: two transform-only runs (framemd5) from the same trf =="
RT="vidstabtransform=input=t_land.trf:${TRANSFORM_OPTS}"
check "run1 framemd5 (transform only)" "${FFMPEG[@]}" -hide_banner -y -i shaky_land.mp4 -vf "$RT" -f framemd5 run1.md5
check "run2 framemd5 (transform only)" "${FFMPEG[@]}" -hide_banner -y -i shaky_land.mp4 -vf "$RT" -f framemd5 run2.md5
if cmp -s run1.md5 run2.md5; then pass "repeatability: framemd5 identical across two runs"
else fail "repeatability: framemd5 differs between runs"; fi
echo "frames in run1: $(grep -vc '^#' run1.md5)"

echo "== TRF1 compatibility with docs/reference/stabilize.py scale_trf =="
check "scale_trf(t_land.trf / t_port.trf, 2.0) parses" python3 -c "
import sys; sys.path.insert(0, '/work/docs/reference')
import stabilize
for name in ('t_land.trf', 't_port.trf'):
    stabilize.scale_trf(open(name, 'rb').read(), 2.0)
"

echo "== summary: $FAILS failed check(s) =="
exit $(( FAILS > 0 ))
