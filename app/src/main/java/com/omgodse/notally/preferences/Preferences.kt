package com.omgodse.notally.preferences

import android.app.Application
import android.preference.PreferenceManager

/**
 * Custom implementation of androidx.preference library
 * Way faster, simpler and smaller, logic of storing preferences has been decoupled
 * from their UI.
 * It is backed by SharedPreferences but it should be trivial to shift to another
 * source if needed.
 */
class Preferences private constructor(app: Application) {

    private val preferences = PreferenceManager.getDefaultSharedPreferences(app)
    private val editor = preferences.edit()

    // Eliminar por completo la antigua opción Glass; preservar el tema oscuro.
    init {
        val edit = preferences.edit().remove("liquidGlassEnabled")
        if (preferences.getString(Theme.key, Theme.defaultValue) == Theme.glass)
            edit.putString(Theme.key, Theme.dark)
        edit.apply()
    }

    // Main thread (unfortunately)
    val view = BetterLiveData(getListPref(View))
    val theme = BetterLiveData(getListPref(Theme))
    val dateFormat = BetterLiveData(getListPref(DateFormat))

    val textSize = BetterLiveData(getListPref(TextSize))
    var maxItems = getSeekbarPref(MaxItems)
    var maxLines = getSeekbarPref(MaxLines)
    var maxTitle = getSeekbarPref(MaxTitle)

    val autoBackup = BetterLiveData(getTextPref(AutoBackup))

    private fun getListPref(info: ListInfo) = requireNotNull(preferences.getString(info.key, info.defaultValue))

    private fun getTextPref(info: TextInfo) = requireNotNull(preferences.getString(info.key, info.defaultValue))

    private fun getSeekbarPref(info: SeekbarInfo) = requireNotNull(preferences.getInt(info.key, info.defaultValue))


    fun getWidgetData(id: Int) = preferences.getLong("widget:$id", 0)

    fun deleteWidget(id: Int) {
        editor.remove("widget:$id")
        editor.commit()
    }

    fun updateWidget(id: Int, noteId: Long) {
        editor.putLong("widget:$id", noteId)
        editor.commit()
    }

    fun getUpdatableWidgets(noteIds: LongArray): List<Pair<Int, Long>> {
        val updatableWidgets = ArrayList<Pair<Int, Long>>()
        val pairs = preferences.all
        pairs.keys.forEach { key ->
            val token = "widget:"
            if (key.startsWith(token)) {
                val end = key.substringAfter(token)
                val id = end.toIntOrNull()
                if (id != null) {
                    val value = pairs[key] as? Long
                    if (value != null) {
                        if (noteIds.contains(value)) {
                            updatableWidgets.add(Pair(id, value))
                        }
                    }
                }
            }
        }
        return updatableWidgets
    }


    fun savePreference(info: SeekbarInfo, value: Int) {
        editor.putInt(info.key, value)
        editor.commit()
        when (info) {
            MaxItems -> maxItems = getSeekbarPref(MaxItems)
            MaxLines -> maxLines = getSeekbarPref(MaxLines)
            MaxTitle -> maxTitle = getSeekbarPref(MaxTitle)
        }
    }

    fun savePreference(info: ListInfo, value: String) {
        editor.putString(info.key, value)
        editor.commit()
        when (info) {
            View -> view.postValue(getListPref(info))
            Theme -> theme.postValue(getListPref(info))
            DateFormat -> dateFormat.postValue(getListPref(info))
            TextSize -> textSize.postValue(getListPref(info))
        }
    }

    fun savePreference(info: TextInfo, value: String) {
        editor.putString(info.key, value)
        editor.commit()
        when (info) {
            AutoBackup -> autoBackup.postValue(getTextPref(info))
        }
    }


    /** Actualiza los valores vivos después de restaurar preferencias autenticadas. */
    fun reloadAfterBackupRestore() {
        if (getListPref(Theme) == Theme.glass) {
            preferences.edit().putString(Theme.key, Theme.dark)
                .remove("liquidGlassEnabled").apply()
        }
        view.postValue(getListPref(View))
        theme.postValue(getListPref(Theme))
        dateFormat.postValue(getListPref(DateFormat))
        textSize.postValue(getListPref(TextSize))
        maxItems = getSeekbarPref(MaxItems)
        maxLines = getSeekbarPref(MaxLines)
        maxTitle = getSeekbarPref(MaxTitle)
    }

    fun isAmoledTheme(): Boolean = getListPref(Theme) == Theme.amoled


    fun showDateCreated(): Boolean {
        return dateFormat.value != DateFormat.none
    }

    companion object {

        @Volatile
        private var instance: Preferences? = null

        fun getInstance(app: Application): Preferences {
            return instance ?: synchronized(this) {
                val instance = Preferences(app)
                this.instance = instance
                return instance
            }
        }
    }
}