package ru.rfidhunter.app

import android.app.Application
import ru.rfidhunter.app.data.AppDatabase
import ru.rfidhunter.app.data.ItemRepository
import ru.rfidhunter.app.hardware.GatewayFactory
import ru.rfidhunter.app.hardware.RfidGateway

class RfidHunterApplication : Application() {
    @Volatile
    var mainActivityResumed: Boolean = false

    val database by lazy { AppDatabase.create(this) }
    val itemRepository by lazy { ItemRepository(database.itemDao()) }
    /** One process-wide gateway prevents the Activity and foreground service from registering duplicate SDK listeners. */
    val rfidGateway: RfidGateway by lazy { GatewayFactory.create(this) }
}
