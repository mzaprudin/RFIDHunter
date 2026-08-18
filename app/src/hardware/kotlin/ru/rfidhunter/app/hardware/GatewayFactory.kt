package ru.rfidhunter.app.hardware

import android.content.Context

object GatewayFactory {
    fun create(context: Context): RfidGateway = HoneywellRfidGateway(context)
}
