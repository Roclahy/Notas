package com.omgodse.notally.room

import androidx.room.TypeConverter
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
        val iterable = JSONArray(SecureFields.open(json, "spans")).iterable<JSONObject>()
        return iterable.map { jsonObject ->
            val bold = jsonObject.getSafeBoolean("bold")
            val link = jsonObject.getSafeBoolean("link")
            val italic = jsonObject.getSafeBoolean("italic")
            val monospace = jsonObject.getSafeBoolean("monospace")
            val strikethrough = jsonObject.getSafeBoolean("strikethrough")
            val start = jsonObject.getInt("start")
            val end = jsonObject.getInt("end")
            val url = jsonObject.optString("url").takeIf { it.isNotBlank() }
            SpanRepresentation(bold, link, italic, monospace, strikethrough, start, end, url)
        }
    }

    @TypeConverter
    fun spansToJson(list: List<SpanRepresentation>) = SecureFields.seal(spansToJSONArray(list).toString(), "spans")


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
        val objects = list.map { representation ->
            val jsonObject = JSONObject()
            jsonObject.put("bold", representation.bold)
            jsonObject.put("link", representation.link)
            jsonObject.put("italic", representation.italic)
            jsonObject.put("monospace", representation.monospace)
            jsonObject.put("strikethrough", representation.strikethrough)
            jsonObject.put("start", representation.start)
            jsonObject.put("end", representation.end)
            representation.url?.let { jsonObject.put("url", it) }
        }
        return JSONArray(objects)
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