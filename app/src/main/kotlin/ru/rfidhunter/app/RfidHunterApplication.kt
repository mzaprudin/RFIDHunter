package ru.rfidhunter.app

import android.app.Application
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import ru.rfidhunter.app.data.AppDatabase
import ru.rfidhunter.app.data.ItemRepository
import ru.rfidhunter.app.data.SyncScheduler
import ru.rfidhunter.app.data.SyncSettings
import ru.rfidhunter.app.hardware.GatewayFactory
import ru.rfidhunter.app.hardware.RfidGateway
import ru.rfidhunter.app.domain.AppMode

class RfidHunterApplication : Application() {
    @Volatile
    var mainActivityResumed: Boolean = false

    @Volatile
    var screenInteractive: Boolean = false

    private val preferences by lazy { getSharedPreferences("rfidhunter_runtime", MODE_PRIVATE) }
    private val _appMode = MutableStateFlow(AppMode.NORMAL)
    val appMode: StateFlow<AppMode> = _appMode.asStateFlow()

    val database by lazy { AppDatabase.create(this) }
    val syncSettings by lazy { SyncSettings(this) }
    val syncScheduler by lazy { SyncScheduler(this) }
    val itemRepository by lazy { ItemRepository(database, syncScheduler) }
    /** One process-wide gateway prevents the Activity and foreground service from registering duplicate SDK listeners. */
    val rfidGateway: RfidGateway by lazy { GatewayFactory.create(this) }

    override fun onCreate() {
        super.onCreate()
        _appMode.value = runCatching {
            AppMode.valueOf(preferences.getString(KEY_APP_MODE, AppMode.NORMAL.name).orEmpty())
        }.getOrDefault(AppMode.NORMAL)
    }

    fun setAppMode(mode: AppMode) {
        if (_appMode.value == mode) return
        preferences.edit().putString(KEY_APP_MODE, mode.name).apply()
        _appMode.value = mode
    }

    private companion object {
        const val KEY_APP_MODE = "app_mode"
    }
}
