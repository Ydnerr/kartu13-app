package id.kartu13.app

import android.content.Context
import com.google.firebase.FirebaseApp
import com.google.firebase.FirebaseOptions
import com.google.firebase.database.DataSnapshot
import com.google.firebase.database.DatabaseError
import com.google.firebase.database.DatabaseReference
import com.google.firebase.database.Query
import com.google.firebase.database.FirebaseDatabase
import com.google.firebase.database.MutableData
import com.google.firebase.database.Transaction
import com.google.firebase.database.ServerValue
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import com.google.firebase.database.ValueEventListener
import java.util.UUID
import kotlin.random.Random

/**
 * Client Firebase untuk protokol room web Kartu 13 v44.
 * Struktur yang ditulis di sini sengaja mengikuti game.js: rooms/{kode}/{players,host,status,game,bot}.
 * Tidak memakai google-services.json karena konfigurasi publik web sudah tersedia di game.js.
 */
class FirebaseRoom(context: Context) {
    companion object {
        const val VMAJ = "v44" // sama persis dengan web: VMAJ = VER.split('.')[0] dari VER = 'v44.11'
        private const val DB_URL = "https://poker13-86781-default-rtdb.asia-southeast1.firebasedatabase.app"
        private const val API_KEY = "AIzaSyDuIFh5L1U6QfmGXf6gwyqhLNqs_flk6-A"
        private const val PROJECT_ID = "poker13-86781"
        private const val APP_ID = "1:277404446731:web:1b87947e9c6512f8b4d70e"
        private const val SENDER_ID = "277404446731"
        private const val STORAGE = "poker13-86781.firebasestorage.app"

        fun database(context: Context): FirebaseDatabase {
            val app = FirebaseApp.getApps(context).firstOrNull() ?: FirebaseApp.initializeApp(
                context,
                FirebaseOptions.Builder()
                    .setApiKey(API_KEY)
                    .setApplicationId(APP_ID)
                    .setProjectId(PROJECT_ID)
                    .setGcmSenderId(SENDER_ID)
                    .setDatabaseUrl(DB_URL)
                    .setStorageBucket(STORAGE)
                    .build()
            )
            return FirebaseDatabase.getInstance(app!!, DB_URL)
        }
    }

    data class Player(val id: String, val name: String, val joinedAt: Long, val bot: Boolean)
    data class ChatMessage(val key: String, val playerId: String, val name: String, val message: String, val time: Long)
    data class Presence(val online: Boolean?, val seen: Long, val ping: Long?, val hiddenAt: Long, val offAt: Long)
    data class PlayerStatus(val state: String, val ping: Long? = null) // on, away, off, afk, bot
    data class RoomState(
        val code: String,
        val host: String,
        val status: String,
        val players: List<Player>,
        val botLevel: Int,
        val game: Map<String, Any?>?,
        val presence: Map<String, Presence> = emptyMap(),
        val connected: Boolean = false,
        val myPing: Long? = null,
        val effectiveDriver: String = host
    )

    private val prefs = context.getSharedPreferences("k13", Context.MODE_PRIVATE)
    private val db = database(context).reference
    val playerId: String = prefs.getString("pid", null) ?: ("p" + UUID.randomUUID().toString().replace("-", "").take(12)).also {
        prefs.edit().putString("pid", it).apply()
    }
    var currentCode: String? = prefs.getString("room", null)
        private set
    private var roomRef: DatabaseReference? = null
    private val listeners = ArrayList<Pair<DatabaseReference, ValueEventListener>>()
    private val main = Handler(Looper.getMainLooper())
    private var presenceListener: ValueEventListener? = null
    private var connectedListener: ValueEventListener? = null
    private var offsetListener: ValueEventListener? = null
    private var connected = false
    private var serverOffset = 0L
    private var myPing: Long? = null
    private var hiddenAt = 0L
    private var connectionAt = 0L
    private var roomPlayers: List<Player> = emptyList()
    private var roomHost = ""
    private var roomStatus = "lobby"
    private var roomPresence: Map<String, Presence> = emptyMap()
    private var roomBotLevel = 1
    private var roomGame: Map<String, Any?>? = null
    private var roomCode: String? = null
    private var roomCallback: ((Result<RoomState>) -> Unit)? = null
    private val heartbeat = object : Runnable { override fun run() { sendHeartbeat(); main.postDelayed(this, 8_000L) } }
    private val presenceTick = object : Runnable { override fun run() { publishState(); main.postDelayed(this, 2_000L) } }

