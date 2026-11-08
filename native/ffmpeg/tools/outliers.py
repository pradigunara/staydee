#!/usr/bin/env python3
"""Frames (by TRF frame number) whose residual shift exceeds 40 px, per video, vs the source's high-motion frames."""
import os, statistics as st, struct, subprocess, sys, tempfile

FF, SRC_TRF = sys.argv[1], sys.argv[2]


def frames_over(trf_bytes, limit):
    pos, out = 24, set()
    while pos < len(trf_bytes):
        fno, n = struct.unpack_from("<ii", trf_bytes, pos); pos += 8
        if n:
            dx = st.median(struct.unpack_from("<h", trf_bytes, pos + 26 * i)[0] for i in range(n))
            dy = st.median(struct.unpack_from("<h", trf_bytes, pos + 26 * i + 2)[0] for i in range(n))
            if (dx * dx + dy * dy) ** 0.5 > limit:
                out.add(fno)
        pos += 26 * n
    return out


src = frames_over(open(SRC_TRF, "rb").read(), 40)
print("source frames >40px:", sorted(src))
for v in sys.argv[3:]:
    with tempfile.TemporaryDirectory() as d:
        trf = os.path.join(d, "r.trf")
        subprocess.run([FF, "-hide_banner", "-nostdin", "-i", v, "-an", "-vf",
                        f"vidstabdetect=result={trf}:shakiness=5:accuracy=15:stepsize=6", "-f", "null", "-"],
                       capture_output=True, check=True)
        out = frames_over(open(trf, "rb").read(), 40)
    print(f"{os.path.basename(v)}: {len(out)} frames >40px, {len(out & src)} overlap source, only-here {sorted(out - src)}")
