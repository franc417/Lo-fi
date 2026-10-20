// Stage 1 ("analog"): jitter, lens, tape chroma, tone, bloom, vignette, night vision,
// overlay burn-in, ghosting. Output is also the temporal history (so grain never feeds back).
in vec2 vUv;
out vec4 fragColor;

uniform sampler2D uTex;      // ingest, virtual resolution
uniform sampler2D uPrev;     // previous stage-1 output
uniform sampler2D uOverlay;  // HUD / timestamp (premultiplied, top-down)
uniform vec2 uSize;
uniform int uFrame;
uniform float uHistoryValid;
uniform float uOverlayAmount;

uniform float uLensSoftness;
uniform float uLensVignette;
uniform float uLensChromaAb;
uniform float uLensBloom;
uniform float uLensSharpen;
uniform float uLensStretchX;
uniform float uLensSmear;
uniform float uLensSmearLength;
uniform float uLensSmearThreshold;
uniform float uLensSmearTaps;
uniform float uLensLetterbox;
uniform float uToneExposure;
uniform float uToneContrast;
uniform float uToneBlackCrush;
uniform float uToneWhitePoint;
uniform float uToneSaturation;
uniform float uToneGamma;
uniform float uToneExposureDrift;
uniform float uToneNegative;
uniform float uToneSolarize;
uniform float uFilmFlicker;
uniform float uFilmBounce;
uniform float uTapeHold;
uniform float uTapeFieldOffset;
uniform vec3 uNightShadowColor;
uniform float uNightSmooth;
uniform float uNightSmoothSize;
uniform float uNightCurveToe;
uniform float uNightCurveShoulder;
uniform float uNightFluorLow;
uniform float uNightFluorHigh;
uniform float uNightFluorGain;
uniform vec3 uNightFluorColor;
uniform float uNightHalation;
uniform float uNightHalationSize;
uniform float uNightCurve;
uniform vec3 uToneShadowTint;
uniform vec3 uToneHighlightTint;
uniform float uNightAmount;
uniform vec3 uNightColor;
uniform float uNightGain;
uniform vec3 uNightIrWeights;
uniform float uNightHotspot;
uniform float uNightHotspotSize;
uniform float uTapeChromaBleed;
uniform float uTapeGhost;
uniform float uTapeJitter;
uniform float uTapeInterlace;

vec3 fetch(vec2 uv) { return texture(uTex, uv).rgb; }

// 3x3 gaussian-ish blur with a radius in virtual pixels.
vec3 blur3(vec2 uv, float r) {
    if (r < 0.01) return fetch(uv);
    vec2 t = r / uSize;
    vec3 c = fetch(uv) * 4.0;
    c += (fetch(uv + vec2(t.x, 0.0)) + fetch(uv - vec2(t.x, 0.0)) +
          fetch(uv + vec2(0.0, t.y)) + fetch(uv - vec2(0.0, t.y))) * 2.0;
    c += fetch(uv + t) + fetch(uv - t) + fetch(uv + vec2(t.x, -t.y)) + fetch(uv + vec2(-t.x, t.y));
    return c / 16.0;
}

