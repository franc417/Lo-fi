#!/usr/bin/env python3
"""Headless renderer for the LoFiCam shader pipeline (the same GLSL files the Android app ships).

  python tools/golden/render_golden.py            # render every look into /tmp/lofi-golden
  python tools/golden/render_golden.py --update   # refresh docs/looks/*.jpg (the golden images)
  python tools/golden/render_golden.py --check    # fail if a render drifts from docs/looks/*.jpg

Needs a software GL stack (Mesa llvmpipe is fine): libegl1, libgl1-mesa-dri.
"""
import argparse, json, math, pathlib, sys
import numpy as np
from PIL import Image, ImageDraw
import moderngl

ROOT = pathlib.Path(__file__).resolve().parents[2]
ASSETS = ROOT / "render-core/src/main/assets"
GOLDEN_DIR = ROOT / "docs/looks"
INPUT = pathlib.Path(__file__).parent / "input/cat.jpg"
FRAMES = 10            # frames fed through the pipeline so ghosting/history settles
MAE_TOLERANCE = 3.0    # 0-255, measured after a 4x box downsample (robust to JPEG + driver float noise)

PRELUDE = "#version 330 core\n"
FIXED_TIME = {"camcorder": ("12.10.2026", "22:57:03"), "cctv": ("12/10/2026", "22:57:03"), "nightshot": ("OCT 12 2026", "10:57:03 PM"), "handycam": ("OCT 12 2026", "10:57:03 PM")}


def uniform_name(path):
    return "u" + "".join(s[:1].upper() + s[1:] for s in path.split("."))


def load_registry():
    return json.loads((ASSETS / "params.json").read_text())["params"]


def resolve(look, registry, macro_values=None, tuning=None, clarity="wear", lines=0.5):
    vals = {p["path"]: list(p["default"]) if isinstance(p["default"], list) else [p["default"]] for p in registry}
    for k, v in look["params"].items():
        vals[k] = list(v) if isinstance(v, list) else [v]
    for m in look.get("macros", []):
        t = (macro_values or {}).get(m["id"], m["default"])
        t = min(max(t, 0.0), 1.0)
        for tg in m["targets"]:
            tt = t * t if tg.get("curve") == "quad" else t
            a = tg["from"] if isinstance(tg["from"], list) else [tg["from"]]
            b = tg["to"] if isinstance(tg["to"], list) else [tg["to"]]
            vals[tg["path"]] = [x + (y - x) * tt for x, y in zip(a, b)]
    if tuning is not None:
        vals = apply_tuning(vals, registry, tuning, clarity, lines, set(look.get("keep", [])))
    return vals


def apply_tuning(vals, registry, tuning, clarity, lines, keep):
    """Mirrors Tuning.apply in Tuning.kt. Clarity scales noise/blur/smear (except the look's `keep` list);
    lines (0..1, 0.5 = the look's own) scales the horizontal-signal-line params and adds a floor above 0.5."""
    level = next(c for c in tuning["clarity"] if c["id"] == clarity)
    spec = {p["path"]: p for p in registry}
    out = {k: list(v) for k, v in vals.items()}
    for path, f in level["factors"].items():
        if path not in keep:
            out[path] = [x * f for x in out[path]]
    mult = lines * 2.0 if lines <= 0.5 else 1.0 + (lines - 0.5) * 2.0
    add = max(0.0, lines - 0.5) * 2.0
    for path, floor in tuning["lines"]["floor"].items():
        out[path] = [min(x * mult + floor * add, spec[path]["max"]) for x in out[path]]
    return out


# ---------- overlay (mirrors OverlayPainter.kt) ----------
def paint_overlay(font, style, w, h, date, time_s, rec_on=True):
    img = np.zeros((h, w, 4), np.uint8)
    if style == "none":
        return img
    px = max(2, round(min(w, h) / 90))
    margin = 3 * px

    def rect(x, y, rw, rh, color):
        x0, y0, x1, y1 = max(x, 0), max(y, 0), min(x + rw, w), min(y + rh, h)
        if x1 > x0 and y1 > y0:
            img[y0:y1, x0:x1] = color

    def glyphs(s, x, y, color):
        for i, ch in enumerate(s):
            rows = font["glyphs"].get(ch)
            if rows is None:
                continue
            gx = x + i * 6 * px
            for r, row in enumerate(rows):
                for c, v in enumerate(row):
                    if v == "X":
                        rect(gx + c * px, y + r * px, px, px, color)

    def text(s, x, y, color):  # one-cell dark drop shadow, then the text
        glyphs(s, x + px, y + px, BLACK)
        glyphs(s, x, y, color)

    WHITE, RED, BLACK = (239, 239, 239, 255), (255, 45, 61, 255), (0, 0, 0, 255)
    dot = [".XXX.", "XXXXX", "XXXXX", "XXXXX", ".XXX."]
    if style in ("camcorder", "nightshot", "handycam"):
        if rec_on:
            dot_color = WHITE if style == "nightshot" else RED
            for r, row in enumerate(dot):
                for c, v in enumerate(row):
                    if v == "X":
                        rect(margin + c * px, margin + (r + 1) * px, px, px, dot_color)
        text("REC", margin + 7 * px, margin, WHITE)
        if style == "nightshot":
            label = "NIGHTSHOT"
            text(label, w - margin - (len(label) * 6 * px - px), margin, WHITE)
        text(time_s, margin, h - margin - 7 * px, WHITE)
        text(date, margin, h - margin - 16 * px, WHITE)
    elif style == "cctv":
        text("CAM 01", margin, margin, WHITE)
        text(date + " " + time_s, margin, h - margin - 7 * px, WHITE)
    return img


