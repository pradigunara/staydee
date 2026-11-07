#!/usr/bin/env bash
# Reproducible build of fully static Android arm64 ffmpeg + ffprobe
# (ffmpeg 9.0.2 + vid.stab 1.1.2 + x264 + zimg 3.0.6), verified under qemu-aarch64.
#
#   native/ffmpeg/build.sh                      # host: builds image if missing, runs build
#   CPUS=1.6 JOBS=2 native/ffmpeg/build.sh      # defaults shown
#
# Host mode only drives podman. The real work runs inside the container
# (`build.sh --inner`). Downloads, sources, and intermediate installs live in the
# named volume $CACHE_VOLUME, so reruns are incremental.
#
# Outputs (in the repo):
#   vendor/ffmpeg/arm64-v8a/ffmpeg
#   vendor/ffmpeg/arm64-v8a/ffprobe
#   vendor/ffmpeg/BUILDINFO.md
set -euo pipefail

# ---------------------------------------------------------------- pinned sources
FFMPEG_VERSION=9.0.2
FFMPEG_URL="https://ffmpeg.org/releases/ffmpeg-${FFMPEG_VERSION}.tar.xz"
FFMPEG_SHA256=8c3850283eb25fa026482078a04051e0be17347b09ef81a0849bec15a96e002e

VIDSTAB_VERSION=1.1.2
VIDSTAB_TAG=v1.1.2
VIDSTAB_COMMIT=92bc0b0f369f2a88aaacf25eac3a10f8415308fc
VIDSTAB_URL="https://github.com/georgmartius/vid.stab/archive/refs/tags/${VIDSTAB_TAG}.tar.gz"
VIDSTAB_SHA256=96db34d48a9e3aa13736a48744b56dfb76731ac9bb5193c716de8534c9fd709d

# x264: tip of the `stable` branch when pinned (git ls-remote refs/heads/stable).
X264_COMMIT=b35605ace3ddf7c1a5d67a2eb553f034aef41d55
X264_URL="https://code.videolan.org/videolan/x264/-/archive/${X264_COMMIT}/x264-${X264_COMMIT}.tar.gz"
X264_SHA256=cd71a7515b0e9a012e1ac9b1f8415bebcaf6fc97d4db32286642ac4c0fbe24f9

ZIMG_VERSION=3.0.6
ZIMG_COMMIT=ef19c4adcfda6e8b4af57aa9dc640e48f6306c35
ZIMG_URL="https://github.com/sekrit-twc/zimg/archive/refs/tags/release-${ZIMG_VERSION}.tar.gz"
ZIMG_SHA256=be89390f13a5c9b2388ce0f44a5e89364a20c1c57ce46d382b1fcc3967057577

# ---------------------------------------------------------------- tunables
IMAGE=${IMAGE:-localhost/staydee-ffmpeg-build:1}
CACHE_VOLUME=${CACHE_VOLUME:-staydee-ffmpeg-cache}
CPUS=${CPUS:-1.6}   # container cpu limit: 40% of the 4-core host
JOBS=${JOBS:-2}     # make / cmake --build parallelism
API=29
TRIPLE=aarch64-linux-android

HERE=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)
REPO=$(cd "$HERE/../.." && pwd)

# ---------------------------------------------------------------- host launcher
if [ "${1:-}" != "--inner" ]; then
  if ! podman image exists "$IMAGE"; then
    # `podman build` has no --cpus; --cpu-period/--cpu-quota is the same limit.
    quota=$(awk -v c="$CPUS" 'BEGIN { printf "%d", c * 100000 }')
    podman build --cpu-period=100000 --cpu-quota="$quota" \
      -t "$IMAGE" -f "$HERE/Containerfile" "$HERE"
  fi
  podman volume exists "$CACHE_VOLUME" || podman volume create "$CACHE_VOLUME" >/dev/null
  exec podman run --rm --name staydee-ffmpeg-build --cpus="$CPUS" \
    -e JOBS="$JOBS" \
    -v "$CACHE_VOLUME":/cache -v "$REPO":/work -w /work \
    "$IMAGE" bash /work/native/ffmpeg/build.sh --inner
fi

# ---------------------------------------------------------------- inner (container)
NDK=${ANDROID_NDK_HOME:?ANDROID_NDK_HOME not set (run inside the build image)}
TC=$NDK/toolchains/llvm/prebuilt/linux-x86_64
SYSROOT=$TC/sysroot
DL=/cache/dl
SRC=/cache/src
BLD=/cache/build
PREFIX=/cache/prefix
OUT=/work/vendor/ffmpeg
mkdir -p "$DL" "$SRC" "$BLD" "$PREFIX" "$OUT/arm64-v8a"

