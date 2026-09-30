package com.github.adham1223990.missingsettings

import android.content.Context
import com.aliucord.patcher.Hook
import com.aliucord.patcher.Patcher
import com.discord.utilities.auditlogs.AuditLogUtils

object AuditLogPatcher {
    fun apply(patcher: Patcher) {
        patcher.patch(
            AuditLogUtils::class.java,
            "getActionTypeString",
            arrayOf(Context::class.java, Int::class.javaPrimitiveType!!),
            Hook { frame ->
                val actionType = frame.args[1] as Int

                val resolved = when (actionType) {
                    // Emojis & Stickers
                    60 -> "Emoji Created"
                    61 -> "Emoji Updated"
                    62 -> "Emoji Deleted"
                    90 -> "Sticker Created"
                    91 -> "Sticker Updated"
                    92 -> "Sticker Deleted"

                    // Stage & Events
                    83 -> "Stage Instance Created"
                    84 -> "Stage Instance Updated"
                    85 -> "Stage Instance Deleted"
                    100 -> "Scheduled Event Created"
                    101 -> "Scheduled Event Updated"
                    102 -> "Scheduled Event Deleted"

                    // Threads & Message Management
                    110 -> "Thread Created"
                    111 -> "Thread Updated"
                    112 -> "Thread Deleted"
                    121 -> "Message Bulk Deleted"
                    122 -> "Message Pinned"
                    123 -> "Message Unpinned"

                    // Auto Moderation
                    140 -> "AutoMod Rule Created"
                    141 -> "AutoMod Rule Updated"
                    142 -> "AutoMod Rule Deleted"
                    143 -> "AutoMod Blocked Message"
                    144 -> "AutoMod Flagged Content"
                    145 -> "AutoMod User Quarantined"

                    // Server Home / Onboarding / Member Verification
                    160 -> "Server Guide Created"
                    161 -> "Server Guide Updated"
                    163 -> "Onboarding Prompt Created"
                    164 -> "Onboarding Prompt Updated"
                    165 -> "Onboarding Prompt Deleted"
                    166 -> "Onboarding Mode Updated"
                    167 -> "Default Onboarding Channels Updated"

                    // Soundboard
                    190 -> "Soundboard Sound Created"
                    191 -> "Soundboard Sound Updated"
                    192 -> "Soundboard Sound Deleted"

                    // Timeouts & Communication
                    24 -> "Member Communication Disabled (Timeout)"
                    25 -> "Member Communication Re-enabled"

                    else -> null
                }

                if (resolved != null) {
                    frame.result = resolved
                }
            }
        )
    }
}
