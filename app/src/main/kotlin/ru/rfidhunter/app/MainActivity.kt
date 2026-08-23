package ru.rfidhunter.app

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModelProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import ru.rfidhunter.app.ui.RfidHunterApp
import ru.rfidhunter.app.ui.MainViewModel
import ru.rfidhunter.app.hardware.RfidForegroundService

class MainActivity : ComponentActivity() {
    private var volumeDownCount = 0
    private var volumeDownWindowStart = 0L
    private var fifthDownAt = 0L
    private var activeViewModel: MainViewModel? = null
    private var cameraGranted by mutableStateOf(false)
    private var microphoneGranted by mutableStateOf(false)
    private var triggerOverlayAvailable by mutableStateOf(true)
    private var bluetoothPermissionRequested = false
    private var notificationPermissionRequested = false
    private var readerServiceStarted = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        refreshPermissions()
        window.addFlags(
            android.view.WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                android.view.WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON,
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            // The API-level methods are the supported equivalent of the window
            // flags above and make a trigger-launched activity visible on the
            // lock screen on modern Samsung firmware.
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        }
        setMaximumVolume()
        startReaderServiceWhenPermitted()
        val vm: MainViewModel = ViewModelProvider(this)[MainViewModel::class.java]
        activeViewModel = vm
        if (blePermissionsReady()) vm.autoConnectSavedReader()
        if (intent.getBooleanExtra(EXTRA_TRIGGER_ON_START, false)) {
            intent.removeExtra(EXTRA_TRIGGER_ON_START)
            vm.onTriggerWakeIntent()
        }
        if (intent.getBooleanExtra(EXTRA_OPEN_SERVICE, false)) {
            intent.removeExtra(EXTRA_OPEN_SERVICE)
            vm.openService()
        }
        setContent {
            RfidHunterApp(
                viewModel = vm,
                cameraPermission = cameraGranted,
                microphonePermission = microphoneGranted,
                triggerOverlayAvailable = triggerOverlayAvailable,
                requestCameraPermission = { requestPermissions(arrayOf(Manifest.permission.CAMERA), REQUEST_CAMERA) },
                requestMicrophonePermission = { requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), REQUEST_MIC) },
                requestTriggerOverlayPermission = ::requestTriggerOverlayPermission,
            )
        }
    }

    override fun onResume() {
        super.onResume()
        (application as RfidHunterApplication).mainActivityResumed = true
        refreshPermissions()
        startReaderServiceWhenPermitted()
        if (blePermissionsReady()) activeViewModel?.autoConnectSavedReader()
    }

    override fun onPause() {
        (application as RfidHunterApplication).mainActivityResumed = false
        super.onPause()
    }

    override fun onStop() {
        (application as RfidHunterApplication).mainActivityResumed = false
        super.onStop()
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (keyCode == KeyEvent.KEYCODE_VOLUME_DOWN || keyCode == KeyEvent.KEYCODE_VOLUME_UP) {
            val vm = activeViewModel
            if (keyCode == KeyEvent.KEYCODE_VOLUME_DOWN) handleServiceGesture(event, vm)
            if (event.repeatCount == 0) {
                val delta = if (keyCode == KeyEvent.KEYCODE_VOLUME_UP) 1 else -1
                vm?.onVolumeKey(delta)
            }
            setMaximumVolume()
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean {
        if (keyCode == KeyEvent.KEYCODE_VOLUME_DOWN || keyCode == KeyEvent.KEYCODE_VOLUME_UP) {
            if (keyCode == KeyEvent.KEYCODE_VOLUME_DOWN) handleServiceGesture(event, activeViewModel)
            setMaximumVolume()
            return true
        }
        return super.onKeyUp(keyCode, event)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (intent.getBooleanExtra(EXTRA_TRIGGER_ON_START, false)) {
            // Re-apply these flags when the existing Activity receives a
            // trigger while the display was already asleep. This is important
            // for SINGLE_TOP delivery, where onCreate/onResume are not called.
            window.addFlags(
                android.view.WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                    android.view.WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON,
            )
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
                setShowWhenLocked(true)
                setTurnScreenOn(true)
            }
            intent.removeExtra(EXTRA_TRIGGER_ON_START)
            activeViewModel?.onTriggerWakeIntent()
        }
        if (intent.getBooleanExtra(EXTRA_OPEN_SERVICE, false)) {
            intent.removeExtra(EXTRA_OPEN_SERVICE)
            activeViewModel?.openService()
        }
    }

    private fun handleServiceGesture(event: KeyEvent, vm: MainViewModel?) {
        if (vm == null) return
        val state = vm.state.value
        if (state !is ru.rfidhunter.app.ui.AppState.Idle) return
        val now = System.currentTimeMillis()
        if (event.action == KeyEvent.ACTION_DOWN) {
            if (event.repeatCount == 0) {
                if (now - volumeDownWindowStart > 2_000) {
                    volumeDownCount = 0
                    volumeDownWindowStart = now
                }
                volumeDownCount++
                if (volumeDownCount == 5) fifthDownAt = now
            } else if (volumeDownCount == 5 && fifthDownAt > 0 && now - fifthDownAt >= 800 && now - volumeDownWindowStart <= 3_000) {
                vm.openService()
                volumeDownCount = 0
                fifthDownAt = 0
            }
        } else if (event.action == KeyEvent.ACTION_UP && volumeDownCount == 5 && fifthDownAt > 0) {
            if (now - fifthDownAt >= 800 && now - volumeDownWindowStart <= 3_000) {
                vm.openService()
                volumeDownCount = 0
                fifthDownAt = 0
            }
        }
    }

    private fun setMaximumVolume() {
        val audio = getSystemService(AUDIO_SERVICE) as AudioManager
        audio.setStreamVolume(AudioManager.STREAM_MUSIC, audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC), 0)
    }

    private fun hasPermission(permission: String): Boolean =
        ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED

    private fun refreshPermissions() {
        cameraGranted = hasPermission(Manifest.permission.CAMERA)
        microphoneGranted = hasPermission(Manifest.permission.RECORD_AUDIO)
        triggerOverlayAvailable = BuildConfig.FLAVOR != "hardware" ||
            Build.VERSION.SDK_INT < ANDROID_16_API_LEVEL ||
            Settings.canDrawOverlays(this)
    }

    private fun requestTriggerOverlayPermission() {
        if (BuildConfig.FLAVOR != "hardware" || Build.VERSION.SDK_INT < ANDROID_16_API_LEVEL) return
        startActivity(
            Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:$packageName"),
            ),
        )
    }

    /** Android 12+ requires runtime Bluetooth permission before a connected-device FGS can start. */
    private fun startReaderServiceWhenPermitted() {
        if (readerServiceStarted) return
        // The mock flavor does not access Bluetooth and should remain usable
        // without prompting for platform permissions.
        if (BuildConfig.FLAVOR != "hardware") {
            ContextCompat.startForegroundService(this, Intent(this, RfidForegroundService::class.java))
            readerServiceStarted = true
            return
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val bluetoothReady = hasPermission(Manifest.permission.BLUETOOTH_CONNECT) &&
                hasPermission(Manifest.permission.BLUETOOTH_SCAN)
            if (!bluetoothReady) {
                if (!bluetoothPermissionRequested) {
                    bluetoothPermissionRequested = true
                    requestPermissions(
                        arrayOf(Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.BLUETOOTH_SCAN),
                        REQUEST_BLUETOOTH,
                    )
                }
                return
            }
        } else if (!hasPermission(Manifest.permission.ACCESS_FINE_LOCATION) && !hasPermission(Manifest.permission.ACCESS_COARSE_LOCATION)) {
            if (!bluetoothPermissionRequested) {
                bluetoothPermissionRequested = true
                requestPermissions(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION), REQUEST_LOCATION)
            }
            return
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            !hasPermission(Manifest.permission.POST_NOTIFICATIONS)
        ) {
            if (!notificationPermissionRequested) {
                notificationPermissionRequested = true
                requestPermissions(
                    arrayOf(Manifest.permission.POST_NOTIFICATIONS),
                    REQUEST_NOTIFICATIONS,
                )
            }
            return
        }
        ContextCompat.startForegroundService(this, Intent(this, RfidForegroundService::class.java))
        readerServiceStarted = true
    }

    private fun blePermissionsReady(): Boolean = if (BuildConfig.FLAVOR != "hardware") {
        true
    } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        hasPermission(Manifest.permission.BLUETOOTH_CONNECT) && hasPermission(Manifest.permission.BLUETOOTH_SCAN)
    } else {
        hasPermission(Manifest.permission.ACCESS_FINE_LOCATION) || hasPermission(Manifest.permission.ACCESS_COARSE_LOCATION)
    }

    companion object {
        const val EXTRA_TRIGGER_ON_START = "ru.rfidhunter.app.EXTRA_TRIGGER_ON_START"
        const val EXTRA_OPEN_SERVICE = "ru.rfidhunter.app.EXTRA_OPEN_SERVICE"
        private const val REQUEST_CAMERA = 10
        private const val REQUEST_MIC = 11
        private const val REQUEST_BLUETOOTH = 12
        private const val REQUEST_LOCATION = 13
        private const val REQUEST_NOTIFICATIONS = 14
        private const val ANDROID_16_API_LEVEL = 36
    }
}
