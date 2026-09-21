package com.aliucord.plugins

import android.content.Context
import com.aliucord.annotations.AliucordPlugin
import com.aliucord.entities.Plugin
import com.aliucord.patcher.Hook
import de.robv.android.xposed.XC_MethodHook
import java.lang.reflect.Method

@AliucordPlugin
class BetterStreamQuality : Plugin() {

    override fun start(context: Context) {
        unlockPremiumStreamQuality()
    }

    private fun unlockPremiumStreamQuality() {
        try {
            // Retrieve the PremiumTier enum class and find the TIER_2 (Full Nitro) constant
            val premiumTierClass = Class.forName("com.discord.api.premium.PremiumTier")
            val tier2Enum = premiumTierClass.enumConstants?.firstOrNull { 
                it.toString().contains("TIER_2", ignoreCase = true) 
            } ?: return

            // Target classes responsible for holding and providing the local user's subscription state
            val userClasses = listOf(
                "com.discord.models.user.MeUser",
                "com.discord.models.user.CoreUser"
            )

            for (className in userClasses) {
                try {
                    val clazz = Class.forName(className)
                    val getPremiumTierMethod: Method = clazz.getDeclaredMethod("getPremiumTier")

                    // Patch getPremiumTier to always return TIER_2
                    patcher.patch(getPremiumTierMethod, Hook { param: XC_MethodHook.MethodHookParam ->
                        param.result = tier2Enum
                    })
                } catch (e: Exception) {
                    logger.error("Failed to hook getPremiumTier in $className", e)
                }
            }

            // Patch utility helper methods related to stream resolution and video quality checks
            patchUtilityFlags()

        } catch (e: Exception) {
            logger.error("Failed to initialize BetterStreamQuality plugin", e)
        }
    }

    private fun patchUtilityFlags() {
        val helperClasses = listOf(
            "com.discord.utilities.premium.PremiumUtils",
            "com.discord.utilities.media.MediaEngineSettingsUtils"
        )

        for (className in helperClasses) {
            try {
                val clazz = Class.forName(className)
                for (method in clazz.declaredMethods) {
                    if (method.returnType == Boolean::class.javaPrimitiveType && 
                        (method.name.contains("Stream", ignoreCase = true) || 
                         method.name.contains("VideoUpload", ignoreCase = true))) {
                        
                        patcher.patch(method, Hook { param: XC_MethodHook.MethodHookParam ->
                            param.result = true
                        })
                    }
                }
            } catch (_: ClassNotFoundException) {
                // Utility class is absent in this build; safely ignore
            }
        }
    }

    override fun stop(context: Context) {
        patcher.unpatchAll()
    }
}
