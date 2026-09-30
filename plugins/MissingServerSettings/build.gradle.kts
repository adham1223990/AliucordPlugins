import com.aliucord.gradle.AliucordExtension

version = "1.0.0"

description = "Adds modern missing server settings, fixes modern Audit Log action types, and brings 64-bit modern permission overrides to Discord 126.21."

configure<AliucordExtension> {
    // TODO: replace 0L with your real Discord user ID if you want the
    // author name to link to your profile in the plugin list
    author("Adham", 0L, hyperlink = true)
}
