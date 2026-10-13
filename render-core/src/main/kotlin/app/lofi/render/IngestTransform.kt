package app.lofi.render

import kotlin.math.abs

/**
 * Clockwise rotation (0/90/180/270) that `SurfaceTexture.getTransformMatrix()` applies to the sensor buffer.
 *
 * (m[4], m[5]) is where "display up" lands in texture space; "image up" in that space is -v because buffer
 * row 0 is the top row. Unrotated: (0,-1). Buffer rotated 90 CW: display up is the buffer's left edge, (-1,0).
 */
internal fun matrixRotationDegrees(m: FloatArray): Int {
    val dx = m[4]
    val dy = m[5]
    return if (abs(dy) >= abs(dx)) {
        if (dy < 0f) 0 else 180
    } else {
        if (dx < 0f) 90 else 270
    }
}

/** Everything the ingest shader needs besides the texture matrix itself. */
internal class IngestParams(
    /** Remaining clockwise rotation (beyond what the matrix already applies), as cos / sin for `uRot`. */
    val rotCos: Float,
    val rotSin: Float,
    /** Centre-crop scale in displayed space for `uCrop`. */
    val cropX: Float,
    val cropY: Float,
    val remainingDegrees: Int,
    val matrixDegrees: Int,
)

/**
 * CameraX's `TransformationInfo.rotationDegrees` is the TOTAL clockwise rotation from the sensor buffer to the
 * target rotation, while a camera-connected SurfaceTexture matrix already contains the camera orientation
 * (CameraX docs: "for TextureView, the only additional rotation needed is the display rotation"; the CameraX
 * OpenGLRenderer test app subtracts the matrix rotation too). So we apply only the difference.
 */
internal object IngestTransform {
    fun compute(
        inputW: Int,
        inputH: Int,
        vrW: Int,
        vrH: Int,
        rotationDegrees: Int,
        matrix: FloatArray,
    ): IngestParams {
        val matrixRot = matrixRotationDegrees(matrix)
        // Unknown yet (negative): assume the matrix alone makes the image upright.
        val total = if (rotationDegrees < 0) matrixRot else Math.floorMod((rotationDegrees / 90) * 90, 360)
        val remaining = Math.floorMod(total - matrixRot, 360)

        val matrixSwaps = matrixRot == 90 || matrixRot == 270
        val natW = if (matrixSwaps) inputH else inputW
        val natH = if (matrixSwaps) inputW else inputH
        val remainingSwaps = remaining == 90 || remaining == 270
        val dispW = if (remainingSwaps) natH else natW
        val dispH = if (remainingSwaps) natW else natH

        val sourceAspect = dispW.toFloat() / dispH
        val targetAspect = vrW.toFloat() / vrH
        val cropX = if (sourceAspect > targetAspect) targetAspect / sourceAspect else 1f
        val cropY = if (sourceAspect > targetAspect) 1f else sourceAspect / targetAspect

        val (c, s) = when (remaining) {
            90 -> 0f to 1f
            180 -> -1f to 0f
            270 -> 0f to -1f
            else -> 1f to 0f
        }
        return IngestParams(c, s, cropX, cropY, remaining, matrixRot)
    }
}
