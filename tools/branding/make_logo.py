import os
import cairosvg

OUT = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "..", "docs", "branding")
os.makedirs(OUT, exist_ok=True)

# Palette
BLUE_C, BLUE_E = "#1a2ad0", "#0a1070"   # VCR "no signal" blue
PUTTY, PUTTY_HI, PUTTY_LO = "#dcd6c2", "#efeadb", "#b9b29b"
DARK, GRAY = "#161728", "#8d8a80"
RED, CYAN, MAG, CREAM = "#ff2d3d", "#19e3ff", "#ff3da6", "#efeadb"
BARS = ["#e8e8e8", "#e8e000", "#00d8d8", "#00c800", "#d800d8", "#e00000", "#2a3cff"]

NAME = "LOFICAM"  # placeholder app name

FONT = {
    "L": ["X....", "X....", "X....", "X....", "X....", "X....", "XXXXX"],
    "O": [".XXX.", "X...X", "X...X", "X...X", "X...X", "X...X", ".XXX."],
    "F": ["XXXXX", "X....", "X....", "XXXX.", "X....", "X....", "X...."],
    "I": ["XXXXX", "..X..", "..X..", "..X..", "..X..", "..X..", "XXXXX"],
    "C": [".XXXX", "X....", "X....", "X....", "X....", "X....", ".XXXX"],
    "A": [".XXX.", "X...X", "X...X", "XXXXX", "X...X", "X...X", "X...X"],
    "M": ["X...X", "XX.XX", "X.X.X", "X.X.X", "X...X", "X...X", "X...X"],
}


def scanlines(w, h, step=10, th=4, op=0.15):
    return "".join(
        f'<rect x="0" y="{y}" width="{w}" height="{th}" fill="#000" opacity="{op}"/>'
        for y in range(0, h, step)
    )


def cam(flat=None):
    """Side-view shoulder camcorder, facing left. flat=<color> draws a plain silhouette (for RGB ghosts)."""
    P = []
    st = "" if flat else f'stroke="{DARK}" stroke-width="10" stroke-linejoin="round"'

    def f(color):
        return flat or color

    # top handle (arch)
    arch = "M330 402 V372 a30 30 0 0 1 30 -30 H560 a30 30 0 0 1 30 30 V402"
    if not flat:
        P.append(f'<path d="{arch}" fill="none" stroke="{DARK}" stroke-width="42" stroke-linecap="round"/>')
    P.append(f'<path d="{arch}" fill="none" stroke="{f(PUTTY_LO)}" stroke-width="24" stroke-linecap="round"/>')
    # viewfinder + eyecup
    P.append(f'<rect x="612" y="318" width="140" height="86" rx="18" fill="{f(PUTTY_LO)}" {st}/>')
    P.append(f'<rect x="752" y="334" width="52" height="54" rx="12" fill="{f(DARK)}" {st}/>')
    # battery pack
    P.append(f'<rect x="738" y="424" width="96" height="250" rx="24" fill="{f(GRAY)}" {st}/>')
    if not flat:
        for y in (470, 500, 530):
            P.append(f'<rect x="754" y="{y}" width="64" height="8" rx="4" fill="{DARK}" opacity="0.45"/>')
    # lens barrel + hood
    P.append(f'<rect x="170" y="450" width="118" height="190" rx="20" fill="{f(PUTTY)}" {st}/>')
    if not flat:
        P.append(f'<rect x="198" y="456" width="10" height="178" fill="{PUTTY_LO}"/>')
        P.append(f'<rect x="228" y="456" width="10" height="178" fill="{PUTTY_LO}"/>')
    P.append(f'<rect x="120" y="425" width="62" height="240" rx="16" fill="{f(DARK)}" {st}/>')
    if not flat:
        P.append(f'<rect x="136" y="452" width="10" height="64" rx="5" fill="{CYAN}"/>')
    # body
    P.append(f'<rect x="270" y="400" width="470" height="290" rx="36" fill="{f(PUTTY)}" {st}/>')
    if not flat:
        P.append(f'<rect x="284" y="414" width="442" height="20" rx="10" fill="{PUTTY_HI}"/>')
        P.append(f'<rect x="284" y="652" width="442" height="26" rx="13" fill="{PUTTY_LO}"/>')
        # tape window with two reels
        P.append(f'<rect x="330" y="452" width="250" height="140" rx="20" fill="{DARK}"/>')
        import math
        for cx in (400, 510):
            P.append(f'<circle cx="{cx}" cy="522" r="40" fill="none" stroke="{CREAM}" stroke-width="8"/>')
            P.append(f'<circle cx="{cx}" cy="522" r="11" fill="{CREAM}"/>')
            for a in (90, 210, 330):
                x2 = cx + 28 * math.cos(math.radians(a))
                y2 = 522 + 28 * math.sin(math.radians(a))
                P.append(f'<line x1="{cx}" y1="522" x2="{x2:.1f}" y2="{y2:.1f}" stroke="{CREAM}" stroke-width="6" stroke-linecap="round"/>')
        P.append(f'<path d="M400 562 Q455 596 510 562" fill="none" stroke="{PUTTY_LO}" stroke-width="5"/>')
        # buttons
        P.append(f'<rect x="620" y="622" width="84" height="22" rx="11" fill="{DARK}" opacity="0.6"/>')
        # REC lamp
        P.append(f'<circle cx="672" cy="455" r="38" fill="{RED}" opacity="0.28"/>')
        P.append(f'<circle cx="672" cy="455" r="21" fill="{RED}" stroke="{DARK}" stroke-width="6"/>')
        P.append(f'<circle cx="665" cy="448" r="6" fill="#ffb3b9"/>')
    return "".join(P)


