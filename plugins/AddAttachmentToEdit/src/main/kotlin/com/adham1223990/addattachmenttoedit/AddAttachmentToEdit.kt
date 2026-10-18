package com.adham1223990.addattachmenttoedit

import android.app.Activity
import android.content.Context
import android.content.Intent
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
import com.aliucord.patcher.InsteadHook
import com.discord.models.user.CoreUser
import com.discord.stores.StoreStream
import com.discord.widgets.chat.MessageManager
import com.discord.widgets.chat.list.actions.WidgetChatListActions
import de.robv.android.xposed.XposedBridge
import kotlin.jvm.functions.Function2
import java.lang.reflect.InvocationTargetException

/**
 * Lets you attach a NEW image to a message while editing it.
 *
 * Discord's own edit screen only lets you keep or remove EXISTING attachments; it never lets
 * you add a new one, because the client's own edit code path (MessageManager.editMessage ->
 * StoreMessages.editMessage) only ever sends the text content -- there is no attachment support
 * in that path at all in this build. The official REST API, however, fully supports adding new
 * files on an edit: PATCH /channels/{channel.id}/messages/{message.id} accepts multipart
 * "files[n]" parts plus an "attachments" array, and new files are appended to the message
 * (see https://docs.discord.com/developers/resources/message#edit-message).
 *
 * This plugin bypasses the client's limited edit path for messages that have a staged
 * attachment and sends that official request itself, through
 * Http.Request.newDiscordRNRequest only (see AttachmentEditUploader.kt).
 *
 * Flow:
 * 1. Long-press one of your own messages -> tap the new "Add Attachment To Edit" row.
 * 2. Pick an image from the system picker. It is staged for that message only.
 * 3. Long-press the message again -> Edit (normal Discord action) -> change the text if you
 *    want -> press Send like always.
 * 4. This plugin intercepts that Send for messages with a staged attachment and uploads the
 *    edit + the new image together. Messages without a staged attachment are edited normally,
 *    completely unaffected by this plugin.
 */
@Suppress("unused")
@AliucordPlugin
class AddAttachmentToEdit : Plugin() {

    companion object {
        private const val PICK_IMAGE_REQUEST_CODE = 822562

        // messageId -> picked image Uri, staged between tapping "Add Attachment To Edit" and
        // actually pressing Send on the edit box for that same message.
        val pendingAttachments = HashMap<Long, Uri>()
    }

    private val addAttachmentViewId = View.generateViewId()
    private var pickerCallback: ((Uri?) -> Unit)? = null
    private val tag = Logger("AddAttachmentToEdit")

    override fun start(context: Context) {
        patchMessageActionsMenu()
        patchActivityResult()
        patchEditMessage()
    }

    // 1. Inject an "Add Attachment To Edit" row into the message long-press menu, right next
    // to the existing native Edit action, for your own messages only.
    private fun patchMessageActionsMenu() {
        patcher.patch(
            WidgetChatListActions::class.java.getDeclaredMethod(
                "configureUI",
                WidgetChatListActions.Model::class.java
            ),
            Hook { cf ->
                try {
                    val model = cf.args[0] as WidgetChatListActions.Model
                    val message = model.message ?: return@Hook
                    val actions = cf.thisObject as WidgetChatListActions

                    val myId = StoreStream.getUsers().me.id
                    val authorId = CoreUser(message.author).id
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
                    row.text = "Add Attachment To Edit"
                    row.setOnClickListener {
                        launchImagePicker { uri ->
                            if (uri != null) {
                                pendingAttachments[message.id] = uri
                                Utils.showToast(
                                    "Attachment staged. Now use this message's Edit action and press Send to apply it.",
                                    false
                                )
                            }
                        }
                        actions.dismiss()
                    }
                    lay.addView(row, 5)
                } catch (e: Throwable) {
                    tag.error("Failed to inject the Add Attachment To Edit button", e)
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

    // 2. Catch the picker's result. Utils.appActivity IS the running Activity, so patching
    // Activity.onActivityResult here only ever reacts to our own request code and never
    // touches anything else going through that same callback.
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

    // 3. The actual fix: when Send is pressed while editing a message that has a staged
    // attachment, skip Discord's text-only edit path and send our own official PATCH request
    // with the new file attached. Messages without a staged attachment fall through to the
    // real, unmodified Discord edit behaviour.
    private fun patchEditMessage() {
        patcher.patch(
            MessageManager::class.java.getDeclaredMethod(
                "editMessage",
                Long::class.javaPrimitiveType,
                Long::class.javaPrimitiveType,
                String::class.java,
                Function2::class.java,
                Integer::class.java
            ),
            InsteadHook { cf ->
                val messageId = cf.args[0] as Long
                val channelId = cf.args[1] as Long
                val content = cf.args[2] as String
                val pendingUri = pendingAttachments[messageId]

                if (pendingUri == null) {
                    return@InsteadHook try {
                        XposedBridge.invokeOriginalMethod(cf.method, cf.thisObject, cf.args)
                    } catch (e: InvocationTargetException) {
                        tag.error(e)
                        false
                    } catch (e: IllegalAccessException) {
                        tag.error(e)
                        false
                    }
                }

                pendingAttachments.remove(messageId)
                Utils.showToast("Uploading attachment…", false)
                Utils.threadPool.execute {
                    try {
                        AttachmentEditUploader.sendEditWithAttachment(channelId, messageId, content, pendingUri)
                        Utils.mainThread.post { Utils.showToast("Message edited with attachment.", false) }
                    } catch (e: Throwable) {
                        tag.error("Failed to upload attachment for edit", e)
                        Utils.mainThread.post {
                            Utils.showToast("Failed to add attachment: ${e.message}", true)
                        }
                    }
                }
                // Mirrors the original method's eager-return contract: it validates and
                // dispatches to a queue without waiting for the network, so we do the same.
                true
            }
        )
    }

    override fun stop(context: Context) {
        patcher.unpatchAll()
        pendingAttachments.clear()
        pickerCallback = null
    }
}
