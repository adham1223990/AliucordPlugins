package com.aliucord.plugins.quests

import com.aliucord.Logger
import com.aliucord.Utils
import com.aliucord.api.SettingsAPI
import com.discord.utilities.time.TimeUtils
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import kotlin.math.min

object QuestManager {
    private val logger = Logger("InstantFinishQuests")
    private var scheduler: ScheduledExecutorService? = null

    private const val MIN_PROGRESS_STEP = 6.0
    private const val MAX_PROGRESS_STEP = 7.0
    private const val VIDEO_REPORT_DELAY_MS = 7500L
    private const val STREAM_POLL_DELAY_MS = 15000L

    fun startAutoRunner(settings: SettingsAPI) {
        stopAutoRunner()
        scheduler = Executors.newSingleThreadScheduledExecutor()
        scheduler?.scheduleWithFixedDelay({
            if (settings.getBool("auto_finish", false)) {
                try {
                    processAllAvailableQuests(settings)
                } catch (e: Exception) {
                    logger.error("Auto runner failed", e)
                }
            }
        }, 10, 3600, TimeUnit.SECONDS)
    }

    fun stopAutoRunner() {
        try {
            scheduler?.shutdownNow()
            scheduler = null
        } catch (e: Exception) {
            logger.error("Failed to stop auto runner", e)
        }
    }

    private fun processAllAvailableQuests(settings: SettingsAPI) {
        Utils.threadPool.execute {
            try {
                val response = QuestsApi.getQuests()
                val now = System.currentTimeMillis()

                val validQuests = response.quests.filter { quest ->
                    val expires = runCatching { TimeUtils.parseUTCDate(quest.config.expiresAt) }.getOrDefault(0L)
                    expires > now && quest.userStatus?.completedAt == null && quest.userStatus?.claimedAt == null
                }

                for (quest in validQuests) {
                    finishSingleQuest(quest, settings) { _, _ -> }
                    Thread.sleep((8000L..12000L).random())
                }
            } catch (e: Exception) {
                logger.error("processAllAvailableQuests failed", e)
            }
        }
    }

    fun finishSingleQuest(
        quest: Quest,
        settings: SettingsAPI,
        onUpdate: (Boolean, String) -> Unit
    ) {
        Utils.threadPool.execute {
            try {
                val tasks = (quest.config.taskConfigV2 ?: quest.config.taskConfig)?.tasks
                if (tasks == null || tasks.isEmpty()) {
                    Utils.mainThread.post { onUpdate(false, "Quest data unavailable") }
                    return@execute
                }

                val taskKey = tasks.keys.first()
                val task = tasks[taskKey] ?: run {
                    Utils.mainThread.post { onUpdate(false, "Task data unavailable") }
                    return@execute
                }

                if (quest.userStatus?.enrolledAt == null) {
                    quest.userStatus = QuestsApi.enroll(quest)
                }

                if (taskKey.contains("STREAM")) {
                    val altToken = settings.getString("alt_token", "")
                    val voiceId = settings.getString("voice_id", "")
                    val serverId = settings.getString("server_id", "")

                    if (altToken.isBlank() || voiceId.isBlank() || serverId.isBlank()) {
                        Utils.mainThread.post {
                            onUpdate(false, "Configure voice_id, server_id, and alt_token")
                        }
                        return@execute
                    }

                    runCatching { QuestsApi.enroll(quest, altToken) }

                    Utils.mainThread.post {
                        onUpdate(true, "Alt ready! Start streaming your screen now")
                        Utils.showToast("Please join Voice and start screen sharing")
                    }

                    val target = task.target.toDouble()
                    var isCompleted = false

                    while (!isCompleted) {
                        Thread.sleep(STREAM_POLL_DELAY_MS)

                        val refreshedQuests = QuestsApi.getQuests()
                        val currentQuest = refreshedQuests.quests.firstOrNull { it.id == quest.id }
                        val currentProgress = currentQuest?.userStatus?.progress?.get(taskKey)?.value?.toDouble() ?: 0.0

                        if (currentQuest?.userStatus?.completedAt != null || currentProgress >= target) {
                            quest.userStatus = currentQuest?.userStatus
                            isCompleted = true
                            Utils.mainThread.post {
                                onUpdate(true, "Completed!")
                                Utils.showToast("Quest completed successfully!")
                            }
                            return@execute
                        } else {
                            Utils.mainThread.post {
                                onUpdate(true, "Streaming... (${currentProgress.toInt()}/${target.toInt()})")
                            }
                        }
                    }

                } else if (taskKey.contains("VIDEO")) {
                    val target = task.target.toDouble()
                    var currentProgress = quest.userStatus?.progress?.get(taskKey)?.value?.toDouble() ?: 0.0

                    while (currentProgress < target) {
                        currentProgress = min(target, currentProgress + (MIN_PROGRESS_STEP..MAX_PROGRESS_STEP).random())
                        val updated = QuestsApi.reportVideoProgress(quest.id, currentProgress)
                        quest.userStatus = updated

                        if (updated.completedAt != null) {
                            Utils.mainThread.post { onUpdate(true, "Completed!") }
                            return@execute
                        }

                        Thread.sleep(VIDEO_REPORT_DELAY_MS)
                    }

                    val finalResponse = QuestsApi.getQuests()
                    val finishedQuest = finalResponse.quests.firstOrNull { it.id == quest.id }
                    if (finishedQuest?.userStatus?.completedAt != null) {
                        quest.userStatus = finishedQuest.userStatus
                        Utils.mainThread.post { onUpdate(true, "Completed!") }
                    } else {
                        Utils.mainThread.post { onUpdate(false, "Waiting for Discord verification") }
                    }

                } else {
                    Utils.mainThread.post { onUpdate(true, "Enrolled successfully") }
                }

            } catch (e: Exception) {
                logger.error("finishSingleQuest error", e)
                Utils.mainThread.post { onUpdate(false, e.message ?: "Failed to process quest") }
            }
        }
    }
}
