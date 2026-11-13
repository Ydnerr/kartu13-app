package id.kartu13.app

import android.content.Context
import android.app.AlertDialog
import android.text.InputFilter
import android.text.InputType
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.ScrollView
import android.widget.Button
import android.widget.Toast
import android.view.Gravity
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.os.Build
import android.text.Html
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.os.VibrationEffect
import android.os.Vibrator
import android.text.Layout
import android.text.StaticLayout
import android.text.TextUtils
import android.text.TextPaint
import android.view.MotionEvent
import android.view.View
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.random.Random

private val GOLD = Color.parseColor("#e0b44c")
private val INK = Color.parseColor("#f3ecd8")
private val MUTE = Color.parseColor("#8fb0a2")
private val WARN = Color.parseColor("#ff9d92")
private val PANEL = Color.parseColor("#3a000000")
private val TABLE = Color.parseColor("#2e000000")
private val EDGE = Color.parseColor("#22ffffff")
private val DARK = Color.parseColor("#201a08")
private val FELT2 = Color.parseColor("#0c2a20")
private val OKC = Color.parseColor("#7be3a0")
private val BADC = Color.parseColor("#ff8a80")
private val NAMES = arrayOf("Kamu", "Bot Budi", "Bot Sari", "Bot Joko")
private const val CARD_RATIO = 0.657f

private val RULES = arrayOf(
    "Urutan kartu: 3 4 5 6 7 8 9 10 J Q K A 2. Makin ke kanan makin kuat.",
    "Logo nggak ikut dibandingkan (angka sama nggak bisa saling kalahin). Logo cuma dipakai buat nentuin 3♠ di fase kumpulin 3.",
    "Kombinasi: tunggal, pasangan, 3 kembar, 4 kembar (bom), seri, 3 kembar + pasangan, 4 kembar + pasangan, seri + pasangan, dan 8 kartu kembar (bom x2).",
    "Seri harus satu logo semua (minimal 3 kartu, cuma angka 3 sampai 10). Seri 5 kartu satu logo juga bom.",
    "Kombinasi + pasangan: cuma bagian utamanya (3 kembar / seri / 4 kembar) yang dibandingkan, pasangannya bebas asal kembar.",
    "Bom yang menimpa kartu 2 = langsung menang. 2 tunggal bisa dipotong 4 kembar / seri 5 / bom x2. 2 kembar cuma bisa dipotong 8 kartu kembar.",
    "Habis kartu pakai 2 = ngebom diri sendiri, kalah, game diulang dari kumpulin 3.",
    "Kartu murni (tanpa J, Q, K) boleh berhenti = langsung jadi juara, game tetap lanjut buat rebutan juara sisanya.",
    "Game wajib-3: semua pemegang angka 3 mengeluarkan semua kartu 3-nya sekaligus di awal. Setelah itu pemegang 3 terendah jalan duluan.",
    "Dapat 4 kartu 2 di tangan awal = kalah instan, game diulang.",
    "Giliran bergerak ke arah kanan (pemain berikutnya ada di paling kanan atas)."
)

class GameView(ctx: Context) : View(ctx) {
    private enum class Screen { HOME, GAME, LOBBY, RULES, TUTMENU, TUTSLIDE, GUIDE }

    private class Hit(val r: RectF, val id: String, val idx: Int)

    private val dp = resources.displayMetrics.density
    private val handler = Handler(Looper.getMainLooper())
    private val prefs = ctx.getSharedPreferences("k13", Context.MODE_PRIVATE)
    private val snd = Snd(ctx)
    private val firebase by lazy { FirebaseRoom(ctx) }
    private val voice by lazy { VoiceChatManager(ctx, FirebaseRoom.database(ctx).reference, firebase.playerId) { status -> post { voiceStatus = status; invalidate() } } }
    private var voiceStatus = "Voice tidak aktif"
    private var waitingMicPermission = false
    private var pttHeld = false
    private var multiDialog: AlertDialog? = null
    private var roomState: FirebaseRoom.RoomState? = null
    private var multiplayer = false
    private var seatPids: List<String> = emptyList()
    private var seatNames: List<String> = NAMES.toList()
    private var roomMessage = ""
    private var lastLog = 0
    private var screen = Screen.HOME
    private var lv = prefs.getInt("lv", 1).coerceIn(0, 3)
    private var game: Game? = null
    private val sel = ArrayList<Int>()
    private var msg = ""
    private val hits = ArrayList<Hit>()
    private val cache = HashMap<Int, Bitmap?>()
    private var bgBmp: Bitmap? = null
    private var rulesScroll = 0f
    private var rulesMax = 0f

    // ---- tahap 2.6: tutorial (geser + terpandu)
    private var tutI = 0
    private var tutScroll = 0f
    private var tutMax = 0f
    private var gI = 0
    private val gSel = ArrayList<Int>()
    private var gHand = ArrayList<Int>()
    private var gOK = false
    private var gHint = false
    private var gMsg = ""
    private var gBad = false
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val tp = TextPaint(Paint.ANTI_ALIAS_FLAG)
    private val botTask = Runnable { botStep() }
    private var downX = 0f
    private var downY = 0f
    private var lastY = 0f
    private var moved = false

    // ---- tahap 2.5: animasi, atur kartu manual, getar, kartu besar
    private class Fly(val p: Int, val t0: Long, val n: Int) {
        val total = 340L + 240L + 50L * (n - 1) + 40L
    }

    private var now = 0L
    private var lastT = 0L
    private var frameDt = 16L
    private var anim = false
    private var slow = false
    private var fly: Fly? = null
    private var dealT0 = 0L
    private var boomT0 = 0L
    private var arnT0 = 0L
    private var arnDone = false
    private var buzzKey = ""
    private var order = ArrayList<Int>()
    private val selFrac = HashMap<Int, Float>()
    private val cardRects = HashMap<Int, RectF>()
    private val seatR = arrayOfNulls<RectF>(4)
    private var handX0 = 0f
    private var handStep = 0f
    private var handTopY = 0f
    private var handBottomY = 0f
    private var tblCx = 0f
    private var tblCy = 0f
    private var big = prefs.getBoolean("big", false)
    private var dragging = false
    private var dragId = -1
    private var dragX = 0f
    private var dragY = 0f
    private var dragDX = 0f
    private var dragDY = 0f
    private var downCard: Hit? = null
    private val lpTask = Runnable { startDrag() }

    init {
        fitsSystemWindows = true
    }

    private fun d(v: Float): Float = v * dp

    // ---------------------------------------------------------------- alur game
    private fun startGame(lead: Int?) {
        multiplayer = false
        seatNames = NAMES.toList()
        handler.removeCallbacks(botTask)
        game = deal(listOf(0, 1, 2, 3), lead)
        lastLog = game?.log?.size ?: 0
        order = ArrayList()
        selFrac.clear()
        fly = null
        boomT0 = 0L
        endDragState()
        dealT0 = tnow()
        arnT0 = 0L
        arnDone = false
        buzzKey = ""
        sel.clear()
        msg = ""
        screen = Screen.GAME
        afterMove()
    }

    private fun nextLead(): Int? {
        val g = game ?: return null
        return if (g.bomb || g.winner == null) null else g.fin.firstOrNull()
    }

    private fun afterMove() {
        val g = game
        if (g != null) {
            playSfx(g)
            turnBuzz(g)
            sel.retainAll(g.hands[0].toSet())
            if (g.first && !g.over && g.dec.isEmpty() && g.order[g.turn] == 0 && !g.fin.contains(0)) {
                sel.clear()
                sel.addAll(g.hands[0].filter { rk(it) == 0 })
            }
        }
        invalidate()
        scheduleBot()
    }

    private fun scheduleBot() {
        handler.removeCallbacks(botTask)
        if (multiplayer) return
        val g = game ?: return
        if (g.over || screen != Screen.GAME) return
        if (g.dec.isNotEmpty()) {
            if (g.dec.any { it != 0 }) handler.postDelayed(botTask, 600L)
            return
        }
        val cur = g.order[g.turn]
        if (cur == 0 || g.fin.contains(cur)) return
        handler.postDelayed(botTask, 800L + Random.nextLong(700L))
    }

    private fun botStep() {
        val g = game ?: return
        if (g.over || screen != Screen.GAME) return
        val bd = g.dec.firstOrNull { it != 0 }
        if (bd != null) {
            decide(g, bd, botDecide(lv))
            afterMove()
            return
        }
        if (g.dec.isNotEmpty()) return
        val cur = g.order[g.turn]
        if (cur == 0 || g.fin.contains(cur)) return
        val m = botMove(g, cur, lv)
        var ok = move(g, cur, m.cards ?: emptyList(), m.pass)
        if (!ok) ok = move(g, cur, emptyList(), true)
        if (!ok) ok = move(g, cur, listOf(g.hands[cur].minOrNull() ?: 0), false)
        afterMove()
    }

    private fun humanPlay(pass: Boolean) {
        val g = game ?: return
        if (g.over || g.order[g.turn] != 0 || g.dec.isNotEmpty()) return
        val cards = sel.toList()
        if (!pass) {
            if (cards.isEmpty()) { msg = "Pilih kartu dulu."; invalidate(); return }
            val w = why(g, 0, cards)
            if (w.isNotEmpty()) { msg = w; invalidate(); return }
        }
        if (multiplayer) {
            msg = "Mengirim langkah..."
            invalidate()
            firebase.submitMove(if (pass) emptyList() else cards, pass) { result ->
                if (result.isSuccess) { msg = ""; sel.clear() }
                else msg = result.exceptionOrNull()?.message ?: "Gagal mengirim langkah."
                invalidate()
            }
            return
        }
        val ok = move(g, 0, if (pass) emptyList() else cards, pass)
        if (!ok) {
            msg = if (pass) "Tidak bisa lewat sekarang." else "Kombinasi tidak valid / tidak cukup tinggi."
            return
        }
        msg = ""
        sel.clear()
        afterMove()
    }

    private fun tnow(): Long = SystemClock.uptimeMillis()

    // efek suara + animasi buat kartu yang baru keluar (log bisa nambah >1 entri, mis. mainkan + "juara")
    private fun playSfx(g: Game) {
        val ln = g.log.size
        if (ln > lastLog) {
            var e: LogE? = null
            for (i in lastLog until ln) {
                val x = g.log[i]
                if (x.c != null || x.x) e = x
            }
            if ((g.bomb || g.self2 != null) && g.over) {
                snd.boom()
                boomT0 = tnow()
            } else if (e != null) {
                val cs = e.c
                if (cs != null) snd.play(cs.size) else snd.pass()
            }
            if (e != null) {
                val ec = e.c
                if (ec != null) fly = Fly(e.p, tnow(), ec.size)
            }
        }
        lastLog = ln
    }

    // getar tipis sekali tiap giliran kamu (nggak ada timer, nggak jalan otomatis)
    private fun turnBuzz(g: Game) {
        if (g.over || g.dec.isNotEmpty() || g.order[g.turn] != 0 || g.fin.contains(0)) { buzzKey = ""; return }
        val key = g.log.size.toString() + ":" + g.turn
        if (key == buzzKey) return
        buzzKey = key
        buzz(25L)
    }

    @Suppress("DEPRECATION")
    private fun buzz(ms: Long) {
        try {
            val v = context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator ?: return
            if (Build.VERSION.SDK_INT >= 26) v.vibrate(VibrationEffect.createOneShot(ms, VibrationEffect.DEFAULT_AMPLITUDE))
            else v.vibrate(ms)
        } catch (_: Throwable) {
        }
    }

