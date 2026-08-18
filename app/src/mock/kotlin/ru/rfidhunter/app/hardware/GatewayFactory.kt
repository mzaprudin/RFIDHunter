package ru.rfidhunter.app.hardware

import android.content.Context

/** Factory used by the mock flavor; the hardware flavor supplies its own implementation. */
object GatewayFactory {
    fun create(context: Context): RfidGateway = FakeRfidGateway()
}
