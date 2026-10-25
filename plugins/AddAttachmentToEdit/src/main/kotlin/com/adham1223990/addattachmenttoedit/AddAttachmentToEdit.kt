package com.adham1223990.addattachmenttoedit

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.view.inputmethod.InputContentInfoCompat
import androidx.core.widget.NestedScrollView
import com.aliucord.Logger
import com.aliucord.Utils
import com.aliucord.annotations.AliucordPlugin
import com.aliucord.entities.Plugin
import com.aliucord.patcher.Hook
import com.aliucord.patcher.PreHook
import com.discord.api.permission.Permission
import com.discord.models.user.CoreUser
import com.discord.utilities.color.ColorCompat
import com.discord.utilities.permissions.PermissionUtils
import com.discord.stores.StoreStream
import com.discord.widgets.chat.MessageContent
import com.discord.widgets.chat.MessageManager
import com.discord.widgets.chat.input.AppFlexInputViewModel
import com.discord.widgets.chat.input.ChatInputViewModel
import com.discord.widgets.chat.input.WidgetChatInputAttachments
import com.discord.widgets.chat.list.actions.WidgetChatListActions
import com.lytefast.flexinput.model.Attachment
import kotlin.jvm.functions.Function1

@Suppress("unused")
@AliucordPlugin
class AddAttachmentToEdit : Plugin() {

    companion object {
        // FragmentActivity only accepts request codes that fit in the lower 16 bits; anything
        // bigger throws "Can only use lower 16 bits for requestCode" before the picker opens.
        private const val PICK_FILES_REQUEST_CODE = 0x4A3D

        // Same limits Vencord's AddAttachments uses.
        private const val TYPE_DEFAULT = 0
        private const val TYPE_REPLY = 19
        private const val FLAG_IS_VOICE_MESSAGE = 8192L
    }

    private val addAttachmentViewId = View.generateViewId()
    private var pickerCallback: ((List<Uri>) -> Unit)? = null
    private val tag = Logger("AddAttachmentToEdit")

    override fun start(context: Context) {
        patchMessageActionsMenu()
        patchActivityResult()
        patchNativeEditAttachments()
    }

