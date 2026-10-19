package app.lofi.cam

import android.content.Context
import android.util.Size
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
 * to the screen directly, the [LofiRenderer] does.
 */
class CameraController(private val context: Context, private val renderer: LofiRenderer) {
    private var ownerRef: WeakReference<LifecycleOwner>? = null
    private var front = false
    private var mirror = false

    @Volatile var onError: ((String) -> Unit)? = null

    fun bind(owner: LifecycleOwner, front: Boolean, mirror: Boolean) {
        ownerRef = WeakReference(owner)
        this.front = front
        this.mirror = mirror
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
                provider.bindToLifecycle(owner, selector, preview)
                LofiLog.i("camera", "bound ${if (front) "front" else "back"} camera")
            } catch (t: Throwable) {
                LofiLog.e("camera", "bind failed", t)
                onError?.invoke("Camera error: ${t.message}")
            }
        }, executor)
    }

    /** Re-bind after the GL context was recreated (the old camera surface is dead). */
    fun rebind() {
        ownerRef?.get()?.let { bind(it, front, mirror) }
    }

    /** Left-right flip of the image (used for the front camera); applied per frame, no rebind needed. */
    fun setMirror(m: Boolean) {
        mirror = m
        renderer.setMirror(m)
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