def icon_body(uid="i", bg=True):
    """Full 1024x1024 icon contents (no <svg> wrapper)."""
    band_clip = (
        f'<clipPath id="{uid}-keep"><rect x="-300" y="0" width="1600" height="402"/>'
        f'<rect x="-300" y="414" width="1600" height="184"/>'
        f'<rect x="-300" y="612" width="1600" height="500"/></clipPath>'
        f'<clipPath id="{uid}-b1"><rect x="-300" y="402" width="1600" height="12"/></clipPath>'
        f'<clipPath id="{uid}-b2"><rect x="-300" y="598" width="1600" height="14"/></clipPath>'
    )
    defs = (
        f'<defs><radialGradient id="{uid}-bg" cx="50%" cy="42%" r="75%">'
        f'<stop offset="0" stop-color="{BLUE_C}"/><stop offset="1" stop-color="{BLUE_E}"/></radialGradient>'
        f'<clipPath id="{uid}-round"><rect width="1024" height="1024" rx="230"/></clipPath>'
        f'<clipPath id="{uid}-bars"><rect x="218" y="748" width="588" height="54" rx="10"/></clipPath>'
        f"{band_clip}</defs>"
    )
    T = "translate(512,440) scale(1.08) translate(-477,-504)"
    camera = (
        f'<g transform="{T}">'
        f'<g transform="translate(18,0)">{cam(MAG)}</g>'
        f'<g transform="translate(-18,0)">{cam(CYAN)}</g>'
        f'<g clip-path="url(#{uid}-keep)">{cam()}</g>'
        f'<g clip-path="url(#{uid}-b1)"><g transform="translate(22,0)">{cam()}</g></g>'
        f'<g clip-path="url(#{uid}-b2)"><g transform="translate(-16,0)">{cam()}</g></g>'
        f"</g>"
    )
    bars = f'<g clip-path="url(#{uid}-bars)">' + "".join(
        f'<rect x="{218 + i * 84}" y="748" width="84" height="54" fill="{c}"/>' for i, c in enumerate(BARS)
    ) + "</g>"
    if not bg:
        return f"{defs}{camera}{bars}"
    return (
        f"{defs}"
        f'<g clip-path="url(#{uid}-round)">'
        f'<rect width="1024" height="1024" fill="url(#{uid}-bg)"/>'
        f"{camera}{bars}{scanlines(1024, 1024)}</g>"
    )


def pixel_text(text, x, y, p, color, dx=0, pad=3, space=1):
    out, cx = [], x
    for ch in text:
        for r, row in enumerate(FONT[ch]):
            for c, v in enumerate(row):
                if v == "X":
                    out.append(f'<rect x="{cx + c * p + dx}" y="{y + r * p}" width="{p - pad}" height="{p - pad}" fill="{color}"/>')
        cx += (5 + space) * p
    return "".join(out)


# ---- icon ----
icon_svg = (
    '<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 1024 1024" width="1024" height="1024">'
    + icon_body("i")
    + "</svg>"
)
open(f"{OUT}/logo-icon.svg", "w").write(icon_svg)

# ---- lockup ----
W, H = 1800, 560
p = 24
text_w = len(NAME) * 6 * p - p
tx, ty = 610, 280 - (7 * p) // 2
dot_x = tx + text_w + 64
lockup_svg = (
    f'<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 {W} {H}" width="{W}" height="{H}">'
    f'<defs><radialGradient id="l-bg" cx="30%" cy="40%" r="90%"><stop offset="0" stop-color="{BLUE_C}"/>'
    f'<stop offset="1" stop-color="{BLUE_E}"/></radialGradient>'
    f'<clipPath id="l-round"><rect width="{W}" height="{H}" rx="64"/></clipPath></defs>'
    f'<g clip-path="url(#l-round)"><rect width="{W}" height="{H}" fill="url(#l-bg)"/>'
    f'<g transform="translate(30,30) scale({500 / 1024})">{icon_body("k", bg=False)}</g>'
    f"{pixel_text(NAME, tx, ty, p, MAG, dx=7)}{pixel_text(NAME, tx, ty, p, CYAN, dx=-7)}{pixel_text(NAME, tx, ty, p, CREAM)}"
    f'<circle cx="{dot_x}" cy="280" r="44" fill="{RED}" opacity="0.28"/>'
    f'<circle cx="{dot_x}" cy="280" r="26" fill="{RED}"/>'
    f"{scanlines(W, H)}</g></svg>"
)
open(f"{OUT}/logo-lockup.svg", "w").write(lockup_svg)

# ---- PNG previews ----
cairosvg.svg2png(bytestring=icon_svg.encode(), write_to=f"{OUT}/logo-icon-1024.png", output_width=1024, output_height=1024)
cairosvg.svg2png(bytestring=lockup_svg.encode(), write_to=f"{OUT}/logo-lockup.png", output_width=W, output_height=H)
print("done")