    /**
     * Mirrors Vencord's `canAddAttachments` + the popover button's own checks:
     * default/reply message, not a voice message, sent by me, fewer than 10 attachments, and
     * SEND_MESSAGES in the channel.
     */
    private fun canAddAttachments(model: WidgetChatListActions.Model): Boolean {
        val message = model.getMessage() ?: return false
        val type = message.getType() ?: TYPE_DEFAULT
        if (type != TYPE_DEFAULT && type != TYPE_REPLY) return false
        if (message.hasFlag(FLAG_IS_VOICE_MESSAGE)) return false
        if (message.isLocal() || message.isEphemeralMessage()) return false

        val myId = StoreStream.getUsers().me.getId()
        val authorId = CoreUser(message.getAuthor()).getId()
        if (authorId != myId) return false

        if (AttachmentEditUploader.getExistingAttachmentIds(message).size >= AttachmentEditUploader.MAX_ATTACHMENTS) {
            return false
        }

        // DMs have no computed permissions; guild channels need SEND_MESSAGES.
        val permissions = model.getPermissions()
        if (permissions != null && !PermissionUtils.can(Permission.SEND_MESSAGES, permissions)) return false
        return true
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

                    val scrollView = actions.view as? NestedScrollView ?: return@Hook
                    val lay = scrollView.getChildAt(0) as? LinearLayout ?: return@Hook

                    // Reuse the row if configureUI runs again for the same sheet, but always
                    // refresh its visibility and click listener so it never targets a stale message.
                    var row = lay.findViewById<TextView>(addAttachmentViewId)
                    if (row == null) {
                        row = createMenuRow(lay)
                    }

                    if (!canAddAttachments(model)) {
                        row.visibility = View.GONE
                        return@Hook
                    }
                    row.visibility = View.VISIBLE

                    row.setOnClickListener {
                        val channelId = message.getChannelId()
                        val messageId = message.getId()
                        val existingCount = AttachmentEditUploader.getExistingAttachmentIds(message).size
                        val remainingSlots = AttachmentEditUploader.MAX_ATTACHMENTS - existingCount
                        actions.dismiss()

                        if (remainingSlots <= 0) {
                            Utils.showToast("You cannot add more attachments to this message.", true)
                            return@setOnClickListener
                        }

                        launchFilePicker { uris ->
                            if (uris.isEmpty()) return@launchFilePicker
                            if (uris.size > remainingSlots) {
                                Utils.showToast(
                                    "You can only add $remainingSlots more attachment(s) to this message.",
                                    true
                                )
                                return@launchFilePicker
                            }
                            val limit = AttachmentEditUploader.maxFileBytes()
                            val tooBig = uris.any { uri -> querySize(uri) > limit }
                            if (tooBig) {
                                Utils.showToast("A selected file is ${AttachmentEditUploader.limitMessage()}", true)
                                return@launchFilePicker
                            }
                            Utils.showToast("Uploading, this can take a while...", false)
                            Utils.threadPool.execute {
                                try {
                                    val completed = AttachmentEditUploader.addAttachments(channelId, messageId, uris, null)
                                    if (!completed) {
                                        Utils.mainThread.post { Utils.showToast("Upload cancelled.", false) }
                                        return@execute
                                    }
                                    Utils.mainThread.post {
                                        Utils.showToast(
                                            if (uris.size == 1) "Attachment added successfully!"
                                            else "Attachments added successfully!",
                                            false
                                        )
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
                } catch (e: Throwable) {
                    tag.error("Failed to inject the Add Attachments button", e)
                }
            }
        )
    }

    /**
     * Builds a menu row that looks exactly like Discord's own entries (same style as the Edit row,
     * same text colour, same icon tint) and places it right below "Edit".
     */
    private fun createMenuRow(lay: LinearLayout): TextView {
        val ctx = lay.context
        val editRow = lay.findViewById<TextView>(Utils.getResId("dialog_chat_actions_edit", "id"))
        val row = TextView(ctx, null, 0, com.lytefast.flexinput.R.i.UiKit_Settings_Item_Icon)
        row.id = addAttachmentViewId
        row.text = "Add Attachments"

        val iconColor = ColorCompat.getThemedColor(ctx, Utils.getResId("colorInteractiveNormal", "attr"))
        val iconSize = (24 * ctx.resources.displayMetrics.density + 0.5f).toInt()
        row.setCompoundDrawablesRelativeWithIntrinsicBounds(UploadIconDrawable(iconColor, iconSize), null, null, null)
        if (editRow != null) row.setTextColor(editRow.textColors)

        val editIndex = if (editRow != null) lay.indexOfChild(editRow) else -1
        if (editIndex >= 0) lay.addView(row, editIndex + 1) else lay.addView(row)
        return row
    }

    private fun patchNativeEditAttachments() {
        try {
            val setAttachmentFromPickerMethod = WidgetChatInputAttachments::class.java.getDeclaredMethod(
                "setAttachmentFromPicker",
                Context::class.java,
                InputContentInfoCompat::class.java
            )
            setAttachmentFromPickerMethod.isAccessible = true
            patcher.patch(
                setAttachmentFromPickerMethod,
                PreHook { cf ->
                    try {
                        val editing = StoreStream.getChat().editingMessage ?: return@PreHook
                        val message = editing.message ?: return@PreHook
                        val existingCount = AttachmentEditUploader.getExistingAttachmentIds(message).size
                        if (existingCount >= AttachmentEditUploader.MAX_ATTACHMENTS) {
                            Utils.showToast("Maximum of 10 attachments reached.", true)
                            cf.result = null
                            return@PreHook
                        }

                        val inputContentInfo = cf.args[1] as? InputContentInfoCompat ?: return@PreHook
                        val contentUri = inputContentInfo.contentUri ?: return@PreHook
                        try {
                            inputContentInfo.requestPermission()
                        } catch (_: Throwable) {
                        }

                        cf.result = null
                        val channelId = message.getChannelId()
                        val messageId = message.getId()
                        Utils.showToast("Uploading pasted attachment...", false)
                        Utils.threadPool.execute {
                            try {
                                val completed = AttachmentEditUploader.addAttachments(
                                    channelId,
                                    messageId,
                                    listOf(contentUri),
                                    null
                                )
                                if (!completed) {
                                    Utils.mainThread.post { Utils.showToast("Upload cancelled.", false) }
                                    return@execute
                                }
                                Utils.mainThread.post {
                                    Utils.showToast("Pasted attachment added to message.", false)
                                }
                            } catch (e: Throwable) {
                                tag.error("Failed to upload pasted attachment", e)
                                Utils.mainThread.post {
                                    Utils.showToast("Failed to add pasted attachment: ${e.message}", true)
                                }
                            }
                        }
                    } catch (e: Throwable) {
                        tag.error("Error in setAttachmentFromPicker hook", e)
                    }
                }
            )
        } catch (e: Throwable) {
            tag.error("Failed to patch setAttachmentFromPicker", e)
        }

        try {
            patcher.patch(
                AppFlexInputViewModel::class.java.getDeclaredMethod(
                    "onAttachmentsUpdated",
                    List::class.java
                ),
                PreHook { cf ->
                    try {
                        val editing = StoreStream.getChat().editingMessage ?: return@PreHook
                        val rawList = cf.args[0] as? List<*> ?: return@PreHook
                        if (rawList.isEmpty()) return@PreHook

                        val message = editing.message ?: return@PreHook
                        val existingCount = AttachmentEditUploader.getExistingAttachmentIds(message).size
                        val remaining = AttachmentEditUploader.MAX_ATTACHMENTS - existingCount

                        if (remaining <= 0) {
                            Utils.showToast("Maximum of 10 attachments reached.", true)
                            cf.args[0] = emptyList<Any>()
                        } else if (rawList.size > remaining) {
                            Utils.showToast(
                                "Maximum of 10 attachments allowed ($remaining slot(s) remaining).",
                                true
                            )
                            val trimmed = ArrayList<Any?>(remaining)
                            var i = 0
                            while (i < remaining) {
                                trimmed.add(rawList[i])
                                i++
                            }
                            cf.args[0] = trimmed
                        }
                    } catch (e: Throwable) {
                        tag.error("Error in onAttachmentsUpdated hook", e)
                    }
                }
            )
        } catch (e: Throwable) {
            tag.error("Failed to patch AppFlexInputViewModel.onAttachmentsUpdated", e)
        }

        try {
            val sendListenerClass = Class.forName(
                "com.discord.widgets.chat.input.WidgetChatInput\$configureSendListeners\$7"
            )
            patcher.patch(
                sendListenerClass.getDeclaredMethod(
                    "onSend",
                    String::class.java,
                    List::class.java,
                    Function1::class.java
                ),
                PreHook { cf ->
                    try {
                        val editing = StoreStream.getChat().editingMessage ?: return@PreHook
                        val list = cf.args[1] as? List<*> ?: return@PreHook
                        if (list.isEmpty()) return@PreHook

                        val message = editing.message ?: return@PreHook
                        val existingCount = AttachmentEditUploader.getExistingAttachmentIds(message).size
                        val remaining = AttachmentEditUploader.MAX_ATTACHMENTS - existingCount

                        @Suppress("UNCHECKED_CAST")
                        val onSendResult = cf.args[2] as? Function1<Boolean, *>

                        if (remaining <= 0 || list.size > remaining) {
                            Utils.showToast(
                                if (remaining <= 0) "Maximum of 10 attachments reached."
                                else "Cannot add ${list.size} attachments. Only $remaining slot(s) remaining (max 10).",
                                true
                            )
                            onSendResult?.invoke(false)
                            cf.result = null
                            return@PreHook
                        }

                        val uris = ArrayList<Uri>(list.size)
                        var i = 0
                        while (i < list.size) {
                            val item = list[i] as? Attachment<*>
                            val uri = item?.uri
                            if (uri != null) {
                                uris.add(uri)
                            }
                            i++
                        }
                        if (uris.isEmpty()) return@PreHook

                        // args[0] is the text as displayed ("#room @name"); the real server text
                        // ("<#id> <@id>") comes from the autocomplete model, so use that one.
                        val editedText = resolveServerText(cf.thisObject, cf.args[0] as? String)
                        val channelId = message.getChannelId()
                        val messageId = message.getId()

                        onSendResult?.invoke(true)
                        StoreStream.getChat().setEditingMessage(null)
                        cf.result = null

                        Utils.showToast("Saving edit & uploading ${uris.size} attachment(s)...", false)
                        Utils.threadPool.execute {
                            try {
                                val completed = AttachmentEditUploader.addAttachments(
                                    channelId,
                                    messageId,
                                    uris,
                                    editedText
                                )
                                if (!completed) {
                                    Utils.mainThread.post { Utils.showToast("Upload cancelled.", false) }
                                    return@execute
                                }
                                Utils.mainThread.post {
                                    Utils.showToast("Message edited with attachment(s).", false)
                                }
                            } catch (e: Throwable) {
                                tag.error("Failed to edit message with attachments", e)
                                Utils.mainThread.post {
                                    Utils.showToast("Failed to edit message: ${e.message}", true)
                                }
                            }
                        }
                    } catch (e: Throwable) {
                        tag.error("Error in configureSendListeners$7.onSend hook", e)
                    }
                }
            )
        } catch (e: Throwable) {
            tag.error("Failed to patch WidgetChatInput send listener", e)
        }

        try {
            patcher.patch(
                ChatInputViewModel::class.java.getDeclaredMethod(
                    "sendMessage",
                    Context::class.java,
                    MessageManager::class.java,
                    MessageContent::class.java,
                    List::class.java,
                    Boolean::class.javaPrimitiveType,
                    Function1::class.java
                ),
                PreHook { cf ->
                    try {
                        val editing = StoreStream.getChat().editingMessage ?: return@PreHook
                        val list = cf.args[3] as? List<*> ?: return@PreHook
                        if (list.isEmpty()) return@PreHook

                        val message = editing.message ?: return@PreHook
                        val existingCount = AttachmentEditUploader.getExistingAttachmentIds(message).size
                        val remaining = AttachmentEditUploader.MAX_ATTACHMENTS - existingCount

                        @Suppress("UNCHECKED_CAST")
                        val onValidationResult = cf.args[5] as? Function1<Boolean, *>

                        if (remaining <= 0 || list.size > remaining) {
                            Utils.showToast(
                                if (remaining <= 0) "Maximum of 10 attachments reached."
                                else "Cannot add ${list.size} attachments. Only $remaining slot(s) remaining (max 10).",
                                true
                            )
                            onValidationResult?.invoke(false)
                            cf.result = null
                            return@PreHook
                        }

                        val uris = ArrayList<Uri>(list.size)
                        var i = 0
                        while (i < list.size) {
                            val item = list[i] as? Attachment<*>
                            val uri = item?.uri
                            if (uri != null) {
                                uris.add(uri)
                            }
                            i++
                        }
                        if (uris.isEmpty()) return@PreHook

                        val messageContent = cf.args[2] as? MessageContent
                        val editedText = messageContent?.textContent
                        val channelId = message.getChannelId()
                        val messageId = message.getId()

                        onValidationResult?.invoke(true)
                        StoreStream.getChat().setEditingMessage(null)
                        cf.result = null

                        Utils.showToast("Saving edit & uploading ${uris.size} attachment(s)...", false)
                        Utils.threadPool.execute {
                            try {
                                val completed = AttachmentEditUploader.addAttachments(
                                    channelId,
                                    messageId,
                                    uris,
                                    editedText
                                )
                                if (!completed) {
                                    Utils.mainThread.post { Utils.showToast("Upload cancelled.", false) }
                                    return@execute
                                }
                                Utils.mainThread.post {
                                    Utils.showToast("Message edited with attachment(s).", false)
                                }
                            } catch (e: Throwable) {
                                tag.error("Failed to edit message with attachments", e)
                                Utils.mainThread.post {
                                    Utils.showToast("Failed to edit message: ${e.message}", true)
                                }
                            }
                        }
                    } catch (e: Throwable) {
                        tag.error("Error in ChatInputViewModel.sendMessage hook", e)
                    }
                }
            )
        } catch (e: Throwable) {
            tag.error("Failed to patch ChatInputViewModel.sendMessage", e)
        }
    }

    // Opens the system file picker for any file type with multi-select, like Vencord's file input.
    /**
     * Returns the message text with mentions converted back to their server form
     * (`<#channelId>`, `<@userId>`), exactly like Discord's own send path does through
     * InputAutocomplete.getInputContent(). Falls back to [fallback] if anything is missing.
     */
    private fun resolveServerText(listener: Any?, fallback: String?): String? {
        if (listener == null) return fallback
        try {
            val outerField = listener.javaClass.getDeclaredField("this\$0")
            outerField.isAccessible = true
            val chatInput = outerField.get(listener) ?: return fallback
            val autocompleteField = chatInput.javaClass.getDeclaredField("autocomplete")
            autocompleteField.isAccessible = true
            val autocomplete = autocompleteField.get(chatInput) ?: return fallback
            val content = autocomplete.javaClass.getMethod("getInputContent").invoke(autocomplete) as? MessageContent
            val text = content?.textContent
            if (text != null) return text
        } catch (e: Throwable) {
            tag.error("Could not resolve mention text, using the displayed text", e)
        }
        return fallback
    }

    private fun querySize(uri: Uri): Long {
        try {
            Utils.appActivity.contentResolver.query(
                uri, arrayOf(android.provider.OpenableColumns.SIZE), null, null, null
            )?.use { cursor ->
                if (cursor.moveToFirst() && !cursor.isNull(0)) return cursor.getLong(0)
            }
        } catch (_: Throwable) {
        }
        return -1L
    }

    private fun launchFilePicker(callback: (List<Uri>) -> Unit) {
        pickerCallback = callback
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"
            putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
        }
        try {
            Utils.appActivity.startActivityForResult(intent, PICK_FILES_REQUEST_CODE)
        } catch (e: Throwable) {
            pickerCallback = null
            tag.error("Failed to launch the file picker", e)
            Utils.showToast("Could not open the file picker.", true)
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
                if (requestCode != PICK_FILES_REQUEST_CODE) return@Hook
                val resultCode = cf.args[1] as Int
                val data = cf.args[2] as? Intent
                val selectedUris = ArrayList<Uri>()
                if (resultCode == Activity.RESULT_OK && data != null) {
                    val clipData = data.clipData
                    if (clipData != null) {
                        var i = 0
                        val count = clipData.itemCount
                        while (i < count) {
                            val uri = clipData.getItemAt(i)?.uri
                            if (uri != null) {
                                selectedUris.add(uri)
                            }
                            i++
                        }
                    } else {
                        val singleUri = data.data
                        if (singleUri != null) {
                            selectedUris.add(singleUri)
                        }
                    }
                }
                val callback = pickerCallback
                pickerCallback = null
                callback?.invoke(selectedUris)
            }
        )
    }

    override fun stop(context: Context) {
        patcher.unpatchAll()
        pickerCallback = null
    }
}
