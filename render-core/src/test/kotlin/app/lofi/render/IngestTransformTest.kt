package app.lofi.render

import org.junit.Assert.assertEquals
import org.junit.Test

class IngestTransformTest {
    // Column-major 4x4 matrices as returned by SurfaceTexture.getTransformMatrix().
    private val upright = floatArrayOf(1f, 0f, 0f, 0f, 0f, -1f, 0f, 0f, 0f, 0f, 1f, 0f, 0f, 1f, 0f, 1f)          // flip V only
    private val rot90 = floatArrayOf(0f, -1f, 0f, 0f, -1f, 0f, 0f, 0f, 0f, 0f, 1f, 0f, 1f, 1f, 0f, 1f)           // buffer rotated 90 CW
    private val rot180 = floatArrayOf(-1f, 0f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 0f, 1f, 0f, 1f, 0f, 0f, 1f)
    private val rot270 = floatArrayOf(0f, 1f, 0f, 0f, 1f, 0f, 0f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 0f, 1f)

    @Test fun detectsMatrixRotation() {
        assertEquals(0, matrixRotationDegrees(upright))
        assertEquals(90, matrixRotationDegrees(rot90))
        assertEquals(180, matrixRotationDegrees(rot180))
        assertEquals(270, matrixRotationDegrees(rot270))
    }

    @Test fun matrixDirectionMatchesTheCornerMapping() {
        // rot90: display bottom-left (0,0) must land on texture (1,1) (buffer bottom-right).
        val u = rot90[0] * 0f + rot90[4] * 0f + rot90[12]
        val v = rot90[1] * 0f + rot90[5] * 0f + rot90[13]
        assertEquals(1f, u, 1e-6f)
        assertEquals(1f, v, 1e-6f)
    }

    @Test fun typicalPhoneBackCameraNeedsNoExtraRotation() {
        // 1280x960 landscape buffer, matrix already rotates 90, CameraX says total 90 -> nothing remaining.
        val p = IngestTransform.compute(1280, 960, 360, 480, 90, rot90)
        assertEquals(0, p.remainingDegrees)
        assertEquals(1f, p.cropX, 1e-5f)   // displayed 960x1280 (3:4) == virtual 360x480 (3:4)
        assertEquals(1f, p.cropY, 1e-5f)
        assertEquals(1f, p.rotCos, 0f)
        assertEquals(0f, p.rotSin, 0f)
    }

    @Test fun displayRotationAddsOnlyTheDifference() {
        // Same matrix, CameraX total 180 (device turned): remaining 90, displayed dims swap back to landscape.
        val p = IngestTransform.compute(1280, 960, 480, 360, 180, rot90)
        assertEquals(90, p.remainingDegrees)
        assertEquals(0f, p.rotCos, 0f)
        assertEquals(1f, p.rotSin, 0f)
        assertEquals(1f, p.cropX, 1e-5f)   // 1280x960 == 480x360
        assertEquals(1f, p.cropY, 1e-5f)
    }

    @Test fun surfaceWithoutCameraTransformUsesCameraxRotationDirectly() {
        val p = IngestTransform.compute(1280, 960, 360, 480, 90, upright)
        assertEquals(90, p.remainingDegrees)
    }

    @Test fun unknownRotationAssumesTheMatrixIsEnough() {
        assertEquals(0, IngestTransform.compute(1280, 960, 360, 480, -1, rot90).remainingDegrees)
    }

    @Test fun centreCropsToTheTargetAspect() {
        // 16:9 portrait source (720x1280) into a 3:4 target: full width kept, height cropped to 0.75.
        val p = IngestTransform.compute(1280, 720, 360, 480, 90, rot90)
        assertEquals(0, p.remainingDegrees)
        assertEquals(1f, p.cropX, 1e-5f)
        assertEquals(0.75f, p.cropY, 1e-4f)   // (9/16) / (3/4)
        // landscape target from the same source: width cropped instead
        val q = IngestTransform.compute(1280, 720, 480, 360, 90, upright)
        assertEquals(90, q.remainingDegrees)
    }

    @Test fun nonMultiplesOfNinetyAreSnapped() {
        assertEquals(90, IngestTransform.compute(1280, 960, 360, 480, 91, upright).remainingDegrees)
    }
}
