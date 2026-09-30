package com.adham1223990.twofahelper

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Color
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import com.aliucord.Utils
import com.aliucord.api.SettingsAPI
import com.aliucord.fragments.SettingsPage

/** White text, blurple background — keeps every button label readable, all text stays white. */
fun Button.styleWhite() {
    setTextColor(Color.WHITE)
    setBackgroundColor(0xFF5865F2.toInt())
}

class TwoFaHelperSettingsPage(private val settings: SettingsAPI) : SettingsPage() {

    private lateinit var container: LinearLayout
    private lateinit var nameInput: EditText
    private lateinit var secretInput: EditText

    private val tickHandler = Handler(Looper.getMainLooper())
    private var ticking = false
    private val tickRunnable = object : Runnable {
        override fun run() {
            if (!::container.isInitialized) return
            renderCodesOnly()
            tickHandler.postDelayed(this, 1000)
        }
    }

    override fun onViewBound(view: View) {
        super.onViewBound(view)
        setActionBarTitle("2FA Helper")
        TwoFaStore.init(settings)
        container = linearLayout
        render()
    }

    override fun onResume() {
        super.onResume()
        if (!ticking) {
            ticking = true
            tickHandler.post(tickRunnable)
        }
    }

    override fun onPause() {
        super.onPause()
        ticking = false
        tickHandler.removeCallbacks(tickRunnable)
    }

    private fun white(text: String, size: Float, top: Int, bottom: Int, ctx: Context): TextView =
        TextView(ctx).apply {
            this.text = text
            setTextColor(Color.WHITE)
            textSize = size
            setPadding(32, top, 32, bottom)
        }

    private fun render() {
        val ctx = container.context
        container.removeAllViews()

        container.addView(white("2FA Helper", 20f, 24, 4, ctx))
        container.addView(
            white(
                "Store your own 2FA (TOTP) secret keys and read the current code here, " +
                    "the same way a phone authenticator app would. Nothing here is ever sent " +
                    "anywhere — codes are only generated locally.",
                13f, 0, 16, ctx
            )
        )

        container.addView(white("Name", 13f, 8, 2, ctx))
        nameInput = EditText(ctx).apply {
            hint = "example@example.etc"
            setHintTextColor(Color.LTGRAY)
            setTextColor(Color.WHITE)
            isSingleLine = true
        }
        container.addView(nameInput)

        container.addView(white("Secret key", 13f, 16, 2, ctx))
        secretInput = EditText(ctx).apply {
            hint = "Paste the 2FA secret key here"
            setHintTextColor(Color.LTGRAY)
            setTextColor(Color.WHITE)
            isSingleLine = true
        }
        container.addView(secretInput)

        container.addView(Button(ctx).apply {
            text = "Add Account"
            styleWhite()
            setOnClickListener { addEntry() }
        })

        container.addView(white("Saved accounts", 16f, 24, 4, ctx))

        val entries = TwoFaStore.all()
        if (entries.isEmpty()) {
            container.addView(white("No secrets saved yet.", 14f, 4, 16, ctx))
        } else {
            var i = 0
            while (i < entries.size) {
                container.addView(buildEntryRow(ctx, entries[i]))
                i++
            }
        }
    }

    /** Only refreshes the live code labels every second, without rebuilding the input fields
     *  (so typing isn't interrupted while the countdown ticks). */
    private fun renderCodesOnly() {
        var i = 0
        while (i < container.childCount) {
            val child = container.getChildAt(i)
            val entryId = child.getTag(R_TAG_ENTRY_ID) as? String
            val codeView = child.getTag(R_TAG_CODE_VIEW) as? TextView
            if (entryId != null && codeView != null) {
                val entry = TwoFaStore.all().firstOrNullSafe { it.id == entryId }
                if (entry != null) {
                    val code = Totp.currentCode(entry.secret)
                    val seconds = Totp.secondsRemaining()
                    codeView.text = if (code != null) "$code  (${seconds}s)" else "Invalid secret"
                }
            }
            i++
        }
    }

    private fun buildEntryRow(ctx: Context, entry: TwoFaEntry): LinearLayout {
        val row = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(32, 12, 32, 12)
        }
        row.addView(white(entry.name, 15f, 0, 2, ctx))

        val code = Totp.currentCode(entry.secret)
        val seconds = Totp.secondsRemaining()
        val codeView = white(if (code != null) "$code  (${seconds}s)" else "Invalid secret", 22f, 0, 8, ctx)
        row.setTag(R_TAG_ENTRY_ID, entry.id)
        row.setTag(R_TAG_CODE_VIEW, codeView)
        row.addView(codeView)

        val buttons = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }
        buttons.addView(Button(ctx).apply {
            text = "Copy code"
            styleWhite()
            setOnClickListener {
                val current = Totp.currentCode(entry.secret)
                if (current != null) {
                    copyToClipboard(ctx, current)
                    Utils.showToast("Code copied", true)
                }
            }
        })
        buttons.addView(Button(ctx).apply {
            text = "Delete"
            styleWhite()
            setOnClickListener {
                TwoFaStore.remove(entry.id)
                render()
            }
        })
        row.addView(buttons)
        return row
    }

    private fun addEntry() {
        val name = nameInput.text?.toString().orEmpty().trim()
        val secret = secretInput.text?.toString().orEmpty().trim()
        if (name.isEmpty() || secret.isEmpty()) {
            Utils.showToast("Please fill in both the name and the secret key", true)
            return
        }
        if (!Totp.isLikelySecret(secret)) {
            Utils.showToast("That doesn't look like a valid 2FA secret key", true)
            return
        }
        TwoFaStore.add(name, secret)
        nameInput.setText("")
        secretInput.setText("")
        render()
    }

    private fun copyToClipboard(ctx: Context, text: String) {
        val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return
        cm.setPrimaryClip(ClipData.newPlainText("2FA code", text))
    }

    companion object {
        private const val R_TAG_ENTRY_ID = 0x02fa0001
        private const val R_TAG_CODE_VIEW = 0x02fa0002
    }
}

/** Manual find-first helper (avoids Kotlin stdlib iterator specialization issues). */
private fun List<TwoFaEntry>.firstOrNullSafe(predicate: (TwoFaEntry) -> Boolean): TwoFaEntry? {
    var i = 0
    while (i < size) {
        if (predicate(this[i])) return this[i]
        i++
    }
    return null
}
