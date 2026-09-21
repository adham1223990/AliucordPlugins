package com.aliucord.plugins

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.widget.Toast
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
                    // Target methods that set or update stream resolution/FPS parameters
                    if (methodName.contains("quality") || methodName.contains("preset") || methodName.contains("streamsettings")) {
                        patcher.patch(method, Hook { param: XC_MethodHook.MethodHookParam ->
                            inspectArgumentsAndNotify(param.args)
                        })
                    }
                }
            } catch (_: ClassNotFoundException) {
                // Ignore missing classes in current Discord build
            }
        }
    }

    private fun inspectArgumentsAndNotify(args: Array<Any?>?) {
        if (args == null || args.isEmpty()) return

        val details = StringBuilder()
        for (arg in args) {
            if (arg != null) {
                val str = arg.toString()
                if (str.contains("720") || str.contains("1080") || str.contains("60") || str.contains("30") || str.contains("FPS")) {
                    details.append(str).append(" ")
                }
            }
        }

        if (details.isNotEmpty()) {
            val message = "Selected Stream Quality: $details"
            logger.info(message)
            showToast(message)
        }
    }

    private fun showToast(text: String) {
        Handler(Looper.getMainLooper()).post {
            Toast.makeText(Utils.appContext, text, Toast.LENGTH_LONG).show()
        }
    }

    override fun stop(context: Context) {
        patcher.unpatchAll()
    }
}

