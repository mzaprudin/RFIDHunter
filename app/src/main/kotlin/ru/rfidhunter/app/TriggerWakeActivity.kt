package ru.rfidhunter.app

import android.app.Activity
import android.app.NotificationManager
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.view.WindowManager

/**
 * Lock-screen trampoline for a physical IH25 trigger.
 *
 * Android 15+ may allow a PendingIntent itself while still refusing to bring
 * its existing background task to the foreground. This activity lives in a
 * separate, transient task. Once it is resumed, it can safely foreground the
 * real application because the caller now has a visible activity.
 */
class TriggerWakeActivity : Activity() {
    private var forwarded = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        intent.getIntExtra(EXTRA_NOTIFICATION_ID, 0)
            .takeIf { it != 0 }
            ?.let { getSystemService(NotificationManager::class.java).cancel(it) }
        @Suppress("DEPRECATION")
        window.addFlags(
            WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON,
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        }
    }

    override fun onPostResume() {
        super.onPostResume()
        if (forwarded) return
        forwarded = true

        // Post once so WindowManager has committed this activity as visible
        // before MainActivity's existing task is requested.
        window.decorView.post {
            startActivity(
                Intent(this, MainActivity::class.java)
                    .addFlags(
                        Intent.FLAG_ACTIVITY_NEW_TASK or
                            Intent.FLAG_ACTIVITY_SINGLE_TOP or
                            Intent.FLAG_ACTIVITY_CLEAR_TOP,
                    )
                    .putExtra(MainActivity.EXTRA_TRIGGER_ON_START, true),
            )
            @Suppress("DEPRECATION")
            overridePendingTransition(0, 0)
            finishAndRemoveTask()
        }
    }

    companion object {
        const val EXTRA_NOTIFICATION_ID = "ru.rfidhunter.app.EXTRA_TRIGGER_NOTIFICATION_ID"
    }
}
