package com.omgodse.notally.room

import androidx.room.TypeConverter
import com.omgodse.notally.diagnostics.NoteLinkTrace
import com.omgodse.notally.security.SecureFields
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

object Converters {

    @TypeConverter
    fun labelsToJson(labels: List<String>) = SecureFields.seal(JSONArray(labels).toString(), "labels")

    @TypeConverter
    fun jsonToLabels(json: String) = JSONArray(SecureFields.open(json, "labels")).iterable<String>().toList()


    @TypeConverter
    fun imagesToJson(images: List<Image>): String {
        val objects = images.map { image ->
            val jsonObject = JSONObject()
            jsonObject.put("name", image.name)
            jsonObject.put("mimeType", image.mimeType)
        }
        return SecureFields.seal(JSONArray(objects).toString(), "images")
    }

    @TypeConverter
    fun jsonToImages(json: String): List<Image> {
        val iterable = JSONArray(SecureFields.open(json, "images")).iterable<JSONObject>()
        return iterable.map { jsonObject ->
            val name = jsonObject.getString("name")
            val mimeType = jsonObject.getString("mimeType")
            Image(name, mimeType)
        }
    }


    @TypeConverter
    fun audiosToJson(audios: List<Audio>): String {
        val objects = audios.map { audio ->
            val jsonObject = JSONObject()
            jsonObject.put("name", audio.name)
            jsonObject.put("duration", audio.duration)
            jsonObject.put("timestamp", audio.timestamp)
        }
        return SecureFields.seal(JSONArray(objects).toString(), "audios")
    }

    @TypeConverter
    fun jsonToAudios(json: String): List<Audio> {
        val iterable = JSONArray(SecureFields.open(json, "audios")).iterable<JSONObject>()
        return iterable.map { jsonObject ->
            val name = jsonObject.getString("name")
            val duration = jsonObject.getLong("duration")
            val timestamp = jsonObject.getLong("timestamp")
            Audio(name, duration, timestamp)
        }
    }


    @TypeConverter
    fun reminderToJson(reminder: Reminder): String {
        return JSONObject()
            .put("timestamp", reminder.timestamp)
            .put("frequency", reminder.frequency.name)
            .toString().let { SecureFields.seal(it, "reminder") }
    }

    @TypeConverter
    fun jsonToReminder(json: String): Reminder {
        val jsonObject = JSONObject(SecureFields.open(json, "reminder"))
        val timestamp = jsonObject.getLong("timestamp")
        val frequency = Frequency.valueOf(jsonObject.getString("frequency"))
        return Reminder(timestamp, frequency)
    }


    @TypeConverter
    fun jsonToSpans(json: String): List<SpanRepresentation> {
        // Los vínculos son metadatos de presentación: si uno está dañado,
        // JAMÁS impedir que se abran las notas, ni borrar el campo de Room.
        val array = try {
            JSONArray(SecureFields.open(json, "spans"))
        } catch (error: Exception) {
            NoteLinkTrace.recordIfReady("spans.decode.EXCEPTION", "encryptedLength=${json.length}", error)
            android.util.Log.e("NotasLinkRecovery", "Unable to read span metadata: ${error.javaClass.simpleName}")
            return emptyList()
        }
        val result = ArrayList<SpanRepresentation>(minOf(array.length(), 8192))
        for (index in 0 until minOf(array.length(), 8192)) {
            try {
                val raw = array.opt(index)
                val item = when (raw) {
                    is JSONObject -> raw
                    // Support a legacy JSON-string object if one was produced by
                    // a previous build; no note content is logged.
                    is String -> {
                        if (raw.length > 16_384) throw JSONException("Oversized legacy span")
                        val recovered = JSONObject(raw)
                        NoteLinkTrace.recordIfReady("spans.legacyString.recovered", "index=$index")
                        recovered
                    }
                    else -> {
                        // Old spansToJSONArray() returned null when imageName was
                        // absent because the last expression was a nullable let.
                        // A null has no link destination that can be recovered.
                        NoteLinkTrace.recordIfReady("spans.legacyNull.unrecoverable", "index=$index")
                        continue
                    }
                }
                val start = item.getInt("start")
                val end = item.getInt("end")
                if (start < 0 || end <= start || end > 1_000_000) continue
                val url = item.optString("url").takeIf { it.isNotBlank() && it.length <= 4096 }
                val imageName = item.optString("imageName").takeIf { it.isNotBlank() }
                result.add(SpanRepresentation(
                    item.getSafeBoolean("bold"),
                    item.getSafeBoolean("link"),
                    item.getSafeBoolean("italic"),
                    item.getSafeBoolean("monospace"),
                    item.getSafeBoolean("strikethrough"),
                    start, end, url,
                    item.optBoolean("highlighted", false), imageName
                ))
            } catch (error: Exception) {
                NoteLinkTrace.recordIfReady("spans.entry.EXCEPTION", "index=$index", error)
                android.util.Log.w("NotasLinkRecovery", "Skipping malformed display span #$index")
            }
        }
        return result
    }

    @TypeConverter
    fun spansToJson(list: List<SpanRepresentation>): String {
        val array = spansToJSONArray(list)
        // Fail the save rather than silently replace links with a JSON null.
        // Verify the actual encoded representation, not just the in-memory list.
        val encoded = array.toString()
        val decoded = JSONArray(encoded)
        check(decoded.length() == list.size &&
            (0 until decoded.length()).all { decoded.optJSONObject(it) != null }) {
            "The note spans could not be serialized as JSON objects"
        }
        return SecureFields.seal(encoded, "spans")
    }


    @TypeConverter
    fun jsonToItems(json: String): List<ListItem> {
        val iterable = JSONArray(SecureFields.open(json, "items")).iterable<JSONObject>()
        return iterable.map { jsonObject ->
            val body = jsonObject.getString("body")
            val checked = jsonObject.getBoolean("checked")
            ListItem(body, checked)
        }
    }

    @TypeConverter
    fun itemsToJson(list: List<ListItem>) = SecureFields.seal(itemsToJSONArray(list).toString(), "items")


    fun itemsToJSONArray(list: List<ListItem>): JSONArray {
        val objects = list.map { item ->
            val jsonObject = JSONObject()
            jsonObject.put("body", item.body)
            jsonObject.put("checked", item.checked)
        }
        return JSONArray(objects)
    }

    fun spansToJSONArray(list: List<SpanRepresentation>): JSONArray {
        // NEVER use list.map { ... last nullable expression ... } here.
        // It used to produce a list of nulls for every span without imageName.
        val array = JSONArray()
        for (representation in list) {
            val jsonObject = JSONObject()
            jsonObject.put("bold", representation.bold)
            jsonObject.put("link", representation.link)
            jsonObject.put("italic", representation.italic)
            jsonObject.put("monospace", representation.monospace)
            jsonObject.put("strikethrough", representation.strikethrough)
            jsonObject.put("start", representation.start)
            jsonObject.put("end", representation.end)
            representation.url?.let { jsonObject.put("url", it) }
            if (representation.highlighted) jsonObject.put("highlighted", true)
            representation.imageName?.let { jsonObject.put("imageName", it) }
            array.put(jsonObject)
        }
        return array
    }

    private fun JSONObject.getSafeBoolean(name: String): Boolean {
        return try {
            getBoolean(name)
        } catch (exception: JSONException) {
            false
        }
    }


    private fun <T> JSONArray.iterable() = Iterable {
        object : Iterator<T> {
            var index = 0

            override fun next(): T {
                val element = get(index)
                index++
                return element as T
            }

            override fun hasNext(): Boolean {
                return index < length()
            }
        }
    }
}