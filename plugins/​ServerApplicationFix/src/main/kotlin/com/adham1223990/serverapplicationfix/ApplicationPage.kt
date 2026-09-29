package com.adham1223990.serverapplicationfix

import android.content.Context
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import com.aliucord.Logger
import com.aliucord.Utils
import com.aliucord.fragments.SettingsPage
import com.adham1223990.serverapplicationfix.FormStyle.dp

class ApplicationPage(private val guildId: String) : SettingsPage() {
    private val logger = Logger("ServerApplicationFix")

    private var form: VerificationForm? = null
    private var loading = true
    private var error: String? = null
    private var submitting = false

    private lateinit var container: LinearLayout

    override fun onViewBound(view: View) {
        super.onViewBound(view)
        val guildName = ApplicationApi.guildNames[guildId]
        setActionBarTitle(if (!guildName.isNullOrEmpty()) guildName else "Server Application")
        container = linearLayout
        reload()
    }

    /** يقفل الصفحة: الأول بإنهاء الـ Activity الحاملة لها، ولو مش متاحة بنرجع خطوة في الـ back stack. */
    private fun closePage() {
        try {
            val act = activity
            if (act != null && !act.isFinishing) {
                act.finish()
                return
            }
        } catch (t: Throwable) {
            logger.error("Failed to finish activity", t)
        }
        try {
            parentFragmentManager.popBackStack()
        } catch (t: Throwable) {
            logger.error("Failed to pop back stack", t)
        }
    }

    override fun onDestroy() {
        ApplicationApi.openPages.remove(guildId)
        super.onDestroy()
    }

    private fun reload(useCached: Boolean = true) {
        error = null
        val cached = if (useCached) ApplicationApi.prefetchedForms.remove(guildId) else null
        if (cached != null) {
            form = cached
            loading = false
            render()
            return
        }

        loading = true
        render()
        Utils.threadPool.execute {
            val result = runCatching { ApplicationApi.fetchForm(guildId) }
            Utils.mainThread.post {
                if (!::container.isInitialized) return@post
                loading = false
                result.onSuccess { form = it }
                    .onFailure {
                        logger.error("Failed to load application form for guild $guildId", it)
                        error = it.message ?: it.toString()
                    }
                try {
                    render()
                } catch (t: Throwable) {
                    logger.error("Failed to render application form for guild $guildId", t)
                    error = "Render error: ${t.message}"
                    loading = false
                    render()
                }
            }
        }
    }

    private fun render() {
        if (!::container.isInitialized) return
        val ctx = container.context
        container.removeAllViews()
        container.setPadding(ctx.dp(16), ctx.dp(4), ctx.dp(16), ctx.dp(24))

        when {
            loading -> container.addView(FormStyle.bodyText(ctx, "Loading application…"))

            error != null -> {
                container.addView(FormStyle.bodyText(ctx, "Failed to load the application:\n$error"))
                container.addView(FormStyle.primaryButton(ctx, "Retry").apply {
                    setOnClickListener { reload(useCached = false) }
                })
            }

            form != null -> renderForm(ctx, form!!)
        }
    }

    /** يبني هيدر شبه هيدر "Answer a few questions to join" في تطبيق ديسكورد الأصلي. */
    private fun renderHeader(ctx: Context, f: VerificationForm) {
        val guildName = ApplicationApi.guildNames[guildId]
        container.addView(FormStyle.pageTitle(ctx, "Answer a few questions to join"))
        if (!guildName.isNullOrEmpty()) {
            container.addView(FormStyle.pageTitle(ctx, guildName))
        }
        // الفورم القوانين بس (بدون أي سؤال حقيقي) وصفه مختلف عن فورم فيه أسئلة تقديم.
        val subtitle = if (ApplicationApi.isApplicationForm(f)) {
            "The server admin will get back to you shortly after you submit."
        } else {
            "Please read and agree to the server rules to continue."
        }
        container.addView(FormStyle.pageSubtitle(ctx, subtitle))
        container.addView(FormStyle.divider(ctx).apply {
            val lp = layoutParams as LinearLayout.LayoutParams
            lp.topMargin = ctx.dp(16); lp.bottomMargin = ctx.dp(4)
            layoutParams = lp
        })
    }

