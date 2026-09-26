package com.adham1223990.serverapplicationfix

import android.content.Context
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

/** Shows a guild's member-verification form and submits it through our own spoofed requests
 *  (see ApplicationApi.kt), instead of Discord's native screen which uses the app's real,
 *  un-spoofed internal REST client and therefore gets rejected on an outdated build. */
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

    private fun reload() {
        loading = true
        error = null
        render()
        Utils.threadPool.execute {
            val result = runCatching { ApplicationApi.fetchForm(guildId) }
            Utils.mainThread.post {
                if (!isAdded) return@post
                loading = false
                result.onSuccess { form = it }
                    .onFailure {
                        logger.error("Failed to load application form for guild $guildId", it)
                        error = it.message ?: it.toString()
                    }
                render()
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
                setPadding(32, 32, 32, 32)
            })

            error != null -> {
                container.addView(TextView(ctx).apply {
                    text = "Failed to load the application:\n$error"
                    setPadding(32, 32, 32, 16)
                })
                container.addView(Button(ctx).apply {
                    text = "Retry"
                    setOnClickListener { reload() }
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
                            setPadding(32, 8, 32, 8)
                        })
                    }
                    container.addView(CheckBox(ctx).apply {
                        text = if (field.required) "I have read and agree (required)" else "I have read and agree"
                        setPadding(32, 8, 32, 8)
                        setOnCheckedChangeListener { _, checked ->
                            field.response = if (checked) "true" else "false"
                        }
                    })
                }

                "TEXT_INPUT", "PARAGRAPH" -> {
                    container.addView(TextView(ctx).apply {
                        text = if (field.required) "${field.label} *" else field.label
                        setPadding(32, 16, 32, 4)
                    })
                    container.addView(EditText(ctx).apply {
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
                        setPadding(32, 16, 32, 4)
                    })
                    val group = RadioGroup(ctx).apply { orientation = RadioGroup.VERTICAL }
                    for (choice in field.choices) {
                        group.addView(RadioButton(ctx).apply {
                            text = choice
                            id = View.generateViewId()
                        })
                    }
                    group.setOnCheckedChangeListener { rg, checkedId ->
                        for (i in 0 until rg.childCount) {
                            if (rg.getChildAt(i).id == checkedId) {
                                field.response = i
                                break
                            }
                        }
                    }
                    container.addView(group)
                }

                else -> {
                    container.addView(TextView(ctx).apply {
                        text = "${field.label}\n(Unsupported field type: ${field.fieldType}. " +
                            "Please use the official Discord app for this field.)"
                        setPadding(32, 16, 32, 16)
                    })
                }
            }
        }

        container.addView(Button(ctx).apply {
            text = "Submit Application"
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
        submitting = true
        Utils.showToast("Submitting application…")

        Utils.threadPool.execute {
            val result = runCatching { ApplicationApi.submitForm(guildId, f) }
            Utils.mainThread.post {
                submitting = false
                if (!isAdded) return@post
                result
                    .onSuccess {
                        Utils.showToast("Application submitted!")
                        parentFragmentManager.popBackStack()
                    }
                    .onFailure {
                        logger.error("Failed to submit application for guild $guildId", it)
                        Utils.showToast("Failed to submit: ${it.message}", true)
                        render()
                    }
            }
        }
    }
}
