package com.adham1223990.twofahelper

import android.app.AlertDialog
import android.content.Context
import android.graphics.Color
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import androidx.coordinatorlayout.widget.CoordinatorLayout

import com.aliucord.Utils
import com.aliucord.annotations.AliucordPlugin
import com.aliucord.entities.Plugin
import com.aliucord.patcher.Hook

import com.discord.views.CodeVerificationView
import com.discord.widgets.auth.WidgetAuthMfa

import java.lang.reflect.Method

/**
 * Adds a "2FA Helper" button on Discord's own login MFA screen (next to where Backup Codes
 * lives). Picking a saved entry fills the code boxes and submits through WidgetAuthMfa's own
 * private evaluateCode(String) method — the exact same call Discord's own UI makes when you
 * type or paste a code — so this plugin never builds or sends any request of its own.
 */
@AliucordPlugin(requiresRestart = false)
@Suppress("unused")
class TwoFaHelper : Plugin() {

    init {
        settingsTab = SettingsTab(TwoFaHelperSettingsPage::class.java).withArgs(settings)
    }

    // Tag used to avoid adding the button twice if onViewBound runs more than once.
    private val buttonTag = "two_fa_helper_button".hashCode()

    override fun start(context: Context) {
        TwoFaStore.init(settings)

        try {
            val onViewBoundMethod = WidgetAuthMfa::class.java.getDeclaredMethod("onViewBound", View::class.java)
            val getBindingMethod = WidgetAuthMfa::class.java.getDeclaredMethod("getBinding").apply {
                isAccessible = true
            }
            val evaluateCodeMethod = WidgetAuthMfa::class.java.getDeclaredMethod("evaluateCode", String::class.java).apply {
                isAccessible = true
            }

            // Hook (after): let Discord's own onViewBound finish setting up the screen first,
            // then we just add an extra button to the layout it already built.
            patcher.patch(onViewBoundMethod, Hook { param ->
                try {
                    val fragment = param.thisObject as WidgetAuthMfa
                    addHelperButton(fragment, getBindingMethod, evaluateCodeMethod)
                } catch (e: Throwable) {
                    logger.error("Failed to add 2FA Helper button", e)
                }
            })
            logger.info("TwoFaHelper: patched WidgetAuthMfa.onViewBound")
        } catch (e: Throwable) {
            logger.error("Failed to patch WidgetAuthMfa.onViewBound", e)
            Utils.showToast("TwoFaHelper: couldn't hook the login screen, plugin needs an update.", false)
        }
    }

    override fun stop(context: Context) {
        patcher.unpatchAll()
    }

    /** Finds the first declared field of [type] on an object's class, instead of relying on a
     *  hardcoded obfuscated field name (those differ between builds and app versions). */
    private fun <T> findFieldOfType(obj: Any, type: Class<T>): T? {
        val fields = obj.javaClass.declaredFields
        var i = 0
        while (i < fields.size) {
            val f = fields[i]
            if (type.isAssignableFrom(f.type)) {
                f.isAccessible = true
                @Suppress("UNCHECKED_CAST")
                return f.get(obj) as? T
            }
            i++
        }
        return null
    }

    private fun addHelperButton(fragment: WidgetAuthMfa, getBindingMethod: Method, evaluateCodeMethod: Method) {
        val binding = getBindingMethod.invoke(fragment) ?: return
        val root = findFieldOfType(binding, CoordinatorLayout::class.java) ?: return

        // Avoid stacking a second button if this ever runs twice for the same view.
        if (root.findViewWithTag<View>(buttonTag) != null) return

        val ctx = root.context
        val density = ctx.resources.displayMetrics.density

        val button = Button(ctx).apply {
            text = "2FA Helper"
            tag = buttonTag
            setTextColor(Color.WHITE)
            setBackgroundColor(0xFF5865F2.toInt())
        }

        val params = CoordinatorLayout.LayoutParams(
            CoordinatorLayout.LayoutParams.WRAP_CONTENT,
            CoordinatorLayout.LayoutParams.WRAP_CONTENT
        )
        params.gravity = android.view.Gravity.TOP or android.view.Gravity.CENTER_HORIZONTAL
        // Sits below the existing "You can use a backup code..." text.
        params.topMargin = (420 * density).toInt()

        button.setOnClickListener {
            showPicker(fragment, root.context, getBindingMethod, evaluateCodeMethod, button)
        }

        root.addView(button, params)
    }

    private fun showPicker(
        fragment: WidgetAuthMfa,
        ctx: Context,
        getBindingMethod: Method,
        evaluateCodeMethod: Method,
        button: Button
    ) {
        val entries = TwoFaStore.all()
        if (entries.isEmpty()) {
            Utils.showToast("No saved 2FA secrets yet. Add one from the plugin settings.", true)
            return
        }

        val names: Array<CharSequence> = Array(entries.size) { idx -> entries[idx].name as CharSequence }

        AlertDialog.Builder(ctx)
            .setTitle("Paste a 2FA code")
            .setItems(names) { dialog, which ->
                dialog.dismiss()
                submitCode(fragment, entries[which], getBindingMethod, evaluateCodeMethod, button)
            }
            .show()
    }

    @Volatile
    private var submitting = false

    private fun submitCode(
        fragment: WidgetAuthMfa,
        entry: TwoFaEntry,
        getBindingMethod: Method,
        evaluateCodeMethod: Method,
        button: Button
    ) {
        if (submitting) {
            Utils.showToast("Already submitting a code, please wait…", true)
            return
        }

        val code = Totp.currentCode(entry.secret)
        if (code == null) {
            Utils.showToast("That saved secret key looks invalid.", true)
            return
        }

        // Submits exactly once through Discord's own evaluateCode(). We deliberately do NOT
        // call CodeVerificationView.setCode() here: on the real build it turned out to already
        // trigger the same submission internally (that's why the native paste flow swaps the
        // listener to a no-op first), so calling both ended up submitting the code twice —
        // once automatically, once from us — and the second attempt was rejected as invalid.
        submitting = true
        button.isEnabled = false
        Utils.showToast("Submitting code $code for ${entry.name}…", true)
        try {
            evaluateCodeMethod.invoke(fragment, code)
        } catch (e: Throwable) {
            logger.error("Failed to submit 2FA code", e)
            Utils.showToast("Failed to submit code: ${e.message}", true)
        } finally {
            // Re-enabled after a short delay rather than immediately, so a second tap can't
            // race the first submission while Discord is still processing it.
            Handler(Looper.getMainLooper()).postDelayed({
                submitting = false
                button.isEnabled = true
            }, 1000)
        }
    }
}
