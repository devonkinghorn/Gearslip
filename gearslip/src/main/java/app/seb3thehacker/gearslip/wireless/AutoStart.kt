package app.seb3thehacker.gearslip.wireless

import android.Manifest
import android.annotation.SuppressLint
import android.app.ForegroundServiceStartNotAllowedException
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.bluetooth.BluetoothA2dp
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothHeadset
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.companion.AssociationInfo
import android.companion.CompanionDeviceManager
import android.companion.ObservingDevicePresenceRequest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import app.seb3thehacker.gearslip.AppSettings
import app.seb3thehacker.gearslip.GearslipActivity
import app.seb3thehacker.gearslip.GearslipLog
import app.seb3thehacker.gearslip.SessionStatus
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * wifihold-6: starts wireless Android Auto by itself when a car that has completed a wireless
 * session before connects over Bluetooth.
 *
 * Triggers, most robust first:
 *  1. CompanionDeviceManager presence ([CarPresenceService]) for cars the driver associated once.
 *     The system binds that service when the car connects, even with Gearslip killed, and an
 *     associated app may start foreground services and its activity from the background.
 *  2. The manifest [CarBluetoothReceiver]: ACL_CONNECTED, and A2DP/HFP connected.
 *  3. Already connected when Gearslip or its service starts ([checkAlreadyConnected]).
 *
 * Each trigger goes through [trigger], which debounces and starts [AutoStartService]
 * (foreground, type connectedDevice), which runs the RFCOMM start-up and retries.
 */
object AutoStart {
    private val log = GearslipLog.tagged("AUTO")

    const val ACTION_AUTO_WIRELESS = "app.seb3thehacker.gearslip.AUTO_WIRELESS"
    const val EXTRA_ADDRESS = "address"
    private const val KEY_ENABLED = "auto_start_known_cars"
    private const val KEY_CARS = "auto_start_cars" // "AA:BB:..|name;..."
    private const val KEY_ASKED = "auto_start_cdm_asked" // addresses already offered the association
    private const val DEBOUNCE_MS = 20_000L
    private const val NOTIFY_CHANNEL = "wireless_auto"
    private const val NOTIFY_TAP_ID = 4711

    @Volatile private var appContext: Context? = null
    @Volatile private var lastTriggerAt = 0L
    @Volatile private var lastTriggerAddress: String? = null
    /** Known cars Bluetooth says are connected right now. */
    private val connected = java.util.Collections.synchronizedSet(HashSet<String>())

    /** Set by the activity: shows the system "associate this car" dialog. */
    @Volatile var requestAssociation: ((address: String) -> Unit)? = null

    data class Car(val address: String, val name: String)

    private val _cars = MutableStateFlow<List<Car>>(emptyList())
    val cars: StateFlow<List<Car>> = _cars

    fun init(context: Context) {
        if (appContext != null) return
        appContext = context.applicationContext
        _cars.value = load(context)
    }

    fun enabled(context: Context): Boolean = prefs(context).getBoolean(KEY_ENABLED, true)
    fun setEnabled(context: Context, value: Boolean) {
        prefs(context).edit().putBoolean(KEY_ENABLED, value).apply()
        log.i("auto-start on known cars ${if (value) "on" else "off"}")
    }

    private fun prefs(context: Context) = context.getSharedPreferences("gearslip_auto_start", Context.MODE_PRIVATE)

    private fun load(context: Context): List<Car> =
        prefs(context).getString(KEY_CARS, "").orEmpty().split(";").filter { it.contains("|") }.map {
            val (a, n) = it.split("|", limit = 2)
            Car(a.uppercase(), n)
        }

    private fun save(context: Context, cars: List<Car>) {
        prefs(context).edit().putString(KEY_CARS, cars.joinToString(";") { "${it.address}|${it.name.replace(";", " ").replace("|", " ")}" }).apply()
        _cars.value = cars
    }

    fun isKnown(context: Context, address: String) = load(context).any { it.address.equals(address, true) }

    fun forget(context: Context, address: String) {
        save(context, load(context).filterNot { it.address.equals(address, true) })
        log.i("forgot car $address")
    }

