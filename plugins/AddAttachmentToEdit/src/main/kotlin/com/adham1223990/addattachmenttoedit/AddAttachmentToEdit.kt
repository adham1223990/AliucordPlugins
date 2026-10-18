package com.adham1223990.addattachmenttoedit

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Color
import android.net.Uri
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.widget.NestedScrollView
import com.aliucord.Logger
import com.aliucord.Utils
import com.aliucord.annotations.AliucordPlugin
import com.aliucord.entities.Plugin
import com.aliucord.patcher.Hook
import com.discord.models.user.CoreUser
import com.discord.stores.StoreStream
import com.discord.widgets.chat.list.actions.WidgetChatListActions

@Suppress("unused")
@AliucordPlugin
class AddAttachmentToEdit : Plugin() {

    companion object {
        private const val PICK_IMAGE_REQUEST_CODE = 822562
    }

    private val addAttachmentViewId = View.generateViewId()
    private var pickerCallback: ((Uri?) -> Unit)? = null
    private val tag = Logger("AddAttachmentToEdit")

    override fun start(context: Context) {
        patchMessageActionsMenu()
        patchActivityResult()
    }

    private fun patchMessageActionsMenu() {
        patcher.patch(
            WidgetChatListActions::class.java.getDeclaredMethod(
                "configureUI",
                WidgetChatListActions.Model::class.java
            ),
            Hook { cf ->
                try {
                    val model = cf.args[0] as WidgetChatListActions.Model
                    val message = model.getMessage() ?: return@Hook
                    val actions = cf.thisObject as WidgetChatListActions

                    val myId = StoreStream.getUsers().me.getId()
                    val authorId = CoreUser(message.getAuthor()).getId()
                    if (authorId != myId) return@Hook

                    val scrollView = actions.view as? NestedScrollView ?: return@Hook
                    val lay = scrollView.getChildAt(0) as LinearLayout
                    if (lay.findViewById<View>(addAttachmentViewId) != null) return@Hook

                    val row = TextView(
                        lay.context,
                        null,
                        0,
                        com.lytefast.flexinput.R.i.UiKit_Settings_Item_Icon
                    )
                    row.id = addAttachmentViewId
                    row.text = "Add Attachment"
                    row.setCompoundDrawablesWithIntrinsicBounds(
                        android.R.drawable.ic_input_add, 0, 0, 0
                    )
                    val iconPadding = (12 * lay.context.resources.displayMetrics.density).toInt()
                    row.compoundDrawablePadding = iconPadding
                    if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M) {
                        row.compoundDrawableTintList = ColorStateList.valueOf(Color.WHITE)
                    }
                    row.setOnClickListener {
                        val channelId = message.getChannelId()
                        val messageId = message.getId()
                        actions.dismiss()
                        launchImagePicker { uri ->
                            if (uri == null) return@launchImagePicker
                            Utils.showToast("Uploading attachment...", false)
                            Utils.threadPool.execute {
                                try {
                                    AttachmentEditUploader.addAttachment(channelId, messageId, uri)
                                    Utils.mainThread.post {
                                        Utils.showToast("Attachment added.", false)
                                    }
                                } catch (e: Throwable) {
                                    tag.error("Failed to add attachment", e)
                                    Utils.mainThread.post {
                                        Utils.showToast("Failed to add attachment: ${e.message}", true)
                                    }
                                }
                            }
                        }
                    }
                    lay.addView(row, 5)
                } catch (e: Throwable) {
                    tag.error("Failed to inject the Add Attachment button", e)
                }
            }
        )
    }

    private fun launchImagePicker(callback: (Uri?) -> Unit) {
        pickerCallback = callback
        val intent = Intent(Intent.ACTION_GET_CONTENT).apply { type = "image/*" }
        try {
            Utils.appActivity.startActivityForResult(intent, PICK_IMAGE_REQUEST_CODE)
        } catch (e: Throwable) {
            pickerCallback = null
            tag.error("Failed to launch the image picker", e)
            Utils.showToast("Could not open the image picker.", true)
        }
    }

    private fun patchActivityResult() {
        patcher.patch(
            Activity::class.java.getDeclaredMethod(
                "onActivityResult",
                Int::class.javaPrimitiveType,
                Int::class.javaPrimitiveType,
                Intent::class.java
            ),
            Hook { cf ->
                val requestCode = cf.args[0] as Int
                if (requestCode != PICK_IMAGE_REQUEST_CODE) return@Hook
                val resultCode = cf.args[1] as Int
                val data = cf.args[2] as? Intent
                val uri = if (resultCode == Activity.RESULT_OK) data?.data else null
                val callback = pickerCallback
                pickerCallback = null
                callback?.invoke(uri)
            }
        )
    }

    override fun stop(context: Context) {
        patcher.unpatchAll()
        pickerCallback = null
    }
}
