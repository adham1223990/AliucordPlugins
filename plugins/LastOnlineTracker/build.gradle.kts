import com.aliucord.gradle.AliucordExtension

version = "1.1.0"

description = "Tracks last seen online status accurately, detects invisible users via typing, reactions, voice activity, and message events."

configure<AliucordExtension> {
    changelog.set(
        """
        # 1.1.0
        * Added direct gateway hooks to track split-second mobile online spikes.
        * Added support for detecting invisible users via typing, messages, reactions, and voice activity.
        # 1.0.0
        * Initial plugin release.
        """.trimIndent()
    )
    author("Adham", 1001222848716738570L, hyperlink = true)
}
