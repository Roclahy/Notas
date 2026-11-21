package com.omgodse.notally.backup

import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import org.json.JSONArray
import org.json.JSONObject

/**
 * Cliente HTTPS mínimo para Drive v3. Solo opera sobre los archivos creados
 * por Rose con el scope restringido por archivo (drive.file).
 *
 * No se suben archivos sin cifrar. Se usa upload resumable para evitar
 * almacenar una copia completa del archivo dentro de un cuerpo multipart.
 */
internal object RoseDriveApi {
    private const val API = "https://www.googleapis.com/drive/v3/files"
    private const val UPLOAD = "https://www.googleapis.com/upload/drive/v3/files"
    private const val MIME = "application/octet-stream"
    private const val FOLDER_MIME = "application/vnd.google-apps.folder"

    private fun connection(url: String, method: String, token: String): HttpURLConnection =
        (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = 20_000
            readTimeout = 80_000
            useCaches = false
            instanceFollowRedirects = false
            setRequestProperty("Authorization", "Bearer $token")
            setRequestProperty("Accept", "application/json")
        }

    private fun checkResponse(c: HttpURLConnection): String {
        val code = c.responseCode
        if (code !in 200..299) {
            // No registrar token, contraseña ni datos de usuario en los errores.
            throw java.io.IOException("Google Drive HTTP $code")
        }
        return c.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
    }

    private fun getJson(url: String, token: String): JSONObject {
        val c = connection(url,"GET",token)
        return try { JSONObject(checkResponse(c)) } finally { c.disconnect() }
    }

    private fun postJson(url: String, token: String, data: JSONObject): JSONObject {
        val c = connection(url,"POST",token).apply {
            doOutput = true
            setRequestProperty("Content-Type", "application/json; charset=UTF-8")
        }
        return try {
            c.outputStream.use { it.write(data.toString().toByteArray(Charsets.UTF_8)) }
            JSONObject(checkResponse(c))
        } finally { c.disconnect() }
    }

    private fun lookupFolder(token: String): String? {
        val q = "name = 'Rose' and mimeType = '$FOLDER_MIME' and trashed = false"
        val url = "$API?spaces=drive&pageSize=50&fields=nextPageToken,files(id,name)" +
            "&q=" + URLEncoder.encode(q,"UTF-8")
        val files = getJson(url,token).optJSONArray("files") ?: return null
        for (i in 0 until files.length()) {
            val id = files.optJSONObject(i)?.optString("id")
            if (!id.isNullOrBlank()) return id
        }
        return null
    }

    private fun folder(token: String): String {
        lookupFolder(token)?.let { return it }
        val metadata = JSONObject()
            .put("name", "Rose")
            .put("mimeType", FOLDER_MIME)
        return postJson("$API?fields=id,name",token,metadata).getString("id")
    }

    private fun uploadEncrypted(token: String, parent: String, file: File, name: String) {
        val metadata = JSONObject()
            .put("name",name)
            .put("mimeType",MIME)
            .put("parents", JSONArray().put(parent))
        val start = connection("$UPLOAD?uploadType=resumable&fields=id,name", "POST", token).apply {
            doOutput = true
            setRequestProperty("Content-Type", "application/json; charset=UTF-8")
            setRequestProperty("X-Upload-Content-Type", MIME)
            setRequestProperty("X-Upload-Content-Length", file.length().toString())
        }
        val session = try {
            start.outputStream.use { it.write(metadata.toString().toByteArray(Charsets.UTF_8)) }
            if (start.responseCode !in 200..299) throw java.io.IOException("Drive session HTTP ${start.responseCode}")
            requireNotNull(start.getHeaderField("Location")) { "Missing Drive upload session" }
        } finally { start.disconnect() }
        require(session.startsWith("https://www.googleapis.com/")) { "Unexpected Drive upload endpoint" }

        val upload = connection(session,"PUT",token).apply {
            doOutput = true
            setRequestProperty("Content-Type",MIME)
            setFixedLengthStreamingMode(file.length())
        }
        try {
            file.inputStream().use { input ->
                upload.outputStream.use { output -> input.copyTo(output,64*1024) }
            }
            checkResponse(upload)
        } finally { upload.disconnect() }
    }

    private fun keepLastSeven(token: String, folder: String) {
        val q = "'$folder' in parents and trashed = false"
        val url = "$API?fields=nextPageToken,files(id,name,createdTime)&pageSize=100&" +
            "orderBy=createdTime%20desc&q=" + URLEncoder.encode(q,"UTF-8")
        val files = getJson(url,token).optJSONArray("files") ?: return
        var retained = 0
        for (i in 0 until files.length()) {
            val item = files.optJSONObject(i) ?: continue
            val name = item.optString("name")
            if (!name.startsWith("Rose-") || !name.endsWith(".rnotas")) continue
            retained++
            if (retained > 7) {
                val id = item.optString("id")
                if (id.isBlank()) continue
                try {
                    val c = connection("$API/" + URLEncoder.encode(id,"UTF-8"),"DELETE",token)
                    try { if (c.responseCode !in 200..299) throw java.io.IOException() }
                    finally { c.disconnect() }
                } catch (_: Exception) {
                    // Fallo no fatal: el respaldo nuevo ya se cargó correctamente.
                }
            }
        }
    }

    fun backup(token: String, encryptedFile: File, filename: String) {
        require(filename.matches(Regex("Rose-[0-9]{8}-[0-9]{6}\\.rnotas")))
        val destination = folder(token)
        uploadEncrypted(token,destination,encryptedFile,filename)
        try { keepLastSeven(token,destination) } catch (_: Exception) { /* cleanup no fatal */ }
    }
}
