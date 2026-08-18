package ru.rfidhunter.app.hardware

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.os.PowerManager
import android.os.IBinder
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import ru.rfidhunter.app.MainActivity
import ru.rfidhunter.app.RfidHunterApplication
import ru.rfidhunter.app.domain.TriggerEvent

class RfidForegroundService : Service() {
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    override fun onCreate() {
        super.onCreate()
        val manager = getSystemService(NotificationManager::class.java)
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
                if (event == TriggerEvent.PRESSED && !(getSystemService(POWER_SERVICE) as PowerManager).isInteractive) {
                    startActivity(
                        Intent(this@RfidForegroundService, MainActivity::class.java)
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                            .putExtra(MainActivity.EXTRA_TRIGGER_ON_START, true),
                    )
                }
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        serviceScope.cancel()
        super.onDestroy()
    }

    companion object {
        private const val CHANNEL_ID = "rfid_reader"
        private const val NOTIFICATION_ID = 1001
    }
}
