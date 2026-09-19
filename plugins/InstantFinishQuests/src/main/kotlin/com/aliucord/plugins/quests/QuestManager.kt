package com.aliucord.plugins.quests

import com.aliucord.Logger
import com.aliucord.Utils
import com.aliucord.api.SettingsAPI
import com.discord.stores.StoreStream
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

    private const val HEARTBEAT_DELAY_MS = 30_000L
    private const val HEARTBEAT_MAX_STALLED_ATTEMPTS = 5

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

                if (!response.enrollmentBlockedUntil.isNullOrEmpty()) {
                    val blockedUntil = runCatching { TimeUtils.parseUTCDate(response.enrollmentBlockedUntil) }.getOrDefault(0L)
                    if (blockedUntil > now) {
                        logger.warn("Quest enrollment is blocked until ${response.enrollmentBlockedUntil}, skipping this cycle")
                        return@execute
                    }
                }

                val validQuests = response.quests.filter { quest ->
                    val expires = runCatching { TimeUtils.parseUTCDate(quest.config.expiresAt) }.getOrDefault(0L)
                    expires > now && quest.userStatus?.completedAt == null && quest.userStatus?.claimedAt == null
                }

                logger.info("Auto runner found ${validQuests.size} quest(s) to process")

                for (quest in validQuests) {
                    val (ok, msg) = finishSingleQuest(quest, settings)
                    logger.info("Quest ${quest.id}: ok=$ok msg=$msg")
                    Thread.sleep((8000L..12000L).random())
                }
            } catch (e: Exception) {
                logger.error("processAllAvailableQuests failed", e)
            }
        }
    }

    fun finishSingleQuest(quest: Quest, settings: SettingsAPI): Pair<Boolean, String> {
        return try {
            val tasks = (quest.config.taskConfigV2 ?: quest.config.taskConfig)?.tasks
                ?: return Pair(false, "Quest data unavailable")
            val taskKey = tasks.keys.firstOrNull() ?: return Pair(false, "Unknown quest type")
            val task = tasks[taskKey] ?: return Pair(false, "Task data unavailable")

            when {
                taskKey.contains("PLAY_ACTIVITY") -> finishHeartbeatQuest(quest, taskKey, task, useOwnUserIdAsKey = true)
                taskKey.contains("STREAM") -> finishStreamOnlyQuest(quest, settings)
                taskKey.contains("PLAY") -> finishHeartbeatQuest(quest, taskKey, task, useOwnUserIdAsKey = false)
                taskKey.contains("VIDEO") -> finishVideoQuest(quest, taskKey, task)
                else -> {
                    var status = quest.userStatus
                    if (status?.enrolledAt == null) {
                        status = QuestsApi.enroll(quest)
                        quest.userStatus = status
                    }
                    Pair(true, "Quest enrolled successfully")
                }
            }
        } catch (e: QuestApiException) {
            logger.error("finishSingleQuest failed for ${quest.id}: ${e.message}", e)
            Pair(false, e.message ?: "Discord rejected the request")
        } catch (e: Exception) {
            logger.error("finishSingleQuest failed for ${quest.id}", e)
            Pair(false, "Could not process quest: ${e.message}")
        }
    }

    private fun finishVideoQuest(quest: Quest, taskKey: String, task: QuestTask): Pair<Boolean, String> {
        var status = quest.userStatus
        if (status?.enrolledAt == null) {
            status = QuestsApi.enroll(quest)
            quest.userStatus = status
        }

        if (status.completedAt != null) {
            return Pair(true, "Quest already complete")
        }

        val target = task.target.toDouble()
        var lastReported = status.progress?.get(taskKey)?.value?.toDouble() ?: 0.0
        var attemptsWithoutProgress = 0

        while (true) {
            val nextTimestamp = min(target, lastReported + MIN_PROGRESS_STEP + (0..(MAX_PROGRESS_STEP - MIN_PROGRESS_STEP).toInt()).random())

            val updated = QuestsApi.reportVideoProgress(quest.id, nextTimestamp)
            quest.userStatus = updated

            val serverProgress = updated.progress?.get(taskKey)?.value?.toDouble() ?: nextTimestamp
            val newReported = maxOf(nextTimestamp, serverProgress)

            if (updated.completedAt != null) {
                logger.info("Video quest ${quest.id} completed at progress=$newReported/$target")
                return Pair(true, "Quest completed!")
            }

            if (newReported <= lastReported) {
                attemptsWithoutProgress++
                if (attemptsWithoutProgress >= 5) {
                    logger.error("Video quest ${quest.id} stuck at progress=$newReported/$target after 5 attempts", null)
                    return Pair(false, "Stuck at $newReported/$target — Discord did not mark quest complete")
                }
            } else {
                attemptsWithoutProgress = 0
            }

            lastReported = newReported
            Thread.sleep(VIDEO_REPORT_DELAY_MS)
        }
    }

    private fun finishHeartbeatQuest(
        quest: Quest,
        taskKey: String,
        task: QuestTask,
        useOwnUserIdAsKey: Boolean
    ): Pair<Boolean, String> {
        var status = quest.userStatus
        if (status?.enrolledAt == null) {
            status = QuestsApi.enroll(quest)
            quest.userStatus = status
        }

        if (status.completedAt != null) {
            return Pair(true, "Quest already complete")
        }

        val keyComponent = if (useOwnUserIdAsKey) {
            currentUserId() ?: quest.id
        } else {
            quest.id
        }
        val streamKey = "call:$keyComponent:1"

        val target = task.target.toDouble()
        var attemptsWithoutProgress = 0
        var lastProgress = status.progress?.get(taskKey)?.value?.toDouble() ?: 0.0

        while (true) {
            val updated = QuestsApi.heartbeat(quest.id, streamKey)
            quest.userStatus = updated

            val newProgress = updated.progress?.get(taskKey)?.value?.toDouble() ?: lastProgress

            if (updated.completedAt != null || newProgress >= target) {
                logger.info("Heartbeat quest ${quest.id} completed at progress=$newProgress/$target")
                return Pair(true, "Quest completed!")
            }

            if (newProgress <= lastProgress) {
                attemptsWithoutProgress++
                if (attemptsWithoutProgress >= HEARTBEAT_MAX_STALLED_ATTEMPTS) {
                    logger.error("Heartbeat quest ${quest.id} stuck at $newProgress/$target — Discord not crediting progress", null)
                    return Pair(false, "Discord did not credit progress ($newProgress/$target) — this quest type may require the game/activity itself to be genuinely open")
                }
            } else {
                attemptsWithoutProgress = 0
            }

            lastProgress = newProgress
            Thread.sleep(HEARTBEAT_DELAY_MS)
        }
    }

    private fun finishStreamOnlyQuest(quest: Quest, settings: SettingsAPI): Pair<Boolean, String> {
        val altToken = settings.getString("alt_token", "")
        val voiceId = settings.getString("voice_id", "")
        val serverId = settings.getString("server_id", "")

        if (altToken.isBlank() || voiceId.isBlank() || serverId.isBlank()) {
            return Pair(false, "Stream settings missing (voice_id / server_id / alt_token)")
        }

        if (quest.userStatus?.enrolledAt == null) {
            quest.userStatus = QuestsApi.enroll(quest)
        }
        runCatching { QuestsApi.enroll(quest, altToken) }
            .onFailure { logger.error("Alt account enroll failed", it) }

        return Pair(
            true,
            "Enrolled — join voice channel $voiceId and start streaming manually to complete this quest"
        )
    }

    private fun currentUserId(): String? = runCatching {
        StoreStream.getUsers().me?.id?.toString()
    }.getOrNull()
}