    /** Called when a wireless session reaches video: this car now starts Gearslip by itself. */
    @SuppressLint("MissingPermission")
    fun onWirelessSessionWorked() {
        val context = appContext ?: return
        val device = WirelessLink.currentDevice ?: return
        val name = runCatching { device.name }.getOrNull() ?: device.address
        val cars = load(context)
        if (cars.none { it.address.equals(device.address, true) }) {
            save(context, cars + Car(device.address.uppercase(), name))
            log.i("remembered $name (${device.address}) - it will start wireless Android Auto by itself when it connects")
        }
        if (isAssociated(context, device.address)) startObserving(context, device.address)
    }

    // --- triggers -------------------------------------------------------------------------

    fun onCarConnected(context: Context, address: String, why: String) {
        init(context)
        if (!isKnown(context, address)) return
        connected += address.uppercase()
        trigger(context, address, why)
    }

    fun onCarDisconnected(context: Context, address: String, why: String) {
        init(context)
        if (!isKnown(context, address)) return
        if (connected.remove(address.uppercase())) log.i("$address disconnected ($why)")
        AutoStartService.onCarGone(context, address)
    }

    fun isConnected(address: String) = connected.contains(address.uppercase())

    /** True while a session is starting or running, or one was just triggered. */
    fun busy(): Boolean {
        val phase = SessionStatus.state.value.phase
        return phase == SessionStatus.Phase.CONNECTING || phase == SessionStatus.Phase.PROJECTING || WirelessLink.busy
    }

    fun trigger(context: Context, address: String, why: String) {
        init(context)
        val now = System.currentTimeMillis()
        val car = load(context).firstOrNull { it.address.equals(address, true) }
        if (car == null) return
        when {
            !enabled(context) -> { log.i("${car.name} connected ($why) - auto-start is off in Settings"); return }
            !AppSettings.gearslipEnabled(context) -> { log.i("${car.name} connected ($why) - Gearslip is switched off"); return }
            context.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED -> {
                log.w("${car.name} connected ($why) - can't auto-start: the Nearby devices permission isn't granted")
                return
            }
            busy() -> { log.i("${car.name} connected ($why) - a session is already starting or running, not starting another"); return }
            now - lastTriggerAt < DEBOUNCE_MS && address.equals(lastTriggerAddress, true) -> {
                log.i("${car.name} connected ($why) - already triggered ${now - lastTriggerAt}ms ago, ignoring")
                return
            }
        }
        lastTriggerAt = now
        lastTriggerAddress = address
        log.i("${car.name} connected ($why) - starting wireless Android Auto")
        try {
            context.startForegroundService(
                Intent(context, AutoStartService::class.java).putExtra(EXTRA_ADDRESS, car.address),
            )
        } catch (e: ForegroundServiceStartNotAllowedException) {
            log.w("Android won't let Gearslip start in the background here (${e.message}) - showing a tap-to-connect notification. Associating the car in Settings > Connection lifts this.")
            notifyTapToStart(context, car)
        } catch (e: Exception) {
            log.e("couldn't start the auto-start service", e)
            notifyTapToStart(context, car)
        }
    }

