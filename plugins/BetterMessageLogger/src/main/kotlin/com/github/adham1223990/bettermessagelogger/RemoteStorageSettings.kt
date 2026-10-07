package com.github.adham1223990.bettermessagelogger

import android.content.Context
import android.text.InputType
import android.widget.LinearLayout
import android.widget.TextView
import com.aliucord.api.SettingsAPI
import com.google.android.material.textfield.TextInputLayout

internal fun remoteStorageStatus(settings: SettingsAPI): String {
    val config = RemoteStorage.config(settings) ?: return "Off · nothing saved on this device while the database is on"
    return RemoteStorage.label(config.provider)
}

/** Dialog where each user enters their own cloud account. Everything stays on this device's settings. */
internal fun showRemoteStorageDialog(
    context: Context,
    ui: LoggerUi,
    settings: SettingsAPI,
    changed: () -> Unit,
    show: (DiscordDialog) -> Unit,
) {
    var provider = settings.getString(RemoteStorage.PROVIDER, "").ifEmpty { RemoteStorage.CLOUDINARY }
    val saved = HashMap<String, String>()
    for (name in listOf(RemoteStorage.CLOUDINARY, RemoteStorage.SUPABASE)) {
        for (index in 0..2) saved[RemoteStorage.key(name, index)] = settings.getString(RemoteStorage.key(name, index), "")
    }
    val content = ui.dialogContent()
    val holder = ui.column()
    var inputs: List<TextInputLayout> = emptyList()
    lateinit var service: TextView

    fun capture() {
        inputs.forEachIndexed { index, field ->
            saved[RemoteStorage.key(provider, index)] = field.editText?.text?.toString().orEmpty().trim()
        }
    }

    fun render() {
        service.text = RemoteStorage.label(provider)
        holder.removeAllViews()
        inputs = RemoteStorage.hints(provider).mapIndexed { index, hint ->
            DiscordSettingsUi.field(
                context,
                hint,
                InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS or InputType.TYPE_TEXT_VARIATION_URI,
            ).also {
                it.editText?.setText(saved[RemoteStorage.key(provider, index)].orEmpty())
                holder.addView(it, LinearLayout.LayoutParams(-1, -2))
            }
        }
    }

    service = ui.row(content, "Service", null) {
        capture()
        provider = RemoteStorage.next(provider)
        render()
    }
    content.addView(holder, LinearLayout.LayoutParams(-1, -2))
    content.addView(
        ui.caption(
            "Deleted images and videos are uploaded to your own account and are not kept on this device. " +
                "Cloudinary: create an unsigned upload preset. Supabase: use a public bucket. " +
                "Used only while \"Save logs across restarts\" is on; otherwise media is saved in the device folder.",
        ),
    )
    render()

    lateinit var dialog: DiscordDialog
    dialog = DiscordDialog(context, "Cloud media storage")
        .content(ui.scroll(content, 0.6f))
        .negative("Cancel")
        .neutral("Turn off") {
            settings.setString(RemoteStorage.PROVIDER, "")
            changed()
            dialog.dismiss()
        }
        .positive("Save") {
            capture()
            val values = RemoteStorage.hints(provider).indices.map { saved[RemoteStorage.key(provider, it)].orEmpty() }
            val empty = values.indexOfFirst { it.isEmpty() }
            when {
                empty >= 0 -> {
                    inputs[empty].error = "Required"
                    false
                }

                provider == RemoteStorage.SUPABASE && !values[0].startsWith("https://") -> {
                    inputs[0].error = "Must start with https://"
                    false
                }

                else -> {
                    settings.setString(RemoteStorage.PROVIDER, provider)
                    values.forEachIndexed { index, value -> settings.setString(RemoteStorage.key(provider, index), value) }
                    changed()
                    true
                }
            }
        }
    show(dialog)
}
