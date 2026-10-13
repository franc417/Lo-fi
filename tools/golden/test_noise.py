#!/usr/bin/env python3
"""Regression test: grain must be temporally independent (no pattern sliding between frames).

Renders a flat grey frame with heavy grain for consecutive frame indices and cross-correlates the noise of
frames that are 1 and 2 apart over ALL 2D shifts. White noise that is re-drawn every frame shows no
correlation peak anywhere; a pattern that translates (the old bug: 7 rows per frame) shows a peak near 1.0.
"""
import json
import pathlib
import sys

import numpy as np
from PIL import Image

sys.path.insert(0, str(pathlib.Path(__file__).parent))
import render_golden as rg  # noqa: E402

MAX_PEAK = 0.15


def peak_correlation(a, b):
    a = a - a.mean()
    b = b - b.mean()
    corr = np.fft.ifft2(np.fft.fft2(a) * np.conj(np.fft.fft2(b))).real
    corr /= np.sqrt((a * a).sum() * (b * b).sum())
    iy, ix = np.unravel_index(np.argmax(np.abs(corr)), corr.shape)
    return float(abs(corr[iy, ix])), (int(iy), int(ix))


def main():
    pipe = rg.Pipeline()
    look = {"schema": 1, "id": "noise-test", "name": "n", "virtualResolution": {"w": 128, "h": 128},
            "upscale": "nearest", "exportScale": 1, "overlay": {"style": "none"},
            "params": {"digital.grain": 0.8}, "macros": []}
    grey = Image.new("RGB", (128, 128), (128, 128, 128))
    frames = [np.asarray(pipe.render_look(look, grey, frames=k), dtype=np.float32)[..., 1] for k in range(2, 10)]
    worst = 0.0
    for gap in (1, 2):
        for i in range(len(frames) - gap):
            peak, where = peak_correlation(frames[i], frames[i + gap])
            worst = max(worst, peak)
            if peak > MAX_PEAK:
                print(f"FAIL: frames {i + 1} and {i + 1 + gap} correlate {peak:.2f} at shift (dy={where[0]}, dx={where[1]})")
    print(f"worst correlation over all shifts: {worst:.3f} (limit {MAX_PEAK})")
    sys.exit(1 if worst > MAX_PEAK else 0)


if __name__ == "__main__":
    main()
