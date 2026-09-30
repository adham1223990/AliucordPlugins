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

    private fun addHelperButton(fragment: WidgetAuthMfa, getBindingMethod: Method, evaluateCodeMethod: Method) {
        val binding = getBindingMethod.invoke(fragment) ?: return
        val rootField = binding.javaClass.getDeclaredField("a").apply { isAccessible = true }
        val root = rootField.get(binding) as? CoordinatorLayout ?: return

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
            showPicker(fragment, root.context, getBindingMethod, evaluateCodeMethod)
        }

        root.addView(button, params)
    }

    private fun showPicker(fragment: WidgetAuthMfa, ctx: Context, getBindingMethod: Method, evaluateCodeMethod: Method) {
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
                submitCode(fragment, entries[which], getBindingMethod, evaluateCodeMethod)
            }
            .show()
    }

    private fun submitCode(fragment: WidgetAuthMfa, entry: TwoFaEntry, getBindingMethod: Method, evaluateCodeMethod: Method) {
        val code = Totp.currentCode(entry.secret)
        if (code == null) {
            Utils.showToast("That saved secret key looks invalid.", true)
            return
        }

        val binding = getBindingMethod.invoke(fragment) ?: return
        val codeViewField = binding.javaClass.getDeclaredField("f2242b").apply { isAccessible = true }
        val codeView = codeViewField.get(binding) as? CodeVerificationView

        // Fill the boxes visually first, then submit through Discord's own evaluateCode() —
        // the exact same private method the native "type" and "paste" flows call.
        codeView?.setCode(code)

        Handler(Looper.getMainLooper()).postDelayed({
            try {
                evaluateCodeMethod.invoke(fragment, code)
            } catch (e: Throwable) {
                logger.error("Failed to submit pasted 2FA code", e)
                Utils.showToast("Failed to submit code: ${e.message}", true)
            }
        }, 150)
    }
}
