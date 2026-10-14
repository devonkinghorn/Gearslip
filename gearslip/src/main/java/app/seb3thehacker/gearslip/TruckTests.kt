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
    /** "off" or "on": resend the last keyframe whenever the screen is still, ~10 fps. */
    @Volatile var steadyFrames = "off"
    /** Audio chunks freed per ack from the car: "1" or "2". */
    @Volatile var audioAckCredit = "1"
    /** Audio chunk size in bytes: "4096" or "2048". */
    @Volatile var audioChunk = "4096"

    /** SPS constraint flags byte: "off" (as encoded), "c0", or "e0" (constrained baseline). */
    @Volatile var spsFlags = "off"
    /** "request" (ask the car for video focus) or "skip" (send Start and let the car grant it). */
    @Volatile var focusMode = "request"

    /** Rewrites the constraint byte of every Baseline SPS (00 00 01 67 42 xx) in place. */
    fun patchSps(b: ByteArray): ByteArray {
        val v = when (spsFlags) { "c0" -> 0xC0; "e0" -> 0xE0; else -> return b }
        val out = b.copyOf()
        var i = 0
        while (i + 5 < out.size) {
            if (out[i] == 0.toByte() && out[i + 1] == 0.toByte() && out[i + 2] == 1.toByte() &&
                (out[i + 3].toInt() and 0x1F) == 7 && out[i + 4] == 0x42.toByte()) {
                out[i + 5] = v.toByte()
                i += 6
            } else i++
        }
        return out
    }

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
        steadyFrames = AppSettings.getString(context, KEY_STEADY, "off")
        audioAckCredit = AppSettings.getString(context, KEY_AUDIO_CREDIT, "1")
        audioChunk = AppSettings.getString(context, KEY_AUDIO_CHUNK, "4096")
        spsFlags = AppSettings.getString(context, KEY_SPS, "off")
        focusMode = AppSettings.getString(context, KEY_FOCUS, "request")
        videoAckBypass = false; audioAckBypass = false; videoStalls = 0; audioStalls = 0
        GearslipLog.i("truck tests: ackMode=$ackMode pts=$ptsMode csd=$csdMode verbose=$verbose steady=$steadyFrames audioCredit=$audioAckCredit audioChunk=$audioChunk sps=$spsFlags focus=$focusMode")
    }

    const val KEY_ACK = "tt_ack_mode"
    const val KEY_PTS = "tt_pts_mode"
    const val KEY_CSD = "tt_csd_mode"
    const val KEY_VERBOSE = "tt_verbose"
    const val KEY_STEADY = "tt_steady"
    const val KEY_AUDIO_CREDIT = "tt_audio_credit"
    const val KEY_AUDIO_CHUNK = "tt_audio_chunk"
    const val KEY_SPS = "tt_sps_flags"
    const val KEY_FOCUS = "tt_focus_mode"

    fun summary() = "ack=$ackMode${if (videoAckBypass) "(video→nowait)" else ""}" +
        "${if (audioAckBypass) "(audio→nowait)" else ""} pts=$ptsMode csd=$csdMode verbose=$verbose steady=$steadyFrames audioCredit=$audioAckCredit audioChunk=$audioChunk sps=$spsFlags focus=$focusMode"
}
