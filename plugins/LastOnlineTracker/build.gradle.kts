import com.aliucord.gradle.AliucordExtension

version = "1.0.0"

description = " ​A gentle presence tracker — shows the last time someone was online, idle, DND, right in their profile."

configure<AliucordExtension> {
    // TODO: replace 0L with your real Discord user ID if you want the
    // author name to link to your profile in the plugin list
    author("Adham", 0L, hyperlink = true)
}
