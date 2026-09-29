package com.adham1223990.serverapplicationfix

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.TextView
import androidx.core.widget.CompoundButtonCompat

/**
 * ألوان وأبعاد قريبة من ثيم ديسكورد الداكن الرسمي (Dark theme)، عشان فورم "Answer a few
 * questions to join" بتاعنا يبان أقرب ما يمكن لشكل ديسكورد الأصلي بدل ما يبان زي شاشة
 * إعدادات عادية. الألوان دي متاخدة بالعين من الثيم الرسمي — مفيش وصول لموارد ديسكورد
 * (colors.xml) نفسها من هنا، فممكن تختلف شعرة عن أي تحديث ثيم مستقبلي.
 */
object FormStyle {
    private const val BG_FIELD = "#1E1F22"
    private const val BG_CARD = "#2B2D31"
    private const val TEXT_PRIMARY = "#F2F3F5"
    private const val TEXT_MUTED = "#B5BAC1"
    private const val DIVIDER = "#3F4147"
    private const val BRAND = "#5865F2"
    private const val BRAND_DISABLED = "#3C4270"
    private const val DANGER = "#DA373C"

    /** حد أقصى معقول لخانة الفقرة (PARAGRAPH) — ديسكورد نفسه بيستخدم 1000 للحقل ده. */
    const val PARAGRAPH_MAX_LEN = 1000

    fun Context.dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    private fun color(hex: String) = Color.parseColor(hex)

    fun rounded(ctx: Context, bg: String, radiusDp: Int = 8, strokeColor: String? = null): GradientDrawable {
        return GradientDrawable().apply {
            setColor(color(bg))
            cornerRadius = ctx.dp(radiusDp).toFloat()
            if (strokeColor != null) setStroke(ctx.dp(1), color(strokeColor))
        }
    }

    fun pageTitle(ctx: Context, text: String): TextView = TextView(ctx).apply {
        this.text = text
        setTextColor(color(TEXT_PRIMARY))
        textSize = 20f
        setTypeface(typeface, Typeface.BOLD)
        gravity = Gravity.CENTER
        setPadding(0, ctx.dp(4), 0, ctx.dp(2))
    }

    fun pageSubtitle(ctx: Context, text: String): TextView = TextView(ctx).apply {
        this.text = text
        setTextColor(color(TEXT_MUTED))
        textSize = 13f
        gravity = Gravity.CENTER
        setPadding(ctx.dp(8), ctx.dp(6), ctx.dp(8), ctx.dp(4))
    }