    // urutan tangan: dipertahankan; kartu baru dibagi = diacak seperti dibagiin dealer
    private fun syncOrder(hs: List<Int>): List<Int> {
        val set = hs.toSet()
        val k = ArrayList<Int>()
        for (id in order) if (set.contains(id) && !k.contains(id)) k.add(id)
        order = if (k.size < hs.size) ArrayList(hs.shuffled()) else k
        return order
    }

    private fun startDrag() {
        val h = downCard ?: return
        val r = cardRects[h.idx] ?: return
        dragging = true
        dragId = h.idx
        dragX = downX
        dragY = downY
        dragDX = downX - r.left
        dragDY = downY - r.top
        buzz(12L)
        invalidate()
    }

    private fun endDragState() {
        dragging = false
        dragId = -1
        downCard = null
        handler.removeCallbacks(lpTask)
    }

    private fun dragReorder() {
        val n = order.size
        val from = order.indexOf(dragId)
        if (from < 0 || n < 2 || handStep <= 0f) return
        if (dragY < handTopY - d(110f) || dragY > handBottomY + d(60f)) return
        val to = ((dragX - handX0) / handStep).toInt().coerceIn(0, n - 1)
        if (to != from) {
            order.removeAt(from)
            order.add(to, dragId)
        }
    }

    fun onResume() { snd.resume(); firebase.setAppVisible(true) }

    fun onPause() { snd.pause(); firebase.setAppVisible(false) }

    override fun onWindowVisibilityChanged(visibility: Int) {
        super.onWindowVisibilityChanged(visibility)
        if (visibility == View.VISIBLE) firebase.setAppVisible(true) else firebase.setAppVisible(false)
    }

    fun stop() {
        handler.removeCallbacksAndMessages(null)
        voice.leave()
        if (multiplayer || roomState != null) firebase.stopListening()
        snd.release()
    }

    fun onBack(): Boolean {
        if (screen == Screen.HOME) return false
        handler.removeCallbacks(botTask)
        if (screen == Screen.TUTSLIDE || screen == Screen.GUIDE) screen = Screen.TUTMENU
        else {
            voice.leave()
            if (screen == Screen.LOBBY || multiplayer) firebase.leave()
            multiplayer = false; roomState = null; game = null; screen = Screen.HOME
        }
        tutScroll = 0f
        invalidate()
        return true
    }

