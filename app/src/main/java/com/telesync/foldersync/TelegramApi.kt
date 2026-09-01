package com.telesync.foldersync

import org.json.JSONArray
import org.json.JSONObject
import java.io.InputStream
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID

object TelegramApi {

    private const val BASE = "https://api.telegram.org/bot"
    private const val FILE_BASE = "https://api.telegram.org/file/bot"

    fun getMe(token: String): JSONObject? {
        val conn = URL("$BASE$token/getMe").openConnection() as HttpURLConnection
        return try {
            conn.requestMethod = "GET"
            val resp = conn.inputStream.bufferedReader().readText()
            JSONObject(resp).optJSONObject("result")
        } catch (e: Exception) {
            null
        } finally {
            conn.disconnect()
        }
    }

    fun getUpdates(token: String, offset: Long): JSONArray {
        val conn = URL("$BASE$token/getUpdates?offset=$offset&timeout=0").openConnection() as HttpURLConnection
        return try {
            conn.requestMethod = "GET"
            conn.connectTimeout = 15000
            conn.readTimeout = 15000
            val resp = conn.inputStream.bufferedReader().readText()
            JSONObject(resp).optJSONArray("result") ?: JSONArray()
        } catch (e: Exception) {
            JSONArray()
        } finally {
            conn.disconnect()
        }
    }

    fun getFilePath(token: String, fileId: String): String? {
        val conn = URL("$BASE$token/getFile?file_id=$fileId").openConnection() as HttpURLConnection
        return try {
            conn.requestMethod = "GET"
            val resp = conn.inputStream.bufferedReader().readText()
            JSONObject(resp).optJSONObject("result")?.optString("file_path")
        } catch (e: Exception) {
            null
        } finally {
            conn.disconnect()
        }
    }

    fun downloadFile(token: String, filePath: String, out: OutputStream): Boolean {
        val conn = URL("$FILE_BASE$token/$filePath").openConnection() as HttpURLConnection
        return try {
            conn.requestMethod = "GET"
            conn.inputStream.use { input -> input.copyTo(out) }
            true
        } catch (e: Exception) {
            false
        } finally {
            conn.disconnect()
        }
    }

    fun sendDocument(
        token: String,
        chatId: String,
        filename: String,
        mimeType: String,
        inputStream: InputStream
    ): JSONObject? {
        val boundary = "----TeleSync${UUID.randomUUID()}"
        val conn = URL("$BASE$token/sendDocument").openConnection() as HttpURLConnection
        return try {
            conn.doOutput = true
            conn.requestMethod = "POST"
            conn.connectTimeout = 20000
            conn.readTimeout = 60000
            conn.setRequestProperty("Content-Type", "multipart/form-data; boundary=$boundary")

            val out = conn.outputStream
            out.write("--$boundary\r\n".toByteArray())
            out.write("Content-Disposition: form-data; name=\"chat_id\"\r\n\r\n".toByteArray())
            out.write("$chatId\r\n".toByteArray())

            out.write("--$boundary\r\n".toByteArray())
            out.write("Content-Disposition: form-data; name=\"document\"; filename=\"$filename\"\r\n".toByteArray())
            out.write("Content-Type: $mimeType\r\n\r\n".toByteArray())
            inputStream.use { it.copyTo(out) }
            out.write("\r\n".toByteArray())
            out.write("--$boundary--\r\n".toByteArray())
            out.flush()
            out.close()

            val respCode = conn.responseCode
            val stream = if (respCode in 200..299) conn.inputStream else conn.errorStream
            val resp = stream.bufferedReader().readText()
            val json = JSONObject(resp)
            if (json.optBoolean("ok")) json.optJSONObject("result") else null
        } catch (e: Exception) {
            null
        } finally {
            conn.disconnect()
        }
    }
}
