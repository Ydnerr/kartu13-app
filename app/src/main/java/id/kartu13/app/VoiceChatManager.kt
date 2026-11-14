package id.kartu13.app

import android.content.Context
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import com.google.firebase.database.*
import org.json.JSONObject
import org.webrtc.*
import org.webrtc.audio.JavaAudioDeviceModule
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ThreadFactory
import java.util.concurrent.TimeUnit

/** WebRTC mesh voice client compatible with Kartu13 web v44 rooms/{code}/voice and vsig. */
class VoiceChatManager(context: Context, private val db: DatabaseReference, private val playerId: String, private val onJoinedChange: ((Boolean) -> Unit)? = null, private val onStatus: (String) -> Unit) {
    private val app = context.applicationContext
    private var code: String? = null
    private var factory: PeerConnectionFactory? = null
    private var adm: JavaAudioDeviceModule? = null
    private var audioSource: AudioSource? = null
    private var micTrack: org.webrtc.AudioTrack? = null
    private var muted = false
    private var ptt = app.getSharedPreferences("k13", Context.MODE_PRIVATE).getBoolean("ptt", false) // sama dengan web: localStorage k13ptt
    private var joined = false
    private var voiceListener: ValueEventListener? = null
    private var signalListener: ChildEventListener? = null
    private val peers = ConcurrentHashMap<String, Peer>()
    private val members = ConcurrentHashMap<String, Long>()
    // indikator bicara: id pemain -> waktu (uptime ms) terakhir terdengar. Ambang sama dengan web (kamu .02, lawan .03).
    private val speakingNow = ConcurrentHashMap<String, Long>()
    private var scheduler: ScheduledExecutorService? = null
    private var lastSpeakKey = ""
    private var deviceCb: AudioDeviceCallback? = null
    private val iceServers = listOf(
        PeerConnection.IceServer.builder("stun:stun.l.google.com:19302").createIceServer(),
        PeerConnection.IceServer.builder("stun:stun1.l.google.com:19302").createIceServer(),
        PeerConnection.IceServer.builder("stun:stun.relay.metered.ca:80").createIceServer(),
        PeerConnection.IceServer.builder("turn:global.relay.metered.ca:80").setUsername(TURN_USER).setPassword(TURN_PASS).createIceServer(),
        PeerConnection.IceServer.builder("turn:global.relay.metered.ca:80?transport=tcp").setUsername(TURN_USER).setPassword(TURN_PASS).createIceServer(),
        PeerConnection.IceServer.builder("turn:global.relay.metered.ca:443").setUsername(TURN_USER).setPassword(TURN_PASS).createIceServer(),
        PeerConnection.IceServer.builder("turns:global.relay.metered.ca:443?transport=tcp").setUsername(TURN_USER).setPassword(TURN_PASS).createIceServer()
    )
    private data class Peer(val pc: PeerConnection, var remoteSet: Boolean = false, val queuedIce: MutableList<IceCandidate> = mutableListOf())

    companion object {
        // These are the same public static relay credentials currently embedded in web game.js v44.
        // Rotate them in Metered and move to short-lived credentials before public production use.
        private const val TURN_USER = "ab597c34ed266524bbe49b26"
        private const val TURN_PASS = "gy41gktZ6lA0pvU2"
        private var initialized = false
        private const val MIC_TH = 0.02
        private const val REMOTE_TH = 0.03
        private const val HOLD_MS = 450L
        // Headset yang dihormati (nggak dipaksa ke speaker): kabel, USB, Bluetooth SCO, BLE (26 = TYPE_BLE_HEADSET, API 31)
        private val HEADSET_TYPES = setOf(AudioDeviceInfo.TYPE_WIRED_HEADSET, AudioDeviceInfo.TYPE_WIRED_HEADPHONES, AudioDeviceInfo.TYPE_USB_HEADSET, AudioDeviceInfo.TYPE_BLUETOOTH_SCO, 26)
    }

    fun isJoined() = joined
    fun isMuted() = muted
    fun isPtt() = ptt

    /** Mode tahan-ngomong. Sama dengan web: PTT mulai dalam keadaan mute, open mic langsung nyala. */
    fun setPtt(on: Boolean) {
        ptt = on
        app.getSharedPreferences("k13", Context.MODE_PRIVATE).edit().putBoolean("ptt", on).apply()
        setMuted(on)
    }