    /** The intent that brings Gearslip up for an automatic wireless start. */
    fun activityIntent(context: Context, address: String): Intent =
        Intent(context, GearslipActivity::class.java)
            .setAction(ACTION_AUTO_WIRELESS)
            .putExtra(EXTRA_ADDRESS, address)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)

    fun channel(context: Context): String {
        context.getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(NOTIFY_CHANNEL, "Wireless Android Auto", NotificationManager.IMPORTANCE_HIGH),
        )
        return NOTIFY_CHANNEL
    }

    fun notifyTapToStart(context: Context, car: Car) {
        val tap = PendingIntent.getActivity(
            context, 0, activityIntent(context, car.address),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val n = Notification.Builder(context, channel(context))
            .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
            .setContentTitle("${car.name} connected")
            .setContentText("Tap to start wireless Android Auto")
            .setContentIntent(tap)
            .setAutoCancel(true)
            .build()
        runCatching { context.getSystemService(NotificationManager::class.java).notify(NOTIFY_TAP_ID, n) }
            .onFailure { log.w("couldn't post the tap-to-connect notification: ${it.message}") }
    }

    fun cancelTapNotification(context: Context) {
        runCatching { context.getSystemService(NotificationManager::class.java).cancel(NOTIFY_TAP_ID) }
    }

    /** A known car already connected when Gearslip (or its service) starts. */
    @SuppressLint("MissingPermission")
    fun checkAlreadyConnected(context: Context, why: String) {
        init(context)
        if (!enabled(context) || load(context).isEmpty()) return
        if (context.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) return
        val adapter = context.getSystemService(BluetoothManager::class.java)?.adapter ?: return
        if (!adapter.isEnabled) return
        for (profile in listOf(BluetoothProfile.HEADSET, BluetoothProfile.A2DP)) {
            adapter.getProfileProxy(context.applicationContext, object : BluetoothProfile.ServiceListener {
                override fun onServiceConnected(p: Int, proxy: BluetoothProfile) {
                    val devices = runCatching { proxy.connectedDevices }.getOrDefault(emptyList())
                    runCatching { adapter.closeProfileProxy(p, proxy) }
                    devices.firstOrNull { isKnown(context, it.address) }?.let {
                        log.i("${runCatching { it.name }.getOrNull() ?: it.address} is already connected (${if (p == BluetoothProfile.A2DP) "A2DP" else "HFP"}) when $why")
                        onCarConnected(context, it.address, "already connected when $why")
                    }
                }
                override fun onServiceDisconnected(p: Int) = Unit
            }, profile)
        }
    }

    // --- CompanionDeviceManager -------------------------------------------------------------

    private fun cdm(context: Context): CompanionDeviceManager? =
        if (context.packageManager.hasSystemFeature(PackageManager.FEATURE_COMPANION_DEVICE_SETUP))
            context.getSystemService(CompanionDeviceManager::class.java) else null

    fun association(context: Context, address: String): AssociationInfo? =
        if (Build.VERSION.SDK_INT >= 33) runCatching {
            cdm(context)?.myAssociations?.firstOrNull { it.deviceMacAddress?.toString().equals(address, true) }
        }.getOrNull() else null

    @Suppress("DEPRECATION")
    fun isAssociated(context: Context, address: String): Boolean =
        if (Build.VERSION.SDK_INT >= 33) association(context, address) != null
        else runCatching { cdm(context)?.associations?.any { it.equals(address, true) } == true }.getOrDefault(false)

    fun addressForAssociation(context: Context, id: Int): String? =
        if (Build.VERSION.SDK_INT >= 33) runCatching {
            cdm(context)?.myAssociations?.firstOrNull { it.id == id }?.deviceMacAddress?.toString()?.uppercase()
        }.getOrNull() else null

    /** Asks the system to bind [CarPresenceService] whenever the car connects. */
    @Suppress("DEPRECATION")
    fun startObserving(context: Context, address: String) {
        val manager = cdm(context) ?: return
        try {
            if (Build.VERSION.SDK_INT >= 36) {
                val info = association(context, address) ?: return
                manager.startObservingDevicePresence(
                    ObservingDevicePresenceRequest.Builder().setAssociationId(info.id).build(),
                )
            } else {
                manager.startObservingDevicePresence(address)
            }
            log.i("watching for $address through the companion device service")
        } catch (e: Exception) {
            log.w("couldn't start presence observation for $address: ${e.javaClass.simpleName}: ${e.message}")
        }
    }

    /** On app start: re-arm presence observation for every associated known car. */
    fun rearm(context: Context) {
        init(context)
        load(context).filter { isAssociated(context, it.address) }.forEach { startObserving(context, it.address) }
    }

    /** A known car without an association, not yet offered: offer it once (Settings can offer again). */
    fun carToOfferAssociation(context: Context): Car? {
        if (cdm(context) == null || !enabled(context)) return null
        val asked = prefs(context).getString(KEY_ASKED, "").orEmpty().split(",")
        return load(context).firstOrNull { !isAssociated(context, it.address) && it.address !in asked }
    }

    fun markAssociationOffered(context: Context, address: String) {
        val asked = prefs(context).getString(KEY_ASKED, "").orEmpty().split(",").filter { it.isNotEmpty() }
        prefs(context).edit().putString(KEY_ASKED, (asked + address.uppercase()).distinct().joinToString(",")).apply()
    }

    fun companionAvailable(context: Context) = cdm(context) != null
}
