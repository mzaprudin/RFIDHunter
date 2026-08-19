package ru.rfidhunter.app

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioManager
import android.os.Build
import android.os.Bundle
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
    private var bluetoothPermissionRequested = false
    private var readerServiceStarted = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        refreshPermissions()
        window.addFlags(
            android.view.WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                android.view.WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON,
        )
        setMaximumVolume()
        startReaderServiceWhenPermitted()
        setContent {
            val vm: MainViewModel = ViewModelProvider(this@MainActivity)[MainViewModel::class.java]
            activeViewModel = vm
            if (intent.getBooleanExtra(EXTRA_TRIGGER_ON_START, false)) {
                intent.removeExtra(EXTRA_TRIGGER_ON_START)
                vm.onTriggerPressed()
            }
            RfidHunterApp(
                viewModel = vm,
                cameraPermission = cameraGranted,
                microphonePermission = microphoneGranted,
                requestCameraPermission = { requestPermissions(arrayOf(Manifest.permission.CAMERA), REQUEST_CAMERA) },
                requestMicrophonePermission = { requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), REQUEST_MIC) },
            )
        }
    }

    override fun onResume() {
        super.onResume()
        refreshPermissions()
        startReaderServiceWhenPermitted()
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
            intent.removeExtra(EXTRA_TRIGGER_ON_START)
            activeViewModel?.onTriggerPressed()
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
    }

    /** Android 12+ requires runtime Bluetooth permission before a connected-device FGS can start. */
    private fun startReaderServiceWhenPermitted() {
        if (readerServiceStarted) return
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
        }
        ContextCompat.startForegroundService(this, Intent(this, RfidForegroundService::class.java))
        readerServiceStarted = true
    }

    companion object {
        const val EXTRA_TRIGGER_ON_START = "ru.rfidhunter.app.EXTRA_TRIGGER_ON_START"
        private const val REQUEST_CAMERA = 10
        private const val REQUEST_MIC = 11
        private const val REQUEST_BLUETOOTH = 12
    }
}