    private fun renderForm(ctx: Context, f: VerificationForm) {
        renderHeader(ctx, f)

        for (field in f.fields) {
            when (field.fieldType) {
                "TERMS" -> {
                    container.addView(FormStyle.boldSectionHeader(ctx, "Read & Agree to Server Rules"))
                    container.addView(FormStyle.rulesCard(ctx, field.values))
                    val agreeText = if (field.required) {
                        "I have read and agree to the rules"
                    } else {
                        "I have read and agree"
                    }
                    container.addView(FormStyle.agreeRow(ctx, agreeText) { checked ->
                        field.response = if (checked) "true" else "false"
                    }.apply {
                        val lp = LinearLayout.LayoutParams(
                            LinearLayout.LayoutParams.MATCH_PARENT,
                            LinearLayout.LayoutParams.WRAP_CONTENT
                        )
                        lp.topMargin = ctx.dp(12)
                        layoutParams = lp
                    })
                }

                "TEXT_INPUT" -> {
                    container.addView(FormStyle.questionLabel(ctx, field.label, field.required))
                    container.addView(FormStyle.shortAnswerField(ctx).apply {
                        addTextChangedListener(object : TextWatcher {
                            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                            override fun afterTextChanged(s: Editable?) {
                                field.response = s?.toString() ?: ""
                            }
                        })
                    })
                }

                "PARAGRAPH" -> {
                    container.addView(FormStyle.questionLabel(ctx, field.label, field.required))
                    val (card, _) = FormStyle.paragraphField(ctx) { text, counter ->
                        field.response = text
                        val remaining = FormStyle.PARAGRAPH_MAX_LEN - text.length
                        counter.text = remaining.coerceAtLeast(0).toString()
                    }
                    container.addView(card)
                }

                "MULTIPLE_CHOICE" -> {
                    container.addView(FormStyle.questionLabel(ctx, field.label, field.required))
                    container.addView(FormStyle.choiceCard(ctx, field.choices) { index ->
                        field.response = index
                    })
                }

                else -> {
                    container.addView(FormStyle.bodyText(
                        ctx,
                        "${field.label}\n(Unsupported field type: ${field.fieldType}. " +
                            "Please use the official Discord app for this field.)"
                    ))
                }
            }
        }

        container.addView(FormStyle.primaryButton(ctx, "Complete").apply {
            isEnabled = !submitting
            setOnClickListener {
                val missing = f.fields.any {
                    it.required && (it.response == "" || it.response == -1)
                }
                if (missing) {
                    Utils.showToast("Please complete all required fields", true)
                    return@setOnClickListener
                }
                submit(f)
            }
        })
    }

    private fun submit(f: VerificationForm) {
        if (submitting) return
        // مفيش فحص على appliedGuilds هنا: ممكن يكون قديم (اتقبلت ثم خرجت). submitForm بيسأل
        // ديسكورد مباشرة وبيرفض بس لو الطلب فعلاً قيد المراجعة أو مرفوض.
        submitting = true
        Utils.showToast("Submitting…")
        (container.getChildAt(container.childCount - 1) as? Button)?.let {
            FormStyle.setButtonEnabled(container.context, it, false)
        }

        Utils.threadPool.execute {
            val result = runCatching { ApplicationApi.submitForm(guildId, f) }
            Utils.mainThread.post {
                submitting = false
                result
                    .onSuccess { status ->
                        ApplicationApi.appliedGuilds.add(guildId)
                        if (status == "DONE") {
                            Utils.showToast("Done!", true)
                        } else {
                            Utils.showToast("Application submitted! Status: ${ApplicationApi.statusLabel(status)}", true)
                        }
                        closePage()
                    }
                    .onFailure {
                        if (it is AlreadyAppliedException) {
                            Utils.showToast(it.message ?: "You already applied to this server.", true)
                            closePage()
                        } else {
                            logger.error("Failed to submit application for guild $guildId", it)
                            Utils.showToast("Failed to submit: ${it.message}", true)
                            runCatching { render() }
                        }
                    }
            }
        }
    }
}