export PATH=$TC/bin:$PATH
export CC=${TRIPLE}${API}-clang
export CXX=${TRIPLE}${API}-clang++
export AR=llvm-ar RANLIB=llvm-ranlib STRIP=llvm-strip NM=llvm-nm
export PKG_CONFIG_LIBDIR=$PREFIX/lib/pkgconfig
export PKG_CONFIG_PATH=$PREFIX/lib/pkgconfig
export SOURCE_DATE_EPOCH=1700000000

CFLAGS_COMMON="-O2 -fPIC -ffile-prefix-map=/cache=."
# -static, not -static-pie: bionic's static-pie segfaults under qemu-user (even hello-world), and qemu is the verification path.
LDFLAGS_COMMON="-static -Wl,-z,max-page-size=16384"

fetch() { # url sha256 file
  local f=$DL/$3
  if [ ! -f "$f" ]; then curl -fL --retry 3 -o "$f.part" "$1" && mv "$f.part" "$f"; fi
  echo "$2  $f" | sha256sum -c - >/dev/null || { echo "sha256 mismatch: $f" >&2; exit 1; }
}
unpack() { # archive dest  (fresh tree, top-level dir stripped)
  rm -rf "$2"; mkdir -p "$2"; tar -xf "$DL/$1" -C "$2" --strip-components=1
}
stamp() { echo "$PREFIX/.stamp-$1"; }

fetch "$FFMPEG_URL" "$FFMPEG_SHA256" "ffmpeg-${FFMPEG_VERSION}.tar.xz"
fetch "$VIDSTAB_URL" "$VIDSTAB_SHA256" "vid.stab-${VIDSTAB_TAG}.tar.gz"
fetch "$X264_URL" "$X264_SHA256" "x264-${X264_COMMIT}.tar.gz"
fetch "$ZIMG_URL" "$ZIMG_SHA256" "zimg-${ZIMG_VERSION}.tar.gz"

# ---- x264: static, arm64 NEON asm (gas-compatible assembly via clang)
if [ ! -f "$(stamp x264-$X264_COMMIT)" ]; then
  unpack "x264-${X264_COMMIT}.tar.gz" "$SRC/x264"
  ( cd "$SRC/x264"
    AS=$CC ./configure --prefix="$PREFIX" --host=aarch64-linux-android \
      --enable-static --disable-shared --enable-pic --disable-cli \
      --disable-opencl --extra-cflags="$CFLAGS_COMMON" --extra-ldflags="$LDFLAGS_COMMON"
    echo "x264 config.h arch/asm lines:"; grep -E 'ARCH_|NEON|HAVE_AS' config.h || true
    make -j"$JOBS" && make install )
  touch "$(stamp x264-$X264_COMMIT)"
fi

# ---- zimg: static (its C++ runtime is linked into the final binaries, see ffmpeg)
if [ ! -f "$(stamp zimg-$ZIMG_VERSION)" ]; then
  unpack "zimg-${ZIMG_VERSION}.tar.gz" "$SRC/zimg"
  ( cd "$SRC/zimg"
    ./autogen.sh
    ./configure --prefix="$PREFIX" --host=aarch64-linux-android \
      --enable-static --disable-shared --disable-testapp --disable-example \
      --disable-unit-test --with-pic \
      CFLAGS="$CFLAGS_COMMON" CXXFLAGS="$CFLAGS_COMMON" LDFLAGS="$LDFLAGS_COMMON"
    make -j"$JOBS" && make install )
  touch "$(stamp zimg-$ZIMG_VERSION)"
fi

# ---- vid.stab: static; OpenMP via the NDK's static libomp.a when present
OMPA=$(find "$TC/lib/clang" -path '*aarch64*' -name libomp.a 2>/dev/null | head -1)
VIDSTAB_OMP=ON
if [ -z "$OMPA" ]; then
  VIDSTAB_OMP=OFF
  echo "NOTE: no static libomp.a in NDK; building vid.stab with -DUSE_OMP=OFF" >&2
