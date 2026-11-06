# Plan: Staydee, a native Android app for local two-pass vidstab stabilization

**Status:** proposal with owner decisions recorded (§15); written to be *evaluated* before it is implemented.
**Revision:** 8.3 (owner decisions of 2026-11-07 recorded in §15 and folded into the body; AAR `ffmpeg-kit-full-gpl:8.1.9` inspected directly, §15.2; reference CLI now `stabilize.py` v2.0.8, 51 tests; project named Staydee; licence resolved to GPL-3.0). Rev 8.2 (`stabilize.py` v2.0.6: ffmpeg/vid.stab capability check + ASCII-only fallback, sidecar-guess warning; M1 trf gate rewritten as fixed-input, `--dump-trf` dropped). Rev 8.1: desktop reply §14.6: AAR `n8.1.2` + vid.stab `v1.1.1` verified in the fork's `source.sh`; GPL-2.0-or-later wording; size-dependence note in §3.7. Rev 8: §14.5 phone review of rev 7; §3.7 now states resolutions with 4K evidence; §14.1 phone verification recorded. Rev 7: desktop review folded in: §14 review feedback; AAR stack corrected to ffmpeg 8.1.2 + vid.stab 1.1.1; M1 gate moved to pass-2 `.trf` bytes; `--keep-trf` sidecar contract from `stabilize.py` v2.0.5; minSdk 29; stale versions fixed. Rev 6: upgraded reference env to ffmpeg 9.0.2 + vid.stab 1.1.2, §3.7 rewritten, Appendix A added.

