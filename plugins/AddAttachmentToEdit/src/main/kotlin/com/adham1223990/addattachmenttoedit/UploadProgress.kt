package com.adham1223990.addattachmenttoedit

import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import com.aliucord.Utils
import com.discord.utilities.color.ColorCompat

/**
 * Small floating card at the bottom of the screen that shows upload progress
 * (file name, percent, transferred / total size). Safe to call from any thread.
 */
class UploadProgress {

    private var card: LinearLayout? = null
    private var title: TextView? = null
    private var detail: TextView? = null
    private var bar: ProgressBar? = null

    @Volatile private var totalBytes = 0L
    @Volatile private var fileCount = 1
    @Volatile private var sentBytes = 0L
    @Volatile private var currentName = ""
    @Volatile private var currentIndex = 0
    @Volatile private var stage = "Preparing…"
    @Volatile private var lastRender = 0L
    @Volatile private var dismissed = false

    private fun themed(attr: String, fallback: Int): Int {
        return try {
            val id = Utils.getResId(attr, "attr")
            if (id != 0) ColorCompat.getThemedColor(Utils.appActivity, id) else fallback
        } catch (_: Throwable) {
            fallback
        }
    }

    fun show(initialStage: String) {
        stage = initialStage
        Utils.mainThread.post {
            if (dismissed) return@post
            try {
                val activity = Utils.appActivity
                val root = activity.window.decorView as? ViewGroup ?: return@post
                val density = activity.resources.displayMetrics.density
                fun dp(v: Int) = (v * density + 0.5f).toInt()

                val c = LinearLayout(activity)
                c.orientation = LinearLayout.VERTICAL
                c.setPadding(dp(16), dp(12), dp(16), dp(14))
                c.elevation = dp(8).toFloat()
                c.background = GradientDrawable().apply {
                    cornerRadius = dp(12).toFloat()
                    setColor(themed("colorBackgroundFloating", Color.parseColor("#2B2D31")))
                    setStroke(dp(1), themed("colorTextMuted", Color.parseColor("#4E5058")))
                }
                val textColor = themed("colorTextNormal", Color.WHITE)

                val t = TextView(activity)
                t.setTextColor(textColor)
                t.textSize = 14f
                t.maxLines = 1
                t.ellipsize = android.text.TextUtils.TruncateAt.MIDDLE
                c.addView(t)

                val b = ProgressBar(activity, null, android.R.attr.progressBarStyleHorizontal)
                b.max = 1000
                b.progressTintList = ColorStateList.valueOf(Color.parseColor("#5865F2"))
                b.progressBackgroundTintList = ColorStateList.valueOf(Color.parseColor("#4E5058"))
                b.isIndeterminate = true
                c.addView(
                    b,
                    LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(8)).apply {
                        topMargin = dp(8)
                    }
                )

                val d = TextView(activity)
                d.setTextColor(themed("colorTextMuted", Color.LTGRAY))
                d.textSize = 12f
                c.addView(
                    d,
                    LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT
                    ).apply { topMargin = dp(6) }
                )

                val lp = FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
                ).apply {
                    setMargins(dp(16), 0, dp(16), dp(96))
                }
                root.addView(c, lp)

                card = c
                title = t
                detail = d
                bar = b
                render(true)
            } catch (_: Throwable) {
                // Progress UI is cosmetic; never let it break the upload.
            }
        }
    }

    /** Called once the files have been inspected and the overall size is known. */
    fun setTotal(total: Long, count: Int) {
        totalBytes = total
        fileCount = count
        sentBytes = 0L
        stage = "Uploading"
        render(true)
    }

    fun startFile(index: Int, name: String) {
        currentIndex = index
        currentName = name
        render(true)
    }

    fun addBytes(n: Long) {
        sentBytes += n
        render(false)
    }

    fun finishing() {
        stage = "Finishing…"
        sentBytes = totalBytes
        render(true)
    }

    fun dismiss() {
        dismissed = true
        Utils.mainThread.post {
            try {
                val c = card
                (c?.parent as? ViewGroup)?.removeView(c)
            } catch (_: Throwable) {
            }
            card = null
        }
    }

    private fun render(force: Boolean) {
        val now = System.currentTimeMillis()
        if (!force && now - lastRender < 100) return
        lastRender = now
        Utils.mainThread.post {
            val t = title ?: return@post
            val d = detail ?: return@post
            val b = bar ?: return@post
            if (totalBytes <= 0L) {
                b.isIndeterminate = true
                t.text = stage
                d.text = ""
                return@post
            }
            b.isIndeterminate = false
            val fraction = (sentBytes.toDouble() / totalBytes.toDouble()).coerceIn(0.0, 1.0)
            b.progress = (fraction * 1000).toInt()
            val percent = (fraction * 100).toInt()
            val filePart = if (fileCount > 1) " (${currentIndex + 1}/$fileCount)" else ""
            t.text = if (stage == "Uploading" && currentName.isNotEmpty()) {
                "Uploading$filePart: $currentName"
            } else {
                stage
            }
            d.text = "$percent%  •  ${size(sentBytes)} / ${size(totalBytes)}"
        }
    }

    private fun size(bytes: Long): String {
        val mb = bytes / (1024.0 * 1024.0)
        return if (mb >= 1024.0) String.format("%.2f GB", mb / 1024.0) else String.format("%.1f MB", mb)
    }
}
