package app.seb3thehacker.gearslip.audio

import app.seb3thehacker.gearslip.GearslipLog
import app.seb3thehacker.gearslip.Protobuf
import app.seb3thehacker.gearslip.ServiceDiscovery
import app.seb3thehacker.gearslip.Wire

/**
 * The phone end of the head unit's media audio sink, the audio twin of the video channel:
 *
 *   -> CHANNEL_OPEN_REQUEST   <- CHANNEL_OPEN_RESPONSE
 *   -> Setup { PCM }          <- Config { status, max_unacked, configuration_indices }
 *   -> AudioFocusRequest      <- AudioFocusResponse           (control channel)
 *   -> Start { session_id, configuration_index }
 *   -> Data { 8-byte timestamp, PCM }...   <- Ack { session_id, ack }
 *   -> Stop
 *
 * Inverted from aasdk's AudioMediaSinkService. Bench-verified only as far as capture goes:
 * nothing on this side of the wire has met a head unit yet.
 */
class AudioLink(
    private val service: ServiceDiscovery.AudioService,
    private val sendOnChannel: (messageId: Int, body: ByteArray) -> Unit,
    private val sendControl: (messageId: Int, body: ByteArray) -> Unit,
    /** Log name: "audio" for the media sink, "guidance" for the directions sink. */
    private val tag: String = "audio",
    private val sessionId: Int = SESSION_ID,
    /** Media asks the car for focus; directions just start, and the car ducks music under them. */
    private val asksFocus: Boolean = true,
    private val onReady: (AudioLink) -> Unit = { CarAudio.attach(it) },
    private val onClose: (AudioLink) -> Unit = { CarAudio.detach(it) },
    /** The car took the sound back after giving it, as when the driver switches to the radio. */
    private val onTakenBack: () -> Unit = {},
    /**
     * wifihold-5: media goes through a jitter queue and its own paced sender thread, in 8 KB
     * chunks with sample-clock timestamps. Directions pace themselves, so they keep the direct path.
     */
    private val paced: Boolean = true,
) : CarAudio.Link {

    private var configIndex = 0
    @Volatile private var ready = false
    @Volatile private var started = false
    @Volatile private var focusGranted = false
    @Volatile private var focusRequested = false
    @Volatile private var inFlight = 0
    // The Uconnect asks for 4 on its video channel; assume the same until its Config says otherwise.
    @Volatile private var maxUnacked = 4
    @Volatile private var lastAckAt = 0L
    private var stalls = 0
    private var chunks = 0
    private var bytes = 0L
    private var dropped = 0
    @Volatile private var configTimer: Thread? = null
    @Volatile private var focusTimer: Thread? = null
    @Volatile private var focusAskedAt = 0L
    // Ack timing, so a log shows how fast the car really drains the sink.
    private var acks = 0
    private var framesAcked = 0
    private var firstChunkAt = 0L
    private var firstAckLogged = false
    private var maxAckGapMs = 0L
    private val ackLock = Object()

    // --- wifihold-5 paced media path -------------------------------------------------------
    // Why: the 2019 GM unit acked exactly one MediaAck (ack=1) per TWO 4 KB chunks (495 acks for
    // 1000 chunks, steady at 49.5%). The rest were never acked, so the 16-chunk window only
    // reopened through the 2 s stall reset; the capture thread was the one blocked in send(), so
    // AudioRecord overran meanwhile and the car got ~25% of real time: choppy audio.
    /** Chunks sent and not yet acked or expired, by send time (oldest first). Guarded by ackLock. */
    private val sentAt = ArrayDeque<Long>()
    /** Chunks one MediaAck stands for on this car, learned from the ack/chunk ratio. */
    @Volatile private var ackScale = 1
    private var rawAcksForScale = 0
    private var chunksForScale = 0
    private var expired = 0
    private val queue = ArrayDeque<ByteArray>()
    private val queueLock = Object()
    private var rechunk = java.io.ByteArrayOutputStream()
    private var queueDrops = 0
    private var rebases = 0
    @Volatile private var sender: Thread? = null
    @Volatile private var senderGen = 0
    private var streamBaseUs = 0L
    private var streamBaseWallMs = 0L
    private var samplesSent = 0L
    /** Only for a sink that takes AAC and nothing else; PCM sinks get the capture as it is. */
    @Volatile private var aac: AacEncoder? = null
    private val aacSink get() = service.codecType == ServiceDiscovery.CODEC_AAC_LC || service.codecType == ServiceDiscovery.CODEC_AAC_LC_ADTS

    private val config get() = service.configs.getOrNull(configIndex) ?: service.configs.firstOrNull()
    override val sampleRate get() = config?.sampleRate ?: 48_000
    override val channels get() = config?.channels ?: 2

    val channelId get() = service.serviceId

    fun opened(status: Int) {
        GearslipLog.i("$tag: <- ChannelOpenResponse: status=$status")
        if (status != 0) {
            GearslipLog.w("$tag: the head unit refused the $tag channel (status $status)")
            return
        }
        // Setup { required MediaCodecType type = 1 }: the codec the car advertised for this sink
        // (aasdk's current protos, and LIVI reads it as the codec). Gearslip used to send 0 here,
        // read from an older proto as a config index; 0 is no codec at all, and the 2024 VW
        // answered UNEXPECTED_MESSAGE (id 255), never acked a chunk and played nothing.
        sendOnChannel(MSG_SETUP, Protobuf.varintField(1, service.codecType.toLong()))
        GearslipLog.i("$tag: -> Setup(codec=${service.codecName}) on channel ${service.serviceId}")
        armConfigFallback()
    }

    /**
     * The 2018 Uconnect, a Dacia ULC 4.5 and the 2024 VW answered the old Setup (codec 0) with
     * UNEXPECTED_MESSAGE (id 255, no body) rather than a Config, so the sink never reached READY.
     * [MSG_SETUP_ANSWER] still takes that as the go-ahead, in case a car rejects the real codec too.
     *
     * Service discovery already told us everything a Config would have: the sink's rate, channels
     * and its one configuration. So for a car that answers nothing at all, wait a moment and then
     * assume the advertised configuration. If that guess is wrong the head unit simply will not
     * ack, which the write path already reports.
     */
    private fun armConfigFallback() {
        val timer = Thread({
            try {
                Thread.sleep(CONFIG_WAIT_MS)
            } catch (e: InterruptedException) {
                return@Thread
            }
            useAdvertisedSink("no usable Config after ${CONFIG_WAIT_MS}ms")
        }, "gearslip-audio-config").apply { isDaemon = true }
        configTimer = timer
        timer.start()
    }

    @Synchronized
    private fun useAdvertisedSink(why: String) {
        if (ready) return
        configTimer?.interrupt()
        GearslipLog.i(
            "$tag: $why - using the sink the head unit advertised " +
                "(${sampleRate}Hz x$channels, config_index=0, max_unacked=$maxUnacked)",
        )
        configIndex = 0
        ready = true
        onReady(this)
    }

    fun onMessage(messageId: Int, body: ByteArray) {
        when (messageId) {
            MSG_CHANNEL_OPEN_RESPONSE -> opened(Protobuf.readInt32Field(body, 1) ?: 0)

            MSG_CONFIG -> {
                val fields = Wire.fields(body)
                val status = Wire.varint(fields, 1)?.toInt()
                val window = Wire.varint(fields, 2)?.toInt()
                val indices = fields.filter { it.number == 3 && it.wireType == 0 }.map { it.varint.toInt() }
                GearslipLog.i("$tag: <- Config status=$status max_unacked=$window indices=$indices")
                if (window != null) maxUnacked = maxOf(1, window)
                configIndex = indices.firstOrNull() ?: 0
                if (status == STATUS_READY) {
                    configTimer?.interrupt()
                    ready = true
                    GearslipLog.i("$tag: sink ready - ${sampleRate}Hz x$channels ${config?.bits}bit")
                    onReady(this)
                } else {
                    GearslipLog.w(
                        "$tag: the sink is not ready (status=$status, 1=WAIT 2=READY) - " +
                            "nothing will be sent until it is",
                    )
                }
            }

            MSG_ACK -> {
                // Ack { session_id = 1; ack = 2 }: ack is how many Data messages this one covers.
                val acked = maxOf(1, Wire.varint(Wire.fields(body), 2)?.toInt() ?: 1)
                val now = System.currentTimeMillis()
                synchronized(ackLock) {
                    if (lastAckAt > 0 && acks > 0) maxAckGapMs = maxOf(maxAckGapMs, now - lastAckAt)
                    if (paced) {
                        rawAcksForScale += acked
                        repeat(acked * ackScale) { sentAt.removeFirstOrNull() }
                        inFlight = sentAt.size
                    } else {
                        inFlight = maxOf(0, inFlight - acked)
                    }
                    lastAckAt = now
                    acks++
                    framesAcked += acked * (if (paced) ackScale else 1)
                    ackLock.notifyAll()
                }
                if (!firstAckLogged && started) {
                    firstAckLogged = true
                    GearslipLog.i("$tag: <- first ack ${now - firstChunkAt}ms after the first chunk (covers $acked)")
                }
            }

            // Whatever else the head unit answers Setup with, we need its bytes to identify it.
            else -> if (messageId == MSG_SETUP_ANSWER && body.isEmpty()) {
                useAdvertisedSink("<- empty Setup answer (id $messageId)")
            } else {
                GearslipLog.i("$tag: <- unhandled message id=$messageId (${body.size} bytes)")
                GearslipLog.hex("   audio msg", body, limit = 64)
            }
        }
    }

    /**
     * The head unit's answer to our AudioFocusRequest. Until it grants focus the sink is wired to
     * whatever else the car is playing - the radio, usually - and anything we send is discarded,
     * so [start] waits here rather than streaming into nothing.
     */
    fun onFocus(state: Int) {
        val name = FOCUS_STATES[state] ?: "unknown($state)"
        GearslipLog.i("$tag: <- AudioFocusResponse state=$name")
        if (asksFocus) {
            val after = if (focusAskedAt > 0) " ${System.currentTimeMillis() - focusAskedAt}ms after the request" else ""
            app.seb3thehacker.gearslip.SessionReport.audioFocus("$name$after")
        }
        val had = focusGranted
        focusGranted = state == FOCUS_STATE_GAIN || state == FOCUS_STATE_GAIN_TRANSIENT ||
            state == FOCUS_STATE_GAIN_MEDIA_ONLY
        if (focusGranted) startStream() else stop()
        // A ducking request leaves the music playing; a loss means another source has the speakers.
        if (had && (state == FOCUS_STATE_LOSS || state == FOCUS_STATE_LOSS_TRANSIENT)) onTakenBack()
    }

    /**
     * wifihold-4: the answer to the session-start AudioFocusRequest the runner sends as Android Auto
     * does. Before this link has asked for focus itself, it only records the grant (nothing is
     * playing, so there is nothing to start); afterwards it is the normal [onFocus].
     */
    fun onFocusAnswer(state: Int) {
        if (focusRequested || started) {
            onFocus(state)
            return
        }
        val name = FOCUS_STATES[state] ?: "unknown($state)"
        focusGranted = state == FOCUS_STATE_GAIN || state == FOCUS_STATE_GAIN_TRANSIENT ||
            state == FOCUS_STATE_GAIN_MEDIA_ONLY
        GearslipLog.i("$tag: <- AudioFocusResponse state=$name (to the session-start request; streaming starts when something plays)")
    }

    override fun start() {
        if (!ready || started) return
        if (asksFocus && !focusRequested) {
            focusRequested = true
            sendControl(MSG_FOCUS_REQUEST, Protobuf.varintField(1, FOCUS_GAIN.toLong()))
            GearslipLog.i("$tag: -> AudioFocusRequest(GAIN); waiting for the head unit to hand the sink over")
        }
        if (!asksFocus || focusGranted) {
            startStream()
            return
        }
        // Wait for the car's answer before streaming, as Android Auto does: a sink that hasn't
        // been handed over may still be draining the car's own source, and this one (2019 GM)
        // took audio at a quarter of real time without focus. Some head units grant the sink
        // silently and never answer, so after FOCUS_WAIT_MS ask once more and start anyway.
        focusAskedAt = System.currentTimeMillis()
        app.seb3thehacker.gearslip.SessionReport.audioFocus("never answered")
        focusTimer?.interrupt()
        focusTimer = Thread({
            try {
                Thread.sleep(FOCUS_WAIT_MS)
            } catch (e: InterruptedException) {
                return@Thread
            }
            if (!ready || started || focusGranted || !focusRequested) return@Thread
            GearslipLog.w("$tag: no AudioFocusResponse after ${FOCUS_WAIT_MS}ms - asking again and streaming anyway")
            runCatching { sendControl(MSG_FOCUS_REQUEST, Protobuf.varintField(1, FOCUS_GAIN.toLong())) }
            app.seb3thehacker.gearslip.SessionReport.audioFocus("never answered - streamed without it")
            startStream()
        }, "gearslip-audio-focus").apply { isDaemon = true; start() }
    }

    private fun startStream() {
        if (!ready || started) return
        sendOnChannel(
            MSG_START,
            Protobuf.varintField(1, sessionId.toLong()) + Protobuf.varintField(2, configIndex.toLong()),
        )
        inFlight = 0
        lastAckAt = System.currentTimeMillis()
        firstChunkAt = 0L
        firstAckLogged = false
        acks = 0
        framesAcked = 0
        maxAckGapMs = 0L
        if (aacSink && aac == null) {
            aac = runCatching { AacEncoder(sampleRate, channels, adts = service.codecType == ServiceDiscovery.CODEC_AAC_LC_ADTS) }
                .onSuccess { GearslipLog.i("$tag: the car takes ${service.codecName} only - encoding to AAC") }
                .onFailure { GearslipLog.e("$tag: couldn't start the AAC encoder", it) }
                .getOrNull()
        }
        started = true
        GearslipLog.i("$tag: -> Start(session=$sessionId, config=$configIndex) - streaming ${sampleRate}Hz x$channels")
        if (paced) startSender()
    }

    private val frameBytes get() = 2 * channels
    /** 2048 sample frames per chunk: 8192 bytes at 48 kHz stereo (42.7 ms), still one AA frame. */
    private val chunkBytes get() = CHUNK_FRAMES * frameBytes
    private val chunkMs get() = CHUNK_FRAMES * 1000.0 / sampleRate

    private fun startSender() {
        synchronized(ackLock) { sentAt.clear(); inFlight = 0 }
        synchronized(queueLock) { queue.clear(); rechunk = java.io.ByteArrayOutputStream() }
        ackScale = 1; rawAcksForScale = 0; chunksForScale = 0; expired = 0; queueDrops = 0; rebases = 0
        samplesSent = 0
        streamBaseUs = System.nanoTime() / 1_000
        streamBaseWallMs = System.currentTimeMillis()
        GearslipLog.i("$tag: paced sender: ${chunkBytes}-byte chunks (${"%.1f".format(chunkMs)} ms), jitter queue up to ${QUEUE_MAX_MS} ms, window $maxUnacked, lead up to ${MAX_LEAD_MS} ms")
        val gen = ++senderGen
        val t = Thread({ senderLoop(gen) }, "gearslip-$tag-sender").apply {
            isDaemon = true
            priority = Thread.MAX_PRIORITY
        }
        sender = t
        t.start()
    }

    /** Called by the capture thread: never blocks on the car, only queues. */
    private fun enqueue(chunk: ByteArray) {
        synchronized(queueLock) {
            val maxChunks = maxOf(4, (QUEUE_MAX_MS / chunkMs).toInt())
            while (queue.size >= maxChunks) {
                queue.removeFirst()
                if (queueDrops++ % 50 == 0) GearslipLog.w("$tag: jitter queue full (${queue.size + 1} chunks) - dropped the oldest ($queueDrops so far)")
            }
            queue.addLast(chunk)
            queueLock.notifyAll()
        }
    }

    /** Frees chunks the car must have played by now even though it never acked them. */
    private fun expireLocked(now: Long) {
        val limit = (chunkMs * maxUnacked).toLong() + EXPIRE_GRACE_MS
        while (sentAt.isNotEmpty() && now - sentAt.first() > limit) {
            sentAt.removeFirst()
            expired++
        }
        inFlight = sentAt.size
    }

    private fun senderLoop(gen: Int) {
        try {
            while (started && gen == senderGen) {
                val chunk = synchronized(queueLock) {
                    while (started && queue.isEmpty()) queueLock.wait(50)
                    queue.removeFirstOrNull()
                } ?: continue
                // Window: wait for an ack, or for the oldest unacked chunk to age out.
                synchronized(ackLock) {
                    expireLocked(System.currentTimeMillis())
                    while (started && sentAt.size >= maxUnacked) {
                        ackLock.wait(10)
                        expireLocked(System.currentTimeMillis())
                    }
                }
                if (!started) break
                // Real-time pacing on the sample clock: never more than MAX_LEAD_MS ahead of the
                // wall clock; after a gap (nothing captured) move the clock forward instead of
                // racing to catch up.
                val streamMs = samplesSent * 1000 / sampleRate
                val lead = streamMs - (System.currentTimeMillis() - streamBaseWallMs)
                if (lead > MAX_LEAD_MS) Thread.sleep(lead - MAX_LEAD_MS)
                if (lead < -REBASE_MS) {
                    val behindMs = -lead
                    samplesSent += behindMs * sampleRate / 1000
                    if (rebases++ % 20 == 0) GearslipLog.w("$tag: capture fell ${behindMs} ms behind real time - moving the timeline forward ($rebases times)")
                }
                send(chunk, CHUNK_FRAMES_OF(chunk))
                maybeLearnAckScale()
            }
        } catch (_: InterruptedException) {
        } catch (t: Throwable) {
            GearslipLog.e("$tag: paced sender failed", t)
        }
    }

    private fun CHUNK_FRAMES_OF(chunk: ByteArray): Int = if (aac != null) AAC_FRAME_SAMPLES else chunk.size / frameBytes

    /**
     * How many chunks one MediaAck stands for. Gearslip assumed one; the 2019 GM unit acks one per
     * two 4 KB chunks. Checked every 64 chunks once the window has filled at least once.
     */
    private fun maybeLearnAckScale() {
        chunksForScale++
        if (chunksForScale < 64) return
        val raw = rawAcksForScale
        val ratio = if (raw > 0) chunksForScale.toDouble() / raw else 0.0
        val scale = when {
            raw == 0 -> ackScale
            ratio > 3.6 -> ackScale // way off: leave it, expiry keeps the window moving
            ratio > 2.6 -> 3
            ratio > 1.6 -> 2
            else -> 1
        }
        if (scale != ackScale) {
            GearslipLog.w("$tag: the car acks ${"%.2f".format(ratio)} chunks per MediaAck - counting each ack as $scale chunks (was $ackScale)")
            ackScale = scale
        }
        chunksForScale = 0
        rawAcksForScale = 0
    }

    override fun stop() {
        focusTimer?.interrupt()
        focusTimer = null
        synchronized(queueLock) { queue.clear(); queueLock.notifyAll() }
        senderGen++
        sender = null
        if (!started) {
            // Asked for focus but never started: still give the request back.
            if (focusRequested) {
                runCatching { sendControl(MSG_FOCUS_REQUEST, Protobuf.varintField(1, FOCUS_RELEASE.toLong())) }
                focusRequested = false
            }
            return
        }
        started = false
        synchronized(ackLock) { ackLock.notifyAll() }
        runCatching {
            sendOnChannel(MSG_STOP, Protobuf.varintField(1, sessionId.toLong()))
            if (focusRequested) sendControl(MSG_FOCUS_REQUEST, Protobuf.varintField(1, FOCUS_RELEASE.toLong()))
        }
        focusRequested = false
        focusGranted = false
        aac?.release()
        aac = null
        GearslipLog.i("$tag: -> Stop, AudioFocusRequest(RELEASE) after $chunks chunks / $bytes bytes")
        chunks = 0
        bytes = 0
    }

    override fun write(pcm: ByteArray, size: Int) {
        if (!started) return
        val encoder = aac
        if (encoder == null) {
            if (!paced) {
                send(pcm.copyOf(size))
                return
            }
            // Re-chunk the capture into CHUNK_FRAMES-sized pieces for the sender thread.
            val full = ArrayList<ByteArray>()
            synchronized(queueLock) {
                rechunk.write(pcm, 0, size)
                if (rechunk.size() >= chunkBytes) {
                    val all = rechunk.toByteArray()
                    var off = 0
                    while (all.size - off >= chunkBytes) {
                        full += all.copyOfRange(off, off + chunkBytes)
                        off += chunkBytes
                    }
                    rechunk = java.io.ByteArrayOutputStream().apply { write(all, off, all.size - off) }
                }
            }
            full.forEach(::enqueue)
            return
        }
        val frames = try {
            encoder.encode(pcm, size)
        } catch (e: Exception) {
            // A codec that dies mid-drive leaves the car silent, as it was before AAC; log it once.
            GearslipLog.e("$tag: AAC encoding failed - no more audio this drive", e)
            aac = null
            started = false
            return
        }
        if (paced) frames.forEach(::enqueue) else frames.forEach { send(it) }
    }

    /** One Data message, once the car has room for it. [frames] is set on the paced path. */
    private fun send(payload: ByteArray, frames: Int = -1) {
        // Wait for the car to catch up rather than throw the chunk away: a dropped chunk is a gap
        // the driver hears, while a short wait is soaked up by the capture buffer behind us.
        if (!paced) synchronized(ackLock) {
            while (started && inFlight >= maxUnacked) {
                val waited = System.currentTimeMillis() - lastAckAt
                if (waited > STALL_MS) {
                    // A lost ack must not silence the stream for good; after a stall assume the window cleared.
                    // A car that never acks (the 2024 VW, the Bolt) hits this every chunk: say so
                    // once, then now and then, so the rest of the log stays readable.
                    if (stalls++ % 500 == 0) {
                        GearslipLog.w("$tag: no ack for ${STALL_MS}ms with $inFlight in flight - reopening the window ($stalls times)")
                    }
                    inFlight = 0
                    break
                }
                ackLock.wait(STALL_MS - waited + 1)
            }
        }
        if (!started) {
            dropped++
            return
        }
        val timestamp = ByteArray(8)
        // Paced: the sample clock (start + samples sent), evenly spaced by the chunk's length.
        val micros = if (frames >= 0) streamBaseUs + samplesSent * 1_000_000 / sampleRate else System.nanoTime() / 1_000
        if (frames >= 0) samplesSent += frames
        for (i in 0 until 8) timestamp[i] = ((micros shr ((7 - i) * 8)) and 0xFF).toByte()
        try {
            sendOnChannel(MSG_DATA, timestamp + payload)
        } catch (e: java.io.IOException) {
            GearslipLog.e("$tag: the link died mid-stream", e)
            started = false
            return
        }
        synchronized(ackLock) {
            if (paced) { sentAt.addLast(System.currentTimeMillis()); inFlight = sentAt.size } else inFlight++
        }
        if (chunks == 0) firstChunkAt = System.currentTimeMillis()
        chunks++
        bytes += payload.size
        // The first chunk proves the whole path; after that, an occasional line is enough to show
        // the car is still taking audio and to catch a window that has quietly closed.
        if (chunks == 1 || chunks % CHUNK_LOG_EVERY == 0) {
            GearslipLog.i(
                "$tag: -> $chunks chunks / $bytes bytes, inFlight=$inFlight/$maxUnacked, " +
                    "dropped=$dropped, focus=${if (focusGranted) "granted" else "not granted"}, " +
                    "acks=$acks covering $framesAcked, longest ack gap ${maxAckGapMs}ms, " +
                    "car took ${"%.0f".format(drainPercent())}% of real time" +
                    (if (paced) ", ack scale x$ackScale, expired unacked=$expired, queue=${synchronized(queueLock) { queue.size }}, queue drops=$queueDrops, timeline moves=$rebases" else ""),
            )
        }
    }

    /** Audio the car acked, as a share of the wall-clock time since the first chunk. */
    private fun drainPercent(): Double {
        val elapsed = System.currentTimeMillis() - firstChunkAt
        if (firstChunkAt == 0L || elapsed <= 0) return 0.0
        val bytesPerFrame = 2 * channels
        val chunkMs = if (chunks == 0) 0.0 else (bytes.toDouble() / chunks) / bytesPerFrame / sampleRate * 1000
        return framesAcked * chunkMs * 100 / elapsed
    }

    fun close() {
        configTimer?.interrupt()
        configTimer = null
        stop()
        ready = false
        onClose(this)
    }

    companion object {
        const val MSG_FOCUS_REQUEST = 18
        const val MSG_FOCUS_RESPONSE = 19
        private const val MSG_CHANNEL_OPEN_RESPONSE = 8
        private const val MSG_DATA = 0
        private const val MSG_SETUP = 32768
        private const val MSG_START = 32769
        private const val MSG_STOP = 32770
        private const val MSG_CONFIG = 32771
        private const val MSG_ACK = 32772
        private const val MSG_SETUP_ANSWER = 255
        private const val STATUS_READY = 2
        private const val FOCUS_GAIN = 1
        private const val FOCUS_RELEASE = 4
        private const val FOCUS_STATE_GAIN = 1
        private const val FOCUS_STATE_GAIN_TRANSIENT = 2
        private const val FOCUS_STATE_GAIN_MEDIA_ONLY = 6
        private const val FOCUS_STATE_LOSS = 3
        private const val FOCUS_STATE_LOSS_TRANSIENT = 5
        private const val SESSION_ID = 2
        private const val STALL_MS = 2_000L
        private const val CHUNK_LOG_EVERY = 200 // about every four seconds
        private const val CONFIG_WAIT_MS = 1_000L
        private const val FOCUS_WAIT_MS = 2_000L
        private const val CHUNK_FRAMES = 2048
        private const val AAC_FRAME_SAMPLES = 1024
        private const val QUEUE_MAX_MS = 1_000.0
        private const val MAX_LEAD_MS = 300L
        private const val REBASE_MS = 500L
        private const val EXPIRE_GRACE_MS = 400L

        // AudioFocusStateNotification.AudioFocusState in aasdk.
        private val FOCUS_STATES = mapOf(
            1 to "GAIN", 2 to "GAIN_TRANSIENT", 3 to "LOSS", 4 to "LOSS_TRANSIENT_CAN_DUCK",
            5 to "LOSS_TRANSIENT", 6 to "GAIN_MEDIA_ONLY", 7 to "GAIN_TRANSIENT_GUIDANCE_ONLY",
        )
    }
}
