package app.seb3thehacker.gearslip.wireless

import android.companion.AssociationInfo
import android.companion.CompanionDeviceService
import android.companion.DevicePresenceEvent
import app.seb3thehacker.gearslip.GearslipLog

/**
 * wifihold-6: bound by the system when an associated car connects or leaves (CompanionDeviceManager
 * presence). Works with Gearslip killed, and lets it start its foreground service from the background.
 */
class CarPresenceService : CompanionDeviceService() {
    private val log = GearslipLog.tagged("AUTO")

    override fun onCreate() {
        super.onCreate()
        GearslipLog.init(this)
        AutoStart.init(this)
    }

    @Deprecated("API 31-32")
    override fun onDeviceAppeared(address: String) {
        log.i("companion: $address appeared")
        AutoStart.onCarConnected(this, address, "companion device appeared")
    }

    @Deprecated("API 31-32")
    override fun onDeviceDisappeared(address: String) {
        log.i("companion: $address disappeared")
        AutoStart.onCarDisconnected(this, address, "companion device disappeared")
    }

    @Deprecated("API 33-35")
    override fun onDeviceAppeared(associationInfo: AssociationInfo) {
        val address = associationInfo.deviceMacAddress?.toString() ?: return
        log.i("companion: $address appeared")
        AutoStart.onCarConnected(this, address, "companion device appeared")
    }

    @Deprecated("API 33-35")
    override fun onDeviceDisappeared(associationInfo: AssociationInfo) {
        val address = associationInfo.deviceMacAddress?.toString() ?: return
        log.i("companion: $address disappeared")
        AutoStart.onCarDisconnected(this, address, "companion device disappeared")
    }

    override fun onDevicePresenceEvent(event: DevicePresenceEvent) {
        val address = AutoStart.addressForAssociation(this, event.associationId) ?: run {
            log.w("companion: presence event ${event.event} for unknown association ${event.associationId}")
            return
        }
        log.i("companion: presence event ${event.event} for $address")
        when (event.event) {
            DevicePresenceEvent.EVENT_BT_CONNECTED, DevicePresenceEvent.EVENT_BLE_APPEARED ->
                AutoStart.onCarConnected(this, address, "companion presence (event ${event.event})")
            DevicePresenceEvent.EVENT_BT_DISCONNECTED, DevicePresenceEvent.EVENT_BLE_DISAPPEARED ->
                AutoStart.onCarDisconnected(this, address, "companion presence (event ${event.event})")
        }
    }
}
