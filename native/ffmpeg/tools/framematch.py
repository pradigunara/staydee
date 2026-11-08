#!/usr/bin/env python3
"""Find output frames that show the wrong moment of the video.

usage: framematch.py FFMPEG reference.mp4 candidate.mp4

Both videos are decoded to 90×160 grayscale. A candidate frame is "out of place" when it
matches none of the reference frames i-1, i, i+1 (sampled MSE > 500); for each one the best
matching reference frame over the whole clip is printed. Used in plan §15.6.
"""
import subprocess, sys

FF, REF, CAND = sys.argv[1:4]
W, H = 90, 160
N = W * H


def gray(path):
    data = subprocess.run([FF, "-v", "error", "-i", path, "-vf", f"scale={W}:{H},format=gray", "-f", "rawvideo", "-"],
                          capture_output=True, check=True).stdout
    return [data[i * N:(i + 1) * N] for i in range(len(data) // N)]


def mse(x, y):
    xs, ys = x[::7], y[::7]
    return sum((p - q) ** 2 for p, q in zip(xs, ys)) / len(xs)


ref, cand = gray(REF), gray(CAND)
bad = [i for i in range(1, min(len(cand), len(ref)) - 1)
       if min(mse(cand[i], ref[j]) for j in (i - 1, i, i + 1)) > 500]
print(f"{len(bad)} of {len(cand)} frames out of place")
for i in bad[:20]:
    best = min(range(len(ref)), key=lambda j: mse(cand[i], ref[j]))
    print(f"frame {i:4d} looks like reference {best:4d} (offset {best - i:+d})")
