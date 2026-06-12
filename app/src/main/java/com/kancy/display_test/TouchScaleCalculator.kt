package com.kancy.display_test

import android.graphics.PointF

/**
 * Calculates touch coordinate scaling and letterbox/pillarbox offsets.
 *
 * When the Android view aspect ratio doesn't match the guest display aspect ratio,
 * the view is letterboxed (black bars on top/bottom) or pillarboxed (bars on left/right).
 * Touch coordinates need to be:
 * 1. Offset to account for the black bars
 * 2. Scaled to guest resolution
 */
object TouchScaleCalculator {

    /**
     * Computes scale factors and offsets for touch coordinate transformation.
     *
     * @param guestWidth Guest display width (e.g., 1920)
     * @param guestHeight Guest display height (e.g., 1080)
     * @param viewWidth Android view width
     * @param viewHeight Android view height
     * @return TouchTransform with scale factors and offsets
     */
    fun compute(
        guestWidth: Int,
        guestHeight: Int,
        viewWidth: Int,
        viewHeight: Int
    ): TouchTransform {
        if (viewWidth <= 0 || viewHeight <= 0 || guestWidth <= 0 || guestHeight <= 0) {
            return TouchTransform(1f, 1f, 0f, 0f)
        }

        val guestRatio = guestWidth.toFloat() / guestHeight.toFloat()
        val viewRatio = viewWidth.toFloat() / viewHeight.toFloat()

        return if (viewRatio > guestRatio) {
            // Pillarbox: black bars on left/right
            val displayHeight = viewHeight.toFloat()
            val displayWidth = displayHeight * guestRatio
            val offsetX = (viewWidth - displayWidth) / 2f
            TouchTransform(
                scaleX = guestWidth / displayWidth,
                scaleY = guestHeight / displayHeight,
                offsetX = offsetX,
                offsetY = 0f
            )
        } else {
            // Letterbox: black bars on top/bottom
            val displayWidth = viewWidth.toFloat()
            val displayHeight = displayWidth / guestRatio
            val offsetY = (viewHeight - displayHeight) / 2f
            TouchTransform(
                scaleX = guestWidth / displayWidth,
                scaleY = guestHeight / displayHeight,
                offsetX = 0f,
                offsetY = offsetY
            )
        }
    }

    data class TouchTransform(
        val scaleX: Float,
        val scaleY: Float,
        val offsetX: Float,
        val offsetY: Float
    ) {
        /**
         * Transforms a view coordinate to guest coordinate.
         * Returns null if the coordinate is outside the display area (in letterbox/pillarbox bars).
         */
        fun transform(viewX: Float, viewY: Float): PointF? {
            val adjustedX = viewX - offsetX
            val adjustedY = viewY - offsetY

            // Check if touch is in the black bars
            if (adjustedX < 0 || adjustedY < 0) return null

            val guestX = adjustedX * scaleX
            val guestY = adjustedY * scaleY

            return PointF(guestX, guestY)
        }
    }
}