    /** Set mute eksplisit (dipakai tombol tahan-ngomong: tekan = false, lepas = true). */
    fun setMuted(m: Boolean) {
        muted = m
        micTrack?.setEnabled(!m)
        if (joined) onStatus(statusText())
    }

    /** Status sambungan voice ke pemain [id]: 0 = tidak di voice, 1 = menyambung, 2 = tersambung, 3 = gagal. */
    fun peerState(id: String): Int {
        if (!joined || id == playerId || !members.containsKey(id)) return 0
        val pc = peers[id]?.pc ?: return 1
        return try {
            when (pc.iceConnectionState()) {
                PeerConnection.IceConnectionState.CONNECTED, PeerConnection.IceConnectionState.COMPLETED -> 2
                PeerConnection.IceConnectionState.FAILED, PeerConnection.IceConnectionState.DISCONNECTED, PeerConnection.IceConnectionState.CLOSED -> 3
                else -> 1
            }
        } catch (_: Throwable) { 1 }
    }

    fun join(roomCode: String) {
        if (joined) return
        code = roomCode
        try {
            synchronized(VoiceChatManager::class.java) {
                if (!initialized) {
                    PeerConnectionFactory.initialize(PeerConnectionFactory.InitializationOptions.builder(app).createInitializationOptions())
                    initialized = true
                }
            }
            adm = JavaAudioDeviceModule.builder(app)
                .setUseHardwareAcousticEchoCanceler(true)
                .setUseHardwareNoiseSuppressor(true)
                .setSamplesReadyCallback(JavaAudioDeviceModule.SamplesReadyCallback { smp -> onMicSamples(smp) })
                .createAudioDeviceModule()
            factory = PeerConnectionFactory.builder().setAudioDeviceModule(adm!!).createPeerConnectionFactory()
            audioSource = factory!!.createAudioSource(MediaConstraints().apply {
                mandatory.add(MediaConstraints.KeyValuePair("googEchoCancellation", "true"))
                mandatory.add(MediaConstraints.KeyValuePair("googNoiseSuppression", "true"))
                mandatory.add(MediaConstraints.KeyValuePair("googAutoGainControl", "true"))
            })
            muted = ptt // PTT: mulai mute; open mic: langsung nyala
            micTrack = factory!!.createAudioTrack("k13-mic", audioSource!!).apply { setEnabled(!muted) }
            joined = true
            val base = db.child("rooms").child(roomCode)
            val voiceRef = base.child("voice")
            val signalRef = base.child("vsig").child(playerId)
            signalRef.removeValue()
            signalRef.onDisconnect().removeValue()
            voiceRef.child(playerId).onDisconnect().removeValue()
            signalListener = object : ChildEventListener {
                override fun onChildAdded(s: DataSnapshot, p: String?) = handleSignal(s)
                override fun onChildChanged(s: DataSnapshot, p: String?) {}
                override fun onChildRemoved(s: DataSnapshot) {}
                override fun onChildMoved(s: DataSnapshot, p: String?) {}
                override fun onCancelled(e: DatabaseError) { onStatus("Signaling gagal: ${e.message}") }
            }
            signalRef.addChildEventListener(signalListener!!)
            voiceListener = object : ValueEventListener {
                override fun onDataChange(s: DataSnapshot) {
                    val now = HashMap<String, Long>()
                    for (child in s.children) if (child.key != playerId) now[child.key!!] = child.child("t").getValue(Long::class.java) ?: 1L
                    members.keys.toList().filter { !now.containsKey(it) }.forEach { dropPeer(it) }
                    members.clear(); members.putAll(now)
                    now.keys.forEach { id -> if (playerId < id && !peers.containsKey(id)) createPeer(id, true) }
                    onStatus(statusText())
                }
                override fun onCancelled(e: DatabaseError) { onStatus("Voice: ${e.message}") }
            }
            voiceRef.addValueEventListener(voiceListener!!)
            voiceRef.child(playerId).setValue(mapOf("t" to ServerValue.TIMESTAMP))
            (app.getSystemService(Context.AUDIO_SERVICE) as AudioManager).mode = AudioManager.MODE_IN_COMMUNICATION
            raiseCallVolume(); routeAudio(); watchDevices(); onJoinedChange?.invoke(true) // MODE_IN_COMMUNICATION default-nya ke speaker telinga (kayak telepon): paksa ke speaker luar
            onStatus("Mikrofon aktif · menunggu pemain lain")
            scheduler?.shutdownNow()
            scheduler = Executors.newSingleThreadScheduledExecutor(ThreadFactory { r -> Thread(r).apply { isDaemon = true } }).also {
                it.scheduleWithFixedDelay({ try { pollSpeaking() } catch (_: Throwable) {} }, 300L, 250L, TimeUnit.MILLISECONDS)
            }
        } catch (e: Throwable) { // Throwable: UnsatisfiedLinkError / NoClassDefFoundError dari native WebRTC bukan Exception
            leave(); onStatus("Voice gagal dimulai: ${e.message ?: "kesalahan WebRTC"}")
        }
    }