    fun savedName(): String = prefs.getString("name", "") ?: ""
    fun saveName(name: String) { prefs.edit().putString("name", name.trim().take(14)).apply() }

    fun createRoom(name: String, done: (Result<String>) -> Unit) {
        val n = name.trim().take(14)
        if (n.isEmpty()) return done(Result.failure(IllegalArgumentException("Isi nama pemain dulu.")))
        saveName(n)
        createAttempt(n, 0, done)
    }

    private fun createAttempt(name: String, attempt: Int, done: (Result<String>) -> Unit) {
        if (attempt >= 8) return done(Result.failure(IllegalStateException("Gagal membuat room. Coba lagi.")))
        val code = (0..4).joinToString("") { Random.nextInt(10).toString() }
        val now = System.currentTimeMillis()
        val value = mapOf(
            "host" to playerId,
            "status" to "lobby",
            "created" to now,
            "players" to mapOf(playerId to mapOf("n" to name, "t" to now))
        )
        db.child("rooms").child(code).runTransaction(object : Transaction.Handler {
            override fun doTransaction(currentData: MutableData): Transaction.Result {
                if (currentData.value != null) return Transaction.abort()
                currentData.value = value
                return Transaction.success(currentData)
            }
            override fun onComplete(error: DatabaseError?, committed: Boolean, snapshot: DataSnapshot?) {
                if (error != null) done(Result.failure(error.toException()))
                else if (committed) { remember(code); done(Result.success(code)) }
                else createAttempt(name, attempt + 1, done)
            }
        }, false)
    }

    fun joinRoom(rawCode: String, name: String, done: (Result<String>) -> Unit) {
        val code = rawCode.trim()
        val n = name.trim().take(14)
        if (n.isEmpty()) return done(Result.failure(IllegalArgumentException("Isi nama pemain dulu.")))
        if (!code.matches(Regex("\\d{5}"))) return done(Result.failure(IllegalArgumentException("Kode room harus 5 angka.")))
        saveName(n)
        db.child("rooms").child(code).runTransaction(object : Transaction.Handler {
            override fun doTransaction(currentData: MutableData): Transaction.Result {
                val room = currentData.value as? MutableMap<String, Any?> ?: return Transaction.abort()
                val players = (room["players"] as? MutableMap<String, Any?>) ?: mutableMapOf()
                if (players.containsKey(playerId)) return Transaction.success(currentData)
                if (room["status"] != "lobby") return Transaction.abort()
                if (players.size >= 4) return Transaction.abort()
                players[playerId] = mapOf("n" to n, "t" to System.currentTimeMillis())
                room["players"] = players
                currentData.value = room
                return Transaction.success(currentData)
            }
            override fun onComplete(error: DatabaseError?, committed: Boolean, snapshot: DataSnapshot?) {
                if (error != null) done(Result.failure(error.toException()))
                else if (committed && snapshot?.child("players")?.hasChild(playerId) == true) {
                    remember(code); done(Result.success(code))
                } else done(Result.failure(IllegalStateException("Room tidak ditemukan, sudah dimulai, atau penuh.")))
            }
        }, false)
    }

    private fun remember(code: String) {
        currentCode = code
        prefs.edit().putString("room", code).apply()
    }

