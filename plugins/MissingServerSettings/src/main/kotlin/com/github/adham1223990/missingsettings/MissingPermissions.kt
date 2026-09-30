package com.github.adham1223990.missingsettings

object MissingPermissions {
    // Basic permissions required for UI visibility
    const val MANAGE_GUILD = 1L shl 5
    const val MANAGE_ROLES = 1L shl 28
    const val MANAGE_CHANNELS = 1L shl 4
    const val KICK_MEMBERS = 1L shl 1
    const val BAN_MEMBERS = 1L shl 2
    const val ADMINISTRATOR = 1L shl 3

    // Modern 64-bit flags completely missing from Discord 126.21 native pickers
    const val START_EMBEDDED_ACTIVITIES = 1L shl 39
    const val MODERATE_MEMBERS = 1L shl 40
    const val VIEW_CREATOR_MONETIZATION_ANALYTICS = 1L shl 41
    const val USE_SOUNDBOARD = 1L shl 42
    const val CREATE_GUILD_EXPRESSIONS = 1L shl 43
    const val CREATE_EVENTS = 1L shl 44
    const val USE_EXTERNAL_SOUNDS = 1L shl 45
    const val SEND_VOICE_MESSAGES = 1L shl 46
    const val SEND_POLLS = 1L shl 49
    const val USE_EXTERNAL_APPS = 1L shl 50

    val ALL_MODERN_PERMISSIONS = listOf(
        "Use Activities (Embedded Apps)" to START_EMBEDDED_ACTIVITIES,
        "Timeout / Moderate Members" to MODERATE_MEMBERS,
        "View Creator Analytics" to VIEW_CREATOR_MONETIZATION_ANALYTICS,
        "Use Soundboard" to USE_SOUNDBOARD,
        "Create Expressions (Emojis/Sounds)" to CREATE_GUILD_EXPRESSIONS,
        "Create Events" to CREATE_EVENTS,
        "Use External Sounds" to USE_EXTERNAL_SOUNDS,
        "Send Voice Messages" to SEND_VOICE_MESSAGES,
        "Create & Vote in Polls" to SEND_POLLS,
        "Use External Apps / Commands" to USE_EXTERNAL_APPS
    )
}
