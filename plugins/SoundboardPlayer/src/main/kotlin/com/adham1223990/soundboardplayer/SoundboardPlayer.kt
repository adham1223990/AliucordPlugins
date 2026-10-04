package com.adham1223990.soundboardplayer

import android.content.Context
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import com.aliucord.Utils
import com.aliucord.annotations.AliucordPlugin
import com.aliucord.api.CommandsAPI.CommandResult
import com.aliucord.entities.Plugin
import com.aliucord.patcher.Hook
import com.aliucord.wrappers.ChannelWrapper
import com.discord.widgets.voice.controls.VoiceControlsSheetView
import com.discord.widgets.voice.model.CallModel
import kotlin.jvm.functions.Function0

/**
 * Adds a real "Soundboard" button to Discord's own in-call controls sheet
 * (com.discord.widgets.voice.controls.VoiceControlsSheetView), plus a /soundboard command as a
 * backup entry point. Both open the same search-and-play UI, built only on the official,
 * documented Soundboard REST endpoints (see SoundboardApi.kt) sent through
 * Http.Request.newDiscordRNRequest.
 */
@AliucordPlugin(requiresRestart = false)
@Suppress("unused")
class SoundboardPlayer : Plugin() {

    // Tag used to avoid adding the button twice — configureInviteButtons runs every time the
    // controls sheet re-renders (e.g. on mute toggle), not just once.
    private val buttonTag = "soundboard_player_button".hashCode()

    override fun start(context: Context) {
        // تسجيل الأمر باتباع نمط CommandsAPI.CommandResult
        try {
            commands.registerCommand(
                "soundboard",
                "Search and play a soundboard sound in a voice channel",
                emptyList()
            ) { ctx ->
                val channelId = runCatching { ctx.channel?.id?.toString() }.getOrNull()
                val guildId = runCatching { ctx.channel?.guildId?.toString() }.getOrNull()
                Utils.mainThread.post {
                    runCatching {
                        Utils.openPageWithProxy(Utils.appActivity, SoundboardPage(channelId, guildId))
                    }.onFailure {
                        logger.error("Failed to open SoundboardPage", it)
                        Utils.showToast("Failed to open Soundboard: ${it.message}", true)
                    }
                }
                CommandResult("Opening Soundboard…", null, false)
            }
            logger.info("SoundboardPlayer: registered /soundboard command")
        } catch (e: Throwable) {
            logger.error("Failed to register /soundboard command", e)
            Utils.showToast("SoundboardPlayer: couldn't register the /soundboard command, plugin needs an update.", false)
        }

        // حقن الزر في شريط أزرار المكالمة
        try {
            val configureInviteButtonsMethod = VoiceControlsSheetView::class.java.getDeclaredMethod(
                "configureInviteButtons",
                CallModel::class.java,
                Function0::class.java,
                Boolean::class.javaPrimitiveType
            ).apply { isAccessible = true }

            patcher.patch(configureInviteButtonsMethod, Hook { param ->
                try {
                    val view = param.thisObject as VoiceControlsSheetView
                    val model = param.args[0] as? CallModel
                    addSoundboardButton(view, model)
                } catch (e: Throwable) {
                    logger.error("Failed to inject the in-call Soundboard button", e)
                }
            })
            logger.info("SoundboardPlayer: patched VoiceControlsSheetView.configureInviteButtons")
        } catch (e: Throwable) {
            logger.error("Failed to patch VoiceControlsSheetView.configureInviteButtons", e)
            Utils.showToast("SoundboardPlayer: couldn't hook the call controls, use /soundboard instead.", false)
        }
    }

    private fun addSoundboardButton(view: VoiceControlsSheetView, model: CallModel?) {
        if (view.findViewWithTag<View>(buttonTag) != null) return

        // ChannelWrapper.id / .guildId are properties on a ChannelWrapper INSTANCE, not static
        // functions that take the channel as a parameter — must wrap the channel first.
        val channel = runCatching { model?.getChannel() }.getOrNull()
        val channelId = channel?.let { runCatching { ChannelWrapper(it).id.toString() }.getOrNull() }
        val guildId = channel?.let {
            runCatching { ChannelWrapper(it).guildId }.getOrNull()?.takeIf { it != 0L }?.toString()
        }

        val button = Button(view.context).apply {
            text = "Soundboard"
            tag = buttonTag
        }
        button.setOnClickListener {
            runCatching {
                Utils.openPageWithProxy(Utils.appActivity, SoundboardPage(channelId, guildId))
            }.onFailure {
                logger.error("Failed to open SoundboardPage from the call button", it)
                Utils.showToast("Failed to open Soundboard: ${it.message}", true)
            }
        }

        view.addView(
            button,
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        )
    }

    override fun stop(context: Context) {
        commands.unregisterAll()
        patcher.unpatchAll()
    }
}