    fun listen(code: String, onChange: (Result<RoomState>) -> Unit) {
        stopListening()
        roomCode = code
        roomCallback = onChange
        val ref = db.child("rooms").child(code)
        roomRef = ref
        val cache = HashMap<String, Any?>()
        val loaded = HashSet<String>()
        val keys = listOf("players", "host", "status", "game", "bot")
        keys.forEach { key ->
            val childRef = ref.child(key)
            val l = object : ValueEventListener {
                override fun onDataChange(snapshot: DataSnapshot) {
                    cache[key] = snapshot.value
                    loaded.add(key)
                    if (loaded.size != keys.size) return
                    val rawPlayers = cache["players"] as? Map<*, *> ?: emptyMap<Any, Any>()
                    val players = rawPlayers.entries.mapNotNull { entry ->
                        val id = entry.key as? String ?: return@mapNotNull null
                        val data = entry.value as? Map<*, *> ?: emptyMap<Any, Any>()
                        Player(id, data["n"] as? String ?: "Pemain", (data["t"] as? Number)?.toLong() ?: 0L, data["bot"] as? Boolean ?: false)
                    }.sortedBy { it.joinedAt }
                    if (players.none { it.id == playerId }) {
                        currentCode = null
                        prefs.edit().remove("room").apply()
                        onChange(Result.failure(IllegalStateException("Kamu sudah tidak terdaftar di room.")))
                        return
                    }
                    roomPlayers = players
                    roomHost = cache["host"] as? String ?: ""
                    roomStatus = cache["status"] as? String ?: "lobby"
                    roomBotLevel = ((cache["bot"] as? Map<*, *>)?.get("lv") as? Number)?.toInt() ?: 1
                    roomGame = cache["game"] as? Map<String, Any?>
                    publishState()
                }
                override fun onCancelled(error: DatabaseError) = onChange(Result.failure(error.toException()))
            }
            listeners.add(childRef to l)
            childRef.addValueEventListener(l)
        }
        presenceListener = object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                val map = LinkedHashMap<String, Presence>()
                snapshot.children.forEach { child ->
                    val online = child.child("online").getValue(Boolean::class.java)
                    map[child.key ?: return@forEach] = Presence(
                        online,
                        (child.child("seen").value as? Number)?.toLong() ?: 0L,
                        (child.child("ping").value as? Number)?.toLong(),
                        (child.child("hid").value as? Number)?.toLong() ?: 0L,
                        (child.child("off").value as? Number)?.toLong() ?: 0L
                    )
                }
                roomPresence = map
                publishState()
            }
            override fun onCancelled(error: DatabaseError) { }
        }
        ref.child("presence").addValueEventListener(presenceListener!!)
        connectedListener = object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                connected = snapshot.getValue(Boolean::class.java) == true
                if (connected) { connectionAt = System.currentTimeMillis(); registerDisconnect(); sendHeartbeat() }
                else myPing = null
                publishState()
            }
            override fun onCancelled(error: DatabaseError) { connected = false; publishState() }
        }
        db.child(".info/connected").addValueEventListener(connectedListener!!)
        offsetListener = object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) { serverOffset = (snapshot.value as? Number)?.toLong() ?: 0L }
            override fun onCancelled(error: DatabaseError) { }
        }
        db.child(".info/serverTimeOffset").addValueEventListener(offsetListener!!)
        main.removeCallbacks(heartbeat); main.postDelayed(heartbeat, 8_000L)
        main.removeCallbacks(presenceTick); main.postDelayed(presenceTick, 2_000L)
        remember(code)
    }

    private fun serverNow(): Long = System.currentTimeMillis() + serverOffset

    private fun registerDisconnect() {
        val ref = roomRef ?: return
        // Register server-side disconnect before publishing online, matching web v44.
        ref.child("presence").child(playerId).onDisconnect().updateChildren(mapOf("online" to false, "off" to ServerValue.TIMESTAMP))
            .addOnSuccessListener { sendHeartbeat() }
    }

    private fun sendHeartbeat() {
        val ref = roomRef ?: return
        if (!connected) return
        val now = serverNow()
        val hidden = hiddenAt > 0L
        val started = System.currentTimeMillis()
        val values = hashMapOf<String, Any?>(
            "online" to true,
            "seen" to ServerValue.TIMESTAMP,
            "ping" to myPing,
            "hid" to if (hidden) hiddenAt else null
        )
        ref.child("presence").child(playerId).updateChildren(values).addOnCompleteListener { task ->
            if (task.isSuccessful && !hidden) {
                myPing = (System.currentTimeMillis() - started).coerceAtLeast(0L)
                publishState()
            }
        }
    }

    fun setAppVisible(visible: Boolean) {
        hiddenAt = if (visible) 0L else if (hiddenAt == 0L) serverNow() else hiddenAt
        sendHeartbeat()
    }

    fun playerStatus(id: String, statePlayers: List<Player> = roomPlayers): PlayerStatus {
        val player = statePlayers.firstOrNull { it.id == id } ?: return PlayerStatus("off")
        if (player.bot) return PlayerStatus("bot")
        val pr = roomPresence[id] ?: return PlayerStatus("on")
        val now = serverNow()
        var since = 0L
        if (pr.online == false) since = if (pr.offAt > 0) pr.offAt else pr.seen
        else if (pr.hiddenAt == 0L && pr.seen > 0L && now - pr.seen > 25_000L) since = pr.seen + 25_000L
        if (since > 0L) return PlayerStatus(if (now - since > 8_000L) "afk" else "off", pr.ping)
        if (pr.hiddenAt > 0L) return PlayerStatus(if (now - pr.hiddenAt > 20_000L) "afk" else "away", pr.ping)
        return PlayerStatus("on", pr.ping)
    }

    private fun effectiveDriver(): String {
        val humans = roomPlayers.filter { !it.bot }
        if (humans.isEmpty()) return roomHost
        val ordered = humans.sortedWith(compareBy<Player> { if (it.id == roomHost) 0 else 1 }.thenBy { it.joinedAt })
        return ordered.firstOrNull { it.id == playerId || playerStatus(it.id).state != "afk" }?.id ?: ""
    }

    private fun publishState() {
        if (roomPlayers.isEmpty()) return
        val state = RoomState(roomCode ?: return, roomHost, roomStatus, roomPlayers, roomBotLevel, roomGame,
            roomPresence, connected, myPing, effectiveDriver())
        roomCallback?.invoke(Result.success(state))
    }

    fun startGame(state: RoomState, done: (Result<Unit>) -> Unit) {
        val mayStart = if (state.status == "lobby") state.host == playerId else state.effectiveDriver == playerId
        if (!mayStart) return done(Result.failure(IllegalStateException("Menunggu host aktif atau pengendali berikutnya.")))
        if (state.players.size < 2) return done(Result.failure(IllegalStateException("Minimal 2 pemain.")))
        // Rotasi urutan menjaga arah giliran, tetapi menempatkan pemain lokal pada indeks 0 di mesin Kotlin.
        val ids = state.players.map { it.id }
        val rotated = ids.dropWhile { it != playerId } + ids.takeWhile { it != playerId }
        var lead: Int? = null
        state.game?.let { old ->
            val previous = GameWire.decode(old, playerId)?.first
            if (previous != null && previous.over && !previous.bomb) {
                val winnerSeat = previous.fin.firstOrNull()
                val winnerPid = winnerSeat?.let { previousSeat ->
                    // decode() returns seats with player lokal pada indeks 0.
                    val previousIds = (old["order"] as? List<*>)?.mapNotNull { it as? String }.orEmpty()
                    val rotatedPrevious = previousIds.dropWhile { it != playerId } + previousIds.takeWhile { it != playerId }
                    rotatedPrevious.getOrNull(previousSeat)
                }
                lead = winnerPid?.let { rotated.indexOf(it).takeIf { ix -> ix >= 0 } }
            }
        }
        val game = deal(rotated.indices.toList(), lead)
        val payload = GameWire.encode(game, rotated)
        roomRef?.updateChildren(mapOf("status" to "play", "game" to payload))
            ?.addOnSuccessListener { done(Result.success(Unit)) }
            ?.addOnFailureListener { done(Result.failure(it)) }
            ?: done(Result.failure(IllegalStateException("Room belum terhubung.")))
    }

    fun submitMove(cards: List<Int>, pass: Boolean, done: (Result<Unit>) -> Unit = {}) {
        val ref = roomRef?.child("game") ?: return done(Result.failure(IllegalStateException("Room belum terhubung.")))
        ref.runTransaction(object : Transaction.Handler {
            override fun doTransaction(data: MutableData): Transaction.Result {
                val raw = data.value as? Map<String, Any?> ?: return Transaction.abort()
                val decoded = GameWire.decode(raw, playerId) ?: return Transaction.abort()
                val (g, seats) = decoded
                if (g.order.getOrNull(g.turn) != 0 || !move(g, 0, cards, pass)) return Transaction.abort()
                data.value = GameWire.encode(g, seats)
                return Transaction.success(data)
            }
            override fun onComplete(error: DatabaseError?, committed: Boolean, snapshot: DataSnapshot?) {
                when {
                    error != null -> done(Result.failure(error.toException()))
                    committed -> done(Result.success(Unit))
                    else -> done(Result.failure(IllegalStateException("Giliran berubah atau kombinasi tidak valid.")))
                }
            }
        }, false)
    }

    fun submitDecision(stop: Boolean, done: (Result<Unit>) -> Unit = {}) {
        val ref = roomRef?.child("game") ?: return done(Result.failure(IllegalStateException("Room belum terhubung.")))
        ref.runTransaction(object : Transaction.Handler {
            override fun doTransaction(data: MutableData): Transaction.Result {
                val raw = data.value as? Map<String, Any?> ?: return Transaction.abort()
                val decoded = GameWire.decode(raw, playerId) ?: return Transaction.abort()
                val (g, seats) = decoded
                if (!g.dec.contains(0)) return Transaction.abort()
                decide(g, 0, stop)
                data.value = GameWire.encode(g, seats)
                return Transaction.success(data)
            }
            override fun onComplete(error: DatabaseError?, committed: Boolean, snapshot: DataSnapshot?) {
                when {
                    error != null -> done(Result.failure(error.toException()))
                    committed -> done(Result.success(Unit))
                    else -> done(Result.failure(IllegalStateException("Pilihan sudah berubah.")))
                }
            }
        }, false)
    }

    /** Host menjalankan bot dengan transaksi; hanya satu host yang boleh menjadi driver. */
    fun runBotTurn(state: RoomState) {
        if (state.effectiveDriver != playerId || state.status != "play" || !state.connected) return
        if (System.currentTimeMillis() - connectionAt < 3_000L) return
        val ref = roomRef?.child("game") ?: return
        ref.runTransaction(object : Transaction.Handler {
            override fun doTransaction(data: MutableData): Transaction.Result {
                val raw = data.value as? Map<String, Any?> ?: return Transaction.abort()
                val decoded = GameWire.decode(raw, playerId) ?: return Transaction.abort()
                val (g, seats) = decoded
                if (g.over) return Transaction.abort()
                val autoIds = state.players.filter { it.bot || (!it.bot && state.players.count { p -> !p.bot } > 1 && playerStatus(it.id, state.players).state == "afk") }.map { it.id }.toSet()
                val decisionPid = g.dec.firstOrNull { seats.getOrNull(it) in autoIds }
                if (decisionPid != null) {
                    val decisionOwner = seats.getOrNull(decisionPid)
                    val stop = if (state.players.firstOrNull { it.id == decisionOwner }?.bot == true) botDecide(state.botLevel) else false
                    decide(g, decisionPid, stop)
                    data.value = GameWire.encode(g, seats)
                    return Transaction.success(data)
                }
                if (g.dec.isNotEmpty()) return Transaction.abort()
                val current = g.order.getOrNull(g.turn) ?: return Transaction.abort()
                if (seats.getOrNull(current) !in autoIds || g.fin.contains(current)) return Transaction.abort()
                val m = botMove(g, current, state.botLevel)
                var ok = move(g, current, m.cards ?: emptyList(), m.pass)
                if (!ok) ok = move(g, current, emptyList(), true)
                if (!ok) ok = move(g, current, listOf(g.hands[current].minOrNull() ?: 0), false)
                if (!ok) return Transaction.abort()
                data.value = GameWire.encode(g, seats)
                return Transaction.success(data)
            }
            override fun onComplete(error: DatabaseError?, committed: Boolean, snapshot: DataSnapshot?) { /* listener berikutnya memicu bot lagi */ }
        }, false)
    }

    fun addBot(level: Int, state: RoomState, done: (Result<Unit>) -> Unit = {}) {
        if (state.host != playerId) return done(Result.failure(IllegalStateException("Hanya host.")))
        val playersRef = roomRef?.child("players") ?: return done(Result.failure(IllegalStateException("Room belum terhubung.")))
        playersRef.runTransaction(object : Transaction.Handler {
            override fun doTransaction(data: MutableData): Transaction.Result {
                val players = data.value as? MutableMap<String, Any?> ?: return Transaction.abort()
                val humans = players.values.count { (it as? Map<*, *>)?.get("bot") != true }
                if (humans < 2 || players.size >= 4) return Transaction.abort()
                val names = players.values.mapNotNull { (it as? Map<*, *>)?.get("n") as? String }.toSet()
                val botName = listOf("Bot Budi", "Bot Sari", "Bot Joko", "Bot Cadangan").firstOrNull { it !in names } ?: "Bot Cadangan"
                var id: String
                do { id = "bot" + UUID.randomUUID().toString().take(7) } while (players.containsKey(id))
                players[id] = mapOf("n" to botName, "t" to System.currentTimeMillis(), "bot" to true)
                data.value = players
                return Transaction.success(data)
            }
            override fun onComplete(error: DatabaseError?, committed: Boolean, snapshot: DataSnapshot?) {
                if (error != null) done(Result.failure(error.toException()))
                else if (!committed) done(Result.failure(IllegalStateException("Tambah bot butuh 2 pemain manusia dan kursi kosong.")))
                else roomRef?.child("bot/lv")?.setValue(level.coerceIn(0, 3))
                    ?.addOnSuccessListener { done(Result.success(Unit)) }
                    ?.addOnFailureListener { done(Result.failure(it)) }
            }
        }, false)
    }

    fun setBotLevel(level: Int, state: RoomState, done: (Result<Unit>) -> Unit = {}) {
        if (state.host != playerId) return done(Result.failure(IllegalStateException("Hanya host.")))
        roomRef?.child("bot/lv")?.setValue(level.coerceIn(0, 3))
            ?.addOnSuccessListener { done(Result.success(Unit)) }
            ?.addOnFailureListener { done(Result.failure(it)) }
            ?: done(Result.failure(IllegalStateException("Room belum terhubung.")))
    }

    fun removeBot(id: String, state: RoomState, done: (Result<Unit>) -> Unit = {}) {
        if (state.host != playerId || state.players.none { it.id == id && it.bot })
            return done(Result.failure(IllegalStateException("Bot tidak bisa dihapus.")))
        roomRef?.child("players")?.child(id)?.removeValue()
            ?.addOnSuccessListener { done(Result.success(Unit)) }
            ?.addOnFailureListener { done(Result.failure(it)) }
            ?: done(Result.failure(IllegalStateException("Room belum terhubung.")))
    }


    private var chatRef: Query? = null
    private var chatListener: ValueEventListener? = null
    private var lastChatSend = 0L
    private var lastReactSend = 0L
    private var reactRef: Query? = null
    private var reactListener: com.google.firebase.database.ChildEventListener? = null
    private val allowedReactions = setOf("😂", "🔥", "👏", "😭", "😡", "🤡", "💀", "👀")

    fun listenChat(code: String, onMessages: (List<ChatMessage>) -> Unit) {
        stopChatListener()
        val ref = db.child("rooms").child(code).child("chat").limitToLast(50)
        val listener = object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                val messages = snapshot.children.mapNotNull { child ->
                    val p = child.child("p").getValue(String::class.java) ?: return@mapNotNull null
                    ChatMessage(
                        child.key ?: return@mapNotNull null,
                        p,
                        child.child("n").getValue(String::class.java) ?: "?",
                        child.child("m").getValue(String::class.java) ?: "",
                        (child.child("t").value as? Number)?.toLong() ?: 0L
                    )
                }
                onMessages(messages)
            }
            override fun onCancelled(error: DatabaseError) { }
        }
        chatRef = ref
        chatListener = listener
        ref.addValueEventListener(listener)
    }

    fun stopChatListener() {
        chatListener?.let { listener -> chatRef?.removeEventListener(listener) }
        chatListener = null
        chatRef = null
    }

    fun sendChat(code: String, message: String, done: (Result<Unit>) -> Unit = {}) {
        val text = message.trim().take(140)
        val now = SystemClock.elapsedRealtime()
        if (text.isEmpty()) return done(Result.failure(IllegalArgumentException("Pesan kosong.")))
        if (now - lastChatSend < 700L) return done(Result.failure(IllegalStateException("Tunggu sebentar sebelum mengirim pesan lagi.")))
        lastChatSend = now
        val name = roomPlayers.firstOrNull { it.id == playerId }?.name ?: "?"
        db.child("rooms").child(code).child("chat").push()
            .setValue(mapOf("p" to playerId, "n" to name, "m" to text, "t" to ServerValue.TIMESTAMP))
            .addOnSuccessListener { done(Result.success(Unit)) }
            .addOnFailureListener { done(Result.failure(it)) }
    }

    fun listenReactions(code: String, onReaction: (String, String, String) -> Unit) {
        stopReactionListener()
        val ref = db.child("rooms").child(code).child("react").limitToLast(1)
        val ready = booleanArrayOf(false)
        val listener = object : com.google.firebase.database.ChildEventListener {
            override fun onChildAdded(snapshot: DataSnapshot, previousChildName: String?) {
                if (!ready[0]) return
                val emoji = snapshot.child("e").getValue(String::class.java) ?: return
                if (emoji !in allowedReactions) return
                val pid = snapshot.child("p").getValue(String::class.java) ?: return
                val name = snapshot.child("n").getValue(String::class.java) ?: "?"
                onReaction(pid, name, emoji)
            }
            override fun onChildChanged(snapshot: DataSnapshot, previousChildName: String?) { }
            override fun onChildRemoved(snapshot: DataSnapshot) { }
            override fun onChildMoved(snapshot: DataSnapshot, previousChildName: String?) { }
            override fun onCancelled(error: DatabaseError) { }
        }
        reactRef = ref
        reactListener = listener
        ref.addChildEventListener(listener)
        ref.get().addOnSuccessListener { ready[0] = true }
    }

    fun stopReactionListener() {
        reactListener?.let { listener -> reactRef?.removeEventListener(listener) }
        reactListener = null
        reactRef = null
    }

    fun sendReaction(code: String, emoji: String, done: (Result<Unit>) -> Unit = {}) {
        if (emoji !in allowedReactions) return done(Result.failure(IllegalArgumentException("Reaksi tidak dikenal.")))
        val now = SystemClock.elapsedRealtime()
        if (now - lastReactSend < 400L) return done(Result.failure(IllegalStateException("Tunggu sebentar.")))
        lastReactSend = now
        val name = roomPlayers.firstOrNull { it.id == playerId }?.name ?: "?"
        val ref = db.child("rooms").child(code).child("react").push()
        ref.setValue(mapOf("p" to playerId, "n" to name, "e" to emoji))
            .addOnSuccessListener { main.postDelayed({ ref.removeValue() }, 8_000L); done(Result.success(Unit)) }
            .addOnFailureListener { done(Result.failure(it)) }
    }

    fun leave(deleteLobbyIfHost: Boolean = true) {
        val ref = roomRef
        stopListening()
        if (ref != null) {
            // Pertahankan perilaku web: host membubarkan lobby, pemain lain hanya menghapus dirinya.
            ref.get().addOnSuccessListener { snap ->
                if (snap.child("status").getValue(String::class.java) == "lobby" &&
                    snap.child("host").getValue(String::class.java) == playerId && deleteLobbyIfHost) ref.removeValue()
                else {
                    ref.child("players").child(playerId).removeValue()
                    ref.child("presence").child(playerId).removeValue()
                }
            }
        }
        currentCode = null
        prefs.edit().remove("room").apply()
    }

    fun stopListening() {
        stopChatListener()
        stopReactionListener()
        main.removeCallbacks(heartbeat); main.removeCallbacks(presenceTick)
        roomRef?.child("presence")?.let { ref -> presenceListener?.let { ref.removeEventListener(it) } }
        presenceListener = null
        connectedListener?.let { db.child(".info/connected").removeEventListener(it) }; connectedListener = null
        offsetListener?.let { db.child(".info/serverTimeOffset").removeEventListener(it) }; offsetListener = null
        roomRef?.child("presence")?.child(playerId)?.onDisconnect()?.cancel()
        listeners.forEach { (ref, listener) -> ref.removeEventListener(listener) }
        listeners.clear()
        roomRef = null; roomCallback = null; roomPlayers = emptyList(); roomPresence = emptyMap()
        connected = false; myPing = null; hiddenAt = 0L
    }
}

