package com.telesync.foldersync

import android.content.Context
import android.net.Uri
import android.webkit.MimeTypeMap
import androidx.documentfile.provider.DocumentFile
import androidx.work.Worker
import androidx.work.WorkerParameters
import org.json.JSONObject

class SyncWorker(context: Context, params: WorkerParameters) : Worker(context, params) {

    override fun doWork(): Result {
        val prefs = applicationContext.getSharedPreferences("sync_prefs", Context.MODE_PRIVATE)
        val folderUriStr = prefs.getString(MainActivity.KEY_FOLDER_URI, null)
        val botToken = prefs.getString(MainActivity.KEY_BOT_TOKEN, null)
        val chatId = prefs.getString(MainActivity.KEY_CHAT_ID, null)

        if (folderUriStr.isNullOrBlank() || botToken.isNullOrBlank() || chatId.isNullOrBlank()) {
            return Result.failure()
        }

        val folder = DocumentFile.fromTreeUri(applicationContext, Uri.parse(folderUriStr))
            ?: return Result.failure()

        val stateStore = SyncStateStore(applicationContext)
        val state = stateStore.load()
        val filesState = state.optJSONObject("files") ?: JSONObject().also { state.put("files", it) }
        var lastOffset = state.optLong("offset", 0L)

        val me = TelegramApi.getMe(botToken)
        val botId = me?.optLong("id") ?: -1L

        // ফোন -> টেলিগ্রাম
        folder.listFiles().forEach { doc ->
            if (doc.isFile && doc.name != null) {
                val name = doc.name!!
                val size = doc.length()
                val modified = doc.lastModified()
                val known = filesState.optJSONObject(name)
                val changed = known == null || known.optLong("size") != size || known.optLong("modified") != modified

                if (changed) {
                    val mime = doc.type ?: guessMime(name)
                    val input = applicationContext.contentResolver.openInputStream(doc.uri)
                    if (input != null) {
                        val result = TelegramApi.sendDocument(botToken, chatId, name, mime, input)
                        if (result != null) {
                            val entry = JSONObject()
                            entry.put("size", size)
                            entry.put("modified", modified)
                            entry.put("message_id", result.optLong("message_id"))
                            filesState.put(name, entry)
                        }
                    }
                }
            }
        }

        // টেলিগ্রাম -> ফোন
        val updates = TelegramApi.getUpdates(botToken, lastOffset)
        for (i in 0 until updates.length()) {
            val update = updates.getJSONObject(i)
            lastOffset = update.optLong("update_id") + 1

            val message = update.optJSONObject("message") ?: update.optJSONObject("channel_post")
            if (message != null) {
                val fromBot = message.optJSONObject("from")?.optLong("id") == botId
                val document = message.optJSONObject("document")
                val msgChatId = message.optJSONObject("chat")?.optLong("id")?.toString()

                if (!fromBot && document != null && msgChatId == chatId) {
                    val fileName = document.optString("file_name", "file_${System.currentTimeMillis()}")
                    val fileId = document.optString("file_id")
                    val alreadyHandled = filesState.optJSONObject(fileName)?.optString("tg_file_id") == fileId

                    if (!alreadyHandled) {
                        val filePath = TelegramApi.getFilePath(botToken, fileId)
                        if (filePath != null) {
                            var target = folder.findFile(fileName)
                            if (target == null) {
                                target = folder.createFile(guessMime(fileName), fileName)
                            }
                            if (target != null) {
                                val out = applicationContext.contentResolver.openOutputStream(target.uri, "wt")
                                if (out != null) {
                                    val ok = TelegramApi.downloadFile(botToken, filePath, out)
                                    out.close()
                                    if (ok) {
                                        val entry = JSONObject()
                                        entry.put("size", target.length())
                                        entry.put("modified", target.lastModified())
                                        entry.put("tg_file_id", fileId)
                                        filesState.put(fileName, entry)
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        state.put("offset", lastOffset)
        stateStore.save(state)

        return Result.success()
    }

    private fun guessMime(fileName: String): String {
        val ext = MimeTypeMap.getFileExtensionFromUrl(fileName)
        return MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext) ?: "application/octet-stream"
    }
}
