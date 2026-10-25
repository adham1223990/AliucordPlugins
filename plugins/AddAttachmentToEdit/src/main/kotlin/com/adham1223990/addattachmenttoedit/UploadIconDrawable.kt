package com.adham1223990.addattachmenttoedit

import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.drawable.Drawable

/**
 * Vencord's "UploadIcon" (the circled plus used by its Add Attachments action), rebuilt as an
 * android.graphics.Path so it can be shown as a compound drawable in Discord's native menu.
 *
 * Source SVG (viewBox 0 0 24 24):
 *   M12 23a11 11 0 1 0 0-22 11 11 0 0 0 0 22Z
 *   m0-17a1 1 0 0 1 1 1v4h4a1 1 0 1 1 0 2h-4v4a1 1 0 1 1-2 0v-4H7a1 1 0 1 1 0-2h4V7a1 1 0 0 1 1-1Z
 * i.e. a filled circle (r = 11) with a rounded plus (arms 2 wide, 12 long, round caps) cut out.
 */
class UploadIconDrawable(color: Int, private val sizePx: Int) : Drawable() {

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        this.color = color
    }

    private val path = Path().apply {
        val circle = Path().apply { addCircle(12f, 12f, 11f, Path.Direction.CW) }
        val vertical = Path().apply { addRoundRect(RectF(11f, 6f, 13f, 18f), 1f, 1f, Path.Direction.CW) }
        val horizontal = Path().apply { addRoundRect(RectF(6f, 11f, 18f, 13f), 1f, 1f, Path.Direction.CW) }
        // Union the two bars into one plus, then punch it out of the circle.
        vertical.op(horizontal, Path.Op.UNION)
        circle.op(vertical, Path.Op.DIFFERENCE)
        set(circle)
    }

    override fun draw(canvas: Canvas) {
        val b = bounds
        if (b.isEmpty) return
        val save = canvas.save()
        canvas.translate(b.left.toFloat(), b.top.toFloat())
        canvas.scale(b.width() / 24f, b.height() / 24f)
        canvas.drawPath(path, paint)
        canvas.restoreToCount(save)
    }

    override fun getIntrinsicWidth(): Int = sizePx

    override fun getIntrinsicHeight(): Int = sizePx

    override fun setAlpha(alpha: Int) {
        paint.alpha = alpha
        invalidateSelf()
    }

    override fun setColorFilter(colorFilter: ColorFilter?) {
        paint.colorFilter = colorFilter
        invalidateSelf()
    }

    @Suppress("OVERRIDE_DEPRECATION")
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
}
