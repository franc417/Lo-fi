# LoFiCam: design and architecture

Android only. This is the repo copy of the design doc, updated for what is actually built. Decisions that
deviate from the original plan are recorded in [docs/adr](adr).

## Goals

A retro camera app that applies authentic lo-fi looks live while shooting photos and (Phase 2) video, plus an
import mode that applies the same looks to existing media.

Success criteria: 30 fps live preview on a mid-range phone; preview, photo and video match; recorded files always
play; every failure is logged with enough detail to reproduce it.

Out of scope for v1: iOS, accounts or cloud, social feed, timeline editing, AI upscaling.

## Decisions

| Area | Choice | Note |
|---|---|---|
| Language / UI | Kotlin, Jetpack Compose | |
| Camera | CameraX `Preview` into our own GL surface | [ADR 0001](adr/0001-camera-feeds-our-own-gl-surface.md) |
| Rendering | OpenGL ES 3.0, own pipeline, GLSL shared with the desktop harness | |
| Looks | JSON data, fixed parameter registry, no user shader code | |
| Video (Phase 2) | MediaCodec Surface input + MediaMuxer; audio recorded clean, lo-fi DSP applied after | |
| Min Android | API 29 | scoped storage, no storage permission |
| Modules | `app`, `render-core` (shaders, looks, parser, logging live here for now) | split further when it hurts |

## Render pipeline

Virtual resolution: every look defines one (e.g. 480x360). The ingest pass crops and downsamples the camera frame
to it, all effects run at that size, and the present pass upscales (nearest or linear, per look) to the screen or
export size. Parameters are in virtual pixels, so output is identical at any real size.

| Pass | Does | State |
|---|---|---|
| ingest | rotate, mirror, centre-crop, downscale | |
| stage 1 | jitter, softness, chromatic aberration, chroma bleed (YIQ), tone, bloom, vignette, night vision, HUD burn-in, ghost / interlace | writes the temporal history |
| stage 2 | head-switch band, dropouts, grain, scanlines, quantise + Bayer dither, palette | |
| present | upscale to screen / export | |

Grain lives in stage 2 on purpose: the history is the stage-1 output, so noise never feeds back into ghosting.
Hashes use an integer PCG hash, not `fract(sin())`, so results match across GPUs and the desktop harness.

Not built yet (planned): true multi-pass bloom, generated shader fusion, compression-artifact simulation.

## Photo

Instant capture: re-render the final frame at `virtualResolution x exportScale` and read it back. PNG for
nearest-upscale (dither) looks, JPEG 92 otherwise; saved to `Pictures/LoFiCam`. Because the history buffer is live,
ghosting looks give real motion trails in photos.

## Video (Phase 2)

Same graph, second EGL surface from a MediaCodec input surface, camera timestamps as presentation time.
Export profiles: *Authentic* (virtual resolution, about 0.8 Mbps) and *Share* (720p-class, 4 to 6 Mbps; grain
needs bitrate). Audio: record clean, then band-limit, resample, hiss, wow/flutter, AGC pumping, and remux.
Safe finalize on interruption. Optional generation-loss export (re-encode N times).

## UI

Camera screen: live preview in the look's aspect ratio, a strip of look chips, a Wear slider, shutter, flip.
Planned: cassette-style look cards with live thumbnails, SP/LP tape-speed toggle, camcorder HUD toggle,
look editor, library with re-look, hold-to-compare, fake-date picker.

## Logging, errors, testing

* `LofiLog`: logcat plus a rolling JSON-lines file; perf line every 150 frames; uncaught exceptions logged.
* Errors: camera, GL (shader compile/link, incomplete framebuffer), render, save. Shown on screen and logged.
* Tests: JUnit for logic, headless golden renders for shaders (see README), on-device checks for the rest.

## Roadmap

* **Phase 0 (device gate), do next on a phone:** install the CI APK and check (1) preview is upright on the back
  and front cameras, (2) mirror is right for the front camera, (3) 30 fps with each look (`perf` log lines),
  (4) captured photo matches the preview, (5) no GL errors in `lofi-log.jsonl`.
* **Phase 1, photo MVP:** done in code, pending Phase 0.
* **Phase 2, video:** recording with looks, export profiles, audio chain, pause/resume.
* **Phase 3:** import and re-look, look editor, preset sharing.
* **Phase 4:** quality tiers and thermal handling, onboarding, accessibility, log export, store prep.

## Risks and open questions

Device quirks (rotation and mirroring values from CameraX, GL context loss, codec support); thermal throttling
in long recordings; social platforms re-compress and crush grain; trademark (generic look names, no PXLCAM
branding); store policy for camera and microphone. Open: app name, minimum Android version, keep clean originals
by default, main share target, free or paid.
