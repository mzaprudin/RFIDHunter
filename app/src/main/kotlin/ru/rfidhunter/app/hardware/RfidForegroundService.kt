package ru.rfidhunter.app.hardware

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.ActivityOptions
import android.app.ActivityManager
import android.app.KeyguardManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.IBinder
import android.os.SystemClock
import android.provider.Settings
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.TextView
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import ru.rfidhunter.app.BuildConfig
import ru.rfidhunter.app.RfidHunterApplication
import ru.rfidhunter.app.TriggerWakeActivity
import ru.rfidhunter.app.domain.TriggerEvent
import kotlin.math.roundToInt

class RfidForegroundService : Service() {
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val wakeHandler = Handler(Looper.getMainLooper())
    private var triggerWakeLock: PowerManager.WakeLock? = null
    private val releaseTriggerWake = Runnable { releaseTriggerWakeLock() }
    private var triggerRequestSequence = (SystemClock.elapsedRealtime() and 0x0fff_ffff).toInt()
    private var triggerOverlayView: View? = null
    private var triggerOverlayLaunch: Runnable? = null

    override fun onCreate() {
        super.onCreate()
        val manager = getSystemService(NotificationManager::class.java)
        // Remove the fixed-id notification used by older debug builds. A
        // still-active notification is only updated by notify(), and Android
        // does not launch its full-screen intent a second time.
        manager.cancel(LEGACY_TRIGGER_NOTIFICATION_ID)
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "RFID-считыватель", NotificationManager.IMPORTANCE_LOW),
        )
        val notification: Notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
            .setContentTitle("Искатель вещей")
            .setContentText("Считыватель готов к работе")
            .setOngoing(true)
            .build()
        startForeground(NOTIFICATION_ID, notification)

        val gateway = (application as RfidHunterApplication).rfidGateway
        serviceScope.launch {
            gateway.triggerEvents.collectLatest { event ->
                Log.i(TAG, "IH25 trigger event=$event")
                if (event == TriggerEvent.PRESSED) bringActivityForwardForTrigger()
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_REMOVE_TRIGGER_OVERLAY) {
            removeTriggerOverlay()
            return START_STICKY
        }
        // Gives hardwareDebug builds a repeatable ADB smoke test for Samsung's
        // background-activity policy without pretending that an RFID tag was
        // read. The non-exported service and BuildConfig guard keep this path
        // out of the production UX.
        if (BuildConfig.DEBUG && intent?.action == ACTION_DEBUG_TRIGGER) {
            Log.i(TAG, "Simulating IH25 trigger from debug service action")
            bringActivityForwardForTrigger()
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        wakeHandler.removeCallbacks(releaseTriggerWake)
        removeTriggerOverlay()
        releaseTriggerWakeLock()
        serviceScope.cancel()
        super.onDestroy()
    }

    @Suppress("DEPRECATION")
    private fun wakeScreenForTrigger() {
        val powerManager = getSystemService(POWER_SERVICE) as? PowerManager ?: return
        if (powerManager.isInteractive) return
        try {
            val wakeLock = triggerWakeLock ?: powerManager.newWakeLock(
                // FULL_WAKE_LOCK is deprecated but remains the most compatible
                // screen-wake path on Samsung devices. The Activity API below is
                // still the primary modern path; this lock covers an already
                // resumed Activity whose display has just gone to sleep.
                PowerManager.FULL_WAKE_LOCK or
                    PowerManager.ACQUIRE_CAUSES_WAKEUP or
                    PowerManager.ON_AFTER_RELEASE,
                "$packageName:rfid-trigger",
            ).also {
                it.setReferenceCounted(false)
                triggerWakeLock = it
            }
            wakeHandler.removeCallbacks(releaseTriggerWake)
            if (wakeLock.isHeld) wakeLock.release()
            wakeLock.acquire(TRIGGER_WAKE_DURATION_MS)
            Log.i(TAG, "Screen wake-lock acquired for ${TRIGGER_WAKE_DURATION_MS}ms")
            // Explicitly release as well as using the timeout, so lifecycle and
            // repeated trigger events cannot leave a held lock behind.
            wakeHandler.postDelayed(releaseTriggerWake, TRIGGER_WAKE_DURATION_MS)
        } catch (error: RuntimeException) {
            // Some OEMs reject screen wake locks from background services. The
            // activity's turnScreenOn/showWhenLocked flags remain as a fallback.
            Log.w(TAG, "Screen wake-lock rejected; relying on Activity turnScreenOn", error)
        }
    }

    private fun releaseTriggerWakeLock() {
        triggerWakeLock?.let { lock ->
            try {
                if (lock.isHeld) lock.release()
            } catch (_: RuntimeException) {
                // The service may be torn down concurrently with the timeout.
            }
        }
    }

    private fun bringActivityForwardForTrigger() {
        if (isMainActivityActuallyForeground()) {
            Log.i(TAG, "MainActivity already resumed; trigger wake UI skipped")
            return
        }
        val powerManager = getSystemService(POWER_SERVICE) as? PowerManager
        val keyguardManager = getSystemService(KeyguardManager::class.java)
        val needsLockScreenLaunch = powerManager?.isInteractive == false || keyguardManager?.isKeyguardLocked == true
        Log.i(TAG, "Handling trigger interactive=${powerManager?.isInteractive}")
        if (powerManager?.isInteractive == false) wakeScreenForTrigger()
        val triggerRequestCode = nextTriggerRequestCode()
        // Android 15+ refuses to bring an existing background task stack to
        // the foreground even when both PendingIntent parties opt into BAL.
        // A tiny activity with its own task affinity becomes visible first and
        // then forwards to MainActivity from the foreground.
        val activityIntent = Intent(this, TriggerWakeActivity::class.java)
            .addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_SINGLE_TOP or
                    Intent.FLAG_ACTIVITY_CLEAR_TOP,
            )
            .putExtra(TriggerWakeActivity.EXTRA_NOTIFICATION_ID, triggerRequestCode)
        val creatorOptions = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            ActivityOptions.makeBasic()
                .setPendingIntentCreatorBackgroundActivityStartMode(
                    triggerBackgroundActivityStartMode(),
                )
                .toBundle()
        } else {
            null
        }
        val pendingIntent = PendingIntent.getActivity(
            this,
            triggerRequestCode,
            activityIntent,
            // Recreate the PendingIntent so an APK update cannot leave an old
            // BAL creator policy attached to the task-launch token.
            PendingIntent.FLAG_CANCEL_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            creatorOptions,
        )
        val senderOptions = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            ActivityOptions.makeBasic()
                // The sender opts in separately when dispatching the intent.
                .setPendingIntentBackgroundActivityStartMode(
                    triggerBackgroundActivityStartMode(),
                )
                .toBundle()
        } else {
            null
        }
        if (needsLockScreenLaunch) {
            showFullScreenTriggerNotification(triggerRequestCode, pendingIntent)
        } else if (Build.VERSION.SDK_INT >= ANDROID_16_API_LEVEL) {
            if (!Settings.canDrawOverlays(this)) {
                Log.w(TAG, "Android 16 trigger overlay permission is absent; using notification fallback")
                showFullScreenTriggerNotification(triggerRequestCode, pendingIntent)
            } else {
                val overlayScheduled = showTriggerOverlayAndLaunch(activityIntent) {
                    showFullScreenTriggerNotification(triggerRequestCode, pendingIntent)
                }
                if (!overlayScheduled) {
                    Log.w(TAG, "Android 16 trigger overlay unavailable; notification fallback posted")
                    showFullScreenTriggerNotification(triggerRequestCode, pendingIntent)
                }
            }
        } else {
            runCatching {
                pendingIntent.send(this, 0, null, null, null, null, senderOptions)
                Log.i(TAG, "TriggerWakeActivity PendingIntent sent")
            }.onFailure { error ->
                Log.e(TAG, "Could not bring trigger activity to front", error)
            }
        }
    }

    /**
     * Android 16 accepts a background activity start from an app that has a
     * visible application overlay. The banner is deliberately non-touchable;
     * it is only the short, user-visible bridge to TriggerWakeActivity.
     */
    private fun showTriggerOverlayAndLaunch(
        activityIntent: Intent,
        onFailure: () -> Unit,
    ): Boolean {
        if (triggerOverlayView != null) {
            Log.i(TAG, "Trigger overlay is already visible; duplicate trigger ignored")
            return true
        }
        val windowManager = getSystemService(WindowManager::class.java) ?: run {
            Log.e(TAG, "WindowManager unavailable for Android 16 trigger overlay")
            return false
        }
        val banner = TextView(this).apply {
            text = "Открываю Искатель вещей…"
            setTextColor(android.graphics.Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            gravity = Gravity.CENTER
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            background = android.graphics.drawable.GradientDrawable().apply {
                setColor(android.graphics.Color.rgb(38, 80, 56))
                cornerRadius = dp(24).toFloat()
            }
            setPadding(dp(20), dp(10), dp(20), dp(10))
        }
        val params = WindowManager.LayoutParams(
            dp(272),
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            y = dp(72)
            title = "RFID trigger launch"
        }
        try {
            windowManager.addView(banner, params)
            triggerOverlayView = banner
            Log.i(TAG, "Android 16 trigger overlay attached")
        } catch (error: RuntimeException) {
            Log.e(TAG, "Could not attach Android 16 trigger overlay", error)
            return false
        }

        val launch = Runnable {
            if (triggerOverlayView !== banner) return@Runnable
            try {
                startActivity(activityIntent)
                Log.i(TAG, "TriggerWakeActivity started from visible Android 16 overlay")
            } catch (error: RuntimeException) {
                Log.e(TAG, "Overlay activity launch failed; using notification fallback", error)
                onFailure()
            } finally {
                removeTriggerOverlay()
            }
        }
        triggerOverlayLaunch = launch
        if (!banner.post { wakeHandler.postDelayed(launch, OVERLAY_VISIBLE_DURATION_MS) }) {
            Log.e(TAG, "Could not schedule Android 16 overlay activity launch")
            removeTriggerOverlay()
            return false
        }
        return true
    }

    private fun removeTriggerOverlay() {
        val launch = triggerOverlayLaunch
        if (launch != null) wakeHandler.removeCallbacks(launch)
        triggerOverlayLaunch = null
        val banner = triggerOverlayView ?: return
        triggerOverlayView = null
        try {
            getSystemService(WindowManager::class.java)?.removeView(banner)
            Log.i(TAG, "Android 16 trigger overlay removed")
        } catch (error: RuntimeException) {
            // The system may have already removed the token while the service
            // is stopping or the trampoline is becoming visible.
            Log.w(TAG, "Could not remove Android 16 trigger overlay", error)
        }
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).roundToInt()

    private fun isMainActivityActuallyForeground(): Boolean {
        val lifecycleResumed = (application as RfidHunterApplication).mainActivityResumed
        val processInfo = ActivityManager.RunningAppProcessInfo()
        ActivityManager.getMyMemoryState(processInfo)
        val processForeground =
            processInfo.importance == ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND
        Log.i(
            TAG,
            "Trigger foreground check lifecycleResumed=$lifecycleResumed importance=${processInfo.importance}",
        )
        return lifecycleResumed && processForeground
    }

    private fun nextTriggerRequestCode(): Int {
        triggerRequestSequence = (triggerRequestSequence + 1) and 0x0fff_ffff
        return triggerRequestSequence
    }

    private fun showFullScreenTriggerNotification(notificationId: Int, pendingIntent: PendingIntent) {
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(
                TRIGGER_CHANNEL_ID,
                "Аппаратный курок RFID",
                NotificationManager.IMPORTANCE_HIGH,
            ).apply {
                description = "Открывает приложение при нажатии курка считывателя"
                enableVibration(false)
                setSound(null, null)
                lockscreenVisibility = Notification.VISIBILITY_PUBLIC
            },
        )
        val fullScreenAllowed = Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE ||
            manager.canUseFullScreenIntent()
        Log.i(TAG, "Posting trigger full-screen notification allowed=$fullScreenAllowed")
        val notification = NotificationCompat.Builder(this, TRIGGER_CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
            .setContentTitle("Искатель вещей")
            .setContentText("Открываю приложение")
            .setCategory(NotificationCompat.CATEGORY_EVENT)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)
            .setFullScreenIntent(pendingIntent, true)
            .build()
        // Each physical press must be a new notification. Updating an active
        // notification does not dispatch its full-screen intent again.
        manager.notify(notificationId, notification)
        wakeHandler.postDelayed(
            { manager.cancel(notificationId) },
            TRIGGER_NOTIFICATION_LIFETIME_MS,
        )
    }

    /**
     * Android 16 split the old ALLOWED policy into two explicit policies. A
     * reader trigger must work while this app is completely hidden, so it
     * needs ALLOW_ALWAYS rather than ALLOW_IF_VISIBLE. The project currently
     * compiles against API 35; value 3 is the public API 36 ALLOW_ALWAYS
     * constant and is only supplied to an API 36+ device.
     */
    private fun triggerBackgroundActivityStartMode(): Int =
        if (Build.VERSION.SDK_INT >= ANDROID_16_API_LEVEL) {
            MODE_BACKGROUND_ACTIVITY_START_ALLOW_ALWAYS
        } else {
            ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED
        }

    companion object {
        private const val TAG = "RfidForegroundService"
        private const val ACTION_DEBUG_TRIGGER = "ru.rfidhunter.app.DEBUG_TRIGGER"
        const val ACTION_REMOVE_TRIGGER_OVERLAY = "ru.rfidhunter.app.REMOVE_TRIGGER_OVERLAY"
        private const val TRIGGER_CHANNEL_ID = "rfid_trigger"
        private const val LEGACY_TRIGGER_NOTIFICATION_ID = 1002
        private const val ANDROID_16_API_LEVEL = 36
        private const val MODE_BACKGROUND_ACTIVITY_START_ALLOW_ALWAYS = 3
        private const val CHANNEL_ID = "rfid_reader"
        private const val NOTIFICATION_ID = 1001
        private const val TRIGGER_WAKE_DURATION_MS = 8_000L
        private const val TRIGGER_NOTIFICATION_LIFETIME_MS = 15_000L
        private const val OVERLAY_VISIBLE_DURATION_MS = 220L
    }
}
