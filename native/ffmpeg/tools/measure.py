#!/usr/bin/env python3
"""Measure flicker-like defects of a video: brightness pulsing, residual shake, repeated frames.

usage: measure.py FFMPEG_WITH_BINARY_VIDSTAB video...
"""
import json, statistics as st, struct, subprocess, sys, tempfile, os

FF = sys.argv[1]


def per_frame(video, vf, key):
    out = subprocess.run(
        [FF, "-hide_banner", "-nostdin", "-i", video, "-vf", vf + ",metadata=print:file=-", "-an", "-f", "null", "-"],
        capture_output=True, text=True, check=True).stdout
    return [float(l.split("=", 1)[1]) for l in out.splitlines() if l.startswith(key + "=")]


def residual_motion(video):
    with tempfile.TemporaryDirectory() as d:
        trf = os.path.join(d, "r.trf")
        subprocess.run([FF, "-hide_banner", "-nostdin", "-i", video, "-an", "-vf",
                        f"vidstabdetect=result={trf}:shakiness=5:accuracy=15:stepsize=6", "-f", "null", "-"],
                       capture_output=True, check=True)
        data = open(trf, "rb").read()
    assert data[:4] == b"TRF1"
    pos, shifts = 24, []
    while pos < len(data):
        _, n = struct.unpack_from("<ii", data, pos); pos += 8
        dx = [struct.unpack_from("<h", data, pos + 26 * i)[0] for i in range(n)]
        dy = [struct.unpack_from("<h", data, pos + 26 * i + 2)[0] for i in range(n)]
        pos += 26 * n
        if n:
            shifts.append((st.median(dx), st.median(dy)))
    return shifts


for v in sys.argv[2:]:
    yavg = per_frame(v, "signalstats", "lavfi.signalstats.YAVG")
    dy = [abs(b - a) for a, b in zip(yavg, yavg[1:])]
    uavg = per_frame(v, "signalstats", "lavfi.signalstats.UAVG")
    vavg = per_frame(v, "signalstats", "lavfi.signalstats.VAVG")
    du = [abs(b - a) for a, b in zip(uavg, uavg[1:])]
    dv = [abs(b - a) for a, b in zip(vavg, vavg[1:])]
    diff = per_frame(v, "tblend=all_mode=difference,signalstats", "lavfi.signalstats.YAVG")
    rep = sum(1 for x in diff[1:] if x < 0.05)  # frame identical to the previous one
    sh = residual_motion(v)
    mag = [(x * x + y * y) ** 0.5 for x, y in sh]
    print(json.dumps({
        "video": os.path.basename(v),
        "frames": len(yavg),
        "brightness_step_mean": round(st.mean(dy), 3),
        "u_steps_over_1": sum(1 for x in du if x > 1),
        "v_steps_over_1": sum(1 for x in dv if x > 1),
        "chroma_step_p99": round(sorted(du + dv)[int(len(du + dv) * 0.99)], 3),
        "brightness_step_p99": round(sorted(dy)[int(len(dy) * 0.99)], 3),
        "brightness_steps_over_2": sum(1 for x in dy if x > 2),
        "repeated_frames": rep,
        "residual_shift_px_median": round(st.median(mag), 2),
        "residual_shift_px_p95": round(sorted(mag)[int(len(mag) * 0.95)], 2),
        "residual_frames_over_5px": sum(1 for m in mag if m > 5),
    }))