    fun toggleMute() {
        setMuted(!muted)
        onStatus(if (muted) "Mikrofon dimatikan" else "Mikrofon aktif")
    }

    fun leave() {
        val c = code
        joined = false
        scheduler?.shutdownNow(); scheduler = null
        speakingNow.clear(); lastSpeakKey = ""
        val base = c?.let { db.child("rooms").child(it) }
        try { signalListener?.let { base?.child("vsig")?.child(playerId)?.removeEventListener(it) } } catch (_: Exception) {}
        try { voiceListener?.let { base?.child("voice")?.removeEventListener(it) } } catch (_: Exception) {}
        signalListener = null; voiceListener = null
        peers.keys.toList().forEach { dropPeer(it) }
        if (c != null) {
            base?.child("voice")?.child(playerId)?.removeValue()
            base?.child("vsig")?.child(playerId)?.removeValue()
            base?.child("voice")?.child(playerId)?.onDisconnect()?.cancel()
            base?.child("vsig")?.child(playerId)?.onDisconnect()?.cancel()
        }
        try { micTrack?.dispose(); audioSource?.dispose(); factory?.dispose(); adm?.release() } catch (_: Exception) {}
        micTrack = null; audioSource = null; factory = null; adm = null
        code = null; members.clear(); muted = false
        unwatchDevices(); resetRoute(); onJoinedChange?.invoke(false)
        try { (app.getSystemService(Context.AUDIO_SERVICE) as AudioManager).mode = AudioManager.MODE_NORMAL } catch (_: Exception) {}
        onStatus("Voice tidak aktif")
    }

    private fun audioMgr() = app.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    /** Suara voice diputar lewat stream PANGGILAN (MODE_IN_COMMUNICATION), volumenya terpisah dari volume media. Naikkan ke maksimal saat gabung; tombol volume HP ikut mengatur stream ini selama voice aktif. */
    private fun raiseCallVolume() {
        try {
            val am = audioMgr()
            am.setStreamVolume(AudioManager.STREAM_VOICE_CALL, am.getStreamMaxVolume(AudioManager.STREAM_VOICE_CALL), 0)
        } catch (_: Throwable) {}
    }

