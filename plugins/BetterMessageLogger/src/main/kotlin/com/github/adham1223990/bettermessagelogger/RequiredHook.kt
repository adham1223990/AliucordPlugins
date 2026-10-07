package com.github.adham1223990.bettermessagelogger

import com.aliucord.api.PatcherAPI
import de.robv.android.xposed.XC_MethodHook

// The class/name overload silently catches installation failures. These hooks are essential.
internal fun PatcherAPI.patchRequired(clazz: Class<*>, name: String, types: Array<Class<*>>, hook: XC_MethodHook) =
    patch(clazz.getDeclaredMethod(name, *types), hook)
