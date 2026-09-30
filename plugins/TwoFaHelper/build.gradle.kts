import com.aliucord.gradle.AliucordExtension

version = "1.0.0"

description = "Store named 2FA (TOTP) secret keys and submit the current code on Discord's login verification screen with one tap."

configure<AliucordExtension> {
    // TODO: replace 0L with your real Discord user ID if you want the
    // author name to link to your profile in the plugin list
    author("Adham", 0L, hyperlink = true)
}
