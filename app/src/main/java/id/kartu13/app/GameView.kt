package id.kartu13.app

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.os.VibrationEffect
import android.os.Vibrator
import android.text.Layout
import android.text.StaticLayout
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
    private enum class Screen { HOME, GAME, RULES }

    private class Hit(val r: RectF, val id: String, val idx: Int)

    private val dp = resources.displayMetrics.density
    private val handler = Handler(Looper.getMainLooper())
    private val prefs = ctx.getSharedPreferences("k13", Context.MODE_PRIVATE)
    private val snd = Snd(ctx)
    private var lastLog = 0
    private var screen = Screen.HOME
    private var lv = prefs.getInt("lv", 1).coerceIn(0, 3)
    private var game: Game? = null
    private val sel = ArrayList<Int>()
    private var msg = ""
    private val hits = ArrayList<Hit>()
    private val cache = HashMap<Int, Bitmap?>()
    private var bg: Paint? = null
    private var rulesScroll = 0f
    private var rulesMax = 0f
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
            if (cards.isEmpty()) { msg = "Pilih kartu dulu."; return }
            val w = why(g, 0, cards)
            if (w.isNotEmpty()) { msg = w; return }
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

    fun onResume() { snd.resume() }

    fun onPause() { snd.pause() }

    fun stop() {
        handler.removeCallbacksAndMessages(null)
        snd.release()
    }

    fun onBack(): Boolean {
        if (screen == Screen.HOME) return false
        handler.removeCallbacks(botTask)
        screen = Screen.HOME
        invalidate()
        return true
    }

    // ---------------------------------------------------------------- sentuhan
    private fun handleHit(h: Hit) {
        when (h.id) {
            "lv" -> { lv = h.idx; prefs.edit().putInt("lv", lv).apply() }
            "start" -> startGame(null)
            "rules" -> { screen = Screen.RULES; rulesScroll = 0f }
            "back", "menu" -> { handler.removeCallbacks(botTask); screen = Screen.HOME }
            "again" -> startGame(nextLead())
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
            "stopY" -> { game?.let { decide(it, 0, true) }; afterMove() }
            "stopN" -> { game?.let { decide(it, 0, false) }; afterMove() }
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
                }
                if (abs(e.x - downX) > d(12f) || abs(e.y - downY) > d(12f)) {
                    moved = true
                    handler.removeCallbacks(lpTask)
                }
            }
            MotionEvent.ACTION_UP -> {
                handler.removeCallbacks(lpTask)
                if (dragging) {
                    endDragState()
                    arnDone = true
                    invalidate()
                    return true
                }
                if (!moved) {
                    for (h in hits.reversed()) {
                        if (h.r.contains(e.x, e.y)) { handleHit(h); break }
                    }
                }
            }
            MotionEvent.ACTION_CANCEL -> {
                handler.removeCallbacks(lpTask)
                if (dragging) {
                    endDragState()
                    invalidate()
                }
            }
        }
        return true
    }

    // ---------------------------------------------------------------- gambar dasar
    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        val p = Paint()
        p.shader = RadialGradient(
            w * 0.5f, h * 0.4f, max(w, h) * 0.75f,
            intArrayOf(Color.parseColor("#23765a"), Color.parseColor("#15493a"), Color.parseColor("#071a14")),
            floatArrayOf(0f, 0.45f, 1f), Shader.TileMode.CLAMP
        )
        bg = p
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

    private fun para(c: Canvas, s: String, x: Float, y: Float, width: Int, size: Float, color: Int,
                     align: Layout.Alignment): Float {
        tp.reset()
        tp.isAntiAlias = true
        tp.textSize = size
        tp.color = color
        val sl = StaticLayout.Builder.obtain(s, 0, s.length, tp, max(1, width))
            .setAlignment(align).setLineSpacing(0f, 1.1f).build()
        c.save()
        c.translate(x, y)
        sl.draw(c)
        c.restore()
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
        bg?.let { c.drawRect(0f, 0f, width.toFloat(), height.toFloat(), it) }
        val l = paddingLeft.toFloat()
        val t = paddingTop.toFloat()
        val w = (width - paddingLeft - paddingRight).toFloat()
        val b = (height - paddingBottom).toFloat()
        when (screen) {
            Screen.HOME -> drawHome(c, l, t, w, b)
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
        txt(c, "Poker 13 · lawan bot", cx, y, d(15f), MUTE)
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
        button(c, RectF(cx - ww / 2, y, cx + ww / 2, y + d(56f)), "Main lawan bot", "start", true, false)
        y += d(56f) + d(12f)
        button(c, RectF(cx - ww / 2, y, cx + ww / 2, y + d(48f)), "Aturan main", "rules", true, true)
        y += d(48f) + d(12f)
        val tg = d(6f)
        val tw = (ww - 2 * tg) / 3
        val x0 = cx - ww / 2
        button(c, RectF(x0, y, x0 + tw, y + d(40f)), "Musik: " + (if (snd.musicOn) "nyala" else "mati"), "music", true, !snd.musicOn, -1, 12f)
        button(c, RectF(x0 + tw + tg, y, x0 + 2 * tw + tg, y + d(40f)), "Suara: " + (if (snd.sfxOn) "nyala" else "mati"), "sfx", true, !snd.sfxOn, -1, 12f)
        button(c, RectF(x0 + 2 * (tw + tg), y, x0 + ww, y + d(40f)), "Kartu: " + (if (big) "besar" else "normal"), "big", true, !big, -1, 12f)
        val v = versionName()
        txt(c, "© 2026 Poker 13" + (if (v.isNotEmpty()) " · v$v" else ""), cx, b - d(40f), d(11f), MUTE)
        txt(c, "AI-assisted by Claude · Card art: Vecteezy.com", cx, b - d(24f), d(11f), MUTE)
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
        txt(c, "Level: " + LEVELS[lv], l + w - m, y + d(24f), d(13f), MUTE, Paint.Align.RIGHT)
        y += d(44f)

        val pend = g.dec.isNotEmpty()
        val cur = g.order[g.turn]
        val myTurn = cur == 0 && !g.over && !pend && !g.fin.contains(0)

        // lawan: pemain berikutnya ada di paling kanan
        val gap = d(8f)
        val ow = (w - 2 * m - 2 * gap) / 3
        val oh = d(88f)
        val seats = intArrayOf(3, 2, 1)
        for (k in seats.indices) {
            val p = seats[k]
            val x0 = l + m + k * (ow + gap)
            val r = RectF(x0, y, x0 + ow, y + oh)
            seatR[p] = r
            val turn = cur == p && !g.over && !pend
            val rank = g.fin.indexOf(p) + 1
            val dim = g.passed.contains(p) || rank > 0
            box(c, r, PANEL, if (turn) GOLD else EDGE, if (turn) d(3f) else d(1f), d(12f))
            val tc = if (dim) MUTE else INK
            txt(c, NAMES[p], r.centerX(), r.top + d(20f), d(14f), tc, Paint.Align.CENTER, true)
            val st = if (rank > 0) "Juara " + rank + (if (g.stopped.contains(p)) " (murni)" else "")
            else if (g.passed.contains(p)) "lewat"
            else if (g.dec.contains(p)) "murni, milih..."
            else ""
            txt(c, st, r.centerX(), r.top + d(38f), d(12f), MUTE)
            val bw = d(22f)
            val bx = r.centerX() - d(30f)
            drawCard(c, -1, RectF(bx, r.top + d(46f), bx + bw, r.top + d(46f) + bw / CARD_RATIO))
            txt(c, "x " + g.hands[p].size, r.centerX() - d(2f), r.top + d(70f), d(15f), tc, Paint.Align.LEFT, true)
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
                val title = NAMES[last.p] + " · " + (TYPE_NAME[last.t] ?: "") + (if (last.t == 'r') " " + last.n else "")
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
            val who = if (myTurn) "Giliran lo!" else if (pend) "Nunggu pilihan kartu murni" else "Giliran " + NAMES[cur]
            txt(c, who, tr.centerX(), tr.bottom - d(14f), d(14f), if (myTurn) GOLD else MUTE, Paint.Align.CENTER, myTurn)
        }

        // pesan
        val note = if (msg.isNotEmpty()) msg
        else if (myTurn) (if (g.first) "Kartu 3 lo sudah kepilih. Tekan Mainkan." else "Pilih kartu lalu tekan Mainkan.")
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
        val title = if (g.bomb) NAMES[g.winner ?: 0] + " ngebom kartu 2: menang!"
        else if (s2 != null) NAMES[s2] + " habis kartu pakai 2: kalah"
        else if (q2 != null) NAMES[q2] + " dapat 4 kartu 2: kalah"
        else "Game selesai"
        txt(c, title, cx, tr.top + d(34f), d(17f), GOLD, Paint.Align.CENTER, true)
        var yy = tr.top + d(66f)
        if (!g.bomb && g.winner != null) {
            for (i in g.fin.indices) {
                val p = g.fin[i]
                val label = "Juara " + (i + 1) + ": " + NAMES[p] + (if (g.stopped.contains(p)) " (murni)" else "")
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
        button(c, RectF(tr.left + d(16f), by, tr.left + d(16f) + bwid, by + d(48f)), "Main lagi", "again", true, false)
        button(c, RectF(tr.right - d(16f) - bwid, by, tr.right - d(16f), by + d(48f)), "Menu", "menu", true, true)
    }
}
