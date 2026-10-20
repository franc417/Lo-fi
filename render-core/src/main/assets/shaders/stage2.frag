// Stage 2 ("digital"): head-switch noise, dropouts, grain, scanlines, quantise + ordered dither, palette.
in vec2 vUv;
out vec4 fragColor;

uniform sampler2D uTex;      // stage-1 output
uniform vec2 uSize;
uniform int uFrame;

uniform float uTapeDropouts;
uniform float uTapeHeadSwitch;
uniform float uFilmScratches;
uniform float uFilmDust;
uniform float uDigitalGrain;
uniform float uDigitalScanlines;
uniform float uDigitalLevels;
uniform float uDigitalDither;
uniform float uDigitalPaletteAmount;
uniform vec3 uDigitalPaletteDark;
uniform vec3 uDigitalPaletteLight;

float bayer4(vec2 p) {
    const int m[16] = int[16](0, 8, 2, 10, 12, 4, 14, 6, 3, 11, 1, 9, 15, 7, 13, 5);
    int x = int(mod(p.x, 4.0));
    int y = int(mod(p.y, 4.0));
    return (float(m[x + y * 4]) + 0.5) / 16.0;
}

void main() {
    vec2 uv = vUv;
    vec2 px = floor(uv * uSize);
    float row = px.y;

    // Head-switching band: the bottom rows tear sideways and fill with noise.
    vec2 uvs = uv;
    float band = 0.0;
    if (uTapeHeadSwitch > 0.0 && row < 8.0) {
        band = 1.0 - row / 8.0;
        uvs.x += (hash12(vec2(row, 3.0), uFrame) - 0.5) * uTapeHeadSwitch * 0.18 * band;
    }
    vec3 col = texture(uTex, uvs).rgb;
    if (band > 0.0) {
        float n = hash12(vec2(px.x, row + 100.0), uFrame);
        col = mix(col, vec3(n), uTapeHeadSwitch * 0.55 * band);
    }

    // Dropouts: short bright horizontal streaks that fade out.
    float dr = hash12(vec2(row, 29.0), uFrame);
    if (dr > 1.0 - uTapeDropouts * 0.02) {
        float start = hash12(vec2(row, 31.0), uFrame);
        float len = 0.08 + 0.25 * hash12(vec2(row, 37.0), uFrame);
        if (uv.x > start && uv.x < start + len) {
            float f = 1.0 - (uv.x - start) / len;
            col = mix(col, vec3(1.0), 0.2 + 0.7 * f);
        }
    }

    // Old-film scratches (vertical lines that change every other frame) and dust specks.
    if (uFilmScratches > 0.0) {
        int fseed = uFrame / 2;
        for (int i = 0; i < 3; i++) {
            float sx = hash12(vec2(float(i), 41.0), fseed);
            float on = hash12(vec2(float(i), 43.0), fseed);
            if (on < uFilmScratches * 0.5 && abs(uv.x - sx) < 0.7 / uSize.x) col = mix(col, vec3(0.92), 0.55);
        }
    }
    if (uFilmDust > 0.0) {
        vec2 cell = floor(px / 3.0);
        if (hash12(cell, uFrame) > 1.0 - uFilmDust * 0.006) col = mix(col, vec3(0.05), 0.7);
        if (hash12(cell + vec2(7.0, 3.0), uFrame) > 1.0 - uFilmDust * 0.003) col = mix(col, vec3(0.9), 0.7);
    }

    // Grain: per pixel, per frame, stronger in the shadows.
    float n = hash12(px, uFrame * 7 + 1) - 0.5;
    col += n * uDigitalGrain * (0.55 + 0.9 * (1.0 - luma(col)));

    // Scanlines at virtual resolution.
    col *= 1.0 - uDigitalScanlines * 0.45 * step(0.5, fract(row * 0.5));

    // Quantise (+ ordered dither) and optional two-colour palette.
    float lv = max(uDigitalLevels - 1.0, 0.0);
    float t = bayer4(px) - 0.5;
    if (uDigitalPaletteAmount > 0.01) {
        float x = luma(col);
        if (lv > 0.5) x = clamp(floor(x * lv + 0.5 + t * uDigitalDither) / lv, 0.0, 1.0);
        col = mix(col, mix(uDigitalPaletteDark, uDigitalPaletteLight, x), uDigitalPaletteAmount);
    } else if (lv > 0.5) {
        col = clamp(floor(col * lv + 0.5 + t * uDigitalDither) / lv, 0.0, 1.0);
    }

    fragColor = vec4(clamp(col, 0.0, 1.0), 1.0);
}
