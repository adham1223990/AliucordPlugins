package com.adham1223990.serverapplicationfix

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Typeface
import android.text.InputFilter
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.TextView
import com.aliucord.Utils
import com.aliucord.api.SettingsAPI
import com.aliucord.fragments.SettingsPage

class ApplicationPage(
    private val settings: SettingsAPI?,
    private val guildId: Long,
    private val initialGuildName: String? = null,
    private val inviteCode: String? = null
) : SettingsPage() {

    private var loadedForm: VerificationFormResponse? = null
    private val fieldValueReaders = mutableListOf<Pair<VerificationField, () -> Any?>>()
    private val fieldValidators = mutableListOf<() -> String?>()

    @SuppressLint("SetTextI18n")
    override fun onViewBound(view: View) {
        super.onViewBound(view)
        val ctx = view.context
        val textColor = FormStyle.getTextColor(settings)

        setActionBarTitle(initialGuildName ?: "Server Application")
        setActionBarSubtitle("View Application Status & Apply")

        val container = linearLayout
        container.removeAllViews()
        container.setBackgroundColor(FormStyle.PAGE_BG_COLOR)
        val pad = FormStyle.dp(ctx, 16)
        container.setPadding(pad, pad, pad, pad)

        val statusTitle = createTextView(ctx, "Loading server application status...", 16f, true, textColor)
        container.addView(statusTitle)

        Utils.threadPool.execute {
            // Everything on this thread is guarded: an uncaught exception here used to leave the
            // page on "Loading..." forever. Any failure now shows up as a normal error card.
            var formResultVar: Result<VerificationFormResponse> =
                Result.failure(IllegalStateException("Could not load the application form"))
            var savedRecordVar: SavedApplicationRecord? = null
            var refreshedStatusVar: JoinRequestResult? = null
            try {
                formResultVar = ApplicationApi.fetchMemberVerificationForm(guildId, inviteCode)
                savedRecordVar = ApplicationStore.getByGuildId(settings, guildId)
                // Status comes from the client's own join-request store (no extra request).
                refreshedStatusVar = ApplicationApi.fetchJoinRequestStatus(
                    guildId,
                    savedRecordVar?.requestId,
                    allowNetwork = false
                )
            } catch (t: Throwable) {
                formResultVar = Result.failure(t)
            }
            val formResult = formResultVar
            // The cooldown route does not exist in the Discord client, so there is nothing to fetch.
            val cooldownSec = 0L
            val savedRecord = savedRecordVar
            val refreshedStatus = refreshedStatusVar

            Utils.mainThread.post {
                if (!isAdded) return@post
                container.removeAllViews()

                val form = formResult.getOrNull()
                loadedForm = form

                val effectiveName = form?.guildName ?: savedRecord?.guildName ?: initialGuildName ?: "Server $guildId"
                val effectiveStatus = refreshedStatus?.applicationStatus
                    ?: form?.joinRequestStatus
                    ?: savedRecord?.status
                    ?: "STARTED"
                val effectiveRequestId = refreshedStatus?.requestId
                    ?: form?.requestId
                    ?: savedRecord?.requestId
                val effectiveReason = refreshedStatus?.rejectionReason ?: savedRecord?.rejectionReason
                val effectiveInterviewId = refreshedStatus?.interviewChannelId ?: savedRecord?.interviewChannelId

                setActionBarTitle(effectiveName)
                setActionBarSubtitle("Status: $effectiveStatus")

                if (form != null) {
                    ApplicationStore.upsert(
                        settings,
                        SavedApplicationRecord(
                            guildId = guildId,
                            guildName = effectiveName,
                            guildIcon = form.guildIcon ?: savedRecord?.guildIcon,
                            requestId = effectiveRequestId,
                            status = effectiveStatus,
                            rejectionReason = effectiveReason,
                            interviewChannelId = effectiveInterviewId,
                            inviteCode = inviteCode ?: savedRecord?.inviteCode,
                            formDescription = form.description ?: savedRecord?.formDescription
                        )
                    )
                }

                // Header Status Card
                container.addView(
                    buildStatusHeaderCard(
                        ctx = ctx,
                        guildName = effectiveName,
                        status = effectiveStatus,
                        description = form?.description ?: savedRecord?.formDescription,
                        rejectionReason = effectiveReason,
                        interviewChannelId = effectiveInterviewId,
                        cooldownSeconds = cooldownSec,
                        textColor = textColor
                    )
                )

                if (form == null) {
                    val errorCard = LinearLayout(ctx).apply {
                        orientation = LinearLayout.VERTICAL
                        background = FormStyle.roundedBg(ctx, FormStyle.CARD_BG_COLOR, 10, FormStyle.STROKE_COLOR, 1)
                        setPadding(pad, pad, pad, pad)
                        layoutParams = LinearLayout.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.WRAP_CONTENT
                        ).apply { topMargin = FormStyle.dp(ctx, 12) }
                    }
                    val errMsg = formResult.exceptionOrNull()?.message
                        ?: "Could not load verification form for this server."
                    errorCard.addView(
                        createTextView(
                            ctx,
                            "Unable to load form fields:\n$errMsg",
                            14f,
                            false,
                            textColor
                        )
                    )
                    val retryBtn = createActionButton(ctx, "Retry Loading Form", FormStyle.BLURPLE_COLOR, textColor) {
                        onViewBound(view)
                    }
                    errorCard.addView(retryBtn)
                    container.addView(errorCard)
                    FormStyle.applyTextColorRecursively(container, settings)
                    return@post
                }

                // Render Form Fields
                fieldValueReaders.clear()
                fieldValidators.clear()

                if (form.fields.isEmpty()) {
                    val emptyCard = LinearLayout(ctx).apply {
                        orientation = LinearLayout.VERTICAL
                        background = FormStyle.roundedBg(ctx, FormStyle.CARD_BG_COLOR, 10, FormStyle.STROKE_COLOR, 1)
                        setPadding(pad, pad, pad, pad)
                        layoutParams = LinearLayout.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.WRAP_CONTENT
                        ).apply { topMargin = FormStyle.dp(ctx, 12) }
                    }
                    emptyCard.addView(
                        createTextView(
                            ctx,
                            "This server has no custom questions, or you can submit your application directly.",
                            14f,
                            false,
                            textColor
                        )
                    )
                    container.addView(emptyCard)
                } else {
                    form.fields.forEachIndexed { index, field ->
                        container.addView(buildFieldCard(ctx, index, field, textColor))
                    }
                }

                // Action Buttons (Submit / Re-apply / Withdraw)
                container.addView(
                    buildActionsSection(
                        ctx = ctx,
                        view = view,
                        form = form,
                        effectiveName = effectiveName,
                        effectiveStatus = effectiveStatus,
                        textColor = textColor
                    )
                )

                FormStyle.applyTextColorRecursively(container, settings)
            }
        }
    }

    @SuppressLint("SetTextI18n")
    private fun buildStatusHeaderCard(
        ctx: Context,
        guildName: String,
        status: String,
        description: String?,
        rejectionReason: String?,
        interviewChannelId: String?,
        cooldownSeconds: Long,
        textColor: Int
    ): View {
        val pad = FormStyle.dp(ctx, 16)
        return LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            background = FormStyle.roundedBg(ctx, FormStyle.CARD_BG_COLOR, 12, FormStyle.STROKE_COLOR, 1)
            setPadding(pad, pad, pad, pad)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )

            addView(createTextView(ctx, guildName, 18f, true, textColor))
            addView(createTextView(ctx, "Guild ID: $guildId", 12f, false, textColor).apply {
                setPadding(0, FormStyle.dp(ctx, 2), 0, FormStyle.dp(ctx, 8))
            })

            val badge = createTextView(ctx, "Application Status: ${status.uppercase()}", 13f, true, textColor).apply {
                background = FormStyle.roundedBg(ctx, FormStyle.statusBadgeColor(status), 6)
                val hPad = FormStyle.dp(ctx, 10)
                val vPad = FormStyle.dp(ctx, 6)
                setPadding(hPad, vPad, hPad, vPad)
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                )
            }
            addView(badge)

            if (!description.isNullOrBlankSafe()) {
                addView(createTextView(ctx, description, 14f, false, textColor).apply {
                    setPadding(0, FormStyle.dp(ctx, 10), 0, 0)
                })
            }

            if (!rejectionReason.isNullOrBlankSafe()) {
                addView(createTextView(ctx, "Rejection Reason: $rejectionReason", 13f, true, textColor).apply {
                    setPadding(0, FormStyle.dp(ctx, 8), 0, 0)
                })
            }

            if (!interviewChannelId.isNullOrBlankSafe()) {
                addView(createTextView(ctx, "Interview Channel ID: $interviewChannelId", 13f, false, textColor).apply {
                    setPadding(0, FormStyle.dp(ctx, 6), 0, 0)
                })
            }

            if (cooldownSeconds > 0L) {
                addView(createTextView(ctx, "Cooldown remaining: ${cooldownSeconds}s", 13f, true, textColor).apply {
                    setPadding(0, FormStyle.dp(ctx, 6), 0, 0)
                })
            }
        }
    }

    @SuppressLint("SetTextI18n")
    private fun buildFieldCard(
        ctx: Context,
        index: Int,
        field: VerificationField,
        textColor: Int
    ): View {
        val pad = FormStyle.dp(ctx, 14)
        val card = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            background = FormStyle.roundedBg(ctx, FormStyle.CARD_BG_COLOR, 10, FormStyle.STROKE_COLOR, 1)
            setPadding(pad, pad, pad, pad)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = FormStyle.dp(ctx, 12)
            }
        }

        val reqSuffix = if (field.required) " *" else ""
        card.addView(
            createTextView(
                ctx,
                "${index + 1}. ${field.label}$reqSuffix",
                15f,
                true,
                textColor
            )
        )

        if (!field.description.isNullOrBlankSafe()) {
            card.addView(
                createTextView(ctx, field.description, 13f, false, textColor).apply {
                    setPadding(0, FormStyle.dp(ctx, 4), 0, FormStyle.dp(ctx, 6))
                }
            )
        }

        when (field.fieldType.uppercase()) {
            "TERMS" -> {
                if (field.values.isNotEmpty()) {
                    val rulesBox = LinearLayout(ctx).apply {
                        orientation = LinearLayout.VERTICAL
                        background = FormStyle.roundedBg(ctx, FormStyle.INPUT_BG_COLOR, 8)
                        val rPad = FormStyle.dp(ctx, 10)
                        setPadding(rPad, rPad, rPad, rPad)
                        layoutParams = LinearLayout.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.WRAP_CONTENT
                        ).apply {
                            topMargin = FormStyle.dp(ctx, 8)
                            bottomMargin = FormStyle.dp(ctx, 8)
                        }
                    }
                    field.values.forEachIndexed { ruleIdx, ruleText ->
                        rulesBox.addView(
                            createTextView(ctx, "${ruleIdx + 1}. $ruleText", 13f, false, textColor).apply {
                                setPadding(0, FormStyle.dp(ctx, 3), 0, FormStyle.dp(ctx, 3))
                            }
                        )
                    }
                    card.addView(rulesBox)
                }

                val checkBox = CheckBox(ctx).apply {
                    text = "I have read and agree to the rules"
                    setTextColor(textColor)
                    textSize = 14f
                }
                card.addView(checkBox)

                fieldValueReaders.add(field to { checkBox.isChecked })
                fieldValidators.add {
                    if (field.required && !checkBox.isChecked) {
                        "Please accept the server rules (${field.label})."
                    } else null
                }
            }

            "MULTIPLE_CHOICE" -> {
                val radioGroup = RadioGroup(ctx).apply {
                    orientation = RadioGroup.VERTICAL
                    layoutParams = LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT
                    ).apply { topMargin = FormStyle.dp(ctx, 8) }
                }

                val options = if (field.choices.isNotEmpty()) field.choices else field.values
                options.forEachIndexed { optIdx, choiceText ->
                    val rb = RadioButton(ctx).apply {
                        id = View.generateViewId()
                        tag = optIdx
                        text = choiceText
                        setTextColor(textColor)
                        textSize = 14f
                    }
                    radioGroup.addView(rb)
                }
                card.addView(radioGroup)

                fieldValueReaders.add(field to {
                    val checkedId = radioGroup.checkedRadioButtonId
                    val rb = if (checkedId != -1) radioGroup.findViewById<RadioButton>(checkedId) else null
                    rb?.tag as? Int ?: 0
                })
                fieldValidators.add {
                    if (field.required && radioGroup.checkedRadioButtonId == -1) {
                        "Please select an option for: ${field.label}"
                    } else null
                }
            }

            "VERIFICATION" -> {
                val verifyCheck = CheckBox(ctx).apply {
                    text = "Confirm verification requirement"
                    isChecked = true
                    setTextColor(textColor)
                    textSize = 14f
                }
                card.addView(verifyCheck)
                fieldValueReaders.add(field to { verifyCheck.isChecked })
                fieldValidators.add {
                    if (field.required && !verifyCheck.isChecked) {
                        "Please confirm verification requirement."
                    } else null
                }
            }

            else -> {
                val isParagraph = field.fieldType.equals("PARAGRAPH", ignoreCase = true)
                val input = EditText(ctx).apply {
                    hint = field.placeholder ?: "Write your answer here..."
                    setTextColor(textColor)
                    setHintTextColor(FormStyle.withAlpha(textColor, 150))
                    textSize = 14f
                    background = FormStyle.roundedBg(ctx, FormStyle.INPUT_BG_COLOR, 8, FormStyle.STROKE_COLOR, 1)
                    val iPad = FormStyle.dp(ctx, 12)
                    setPadding(iPad, iPad, iPad, iPad)
                    minLines = if (isParagraph) 4 else 1
                    maxLines = if (isParagraph) 8 else 2
                    gravity = Gravity.TOP or Gravity.START
                    filters = arrayOf(InputFilter.LengthFilter(field.maxLength.coerceAtLeast(50)))
                    layoutParams = LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT
                    ).apply { topMargin = FormStyle.dp(ctx, 8) }
                }
                card.addView(input)

                fieldValueReaders.add(field to { input.text?.toString()?.trim() ?: "" })
                fieldValidators.add {
                    val value = input.text?.toString()?.trim() ?: ""
                    if (field.required && value.isEmpty()) {
                        "Please answer: ${field.label}"
                    } else null
                }
            }
        }

        return card
    }

    private fun buildActionsSection(
        ctx: Context,
        view: View,
        form: VerificationFormResponse,
        effectiveName: String,
        effectiveStatus: String,
        textColor: Int
    ): View {
        val wrapper = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = FormStyle.dp(ctx, 16)
                bottomMargin = FormStyle.dp(ctx, 28)
            }
        }

        val submitBtnText = if (effectiveStatus.equals("SUBMITTED", true) || effectiveStatus.equals("PENDING", true)) {
            "Update / Resubmit Application"
        } else {
            "Submit Server Application"
        }

        val submitBtn = createActionButton(ctx, submitBtnText, FormStyle.GREEN_COLOR, textColor) {
            for (validator in fieldValidators) {
                val error = validator.invoke()
                if (error != null) {
                    Utils.showToast(error)
                    return@createActionButton
                }
            }

            val payloadPairs = fieldValueReaders.map { (field, reader) ->
                field to reader.invoke()
            }

            Utils.showToast("Submitting application...")
            Utils.threadPool.execute {
                val res = ApplicationApi.submitGuildApplication(
                    guildId = guildId,
                    version = form.version,
                    fieldsWithResponses = payloadPairs
                )
                Utils.mainThread.post {
                    if (res.ok) {
                        val newStatus = res.applicationStatus ?: "PENDING"
                        ApplicationStore.upsert(
                            settings,
                            SavedApplicationRecord(
                                guildId = guildId,
                                guildName = effectiveName,
                                guildIcon = form.guildIcon,
                                requestId = res.requestId ?: form.requestId,
                                status = newStatus,
                                rejectionReason = res.rejectionReason,
                                interviewChannelId = res.interviewChannelId,
                                submittedAt = res.submittedAt,
                                actionedAt = res.actionedAt,
                                inviteCode = inviteCode,
                                formDescription = form.description
                            )
                        )
                        Utils.showToast("Application submitted! Status: $newStatus")
                        if (isAdded) onViewBound(view)
                    } else {
                        Utils.showToast("Submission failed: ${res.errorMessage}")
                    }
                }
            }
        }
        wrapper.addView(submitBtn)

        val resetBtn = createActionButton(ctx, "Reset Application Status (Start Fresh)", FormStyle.BLURPLE_COLOR, textColor) {
            Utils.threadPool.execute {
                val res = ApplicationApi.resetGuildApplication(guildId)
                Utils.mainThread.post {
                    if (res.ok) {
                        ApplicationStore.upsert(
                            settings,
                            SavedApplicationRecord(
                                guildId = guildId,
                                guildName = effectiveName,
                                guildIcon = form.guildIcon,
                                requestId = res.requestId ?: form.requestId,
                                status = res.applicationStatus ?: "STARTED",
                                inviteCode = inviteCode,
                                formDescription = form.description
                            )
                        )
                        Utils.showToast("Application reset to STARTED")
                        if (isAdded) onViewBound(view)
                    } else {
                        Utils.showToast("Reset failed: ${res.errorMessage}")
                    }
                }
            }
        }
        wrapper.addView(resetBtn)

        val withdrawBtn = createActionButton(ctx, "Withdraw / Cancel Application", FormStyle.RED_COLOR, textColor) {
            Utils.threadPool.execute {
                val res = ApplicationApi.withdrawGuildApplication(guildId)
                Utils.mainThread.post {
                    if (res.ok) {
                        ApplicationStore.upsert(
                            settings,
                            SavedApplicationRecord(
                                guildId = guildId,
                                guildName = effectiveName,
                                guildIcon = form.guildIcon,
                                status = "WITHDRAWN",
                                inviteCode = inviteCode,
                                formDescription = form.description
                            )
                        )
                        Utils.showToast("Application withdrawn successfully")
                        if (isAdded) onViewBound(view)
                    } else {
                        Utils.showToast("Withdraw failed: ${res.errorMessage}")
                    }
                }
            }
        }
        wrapper.addView(withdrawBtn)

        return wrapper
    }

    private fun createTextView(
        ctx: Context,
        textValue: String,
        sizeSp: Float,
        bold: Boolean,
        color: Int
    ): TextView {
        return TextView(ctx).apply {
            text = textValue
            textSize = sizeSp
            setTextColor(color)
            setTypeface(typeface, if (bold) Typeface.BOLD else Typeface.NORMAL)
        }
    }

    private fun createActionButton(
        ctx: Context,
        label: String,
        bgColor: Int,
        textColor: Int,
        onClick: () -> Unit
    ): TextView {
        return TextView(ctx).apply {
            text = label
            textSize = 14f
            setTextColor(textColor)
            setTypeface(typeface, Typeface.BOLD)
            gravity = Gravity.CENTER
            background = FormStyle.roundedBg(ctx, bgColor, 8)
            val vPad = FormStyle.dp(ctx, 12)
            val hPad = FormStyle.dp(ctx, 16)
            setPadding(hPad, vPad, hPad, vPad)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = FormStyle.dp(ctx, 10)
            }
            setOnClickListener { onClick() }
        }
    }
}
