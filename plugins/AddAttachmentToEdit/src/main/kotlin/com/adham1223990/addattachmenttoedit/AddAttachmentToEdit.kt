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
import androidx.core.view.inputmethod.InputContentInfoCompat
import androidx.core.widget.NestedScrollView
import com.aliucord.Logger
import com.aliucord.Utils
import com.aliucord.annotations.AliucordPlugin
import com.aliucord.entities.Plugin
import com.aliucord.patcher.Hook
import com.aliucord.patcher.PreHook
import com.discord.models.user.CoreUser
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
        private const val PICK_IMAGE_REQUEST_CODE = 822562
        private const val EXTRA_PICK_IMAGES_MAX = "android.provider.extra.PICK_IMAGES_MAX"
    }

    private val addAttachmentViewId = View.generateViewId()
    private var pickerCallback: ((List<Uri>) -> Unit)? = null
    private val tag = Logger("AddAttachmentToEdit")

    override fun start(context: Context) {
        patchMessageActionsMenu()
        patchActivityResult()
        patchNativeEditAttachments()
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

                        val existingCount = AttachmentEditUploader.getExistingAttachmentIds(message).size
                        val remainingSlots = AttachmentEditUploader.MAX_ATTACHMENTS - existingCount
                        if (remainingSlots <= 0) {
                            Utils.showToast("Maximum of 10 attachments reached.", true)
                            return@setOnClickListener
                        }

                        launchImagePicker(remainingSlots) { uris ->
                            if (uris.isEmpty()) return@launchImagePicker
                            if (existingCount + uris.size > AttachmentEditUploader.MAX_ATTACHMENTS) {
                                Utils.showToast(
                                    "Cannot add ${uris.size} attachments. Only $remainingSlots slot(s) remaining (max 10).",
                                    true
                                )
                                return@launchImagePicker
                            }
                            Utils.showToast("Uploading ${uris.size} attachment(s)...", false)
                            Utils.threadPool.execute {
                                try {
                                    AttachmentEditUploader.addAttachments(channelId, messageId, uris, null)
                                    Utils.mainThread.post {
                                        Utils.showToast("Attachment(s) added.", false)
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
                                AttachmentEditUploader.addAttachments(
                                    channelId,
                                    messageId,
                                    listOf(contentUri),
                                    null
                                )
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

                        val editedText = cf.args[0] as? String
                        val channelId = message.getChannelId()
                        val messageId = message.getId()

                        onSendResult?.invoke(true)
                        StoreStream.getChat().setEditingMessage(null)
                        cf.result = null

                        Utils.showToast("Saving edit & uploading ${uris.size} attachment(s)...", false)
                        Utils.threadPool.execute {
                            try {
                                AttachmentEditUploader.addAttachments(
                                    channelId,
                                    messageId,
                                    uris,
                                    editedText
                                )
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
                                AttachmentEditUploader.addAttachments(
                                    channelId,
                                    messageId,
                                    uris,
                                    editedText
                                )
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

    private fun launchImagePicker(maxSelection: Int, callback: (List<Uri>) -> Unit) {
        pickerCallback = callback
        val intent = Intent(Intent.ACTION_GET_CONTENT).apply {
            type = "image/*"
            putExtra(Intent.EXTRA_ALLOW_MULTIPLE, maxSelection > 1)
            if (maxSelection > 1) {
                putExtra(EXTRA_PICK_IMAGES_MAX, maxSelection)
            }
        }
        try {
            Utils.appActivity.startActivityForResult(
                Intent.createChooser(intent, "Select up to $maxSelection image(s)"),
                PICK_IMAGE_REQUEST_CODE
            )
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
                val selectedUris = ArrayList<Uri>()
                if (resultCode == Activ
