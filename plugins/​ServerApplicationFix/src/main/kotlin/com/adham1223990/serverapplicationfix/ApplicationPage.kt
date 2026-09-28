package com.adham1223990.serverapplicationfix

import android.content.Context
import android.graphics.Color
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.TextView
import com.aliucord.Logger
import com.aliucord.Utils
import com.aliucord.fragments.SettingsPage

class ApplicationPage(private val guildId: String) : SettingsPage() {
    private val logger = Logger("ServerApplicationFix")

    private var form: VerificationForm? = null
    private var loading = true
    private var error: String? = null
    private var submitting = false

    private lateinit var container: LinearLayout

    override fun onViewBound(view: View) {
        super.onViewBound(view)
        setActionBarTitle("Server Application")
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

        when {
            loading -> container.addView(TextView(ctx).apply {
                text = "Loading application…"
                setTextColor(Color.WHITE)
                setPadding(32, 32, 32, 32)
            })

            error != null -> {
                container.addView(TextView(ctx).apply {
                    text = "Failed to load the application:\n$error"
                    setTextColor(Color.WHITE)
                    setPadding(32, 32, 32, 16)
                })
                container.addView(Button(ctx).apply {
                    text = "Retry"
                    styleWhite()
                    setOnClickListener { reload(useCached = false) }
                })
            }

            form != null -> renderForm(ctx, form!!)
        }
    }

    private fun renderForm(ctx: Context, f: VerificationForm) {
        for (field in f.fields) {
            when (field.fieldType) {
                "TERMS" -> {
                    for (rule in field.values) {
                        container.addView(TextView(ctx).apply {
                            text = rule
                            setTextColor(Color.WHITE)
                            setPadding(32, 8, 32, 8)
                        })
                    }
                    container.addView(CheckBox(ctx).apply {
                        text = if (field.required) "I have read and agree (required)" else "I have read and agree"
                        setTextColor(Color.WHITE)
                        setPadding(32, 8, 32, 8)
                        setOnCheckedChangeListener { _, checked ->
                            field.response = if (checked) "true" else "false"
                        }
                    })
                }

                "TEXT_INPUT", "PARAGRAPH" -> {
                    container.addView(TextView(ctx).apply {
                        text = if (field.required) "${field.label} *" else field.label
                        setTextColor(Color.WHITE)
                        setPadding(32, 16, 32, 4)
                    })
                    container.addView(EditText(ctx).apply {
                        setTextColor(Color.WHITE)
                        setHintTextColor(Color.LTGRAY)
                        if (field.fieldType == "PARAGRAPH") {
                            isSingleLine = false
                            minLines = 3
                        }
                        addTextChangedListener(object : TextWatcher {
                            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                            override fun afterTextChanged(s: Editable?) {
                                field.response = s?.toString() ?: ""
                            }
                        })
                    })
                }

                "MULTIPLE_CHOICE" -> {
                    container.addView(TextView(ctx).apply {
                        text = if (field.required) "${field.label} *" else field.label
                        setTextColor(Color.WHITE)
                        setPadding(32, 16, 32, 4)
                    })
                    val group = RadioGroup(ctx).apply { orientation = RadioGroup.VERTICAL }
                    for (choice in field.choices) {
                        group.addView(RadioButton(ctx).apply {
                            text = choice
                            setTextColor(Color.WHITE)
                            id = View.generateViewId()
                        })
                    }
                    group.setOnCheckedChangeListener { rg, checkedId ->
                        var i = 0
                        while (i < rg.childCount) {
                            if (rg.getChildAt(i).id == checkedId) {
                                field.response = i
                                break
                            }
                            i++
                        }
                    }
                    container.addView(group)
                }

                else -> {
                    container.addView(TextView(ctx).apply {
                        text = "${field.label}\n(Unsupported field type: ${field.fieldType}. " +
                            "Please use the official Discord app for this field.)"
                        setTextColor(Color.WHITE)
                        setPadding(32, 16, 32, 16)
                    })
                }
            }
        }

        container.addView(Button(ctx).apply {
            text = "Submit Application"
            styleWhite()
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