void main() {
    vec2 uv = vUv;
    float row = floor(uv.y * uSize.y);

    // Strobe ("flash" effect): keep showing the previous frame and only refresh every N frames.
    if (uTapeHold > 1.5 && uHistoryValid > 0.5 && mod(float(uFrame), floor(uTapeHold + 0.5)) > 0.5) {
        fragColor = vec4(texture(uPrev, uv).rgb, 1.0);
        return;
    }

    // Tape time-base jitter: per row, per frame, with occasional torn bands.
    float rnd = hash12(vec2(row, 7.0), uFrame);
    float jit = (rnd - 0.5) * 2.0 * uTapeJitter / uSize.x;
    float tear = hash12(vec2(floor(row / 5.0), 13.0), uFrame);
    if (tear > 0.985) jit += (rnd - 0.5) * uTapeJitter * 8.0 / uSize.x;
    vec2 uvj = vec2(uv.x + jit, uv.y);
    float oddRow = mod(row, 2.0);
    uvj.x += oddRow * (hash12(vec2(11.0, 11.0), uFrame) - 0.5) * 2.0 * uTapeFieldOffset / uSize.x;   // field timing judder
    uvj.y += (hash12(vec2(3.0, 3.0), uFrame) - 0.5) * 4.0 * uFilmBounce / uSize.y;   // projector gate bounce
    uvj.x = (uvj.x - 0.5) / uLensStretchX + 0.5;                                    // slim / stretch
    float inside = step(0.0, uvj.x) * step(uvj.x, 1.0);

    // Lens: softness + radial chromatic aberration.
    vec2 ca = (uv - 0.5) * 2.0 * uLensChromaAb / uSize;
    vec3 col;
    col.r = blur3(uvj + ca, uLensSoftness).r;
    col.g = blur3(uvj, uLensSoftness).g;
    col.b = blur3(uvj - ca, uLensSoftness).b;
    col *= inside;

    // Tape chroma: luma stays sharp, I/Q is low-passed and smeared to the right.
    if (uTapeChromaBleed > 0.01) {
        vec3 yiq = rgb2yiq(col);
        vec2 iq = vec2(0.0);
        float wsum = 0.0;
        for (int k = 0; k < 8; k++) {
            float w = 1.0 - float(k) / 8.0;
            vec2 o = vec2(-float(k) / 7.0 * uTapeChromaBleed / uSize.x, 0.0);
            iq += rgb2yiq(fetch(uvj + o)).yz * w;
            wsum += w;
        }
        yiq.yz = iq / wsum;
        col = yiq2rgb(yiq);
    }

    // Analog edge enhancement: unsharp mask, gives the bright/dark halos around edges of old camcorders.
    if (uLensSharpen > 0.0) col += uLensSharpen * (fetch(uvj) - blur3(uvj, 1.5));

    // Tone. Auto-gain "hunting": slow brightness drift, as on a CCD camcorder in low light (30 fps assumed).
    float t = float(uFrame) / 30.0;
    float drift = 1.0 + uToneExposureDrift * 0.12 * (0.6 * sin(t * 0.8) + 0.4 * sin(t * 2.1 + 1.3));
    float flick = 1.0 + uFilmFlicker * 0.3 * (hash12(vec2(5.0, 5.0), uFrame) - 0.5) * 2.0;
    col *= uToneExposure * drift * flick;
    col = (col - 0.5) * uToneContrast + 0.5;
    col = max(col - uToneBlackCrush, 0.0) / (1.0 - uToneBlackCrush);
    col /= uToneWhitePoint;
    float l = luma(col);
    col = mix(vec3(l), col, uToneSaturation);
    col = pow(clamp(col, 0.0, 1.0), vec3(1.0 / uToneGamma));
    col = mix(col, 1.0 - col, uToneNegative);                                                     // negative art
    col = mix(col, 1.0 - col, uToneSolarize * smoothstep(vec3(0.46), vec3(0.54), col));            // solarize
    l = luma(col);
    col += uToneShadowTint * pow(1.0 - l, 2.0) + uToneHighlightTint * pow(l, 2.0);

    // CCD vertical smear: pixels above the threshold (lamps, teeth, specular spots) leave a coloured trail that runs DOWN
    // the picture: up to uLensSmearTaps lookups, each one attenuated by uLensSmearLength.
    if (uLensSmear > 0.0) {
        vec3 trail = vec3(0.0);
        float w = 1.0;
        for (int k = 1; k <= 24; k++) {
            if (float(k) > uLensSmearTaps) break;
            w *= uLensSmearLength;
            vec3 src = fetch(vec2(uvj.x, uv.y + float(k) * 2.0 / uSize.y));      // the pixel k*2 rows ABOVE this one
            float b = luma(src) * uToneExposure;
            float over = clamp((b - uLensSmearThreshold) / (1.0 - uLensSmearThreshold + 0.001), 0.0, 1.0);
            trail += w * over * (src / max(luma(src), 0.05));
        }
        col += trail * uLensSmear * 0.5;
    }

    // Bloom / halation (cheap: two wide blurs, thresholded).
    if (uLensBloom > 0.0) {
        vec3 glow = blur3(uvj, 4.0) * 0.6 + blur3(uvj, 9.0) * 0.4;
        glow = max(glow * uToneExposure - 0.55, 0.0);
        col += glow * uLensBloom * 1.6;
    }

    // Vignette.
    float d = length(uv - 0.5) * 1.4142;
    col *= mix(1.0, 1.0 - smoothstep(0.35, 1.05, d), uLensVignette);

    // Night vision, 1998 NightShot with a forensic UV/IR flavour.
    //  1. IR luminance: a red-heavy channel mix (melanin absorbs little IR, so skin loses its dark contrast), lit by a
    //     camera-mounted IR lamp (gaussian hotspot). Mid tones get an edge-preserving smoothing + contrast damping so
    //     skin turns flat, matte and ghostly while edges (eyes, teeth, outlines) stay sharp.
    //  2. Tone curve: shadows crushed to charcoal green, mids lifted to pale sage.
    //  3. UV-style fluorescence: Rec.601 luminance between fluorLow and fluorHigh (whites of eyes, teeth, synthetic
    //     whites; skin is lower in green/blue so it stays out) glows mint-cyan, with a wide halation around it.
    if (uNightAmount > 0.0) {
        const vec2 DIR[8] = vec2[8](vec2(1.0, 0.0), vec2(0.7071, 0.7071), vec2(0.0, 1.0), vec2(-0.7071, 0.7071),
                                    vec2(-1.0, 0.0), vec2(-0.7071, -0.7071), vec2(0.0, -1.0), vec2(0.7071, -0.7071));
        float expo = uToneExposure * drift * flick;
        float r = length(uv - 0.5) * 2.0;
        float lit = mix(1.0, exp(-(r * r) / (2.0 * uNightHotspotSize * uNightHotspotSize)), uNightHotspot);

        float yc = dot(col, uNightIrWeights);
        float y = yc;
        if (uNightSmooth > 0.0) {
            float sum = yc;
            float wsum = 1.0;
            for (int ring = 1; ring <= 2; ring++) {
                float rad = uNightSmoothSize * float(ring);
                for (int d = 0; d < 8; d++) {
                    float yn = dot(fetch(uvj + DIR[d] * rad / uSize), uNightIrWeights) * expo;
                    float wgt = exp(-((yn - yc) * (yn - yc)) / 0.02);          // similar brightness only: edges survive
                    sum += wgt * yn;
                    wsum += wgt;
                }
            }
            float mid = smoothstep(0.15, 0.40, yc) * (1.0 - smoothstep(0.70, 0.90, yc));
            float smoothed = sum / wsum;
            smoothed = mix(smoothed, 0.5, 0.2);                                // local contrast damping
            y = mix(yc, smoothed, mid * uNightSmooth);
        }
        y = clamp(y * uNightGain * lit, 0.0, 1.0);
        y = mix(y, pow(smoothstep(uNightCurveToe, max(uNightCurveShoulder, uNightCurveToe + 0.05), y), 0.85), uNightCurve);
        vec3 base = mix(uNightShadowColor, uNightColor, y);

        float lo = uNightFluorLow;
        float hi = max(uNightFluorHigh, lo + 0.01);
        float f = smoothstep(lo, hi, luma(col)) * mix(1.0, lit, 0.5 * uNightHotspot);
        float halo = 0.0;
        if (uNightHalation > 0.0) {
            for (int ring = 0; ring < 2; ring++) {
                float rad = uNightHalationSize * (0.5 + 0.7 * float(ring));
                for (int d = 0; d < 8; d++) {
                    halo += smoothstep(lo, hi, luma(fetch(uvj + DIR[d] * rad / uSize) * expo));
                }
            }
            halo /= 16.0;
        }
        vec3 night = base + uNightFluorColor * (f + halo * uNightHalation) * uNightFluorGain;
        // soft shoulder: the hottest highlights stay minty instead of clipping to flat white
        vec3 over = max(night - 0.75, 0.0);
        night = min(night, vec3(0.75)) + 0.25 * (1.0 - exp(-over / 0.25));
        col = mix(col, night, uNightAmount);
    }

    // Cine letterbox bars (the HUD below is drawn over them).
    if (uv.y < uLensLetterbox || uv.y > 1.0 - uLensLetterbox) col = vec3(0.0);

    // Burned-in overlay (premultiplied alpha).
    vec4 ov = texture(uOverlay, vec2(uv.x, 1.0 - uv.y));
    col = col * (1.0 - ov.a * uOverlayAmount) + ov.rgb * uOverlayAmount;

    // Ghosting + interlace comb: blend with the previous frame (odd rows more).
    vec3 prev = texture(uPrev, uv).rgb;
    float odd = mod(row, 2.0);
    float g = clamp(uTapeGhost + uTapeInterlace * 0.5 * odd, 0.0, 0.92) * uHistoryValid;
    col = mix(col, prev, g);

    fragColor = vec4(clamp(col, 0.0, 1.0), 1.0);
}
