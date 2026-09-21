package com.aliucord.plugins

import android.content.Context
import com.aliucord.Utils
import com.aliucord.annotations.AliucordPlugin
import com.aliucord.entities.Plugin
import com.aliucord.patcher.Hook
import de.robv.android.xposed.XC_MethodHook

@AliucordPlugin
class StreamQualityIndicator : Plugin() {

    override fun start(context: Context) {
        hookStreamQualitySettings()
    }

    private fun hookStreamQualitySettings() {
        val targetClasses = listOf(
            "com.discord.stores.StoreStream",
            "com.discord.stores.StoreApplicationStreaming",
            "com.discord.stores.StoreStreamRtcConnection"
        )

        for (className in targetClasses) {
            try {
                val clazz = Class.forName(className)
                for (method in clazz.declaredMethods) {
                    val methodName = method.name.lowercase()
                    // Target methods handling stream presets, quality updates, or settings
                    if (methodName.contains("quality") || methodName.contains("preset") || methodName.contains("streamsettings")) {
                        patcher.patch(method, Hook { param: XC_MethodHook.MethodHookParam ->
                            inspectArgumentsAndNotify(param.args)
                        })
                    }
                }
            } catch (_: ClassNotFoundException) {
                // Ignore missing classes in current build
            }
        }
    }

    private fun inspectArgumentsAndNotify(args: Array<Any?>?) {
        if (args == null || args.isEmpty()) return

        var resolution = ""
        var fps = ""

        for (arg in args) {
            if (arg != null) {
                val str = arg.toString()
                if (str.contains("1080")) resolution = "1080p"
                else if (str.contains("720")) resolution = "720p"
                else if (str.contains("480")) resolution = "480p"

                if (str.contains("60")) fps = "60 FPS"
                else if (str.contains("30")) fps = "30 FPS"
                else if (str.contains("15")) fps = "15 FPS"
            }
        }

        if (resolution.isNotEmpty() || fps.isNotEmpty()) {
            val message = if (resolution.isNotEmpty() && fps.isNotEmpty()) {
                "Stream Quality: $resolution @ $fps"
            } else {
                "Stream Quality: ${resolution.ifEmpty { fps }}"
            }

            logger.info(message)
            // Displays the stylish Aliucord custom toast banner
            Utils.showToast(message)
        }
    }

    override fun stop(context: Context) {
        patcher.unpatchAll()
    }
}
