package com.adham1223990.serverapplicationfix

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.util.TypedValue
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.core.graphics.ColorUtils
import androidx.fragment.app.Fragment
import com.aliucord.Utils
import com.aliucord.views.Button
import com.lytefast.flexinput.R
import org.json.JSONArray
import org.json.JSONObject

class ApplicationPage(
    private val plugin: ServerApplicationFix,
    private val guildId: String,
    private val serverDescription: String,
    private val formFields: JSONArray
) : Fragment() {

    private fun dpToPx(context: Context, dp: Int): Int {
        return Math.round(dp.toFloat() * context.resources.displayMetrics.density)
    }

    private fun closePage() {
        Utils.mainThread.post {
            try { fragmentManager?.popBackStackImmediate() } catch (ignored: Exception) {}
            activity?.let { if (!it.isFinishing) it.finish() }
        }
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        val ctx = requireContext()
        val dp16 = dpToPx(ctx, 16)
        val dp12 = dpToPx(ctx, 12)
        val dp8 = dpToPx(ctx, 8)

        val rootLayout = RelativeLayout(ctx).apply {
            layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            setPadding(dp16, dp16, dp16, dp16)

            val typedValue = TypedValue()
            if (ctx.theme.resolveAttribute(R.b.colorBackgroundPrimary, typedValue, true)) {
                setBackgroundColor(ctx.getColor(typedValue.resourceId))
            }
        }

        val layoutHeader = LinearLayout(ctx).apply {
            id = View.generateViewId()
            orientation = LinearLayout.VERTICAL
            setPadding(0, 0, 0, dp12)
            layoutParams = RelativeLayout.LayoutParams(RelativeLayout.LayoutParams.MATCH_PARENT, RelativeLayout.LayoutParams.WRAP_CONTENT).apply {
                addRule(RelativeLayout.ALIGN_PARENT_TOP)
            }
        }

        val topRow = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        }

        val btnClose = ImageButton(ctx).apply {
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            setBackgroundColor(0)
            setImageResource(R.e.ic_arrow_back_white_24dp)
            scaleType = ImageView.ScaleType.FIT_CENTER
            val inset = dpToPx(ctx, 4)
            setPadding(inset, inset, inset, inset)
            setOnClickListener { closePage() }
        }

        val txtHeader = TextView(ctx).apply {
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                setMarginStart(dp8)
            }
            setTextAppearance(R.i.UiKit_Settings_Text)
            text = "Server Application"
            setTypeface(null, Typeface.BOLD)
        }

        topRow.addView(btnClose)
        topRow.addView(txtHeader)
        layoutHeader.addView(topRow)

        if (serverDescription.isNotEmpty()) {
            val txtDesc = TextView(ctx).apply {
                layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                    topMargin = dp8
                }
                setTextAppearance(R.i.UiKit_TextView_Subtext)
                text = serverDescription
            }
            layoutHeader.addView(txtDesc)
        }
        rootLayout.addView(layoutHeader)

        val layoutBottom = RelativeLayout(ctx).apply {
            id = View.generateViewId()
            setPadding(0, dp12, 0, 0)
            layoutParams = RelativeLayout.LayoutParams(RelativeLayout.LayoutParams.MATCH_PARENT, RelativeLayout.LayoutParams.WRAP_CONTENT).apply {
                addRule(RelativeLayout.ALIGN_PARENT_BOTTOM)
            }
        }

        val btnSubmit = Button(ctx).apply {
            text = "Submit Application"
            layoutParams = RelativeLayout.LayoutParams(RelativeLayout.LayoutParams.MATCH_PARENT, RelativeLayout.LayoutParams.WRAP_CONTENT)
        }
        layoutBottom.addView(btnSubmit)
        rootLayout.addView(layoutBottom)

        val scrollView = ScrollView(ctx).apply {
            isFillViewport = true
            layoutParams = RelativeLayout.LayoutParams(RelativeLayout.LayoutParams.MATCH_PARENT, RelativeLayout.LayoutParams.MATCH_PARENT).apply {
                addRule(RelativeLayout.BELOW, layoutHeader.id)
                addRule(RelativeLayout.ABOVE, layoutBottom.id)
            }
        }

        val fieldsContainer = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp8, 0, dp8)
            layoutParams = FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT)
        }
        scrollView.addView(fieldsContainer)
        rootLayout.addView(scrollView)

        val collectedResponses = mutableListOf<() -> JSONObject?>()

        for (i in 0 until formFields.length()) {
            val field = formFields.getJSONObject(i)
            val fieldType = field.optString("field_type")
            val label = field.optString("label")
            val required = field.optBoolean("required", false)

            when (fieldType) {
                "TERMS" -> {
                    val values = field.optJSONArray("values") ?: JSONArray()
                    var isAgreed = false

                    val card = buildCard(ctx, label.ifEmpty { "Rules & Guidelines" }, "Tap to agree to server rules")
                    val checkMark = card.second

                    card.first.setOnClickListener {
                        isAgreed = !isAgreed
                        updateCardSelection(ctx, card.first, checkMark, isAgreed)
                    }

                    if (values.length() > 0) {
                        val rulesText = StringBuilder()
                        for (r in 0 until values.length()) {
                            rulesText.append("${r + 1}. ").append(values.getString(r)).append("\n")
                        }
                        val desc = card.first.findViewWithTag<TextView>("desc")
                        desc?.text = rulesText.toString().trim()
                        desc?.visibility = View.VISIBLE
                    }

                    fieldsContainer.addView(card.first)
                    collectedResponses.add {
                        if (required && !isAgreed) null
                        else JSONObject().apply {
                            put("field_type", "TERMS")
                            put("label", label)
                            put("response", isAgreed)
                        }
                    }
                }

                "TEXT_INPUT", "PARAGRAPH" -> {
                    val block = LinearLayout(ctx).apply {
                        orientation = LinearLayout.VERTICAL
                        setPadding(0, dp8, 0, dp8)
                    }

                    val title = TextView(ctx).apply {
                        setTextAppearance(R.i.UiKit_Settings_Text)
                        setTypeface(null, Typeface.BOLD)
                        text = if (required) "$label *" else label
                    }
                    block.addView(title)

                    val editText = EditText(ctx).apply {
                        hint = "Your answer..."
                        setTextAppearance(ctx, R.i.UiKit_Settings_Text)
                        background = GradientDrawable().apply {
                            setColor(ColorUtils.setAlphaComponent(Color.GRAY, 25))
                            cornerRadius = dpToPx(ctx, 8).toFloat()
                        }
                        setPadding(dp12, dp12, dp12, dp12)
                    }
                    block.addView(editText)
                    fieldsContainer.addView(block)

                    collectedResponses.add {
                        val ans = editText.text.toString().trim()
                        if (required && ans.isEmpty()) null
                        else JSONObject().apply {
                            put("field_type", fieldType)
                            put("label", label)
                            put("response", ans)
                        }
                    }
                }

                "MULTIPLE_CHOICE" -> {
                    val options = field.optJSONArray("choices") ?: JSONArray()
                    var selectedIndex = -1

                    val block = LinearLayout(ctx).apply {
                        orientation = LinearLayout.VERTICAL
                        setPadding(0, dp8, 0, dp8)
                    }

                    val title = TextView(ctx).apply {
                        setTextAppearance(R.i.UiKit_Settings_Text)
                        setTypeface(null, Typeface.BOLD)
                        text = if (required) "$label *" else label
                    }
                    block.addView(title)

                    val cardsList = mutableListOf<Pair<LinearLayout, ImageView>>()

                    for (j in 0 until options.length()) {
                        val choice = options.getString(j)
                        val optCard = buildCard(ctx, choice, "")
                        cardsList.add(optCard)

                        optCard.first.setOnClickListener {
                            selectedIndex = j
                            cardsList.forEachIndexed { idx, pair ->
                                updateCardSelection(ctx, pair.first, pair.second, idx == selectedIndex)
                            }
                        }
                        block.addView(optCard.first)
                    }
                    fieldsContainer.addView(block)

                    collectedResponses.add {
                        if (required && selectedIndex == -1) null
                        else JSONObject().apply {
                            put("field_type", "MULTIPLE_CHOICE")
                            put("label", label)
                            put("response", if (selectedIndex != -1) options.getString(selectedIndex) else "")
                        }
                    }
                }
            }
        }

        btnSubmit.setOnClickListener {
            val payload = JSONArray()
            for (builder in collectedResponses) {
                val res = builder()
                if (res == null) {
                    Utils.showToast("Please fill all required fields / agree to rules.", false)
                    return@setOnClickListener
                }
                payload.put(res)
            }

            btnSubmit.isEnabled = false
            plugin.submitForm(guildId, payload) { success, error ->
                Utils.mainThread.post {
                    if (success) {
                        Utils.showToast("Application submitted successfully!", false)
                        closePage()
                    } else {
                        btnSubmit.isEnabled = true
                        Utils.showToast("Submission failed: $error", false)
                    }
                }
            }
        }

        return rootLayout
    }

    private fun buildCard(context: Context, titleStr: String, descStr: String): Pair<LinearLayout, ImageView> {
        val dp16 = dpToPx(context, 16)
        val dp12 = dpToPx(context, 12)
        val dp8 = dpToPx(context, 8)
        val dp4 = dpToPx(context, 4)
        val dp24 = dpToPx(context, 24)

        val root = LinearLayout(context).apply {
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                bottomMargin = dp8
            }
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp16, dp16, dp16, dp16)
            isClickable = true
            isFocusable = true

            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dpToPx(context, 8).toFloat()
                setColor(Color.TRANSPARENT)
                setStroke(dpToPx(context, 1), ColorUtils.setAlphaComponent(Color.WHITE, 60))
            }
        }

        val textContainer = LinearLayout(context).apply {
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                weight = 1f
                marginEnd = dp12
            }
            orientation = LinearLayout.VERTICAL
        }

        val title = TextView(context).apply {
            setTextAppearance(R.i.UiKit_Settings_Text)
            text = titleStr
        }

        val desc = TextView(context).apply {
            tag = "desc"
            setTextAppearance(R.i.UiKit_TextView_Subtext)
            text = descStr
            visibility = if (descStr.isEmpty()) View.GONE else View.VISIBLE
            setPadding(0, dp4, 0, 0)
        }

        textContainer.addView(title)
        textContainer.addView(desc)
        root.addView(textContainer)

        val checkMark = ImageView(context).apply {
            layoutParams = LinearLayout.LayoutParams(dp24, dp24)
            setImageResource(android.R.drawable.checkbox_on_background)
            visibility = View.INVISIBLE
        }
        root.addView(checkMark)

        return Pair(root, checkMark)
    }

    private fun updateCardSelection(context: Context, root: LinearLayout, check: ImageView, selected: Boolean) {
        val typedValue = TypedValue()
        context.theme.resolveAttribute(R.b.colorAccent, typedValue, true)
        val accentColor = typedValue.data
        val bgSelected = ColorUtils.setAlphaComponent(accentColor, 40)

        val drawable = root.background as GradientDrawable
        if (selected) {
            drawable.setColor(bgSelected)
            drawable.setStroke(dpToPx(context, 2), accentColor)
            check.visibility = View.VISIBLE
        } else {
            drawable.setColor(Color.TRANSPARENT)
            drawable.setStroke(dpToPx(context, 1), ColorUtils.setAlphaComponent(Color.WHITE, 60))
            check.visibility = View.INVISIBLE
        }
    }
}