fi
if [ ! -f "$(stamp vidstab-$VIDSTAB_TAG-omp$VIDSTAB_OMP)" ]; then
  unpack "vid.stab-${VIDSTAB_TAG}.tar.gz" "$SRC/vidstab"
  rm -rf "$SRC/vidstab/_b"
  OMP_CMAKE=()
  if [ "$VIDSTAB_OMP" = ON ]; then
    OMP_CMAKE=(-DOpenMP_C_FLAGS=-fopenmp -DOpenMP_C_LIB_NAMES=omp -DOpenMP_omp_LIBRARY="$OMPA"
               -DOpenMP_CXX_FLAGS=-fopenmp -DOpenMP_CXX_LIB_NAMES=omp)
  fi
  cmake -S "$SRC/vidstab" -B "$SRC/vidstab/_b" \
    -DCMAKE_TOOLCHAIN_FILE="$NDK/build/cmake/android.toolchain.cmake" \
    -DANDROID_ABI=arm64-v8a -DANDROID_PLATFORM=android-$API -DANDROID_STL=c++_static \
    -DCMAKE_BUILD_TYPE=Release -DCMAKE_POSITION_INDEPENDENT_CODE=ON \
    -DCMAKE_C_FLAGS="-ffile-prefix-map=/cache=." \
    -DBUILD_SHARED_LIBS=OFF -DUSE_OMP=$VIDSTAB_OMP \
    -DCMAKE_INSTALL_PREFIX="$PREFIX" -DCMAKE_POLICY_VERSION_MINIMUM=3.5 \
    "${OMP_CMAKE[@]}"
  cmake --build "$SRC/vidstab/_b" -j"$JOBS"
  cmake --install "$SRC/vidstab/_b"
  rm -f "$PREFIX"/lib/libvidstab*.so*   # static archive only
  touch "$(stamp vidstab-$VIDSTAB_TAG-omp$VIDSTAB_OMP)"
fi
echo "$VIDSTAB_OMP" > "$PREFIX/.vidstab-omp"

# Link line extras. libc++_static supplies the C++ runtime (zimg is C++); libomp.a
# only when vid.stab was built with OpenMP.
EXTRA_LIBS="-lc++_static -lc++abi"
[ "$VIDSTAB_OMP" = ON ] && EXTRA_LIBS="$OMPA $EXTRA_LIBS -ldl"

# ---- ffmpeg
FF_ARGS=(
  --prefix="$PREFIX/ffmpeg" --enable-cross-compile --target-os=android --arch=aarch64
  --cc="$CC" --cxx="$CXX" --ld="$CXX" --ar="$AR" --ranlib="$RANLIB" --strip="$STRIP" --nm="$NM"
  --pkg-config=pkg-config --pkg-config-flags=--static
  --enable-gpl --enable-libx264 --enable-libvidstab --enable-libzimg
  --disable-network --disable-autodetect --disable-doc --disable-debug
  --disable-shared --enable-static --enable-pic
  --disable-everything --disable-avdevice --disable-ffplay
  --enable-ffmpeg --enable-ffprobe
  --extra-cflags="$CFLAGS_COMMON -I$PREFIX/include"
  --extra-ldflags="$LDFLAGS_COMMON -L$PREFIX/lib"
  --extra-libs="$EXTRA_LIBS"
  --enable-protocol=file,pipe,fd
  --enable-demuxer=mov,matroska,avi,mpegts,flv,ogg,wav,mp3,aac,ac3,eac3,flac
  --enable-decoder=h264,hevc,mpeg4,vp8,vp9,aac,mp3,mp3float,opus,vorbis,flac,ac3,eac3,ffv1,rawvideo
  --enable-decoder=pcm_s16le,pcm_s16be,pcm_s24le,pcm_s32le,pcm_f32le,pcm_f64le,pcm_u8,pcm_alaw,pcm_mulaw
  --enable-parser=h264,hevc,aac,aac_latm,mpeg4video,vp8,vp9,opus,vorbis,flac,ac3,mpegaudio
  --enable-encoder=libx264,aac,ffv1,rawvideo,wrapped_avframe,pcm_s16le
  --enable-muxer=mp4,mov,ipod,3gp,matroska,avi,mpegts,flv,nut,null,framemd5,framecrc,md5
  --enable-bsf=h264_mp4toannexb,hevc_mp4toannexb,aac_adtstoasc,extract_extradata
  --enable-filter=vidstabdetect,vidstabtransform,scale,unsharp,zscale,tonemap,format,split,psnr,null,anull,aresample,aformat,transpose,hflip,vflip,rotate,copy,setpts,settb,fps,pad,crop,hstack,vstack,trim,atrim,asetpts,anullsrc,testsrc2
  # Graph plumbing the ffmpeg CLI inserts implicitly (buffers, SAR/DAR, sinks).
  --enable-filter=buffer,buffersink,abuffer,abuffersink,setsar,setdar,colorspace,nullsink,anullsink,asplit
)

