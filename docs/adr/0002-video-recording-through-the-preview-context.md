# ADR 0002: Video recording renders through the preview's own EGL context

Status: accepted, Phase 2. Needs on-device verification.

## Context

Video must contain exactly the processed picture, at a size independent of the screen, with sound.

## Decision

* The preview `GLSurfaceView` uses `RecordableConfigChooser` (RGBA8888, ES3, `EGL_RECORDABLE_ANDROID`).
* `VideoRecorder` owns an H.264 `MediaCodec` (surface input) and an optional AAC encoder fed by `AudioRecord`;
  both write into one `MediaMuxer` on a MediaStore file descriptor (`Movies/LoFiCam`).
* After each processed camera frame, `LofiRenderer` makes an `EGLSurface` wrapping the codec's input surface current
  (same context, so every texture and program is reused), draws the *present* pass at the video size, sets the
  presentation time from the camera frame timestamp, swaps, and makes the preview surface current again.
* Audio runs on its own thread. It is filtered by `AudioChain` (the "worn" microphone sound) according to the clarity
  level: WEAR = lo-fi, CLEAR = light, HD / VERY CLEAR = untouched.
* The muxer starts when all expected tracks have a format; early samples are queued. If the microphone fails the muxer
  starts with video only.
* Video size: 720 wide (HD and VERY CLEAR: 1080 wide, falling back to 720 if the encoder refuses).

## Consequences

* No second GL context and no pixel read-back: recording costs one extra draw call per frame.
* A/V sync is by wall clock offsets (first video frame vs. first audio sample); expect tens of milliseconds of error.
* The recording stops when the app is paused (the GL thread stops too); the file is finalised first.
* Flash while recording is the camera torch (rear camera only).

## Alternatives considered

* CameraX `VideoCapture` + effects: cannot take our look pipeline without the effects framework (see ADR 0001).
* Separate recording EGL context with shared textures: more state to keep in sync for no gain here.
