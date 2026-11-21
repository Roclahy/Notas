package com.omgodse.notally

import android.app.Application
import android.app.Activity
import android.os.Bundle
import com.omgodse.notally.security.PinSecurity
import androidx.appcompat.app.AppCompatDelegate
import com.omgodse.notally.backup.BackupScheduler
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequest
import androidx.work.WorkManager
import com.omgodse.notally.preferences.Preferences
import com.omgodse.notally.preferences.Theme
import java.util.concurrent.TimeUnit

class NotallyApplication : Application() {

    override fun onCreate() {
        super.onCreate()
        registerActivityLifecycleCallbacks(object : ActivityLifecycleCallbacks {
            private var visibleActivities = 0
            override fun onActivityCreated(activity: Activity, state: Bundle?) {}
            override fun onActivityStarted(activity: Activity) { visibleActivities++ }
            override fun onActivityResumed(activity: Activity) {}
            override fun onActivityPaused(activity: Activity) {}
            override fun onActivityStopped(activity: Activity) {
                visibleActivities = (visibleActivities - 1).coerceAtLeast(0)
                if (visibleActivities == 0) PinSecurity.appUnlocked = false
            }
            override fun onActivitySaveInstanceState(activity: Activity, state: Bundle) {}
            override fun onActivityDestroyed(activity: Activity) {}
        })

        val preferences = Preferences.getInstance(this)
        preferences.theme.observeForever { theme ->
            when (theme) {
                Theme.dark, Theme.amoled -> AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_YES)
                Theme.light -> AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_NO)
                Theme.followSystem -> AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM)
            }
        }

        BackupScheduler.schedule(this)
    }
}