package app.seb3thehacker.gearslip.wireless

import android.annotation.SuppressLint
import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import app.seb3thehacker.gearslip.GearslipLog
import app.seb3thehacker.gearslip.SessionStatus
import android.bluetooth.BluetoothManager

/**
 * wifihold-6: the foreground service (type connectedDevice) an automatic wireless start runs in.
 * Starts the RFCOMM start-up, brings Gearslip's screen up for the session, retries while the car
 * stays connected, and stops once the session ends.
 */
class AutoStartService : Service() {
    private val log = GearslipLog.tagged("AUTO")
    @Volatile private var address: String? = null
    @Volatile private var watcher: Thread? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        GearslipLog.init(this)
        AutoStart.init(this)
        val addr = intent?.getStringExtra(AutoStart.EXTRA_ADDRESS)
        val car = AutoStart.cars.value.firstOrNull { it.address.equals(addr, true) }
        try {
            startForeground(NOTE_ID, note("Connecting to ${car?.name ?: "your car"}"), ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
        } catch (e: Exception) {
            log.e("couldn't go foreground", e)
            car?.let { AutoStart.notifyTapToStart(this, it) }
            stopSelf()
            return START_NOT_STICKY
        }
        if (car == null) {
            log.w("service started without a known car")
            stopSelf()
            return START_NOT_STICKY
        }
        current = this
        if (address != null && watcher?.isAlive == true) {
            log.i("service already handling ${car.name}")
            return START_NOT_STICKY
        }
        address = car.address
        AutoStart.cancelTapNotification(this)
        watcher = Thread({ runFor(car) }, "gearslip-auto-start").apply { isDaemon = true; start() }
        return START_NOT_STICKY
    }

    @SuppressLint("MissingPermission")
    private fun runFor(car: AutoStart.Car) {
        try {
            var attempt = 0
            while (attempt < TRIES) {
                attempt++
                if (AutoStart.busy()) {
                    log.i("a session is already starting or running - watching it instead of starting another")
                } else {
                    val device = getSystemService(BluetoothManager::class.java)?.adapter?.getRemoteDevice(car.address)
                    if (device == null) { log.w("Bluetooth unavailable"); break }
                    log.i("try $attempt/$TRIES: RFCOMM wireless start-up with ${car.name}")
                    openScreen(car)
                    WirelessLink.start(this, device)
                }
                // Wait for the start-up to project, fail, or the car to leave.
                val outcome = waitForOutcome()
                when (outcome) {
                    Outcome.PROJECTING -> {
                        log.i("projecting to ${car.name} - staying in the foreground until the session ends")
                        updateNote("Connected to ${car.name}")
                        waitWhile { SessionStatus.state.value.phase == SessionStatus.Phase.PROJECTING }
                        log.i("the session with ${car.name} ended (${SessionStatus.state.value.headline})")
                        return
                    }
                    Outcome.GONE -> { log.i("${car.name} left - stopping"); WirelessLink.stop(this, "the car disconnected Bluetooth"); return }
                    Outcome.FAILED -> {
                        val detail = SessionStatus.state.value.let { "${it.headline}: ${it.detail}" }
                        if (attempt >= TRIES) { log.w("start-up failed ($detail) - giving up after $TRIES tries"); return }
                        log.w("start-up failed ($detail) - the car may not be listening yet; trying again in ${RETRY_MS / 1000}s")
                        updateNote("Retrying ${car.name}…")
                        Thread.sleep(RETRY_MS)
                        if (!stillThere(car)) { log.i("${car.name} left while waiting to retry"); return }
                    }
                }
            }
        } catch (_: InterruptedException) {
        } catch (e: Exception) {
            log.e("auto-start failed", e)
        } finally {
            current = null
            address = null
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    private enum class Outcome { PROJECTING, FAILED, GONE }

    private fun waitForOutcome(): Outcome {
        val started = System.currentTimeMillis()
        while (true) {
            val phase = SessionStatus.state.value.phase
            if (phase == SessionStatus.Phase.PROJECTING) return Outcome.PROJECTING
            if (carGone) { carGone = false; return Outcome.GONE }
            val elapsed = System.currentTimeMillis() - started
            // Grace for the start-up to flip the phase to CONNECTING before judging it.
            if (elapsed > 3_000 && (phase == SessionStatus.Phase.FAILED || phase == SessionStatus.Phase.DISCONNECTED ||
                    (phase == SessionStatus.Phase.IDLE && !WirelessLink.busy))
            ) return Outcome.FAILED
            if (elapsed > OUTCOME_MAX_MS) return Outcome.FAILED
            Thread.sleep(250)
        }
    }

    private fun waitWhile(cond: () -> Boolean) { while (cond()) Thread.sleep(500) }

    private fun stillThere(car: AutoStart.Car) = !carGone

    /**
     * The session runs in Gearslip's activity. Associated apps may open it from the background;
     * otherwise Android blocks the launch silently, and the start-up holds the car's socket until
     * the driver taps the notification (see [WirelessLink.onNoScreen]).
     */
    private fun openScreen(car: AutoStart.Car) {
        runCatching { startActivity(AutoStart.activityIntent(this, car.address)) }
            .onSuccess { log.i("asked Android to open Gearslip's screen for the session${if (AutoStart.isAssociated(this, car.address)) " (car associated - allowed from the background)" else " (no association - Android may block this while in the background)"}") }
            .onFailure { log.w("couldn't open Gearslip's screen: ${it.message}") }
    }

    private fun note(text: String): Notification {
        val tap = PendingIntent.getActivity(
            this, 1, AutoStart.activityIntent(this, address ?: ""),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return Notification.Builder(this, AutoStart.channel(this))
            .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
            .setContentTitle("Wireless Android Auto")
            .setContentText(text)
            .setContentIntent(tap)
            .setOngoing(true)
            .build()
    }

    private fun updateNote(text: String) {
        runCatching { getSystemService(android.app.NotificationManager::class.java).notify(NOTE_ID, note(text)) }
    }

    override fun onDestroy() {
        watcher?.interrupt()
        if (current === this) current = null
        super.onDestroy()
    }

    companion object {
        private const val NOTE_ID = 4712
        private const val TRIES = 3
        private const val RETRY_MS = 15_000L
        private const val OUTCOME_MAX_MS = 200_000L
        @Volatile private var current: AutoStartService? = null
        @Volatile private var carGone = false

        fun onCarGone(context: Context, address: String) {
            val s = current ?: return
            if (s.address.equals(address, true) && SessionStatus.state.value.phase != SessionStatus.Phase.PROJECTING) carGone = true
        }

        /** The start-up holds a socket nobody's waiting for: tell the driver to tap in. */
        fun screenNeeded(context: Context) {
            val s = current ?: return
            val car = AutoStart.cars.value.firstOrNull { it.address.equals(s.address, true) } ?: return
            s.openScreen(car)
            s.updateNote("${car.name} is ready - tap to start Android Auto")
            AutoStart.notifyTapToStart(context, car)
        }
    }
}