/** Konversi state Kotlin <-> bentuk JSON Firebase yang dipakai klien web v44. */
object GameWire {
    fun encode(g: Game, seatPids: List<String>): Map<String, Any?> {
        fun pid(i: Int): String = seatPids.getOrElse(i) { seatPids.first() }
        val last = g.last?.let { mapOf("p" to pid(it.p), "c" to it.c, "t" to it.t.toString(), "n" to it.n, "k" to it.k, "k2" to it.k2) }
        val logs = g.log.map { e ->
            buildMap<String, Any?> {
                put("p", pid(e.p)); e.c?.let { put("c", it) }; e.m?.let { put("m", it) }; if (e.x) put("x", true)
            }
        }
        return mapOf(
            "v" to FirebaseRoom.VMAJ,
            "order" to g.order.map(::pid),
            "hands" to seatPids.mapIndexed { i, p -> p to g.hands[i].toList() }.toMap(),
            "turn" to g.turn, "last" to last,
            "passed" to g.passed.associate { pid(it) to true },
            "first" to g.first, "over" to g.over,
            "winner" to g.winner?.let(::pid),
            "fin" to g.fin.map(::pid), "dec" to g.dec.associate { pid(it) to true }.ifEmpty { null },
            "stopped" to g.stopped.associate { pid(it) to true }.ifEmpty { null }, "st" to pid(g.st), "s" to g.s,
            "bomb" to g.bomb, "self2" to g.self2?.let(::pid), "quad2" to g.quad2?.let(::pid),
            "log" to logs
        )
    }