    /** Keluar lewat speaker luar, kecuali ada headset (kabel / USB / Bluetooth): itu tetap dipakai. */
    @Suppress("DEPRECATION")
    private fun routeAudio() {
        try {
            val am = audioMgr()
            if (Build.VERSION.SDK_INT >= 31) {
                val devs = am.availableCommunicationDevices
                val target = devs.firstOrNull { it.type in HEADSET_TYPES } ?: devs.firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER }
                if (target != null) am.setCommunicationDevice(target)
            } else {
                val headset = am.getDevices(AudioManager.GET_DEVICES_OUTPUTS).any { it.type in HEADSET_TYPES }
                am.isSpeakerphoneOn = !headset
            }
        } catch (_: Throwable) {}
    }

    @Suppress("DEPRECATION")
    private fun resetRoute() {
        try {
            val am = audioMgr()
            if (Build.VERSION.SDK_INT >= 31) am.clearCommunicationDevice()
            else am.isSpeakerphoneOn = false
        } catch (_: Throwable) {}
    }

    // headset dicolok / dicabut / Bluetooth nyambung saat voice jalan: atur ulang jalurnya
    private fun watchDevices() {
        if (deviceCb != null) return
        try {
            val cb = object : AudioDeviceCallback() {
                override fun onAudioDevicesAdded(added: Array<out AudioDeviceInfo>?) { if (joined) routeAudio() }
                override fun onAudioDevicesRemoved(removed: Array<out AudioDeviceInfo>?) { if (joined) routeAudio() }
            }
            deviceCb = cb
            audioMgr().registerAudioDeviceCallback(cb, Handler(Looper.getMainLooper()))
        } catch (_: Throwable) { deviceCb = null }
    }

    private fun unwatchDevices() {
        val cb = deviceCb ?: return
        deviceCb = null
        try { audioMgr().unregisterAudioDeviceCallback(cb) } catch (_: Throwable) {}
    }

    private fun createPeer(id: String, makeOffer: Boolean): Peer? {
        peers[id]?.let { return it }
        val f = factory ?: return null
        val pc = f.createPeerConnection(PeerConnection.RTCConfiguration(iceServers), object : PeerConnection.Observer {
            override fun onSignalingChange(s: PeerConnection.SignalingState) {}
            override fun onIceConnectionChange(s: PeerConnection.IceConnectionState) { onStatus(statusText()) }
            override fun onIceConnectionReceivingChange(b: Boolean) {}
            override fun onIceGatheringChange(s: PeerConnection.IceGatheringState) {}
            override fun onIceCandidate(c: IceCandidate) { sendSignal(id, "ice", JSONObject().put("sdpMid", c.sdpMid).put("sdpMLineIndex", c.sdpMLineIndex).put("candidate", c.sdp).toString()) }
            override fun onIceCandidatesRemoved(c: Array<out IceCandidate>) {}
            override fun onAddStream(s: MediaStream) {}
            override fun onRemoveStream(s: MediaStream) {}
            override fun onDataChannel(c: DataChannel) {}
            override fun onRenegotiationNeeded() {}
            override fun onAddTrack(r: RtpReceiver, s: Array<out MediaStream>) {}
            override fun onTrack(t: RtpTransceiver) {}
        }) ?: return null
        val p = Peer(pc); peers[id] = p
        micTrack?.let { pc.addTrack(it, listOf("k13-audio")) }
        if (makeOffer) pc.createOffer(object : SdpObserver {
            override fun onCreateSuccess(d: SessionDescription) { val munged = SessionDescription(d.type, opusSdp(d.description)); pc.setLocalDescription(object : SdpObserver by EmptySdpObserver() { override fun onSetSuccess() { sendSignal(id, "offer", sdpJson(munged)) } }, munged) }
            override fun onSetSuccess() {}
            override fun onCreateFailure(e: String) { onStatus("Offer gagal: $e") }
            override fun onSetFailure(e: String) { onStatus("SDP gagal: $e") }
        }, MediaConstraints())
        return p
    }

    private fun handleSignal(s: DataSnapshot) {
        val m = s.value as? Map<*, *> ?: run { s.ref.removeValue(); return }
        s.ref.removeValue()
        val from = m["f"] as? String ?: return
        if (from == playerId || !joined) return
        val kind = m["k"] as? String ?: return
        val raw = m["d"] as? String ?: return
        try {
            val j = JSONObject(raw)
            when (kind) {
                "offer" -> {
                    val p = createPeer(from, false) ?: return
                    val desc = SessionDescription(SessionDescription.Type.OFFER, j.getString("sdp"))
                    p.pc.setRemoteDescription(object : SdpObserver by EmptySdpObserver() {
                        override fun onSetSuccess() {
                            p.remoteSet = true; flushIce(p)
                            p.pc.createAnswer(object : SdpObserver {
                                override fun onCreateSuccess(d: SessionDescription) { val a = SessionDescription(d.type, opusSdp(d.description)); p.pc.setLocalDescription(object : SdpObserver by EmptySdpObserver() { override fun onSetSuccess() { sendSignal(from, "answer", sdpJson(a)) } }, a) }
                                override fun onSetSuccess() {}
                                override fun onCreateFailure(e: String) { onStatus("Answer gagal: $e") }
                                override fun onSetFailure(e: String) { onStatus("SDP gagal: $e") }
                            }, MediaConstraints())
                        }
                    }, desc)
                }
                "answer" -> peers[from]?.let { p -> p.pc.setRemoteDescription(object : SdpObserver by EmptySdpObserver() { override fun onSetSuccess() { p.remoteSet = true; flushIce(p) } }, SessionDescription(SessionDescription.Type.ANSWER, j.getString("sdp"))) }
                "ice" -> {
                    val c = IceCandidate(j.optString("sdpMid", "audio"), j.optInt("sdpMLineIndex", 0), j.getString("candidate"))
                    val p = peers[from] ?: createPeer(from, false)
                    if (p != null) if (p.remoteSet) p.pc.addIceCandidate(c) else p.queuedIce.add(c)
                }
            }
        } catch (e: Exception) { onStatus("Sinyal voice tidak valid") }
    }

    private fun flushIce(p: Peer) { p.queuedIce.forEach { p.pc.addIceCandidate(it) }; p.queuedIce.clear() }
    private fun sendSignal(to: String, kind: String, data: String) {
        val c = code ?: return
        db.child("rooms").child(c).child("vsig").child(to).push().setValue(mapOf("f" to playerId, "k" to kind, "d" to data))
    }
    private fun dropPeer(id: String) { peers.remove(id)?.pc?.dispose() }
    // level mic sendiri dari sampel PCM16 (thread audio WebRTC)
    private fun onMicSamples(smp: JavaAudioDeviceModule.AudioSamples) {
        if (muted || smp.audioFormat != AudioFormat.ENCODING_PCM_16BIT) return
        val data = smp.data
        var sum = 0.0
        var n = 0
        var i = 0
        while (i + 1 < data.size) {
            val v = ((data[i + 1].toInt() shl 8) or (data[i].toInt() and 0xff)).toShort().toInt()
            sum += v.toDouble() * v
            n++
            i += 2
        }
        if (n > 0 && Math.sqrt(sum / n) / 32768.0 > MIC_TH) speakingNow[playerId] = SystemClock.uptimeMillis()
    }

    // level pemain lain dari statistik inbound-rtp (audioLevel 0..1), dibaca tiap 250 ms
    private fun pollSpeaking() {
        if (!joined) return
        for ((id, p) in peers) {
            try {
                p.pc.getStats(RTCStatsCollectorCallback { report ->
                    var lvl = 0.0
                    for (st in report.statsMap.values) {
                        if (st.type == "inbound-rtp" && st.members["kind"] == "audio") {
                            val a = (st.members["audioLevel"] as? Number)?.toDouble() ?: 0.0
                            if (a > lvl) lvl = a
                        }
                    }
                    if (lvl > REMOTE_TH) speakingNow[id] = SystemClock.uptimeMillis()
                })
            } catch (_: Throwable) {}
        }
        val key = speakingIds().sorted().joinToString(",")
        if (key != lastSpeakKey) { lastSpeakKey = key; onStatus(statusText()) } // memicu gambar ulang
    }

    fun speakingIds(): Set<String> {
        if (!joined) return emptySet()
        val t = SystemClock.uptimeMillis()
        return speakingNow.filter { t - it.value < HOLD_MS && (it.key != playerId || !muted) }.keys
    }

    fun isSpeaking(id: String): Boolean = speakingIds().contains(id)

    private fun statusText(): String {
        if (!joined) return "Voice tidak aktif"
        val connected = peers.values.count { it.pc.iceConnectionState() == PeerConnection.IceConnectionState.CONNECTED || it.pc.iceConnectionState() == PeerConnection.IceConnectionState.COMPLETED }
        return "${if (muted) "Mic mati" else "Mic aktif"} · tersambung $connected/${members.size}"
    }
    private fun sdpJson(d: SessionDescription) = JSONObject().put("type", d.type.canonicalForm()).put("sdp", d.description).toString()
    private fun opusSdp(sdp: String): String {
        val match = Regex("a=rtpmap:(\\d+) opus/48000", RegexOption.IGNORE_CASE).find(sdp) ?: return sdp
        val pt = match.groupValues[1]
        val fmtp = "maxaveragebitrate=24000;stereo=0;sprop-stereo=0;useinbandfec=1;usedtx=1;maxplaybackrate=24000"
        val pattern = Regex("a=fmtp:$pt ([^\\r\\n]*)")
        return if (pattern.containsMatchIn(sdp)) pattern.replace(sdp) { "a=fmtp:$pt ${it.groupValues[1].replace(Regex(";?(maxaveragebitrate|stereo|sprop-stereo|useinbandfec|usedtx|maxplaybackrate)=[^;]*"), "")};$fmtp" }
        else sdp.replace(match.value, match.value + "\r\na=fmtp:$pt $fmtp")
    }
    private open class EmptySdpObserver : SdpObserver {
        override fun onCreateSuccess(d: SessionDescription) {}
        override fun onSetSuccess() {}
        override fun onCreateFailure(e: String) {}
        override fun onSetFailure(e: String) {}
    }
}
