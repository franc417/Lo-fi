package app.lofi.cam

import android.content.Context
import android.util.Size
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.Preview
import androidx.camera.core.SurfaceRequest
import androidx.camera.core.resolutionselector.AspectRatioStrategy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import app.lofi.render.LofiLog
import app.lofi.render.LofiRenderer
import java.lang.ref.WeakReference
import java.util.concurrent.Executor

/**
 * Binds a CameraX Preview whose surface is our own GL input surface (see ADR 0001): the camera never draws
 * to the screen directly, the [LofiRenderer] does. Also owns zoom and the torch.
 */
class CameraController(private val context: Context, private val renderer: LofiRenderer) {
    private var ownerRef: WeakReference<LifecycleOwner>? = null
    private var front = false
    private var mirror = false
    private var zoom = 0f
    private var camera: Camera? = null

    @Volatile var onError: ((String) -> Unit)? = null
    @Volatile var onZoomRatio: ((Float) -> Unit)? = null

    /** True if the bound camera has a flash unit (most back cameras; almost no front cameras). */
    val hasFlash: Boolean get() = camera?.cameraInfo?.hasFlashUnit() == true

    fun bind(owner: LifecycleOwner, front: Boolean, mirror: Boolean, zoom: Float) {
        ownerRef = WeakReference(owner)
        this.front = front
        this.mirror = mirror
        this.zoom = zoom
        val executor = ContextCompat.getMainExecutor(context)
        val future = ProcessCameraProvider.getInstance(context)
        future.addListener({
            try {
                val provider = future.get()
                val selector = if (front) CameraSelector.DEFAULT_FRONT_CAMERA else CameraSelector.DEFAULT_BACK_CAMERA
                if (!provider.hasCamera(selector)) {
                    onError?.invoke(if (front) "No front camera" else "No back camera")
                    return@addListener
                }
                val resolution = ResolutionSelector.Builder()
                    .setAspectRatioStrategy(AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY)
                    .setResolutionStrategy(
                        ResolutionStrategy(Size(1280, 960), ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER),
                    )
                    .build()
                val preview = Preview.Builder().setResolutionSelector(resolution).build()
                renderer.setMirror(mirror)
                preview.setSurfaceProvider(executor) { request -> onSurfaceRequested(request, executor) }
                provider.unbindAll()
                val bound = provider.bindToLifecycle(owner, selector, preview)
                camera = bound
                bound.cameraControl.setLinearZoom(this.zoom.coerceIn(0f, 1f))
                bound.cameraInfo.zoomState.observe(owner) { z -> onZoomRatio?.invoke(z.zoomRatio) }
                LofiLog.i("camera", "bound ${if (front) "front" else "back"} camera, flash=${bound.cameraInfo.hasFlashUnit()}")
            } catch (t: Throwable) {
                LofiLog.e("camera", "bind failed", t)
                onError?.invoke("Camera error: ${t.message}")
            }
        }, executor)
    }

    /** Re-bind after the GL context was recreated (the old camera surface is dead). */
    fun rebind() {
        ownerRef?.get()?.let { bind(it, front, mirror, zoom) }
    }

    /** Left-right flip of the image (used for the front camera); applied per frame, no rebind needed. */
    fun setMirror(m: Boolean) {
        mirror = m
        renderer.setMirror(m)
    }

    /** 0 = widest, 1 = maximum zoom. Applied immediately; whatever value was last set stays. */
    fun setZoom(linear: Float) {
        zoom = linear.coerceIn(0f, 1f)
        camera?.cameraControl?.setLinearZoom(zoom)
    }

    fun setTorch(on: Boolean) {
        camera?.cameraControl?.enableTorch(on)
    }

    private fun onSurfaceRequested(request: SurfaceRequest, executor: Executor) {
        val size = request.resolution
        LofiLog.i("camera", "surface requested ${size.width}x${size.height}")
        request.setTransformationInfoListener(executor) { info -> renderer.setRotation(info.rotationDegrees) }
        renderer.requestInputSurface(size.width, size.height) { surface ->
            request.provideSurface(surface, executor) { result ->
                LofiLog.i("camera", "surface done, result=${result.resultCode}")
                renderer.releaseInputSurface(surface)
            }
        }
    }
}