FFSRC=$SRC/ffmpeg-${FFMPEG_VERSION}
[ -f "$FFSRC/configure" ] || tar -xf "$DL/ffmpeg-${FFMPEG_VERSION}.tar.xz" -C "$SRC"
FFCONF_KEY=$(printf '%s\n' "${FF_ARGS[@]}" "$CC" "$VIDSTAB_OMP" | sha256sum | cut -c1-16)
if [ "$(cat "$BLD/ffmpeg.key" 2>/dev/null || true)" != "$FFCONF_KEY" ]; then
  rm -rf "$BLD/ffmpeg"; mkdir -p "$BLD/ffmpeg"
  ( cd "$BLD/ffmpeg" && "$FFSRC/configure" "${FF_ARGS[@]}" ) > "$BLD/ffmpeg.configure.log" 2>&1 \
    || { tail -40 "$BLD/ffmpeg.configure.log" >&2; exit 1; }
  echo "$FFCONF_KEY" > "$BLD/ffmpeg.key"
fi
cd "$BLD/ffmpeg"
make -j"$JOBS" ffmpeg ffprobe

cp -f ffmpeg "$OUT/arm64-v8a/ffmpeg"
cp -f ffprobe "$OUT/arm64-v8a/ffprobe"
"$STRIP" --strip-all "$OUT/arm64-v8a/ffmpeg" "$OUT/arm64-v8a/ffprobe"
chmod 755 "$OUT/arm64-v8a/ffmpeg" "$OUT/arm64-v8a/ffprobe"
file "$OUT/arm64-v8a/ffmpeg" "$OUT/arm64-v8a/ffprobe"
# ---- link-type gate: static ELF (ET_EXEC, no PT_INTERP, 16 KiB LOAD alignment), or fail
for b in ffmpeg ffprobe; do
  p=$OUT/arm64-v8a/$b
  llvm-readelf -h "$p" | grep -E 'Type: +EXEC' >/dev/null || { echo "FATAL: $b is not ET_EXEC" >&2; exit 1; }
  [ "$(llvm-readelf -l "$p" | grep -c INTERP || true)" = 0 ] || { echo "FATAL: $b has PT_INTERP" >&2; exit 1; }
  n_bad=$(llvm-readelf -lW "$p" | awk '/ LOAD /{ if ($NF != "0x4000") n++ } END{ print n+0 }')
  [ "$n_bad" = 0 ] || { echo "FATAL: $b has LOAD alignment != 0x4000" >&2; exit 1; }
  file "$p" | grep 'statically linked' >/dev/null || { echo "FATAL: file(1) does not report statically linked: $b" >&2; exit 1; }
done

# ---- verify under qemu (verify.sh exits nonzero on any failed check; BUILDINFO is still written)
VERIFY_RC=0
bash /work/native/ffmpeg/verify.sh /work/vendor/ffmpeg/arm64-v8a /cache/verify > /cache/verify.log 2>&1 || VERIFY_RC=$?
cat /cache/verify.log

