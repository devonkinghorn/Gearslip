package app.seb3thehacker.gearslip.wireless

import android.bluetooth.BluetoothA2dp
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothHeadset
import android.bluetooth.BluetoothProfile
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import app.seb3thehacker.gearslip.GearslipLog

/** wifihold-6: Bluetooth connect/disconnect broadcasts for [AutoStart] (known cars only). */
class CarBluetoothReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        GearslipLog.init(context)
        if (intent.action == Intent.ACTION_MY_PACKAGE_REPLACED) {
            AutoStart.checkAlreadyConnected(context, "Gearslip was updated")
            AutoStart.rearm(context)
            return
        }
        @Suppress("DEPRECATION")
        val device = intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java) ?: return
        AutoStart.init(context)
        if (!AutoStart.isKnown(context, device.address)) return
        when (intent.action) {
            BluetoothDevice.ACTION_ACL_CONNECTED -> AutoStart.onCarConnected(context, device.address, "Bluetooth link up")
            BluetoothDevice.ACTION_ACL_DISCONNECTED -> AutoStart.onCarDisconnected(context, device.address, "Bluetooth link down")
            BluetoothA2dp.ACTION_CONNECTION_STATE_CHANGED, BluetoothHeadset.ACTION_CONNECTION_STATE_CHANGED -> {
                val profile = if (intent.action == BluetoothA2dp.ACTION_CONNECTION_STATE_CHANGED) "A2DP" else "HFP"
                when (intent.getIntExtra(BluetoothProfile.EXTRA_STATE, -1)) {
                    BluetoothProfile.STATE_CONNECTED -> AutoStart.onCarConnected(context, device.address, "$profile connected")
                    BluetoothProfile.STATE_DISCONNECTED -> GearslipLog.i("$profile disconnected from ${device.address}", "AUTO")
                }
            }
        }
    }
}
