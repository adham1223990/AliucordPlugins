package com.aliucord.plugins.quests

import com.aliucord.Http
import com.aliucord.Logger
import com.aliucord.utils.GsonUtils
import com.discord.utilities.rest.RestAPI

object QuestsApi {
    private val logger = Logger("InstantFinishQuests")

    private val defaultAuthToken: String
        get() = runCatching { RestAPI.AppHeadersProvider.INSTANCE.authToken }.getOrDefault("")

    fun getQuests(): QuestsResponse {
        return runWithRetry { 
            Http.Request.newDiscordRNRequest("/quests/@me", "GET")
                .setHeader("Authorization", defaultAuthToken)
                .execute()
                .readJson() 
        }
    }

    fun enroll(quest: Quest, customToken: String? = null): QuestUserStatus {
        val body = EnrollQuestRequest(
            trafficMetadataRaw = quest.trafficMetadataRaw,
            trafficMetadataSealed = quest.trafficMetadataSealed
        )
        return post("/quests/${quest.id}/enroll", body, customToken)
    }

    fun reportVideoProgress(questId: String, timestamp: Double): QuestUserStatus {
        return post("/quests/$questId/video-progress", VideoProgressRequest(timestamp))
    }

    fun heartbeat(questId: String, streamKey: String): QuestUserStatus {
        return post("/quests/$questId/heartbeat", HeartbeatRequest(streamKey))
    }

    fun claimReward(quest: Quest, captchaSolution: QuestCaptchaSolution? = null): QuestUserStatus {
        val body = ClaimRewardRequest(
            platform = quest.config.rewardsConfig.platforms.firstOrNull() ?: 0,
            trafficMetadataSealed = quest.trafficMetadataSealed
        )
        val request = Http.Request.newDiscordRNRequest(
            "/quests/${quest.id}/claim-reward",
            "POST"
        ).setHeader("Authorization", defaultAuthToken)

        if (captchaSolution != null) {
            request
                .setHeader("x-captcha-key", captchaSolution.key)
                .setHeader("x-captcha-rqtoken", captchaSolution.rqtoken)
                .setHeader("x-captcha-session-id", captchaSolution.sessionId)
        }
        return runWithRetry {
            request.executeWithJson(GsonUtils.gsonRestApi, body).readJson()
        }
    }

    private inline fun <reified T> post(path: String, body: Any, customToken: String? = null): T {
        return runWithRetry {
            val token = if (!customToken.isNullOrBlank()) customToken else defaultAuthToken
            val request = Http.Request.newDiscordRNRequest(path, "POST")
                .setHeader("Authorization", token)

            request.executeWithJson(GsonUtils.gsonRestApi, body).readJson()
        }
    }

    private inline fun <T> runWithRetry(attempt: () -> T): T {
        var lastError: QuestApiException? = null
        repeat(3) { attemptIndex ->
            try {
                return attempt()
            } catch (e: QuestApiException) {
                lastError = e
                if (e.statusCode == 429) {
                    val waitMs = 5000L * (attemptIndex + 1)
                    logger.warn("Rate limited (429), waiting ${waitMs}ms before retry ${attemptIndex + 1}/3")
                    Thread.sleep(waitMs)
                } else {
                    throw e
                }
            }
        }
        throw lastError ?: QuestApiException(429, null, "Rate limited after 3 retries")
    }

    private inline fun <reified T> Http.Response.readJson(): T = use { response ->
        if (!response.ok()) {
            val httpException = runCatching { response.assertOk() }.exceptionOrNull()
            val errorBody = httpException?.message?.substringAfter('\n', "").orEmpty()

            logger.error("Discord API error [${response.statusCode}] body=$errorBody", null)

            val error = runCatching {
                GsonUtils.fromJson(errorBody, QuestApiError::class.java)
            }.getOrNull()

            val challenge = error?.let {
                if (!it.captchaKey.isNullOrEmpty() && it.captchaSiteKey != null &&
                    it.captchaRqdata != null && it.captchaRqtoken != null &&
                    it.captchaSessionId != null
                ) {
                    QuestCaptchaChallenge(
                        it.captchaSiteKey,
                        it.captchaRqdata,
                        it.captchaRqtoken,
                        it.captchaSessionId
                    )
                } else null
            }

            val message = if (challenge != null) {
                "Discord requires a captcha"
            } else if (response.statusCode == 401) {
                "Unauthorized"
            } else {
                error?.message ?: "Discord returned HTTP ${response.statusCode}"
            }
            throw QuestApiException(response.statusCode, challenge, message)
        }
        response.json(GsonUtils.gsonRestApi, T::class.java)
    }
}
