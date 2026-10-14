package app.seb3thehacker.gearslip

import android.content.Context

/**
 * devfix: experiment switches for older head units (2019 Colorado, AA 1.3). Loaded from
 * Settings at the start of each session; read by the runner and audio link.
 */
object TruckTests {
    /** "auto" (wait for acks, stop waiting if the car never acks), "wait", or "nowait". */
    @Volatile var ackMode = "auto"
    /** Video timestamp: "encoder" (encoder PTS), "wall" (clock micros), or "zero". */
    @Volatile var ptsMode = "encoder"
    /** SPS/PPS: "separate" (CodecConfig message), "prepend" (on each keyframe), or "both". */
    @Volatile var csdMode = "separate"
    /** Log every message in and out with its bytes. */
    @Volatile var verbose = false

    /** Set at runtime when "auto" decides the car isn't acking. */
    @Volatile var videoAckBypass = false
    @Volatile var audioAckBypass = false
    @Volatile var videoStalls = 0
    @Volatile var audioStalls = 0

    fun waitVideoAcks() = ackMode == "wait" || (ackMode == "auto" && !videoAckBypass)
    fun waitAudioAcks() = ackMode == "wait" || (ackMode == "auto" && !audioAckBypass)

    /** Called on each ack stall; in auto mode two stalls switch the stream to not waiting. */
    fun onVideoStall() {
        videoStalls++
        if (ackMode == "auto" && !videoAckBypass && videoStalls >= 2) {
            videoAckBypass = true
            GearslipLog.w("auto: the car isn't acking video (stalls=$videoStalls) - switching video to no-wait")
        }
    }

    fun onAudioStall() {
        audioStalls++
        if (ackMode == "auto" && !audioAckBypass && audioStalls >= 2) {
            audioAckBypass = true
            GearslipLog.w("auto: the car isn't acking audio (stalls=$audioStalls) - switching audio to no-wait")
        }
    }

    fun load(context: Context) {
        ackMode = AppSettings.getString(context, KEY_ACK, "auto")
        ptsMode = AppSettings.getString(context, KEY_PTS, "encoder")
        csdMode = AppSettings.getString(context, KEY_CSD, "separate")
        verbose = AppSettings.getString(context, KEY_VERBOSE, "off") == "on"
        videoAckBypass = false; audioAckBypass = false; videoStalls = 0; audioStalls = 0
        GearslipLog.i("truck tests: ackMode=$ackMode pts=$ptsMode csd=$csdMode verbose=$verbose")
    }

    const val KEY_ACK = "tt_ack_mode"
    const val KEY_PTS = "tt_pts_mode"
    const val KEY_CSD = "tt_csd_mode"
    const val KEY_VERBOSE = "tt_verbose"

    fun summary() = "ack=$ackMode${if (videoAckBypass) "(video→nowait)" else ""}" +
        "${if (audioAckBypass) "(audio→nowait)" else ""} pts=$ptsMode csd=$csdMode verbose=$verbose"
}