**Author context:** distilled from building and benchmarking a working reference CLI
(`stabilize.py`, v2.0.8, <https://gist.github.com/097fa67734767e131825150ebedd3b95>)
on an 8-core arm64 Android phone via Termux. Numbers below are measured on that
device, not estimated, unless explicitly marked "model". **Stacks:** §3.1–§3.3 were
measured originally on ffmpeg 7.1.1 (Termux build, libvidstab present); the device was
later moved to ffmpeg 8.1.2 and then to ffmpeg 9.0.2 + vid.stab 1.1.2 (§3.6). The only
re-run on 9.0.2 in §3.1–§3.3 is §3.1's two-pass time (43.5 s, was 47.6 s); the other
figures there have not been re-run on the newer stacks.

**Project:** **Staydee**. Application ID `io.github.pradigunara.staydee`; repo
`github.com/pradigunara/staydee` (private for now, to be made public); licence GPL-3.0
(§7, §15). Reference CLI: `stabilize.py` **v2.0.8**; a copy lives in `docs/reference/`
(local, untracked), the gist stays canonical.

**Audience:** an agent (or human) who will first *critique* this plan, then implement
it. Section 9 lists what to challenge; section 4 lists the logic that must be ported
verbatim; section 3 lists facts that should **not** be re-litigated without new
measurements.

---

## 1. Goal

A fully local Android app (**Staydee**) that stabilizes a video with FFmpeg + vid.stab and saves the
result to the system gallery. No network, no account, no server. Feature parity with
the reference CLI's pipeline, plus a UI that hides the knobs that do not matter.

Non-goals for v1: iOS, web, desktop (Android only, §15), multi-clip batch, timeline editing, cloud sync,
on-device AI stabilization.

---

## 2. Why an app at all (and why native Kotlin)

The reference script already works on the phone. An app buys three things:

1. **Discoverability** — a gallery-visible result without knowing `termux-media-scan`.
2. **Progress and cancellation** — a 48 s encode in a terminal is fine; in a GUI it must
   survive screen-off and be cancellable.
3. **Defaults that are right for the hardware** — the measured encoder/preset settings
   below are not what a generic "video stabilizer" app ships.

Stack decision: **Kotlin + Jetpack Compose, Android-only, built in CI.** Rationale and
alternatives considered are in §11. The decisive point: FFmpeg must be wired natively
regardless of UI framework, Flutter/RN add a bridge layer without removing the Android
toolchain (Flutter builds through Gradle anyway), and the only maintained prebuilt
FFmpeg binary with vid.stab for Android is a Maven AAR.

---

## 3. Established facts (do not re-litigate without new measurements)

### 3.1 Encoder: the preset barely matters, CRF does

Source: 1080×1920 HEVC, 15.03 s, 451 frames, 14.99 MB. Two-pass with vidstab transform +
`unsharp`, measured end to end on the phone:

| pass | setting | wall time | output |
|---|---|---|---|
| 1 | `vidstabdetect` (shakiness 5, accuracy 15, stepsize 6) | **17.7 s** | — |
| 2 | `-preset superfast -crf 20` | 30.7 s | 19.61 MB |
| 2 | `-preset veryfast -crf 20` | 30.6 s | 11.39 MB |
| 2 | `-preset medium -crf 20` | 36.1 s | 12.38 MB |

After the ffmpeg 9.0.2 upgrade the same two-pass run takes **43.5 s** (was 47.6 s) and
produces a 9.1 MB file — the conclusions below are unchanged.

Why: x264 alone runs ~49.5 fps on a 90-frame slice of this clip, but **14.7 fps inside
the real chain** — `vidstabtransform` must consume frames in order and starves the
encoder. Pass 2 is filter-bound, not encoder-bound. Consequences:

- `veryfast` is the default. `medium` costs +18% time and bought nothing here.
- `superfast`/`ultrafast` are **a trap**: same speed as `veryfast`, +72% size
  (they give up CABAC and 8×8 DCT).
- CRF is free in time: on a 3 s slice at `veryfast`, CRF 18/20/22/24 →
  2682/2063/1611/1291 KB, all ~7.3 s. Default **CRF 22** for phone viewing.

### 3.2 Pass 1 can run at reduced resolution, and that is the big lever

The `.trf` transform file stores motion in **detection-resolution pixels**, so it can be
rescaled exactly before pass 2; the warp still happens at full resolution. Validated by
PSNR of the stabilized output against a full-resolution-detection baseline at the *same*
output size:

| render | pass-1 resolution | scale factor k | PSNR vs baseline |
|---|---|---|---|
| 2160 long edge | 1920 long edge | ×2 | **47.2 dB** |
| 2560 long edge | 1920 long edge | ×1.333 | **47.8 dB** |
| 2560 long edge | 1280 long edge | ×2 | **44.0 dB** |
| 2160 long edge | 1920, **no rescale** (control) | ×1 | 22.8 dB |

So: rescaling works (47 dB ≈ visually identical), skipping the rescale is catastrophic
(22.8 dB = under-stabilized drift), and going below a 1080p-class frame starts costing
real fidelity (44 dB). Cost of pass 1 vs long edge, as originally recorded — two
measurements with different frame counts:

- **90-frame slices:** 1080p-class 4.2 s → 4K 30.8 s (**7.3× for 4× pixels** — worse than linear).
- **30-frame slices:** 1440p 2.14 s, 1080p-class 1.06 s, 720p 0.50 s.

**Unresolved:** 4.2 s per 90 frames normalises to 1.4 s per 30 frames, not the 1.06 s
recorded for the 1080p class at 30 frames. They are separate measurements; thermal drift
(§3.3) is a candidate cause but [INFERENCE]. Do not combine the two series in one ratio;
re-measure at M0 if pass-1 cost per size is needed.

Policy to implement (`detect_edge = auto`), in **long-edge** terms:

```
target = min(render_long, max(1920, render_long // 2))
k      = render_long / target          (1.0 => no rescale)
```

| render long edge | target | k | effect |
|---|---|---|---|
| ≤1920 (1080p class) | same | 1.0 | no-op, no quality or behaviour change |
| 2560 (1440p) | 1920 | 1.333 | ~2× cheaper pass 1, 47.8 dB |
| 3840 (4K) | 1920 | 2.0 | ~7× cheaper pass 1, 47.2 dB |
| 5120 (5K) | 2560 | 2.0 | ~7× cheaper pass 1 |

Floor at 1920 long edge (= a 1080p-class frame) because 720p detection measurably lost
4 dB. `--detect-edge off` disables; `--detect-edge N` forces a target (clamped to
render size, never upscales).

### 3.3 4K cost

90-frame 4K slice (2160×3840): pass 1 30.8 s (repeat runs 21.3–31 s — thermal), pass 2
`veryfast` 27.3 s → 4.9 MB, `medium` 34.9 s. Downscaling the render to 1920 long edge
(`--max-edge 1920`): pass 1 4.5 s, pass 2 7.7 s → 1.6 MB. **Resolution dominates
everything; the preset is noise at 4K.** A 15 s 4K clip costs ~5 min at full res,
~1 min downscaled.

### 3.4 Gallery visibility (Android specifics)

- Galleries read MediaStore, not the filesystem. A file written by an app/terminal is
  invisible until a MediaStore row exists. `termux-media-scan` inserts it; an app should
  insert it directly (see §6).
- Ownership/permissions were **not** the cause of the original "not detected" problem —
  the files had the same ownership as camera captures; the missing row was.
- ffmpeg's default major brand `isom` is accepted by MediaProvider, but
  `-brand mp42` + `-movflags +faststart` + a real `creation_time` avoid picky players and
  wrong sort dates when the file leaves the device (copying to desktop/NAS/cloud loses
  mtime).
- MediaStore date falls back to file mtime, so creation_time is mostly insurance — still
  cheap to set.

### 3.5 Ecosystem facts (verify dates still hold at implementation time)

- `ffmpeg-kit` (arthenica) was retired **2025-01-06**; binaries were removed from Maven
  Central / CocoaPods on a schedule ending **2025-04-01**; author announced
  **FFmpegKitNext** (source-only) in 2026. `ffmpeg-kit-react-native` and
  `ffmpeg_kit_flutter*` are consequently dead on package managers.
- Android-only maintained fork with the same package/API:
  `dev.ffmpegkit-maintained:ffmpeg-kit-full-gpl:8.1.x` (Maven Central). Same
  `com.arthenica.ffmpegkit` package, `-gpl` = includes `vid.stab`, x264, x265, xvid.
  No iOS, no RN/Flutter bindings, by design.
- Google Play requires **16 KB page size** support for new/updated apps since Nov 2025 —
  the reason the old 6.0 AAR is unusable; the fork targets SDK 35 and 16 KB.
- `vid.stab` was **relicensed to LGPL-2.1-or-later in "1.3"** (unreleased master; RELICENSE.md: GPL-2.0-or-later "up to and including release v1.1.2"), but ffmpeg's
  `configure` still gates `--enable-libvidstab` behind `--enable-gpl`, so a stock build
  with vidstab is GPL anyway.

### 3.6 vid.stab version, and a silent fallback worth knowing

The reference and the AAR are **not** on the same vid.stab: the reference runs **1.1.2**, the AAR ships **≥ 1.1.1** (exact version unverified, see below). All of 1.1.x is GPL-2.0-or-later:

- Termux: the reference device now runs **ffmpeg 9.0.2-1 + libvidstab 1.1.2** (upgraded
  in steps: ffmpeg 7.1.1 → 8.1.2 (libvidstab 1.1.1) → 9.0.2 (libvidstab 1.1.2); the 7.1.1
  libvidstab version is not recorded — see Appendix A for the mirror procedure).
  Both were built `--enable-gpl --enable-libvidstab`.
  **Mirror caveat (checked):** upstream `termux-packages` bumped libvidstab to **1.1.2** on
  2026-09-22 (upstream tag v1.1.2 dated 2026-01-04), and `packages.termux.dev` serves
  **1.1.2**. The reference device's apt source is `https://termux.net`, whose Packages index
  still lists `1.1.1-3`, and the local index (2026-10-22) matches that mirror — so
  `apt update` alone will not pick it up. To test 1.1.2, repoint
  `$PREFIX/etc/apt/sources.list.d/termux.sources` at `https://packages.termux.dev/apt/termux-main`
  first. Both 1.1.x are GPL-2.0-or-later (LGPL only from "1.3"), so this does not change §7.
- FFmpegKit / the maintained Android fork: at tag `v8.1.7-lts-android`,
  `android-8.1-lts/scripts/source.sh` pins `arthenica/vid.stab` tag `v1.1.1` (a fork, not
  upstream) and `arthenica/FFmpeg` tag `n8.1.2` (verified 2026-11-06); the project's Versions
  wiki lists `libvid.stab v1.1.1` for v6.0, `main` and `development`.
- AAR **8.1.9** (inspected directly 2026-11-07, §15.2): inner ffmpeg version string
  **n8.1.3** (8.1.7's source pinned n8.1.2); configure has `--enable-libvidstab`, and
  libavfilter has the vid.stab `fileformat` option, so vid.stab ≥ 1.1.1 (binary TRF1). The
  exact vid.stab version is **unverified** beyond that.

Two consequences:

1. **1.1.1 is GPL-2.0-or-later.** The relicensing to LGPL happened only in vid.stab 1.3,
   so with this binary the library itself is GPL — independently of ffmpeg's
   `--enable-libvidstab`-behind-`--enable-gpl` gating. The app is GPL (§7) either way.
2. **`optalgo=opt` silently behaves as gaussian on 1.1.1.** vid.stab's 1.3 changelog says
   the L1-optimal path "now really runs the optimization instead of falling back to the
   gaussian filter". Verified here, not assumed: on the same `.trf`, `optalgo=opt` (0) and
   `optalgo=gauss` (1) produce **byte-identical** output, while `optalgo=avg` (2) differs.

   Therefore the reference CLI — which never set `optalgo`, so ffmpeg's default `opt` —
   has been measuring **gaussian smoothing all along**. All §3 benchmarks are gaussian
   numbers. To keep the app reproducible across vid.stab versions, the reference pins
   `optalgo=gauss` explicitly (`stabilize.py` v2.0.3, `--optalgo opt|gauss|avg`), which is
   behaviour-neutral on 1.1.1 and protects against a future vid.stab >= 1.3 where `opt`
   genuinely changes the character of the output (static/linear/parabolic segments).

   Re-confirmed on the upgraded stack (ffmpeg 9.0.2 + vid.stab 1.1.2) where the transform
   is deterministic again (§3.7): `optalgo=opt` and `optalgo=gauss` produce **identical**
   raw frames (PSNR `inf`, byte-identical md5). So `opt` still does not run the L1 solver in
   1.1.2 — that arrives with 1.3. The caveat that applied while the device ran ffmpeg 8.1.2
   (§3.7) no longer applies here.


### 3.7 Reproducibility: fixed by the ffmpeg 9 / vid.stab 1.1.2 upgrade

**Status: resolved at both tested resolutions on the phone, with one variable still
confounded.** Measured 2026-11-06 on ffmpeg 9.0.2 + vid.stab 1.1.2, transform-only, raw
frames, fixed `.trf`: **1920 long edge**, 4 runs → one md5 (`d4d55631d3`, PSNR `inf`
pairwise); **3840 long edge (2160×3840)**, 5 runs of 20 frames → one md5 (`ed9f033015`). So
the fix holds at 4K on this build too — which does not contradict §14.2's desktop result
(ffmpeg 7.0.2, vid.stab unknown, 4K still nondeterministic): the defect is build-specific,
and *within* an affected build it is size-dependent (see below). On
**ffmpeg 8.1.2 + vid.stab 1.1.1**, identical runs produced different pixels. After repointing
the apt mirror and upgrading to **ffmpeg 9.0.2 + vid.stab 1.1.2**, four identical runs produce
**byte-identical raw frames** (`md5 d4d55631d3` ×4, PSNR `inf` between all pairs), and pass 1
remains deterministic with `OMP_NUM_THREADS=1`. Two things changed at once (ffmpeg major
version and vid.stab patch), so the credit is unresolved — and it does not transfer to the
AAR. AAR 8.1.7 bundled **ffmpeg 8.1.2 + vid.stab 1.1.1** (fork release `v8.1.7-lts-android`:
"FFmpeg n8.1.2"; `android-8.1-lts/scripts/source.sh` pins vid.stab v1.1.1, §3.6) — the same
*versions* that were nondeterministic on the phone (a different build; its own behaviour is
unmeasured until M0). The current pick, AAR 8.1.9, ships ffmpeg **n8.1.3** with vid.stab
≥ 1.1.1 (§15.2), equally unmeasured.

**Size dependence in affected builds.** Both nondeterministic builds were clean at small
sizes and failed at larger ones: phone 8.1.2 + 1.1.1 deterministic at 640×360; desktop
static 7.0.2 deterministic at 1920, not at 3840 (§14.2). So an AAR repeatability check at
1080p alone proves nothing for 4K — M0 must run both sizes, ≥ 60 frames each (§14.6).

The measurements below are the *old* (8.1.2 + 1.1.1) numbers, kept because they define what
"not reproducible" looks like if the app's AAR turns out to behave that way.

Same input, same `.trf`, same command, repeated on 8.1.2 + 1.1.1: raw-frame PSNR between
two runs.

| configuration | run-to-run PSNR |
|---|---|
| default (`smoothing=15 optzoom=1 crop=keep`) | 35.9 – 43.0 dB over 6 pairs |
| `smoothing=0 optzoom=0` (per-frame warp only) | ~29.7 dB |
| `interpol=no` | ~29.5 dB (`keep`) / ~36.7 dB (`black`) |
| `interpol=bicubic crop=black` | ~29.2 dB |
| plain re-encode, no vidstab | **byte-identical** (deterministic) |
| first frame only | identical (the warp of frame 1 is the identity) |

Interpretation: with the transform file and the decoded frames both fixed, only the warp
stage remains — so **vidstabtransform itself is not bit-reproducible on this build**, and
it is not an artefact of interpolation, crop mode, smoothing or the zoom decision.
Non-deterministic memory reads inside vid.stab 1.1.1 are the leading hypothesis; the
synthetic 640×360 test *was* deterministic, which points at a resolution-dependent path
(edge/padding handling).

Related: `OMP_NUM_THREADS=1` makes **detection** deterministic (trf byte-identical across
runs) but does nothing for the transform. Encoding alone is deterministic; CRF encoding
then amplifies even tiny warp differences into visibly different files (encoded-output
PSNR between two runs drops to ~25–33 dB), which is why hashing outputs is meaningless.

Consequences for this project:

- Keep every pixel comparison **decoded-frame based** with the trf fixed (`--trf`). But do
  **not** gate M1 on 4K pixel PSNR. On the phone's Termux ffmpeg 8.1.2 + vid.stab 1.1.1,
  run-to-run PSNR was 35.9–43.0 dB at default settings and down to ~29.2 dB in other
  configurations (table above). The AAR 8.1.7 used the same version numbers (ffmpeg
  n8.1.2, vid.stab 1.1.1), but 8.1.9 ships a different inner build (n8.1.3). A ≥30 dB
  gate would therefore sit at that floor — *inference* for the AAR, whose own floor is
  unmeasured. (The desktop
  29.2 dB / 18.5 dB pair in §14.2 is a static ffmpeg 7.0.2 with unknown vid.stab: it shows
  such variance exists, not what the AAR does.) M1 gates on the **pass-2 `.trf`
  bytes** instead (§8); pixel PSNR only at 1080p, 4K threshold set after M0 measures the
  AAR's own repeatability.
- Do not advertise "same input ⇒ same output" until the AAR passes that repeatability test.
- Done: **vid.stab 1.1.2** via the repointed mirror (§3.6, Appendix A). Determinism came
  back, `opt` still equals `gauss`.
- Still open, and now the interesting one: **vid.stab master** (changelog entry "1.3": LGPL,
  the L1 camera path; **not a release** — the newest tag is v1.1.2) built from source with
  `cmake` + `clang` (both now installed on the reference device); build into a prefix and
  override with `LD_LIBRARY_PATH` so the system ffmpeg picks it up by soname. That is the
  first chance to A/B `optalgo=opt` (real L1) against `gauss`.


---

## 4. Logic to port verbatim from the reference CLI

Reference: `stabilize.py` **v2.0.8** (gist above; a local, untracked copy lives in
`docs/reference/`, the gist stays canonical), **51** unit tests in `stabilize_tests.py`
(41 + `TestMediastoreScan` 7 + `TestApiAppProbe` 3). ≈34 apply to `:core`; the
terminal/Termux-only classes are `TestPrompter`, `TestHuman`, `TestMediastoreScan` and
`TestApiAppProbe`. 6 of the 7 `TestMediastoreScan` tests fail off-device because
`scan_mediastore` checks `shutil.which("termux-media-scan")` and the tests do not mock it.
Treat the Python file as the spec; port these exactly.

v2.0.7/v2.0.8 changed only the Termux MediaStore scan (new `api_app_installed()` probe via
`pm list packages com.termux.api`, `SCAN_TIMEOUT = 20` on `termux-media-scan`, a Google Play
Termux hint). No change to filter chains, TRF1, geometry, encoder args, containers or naming
— none of it is relevant to the app, which inserts into MediaStore directly (§6).

### 4.1 Defaults

| knob | default | notes |
|---|---|---|
| shakiness | 5 | 1–10, the only detection knob worth asking |
| accuracy | 15 | must be ≥ shakiness (auto-bump + warn) |
| smoothing | 15 | 0–1000, frames*2+1 |
| optzoom | 1 | 0 none / 1 static / 2 dynamic |
| optalgo | gauss | gauss/opt/avg; **pinned** — see §3.6, `opt` == `gauss` on 1.1.1 |
| zoomspeed | 0.25 | only meaningful when optzoom=2 |
| border (crop) | keep | keep / black |
| interpol | bicubic | no/linear/bilinear/bicubic |
| sharpen | yes | unsharp=5:5:0.8:3:3:0.4 |
| crf | 22 | 0–51 |
| preset | veryfast | see §3.1 |
| audio | yes | yes = `-c:a copy`; **no = re-encode to AAC 192k, not drop**; nothing if no stream; advanced only |
| compare | none | none/vstack/hstack, separate output; **CLI only, not in the app** (in-app side-by-side player, §15) |
| tripod | no | forces relative=0:smoothing=0, warn about smoothing |
| stepsize | 6 | 1–32 |
| mincontrast | 0.25 | 0–1 |
| maxshift | -1 | -1–500 (px) |
| maxangle | -1 | -1–3.14 (rad) |
| zoom | 0 | -100–100 % |
| max_edge | 0 | 0 = never downscale the render; **app default 0: 4K stays full resolution (§15)** |
| detect_edge | auto | see §3.2 |

UI rule from the CLI: a plain run asks **three** questions (shakiness, smoothing, crf);
everything else lives behind an "Advanced" section. The table has **21 rows in the CLI**
(3 primary + 18 others, including `compare`) and **20 in the app**, because `compare` is
removed (§15): **17 rows sit outside the 3 primary** in the app. This was a deliberate
decision after users found 11 prompts excessive — preserve it.

### 4.2 Filter chains (exact strings)

```
# pass 1
[<detect_scale>,]vidstabdetect=result=<trf>:shakiness=<s>:accuracy=<a>:stepsize=<st>:mincontrast=<mc>[:tripod=1]

# pass 2
[<render_scale>,]vidstabtransform=input=<trf>:smoothing=<sm>:optzoom=<oz>:optalgo=<gauss|opt|avg>:zoomspeed=<zs>\
:crop=<border>:interpol=<interp>:relative=1:zoom=<z>:maxshift=<ms>:maxangle=<ma>[:tripod=1]\
[,unsharp=5:5:0.8:3:3:0.4]
```

Scale filter, orientation-agnostic (portrait phone video must work):

```
scale=w='if(gte(iw,ih),min(iw,<TARGET>),-2)':h='if(gte(iw,ih),-2,min(ih,<TARGET>))'
```

Number formatting: emit `-1` not `-1.0`, `0.25` not `0.250000` (ffmpeg accepts either;
keep output diffable against the CLI).

Invocation shape: `ffmpeg -hide_banner -nostdin -i SRC -map 0:v:0 [-an -sn | -map 0:a?] -vf <chain> ...`
Pass 1 ends with `-f null -`; pass 2 adds encoder args then the output path.

**HDR input (app-only step, §15).** When the source video stream's transfer function is
HLG (`arib-std-b67`) or PQ (`smpte2084`), pass 2 tone-maps to SDR with `zscale` + `tonemap`
(zimg is confirmed in AAR 8.1.9: `--enable-libzimg`, `zscale` and `tonemap` present in
libavfilter). `:core` owns the transfer-function detection and the chain string; the
reference CLI has no such step. Placement: after `<render_scale>`, before `vidstabtransform`.
Starting-point chain:

```
zscale=t=linear:npl=100,format=gbrpf32le,zscale=p=bt709,tonemap=tonemap=hable:desat=0,zscale=t=bt709:m=bt709:r=tv,format=yuv420p
```

[INFERENCE] The operator, `npl`, and the output colour tags are not validated here;
validate on one HLG and one PQ clip at M1/M4. Whether detecting on the untone-mapped
luma in pass 1 matters is unmeasured.

### 4.3 Transform file (`TRF1`) binary format — required for rescaled detection

```
header   24 B   "TRF1" | int32 accuracy | int32 shakiness | int32 stepsize |
                double mincontrast
per frame       int32 frame_no | int32 count
per motion 26 B int16 dx, int16 dy, int16 px, int16 py, int16 field_size |
                double contrast | double quality
```

To rescale by `k`: multiply **dx, dy, px, py** (displacements *and* field positions) and
leave `field_size` + both doubles untouched. Refuse (do not clamp) if a scaled value
exceeds int16 range. Validate by consuming the file byte-exactly; assert `pos == len` at
the end. The `TRF1` magic makes future format changes fail loudly.

**Do not** attempt `vidstabdetect=fileformat=ascii` + rescale + re-read: `vidstabtransform`
has no `fileformat` option and rejects the ASCII file
(`[vid.stab - serialization] cannot read frame local motions from file, got <byte>`)
while still emitting a plausible-looking video. This was measured; it is a silent
correctness trap.

### 4.4 Output rules

- Default name `<stem>-stab.<ext>`; `-o out` (no dotted basename) → `out.<src ext>`.
- h264-capable containers: mp4, mov, m4v, mkv, avi, ts, m2ts, flv, nut, 3gp.
  webm/ogv/ogg/gif/mp3/m4a/wav → fall back to `.mkv` with a warning.
- mp4/mov/m4v: `-movflags +faststart -brand mp42 -metadata creation_time=<ISO>`.
  creation_time = source's `format.tags.creation_time` if it parses, else source mtime as
  local `%Y-%m-%dT%H:%M:%S`.
- `-map_metadata 0` on pass 2.
- Audio: `-c:a copy` when a stream exists and audio=yes; `-c:a aac -b:a 192k` when
  audio=no; nothing when there is no audio stream.

### 4.5 Behavioural details worth keeping

- `--trf FILE` reuses detection (skips pass 1). **Contract since v2.0.5:** `--keep-trf`
  saves the *unscaled detection-space* trf as `<output>.trf` plus `<output>.trf.json` =
  `{"long_edge": N}` (TRF1 has no resolution field). On reuse, `k = render_long / N`, one
  rescale from the original measurement, correct for any `--max-edge`/`--detect-edge`
  change. No sidecar ⇒ the trf is assumed to match the render already (true for ≤ 2.0.4
  files and plain ffmpeg output); a malformed sidecar is an error. Before v2.0.5 the saved
  file was already render-space and got rescaled again (motion ×k too large, 2× at 4K;
  re-tune vs original 16.2 dB). This enables cheap re-tuning ("try smoothing 30 without
  re-detecting"). The app's re-tune flow is **post-v1** (§15); `:core` keeps the trf-reuse
  logic (`trfEdge`, rescale-once) so it can be added without a refactor.
- **Binary-trf capability (since v2.0.6).** The CLI checks whether ffmpeg's
  `vidstabdetect` has the `fileformat` option (ffmpeg built against vid.stab ≥ 1.1.1, the
  first release with binary TRF1). The exact vid.stab release is not observable: every
  1.1.x logs `init v1.1 (2015-05-16)`. Without binary support (vid.stab 1.1.0, e.g.
  Ubuntu 24.04) reduced-size detection would produce an ASCII trf that cannot be rescaled,
  so `detect_edge` falls back to the render size with a warning, and reusing an ASCII trf
  at k ≠ 1 is refused with a hint. The app's AAR has binary support (8.1.9: the `fileformat`
  option is present, so vid.stab ≥ 1.1.1; verified statically, §15.2), so this is a CLI-only
  concern; `:core` still ports the `binary_trf` parameter for parity.
- Overwrite confirmation only in interactive mode; non-interactive overwrites with a
  warning.
- Exit codes: 0 ok, 1 failure (bad input/ffmpeg), 2 usage.
- Temp files in a private dir, cleaned up on any exit path.

---

## 5. Architecture

Two Gradle modules, one reason: keep the ported logic platform-free and unit-testable on a
plain JVM. Android only, no KMP (§15).

```
:core   kotlin("jvm")   no Android imports
          Settings, Knob validation, DetectGeometry, TrfScaler (+ `binary_trf` parity),
          FilterChain (incl. HDR tone-map: transfer-function detection + chain string, §4.2),
          EncoderArgs, ContainerRules, OutputNaming, ProgressMath, estimate_seconds
          src/test: port the ≈34 applicable Python tests (plain JVM ⇒ src/test, not commonTest)
:app    android         Compose UI (Miuix, see §13), FfmpegRunner, ForegroundService,
                        MediaStore, picker + share target, compare player
```

`expect/actual` is **not** needed (no KMP, §15); keep `:core` a plain JVM module and inject the
"run ffmpeg" function into the UI layer.

Key seams:

```
fun interface StabilizeRunner {           // :app
    suspend fun run(req: StabilizeRequest, onProgress: (Float) -> Unit, onLog: (String) -> Unit): Result<File>
}

data class StabilizeRequest(              // :core
    val src: String, val out: String, val settings: Settings,
    val quality: Quality,                  // §7: Crf(22) default
    val trf: String?,                      // reuse detection
    val trfEdge: Int?,                     // detection-space long edge of a reused trf (§4.5); null = assume it matches the render
    val renderEdge: Int?, val detectEdge: Int?,
)
```

The runner owns only: build two commands from `:core`, run pass 1, scale the trf, run
pass 2, report progress/cancel. Everything else is testable off-device.

---

## 6. Android integration specifics

- **Dependency**: `dev.ffmpegkit-maintained:ffmpeg-kit-full-gpl` **8.1.9**, **vendored**
  (§15): the AAR is a GitHub release asset on our repo, fetched by Gradle/CI and verified
  against sha256 `72094919ac812598ec3f77ba2631e5b2dbd50b1daa98dfa64e9fc565f2b52ede`; no
  Maven dependency on the fork. The free `dev.ffmpegkit-maintained:ffmpeg` artifact has no
  H.264 encode, so full-gpl is required (§15.2). `ndk { abiFilters += "arm64-v8a" }`,
  **minSdk 29** (`RELATIVE_PATH`/`IS_PENDING` are API 29+; no legacy `WRITE_EXTERNAL_STORAGE`
  path). Confirm at build time that `ffmpeg -filters` lists `vidstabdetect` (see M0).
- **Picking input**: two entry paths, both yielding a SAF `content://` Uri (§14.4):
  `ActivityResultContracts.PickVisualMedia` (no storage permission) and a share target
  (`ACTION_SEND`, `video/*`).
- **Output**: encode into `cacheDir`, then insert into MediaStore:
  `RELATIVE_PATH=Movies/Staydee`, `IS_PENDING=1` → write → `IS_PENDING=0`;
  set `DATE_TAKEN`/`DATE_ADDED` from the derived creation_time. This replaces
  `termux-media-scan`, `-brand mp42` and the scan-state machinery from the CLI (still set
  the muxer tags — they matter once the file leaves the device).
- **Long jobs**: `ForegroundService` with `foregroundServiceType="mediaProcessing|dataSync"`:
  `mediaProcessing` (+ `FOREGROUND_SERVICE_MEDIA_PROCESSING`) on API 35+, `dataSync`
  (+ `FOREGROUND_SERVICE_DATA_SYNC`) on API 34, none required below; `POST_NOTIFICATIONS`; progress in the
  notification, `PARTIAL_WAKE_LOCK`. A 48 s encode dies when the screen sleeps otherwise.
- **Cancellation**: `FFmpegKit.cancel()`; also cancel *between* passes.
- **Progress**: `StatisticsCallback.getTime()` (ms) ÷ duration from
  `MediaMetadataRetriever` or `FFprobeKit`; elide the bar when duration is unknown.
- **Memory**: clips ≤ 5 min and ≤ 4K (§15); `maxEdge=0` by default, so 4K renders at full
  resolution. 1080p is comfortable; **4K × 5 min is the risk**. The AAR decodes in-process,
  not in the Java heap, so this is a native-buffers question — **measure peak native memory
  at M1/M4** at 4K × 5 min. Worst case is ~100 min per job (model: §3.3's ≈5 min per 15 s
  at full-res 4K × 20), so the **estimate before Run is mandatory**; the FGS timeout
  (API 35+, ~6 h) fits.

---

## 7. Encoder decision (made 2026-11-07: option A, §15)

| option | pros | cons |
|---|---|---|
| **A. `libx264 -crf`** (as CLI) | identical output to the reference; CRF is a sane quality knob; one flag | `-gpl` AAR ⇒ **whole app is GPL-3.0**; CPU-bound, filter-bound |
| **B. `h264_mediacodec -b:v`** | ~5× faster, hardware, license-clean if combined with a patched LGPL ffmpeg | no CRF ⇒ bitrate slider with a different quality model; encoder quirks on some SoCs; still needs `--enable-libvidstab` (configure gating patch) if the app were closed source |

**Decided (2026-11-07, §15): option A.** It matches the verified output and is simplest, and
the app is open source under **GPL-3.0** (the AAR is configured `--enable-gpl
--enable-version3`, §15.2). Option B is not planned; revisit only if long-clip performance
becomes the binding constraint. The `:core` API still exposes
`quality: Quality.Crf(22) | Quality.Bitrate(8_000_000)` (carried by `StabilizeRequest`, §5)
so the decision stays reversible.

---

## 8. Milestones and acceptance criteria

| id | scope | acceptance | estimate |
|---|---|---|---|
| **M0** | CI + empty app + AAR | GitHub Actions builds a debug APK and fetches the vendored AAR with a sha256 check (§15); Miuix resolves and compiles beside it (§13.2); on-device the app prints `ffmpeg -filters` output containing `vidstabdetect` (also `zscale`, `tonemap`); `ffmpeg -version` shows 16 KB-compatible libs; `FFmpegKitConfig.getBuildConfiguration()` recorded (licence flags). The filters, licence flags, 16 KB alignment and zscale are already **verified statically** on AAR 8.1.9 (§15.2) — **still confirm on device**. **AAR repeatability**: ≥5 transform-only runs (no x264) from one fixed trf at 1080p and 4K, **≥ 60 frames per size**, `framemd5` + PSNR recorded | 0.5 d |
| **M1** | pipeline, no UI polish | pick a file → two-pass → output in cache; **fixed-input trf gate**: give the app a known detection-space trf plus its long edge, let it render at a size with k ≠ 1 (e.g. 1920-edge trf, 3840 render), and require the pass-2 trf it used (exported in debug builds) to be **byte-identical** to the reference's `scale_trf(input, k)` — the same function the CLI's pass 2 uses, so no CLI export is needed; pass-1 trf bytes app vs CLI reported separately as a diagnostic only (they depend on the ffmpeg/vid.stab build); at **1080p** decoded-frame PSNR vs the CLI with the same fixed trf, threshold from M0 repeatability (bit-identical expected on a deterministic stack); **no 4K pixel gate** until M0 measures the AAR (§3.7); peak native memory at 4K × 5 min recorded (§6); progress reported | 1 d |
| **M2** | UX | 3 primary knobs + Advanced expander; both entry paths work (PickVisualMedia and the `ACTION_SEND` `video/*` share target, via SAF `content://`, §14.4); foreground service survives screen-off; cancel works mid-pass and between passes | 1 d |
| **M3** | gallery | result appears in the system gallery under `Movies/Staydee`, correct date, plays; no stale pending items on cancel | 0.5 d |
| **M4** | geometry | `maxEdge` (default 0) + `detectEdge=auto` + trf rescale; `:core` unit tests (ported) all green, incl. HDR tone-map chain tests; **time estimate (`estimate_seconds`) shown before Run, within ±30% of the actual wall clock** on the reference device at 1080p and 4K; a **≤ 3 min target for a 15 s 4K clip applies only with `maxEdge=1920`** (full-res 4K is ≈5 min per 15 s, §3.3) | 1 d |
| **M5** | release polish | arm64 APK ≤ 40 MB (sideload via GitHub releases; no AAB, §15); graceful errors; in-app synchronized side-by-side compare player (source vs result). Estimate to be re-set: compare player added, re-tune flow removed (post-v1, §15) | to be re-set |

Performance gate for M1/M4 on the reference device: **15 s 1080p ≤ 120 s wall clock** with
defaults (measured reference: 48 s originally; 43.5 s on ffmpeg 9.0.2, §3.1; the AAR's own time is unmeasured until M1).

---

## 9. For the evaluating agent: what to challenge

1. **Licensing.** *Answered (§15): GPL-3.0, option A.* The AAR is configured
   `--enable-gpl --enable-version3`. Note vid.stab 1.1.x (what both sides ship) is GPL-2.0-or-later; only master ("1.3", unreleased) is LGPL-2.1-or-later. ffmpeg also gates it behind `--enable-gpl`.
2. **Fork longevity.** *Answered (§15): the AAR is vendored* as a GitHub release asset on
   our repo with a sha256 check in Gradle/CI; no Maven dependency on the fork. If the fork
   stalls, the fallback is still building FFmpeg + vid.stab with the NDK (a day, documented
   in the vid.stab README: `--enable-gpl --enable-libvidstab`).
3. **Does the PSNR gate make sense?** It assumes x264 (option A). Under option B the
   comparison must be structural (crop/zoom/edges) rather than pixel-exact.
4. **4K handling.** *Answered (§15): full resolution by default (`maxEdge=0`).* A downscale
   to 1920 stays available in Advanced. Cost (§3.3): ≈5 min vs ≈1 min per 15 s, hence the mandatory time estimate.
5. **Do we need the compare video?** *Answered (§15): no.* The app gets an in-app
   synchronized side-by-side player; the `compare` knob is CLI-only.
6. **Prompt/UX parity.** The CLI's "3 questions" decision was hard-won; do not silently
   expand it. Any new knob must be justified with a measurement.
7. **Anything in §3 that may be device-specific.** All timings are one arm64 phone with
   thermal throttling (same config varied 21–31 s run to run). Ratios held up; absolute
   seconds should be re-measured on the target hardware at M0.
8. **Keep vid.stab versions aligned** (§3.6). They are **not** aligned now: reference =
   ffmpeg 9.0.2 + vid.stab 1.1.2 (deterministic), AAR 8.1.9 = ffmpeg n8.1.3 + vid.stab ≥ 1.1.1
   (exact patch unverified; behaviour unmeasured until M0; the earlier phone stack ffmpeg
   8.1.2 + 1.1.1 was nondeterministic). The M1 gate therefore uses trf bytes (§8). If the
   AAR ever bumps vid.stab to >= 1.3, the L1 camera path starts running for real and output
   changes character — re-baseline, and
   keep `optalgo=gauss` pinned unless deliberately switching.
9. **Miuix is 0.9.x and self-described as experimental** (§13). *Decided (§15): Miuix 0.9.4,
   wrapped in a thin `ui/` layer*, with Material 3 Expressive as the fallback (§13.4).
   Still verify on M0 that it resolves and compiles next to the FFmpegKit AAR at minSdk 29.

---

## 10. Verification assets

- Reference implementation + 51 unit tests: gist `097fa67734767e131825150ebedd3b95`,
  which holds **both** `stabilize.py` (v2.0.8) and `stabilize_tests.py`. The applicable
  tests are ported to `:core`'s `src/test` in M4; keep the two in sync by treating the gist
  as the canonical pair (`stabilize.py --publish` uploads both).
- **Oracle testing** (revised per §3.7): pass a **fixed transform file** to both sides
  (`--trf ref.trf`), then compare *decoded frames*, not encoded bytes:
  `ffmpeg -i app.mp4 -i ref.mp4 -lavfi "[0:v][1:v]psnr" -f null -`. Expected value depends
  on the stack: bit-identical on a deterministic one (reference now), ~29–43 dB measured
  on the phone's Termux ffmpeg 8.1.2 + vid.stab 1.1.1; the AAR (n8.1.3 + vid.stab ≥ 1.1.1, different
  build) is unmeasured until M0. Do not use file hashes of encoded output.
  The primary oracle is the fixed-input pass-2 trf byte check (§8 M1).
- Benchmark recipe used above: cut a 3 s slice with `-c copy`, time each pass with
  `date +%s%N` around `ffmpeg`, and read `/sys/class/thermal/thermal_zone0/temp` before
  and after — it moved 46 °C → 57–63 °C, which is why repeated runs drift.

---

## 11. Rejected alternatives (and why)

| option | verdict |
|---|---|
| React Native / Expo | needs a Kotlin native module anyway (~150 lines) plus a JS layer; expo-go cannot host custom native code, so a dev build/CI is required regardless. No upsides for Android-only |
| Flutter | ~same LOC, zero FFmpeg glue (plugin exists), but adds a Dart toolchain *on top of Gradle* and a third-party fork whose setup scripts fetch binaries at build time. Choose only if iOS becomes in scope (Flutter is the only ecosystem with prebuilt full-gpl iOS binaries) |
| Kotlin Multiplatform now | pure overhead for one target; `:core` stays platform-free as a plain JVM module (§5). Decided (§15): Android only, no KMP, no desktop target |
| FFmpeg compiled to WebAssembly in a browser | possible (a third-party vidstab core exists) but wasm32 caps memory ~2 GB, the MT core needs COOP/COEP and has hang bugs, and our chain is filter-bound so multithreading doesn't help. Realistic: 2–5 min per 15 s 1080p clip, 4K aborts. Not for 4K phone footage |
| Termux intent wrapper (`RUN_COMMAND` service) | a weekend prototype that reuses the CLI verbatim, but requires Termux + Termux:API installed and the user to grant `com.termux.permission.RUN_COMMAND`. Good for validating UX, not for shipping |

---

## 12. Questions for the owner (all answered 2026-11-07, see §15)

1. Closed-source or open? → **Answered: open source, GPL-3.0, option A** (§7, §15).
2. Play Store, or sideload/F-Droid? → **Answered: sideload only**, GitHub releases, arm64 APK; no AAB/Play work (§15).
3. Is desktop a near-term target? → **Answered: no; Android only.** `:core` is a plain JVM module, no KMP (§5, §15).
4. Raw knobs or only presets? → **Answered: 3 sliders + Advanced**, unchanged (§4.1, §15).
5. Max clip length / resolution? → **Answered: clips ≤ 5 min, ≤ 4K** (§6, §15).

---

## 13. UI toolkit: Miuix (chosen), Material 3 (fallback)

The app is a single settings-shaped screen: pick a file, adjust three sliders, run,
watch progress, save. That is exactly the shape Miuix is strongest at, so `:app` uses
**Miuix** (`top.yukonga.miuix.kmp`), not Material 3.

```kotlin
// :app/build.gradle.kts  (Compose Multiplatform artifacts, NOT the androidx Compose BOM,
// for the UI layer; androidx is still used for Activity/ActivityResultContract/MediaStore)
implementation("top.yukonga.miuix.kmp:miuix-ui-android:0.9.4")
implementation("top.yukonga.miuix.kmp:miuix-preference-android:0.9.4")  // SliderPreference etc.
implementation("top.yukonga.miuix.kmp:miuix-icons:0.9.4")                // optional; an -android variant is unverified
```

### 13.1 Component mapping (names verified against the Miuix docs, not invented)

| our UI | Miuix component | notes |
|---|---|---|
| app shell | `Scaffold` + `TopAppBar` | see 13.2 — required for overlays |
| three primary knobs (shakiness, smoothing, CRF) | **`SliderPreference`** | `title`, `summary`, `valueText`, `valueRange`, `steps`, `onValueChangeFinished` (commit on release, not on every tick) |
| Advanced section | `OverlayBottomSheet` (or `WindowBottomSheet`) | |
| enum knobs: border, interpol, preset, optzoom | `OverlayDropdownPreference` / `OverlaySpinnerPreference` | Spinner for long lists (9 presets); `compare` is not an app knob (§15) |
| booleans: sharpen, tripod, audio copy | `Switch` | |
| stepsize, mincontrast, zoom, maxshift | `SliderPreference` | `maxshift` needs a `-1` sentinel: use `keyPoints` + `magnetThreshold` |
| analysis/encode progress | `LinearProgressIndicator(progress = …)`; `InfiniteProgressIndicator` while duration is unknown | map `StatisticsCallback.getTime()/duration` (CLI behaviour §4) |
| result row | `Card`, `SmallTitle`, `Divider`, `Button` | |
| theme | `MiuixTheme(controller = ThemeController(ColorSchemeMode.MonetSystem, keyColor = …))` | Monet dynamic colour for free; also provide a fixed scheme fallback |

File picker is **not** a Miuix concern: `ActivityResultContracts.PickVisualMedia` (androidx).

### 13.2 Integration caveats (both cost hours if discovered late)

1. **`Scaffold` is mandatory for every overlay.** Miuix's docs state that
   `OverlayDialog`, `OverlayBottomSheet`, `OverlayDropdownPreference`,
   `OverlaySpinnerPreference`, `OverlayListPopup` are all implemented on top of `Scaffold`
   and must be wrapped by it. No `Scaffold` ⇒ the bottom sheet silently never appears.
2. **Compose Multiplatform, not androidx, for UI.** Miuix ships `org.jetbrains.compose`
   artifacts, so the Kotlin/Compose-compiler ↔ CMP version pair must be pinned together,
   and the androidx Compose BOM is not used for the UI. Mixing is fine (CMP wraps androidx
   on Android) but both sides must be pinned explicitly.
3. **Verify at M0, not M4:** that the Miuix artifacts resolve and compile alongside
   the vendored `ffmpeg-kit-full-gpl` 8.1.9 AAR at minSdk 29. Two heavyweight
   dependencies in one build; find out first.

### 13.3 Risk control

Miuix is Apache-2.0, active (≈1.5k stars, 12 open issues), but **0.9.x and explicitly
"experimental — APIs may change without notice"**, with a small bus factor.
Mitigation, mandatory:

- **Wrap every Miuix call in our own thin composables** in one `ui/` package
  (`KnobRow`, `AdvancedSheet`, `RunButton`, `ProgressRow`). An API break should touch
  that package only — never the screen or `:core`.
- Pin **exact** versions for all `top.yukonga.miuix.kmp` modules; never use version ranges.
- Keep the screen logic free of Miuix types (plain `Settings` in, lambdas out), so a swap
  is mechanical.

### 13.4 Fallback

**Material 3 Expressive** (androidx `material3`, Compose BOM): stable, ships expressive
components/motion, and `Slider`/`ModalBottomSheet`/`LinearProgressIndicator` cover the
same screen with no third-party risk. Swapping means rewriting only `ui/` thanks to 13.3.
Decision rule: if Miuix changes break the build at a bad time, or the pinned version fails
the M0 compile check, ship M3 and revisit after v1.

### 13.5 Rejected / not chosen (for the record)

| option | why not |
|---|---|
| Orbit Compose (kiwicom, MIT) | solid Material-3 design system but travel-flavoured and Android-only; no slider-row equivalent to `SliderPreference` |
| compose-cupertino | iOS aesthetics on an Android utility — wrong signal |
| compose-fluent-ui, czan, ComposeTheme, compose-unstyled | v0.x, small, or a DIY layer we don't need |
| material-motion-compose | complementary, not a toolkit; can be added later for motion |

---

## 14. Review feedback (desktop agent, rev 7) — for the phone agent

Evidence labels: **verified** = measured or read from source/docs; **inference** = not
measured; **phone** = needs a measurement on the reference device or the AAR.

### 14.1 Fixed in the reference CLI (`stabilize.py` v2.0.5)

- **Re-tune double rescale** (verified). See §4.5. Repro on a 2 s shaky 3840×2160
  synthetic clip, `--max-edge 1920 --detect-edge 960` (k = 2, deterministic render size):
  re-tune vs first run 16.2 dB on 2.0.4, `inf` (bit-identical) on 2.0.5. At 4K the fix is
  covered by `TestTrfReuse` and the code path only, not by pixel comparison (§14.2).
- **Phone (done 2026-11-06, ffmpeg 9.0.2 + vid.stab 1.1.2, 1 s 2160×3840 clip):**
  `--keep-trf` wrote `<out>.trf` + `{"long_edge": 1920}`; `--trf` printed
  `trf: 1920p -> 3840p — rescaling the transforms x2`; the re-tune output was
  **byte-identical** to the first pass (`md5 c841fba42b` both, PSNR `inf`) — stronger than
  the desktop 16.2 dB→inf check because this stack is deterministic (§3.7). The 2.0.4 bug
  reproduced on the same clip: reuse gave a different file at **19.9 dB**. First-pass output
  is unchanged between 2.0.4 and 2.0.5 (same md5), so the fix is scoped to the reuse path.
  The M1 byte gate needs no CLI export: from a fixed input trf, pass 2 reads exactly
  `scale_trf(input, k)` (§8, §14.6).

### 14.2 Desktop reproducibility measurements (x86, synthetic 3840×2160, 60 frames)

Static ffmpeg 7.0.2 (johnvansickle; vid.stab version **unknown**), fixed `.trf`, raw frames:

| check | result |
|---|---|
| decode only ×2 | identical |
| detect ×2 at 1920 and 3840 (no OMP pinning) | identical `.trf` |
| 4K transform ×2: default / `OMP_NUM_THREADS=1` / ASLR off / all threads pinned (`-threads 1 -filter_threads 1` + OMP=1, ×3) | differs every time |
| 4K transform from an ASCII trf | differs (34/60 frames) ⇒ not the binary reader |
| 1920 transform ×2 | identical |
| 4K transform-only ×2 into FFV1, decoded PSNR | 29.2 dB avg, 18.5 dB worst frame, 27/60 frames identical |
| system ffmpeg 6.1.1 + vid.stab 1.1.0, 4K ×2 | identical (0/60 differ) |

Ruled out (verified): threads, address layout, decoder, trf reader. **Not tested:**
uninitialized heap/stack/mmap reads — a same-value `MALLOC_PERTURB_` pair differed, but it
was never shown that the tunable reaches that static binary, so it excludes nothing. The
6.1.1 vs 7.0.2 rows are different ffmpeg *and* vid.stab versions, so they show the defect
exists in some builds, not which component causes it.

**Phone:**
1. State the resolution of the four `d4d55631d3` runs (§3.7). If not 4K, repeat ≥5 runs at 4K.
2. If attribution matters (AAR 8.1.7 shipped ffmpeg 8.1.2 + vid.stab 1.1.1; 8.1.9 ships n8.1.3, §15.2): upgrade one component
   at a time, ≥5 transform-only runs each. One matching pair is weak evidence.

### 14.3 Termux packaging (verified, 2026-11-06)

`packages.termux.dev` (aarch64) lists libvidstab 1.1.2 and ffmpeg 9.0.2-1; termux-packages
commit "bump(main/libvidstab): 1.1.2" is dated 2026-09-22. The vid.stab GitHub release
v1.1.2 was published 2026-07-29 (the 2026-01-04 in §3.6 may be the tag's commit date).
Version history: 1.1.0 ASCII trf only, no `fileformat` (Ubuntu 24.04 ships it; there
`detect_edge=auto` fails loudly on every clip >1920 with "not a TRF1 transform file");
1.1.1 adds the binary TRF1 format; 1.1.2 fixes a binary-read offset bug (PR #117).

### 14.4 Android corrections not yet reflected elsewhere

- **SAF input** (verified gap): both entry paths (PickVisualMedia and the `ACTION_SEND`
  `video/*` share target, §15) yield `content://`; pass
  `FFmpegKitConfig.getSafParameterForRead()` or copy to `cacheDir` first.
- **Container fallback**: port `container_for` as is (`NO_H264` → mkv). `-c:a copy`
  keeps e.g. vorbis, which mp4 rejects. Alternative only if mkv playback fails at M3:
  mp4 + AAC re-encode for non-mp4-safe audio.
- **Number formatting** (inference): Python `repr(float)` vs Kotlin `Double.toString()`
  differ for small values (`0.0001` vs `1.0E-4`); write an explicit formatter for `fmtnum`.
- **Rounding** (verified from the tests): `scale_trf` uses Python half-to-even `round()`.
  Witness: `test_scale_up_down`, dx = 1 at k = 0.5 expects 0; Kotlin `roundToInt()` gives 1.
  Use `Math.rint`.
- **Untested reference logic** worth new `:core` tests: `fmtnum`, `src_creation_time`,
  `-map_metadata 0`, `tripod=1` in the *detect* chain, accuracy ≥ shakiness bump, the
  exact ±32767 bound.
- **Runtime estimate**: port `estimate_seconds`; show it before Run (mandatory, §15). The
  model gives 15 s 4K ≈ 138 s, while §3.3 measured ≈5 min per 15 s at full-res 4K — an
  unreconciled discrepancy; the M4 gate is the estimate being within ±30% of actual.
- **Progress across two passes**: weight per pass (≈37 % detect / 63 % transform at 1080p).
- **Fork risk** (verified): fork created 2026-06-21, 25 stars, one main contributor, paid
  "Pro" tier, recent rename Full→Pro; free artifact is now `dev.ffmpegkit-maintained:ffmpeg`
  with the old names as aliases; release notes do not list vid.stab. Pin exactly and vendor
  the AAR (decided: vendored, §15; 8.1.9 inspected, §15.2).
- **Miuix artifacts** (verified): Android-only projects use `miuix-ui-android` /
  `miuix-preference-android` (§13 now uses them); 0.9.4 is latest (§14.5) and tracks
  Compose Multiplatform 1.11.0 as of 0.9.1. Only `miuix-blur` needs minSdk 33. Decided: 0.9.4 (§15).
- **FGS timeout**: `Service.onTimeout(int, int)` exists from API 35 only; share one cleanup
  path for cancel and timeout so no `IS_PENDING` row survives (M3).
- **Licence** (resolved 2026-11-07): the AAR 8.1.9 configure line has
  `--enable-gpl --enable-version3`, so the app is **GPL-3.0** (§15.2). The earlier open
  question (fork repo LICENSE LGPL-3.0 vs the x264 tier needing GPL-3.0) is settled by the configure flags.

### 14.5 Phone-agent review of rev 7 (verified 2026-11-06)

Confirmations and corrections to §14, checked on the reference device:

- **`stabilize.py` 2.0.5 adopted**: local `~/bin/stabilize.py` + `stabilize_tests.py` are now
  byte-identical to the gist; 36 tests pass on-device (`python3 stabilize_tests.py`).
- **vid.stab v1.1.2 published 2026-07-29** (GitHub releases API) — §14.3 correct; the
  2026-01-04 date in this plan was the tag's commit date.
- **Miuix versions are stale in §14.4**: Maven Central metadata says **0.9.4** is latest for
  both `miuix-ui` and `miuix-ui-android` (not 0.9.1/0.9.2). Both artifact names resolve, so
  the KMP name works for an Android-only app too; the `-android` suffix is still the more
  explicit choice.
- **Not independently verified:** the AAR's inner FFmpeg being `n8.1.2` (§3.7/§14.1). It
  comes from the fork's release notes; M0's `FFmpegKitConfig.getBuildConfiguration()` is the
  thing that settles it, as §14.4 already says.
  **Update 2026-11-07:** AAR 8.1.9 inspected directly: inner ffmpeg version string
  **n8.1.3** (§15.2).
- **Sidecar caveat worth writing into `:core`**: `trf_reuse` treats a missing `.json` as
  "matches the render". That is right for CLI-produced files, but a kept file records only
  *today's* render edge, so a hand-made trf reused at a different `--max-edge` will be scaled
  from a wrong edge without complaint. Suggest warning when `known == False` **and**
  `--keep-trf` is also given (i.e. the guess is about to be persisted as truth).
- **Suggested CLI addition for the M1 byte gate**: `--dump-trf PATH` to export the exact
  render-space trf the transform pass used, instead of relying on the temp dir.

### 14.6 Desktop reply to §14.5 (2026-11-06)

- **AAR inner versions: now verified from source**, not only release notes. At tag
  `v8.1.7-lts-android`, `android-8.1-lts/scripts/source.sh` pins `arthenica/FFmpeg`
  `n8.1.2` and `arthenica/vid.stab` `v1.1.1`. This shows what the build script fetches;
  M0's `getBuildConfiguration()` still confirms what the shipped binary contains.
- **4K determinism on 9.0.2 + 1.1.2: accepted** for the reference stack (5 × 20-frame 3840
  runs). Desktop 4K pairs differed in 33–48 of 60 frames; which frames first diverge was not
  recorded, so 20 frames is a reasonable but unproven minimum. For the AAR check use the
  full clip, ≥ 60 frames.
- **Sidecar caveat: implemented in v2.0.6** (smoke-verified). Reusing a trf without `.json`
  together with `--keep-trf` now warns `assuming it matches the <N>p render, and --keep-trf
  will record <N> as its size` plus how to undo it; without `--keep-trf` it stays an info
  line. In `:core` the app always writes the edge itself, so the unknown case only arises
  for imported trfs.
- **`--dump-trf PATH`: dropped** (owner decision). With a fixed input trf, pass 2 reads
  exactly `scale_trf(input, k)`; v2.0.6 routes both CLI paths through one helper,
  `render_trf`, covered by `test_render_trf_rescales_exactly_once`. M1 compares the app's
  exported pass-2 trf against that function, so the CLI needs no new flag. Trade-off
  accepted: the CLI's `run()` wiring is covered by smoke runs, not by a byte export.
- **ffmpeg / vid.stab version check: added in v2.0.6** (see §4.5). Header line, e.g.
  `ffmpeg 9.0.2 · transforms: binary, rescalable (vid.stab >= 1.1.1)`. Smoke-verified on
  desktop: static 7.0.2 → binary; Ubuntu ffmpeg 6.1.1 + vid.stab 1.1.0 → ASCII only, full
  4K run completed with detection at 3840 (v2.0.2 was observed failing there with "not a
  TRF1 transform file"; 2.0.3–2.0.5 share that code path). **Phone:** confirm the header on
  9.0.2 + 1.1.2 reports binary.
- **Miuix 0.9.4:** take it. Pin the exact version in §13 at M0, and check its Compose
  Multiplatform version (0.9.1 tracked CMP 1.11.0).

---

## Appendix A. Reference environment (for reproducing the measurements)

Reference device: 8-core arm64 Android phone, Termux, thermal throttling present
(idle ~46 °C, 57–63 °C during runs, so identical configs drift 21–31 s run to run).

| component | version |
|---|---|
| Termux package mirror | `https://packages.termux.dev/apt/termux-main` (see note) |
| ffmpeg | 9.0.2-1, `--enable-gpl --enable-libvidstab` |
| libvidstab | 1.1.2 (GPL-2.0-or-later) |
| python3 | 3.12 |
| reference CLI | `stabilize.py` v2.0.8 + `stabilize_tests.py` (51 tests) |

**Note — the mirror switch is not just editing one line.** The device originally used
`https://termux.net`, which lags: it still listed libvidstab 1.1.1-3 while
`packages.termux.dev` had 1.1.2. Switching required:

1. `URIs:` in `$PREFIX/etc/apt/sources.list.d/termux.sources` → `https://packages.termux.dev/apt/termux-main`.
2. A fresh keyring, because the newer repo is signed with a key the installed keyring lacked
   (`NO_PUBKEY 5A897D96E57CF20C`): download `termux-keyring_3.14_all.deb` from
   `.../pool/main/t/termux-keyring/` and `dpkg -i` it (installing a .deb needs no repo
   signature).
3. Delete the now-dangling `Signed-By:` line from `termux.sources` — the new keyring no
   longer ships `termux-packages.gpg`, and the symlink in `trusted.gpg.d` pointed at it.
4. `apt update`, then **`apt full-upgrade`** — installing ffmpeg 9 alone fails its
   post-install (`CANNOT LINK EXECUTABLE: library "libplacebo.so.387" not found`); 148
   packages had to move for the dependency chain to be consistent.

---

## 15. Owner decisions (2026-11-07)

### 15.1 Decisions

| # | topic | decision | reflected in |
|---|---|---|---|
| 1 | licence / encoder | Open source, **GPL-3.0**; encoder option A (libx264 + CRF) | §7, §9.1, §12.1, §14.4 |
| 2 | distribution | Sideload only, GitHub releases; arm64 APK; no AAB / Play work | §6, §8 M5, §12.2 |
| 3 | controls | 3 sliders + Advanced (unchanged) | §4.1, §12.4 |
| 4 | 4K | Keep full resolution by default (`maxEdge=0`); downscale stays an Advanced option | §4.1, §6, §8 M4, §9.4 |
| 5 | limits | Clips ≤ 5 min, ≤ 4K. Worst case ~100 min per job (§3.3: ≈5 min per 15 s at full-res 4K × 20); native memory at 4K × 5 min measured at M1/M4; FGS timeout (API 35+, ~6 h) fits; time estimate before Run is mandatory | §6, §8 M1/M4, §12.5 |
| 6 | compare | In-app synchronized side-by-side player; `compare` removed from app defaults/Advanced (CLI keeps it) | §4.1, §8 M5, §9.5, §13.1 |
| 7 | UI kit | Miuix 0.9.4 (`top.yukonga.miuix.kmp:miuix-ui-android`, `miuix-preference-android`), wrapped in `ui/` | §9.9, §13 |
| 8 | AAR | Vendored as a GitHub release asset on our repo + sha256 check in Gradle/CI; no Maven dependency on the fork | §6, §9.2, §8 M0 |
| 9 | re-tune flow | Post-v1; `:core` keeps the trf-reuse logic | §4.5, §8 M5 |
| 10 | desktop | Android only; `:core` plain JVM, no KMP | §5, §11, §12.3 |
| 11 | HDR input (HLG/PQ) | Tone-map to SDR in pass 2 via zscale+tonemap (zimg confirmed in the AAR); app-only step, in `:core` | §4.2, §5 |
| 12 | entry | In-app `PickVisualMedia` + share target (`ACTION_SEND` `video/*`), both over SAF `content://` | §6, §8 M2, §14.4 |
| 13 | gallery folder | `Movies/Staydee` (was `Movies/Stabilized`) | §6, §8 M3 |

### 15.2 AAR facts (verified 2026-11-07)

Inspected `dev.ffmpegkit-maintained:ffmpeg-kit-full-gpl:8.1.9` (latest on Maven Central;
37 MB; sha256 `72094919ac812598ec3f77ba2631e5b2dbd50b1daa98dfa64e9fc565f2b52ede`):

- configure: `--enable-gpl --enable-version3 --enable-libx264 --enable-libvidstab
  --enable-libzimg --enable-mediacodec` ⇒ the app is **GPL-3.0**.
- libavfilter contains `vidstabdetect`, `vidstabtransform`, `zscale`, `tonemap`, and the
  vid.stab `fileformat` option ⇒ vid.stab ≥ 1.1.1 (binary TRF1). The exact vid.stab version
  is still unverified.
- Inner ffmpeg version string **n8.1.3** (8.1.7 pinned n8.1.2).
- LOAD alignment 0x4000 (16 KB pages ok). ABIs: arm64-v8a + x86_64.
- The free `dev.ffmpegkit-maintained:ffmpeg` artifact has **no H.264 encode** (POM: "Paid
  tiers add H.264/H.265 encode"), so full-gpl is required.

These are static findings; M0 re-confirms them on device (§8).

### 15.3 Still open after these decisions

- Exact vid.stab version in the AAR (≥ 1.1.1 only); AAR repeatability at 1080p and 4K (M0).
- Peak native memory at 4K × 5 min (M1/M4); pass/fail threshold not set.
- Estimate model (≈138 s per 15 s 4K, §14.4) vs measured ≈5 min (§3.3): unreconciled; the ±30% gate (M4) forces a recalibration.
- HDR tone-map chain operator/`npl`/colour tags unvalidated (§4.2).
- §3.2 pass-1 cost discrepancy (1.4 s vs 1.06 s per 30 frames) unresolved.