    /** عنوان سؤال، مع نجمة حمرا صغيرة لو الحقل إجباري (زي ديسكورد بالظبط). */
    fun questionLabel(ctx: Context, text: String, required: Boolean): LinearLayout =
        LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, ctx.dp(18), 0, ctx.dp(6))
            addView(TextView(ctx).apply {
                this.text = text
                setTextColor(color(TEXT_PRIMARY))
                textSize = 15f
            })
            if (required) {
                addView(TextView(ctx).apply {
                    text = " *"
                    setTextColor(color(DANGER))
                    textSize = 15f
                })
            }
        }

    fun boldSectionHeader(ctx: Context, text: String): TextView = TextView(ctx).apply {
        this.text = text
        setTextColor(color(TEXT_PRIMARY))
        textSize = 15f
        setTypeface(typeface, Typeface.BOLD)
        setPadding(0, ctx.dp(18), 0, ctx.dp(8))
    }

    fun divider(ctx: Context): View = View(ctx).apply {
        setBackgroundColor(color(DIVIDER))
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ctx.dp(1))
    }

    /** خانة إجابة قصيرة (short answer) بحدود مدوّرة زي ديسكورد بالظبط. */
    fun shortAnswerField(ctx: Context): EditText = EditText(ctx).apply {
        background = rounded(ctx, BG_FIELD, 8, DIVIDER)
        setTextColor(color(TEXT_PRIMARY))
        setHintTextColor(color(TEXT_MUTED))
        hint = "Enter your answer"
        setPadding(ctx.dp(14), ctx.dp(12), ctx.dp(14), ctx.dp(12))
        isSingleLine = true
    }

    /** بطاقة الفقرة (paragraph): EditText متعدد الأسطر + عداد حروف مطابق لديسكورد. */
    fun paragraphField(ctx: Context, onChanged: (String, TextView) -> Unit): Pair<LinearLayout, EditText> {
        val card = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            background = rounded(ctx, BG_FIELD, 8, DIVIDER)
            setPadding(ctx.dp(14), ctx.dp(12), ctx.dp(14), ctx.dp(10))
        }
        val edit = EditText(ctx).apply {
            setTextColor(color(TEXT_PRIMARY))
            setHintTextColor(color(TEXT_MUTED))
            hint = "Enter your answer"
            isSingleLine = false
            minLines = 3
            gravity = Gravity.TOP or Gravity.START
            setBackgroundColor(Color.TRANSPARENT)
            setPadding(0, 0, 0, 0)
        }
        val counter = TextView(ctx).apply {
            setTextColor(color(TEXT_MUTED))
            textSize = 12f
            gravity = Gravity.END
            text = PARAGRAPH_MAX_LEN.toString()
            setPadding(0, ctx.dp(6), 0, 0)
        }
        card.addView(edit)
        card.addView(counter)
        edit.addTextChangedListener(simpleWatcher { s -> onChanged(s, counter) })
        return card to edit
    }

    /** بطاقة اختيار واحد من متعدد (multiple choice) بشكل صفوف داخل كارت واحد زي ديسكورد. */
    fun choiceCard(
        ctx: Context,
        choices: List<String>,
        onSelected: (Int) -> Unit
    ): LinearLayout {
        val card = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            background = rounded(ctx, BG_CARD, 8)
        }
        val radios = ArrayList<RadioButton>()
        choices.forEachIndexed { index, choice ->
            if (index > 0) {
                card.addView(divider(ctx).apply {
                    val lp = layoutParams as LinearLayout.LayoutParams
                    lp.marginStart = ctx.dp(14); lp.marginEnd = ctx.dp(14)
                    layoutParams = lp
                })
            }
            val row = LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(ctx.dp(14), ctx.dp(12), ctx.dp(14), ctx.dp(12))
            }
            row.addView(TextView(ctx).apply {
                text = choice
                setTextColor(color(TEXT_PRIMARY))
                textSize = 14f
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            })
            val rb = RadioButton(ctx).apply {
                isClickable = false
                text = ""
                CompoundButtonCompat.setButtonTintList(this, radioTint(ctx))
            }
            row.addView(rb)
            radios.add(rb)
            row.setOnClickListener {
                radios.forEachIndexed { i, r -> r.isChecked = i == index }
                onSelected(index)
            }
            card.addView(row)
        }
        return card
    }

    /** بطاقة قوانين السيرفر: كل قاعدة سطر مرقّم داخل نفس الكارت زي ديسكورد. */
    fun rulesCard(ctx: Context, rules: List<String>): LinearLayout {
        val card = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            background = rounded(ctx, BG_CARD, 8)
        }
        rules.forEachIndexed { index, rule ->
            if (index > 0) {
                card.addView(divider(ctx).apply {
                    val lp = layoutParams as LinearLayout.LayoutParams
                    lp.marginStart = ctx.dp(14); lp.marginEnd = ctx.dp(14)
                    layoutParams = lp
                })
            }
            card.addView(TextView(ctx).apply {
                text = "${index + 1}. $rule"
                setTextColor(color(TEXT_PRIMARY))
                textSize = 14f
                setPadding(ctx.dp(14), ctx.dp(12), ctx.dp(14), ctx.dp(12))
            })
        }
        return card
    }

    /** صف "I have read and agree" بنفس شكل كروت الفورم، مع checkbox في الآخر زي ديسكورد. */
    fun agreeRow(ctx: Context, text: String, onToggled: (Boolean) -> Unit): LinearLayout {
        val row = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = rounded(ctx, BG_CARD, 8)
            setPadding(ctx.dp(14), ctx.dp(12), ctx.dp(14), ctx.dp(12))
        }
        row.addView(TextView(ctx).apply {
            this.text = text
            setTextColor(color(TEXT_PRIMARY))
            textSize = 14f
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        })
        val box = CheckBox(ctx).apply {
            text = ""
            CompoundButtonCompat.setButtonTintList(this, radioTint(ctx))
        }
        row.addView(box)
        box.setOnCheckedChangeListener { _, checked -> onToggled(checked) }
        row.setOnClickListener { box.isChecked = !box.isChecked }
        return row
    }

    private fun radioTint(ctx: Context): ColorStateList = ColorStateList(
        arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf(-android.R.attr.state_checked)),
        intArrayOf(color(BRAND), color(TEXT_MUTED))
    )

    fun primaryButton(ctx: Context, text: String): Button = Button(ctx).apply {
        this.text = text
        setTextColor(Color.WHITE)
        isAllCaps = false
        textSize = 15f
        background = rounded(ctx, BRAND, 8)
        setPadding(0, ctx.dp(14), 0, ctx.dp(14))
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = ctx.dp(24) }
    }

    fun setButtonEnabled(ctx: Context, b: Button, enabled: Boolean) {
        b.isEnabled = enabled
        b.background = rounded(ctx, if (enabled) BRAND else BRAND_DISABLED, 8)
    }

    fun bodyText(ctx: Context, text: String): TextView = TextView(ctx).apply {
        this.text = text
        setTextColor(color(TEXT_PRIMARY))
        setPadding(ctx.dp(4), ctx.dp(16), ctx.dp(4), ctx.dp(16))
    }

    private fun simpleWatcher(onChanged: (String) -> Unit) = object : android.text.TextWatcher {
        override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
        override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
        override fun afterTextChanged(s: android.text.Editable?) = onChanged(s?.toString() ?: "")
    }
}