class Pipeline:
    def __init__(self):
        try:
            self.ctx = moderngl.create_standalone_context(backend="egl")
        except Exception:  # no EGL (e.g. CI under xvfb): fall back to the default backend
            self.ctx = moderngl.create_standalone_context()
        self.vert = (ASSETS / "shaders/fullscreen.vert").read_text()
        self.common = (ASSETS / "shaders/common.glsl").read_text()
        self.font = json.loads((ASSETS / "font/pixelfont.json").read_text())
        self.registry = load_registry()

    def program(self, frag_name, common=False):
        frag = (ASSETS / f"shaders/{frag_name}").read_text()
        prelude = PRELUDE + "#define INPUT_SAMPLER sampler2D\n"
        fsrc = prelude + (self.common if common else "") + frag
        return self.ctx.program(vertex_shader=PRELUDE + self.vert, fragment_shader=fsrc)

    def tex(self, size, data=None, linear=True):
        t = self.ctx.texture(size, 4, data)
        t.filter = (moderngl.LINEAR, moderngl.LINEAR) if linear else (moderngl.NEAREST, moderngl.NEAREST)
        t.repeat_x = t.repeat_y = False
        return t

    def draw(self, prog, fbo, textures):
        fbo.use()
        fbo.viewport = (0, 0, *fbo.size)
        for i, t in enumerate(textures):
            t.use(location=i)
        self.ctx.vertex_array(prog, []).render(moderngl.TRIANGLES, vertices=3)

    def set_params(self, prog, vals):
        for path, v in vals.items():
            name = uniform_name(path)
            if name in prog:
                prog[name].value = v[0] if len(v) == 1 else tuple(v)

    def render_look(self, look, image, frames=FRAMES, macro_values=None, hd=False, tuning=None, clarity="wear", lines=0.5):
        ctx = self.ctx
        vr = look.get("hd") if (hd and look.get("hd")) else look["virtualResolution"]
        W, H = vr["w"], vr["h"]
        vals = resolve(look, self.registry, macro_values, tuning, clarity, lines)
        linear_up = look["upscale"] == "linear"

        src = self.tex(image.size, image.convert("RGBA").tobytes())
        ingest_t = self.tex((W, H))
        hist = [self.tex((W, H)), self.tex((W, H))]
        final_t = self.tex((W, H), linear=linear_up)
        for t in hist + [ingest_t, final_t]:
            t.write(bytes(W * H * 4))
        style = look["overlay"]["style"]
        date, tm = FIXED_TIME.get(style, ("", ""))
        ov = paint_overlay(self.font, style, W, H, date, tm)
        overlay_t = self.tex((W, H), ov.tobytes())

        fb = lambda t: ctx.framebuffer(color_attachments=[t])
        f_ingest, f_hist, f_final = fb(ingest_t), [fb(hist[0]), fb(hist[1])], fb(final_t)

        p_ing, p_s1, p_s2, p_pre = (self.program("ingest.frag"), self.program("stage1.frag", True),
                                    self.program("stage2.frag", True), self.program("present.frag"))
        # ingest: harness feeds an upright still; flipV mimics SurfaceTexture's matrix
        iw, ih = image.size
        ta, sa = W / H, iw / ih
        crop = (ta / sa, 1.0) if sa > ta else (1.0, sa / ta)
        p_ing["uTexMatrix"].write(np.array([1, 0, 0, 0, 0, -1, 0, 0, 0, 0, 1, 0, 0, 1, 0, 1], "f4").tobytes())
        p_ing["uRot"].write(np.array([1, 0, 0, 1], "f4").tobytes())
        p_ing["uCrop"].value = crop
        p_ing["uMirror"].value = 0.0
        p_s1["uSize"].value = p_s2["uSize"].value = (float(W), float(H))
        p_s1["uOverlayAmount"].value = 0.0 if style == "none" else 1.0
        self.set_params(p_s1, vals)
        self.set_params(p_s2, vals)

        cur = 0
        for frame in range(frames):
            self.draw(p_ing, f_ingest, [src])
            prev, cur = cur, 1 - cur
            p_s1["uFrame"].value = frame
            p_s1["uHistoryValid"].value = 0.0 if frame == 0 else 1.0
            p_s1["uTex"].value, p_s1["uPrev"].value, p_s1["uOverlay"].value = 0, 1, 2
            self.draw(p_s1, f_hist[cur], [ingest_t, hist[prev], overlay_t])
            p_s2["uFrame"].value = frame
            p_s2["uTex"].value = 0
            self.draw(p_s2, f_final, [hist[cur]])

        # present at 1x (golden) – nearest/linear is a property of final_t
        out_t = self.tex((W, H))
        f_out = fb(out_t)
        p_pre["uTex"].value = 0
        self.draw(p_pre, f_out, [final_t])
        data = f_out.read(components=3)
        return Image.frombytes("RGB", (W, H), data).transpose(Image.FLIP_TOP_BOTTOM)


