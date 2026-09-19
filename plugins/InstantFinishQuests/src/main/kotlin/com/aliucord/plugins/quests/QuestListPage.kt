package com.aliucord.plugins.quests

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.core.content.res.ResourcesCompat
import com.aliucord.Constants
import com.aliucord.Utils
import com.aliucord.api.SettingsAPI
import com.aliucord.fragments.SettingsPage
import com.aliucord.utils.DimenUtils
import com.discord.utilities.time.TimeUtils

class QuestListPage(private val settings: SettingsAPI) : SettingsPage() {

    override fun onViewBound(view: View) {
        super.onViewBound(view)
        loadQuestsList(view.context)
    }

    private fun loadQuestsList(context: Context) {
        setActionBarTitle("Loading Quests...")
        linearLayout.removeAllViews()

        Utils.threadPool.execute {
            try {
                val questsResponse = QuestsApi.getQuests()
                val now = System.currentTimeMillis()

                val activeQuests = questsResponse.quests.filter {
                    val expires = runCatching { TimeUtils.parseUTCDate(it.config.expiresAt) }.getOrDefault(0L)
                    expires > now && it.userStatus?.claimedAt == null
                }

                Utils.mainThread.post {
                    setActionBarTitle("Instant Finish Quests")

                    // زر Refresh علوي لتحديث الكويستات يدوياً في أي لحظة
                    val refreshBtn = TextView(context).apply {
                        text = "↻ Refresh Quests"
                        setTextColor(Color.parseColor("#5865F2"))
                        textSize = 14f
                        gravity = Gravity.CENTER
                        setPadding(0, DimenUtils.dpToPx(8), 0, DimenUtils.dpToPx(8))
                        setOnClickListener { loadQuestsList(context) }
                    }
                    linearLayout.addView(refreshBtn)

                    if (activeQuests.isEmpty()) {
                        val noQuestsView = TextView(context).apply {
                            text = "No active quests available."
                            setTextColor(Color.WHITE)
                            textSize = 16f
                            gravity = Gravity.CENTER
                            setPadding(16, 64, 16, 16)
                        }
                        linearLayout.addView(noQuestsView)
                    } else {
                        activeQuests.forEach { quest ->
                            addQuestCard(context, quest)
                        }
                    }
                }
            } catch (e: Exception) {
                Utils.mainThread.post {
                    setActionBarTitle("Instant Finish Quests")
                    val errorView = TextView(context).apply {
                        text = "Failed to load quests. Click to retry."
                        setTextColor(Color.parseColor("#ED4245"))
                        gravity = Gravity.CENTER
                        setPadding(16, 64, 16, 16)
                        setOnClickListener { loadQuestsList(context) }
                    }
                    linearLayout.addView(errorView)
                }
            }
        }
    }

    private fun addQuestCard(context: Context, quest: Quest) {
        val card = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#2F3136"))
                cornerRadius = DimenUtils.dpToPx(8).toFloat()
            }
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                setMargins(
                    DimenUtils.dpToPx(12),
                    DimenUtils.dpToPx(8),
                    DimenUtils.dpToPx(12),
                    DimenUtils.dpToPx(8)
                )
            }
            setPadding(
                DimenUtils.dpToPx(16),
                DimenUtils.dpToPx(16),
                DimenUtils.dpToPx(16),
                DimenUtils.dpToPx(16)
            )
        }

        val title = TextView(context).apply {
            text = quest.config.messages.questName
            setTextColor(Color.WHITE)
            textSize = 18f
            typeface = ResourcesCompat.getFont(context, Constants.Fonts.whitney_semibold)
        }
        card.addView(title)

        val task = (quest.config.taskConfigV2 ?: quest.config.taskConfig)?.tasks?.values?.firstOrNull()
        val target = task?.target ?: 1
        val progressValue = quest.userStatus?.progress?.values?.firstOrNull()?.value ?: 0

        val isCompleted = quest.userStatus?.completedAt != null
        val isEnrolled = quest.userStatus?.enrolledAt != null

        val statusStr = when {
            isCompleted -> "Completed"
            isEnrolled -> "In Progress"
            else -> "Not Enrolled"
        }

        val statusColor = when {
            isCompleted -> "#57F287"
            isEnrolled -> "#5865F2"
            else -> "#FAA61A"
        }

        val statusText = TextView(context).apply {
            text = "Status: $statusStr"
            setTextColor(Color.parseColor(statusColor))
            textSize = 14f
            setPadding(0, DimenUtils.dpToPx(8), 0, DimenUtils.dpToPx(4))
        }
        card.addView(statusText)

        val progressText = TextView(context).apply {
            text = "Progress: $progressValue / $target"
            setTextColor(Color.parseColor("#B9BBBE"))
            textSize = 14f
            setPadding(0, 0, 0, DimenUtils.dpToPx(8))
        }
        card.addView(progressText)

        val progressBar = ProgressBar(context, null, android.R.attr.progressBarStyleHorizontal).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                DimenUtils.dpToPx(10)
            ).apply {
                setMargins(0, 0, 0, DimenUtils.dpToPx(14))
            }
            max = target
            progress = progressValue
            progressDrawable.setColorFilter(Color.parseColor(statusColor), android.graphics.PorterDuff.Mode.SRC_IN)
        }
        card.addView(progressBar)

        val finishBtn = TextView(context).apply {
            text = if (isCompleted) "Quest Completed" else "Instantly finish this quest"
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            isEnabled = !isCompleted
            background = GradientDrawable().apply {
                setColor(Color.parseColor(if (isCompleted) "#4F545C" else "#5865F2"))
                cornerRadius = DimenUtils.dpToPx(4).toFloat()
            }
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                DimenUtils.dpToPx(40)
            )
        }
        card.addView(finishBtn)
        linearLayout.addView(card)

        if (!isCompleted) {
            finishBtn.setOnClickListener {
                finishBtn.isEnabled = false
                finishBtn.alpha = 0.6f
                finishBtn.text = "Starting..."

                QuestManager.finishSingleQuest(quest, settings) { success, message ->
                    finishBtn.text = message
                    if (success && message == "Completed!") {
                        finishBtn.background = GradientDrawable().apply {
                            setColor(Color.parseColor("#4F545C"))
                            cornerRadius = DimenUtils.dpToPx(4).toFloat()
                        }
                        statusText.text = "Status: Completed"
                        statusText.setTextColor(Color.parseColor("#57F287"))
                        progressBar.progress = target
                        progressText.text = "Progress: $target / $target"
                    } else if (success && message.contains("Tracking")) {
                        // تحديث النسبة الحية أثناء البث
                        statusText.text = "Status: In Progress"
                        statusText.setTextColor(Color.parseColor("#5865F2"))
                    } else if (!success) {
                        finishBtn.background = GradientDrawable().apply {
                            setColor(Color.parseColor("#ED4245"))
                            cornerRadius = DimenUtils.dpToPx(4).toFloat()
                        }
                        finishBtn.isEnabled = true
                        finishBtn.alpha = 1f
                    }
                }
            }
        }
    }
}