    fun decode(raw: Map<String, Any?>, localPid: String): Pair<Game, List<String>>? {
        val ver = raw["v"]?.toString()
        if (ver != FirebaseRoom.VMAJ && ver != "44") return null // "44" = game lama buatan app versi sebelumnya
        val orderPids = (raw["order"] as? List<*>)?.mapNotNull { it as? String } ?: return null
        if (orderPids.size !in 2..4 || localPid !in orderPids) return null
        val rotated = orderPids.dropWhile { it != localPid } + orderPids.takeWhile { it != localPid }
        val index = rotated.withIndex().associate { it.value to it.index }
        val handsMap = raw["hands"] as? Map<*, *> ?: return null
        val hands = Array(rotated.size) { i ->
            ((handsMap[rotated[i]] as? List<*>) ?: emptyList<Any>()).mapNotNull { (it as? Number)?.toInt() }.toMutableList()
        }
        val mappedOrder = orderPids.mapNotNull { index[it] }
        if (mappedOrder.size != rotated.size) return null
        val g = Game(mappedOrder, hands)
        g.turn = ((raw["turn"] as? Number)?.toInt() ?: 0).coerceIn(0, mappedOrder.lastIndex)
        fun seat(v: Any?): Int? = (v as? String)?.let { index[it] }
        val lm = raw["last"] as? Map<*, *>
        g.last = lm?.let {
            val p = seat(it["p"]) ?: return null
            val cards = (it["c"] as? List<*>)?.mapNotNull { x -> (x as? Number)?.toInt() } ?: emptyList()
            Last(p, cards, (it["t"] as? String)?.firstOrNull() ?: ' ', (it["n"] as? Number)?.toInt() ?: cards.size,
                (it["k"] as? Number)?.toInt() ?: (cards.maxOrNull() ?: 0), (it["k2"] as? Number)?.toInt() ?: 0)
        }
        fun setIds(key: String): Set<Int> {
            val v = raw[key]
            val ids = when (v) {
                is Map<*, *> -> v.keys.mapNotNull { seat(it) }
                is List<*> -> v.mapNotNull { seat(it) }
                else -> emptyList()
            }
            return ids.toSet()
        }
        g.passed.addAll(setIds("passed"))
        g.first = raw["first"] as? Boolean ?: false
        g.over = raw["over"] as? Boolean ?: false
        g.winner = seat(raw["winner"])
        g.fin.addAll(((raw["fin"] as? List<*>) ?: emptyList<Any>()).mapNotNull(::seat))
        g.dec.addAll(setIds("dec"))
        g.stopped.addAll(setIds("stopped")) // web menyimpan objek {pid: true}
        g.st = seat(raw["st"]) ?: 0
        g.s = (raw["s"] as? Number)?.toInt() ?: -1
        g.bomb = raw["bomb"] as? Boolean ?: false
        g.self2 = seat(raw["self2"])
        g.quad2 = seat(raw["quad2"])
        val logs = raw["log"] as? List<*> ?: emptyList<Any>()
        logs.forEach { item ->
            val m = item as? Map<*, *> ?: return@forEach
            val p = seat(m["p"]) ?: return@forEach
            val cards = (m["c"] as? List<*>)?.mapNotNull { (it as? Number)?.toInt() }
            val xv = m["x"] // web menulis x: 1 (angka), bukan boolean
            g.log.add(LogE(p, cards, m["m"] as? String, xv == true || ((xv as? Number)?.toInt() ?: 0) != 0))
        }
        return g to rotated
    }
}