    // ---------------------------------------------------------------- sentuhan
    private fun handleHit(h: Hit) {
        when (h.id) {
            "lv" -> { lv = h.idx; prefs.edit().putInt("lv", lv).apply() }
            "start" -> startGame(null)
            "multi" -> openMultiplayerDialog()
            "chat" -> showChatDialog()
            "react" -> showReactionDialog()
            "voice" -> toggleVoice()
            "vptt" -> voice.setPtt(!voice.isPtt())
            "vleave" -> voice.leave()
            "lobbyStart" -> roomState?.let { firebase.startGame(it) { r -> if (r.isFailure) { roomMessage = r.exceptionOrNull()?.message ?: "Gagal mulai"; invalidate() } } }
            "lobbyExit" -> { voice.leave(); firebase.leave(); roomState = null; multiplayer = false; game = null; screen = Screen.HOME }
            "addBot" -> roomState?.let { st -> firebase.addBot(st.botLevel, st) { r -> if (r.isFailure) { roomMessage = r.exceptionOrNull()?.message ?: "Gagal tambah bot"; invalidate() } } }
            "botLevel" -> roomState?.let { st -> firebase.setBotLevel((st.botLevel + 1) % 4, st) { r -> if (r.isFailure) { roomMessage = r.exceptionOrNull()?.message ?: "Gagal ubah level"; invalidate() } } }
            "rmBot" -> roomState?.players?.getOrNull(h.idx)?.let { p -> roomState?.let { st -> firebase.removeBot(p.id, st) } }
            "rules" -> { screen = Screen.RULES; rulesScroll = 0f }
            "tut" -> screen = Screen.TUTMENU
            "ts" -> { tutI = 0; tutScroll = 0f; screen = Screen.TUTSLIDE }
            "tg" -> { gI = 0; gStep(); screen = Screen.GUIDE }
            "tprev" -> { tutI = max(0, tutI - 1); tutScroll = 0f }
            "tnext" -> { tutI = min(TUT.size - 1, tutI + 1); tutScroll = 0f }
            "tclose" -> { screen = Screen.HOME; tutScroll = 0f }
            "tprac" -> { lv = 0; startGame(null) }
            "gc" -> if (!gOK) {
                val up = !gSel.contains(h.idx)
                snd.tick(up)
                if (up) gSel.add(h.idx) else gSel.remove(h.idx)
                gMsg = ""
            }
            "gk" -> gPlay(GUIDE[gI])
            "gp" -> {
                val d0 = GUIDE[gI]
                if (d0.pass) gWin(d0) else gSay("Di latihan ini kamu punya kartu yang bisa keluar. Coba pilih kartunya dulu.", true)
            }
            "gi" -> {
                gHint = true
                gSay(if (GUIDE[gI].pass) "Petunjuk: di sini kamu harus tekan Lewat." else "Petunjuk: kartu yang bercahaya hijau. Pilih, lalu Mainkan.", false)
            }
            "gn" -> { gI = min(GUIDE.size - 1, gI + 1); gStep() }
            "gm" -> { lv = 0; startGame(null) }
            "back", "menu" -> { handler.removeCallbacks(botTask); voice.leave(); if (multiplayer) firebase.leave(); multiplayer = false; roomState = null; game = null; screen = Screen.HOME }
            "again" -> if (multiplayer) roomState?.let { st -> firebase.startGame(st) { r -> if (r.isFailure) { msg = r.exceptionOrNull()?.message ?: "Gagal mulai ulang"; invalidate() } } } else startGame(nextLead())
            "card" -> {
                msg = ""
                val up = !sel.contains(h.idx)
                snd.tick(up)
                if (up) sel.add(h.idx) else sel.remove(h.idx)
            }
            "group" -> { order = ArrayList(groupHand(order)); arnDone = true }
            "unsel" -> { sel.clear(); msg = "" }
            "arnx" -> arnDone = true
            "arnbox" -> { }
            "big" -> { big = !big; prefs.edit().putBoolean("big", big).apply() }
            "music" -> snd.setMusic(!snd.musicOn)
            "sfx" -> snd.setSfx(!snd.sfxOn)
            "play" -> humanPlay(false)
            "pass" -> humanPlay(true)
            "stopY" -> { if (multiplayer) firebase.submitDecision(true) { r -> if (r.isFailure) msg = r.exceptionOrNull()?.message ?: "Gagal mengirim pilihan"; invalidate() } else { game?.let { decide(it, 0, true) }; afterMove() } }
            "stopN" -> { if (multiplayer) firebase.submitDecision(false) { r -> if (r.isFailure) msg = r.exceptionOrNull()?.message ?: "Gagal mengirim pilihan"; invalidate() } else { game?.let { decide(it, 0, false) }; afterMove() } }
        }
        invalidate()
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = e.x; downY = e.y; lastY = e.y; moved = false
                handler.removeCallbacks(lpTask)
                downCard = null
                if (screen == Screen.GAME && order.size > 1 && !dragging) {
                    downCard = hits.lastOrNull { it.id == "card" && it.r.contains(e.x, e.y) }
                    if (downCard != null) handler.postDelayed(lpTask, 260L) // tahan sebentar = mulai nyeret kartu
                }
                if (screen == Screen.LOBBY || (screen == Screen.GAME && multiplayer)) { // tahan-ngomong: tekan tombol mic = bicara
                    if (voice.isJoined() && voice.isPtt() && hits.any { it.id == "voice" && it.r.contains(e.x, e.y) }) {
                        pttHeld = true
                        voice.setMuted(false)
                        invalidate()
                    }
                }
            }
            MotionEvent.ACTION_MOVE -> {
                if (dragging) {
                    dragX = e.x
                    dragY = e.y
                    dragReorder()
                    invalidate()
                    return true
                }
                if (screen == Screen.RULES) {
                    rulesScroll = (rulesScroll - (e.y - lastY)).coerceIn(0f, max(0f, rulesMax))
                    lastY = e.y
                    invalidate()
                } else if (screen == Screen.TUTSLIDE || screen == Screen.GUIDE) {
                    tutScroll = (tutScroll - (e.y - lastY)).coerceIn(0f, max(0f, tutMax))
                    lastY = e.y
                    invalidate()
                }
                if (abs(e.x - downX) > d(12f) || abs(e.y - downY) > d(12f)) {
                    moved = true
                    handler.removeCallbacks(lpTask)
                }
            }
            MotionEvent.ACTION_UP -> {
                handler.removeCallbacks(lpTask)
                if (pttHeld) { pttHeld = false; voice.setMuted(true); invalidate() }
                if (dragging) {
                    endDragState()
                    arnDone = true
                    invalidate()
                    return true
                }
                if (screen == Screen.TUTSLIDE) { // geser kiri / kanan ganti halaman
                    val dx = e.x - downX
                    val dy = e.y - downY
                    if (abs(dx) > d(70f) && abs(dx) > abs(dy) * 1.5f) {
                        tutI = (tutI + (if (dx < 0) 1 else -1)).coerceIn(0, TUT.size - 1)
                        tutScroll = 0f
                        invalidate()
                        return true
                    }
                }
                if (!moved) {
                    for (h in hits.reversed()) {
                        if (h.r.contains(e.x, e.y)) { handleHit(h); break }
                    }
                }
            }
            MotionEvent.ACTION_CANCEL -> {
                handler.removeCallbacks(lpTask)
                if (pttHeld) { pttHeld = false; voice.setMuted(true); invalidate() }
                if (dragging) {
                    endDragState()
                    invalidate()
                }
            }
        }
        return true
    }

    // ---------------------------------------------------------------- gambar dasar
    // latar: gradasi hijau (digelapkan) + pola logo & bingkai dari cover SVG (res/drawable/bg_cover_*.xml).
    // Digambar sekali ke bitmap supaya onDraw tetap ringan.
    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        bgBmp = null
        if (w <= 0 || h <= 0) return
        try {
            val bm = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            val cv = Canvas(bm)
            val p = Paint()
            p.shader = RadialGradient(
                w * 0.5f, h * 0.4f, max(w, h) * 0.75f,
                intArrayOf(Color.parseColor("#154534"), Color.parseColor("#0c2b22"), Color.parseColor("#030c09")),
                floatArrayOf(0f, 0.45f, 1f), Shader.TileMode.CLAMP
            )
            cv.drawRect(0f, 0f, w.toFloat(), h.toFloat(), p)
            val dr = context.getDrawable(if (h >= w) R.drawable.bg_cover_portrait else R.drawable.bg_cover_landscape)
            if (dr != null) {
                val vw = if (h >= w) 600f else 1900f
                val vh = if (h >= w) 1900f else 600f
                val k = max(w / vw, h / vh) // "slice": menutup penuh, bagian lebih dipotong
                cv.save()
                cv.translate((w - vw * k) / 2f, (h - vh * k) / 2f)
                cv.scale(k, k)
                dr.setBounds(0, 0, vw.toInt(), vh.toInt())
                dr.draw(cv)
                cv.restore()
            }
            val v = Paint()
            v.shader = RadialGradient(
                w * 0.5f, h * 0.5f, max(w, h) * 0.72f,
                intArrayOf(Color.TRANSPARENT, Color.argb(140, 0, 0, 0)),
                floatArrayOf(0.55f, 1f), Shader.TileMode.CLAMP
            )
            cv.drawRect(0f, 0f, w.toFloat(), h.toFloat(), v)
            bgBmp = bm
        } catch (_: Throwable) {
            bgBmp = null
        }
    }

    private fun bmp(id: Int): Bitmap? {
        if (cache.containsKey(id)) return cache[id]
        val name = if (id < 0) "back" else id.toString()
        val b: Bitmap? = try {
            context.assets.open("cards/$name.webp").use { BitmapFactory.decodeStream(it) }
        } catch (e: Exception) {
            null
        }
        cache[id] = b
        return b
    }

    private fun fade(col: Int, a: Int): Int = Color.argb(a, Color.red(col), Color.green(col), Color.blue(col))

    private fun txt(c: Canvas, s: String, x: Float, y: Float, size: Float, color: Int,
                    align: Paint.Align = Paint.Align.CENTER, bold: Boolean = false) {
        tp.reset()
        tp.isAntiAlias = true
        tp.textSize = size
        tp.color = color
        tp.textAlign = align
        tp.typeface = if (bold) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
        c.drawText(s, x, y, tp)
    }

    // paint khusus layout: rata teks selalu KIRI di paint (rata tengah/kanan diurus StaticLayout),
    // supaya Paint.Align.CENTER dari txt() nggak bocor dan menggeser tiap baris
    private val lp = TextPaint(Paint.ANTI_ALIAS_FLAG)

    private fun para(c: Canvas, s: CharSequence, x: Float, y: Float, width: Int, size: Float, color: Int,
                     align: Layout.Alignment, dr: Boolean = true, spacing: Float = 1.1f): Float {
        lp.reset()
        lp.isAntiAlias = true
        lp.textAlign = Paint.Align.LEFT
        lp.textSize = size
        lp.color = color
        val sl = StaticLayout.Builder.obtain(s, 0, s.length, lp, max(1, width))
            .setAlignment(align).setLineSpacing(0f, spacing).build()
        if (dr) {
            c.save()
            c.translate(x, y)
            sl.draw(c)
            c.restore()
        }
        return sl.height.toFloat()
    }

    private fun box(c: Canvas, r: RectF, fill: Int, stroke: Int, sw: Float, rad: Float) {
        paint.style = Paint.Style.FILL
        paint.color = fill
        c.drawRoundRect(r, rad, rad, paint)
        if (stroke != 0) {
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = sw
            paint.color = stroke
            c.drawRoundRect(r, rad, rad, paint)
        }
        paint.style = Paint.Style.FILL
    }

    private fun button(c: Canvas, r: RectF, label: String, id: String, enabled: Boolean, ghost: Boolean, idx: Int = -1, fs: Float = 15f) {
        val a = if (enabled) 255 else 90
        if (ghost) {
            box(c, r, 0, fade(GOLD, a), d(1.5f), d(10f))
            txt(c, label, r.centerX(), r.centerY() + d(fs * 0.37f), d(fs), fade(GOLD, a), Paint.Align.CENTER, true)
        } else {
            box(c, r, fade(GOLD, a), 0, 0f, d(10f))
            txt(c, label, r.centerX(), r.centerY() + d(fs * 0.37f), d(fs), fade(DARK, a), Paint.Align.CENTER, true)
        }
        if (enabled) hits.add(Hit(r, id, idx))
    }

    // cincin hijau di sekeliling elemen pemain yang lagi ngomong
    private fun speakRing(c: Canvas, r: RectF, rad: Float) {
        box(c, RectF(r.left - d(3f), r.top - d(3f), r.right + d(3f), r.bottom + d(3f)), 0, OKC, d(2.5f), rad + d(3f))
    }

    // baris voice: status + tombol mode (open mic / tahan) + keluar voice. Mengembalikan tinggi yang dipakai (0 kalau belum gabung voice)
    private fun drawVoiceBar(c: Canvas, x0: Float, x1: Float, y: Float): Float {
        if (!voice.isJoined()) return 0f
        val h = d(26f)
        val g = d(6f)
        val kr = RectF(x1 - d(54f), y, x1, y + h)
        val mr = RectF(kr.left - g - d(72f), y, kr.left - g, y + h)
        tp.reset()
        tp.isAntiAlias = true
        tp.textSize = d(9f)
        val shown = TextUtils.ellipsize(voiceStatus, tp, max(0f, mr.left - g - x0), TextUtils.TruncateAt.END).toString()
        txt(c, shown, x0, y + h / 2 + d(3f), d(9f), OKC, Paint.Align.LEFT)
        button(c, mr, if (voice.isPtt()) "TAHAN" else "OPEN MIC", "vptt", true, true, -1, 9f)
        button(c, kr, "KELUAR", "vleave", true, true, -1, 9f)
        return h
    }

    private fun drawCard(c: Canvas, id: Int, r: RectF) {
        val rad = r.width() * 0.055f
        paint.style = Paint.Style.FILL
        paint.color = Color.WHITE
        c.drawRoundRect(r, rad, rad, paint)
        val b = bmp(id)
        if (b != null) {
            c.drawBitmap(b, null, r, paint)
        } else {
            txt(c, if (id >= 0) RANKS[rk(id)] else "?", r.centerX(), r.centerY(), r.width() * 0.4f, Color.BLACK)
        }
    }

    // ---------------------------------------------------------------- layar
    override fun onDraw(c: Canvas) {
        hits.clear()
        now = SystemClock.uptimeMillis()
        frameDt = min(50L, max(1L, now - lastT))
        lastT = now
        anim = false
        slow = false
        val bb = bgBmp
        if (bb != null) c.drawBitmap(bb, 0f, 0f, null) else c.drawColor(Color.parseColor("#0c2b22"))
        val l = paddingLeft.toFloat()
        val t = paddingTop.toFloat()
        val w = (width - paddingLeft - paddingRight).toFloat()
        val b = (height - paddingBottom).toFloat()
        when (screen) {
            Screen.HOME -> drawHome(c, l, t, w, b)
            Screen.LOBBY -> drawLobby(c, l, t, w, b)
            Screen.GAME -> {
                val bt = now - boomT0
                if (boomT0 > 0L && bt < 420L) { // layar goyang sebentar pas bom
                    val k = 1f - bt / 420f
                    c.save()
                    c.translate(sin(bt / 22.0).toFloat() * d(6f) * k, sin(bt / 31.0).toFloat() * d(3f) * k)
                    drawGame(c, l, t, w, b)
                    c.restore()
                } else {
                    drawGame(c, l, t, w, b)
                }
                drawBoomFx(c, w, b)
            }
            Screen.RULES -> drawRules(c, l, t, w, b)
            Screen.TUTMENU -> drawTutMenu(c, l, t, w, b)
            Screen.TUTSLIDE -> drawTutSlide(c, l, t, w, b)
            Screen.GUIDE -> drawGuide(c, l, t, w, b)
        }
        if (anim) postInvalidateOnAnimation() else if (slow) postInvalidateDelayed(250L)
    }

    private fun drawBoomFx(c: Canvas, w: Float, b: Float) {
        if (boomT0 <= 0L) return
        val el = now - boomT0
        if (el > 900L) return
        anim = true
        if (el < 350L) {
            val a = ((1f - el / 350f) * 90f).toInt().coerceIn(0, 255)
            paint.style = Paint.Style.FILL
            paint.color = Color.argb(a, 255, 240, 200)
            c.drawRect(0f, 0f, width.toFloat(), height.toFloat(), paint)
        }
        if (el < 700L) {
            val k = el / 700f
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = d(7f) * (1f - k) + d(1f)
            paint.color = fade(GOLD, ((1f - k) * 255f).toInt().coerceIn(0, 255))
            c.drawCircle(tblCx, tblCy, k * max(w, b) * 0.55f, paint)
            paint.style = Paint.Style.FILL
        }
    }

    private fun versionName(): String {
        return try {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: ""
        } catch (e: Exception) {
            ""
        }
    }

    private fun drawHome(c: Canvas, l: Float, t: Float, w: Float, b: Float) {
        val cx = l + w / 2
        val h = b - t
        val m = d(20f)
        val cw = min(w * 0.22f, d(96f))
        val chh = cw / CARD_RATIO
        val fanY = t + h * 0.07f
        val ids = intArrayOf(44, 47, 51)
        val angs = floatArrayOf(-14f, 0f, 14f)
        for (i in ids.indices) {
            val px = cx + (i - 1) * cw * 0.7f
            c.save()
            c.rotate(angs[i], px, fanY + chh)
            drawCard(c, ids[i], RectF(px - cw / 2, fanY, px + cw / 2, fanY + chh))
            c.restore()
        }
        var y = fanY + chh + d(44f)
        txt(c, "Kartu 13", cx, y, d(40f), GOLD, Paint.Align.CENTER, true)
        y += d(26f)
        txt(c, "Poker 13 · offline & multiplayer", cx, y, d(15f), MUTE)
        y += d(40f)
        txt(c, "Level bot", cx, y, d(14f), INK)
        y += d(12f)
        val gap = d(8f)
        val bw = (w - 2 * m - 3 * gap) / 4
        for (i in 0..3) {
            val x0 = l + m + i * (bw + gap)
            button(c, RectF(x0, y, x0 + bw, y + d(42f)), LEVELS[i], "lv", true, i != lv, i)
        }
        y += d(42f) + d(28f)
        val ww = min(w - 2 * m, d(340f))
        button(c, RectF(cx - ww / 2, y, cx + ww / 2, y + d(52f)), "Main lawan bot", "start", true, false)
        y += d(52f) + d(8f)
        button(c, RectF(cx - ww / 2, y, cx + ww / 2, y + d(46f)), "Multiplayer online", "multi", true, true)
        y += d(46f) + d(8f)
        button(c, RectF(cx - ww / 2, y, cx + ww / 2, y + d(44f)), "Tutorial", "tut", true, true)
        y += d(44f) + d(8f)
        val tg = d(6f)
        val tw = (ww - 2 * tg) / 3
        val x0 = cx - ww / 2
        button(c, RectF(x0, y, x0 + tw, y + d(40f)), "Musik: " + (if (snd.musicOn) "nyala" else "mati"), "music", true, !snd.musicOn, -1, 12f)
        button(c, RectF(x0 + tw + tg, y, x0 + 2 * tw + tg, y + d(40f)), "Suara: " + (if (snd.sfxOn) "nyala" else "mati"), "sfx", true, !snd.sfxOn, -1, 12f)
        button(c, RectF(x0 + 2 * (tw + tg), y, x0 + ww, y + d(40f)), "Kartu: " + (if (big) "besar" else "normal"), "big", true, !big, -1, 12f)
        val v = versionName()
        if (roomMessage.isNotEmpty()) txt(c, roomMessage, cx, b - d(56f), d(11f), WARN, Paint.Align.CENTER)
        txt(c, "© 2026 Poker 13" + (if (v.isNotEmpty()) " · v$v" else ""), cx, b - d(40f), d(11f), MUTE)
        txt(c, "AI-assisted by Claude · Card art: Vecteezy.com", cx, b - d(24f), d(11f), MUTE)
    }

    private fun playerName(seat: Int): String = seatNames.getOrNull(seat) ?: NAMES.getOrElse(seat) { "Pemain" }

    private fun openMultiplayerDialog() {
        roomMessage = ""
        val pad = d(20f).toInt()
        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, d(20f).toInt(), pad, d(16f).toInt())
            background = GradientDrawable().apply {
                setColor(Color.rgb(3, 40, 29))
                cornerRadius = d(20f)
                setStroke(d(1f).toInt(), Color.argb(150, 224, 180, 76))
            }
        }
        val title = TextView(context).apply {
            text = "MULTIPLAYER"
            textSize = 21f
            setTextColor(GOLD)
            typeface = Typeface.create("sans-serif", Typeface.BOLD)
            letterSpacing = .06f
            gravity = Gravity.CENTER
        }
        root.addView(title, LinearLayout.LayoutParams(-1, -2))
        val subtitle = TextView(context).apply {
            text = "Buat meja baru atau bergabung dengan teman."
            textSize = 14f
            setTextColor(INK)
            gravity = Gravity.CENTER
            setLineSpacing(d(3f), 1f)
            setPadding(0, d(7f).toInt(), 0, d(17f).toInt())
        }
        root.addView(subtitle, LinearLayout.LayoutParams(-1, -2))
        val label = TextView(context).apply {
            text = "NAMA PEMAIN"
            textSize = 10f
            setTextColor(MUTE)
            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
            letterSpacing = .1f
        }
        root.addView(label)
        val nameInput = EditText(context).apply {
            hint = "Masukkan nama (maks. 14 karakter)"
            setText(firebase.savedName())
            filters = arrayOf(InputFilter.LengthFilter(14))
            setSingleLine(true)
            textSize = 16f
            setTextColor(INK)
            setHintTextColor(Color.rgb(119, 151, 137))
            background = GradientDrawable().apply {
                setColor(Color.rgb(5, 31, 24))
                cornerRadius = d(10f)
                setStroke(d(1f).toInt(), Color.argb(170, 224, 180, 76))
            }
            setPadding(d(12f).toInt(), d(10f).toInt(), d(12f).toInt(), d(10f).toInt())
        }
        val inputLp = LinearLayout.LayoutParams(-1, d(48f).toInt())
        inputLp.topMargin = d(7f).toInt()
        root.addView(nameInput, inputLp)

        fun actionButton(text: String, primary: Boolean, onClick: () -> Unit): TextView {
            return TextView(context).apply {
                this.text = text
                textSize = 12f
                typeface = Typeface.create("sans-serif", Typeface.BOLD)
                gravity = Gravity.CENTER
                setTextColor(if (primary) Color.rgb(35, 27, 8) else GOLD)
                background = GradientDrawable(
                    if (primary) GradientDrawable.Orientation.TOP_BOTTOM else GradientDrawable.Orientation.LEFT_RIGHT,
                    if (primary) intArrayOf(Color.rgb(244, 212, 123), Color.rgb(193, 139, 45)) else intArrayOf(Color.rgb(10, 52, 39), Color.rgb(7, 38, 30))
                ).apply {
                    cornerRadius = d(11f)
                    setStroke(d(1f).toInt(), if (primary) Color.argb(150, 255, 239, 183) else Color.argb(210, 224, 180, 76))
                }
                isClickable = true
                isFocusable = true
                setPadding(d(5f).toInt(), 0, d(5f).toInt(), 0)
                setOnClickListener { onClick() }
            }
        }
        val buttons = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }
        fun buttonLp(weight: Float, margin: Int = 0) = LinearLayout.LayoutParams(0, d(46f).toInt(), weight).apply {
            if (margin > 0) marginStart = margin
        }
        var multiplayerDialog: AlertDialog? = null
        val join = actionButton("GABUNG", false) {
            val n = nameInput.text.toString().trim()
            if (n.isEmpty()) { roomMessage = "Nama pemain wajib diisi."; invalidate(); return@actionButton }
            showJoinRoomDialog(n)
        }
        val cancel = actionButton("BATAL", false) { /* click handler attached after dialog creation */ }
        val create = actionButton("BUAT ROOM", true) {
            val n = nameInput.text.toString().trim()
            if (n.isEmpty()) { roomMessage = "Nama pemain wajib diisi."; invalidate(); return@actionButton }
            roomMessage = "Membuat room..."; invalidate()
            firebase.createRoom(n) { result ->
                if (result.isSuccess) {
                    // Tutup dialog Multiplayer setelah room berhasil dibuat.
                    multiplayerDialog?.dismiss()
                    enterRoom(result.getOrThrow())
                } else {
                    roomMessage = result.exceptionOrNull()?.message ?: "Gagal membuat room."
                    invalidate()
                }
            }
        }
        val gap = d(7f).toInt()
        buttons.addView(join, buttonLp(1f))
        buttons.addView(cancel, buttonLp(.78f, gap))
        buttons.addView(create, buttonLp(1.18f, gap))
        val buttonsLp = LinearLayout.LayoutParams(-1, d(46f).toInt())
        buttonsLp.topMargin = d(18f).toInt()
        root.addView(buttons, buttonsLp)

        val dialog = AlertDialog.Builder(context).setView(root).create()
        multiplayerDialog = dialog
        multiDialog = dialog
        dialog.setOnDismissListener { if (multiDialog === dialog) multiDialog = null }
        cancel.setOnClickListener { dialog.dismiss() }
        dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        dialog.setOnShowListener {
            dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            dialog.window?.setLayout((resources.displayMetrics.widthPixels * .94f).toInt().coerceAtLeast(d(280f).toInt()), -2)
        }
        dialog.show()
        dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        dialog.window?.setLayout((resources.displayMetrics.widthPixels * .94f).toInt().coerceAtLeast(d(280f).toInt()), -2)
    }

    private fun showJoinRoomDialog(name: String) {
        val pad = d(20f).toInt()
        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, d(20f).toInt(), pad, d(16f).toInt())
            background = GradientDrawable().apply {
                setColor(Color.rgb(3, 40, 29))
                cornerRadius = d(18f)
                setStroke(d(1f).toInt(), Color.argb(150, 224, 180, 76))
            }
        }
        root.addView(TextView(context).apply {
            text = "GABUNG ROOM"
            textSize = 20f
            setTextColor(GOLD)
            typeface = Typeface.create("sans-serif", Typeface.BOLD)
            gravity = Gravity.CENTER
        })
        root.addView(TextView(context).apply {
            text = "Masukkan kode 5 angka dari temanmu."
            textSize = 14f
            setTextColor(INK)
            gravity = Gravity.CENTER
            setPadding(0, d(7f).toInt(), 0, d(16f).toInt())
        })
        val codeInput = EditText(context).apply {
            hint = "KODE ROOM"
            setText(firebase.currentCode ?: "")
            inputType = InputType.TYPE_CLASS_NUMBER
            filters = arrayOf(InputFilter.LengthFilter(5))
            setSingleLine(true)
            textSize = 20f
            gravity = Gravity.CENTER
            setTextColor(INK)
            setHintTextColor(Color.rgb(119, 151, 137))
            background = GradientDrawable().apply {
                setColor(Color.rgb(5, 31, 24)); cornerRadius = d(10f)
                setStroke(d(1f).toInt(), Color.argb(170, 224, 180, 76))
            }
            setPadding(d(12f).toInt(), d(10f).toInt(), d(12f).toInt(), d(10f).toInt())
        }
        root.addView(codeInput, LinearLayout.LayoutParams(-1, d(50f).toInt()))
        val dialog = AlertDialog.Builder(context).setView(root).create()
        val row = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER }
        fun smallButton(text: String, primary: Boolean, click: () -> Unit) = TextView(context).apply {
            this.text = text; textSize = 12f; gravity = Gravity.CENTER
            typeface = Typeface.create("sans-serif", Typeface.BOLD)
            setTextColor(if (primary) Color.rgb(35, 27, 8) else GOLD)
            background = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                if (primary) intArrayOf(Color.rgb(244, 212, 123), Color.rgb(193, 139, 45)) else intArrayOf(Color.rgb(10, 52, 39), Color.rgb(7, 38, 30))).apply {
                cornerRadius = d(10f); setStroke(d(1f).toInt(), Color.argb(190, 224, 180, 76))
            }
            setOnClickListener { click() }
        }
        val cancel = smallButton("BATAL", false) { dialog.dismiss() }
        val join = smallButton("GABUNG ROOM", true) {
            val code = codeInput.text.toString().trim()
            if (!code.matches(Regex("\\d{5}"))) { codeInput.error = "Kode harus 5 angka"; return@smallButton }
            roomMessage = "Menghubungkan..."; invalidate(); dialog.dismiss()
            firebase.joinRoom(code, name) { result ->
                if (result.isSuccess) enterRoom(result.getOrThrow())
                else { roomMessage = result.exceptionOrNull()?.message ?: "Gagal join room."; invalidate() }
            }
        }
        row.addView(cancel, LinearLayout.LayoutParams(0, d(44f).toInt(), 1f))
        row.addView(join, LinearLayout.LayoutParams(0, d(44f).toInt(), 1.5f).apply { marginStart = d(8f).toInt() })
        val lp = LinearLayout.LayoutParams(-1, d(44f).toInt()); lp.topMargin = d(16f).toInt()
        root.addView(row, lp)
        dialog.show()
        dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        dialog.window?.setLayout((resources.displayMetrics.widthPixels * .92f).toInt().coerceAtLeast(d(280f).toInt()), -2)
    }

    fun onMicPermissionResult(granted: Boolean) {
        waitingMicPermission = false
        if (granted) firebase.currentCode?.let { voice.join(it) }
        else { voiceStatus = "Izin mikrofon ditolak"; Toast.makeText(context, "Izin mikrofon diperlukan untuk voice chat.", Toast.LENGTH_SHORT).show() }
        invalidate()
    }

    private fun toggleVoice() {
        if (!voice.isJoined()) {
            val humans = roomState?.players?.count { !it.bot } ?: 2
            if (humans < 2) { // sama dengan web: voice butuh minimal 2 pemain asli
                Toast.makeText(context, "Voice butuh minimal 2 pemain asli di room.", Toast.LENGTH_SHORT).show()
                return
            }
            if (android.os.Build.VERSION.SDK_INT >= 23 && context.checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                waitingMicPermission = true
                (context as? android.app.Activity)?.let { (it as? MainActivity)?.requestMicPermission() }
                    ?: run { voiceStatus = "Tidak bisa meminta izin mikrofon" }
            } else firebase.currentCode?.let { voice.join(it) }
        } else if (!voice.isPtt()) voice.toggleMute() // mode tahan-ngomong diurus sentuhan tahan di onTouchEvent
        invalidate()
    }

    private fun showChatDialog() {
        val code = firebase.currentCode ?: return
        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(d(18f).toInt(), d(16f).toInt(), d(18f).toInt(), d(14f).toInt())
            background = GradientDrawable().apply { setColor(Color.rgb(4, 39, 29)); setStroke(d(1f).toInt(), GOLD); cornerRadius = d(18f) }
        }
        val title = TextView(context).apply { text = "CHAT ROOM"; textSize = 19f; setTextColor(GOLD); gravity = Gravity.CENTER; setTypeface(typeface, Typeface.BOLD) }
        root.addView(title, LinearLayout.LayoutParams(-1, d(34f).toInt()))
        val transcript = TextView(context).apply { text = "Memuat pesan…"; textSize = 14f; setTextColor(INK); setPadding(d(10f).toInt(), d(8f).toInt(), d(10f).toInt(), d(8f).toInt()) }
        val scroll = ScrollView(context).apply { isFillViewport = true; addView(transcript) }
        val scrollLp = LinearLayout.LayoutParams(-1, d(220f).toInt()).apply { topMargin = d(8f).toInt() }
        root.addView(scroll, scrollLp)
        val quick = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER }
        val quickTexts = listOf("Gas terus! 🔥", "GG! 👏", "Yah kasian 😅", "Kartu kamu jelek banget! 😂")
        val input = EditText(context).apply { hint = "Tulis pesan…"; setTextColor(INK); setHintTextColor(MUTE); textSize = 14f; filters = arrayOf(InputFilter.LengthFilter(140)); setSingleLine(true) }
        val send = TextView(context).apply { text = "KIRIM"; textSize = 13f; setTextColor(GOLD); gravity = Gravity.CENTER; setTypeface(typeface, Typeface.BOLD); setPadding(d(12f).toInt(), 0, d(12f).toInt(), 0) }
        val inputRow = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; addView(input, LinearLayout.LayoutParams(0, d(48f).toInt(), 1f)); addView(send, LinearLayout.LayoutParams(d(68f).toInt(), d(48f).toInt())) }
        root.addView(inputRow)
        quickTexts.forEach { message ->
            val chip = TextView(context).apply { text = message; textSize = 10f; setTextColor(MUTE); gravity = Gravity.CENTER; setPadding(d(4f).toInt(), d(7f).toInt(), d(4f).toInt(), d(7f).toInt()); setOnClickListener { firebase.sendChat(code, message) { r -> if (r.isFailure) roomMessage = r.exceptionOrNull()?.message ?: "Gagal kirim" } } }
            quick.addView(chip, LinearLayout.LayoutParams(0, -2, 1f))
        }
        root.addView(quick)
        val dialog = AlertDialog.Builder(context).setView(root).create()
        var lastKey = ""
        firebase.listenChat(code) { messages ->
            val fresh = messages.filter { it.key > lastKey && it.playerId != firebase.playerId }
            if (lastKey.isNotEmpty() && fresh.isNotEmpty()) snd.msg()
            lastKey = messages.lastOrNull()?.key ?: ""
            transcript.text = if (messages.isEmpty()) "Belum ada pesan. Sapa temanmu!" else messages.joinToString("\n") { "${it.name}: ${it.message}" }
            scroll.post { scroll.fullScroll(View.FOCUS_DOWN) }
        }
        send.setOnClickListener {
            val text = input.text?.toString().orEmpty()
            firebase.sendChat(code, text) { result ->
                if (result.isSuccess) input.setText("")
                else { input.error = result.exceptionOrNull()?.message ?: "Gagal mengirim" }
            }
        }
        dialog.setOnDismissListener { firebase.stopChatListener() }
        dialog.show()
        dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        dialog.window?.setLayout((resources.displayMetrics.widthPixels * .92f).toInt(), -2)
    }

    private fun showReactionDialog() {
        val code = firebase.currentCode ?: return
        val emojis = listOf("😂", "🔥", "👏", "😭", "😡", "🤡", "💀", "👀")
        val grid = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL; setPadding(d(16f).toInt(), d(12f).toInt(), d(16f).toInt(), d(12f).toInt()); background = GradientDrawable().apply { setColor(Color.rgb(4, 39, 29)); setStroke(d(1f).toInt(), GOLD); cornerRadius = d(16f) } }
        val title = TextView(context).apply { text = "REAKSI"; textSize = 17f; setTextColor(GOLD); gravity = Gravity.CENTER; setTypeface(typeface, Typeface.BOLD) }
        grid.addView(title, LinearLayout.LayoutParams(-1, d(34f).toInt()))
        var reactionDialog: AlertDialog? = null
        emojis.chunked(4).forEach { rowItems ->
            val row = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
            rowItems.forEach { emoji ->
                val b = TextView(context).apply { text = emoji; textSize = 28f; gravity = Gravity.CENTER; setOnClickListener { firebase.sendReaction(code, emoji) { result -> if (result.isFailure) Toast.makeText(context, result.exceptionOrNull()?.message ?: "Gagal mengirim reaksi", Toast.LENGTH_SHORT).show() }; reactionDialog?.dismiss() } }
                row.addView(b, LinearLayout.LayoutParams(0, d(54f).toInt(), 1f))
            }
            grid.addView(row)
        }
        reactionDialog = AlertDialog.Builder(context).setView(grid).create()
        reactionDialog?.show()
        reactionDialog?.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
    }

    // tutup dialog Multiplayer (jalur GABUNG membuka dialog kedua, jadi dialog pertama harus ditutup di sini)
    private fun dismissMultiDialog() {
        try { multiDialog?.dismiss() } catch (_: Throwable) {}
        multiDialog = null
    }

    private fun enterRoom(code: String) {
        dismissMultiDialog()
        firebase.listenReactions(code) { pid, name, emoji ->
            if (pid != firebase.playerId) {
                snd.msg()
                Toast.makeText(context, "$emoji  $name", Toast.LENGTH_SHORT).show()
            }
        }
        firebase.listen(code) { result ->
            result.onFailure {
                roomMessage = it.message ?: "Koneksi room terputus."
                if (screen == Screen.LOBBY) invalidate()
            }.onSuccess { state ->
                roomState = state
                roomMessage = ""
                dismissMultiDialog()
                if (state.status == "lobby") {
                    multiplayer = false
                    screen = Screen.LOBBY
                } else {
                    val raw = state.game
                    val decoded = raw?.let { GameWire.decode(it, firebase.playerId) }
                    if (decoded != null) {
                        val (remoteGame, ids) = decoded
                        val byId = state.players.associateBy { it.id }
                        if (screen != Screen.GAME || !multiplayer) lastLog = 0
                        game = remoteGame
                        seatPids = ids
                        seatNames = ids.mapIndexed { i, id -> if (i == 0) "Kamu" else byId[id]?.name ?: "Pemain $i" }
                        multiplayer = true
                        screen = Screen.GAME
                        sel.retainAll(remoteGame.hands[0].toSet())
                        playSfx(remoteGame)
                        turnBuzz(remoteGame)
                        firebase.runBotTurn(state)
                    } else {
                        roomMessage = "Versi game tidak cocok atau data room rusak."
                    }
                }
                invalidate()
            }
        }
    }

    // Indikator presence mengikuti palet status web v44: hijau, amber, merah, dan bot.
    private fun drawPresence(c: Canvas, x: Float, y: Float, status: FirebaseRoom.PlayerStatus, compact: Boolean = false) {
        val color = when (status.state) {
            "bot" -> GOLD
            "away" -> Color.parseColor("#e0b44c")
            "off" -> Color.parseColor("#ffb36b")
            "afk" -> BADC
            else -> OKC
        }
        val r = d(if (compact) 3.2f else 4f)
        paint.style = Paint.Style.FILL
        paint.color = color
        c.drawCircle(x, y, r, paint)
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = d(1f)
        paint.color = Color.argb(100, 255, 255, 255)
        c.drawCircle(x, y, r + d(1.4f), paint)
        paint.style = Paint.Style.FILL
        val label = when (status.state) {
            "bot" -> "BOT"
            "away" -> "AWAY"
            "off" -> "PUTUS"
            "afk" -> "AFK · BOT"
            else -> status.ping?.let { "${it} ms" } ?: "ONLINE"
        }
        txt(c, label, x + d(9f), y + d(3.5f), d(if (compact) 8f else 9f), color, Paint.Align.LEFT, true)
        if (status.state == "on" && status.ping != null) {
            val bars = when { status.ping < 120 -> 3; status.ping < 300 -> 2; else -> 1 }
            for (i in 0 until 3) {
                val bh = d(3f + i * 2.2f)
                paint.color = if (i < bars) color else Color.argb(55, 255, 255, 255)
                c.drawRoundRect(x - d(34f) + d(i * 4.5f), y + d(4f) - bh, x - d(31f) + d(i * 4.5f), y + d(4f), d(1f), d(1f), paint)
            }
        }
    }

    private fun drawLobby(c: Canvas, l: Float, t: Float, w: Float, b: Float) {
        val state = roomState ?: return
        val cx = l + w / 2
        val m = d(16f)
        val contentW = w - 2 * m

        // Header bergaya meja kartu: hierarki kode room lebih kuat dan punya affordance salin.
        txt(c, "♠  MEJA MULTIPLAYER  ♥", cx, t + d(27f), d(11f), MUTE, Paint.Align.CENTER, true)
        txt(c, "Ruang tunggu", cx, t + d(58f), d(25f), GOLD, Paint.Align.CENTER, true)
        val codeTop = t + d(78f)
        val codeRect = RectF(l + m, codeTop, l + w - m, codeTop + d(94f))
        box(c, codeRect, Color.argb(190, 3, 35, 25), Color.argb(150, 224, 180, 76), d(1.4f), d(16f))
        txt(c, "KODE ROOM · BAGIKAN KE TEMAN", cx, codeRect.top + d(24f), d(10f), MUTE, Paint.Align.CENTER, true)
        txt(c, state.code, cx, codeRect.top + d(68f), d(37f), INK, Paint.Align.CENTER, true)

        val top = codeRect.bottom + d(14f)
        val bottom = b - d(if (state.host == firebase.playerId) 184f else 144f)
        val boxR = RectF(l + m, top, l + w - m, max(top + d(100f), bottom))
        box(c, boxR, Color.argb(165, 4, 25, 20), Color.argb(100, 224, 180, 76), d(1f), d(15f))
        // Toolbar dipisah dari daftar pemain agar tombol tidak menimpa jumlah pemain/baris pertama.
        txt(c, "PEMAIN", boxR.left + d(14f), boxR.top + d(24f), d(11f), GOLD, Paint.Align.LEFT, true)
        txt(c, "${state.players.size}/4", boxR.left + d(72f), boxR.top + d(24f), d(10f), MUTE, Paint.Align.LEFT, true)
        val lobbyMic = RectF(boxR.right - d(148f), boxR.top + d(5f), boxR.right - d(112f), boxR.top + d(37f))
        button(c, lobbyMic, if (!voice.isJoined()) "🎤" else if (voice.isMuted()) (if (voice.isPtt()) "✋" else "🔇") else "🎙", "voice", true, true, -1, 12f)
        if (voice.isSpeaking(firebase.playerId)) speakRing(c, lobbyMic, d(10f))
        button(c, RectF(boxR.right - d(104f), boxR.top + d(5f), boxR.right - d(50f), boxR.top + d(37f)), "CHAT", "chat", true, true, -1, 8f)
        button(c, RectF(boxR.right - d(44f), boxR.top + d(5f), boxR.right - d(8f), boxR.top + d(37f)), "☺", "react", true, true, -1, 12f)
        val srv = if (!state.connected) "SERVER OFFLINE" else state.myPing?.let { "SERVER · ${it} ms" } ?: "SERVER ONLINE"
        txt(c, srv, boxR.right - d(14f), boxR.top + d(51f), d(8f), if (state.connected) OKC else BADC, Paint.Align.RIGHT, true)
        val vbH = drawVoiceBar(c, boxR.left + d(12f), boxR.right - d(12f), boxR.top + d(58f))
        var yy = boxR.top + d(76f) + (if (vbH > 0f) vbH + d(6f) else 0f)
        state.players.forEachIndexed { index, p ->
            val row = RectF(boxR.left + d(9f), yy - d(17f), boxR.right - d(9f), yy + d(15f))
            if (index % 2 == 0) box(c, row, Color.argb(45, 255, 255, 255), 0, 0f, d(8f))
            val isHost = p.id == state.host
            val isMe = p.id == firebase.playerId
            val label = p.name + (if (isMe) "  · KAMU" else "")
            txt(c, label, row.left + d(9f), yy + d(5f), d(14f), if (isMe) GOLD else INK, Paint.Align.LEFT, isMe)
            if (voice.isSpeaking(p.id)) {
                box(c, row, 0, OKC, d(1.5f), d(8f))
                txt(c, "🎙", row.right - d(88f), yy + d(5f), d(13f), OKC, Paint.Align.CENTER)
            }
            val vs = voice.peerState(p.id)
            if (vs != 0) txt(c, if (vs == 2) "VOICE ✓" else if (vs == 3) "VOICE ✕" else "VOICE …", row.right - d(100f), yy + d(4f), d(8f),
                if (vs == 2) OKC else if (vs == 3) BADC else GOLD, Paint.Align.RIGHT, true)
            val status = firebase.playerStatus(p.id, state.players)
            val badge = if (isHost) "HOST" else ""
            if (badge.isNotEmpty()) txt(c, badge, row.right - d(9f), yy - d(2f), d(8f), GOLD, Paint.Align.RIGHT, true)
            drawPresence(c, row.right - d(74f), yy + d(8f), status)
            if (state.host == firebase.playerId && p.bot) {
                button(c, RectF(row.right - d(57f), yy - d(13f), row.right - d(4f), yy + d(13f)), "Hapus", "rmBot", true, true, index, 9f)
            }
            yy += d(37f)
        }
        if (state.players.size < 4) {
            txt(c, "Menunggu ${4 - state.players.size} pemain lagi…", cx, min(boxR.bottom - d(9f), yy + d(5f)), d(10f), MUTE, Paint.Align.CENTER)
        }
        if (state.host == firebase.playerId) {
            val canAdd = state.players.count { !it.bot } >= 2 && state.players.size < 4
            val half = (contentW - d(8f)) / 2
            button(c, RectF(l + m, b - d(169f), l + m + half, b - d(125f)), "+  Tambah bot", "addBot", canAdd, true, -1, 13f)
            button(c, RectF(l + m + half + d(8f), b - d(169f), l + w - m, b - d(125f)), "Bot: ${LEVELS[state.botLevel]}", "botLevel", true, true, state.botLevel, 12f)
            button(c, RectF(l + m, b - d(113f), l + w - m, b - d(61f)), "MULAI PERMAINAN", "lobbyStart", state.players.size >= 2, false, -1, 15f)
        } else {
            val waitR = RectF(l + m, b - d(130f), l + w - m, b - d(76f))
            box(c, waitR, Color.argb(100, 255, 255, 255), Color.argb(65, 255, 255, 255), d(1f), d(12f))
            txt(c, "◌  Menunggu host memulai permainan", cx, waitR.centerY() + d(4f), d(12f), MUTE, Paint.Align.CENTER)
        }
        button(c, RectF(l + m, b - d(53f), l + w - m, b - d(7f)), "Keluar room", "lobbyExit", true, true)
        if (roomMessage.isNotEmpty()) txt(c, roomMessage, cx, b - d(2f), d(10f), WARN, Paint.Align.CENTER)
    }

    private fun drawRules(c: Canvas, l: Float, t: Float, w: Float, b: Float) {
        val m = d(18f)
        txt(c, "Aturan main", l + w / 2, t + d(36f), d(22f), GOLD, Paint.Align.CENTER, true)
        val top = t + d(56f)
        val bottom = b - d(76f)
        c.save()
        c.clipRect(l, top, l + w, bottom)
        var y = top - rulesScroll
        for (s in RULES) {
            val hgt = para(c, "• " + s, l + m, y, (w - 2 * m).toInt(), d(15f), INK, Layout.Alignment.ALIGN_NORMAL)
            y += hgt + d(10f)
        }
        c.restore()
        rulesMax = (y + rulesScroll) - bottom
        button(c, RectF(l + m, b - d(62f), l + w - m, b - d(14f)), "Kembali", "back", true, false)
    }

    // ---------------------------------------------------------------- tahap 2.6: tutorial
    private fun html(s: String): CharSequence = Html.fromHtml(s, Html.FROM_HTML_MODE_COMPACT)

    private class Geo(val px: Float, val pw: Float, val ix: Float, val iw: Float,
                      val bodyTop: Float, val bodyBottom: Float, val navTop: Float, val closeTop: Float)

    private var clipT = 0f
    private var clipB = 0f
    private val NORM = Layout.Alignment.ALIGN_NORMAL

    private fun tutIw(w: Float): Float = min(w - d(24f), d(560f)) - d(32f)

    // panel modal seperti web (#tut .tw): overlay gelap, kotak felt, border emas, judul, isi bisa digulir
    private fun tutPanel(c: Canvas, l: Float, t: Float, w: Float, b: Float, title: CharSequence,
                         contentH: Float, nav: Boolean, close: Boolean): Geo {
        val pw = min(w - d(24f), d(560f))
        val px = l + (w - pw) / 2f
        val ix = px + d(16f)
        val iw = pw - d(32f)
        val th = para(c, title, ix, 0f, iw.toInt(), d(18.5f), GOLD, NORM, false)
        val fixed = d(14f) + th + d(6f) + (if (nav) d(10f) + d(44f) else 0f) + (if (close) d(8f) + d(44f) else 0f) + d(14f)
        val avail = (b - t) * 0.92f
        val bodyH = min(contentH, max(d(60f), avail - fixed))
        val ph = fixed + bodyH
        val py = t + ((b - t) - ph) / 2f
        paint.style = Paint.Style.FILL
        paint.color = Color.argb(0xdd, 0, 0, 0)
        c.drawRect(0f, 0f, width.toFloat(), height.toFloat(), paint)
        box(c, RectF(px, py, px + pw, py + ph), FELT2, GOLD, d(1f), d(14f))
        para(c, title, ix, py + d(14f), iw.toInt(), d(18.5f), GOLD, NORM)
        val bt = py + d(14f) + th + d(6f)
        val bb = bt + bodyH
        val navTop = bb + d(10f)
        val closeTop = (if (nav) navTop + d(44f) else bb) + d(8f)
        tutMax = max(0f, contentH - bodyH)
        tutScroll = tutScroll.coerceIn(0f, tutMax)
        return Geo(px, pw, ix, iw, bt, bb, navTop, closeTop)
    }

    // tombol di area isi yang bisa digulir: cuma bisa ditekan kalau kelihatan penuh
    private fun cbutton(c: Canvas, r: RectF, label: String, id: String, ghost: Boolean) {
        button(c, r, label, id, true, ghost, -1, 14f)
        if (r.top < clipT || r.bottom > clipB) hits.removeAt(hits.size - 1)
    }

    // kotak contoh (#tut .tx): label + deretan kartu bertumpuk yang membungkus ke baris baru
    private fun cardBox(c: Canvas, label: String, ids: List<Int>, x: Float, y: Float, iw: Float, cw: Float, step: Float,
                        raise: Float, dr: Boolean, hit: Boolean = false,
                        sel: List<Int> = emptyList(), glow: List<Int> = emptyList()): Float {
        val padX = d(8f)
        val padY = d(6f)
        val ch = cw / CARD_RATIO
        val lw = (iw - 2 * padX).toInt()
        val lh = para(c, label, x + padX, y + padY, lw, d(13.5f), INK, NORM, false)
        val per = max(1, ((iw - 2 * padX - cw) / step).toInt() + 1)
        val rows = if (ids.isEmpty()) 0 else (ids.size + per - 1) / per
        val rowH = raise + ch
        val h = padY + lh + (if (rows > 0) d(4f) else 0f) + rows * rowH + padY
        if (dr) {
            box(c, RectF(x, y, x + iw, y + h), Color.argb(0x2e, 0, 0, 0), 0, 0f, d(10f))
            para(c, label, x + padX, y + padY, lw, d(13.5f), INK, NORM)
            for (k in ids.indices) {
                val id = ids[k]
                val row = k / per
                val col = k % per
                val x0 = x + padX + col * step
                val top0 = y + padY + lh + d(4f) + row * rowH + raise
                val isSel = sel.contains(id)
                val top = if (isSel) top0 - raise else top0
                val r = RectF(x0, top, x0 + cw, top + ch)
                drawCard(c, id, r)
                if (isSel || glow.contains(id)) {
                    paint.style = Paint.Style.STROKE
                    paint.strokeWidth = d(if (isSel) 2f else 3f)
                    paint.color = if (isSel) GOLD else OKC
                    c.drawRoundRect(r, cw * 0.055f, cw * 0.055f, paint)
                    paint.style = Paint.Style.FILL
                }
                if (hit) {
                    val hr = RectF(x0, top, x0 + (if (col == per - 1 || k == ids.size - 1) cw else step), top + ch)
                    if (hr.top >= clipT && hr.bottom <= clipB) hits.add(Hit(hr, "gc", id))
                }
            }
        }
        return h
    }

    private fun drawTutMenu(c: Canvas, l: Float, t: Float, w: Float, b: Float) {
        val iw = tutIw(w)
        val body = { dr: Boolean, x: Float, y0: Float ->
            var y = y0
            y += para(c, "Pilih cara belajarnya:", x, y + d(2f), iw.toInt(), d(15f), INK, NORM, dr, 1.3f) + d(10f)
            if (dr) button(c, RectF(x, y, x + iw, y + d(44f)), "Tutorial terpandu (buat pemula)", "tg", true, false, -1, 14f)
            y += d(44f) + d(10f)
            if (dr) button(c, RectF(x, y, x + iw, y + d(44f)), "Baca aturan lengkap", "ts", true, true, -1, 14f)
            y += d(44f) + d(10f)
            if (dr) button(c, RectF(x, y, x + iw, y + d(44f)), "Tutup", "tclose", true, true, -1, 14f)
            y += d(44f)
            y - y0
        }
        val g = tutPanel(c, l, t, w, b, html("<b>Tutorial</b>"), body(false, 0f, 0f), false, false)
        clipT = g.bodyTop
        clipB = g.bodyBottom
        body(true, g.ix, g.bodyTop)
    }

    private fun drawTutSlide(c: Canvas, l: Float, t: Float, w: Float, b: Float) {
        val i = tutI.coerceIn(0, TUT.size - 1)
        val s = TUT[i]
        val last = i == TUT.size - 1
        val iw = tutIw(w)
        val cw = d(35f)
        val step = d(32f)
        val body = { dr: Boolean, x: Float, y0: Float ->
            var y = y0 + d(2f)
            for (p in s.p) y += para(c, html(p), x, y, iw.toInt(), d(15f), INK, NORM, dr, 1.3f) + d(8f)
            for ((label, cs) in s.ex) {
                y += d(2f)
                y += cardBox(c, label, cs.map { cid(it) }, x, y, iw, cw, step, 0f, dr) + d(8f)
            }
            for (p in s.p2) y += para(c, html(p), x, y, iw.toInt(), d(15f), INK, NORM, dr, 1.3f) + d(8f)
            if (s.practice) {
                y += d(4f)
                if (dr) cbutton(c, RectF(x, y, x + iw, y + d(44f)), "Latihan lawan bot Mudah", "tprac", false)
                y += d(44f)
            }
            y - y0
        }
        val title = html("<b>" + (i + 1) + "/" + TUT.size + " · " + s.h + "</b>")
        val g = tutPanel(c, l, t, w, b, title, body(false, 0f, 0f), true, true)
        c.save()
        c.clipRect(g.ix - d(4f), g.bodyTop, g.ix + g.iw + d(4f), g.bodyBottom)
        clipT = g.bodyTop
        clipB = g.bodyBottom
        body(true, g.ix, g.bodyTop - tutScroll)
        c.restore()
        val bw = d(104f)
        button(c, RectF(g.ix, g.navTop, g.ix + bw, g.navTop + d(44f)), "Sebelumnya", "tprev", i > 0, true, -1, 13f)
        txt(c, "‹ geser ›", g.ix + g.iw / 2, g.navTop + d(26f), d(12f), MUTE)
        if (last) button(c, RectF(g.ix + g.iw - bw, g.navTop, g.ix + g.iw, g.navTop + d(44f)), "Tutup", "tclose", true, false, -1, 13f)
        else button(c, RectF(g.ix + g.iw - bw, g.navTop, g.ix + g.iw, g.navTop + d(44f)), "Berikutnya", "tnext", true, false, -1, 13f)
        button(c, RectF(g.ix, g.closeTop, g.ix + g.iw, g.closeTop + d(44f)), "Tutup tutorial", "tclose", true, true, -1, 14f)
    }

    private fun gStep() {
        val d0 = GUIDE[gI]
        gSel.clear()
        gOK = false
        gHint = false
        gHand = ArrayList(d0.hand.map { cid(it) })
        gMsg = ""
        gBad = false
        tutScroll = 0f
    }

    private fun gSay(s: String, bad: Boolean) {
        gMsg = s
        gBad = bad
    }

    private fun gWin(d0: Step) {
        gOK = true
        if (d0.play.isNotEmpty() || d0.any) gHand.removeAll(gSel.toSet())
        gSel.clear()
        gMsg = d0.ok
        gBad = d0.bad
    }

    // kombinasi dicek pakai mesin aturan asli (ev, beats, cuts)
    private fun gPlay(d0: Step) {
        val ids = gSel.sorted()
        if (ids.isEmpty()) { gSay("Pilih kartunya dulu, lalu tekan Mainkan.", true); return }
        val e = ev(ids)
        val lc = d0.table?.second?.map { cid(it) }
        val le = if (lc != null) ev(lc) else null
        val last = if (lc != null && le != null) Last(-1, lc, le.t, le.n, le.k, le.k2) else null
        if (e == null) { gSay("Itu bukan kombinasi yang sah. Kombinasi yang boleh: tunggal, pasangan, 3 kembar, seri satu logo, dan yang pakai pasangan.", true); return }
        if (last != null && !(cuts(e, last) || beats(e, last))) {
            gSay(if (d0.pass) "Nggak ada kartu kamu yang bisa ngalahin meja. Tekan Lewat." else "Nggak bisa: harus sejenis, jumlah kartunya sama, dan angkanya lebih tinggi dari meja.", true)
            return
        }
        if (d0.pass) { gSay("Sebenarnya sah, tapi di latihan ini tekan Lewat.", true); return }
        if (d0.play.isNotEmpty()) {
            val need = d0.play.map { cid(it) }.sorted()
            if (need != ids) { gSay("Kombinasinya sah, tapi di latihan ini coba keluarin: " + d0.play.joinToString(" ") + ".", true); return }
        }
        gWin(d0)
    }

    private fun drawGuide(c: Canvas, l: Float, t: Float, w: Float, b: Float) {
        val d0 = GUIDE[gI.coerceIn(0, GUIDE.size - 1)]
        val n = GUIDE.size
        val last = gI == n - 1
        val iw = tutIw(w)
        val cw = d(46f)
        val step = d(42f)
        val need = d0.play.map { cid(it) }
        val body = { dr: Boolean, x: Float, y0: Float ->
            var y = y0 + d(2f)
            val ph = para(c, html(d0.t), x + d(13f), y, (iw - d(13f)).toInt(), d(15f), INK, NORM, dr, 1.3f)
            if (dr) box(c, RectF(x, y, x + d(3f), y + ph), GOLD, 0, 0f, 0f)
            y += ph + d(8f)
            val tb = d0.table
            if (tb != null) {
                y += cardBox(c, "Meja: " + tb.first + " keluarin", tb.second.map { cid(it) }, x, y, iw, cw, step, 0f, dr) + d(8f)
            } else if (d0.act) {
                y += cardBox(c, "Meja kosong: kamu yang buka ronde", emptyList(), x, y, iw, cw, step, 0f, dr) + d(8f)
            }
            if (d0.hand.isNotEmpty()) {
                y += cardBox(c, "Kartu kamu (tekan buat milih)", gHand, x, y, iw, cw, step, d(12f), dr, true,
                    gSel, if (gHint) need else emptyList()) + d(8f)
            }
            val mh = if (gMsg.isNotEmpty()) para(c, html("<b>" + gMsg + "</b>"), x, y, iw.toInt(), d(15f), if (gBad) BADC else OKC, NORM, dr, 1.3f) else 0f
            y += max(mh, d(22f)) + d(4f)
            if (d0.done) {
                y += d(4f)
                if (dr) cbutton(c, RectF(x, y, x + iw, y + d(44f)), "Main lawan bot Mudah", "gm", false)
                y += d(44f)
            }
            y - y0
        }
        val title = html("<b>Latihan " + (gI + 1) + "/" + n + " · " + d0.h + "</b>")
        val g = tutPanel(c, l, t, w, b, title, body(false, 0f, 0f), !last, true)
        c.save()
        c.clipRect(g.ix - d(4f), g.bodyTop, g.ix + g.iw + d(4f), g.bodyBottom)
        clipT = g.bodyTop
        clipB = g.bodyBottom
        body(true, g.ix, g.bodyTop - tutScroll)
        c.restore()
        if (!last) {
            val gap = d(8f)
            if (d0.act && !gOK) {
                val bw = (g.iw - 2 * gap) / 3
                button(c, RectF(g.ix, g.navTop, g.ix + bw, g.navTop + d(44f)), "Mainkan", "gk", true, false, -1, 13f)
                button(c, RectF(g.ix + bw + gap, g.navTop, g.ix + 2 * bw + gap, g.navTop + d(44f)), "Lewat", "gp", true, true, -1, 13f)
                button(c, RectF(g.ix + 2 * (bw + gap), g.navTop, g.ix + g.iw, g.navTop + d(44f)), "Petunjuk", "gi", true, true, -1, 13f)
            } else {
                button(c, RectF(g.ix, g.navTop, g.ix + g.iw, g.navTop + d(44f)), "Lanjut", "gn", true, false, -1, 14f)
            }
        }
        button(c, RectF(g.ix, g.closeTop, g.ix + g.iw, g.closeTop + d(44f)), "Tutup tutorial", "tclose", true, true, -1, 14f)
    }

    private fun drawGame(c: Canvas, l: Float, t: Float, w: Float, b: Float) {
        val g = game ?: return
        val m = d(10f)
        val cx = l + w / 2
        var y = t + d(4f)
        button(c, RectF(l + m, y, l + m + d(78f), y + d(36f)), "Menu", "menu", true, true)
        val tbw = d(58f)
        val tbx = l + m + d(78f) + d(8f)
        button(c, RectF(tbx, y, tbx + tbw, y + d(36f)), "Musik", "music", true, !snd.musicOn)
        button(c, RectF(tbx + tbw + d(6f), y, tbx + 2 * tbw + d(6f), y + d(36f)), "Suara", "sfx", true, !snd.sfxOn)
        if (multiplayer) {
            val gameMic = RectF(l + w - m - d(160f), y, l + w - m - d(124f), y + d(36f))
            button(c, gameMic, if (!voice.isJoined()) "🎤" else if (voice.isMuted()) (if (voice.isPtt()) "✋" else "🔇") else "🎙", "voice", true, true, -1, 12f)
            if (voice.isSpeaking(firebase.playerId)) speakRing(c, gameMic, d(10f))
            button(c, RectF(l + w - m - d(116f), y, l + w - m - d(60f), y + d(36f)), "CHAT", "chat", true, true, -1, 9f)
            button(c, RectF(l + w - m - d(52f), y, l + w - m, y + d(36f)), "☺", "react", true, true, -1, 13f)
        } else txt(c, "Level: " + LEVELS[lv], l + w - m, y + d(24f), d(13f), MUTE, Paint.Align.RIGHT)
        y += d(44f)
        if (multiplayer) {
            val vb = drawVoiceBar(c, l + m, l + w - m, y)
            if (vb > 0f) y += vb + d(6f)
        }

        val pend = g.dec.isNotEmpty()
        val cur = g.order[g.turn]
        val myTurn = cur == 0 && !g.over && !pend && !g.fin.contains(0)

        // lawan: pemain berikutnya ada di paling kanan
        val gap = d(8f)
        val ow = (w - 2 * m - 2 * gap) / 3
        val oh = d(58f)
        val seats = intArrayOf(3, 2, 1)
        for (k in seats.indices) {
            val p = seats[k]
            if (p >= g.hands.size) continue
            val x0 = l + m + k * (ow + gap)
            val r = RectF(x0, y, x0 + ow, y + oh)
            seatR[p] = r
            val turn = cur == p && !g.over && !pend
            val rank = g.fin.indexOf(p) + 1
            val dim = g.passed.contains(p) || rank > 0
            box(c, r, PANEL, if (turn) GOLD else EDGE, if (turn) d(3f) else d(1f), d(12f))
            val tc = if (dim) MUTE else INK
            txt(c, playerName(p), r.centerX(), r.top + d(20f), d(14f), tc, Paint.Align.CENTER, true)
            val st = if (rank > 0) "Juara " + rank + (if (g.stopped.contains(p)) " (murni)" else "")
            else if (g.passed.contains(p)) "lewat"
            else if (g.dec.contains(p)) "murni, milih..."
            else ""
            txt(c, st, r.centerX(), r.top + d(38f), d(12f), MUTE)
            if (multiplayer && seatPids.getOrNull(p) != null) {
                val pid = seatPids[p]
                if (voice.isSpeaking(pid)) {
                    speakRing(c, r, d(12f))
                    txt(c, "🎙", r.right - d(14f), r.top + d(18f), d(12f), OKC, Paint.Align.CENTER)
                }
                val vs = voice.peerState(pid)
                if (vs != 0) txt(c, if (vs == 2) "VC ✓" else if (vs == 3) "VC ✕" else "VC …", r.left + d(8f), r.top + d(14f), d(8f),
                    if (vs == 2) OKC else if (vs == 3) BADC else GOLD, Paint.Align.LEFT, true)
                val status = firebase.playerStatus(pid, roomState?.players ?: emptyList())
                val statusText = when (status.state) {
                    "bot" -> "🤖 BOT"
                    "away" -> "🌙 AWAY"
                    "off" -> "PUTUS"
                    "afk" -> "AFK · BOT"
                    else -> status.ping?.let { "${it} ms" } ?: "ONLINE"
                }
                txt(c, statusText, r.centerX(), r.bottom - d(7f), d(8f), when (status.state) { "afk", "off" -> BADC; "away" -> GOLD; else -> OKC }, Paint.Align.CENTER, true)
            }
        }

        // ukuran tangan & posisi tombol
        val cwBase = min(w * 0.2f, (b - t) * 0.1f)
        val cw = if (big) min(cwBase * 1.25f, min(w * 0.26f, (b - t) * 0.125f)) else cwBase
        val ch = cw / CARD_RATIO
        val raise = ch * 0.22f
        val handBottom = b - d(8f)
        val handTop = handBottom - ch
        val btnH = d(48f)
        val btnTop = handTop - raise - d(6f) - btnH
        val chipH = d(32f)
        val chipTop = btnTop - d(6f) - chipH
        val msgH = d(38f)
        val tableTop = y + oh + d(8f)
        val tableBottom = max(tableTop + d(120f), chipTop - msgH - d(4f))
        val tr = RectF(l + m, tableTop, l + w - m, tableBottom)
        box(c, tr, TABLE, EDGE, d(1f), d(18f))
        tblCx = tr.centerX()
        tblCy = tr.centerY()

        if (g.over) {
            drawResult(c, g, tr)
        } else if (g.dec.contains(0)) {
            txt(c, "Kartu murni!", tr.centerX(), tr.top + d(40f), d(22f), GOLD, Paint.Align.CENTER, true)
            para(c, "Kamu nggak punya J, Q, K. Berhenti = langsung jadi juara (game tetap lanjut). Atau lanjut main.",
                tr.left + d(16f), tr.top + d(56f), (tr.width() - d(32f)).toInt(), d(14f), INK, Layout.Alignment.ALIGN_CENTER)
            val by = tr.bottom - d(64f)
            val bwid = (tr.width() - d(32f) - gap) / 2
            button(c, RectF(tr.left + d(16f), by, tr.left + d(16f) + bwid, by + d(48f)), "Berhenti (juara)", "stopY", true, false)
            button(c, RectF(tr.right - d(16f) - bwid, by, tr.right - d(16f), by + d(48f)), "Lanjut main", "stopN", true, true)
        } else {
            val last = g.last
            if (last == null) {
                txt(c, if (g.first) "Fase kumpulin 3" else "Meja kosong", tr.centerX(), tr.centerY() - d(6f), d(20f), INK, Paint.Align.CENTER, true)
                txt(c, if (g.first) "Pemegang angka 3 keluarin semua kartu 3-nya" else "Bebas keluarin kombinasi apa aja",
                    tr.centerX(), tr.centerY() + d(18f), d(13f), MUTE)
            } else {
                val title = playerName(last.p) + " · " + (TYPE_NAME[last.t] ?: "") + (if (last.t == 'r') " " + last.n else "")
                txt(c, title, tr.centerX(), tr.top + d(28f), d(15f), INK, Paint.Align.CENTER, true)
                val n = last.c.size
                val availH = tr.height() - d(80f)
                val tcw = min(min(w * 0.15f, availH * CARD_RATIO), d(90f))
                val tch = tcw / CARD_RATIO
                val availW = tr.width() - d(24f)
                val step = if (n > 1) min(tcw * 0.85f, (availW - tcw) / (n - 1)) else 0f
                val totalW = tcw + step * (n - 1)
                var x = tr.centerX() - totalW / 2
                val top = tr.centerY() - tch / 2 + d(4f)
                val fa = fly
                val el = if (fa != null && fa.p == last.p) now - fa.t0 else Long.MAX_VALUE
                if (fa != null && el < fa.total) {
                    anim = true
                    val dx0 = tr.centerX()
                    val dy0 = top + tch / 2
                    val sr = seatR[fa.p]
                    val sx = if (fa.p == 0 || sr == null) cx else sr.centerX()
                    val sy = if (fa.p == 0 || sr == null) handTop + ch / 2 else sr.centerY()
                    if (el < 340L) { // meluncur dari pemain ke meja sebagai punggung kartu
                        val tt = el / 340f
                        val e1 = 1f - (1f - tt) * (1f - tt) * (1f - tt)
                        val sc = 0.55f + 0.45f * e1
                        c.save()
                        c.translate((sx - dx0) * (1f - e1), (sy - dy0) * (1f - e1))
                        c.scale(sc, sc, dx0, dy0)
                        c.saveLayerAlpha(RectF(x - d(4f), top - d(4f), x + totalW + d(4f), top + tch + d(4f)), ((0.2f + 0.8f * e1) * 255f).toInt().coerceIn(0, 255))
                        for (id in last.c) {
                            drawCard(c, -1, RectF(x, top, x + tcw, top + tch))
                            x += step
                        }
                        c.restore()
                        c.restore()
                    } else { // dibalik satu-satu
                        for (i in last.c.indices) {
                            val u = el - 340L - 50L * i
                            val rr = RectF(x, top, x + tcw, top + tch)
                            val back = u < 120L
                            val sxs = if (u < 0L) 1f else if (u < 120L) 1f - u / 120f else if (u < 240L) (u - 120L) / 120f else 1f
                            c.save()
                            c.scale(max(0.03f, sxs), 1f, rr.centerX(), rr.centerY())
                            drawCard(c, if (back) -1 else last.c[i], rr)
                            c.restore()
                            x += step
                        }
                    }
                } else {
                    for (id in last.c) {
                        drawCard(c, id, RectF(x, top, x + tcw, top + tch))
                        x += step
                    }
                }
            }
            val who = if (myTurn) "Giliran kamu!" else if (pend) "Nunggu pilihan kartu murni" else "Giliran " + playerName(cur)
            txt(c, who, tr.centerX(), tr.bottom - d(14f), d(14f), if (myTurn) GOLD else MUTE, Paint.Align.CENTER, myTurn)
        }

        // pesan
        val note = if (msg.isNotEmpty()) msg
        else if (myTurn) (if (g.first) "Kartu 3 kamu sudah kepilih. Tekan Mainkan." else "Pilih kartu lalu tekan Mainkan.")
        else ""
        if (note.isNotEmpty()) {
            para(c, note, l + m, tableBottom + d(6f), (w - 2 * m).toInt(), d(13f), if (msg.isNotEmpty()) WARN else MUTE, Layout.Alignment.ALIGN_CENTER)
        }

        // notif "Rapikan kartu dulu" (30 detik, sekali tiap game baru)
        if (!arnDone && !g.over) {
            if (arnT0 == 0L && g.log.isEmpty() && g.hands[0].size > 1 && !g.dec.contains(0)) arnT0 = now
            if (arnT0 > 0L) {
                val ael = now - arnT0
                if (ael >= 30000L) {
                    arnDone = true
                } else {
                    slow = true
                    val ar = RectF(tr.left + d(8f), tr.top + d(8f), tr.right - d(8f), tr.top + d(84f))
                    box(c, ar, Color.argb(235, 16, 42, 34), GOLD, d(1.5f), d(12f))
                    hits.add(Hit(ar, "arnbox", -1))
                    txt(c, "Rapikan kartu dulu (" + ((30000L - ael) / 1000L + 1L) + " dtk)", ar.left + d(12f), ar.top + d(24f), d(14f), GOLD, Paint.Align.LEFT, true)
                    para(c, "Tahan kartu lalu geser buat atur manual, atau tap Kelompokkan.", ar.left + d(12f), ar.top + d(32f), (ar.width() - d(56f)).toInt(), d(12f), INK, Layout.Alignment.ALIGN_NORMAL)
                    val xr = RectF(ar.right - d(40f), ar.top + d(4f), ar.right - d(4f), ar.top + d(40f))
                    txt(c, "✕", xr.centerX(), xr.centerY() + d(6f), d(18f), MUTE)
                    hits.add(Hit(xr, "arnx", -1))
                    val frac = 1f - ael / 30000f
                    box(c, RectF(ar.left + d(12f), ar.bottom - d(10f), ar.left + d(12f) + (ar.width() - d(24f)) * frac, ar.bottom - d(6f)), GOLD, 0, 0f, d(2f))
                }
            }
        }

        // tombol
        val bwid = (w - 2 * m - gap) / 2
        button(c, RectF(l + m, btnTop, l + m + bwid, btnTop + btnH), "Lewat", "pass", myTurn && g.last != null && !g.first, true)
        button(c, RectF(l + m + bwid + gap, btnTop, l + w - m, btnTop + btnH), "Mainkan", "play", myTurn && sel.isNotEmpty(), false)
        button(c, RectF(l + m, chipTop, l + m + bwid, chipTop + chipH), "Kelompokkan", "group", g.hands[0].size > 1, true, -1, 13f)
        button(c, RectF(l + m + bwid + gap, chipTop, l + w - m, chipTop + chipH), "Batal pilih", "unsel", sel.isNotEmpty(), true, -1, 13f)

        // tangan: urutan manual (tahan lalu geser), Kelompokkan buat merapikan
        val hand = syncOrder(g.hands[0])
        cardRects.clear()
        if (hand.isEmpty()) {
            txt(c, if (g.over) "" else "Kamu sudah selesai. Tinggal nonton!", cx, handTop + ch / 2, d(15f), MUTE)
        } else {
            val n = hand.size
            val availW = w - 2 * m
            val step = if (n > 1) min(cw * 0.9f, (availW - cw) / (n - 1)) else 0f
            val totalW = cw + step * (n - 1)
            var x = cx - totalW / 2
            handX0 = x
            handStep = step
            handTopY = handTop
            handBottomY = handTop + ch
            val dealing = dealT0 > 0L && !g.over && now - dealT0 < 820L
            if (dealing) anim = true
            for (i in hand.indices) {
                val id = hand[i]
                val selected = sel.contains(id)
                val target = if (selected) 1f else 0f
                var f = selFrac[id] ?: target
                if (f != target) {
                    val dv = frameDt / 110f
                    f = if (f < target) min(target, f + dv) else max(target, f - dv)
                    if (f != target) anim = true
                }
                selFrac[id] = f
                val top = handTop - raise * f
                val r = RectF(x, top, x + cw, top + ch)
                cardRects[id] = r
                val ghostSlot = dragging && id == dragId
                val dl = if (dealing) now - dealT0 - 40L * i else 1000L
                if (dl > 0L) {
                    c.save()
                    if (dl < 300L) { // dibagi dari tengah meja
                        val tt = dl / 300f
                        val e1 = 1f - (1f - tt) * (1f - tt) * (1f - tt)
                        val sc = 0.6f + 0.4f * e1
                        c.translate((tblCx - r.centerX()) * (1f - e1), (tblCy - r.centerY()) * (1f - e1))
                        c.scale(sc, sc, r.centerX(), r.centerY())
                    }
                    if (ghostSlot) c.saveLayerAlpha(RectF(r.left - 4f, r.top - 4f, r.right + 4f, r.bottom + 4f), 70)
                    drawCard(c, id, r)
                    if (selected) {
                        paint.style = Paint.Style.STROKE
                        paint.strokeWidth = d(2.5f)
                        paint.color = GOLD
                        c.drawRoundRect(r, cw * 0.055f, cw * 0.055f, paint)
                        paint.style = Paint.Style.FILL
                    }
                    if (ghostSlot) c.restore()
                    c.restore()
                }
                val hr = RectF(x, top, x + (if (i == n - 1) cw else step), top + ch)
                hits.add(Hit(hr, "card", id))
                x += step
            }
        }

        // kartu yang lagi diseret: ikut jari, agak membesar dan miring
        if (dragging) {
            val r0 = cardRects[dragId]
            if (r0 != null) {
                val rw = r0.width()
                val rh = r0.height()
                val left = dragX - dragDX
                val topd = dragY - dragDY
                val rr = RectF(left, topd, left + rw, topd + rh)
                c.save()
                c.rotate(-4f, rr.centerX(), rr.centerY())
                c.scale(1.12f, 1.12f, rr.centerX(), rr.centerY())
                box(c, RectF(rr.left + d(2f), rr.top + d(5f), rr.right + d(2f), rr.bottom + d(5f)), Color.argb(110, 0, 0, 0), 0, 0f, rw * 0.06f)
                drawCard(c, dragId, rr)
                c.restore()
            }
        }
    }

    private fun drawResult(c: Canvas, g: Game, tr: RectF) {
        val cx = tr.centerX()
        val s2 = g.self2
        val q2 = g.quad2
        val title = if (g.bomb) playerName(g.winner ?: 0) + " ngebom kartu 2: menang!"
        else if (s2 != null) playerName(s2) + " habis kartu pakai 2: kalah"
        else if (q2 != null) playerName(q2) + " dapat 4 kartu 2: kalah"
        else "Game selesai"
        txt(c, title, cx, tr.top + d(34f), d(17f), GOLD, Paint.Align.CENTER, true)
        var yy = tr.top + d(66f)
        if (!g.bomb && g.winner != null) {
            for (i in g.fin.indices) {
                val p = g.fin[i]
                val label = "Juara " + (i + 1) + ": " + playerName(p) + (if (g.stopped.contains(p)) " (murni)" else "")
                txt(c, label, cx, yy, d(16f), if (p == 0) GOLD else INK, Paint.Align.CENTER, p == 0)
                yy += d(26f)
            }
        } else if (g.bomb) {
            txt(c, "Game langsung berakhir.", cx, yy, d(15f), INK)
        } else {
            txt(c, "Game diulang dari awal.", cx, yy, d(15f), INK)
        }
        val by = tr.bottom - d(64f)
        val gap = d(8f)
        val bwid = (tr.width() - d(32f) - gap) / 2
        val canAgain = !multiplayer || roomState?.effectiveDriver == firebase.playerId
        if (canAgain) button(c, RectF(tr.left + d(16f), by, tr.left + d(16f) + bwid, by + d(48f)), "Main lagi", "again", true, false)
        else txt(c, "Menunggu pengendali aktif", tr.left + d(16f) + bwid / 2, by + d(29f), d(9f), MUTE, Paint.Align.CENTER)
        button(c, RectF(tr.right - d(16f) - bwid, by, tr.right - d(16f), by + d(48f)), "Menu", "menu", true, true)
    }
}
