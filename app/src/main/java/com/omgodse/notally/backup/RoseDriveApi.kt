package com.omgodse.notally.backup

import android.content.Context
import java.io.File
import java.security.MessageDigest
import java.util.UUID
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import org.json.JSONArray
import org.json.JSONObject

/**
 * Cliente HTTPS mínimo para Drive v3. Solo opera sobre los archivos creados
 * por Notas con el scope restringido por archivo (drive.file).
 *
 * No se suben archivos sin cifrar. Se usa upload resumable para evitar
 * almacenar una copia completa del archivo dentro de un cuerpo multipart.
 */
internal object RoseDriveApi {
    private const val API = "https://www.googleapis.com/drive/v3/files"
    private const val UPLOAD = "https://www.googleapis.com/upload/drive/v3/files"
    private const val MIME = "application/octet-stream"
    private const val FOLDER_MIME = "application/vnd.google-apps.folder"
    private const val SMALL_BACKUP = 5L * 1024L * 1024L
    @Volatile private var cachedFolder: Pair<String, String>? = null

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
        val q = "name = 'Notas' and mimeType = '$FOLDER_MIME' and trashed = false"
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
        // Cache ligada a la autorización actual; no mezclar carpetas de cuentas.
        val key = MessageDigest.getInstance("SHA-256")
            .digest(token.toByteArray(Charsets.UTF_8))
            .take(12).joinToString("") { "%02x".format(it) }
        cachedFolder?.takeIf { it.first == key }?.second?.let { return it }
        val id = lookupFolder(token) ?: postJson("$API?fields=id,name", token,
            JSONObject().put("name", "Notas").put("mimeType", FOLDER_MIME)).getString("id")
        cachedFolder = key to id
        return id
    }

    /**
     * Para archivos cifrados pequeños, Drive recomienda multipart: metadatos
     * y contenido en un único POST. Evita abrir una sesión resumable extra.
     */
    private fun uploadSmallEncrypted(token: String, parent: String, file: File, name: String) {
        val boundary = "RoseEncrypted" + UUID.randomUUID().toString().replace("-", "")
        val meta = JSONObject().put("name", name)
            .put("mimeType", MIME).put("parents", JSONArray().put(parent))
        val before = ("--$boundary\r\nContent-Type: application/json; charset=UTF-8\r\n\r\n" +
            meta.toString() + "\r\n--$boundary\r\nContent-Type: $MIME\r\n\r\n")
            .toByteArray(Charsets.UTF_8)
        val after = "\r\n--$boundary--\r\n".toByteArray(Charsets.US_ASCII)
        val url = "$UPLOAD?uploadType=multipart&fields=id,name"
        val request = connection(url, "POST", token).apply {
            doOutput = true
            setRequestProperty("Content-Type", "multipart/related; boundary=$boundary")
            setFixedLengthStreamingMode(file.length() + before.size + after.size)
        }
        try {
            request.outputStream.use { output ->
                output.write(before)
                file.inputStream().use { it.copyTo(output, 64 * 1024) }
                output.write(after)
            }
            checkResponse(request)
        } finally { request.disconnect() }
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
            if (!name.startsWith("Notas-") || !name.endsWith(".rnotas")) continue
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

    data class RemoteBackup(val id: String, val name: String, val createdTime: String)

    fun listBackups(token: String): List<RemoteBackup> {
        val parent = lookupFolder(token) ?: return emptyList()
        val q = "'$parent' in parents and trashed = false"
        val url = "$API?spaces=drive&pageSize=100&fields=files(id,name,createdTime)" +
            "&orderBy=createdTime%20desc&q=" + URLEncoder.encode(q, "UTF-8")
        val items = getJson(url, token).optJSONArray("files") ?: return emptyList()
        return buildList {
            for (i in 0 until items.length()) {
                val item = items.optJSONObject(i) ?: continue
                val name = item.optString("name")
                val id = item.optString("id")
                if (id.isNotBlank() && name.matches(
                        Regex("Notas-[0-9]{8}-[0-9]{6}\\.rnotas"))) {
                    add(RemoteBackup(id, name, item.optString("createdTime")))
                }
            }
        }
    }

    /** Descarga cifrada con límite de tamaño; nunca guarda un ZIP legible. */
    fun download(token: String, id: String, destination: File) {
        require(id.matches(Regex("[A-Za-z0-9_-]+")))
        val url = "$API/" + URLEncoder.encode(id, "UTF-8") + "?alt=media"
        val connection = connection(url, "GET", token)
        try {
            val status = connection.responseCode
            if (status !in 200..299) throw java.io.IOException("Google Drive HTTP $status")
            val limit = 500L * 1024L * 1024L
            require(connection.contentLengthLong <= limit || connection.contentLengthLong < 0)
            var read = 0L
            connection.inputStream.use { input ->
                destination.outputStream().use { output ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        val amount = input.read(buffer)
                        if (amount < 0) break
                        read += amount
                        if (read > limit) throw java.io.IOException("Backup too large")
                        output.write(buffer, 0, amount)
                    }
                }
            }
        } catch (error: Exception) {
            destination.delete()
            throw error
        } finally {
            connection.disconnect()
        }
    }

    fun backup(context: Context, token: String, encryptedFile: File, filename: String) {
        require(filename.matches(Regex("Notas-[0-9]{8}-[0-9]{6}\\.rnotas")))
        val destination = folder(token)
        if (encryptedFile.length() <= SMALL_BACKUP)
            uploadSmallEncrypted(token, destination, encryptedFile, filename)
        else uploadEncrypted(token, destination, encryptedFile, filename)

        // La limpieza remota se ejecuta solo cada cuarta copia. No retrasa
        // cada guardado (especialmente importante con enlaces lentos).
        val preferences = context.getSharedPreferences("rose_drive_cleanup", Context.MODE_PRIVATE)
        val count = preferences.getInt("uploads", 0) + 1
        preferences.edit().putInt("uploads", count).apply()
        if (count % 4 == 0) {
            try { keepLastSeven(token, destination) } catch (_: Exception) { /* no fatal */ }
        }
    }
}