def contact_sheet(items, cols=4, cell=(480, 360)):
    rows = math.ceil(len(items) / cols)
    sheet = Image.new("RGB", (cols * cell[0], rows * (cell[1] + 28)), (10, 16, 112))
    d = ImageDraw.Draw(sheet)
    for i, (name, im) in enumerate(items):
        x, y = (i % cols) * cell[0], (i // cols) * (cell[1] + 28)
        fit = im.resize(cell, Image.NEAREST if im.size[0] <= 320 else Image.BILINEAR)
        sheet.paste(fit, (x, y + 28))
        d.text((x + 10, y + 8), name, fill=(239, 234, 219))
    return sheet


def clarity_sheet(pipe, image, look_id="camcorder-98"):
    """docs/looks/clarity.jpg: rows = clarity levels, columns = signal lines 0 / 0.5 / 1."""
    tuning = json.loads((ASSETS / "tuning.json").read_text())
    look = next(json.loads(p.read_text()) for p in sorted((ASSETS / "looks").glob("*.json")) if json.loads(p.read_text())["id"] == look_id)
    levels = [c["id"] for c in tuning["clarity"]]
    cell = (320, 240)
    sheet = Image.new("RGB", (cell[0] * 3, (cell[1] + 22) * len(levels)), (10, 16, 112))
    d = ImageDraw.Draw(sheet)
    for r, lv in enumerate(levels):
        for c, ln in enumerate((0.0, 0.5, 1.0)):
            im = pipe.render_look(look, image, hd=True, tuning=tuning, clarity=lv, lines=ln)
            sheet.paste(im.resize(cell, Image.BILINEAR), (c * cell[0], r * (cell[1] + 22) + 22))
            d.text((c * cell[0] + 8, r * (cell[1] + 22) + 6), f"{look['name']}  clarity={lv}  lines={ln}", fill=(239, 234, 219))
    return sheet


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--update", action="store_true")
    ap.add_argument("--check", action="store_true")
    ap.add_argument("--clarity-sheet", action="store_true")
    ap.add_argument("--out", default="/tmp/lofi-golden")
    args = ap.parse_args()

    pipe = Pipeline()
    image = Image.open(INPUT).convert("RGB")
    if args.clarity_sheet:
        out = GOLDEN_DIR / "clarity.jpg"
        clarity_sheet(pipe, image).save(out, quality=88)
        print("wrote", out)
        return
    looks = [json.loads(p.read_text()) for p in sorted((ASSETS / "looks").glob("*.json"))]
    out_dir = GOLDEN_DIR if args.update else pathlib.Path(args.out)
    out_dir.mkdir(parents=True, exist_ok=True)
    failures, items = [], []
    for look in looks:
        im = pipe.render_look(look, image)
        items.append((look["name"], im))
        if args.check:
            golden = GOLDEN_DIR / f"{look['id']}.jpg"
            if not golden.exists():
                failures.append(f"{look['id']}: missing golden {golden.name}")
                continue
            gi = Image.open(golden).convert("RGB")
            if gi.size != im.size:
                failures.append(f"{look['id']}: size {im.size} != golden {gi.size}")
                continue
            small = (im.size[0] // 4, im.size[1] // 4)
            g = np.asarray(gi.resize(small, Image.BOX), dtype=np.float32)
            r = np.asarray(im.resize(small, Image.BOX), dtype=np.float32)
            mae = float(np.abs(g - r).mean())
            print(f"{look['id']:<20} MAE {mae:5.2f}")
            if mae > MAE_TOLERANCE:
                failures.append(f"{look['id']}: MAE {mae:.2f} > {MAE_TOLERANCE}")
                im.save(out_dir / f"{look['id']}.actual.png")
        else:
            im.save(out_dir / f"{look['id']}.jpg", quality=92)
            print("wrote", out_dir / f"{look['id']}.jpg", im.size)
    sheet = contact_sheet(items)
    if not args.check:
        sheet.save(out_dir / "sheet.jpg", quality=90)
    if failures:
        print("\nGOLDEN CHECK FAILED:\n  " + "\n  ".join(failures))
        sys.exit(1)


if __name__ == "__main__":
    main()
