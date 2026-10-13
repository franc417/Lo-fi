# ADR 0001: CameraX Preview feeds our own GL surface (Route B-lite)

Status: accepted, Phase 1. Revisit at the Phase 0 device gate.

## Context

The design doc proposed trying CameraX's effects framework (`CameraEffect` + `SurfaceProcessor`, "Route A") first
and falling back to a Camera2 + custom pipeline ("Route B"). Route A's behaviour for image-capture targets and GL
context ownership depends on the CameraX version and device, and cannot be verified without hardware.

## Decision

Use CameraX **only for camera control**. `Preview` gets a `SurfaceProvider` whose surface is backed by a
`SurfaceTexture` that **we** own. The `LofiRenderer` (a `GLSurfaceView.Renderer`) samples it, runs the pipeline,
and draws to the screen. Photo capture ("Instant" mode in the design) re-renders the processed frame at export
size and reads it back; no `ImageCapture` use case is needed. Video (Phase 2) will add a second EGL surface from a
`MediaCodec` input surface to the same pipeline.

## Consequences

* Preview, photo and video share one render graph by construction (WYSIWYG).
* We own rotation, mirroring and cropping (`ingest.frag`, driven by `SurfaceRequest.TransformationInfo`).
  These are the first things to verify on a device.
* We give up CameraX's effect plumbing and must handle GL context loss ourselves (`onContextRecreated` rebinds).
* "Quality" capture (full-resolution still, then process) is not available yet; it would add `ImageCapture`.

## Alternatives considered

* Route A (effects framework): less plumbing, but unverifiable here and version-sensitive.
* Full Camera2: maximum control, much more code for no Phase 1 benefit.