# ---- BUILDINFO.md
NDK_REV=$(grep -E '^Pkg.Revision' "$NDK/source.properties" | cut -d= -f2 | tr -d ' ')
QFF=(qemu-aarch64 "$OUT/arm64-v8a/ffmpeg")
{
cat <<EOF
# Android arm64 ffmpeg / ffprobe (vendored build)

Generated by \`native/ffmpeg/build.sh\` (image \`$IMAGE\`, see \`native/ffmpeg/Containerfile\`).
Do not edit by hand: rerun the script. Verification script: \`native/ffmpeg/verify.sh\`.

## Licence

**GPL-2.0-or-later.** The binaries link x264 (GPL-2.0-or-later) and vid.stab (GPL-2.0-or-later);
ffmpeg is configured with \`--enable-gpl\`. No \`--enable-version3\`, no \`--enable-nonfree\`.
zimg is WTFPL. Distributing these binaries means distributing under GPL terms, including
the corresponding source (upstream archives pinned below).

## Pinned sources

| Component | Version | Pin | sha256 of source archive |
|-----------|---------|-----|--------------------------|
| ffmpeg | $FFMPEG_VERSION | release tarball | \`$FFMPEG_SHA256\` |
| vid.stab | $VIDSTAB_VERSION | tag \`$VIDSTAB_TAG\` = commit \`$VIDSTAB_COMMIT\` | \`$VIDSTAB_SHA256\` |
| x264 | stable branch | commit \`$X264_COMMIT\` | \`$X264_SHA256\` |
| zimg | $ZIMG_VERSION | tag \`release-$ZIMG_VERSION\` = commit \`$ZIMG_COMMIT\` | \`$ZIMG_SHA256\` |

URLs: $FFMPEG_URL , $VIDSTAB_URL , $X264_URL , $ZIMG_URL

## Toolchain

- Android NDK $NDK_REV (\`$(basename "$NDK")\`); clang: \`$($CC --version | head -1)\`
- Target \`${TRIPLE}${API}\`, fully static (\`-static\`, static libc++ via \`-lc++_static -lc++abi\`),
  \`-Wl,-z,max-page-size=16384\`, stripped with \`llvm-strip --strip-all\`
- vid.stab OpenMP: **$VIDSTAB_OMP** ($( [ "$VIDSTAB_OMP" = ON ] && echo "static libomp.a: $OMPA" || echo "no static libomp.a in NDK; built with -DUSE_OMP=OFF" ))
- Parallelism: \`-j$JOBS\`, container \`--cpus=$CPUS\`

## ffmpeg configure line

\`\`\`
./configure ${FF_ARGS[*]}
\`\`\`

## Binaries

\`\`\`
$(cd "$OUT/arm64-v8a" && ls -l ffmpeg ffprobe)
$(cd "$OUT/arm64-v8a" && sha256sum ffmpeg ffprobe)
$(file "$OUT/arm64-v8a/ffmpeg" "$OUT/arm64-v8a/ffprobe")
\`\`\`

## Link type

Static (non-PIE): \`ET_EXEC\`, no \`PT_INTERP\`, \`LOAD\` alignment 0x4000. Enforced by the link gate in \`build.sh\`.

\`\`\`
$(for b in ffmpeg ffprobe; do echo "== $b"; llvm-readelf -h "$OUT/arm64-v8a/$b" | grep 'Type:'; echo "PT_INTERP segments: $(llvm-readelf -l "$OUT/arm64-v8a/$b" | grep -c INTERP || true)"; llvm-readelf -lW "$OUT/arm64-v8a/$b" | grep ' LOAD '; done)
\`\`\`

## \`ffmpeg -version\` (qemu-aarch64)

\`\`\`
$("${QFF[@]}" -hide_banner -version)
\`\`\`

## \`ffmpeg -buildconf\` (qemu-aarch64)

\`\`\`
$("${QFF[@]}" -hide_banner -buildconf)
\`\`\`

## Supported input codecs and containers

Exact compiled-in set (\`ffmpeg -decoders\` / \`-demuxers\` / \`-parsers\`, qemu-aarch64):

\`\`\`
$("${QFF[@]}" -hide_banner -decoders | sed -n '/^ -*$/,$p')
\`\`\`

\`\`\`
$("${QFF[@]}" -hide_banner -demuxers | sed -n '/^ -*$/,$p')
\`\`\`

Video input: H.264, HEVC, MPEG-4 Part 2, VP8, VP9, FFV1, rawvideo.
Audio input: AAC, MP3, Opus, Vorbis, FLAC, AC-3, E-AC-3, PCM (s16/s24/s32/f32/f64/u8/alaw/mulaw).
Containers: MP4/MOV/3GP (mov), Matroska/WebM, AVI, MPEG-TS, FLV, Ogg, WAV, MP3, AAC (raw ADTS), AC-3/E-AC-3, FLAC.

**AV1 is NOT supported.** No libdav1d, no libaom, and no native av1 decoder is enabled, so AV1 input
(including AV1 in MP4/MKV) will fail. MPEG-2 and ProRes decoders are likewise absent; the list above is the complete input set.

## Verification under qemu-aarch64

Verification exit status: **$VERIFY_RC** (0 = all checks passed). Full log from \`verify.sh\`:

\`\`\`
$(cat /cache/verify.log)
\`\`\`
EOF
} > "$OUT/BUILDINFO.md"
echo "Wrote $OUT/BUILDINFO.md (verify rc=$VERIFY_RC)"
exit "$VERIFY_RC"
