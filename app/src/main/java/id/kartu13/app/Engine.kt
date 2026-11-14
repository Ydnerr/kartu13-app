package id.kartu13.app

import kotlin.random.Random

// Port dari game.js (v44.11). id kartu 0..51: angka = id shr 2 (3..K,A,2), logo = id and 3 (spade<keriting<wajik<hati).
fun rk(id: Int): Int = id shr 2
fun su(id: Int): Int = id and 3

val RANKS = arrayOf("3", "4", "5", "6", "7", "8", "9", "10", "J", "Q", "K", "A", "2")

val TYPE_NAME: Map<Char, String> = mapOf(
    's' to "Tunggal", 'p' to "Pasangan", 't' to "Tiga kembar", 'q' to "Bom 4 kembar",
    'd' to "8 kartu kembar (2 x 4)", 'g' to "4 kembar + pasangan", 'r' to "Seri satu logo",
    'c' to "Kumpulin angka 3", 'f' to "3 kembar + pasangan", 'x' to "Seri satu logo + pasangan"
)

class Ev(val t: Char, val n: Int, val k: Int, val k2: Int = 0, val m: List<Int> = emptyList())

class Last(val p: Int, val c: List<Int>, val t: Char, val n: Int, val k: Int, val k2: Int)

/** Seri spesial J Q K satu logo (3 kartu, atau + pasangan = 5 kartu). Seri biasa maksimal angka 10, jadi kunci K pasti J Q K. */
fun isJqk(l: Last?): Boolean = l != null && (l.t == 'r' || l.t == 'x') && rk(l.k) == 10
fun jqkName(l: Last): String = if (l.t == 'x') "Seri spesial J Q K + pasangan" else "Seri spesial J Q K"
/** Nama jenis kombinasi di meja. */
fun typeLabel(l: Last): String = if (isJqk(l)) jqkName(l) else (TYPE_NAME[l.t] ?: "") + (if (l.t == 'r') " " + l.n else "")

class LogE(val p: Int, val c: List<Int>? = null, val m: String? = null, val x: Boolean = false)

class Opt(val cs: List<Int>, val e: Ev) { var s: Double = 0.0 }

class BotMove(val cards: List<Int>?, val pass: Boolean)

class Game(val order: List<Int>, var hands: Array<MutableList<Int>>) {
    var turn = 0
    var last: Last? = null
    val passed = HashSet<Int>()
    var first = false
    var over = false
    var winner: Int? = null
    val fin = ArrayList<Int>()
    val dec = HashSet<Int>()
    val stopped = HashSet<Int>()
    var st = 0
    var s = -1
    var bomb = false
    var self2: Int? = null
    var quad2: Int? = null
    val log = ArrayList<LogE>()
}

private fun groupByRank(c: List<Int>): List<List<Int>> {
    val m = LinkedHashMap<Int, MutableList<Int>>()
    for (x in c) m.getOrPut(rk(x)) { ArrayList() }.add(x)
    return m.values.toList()
}

fun ev(cs: List<Int>): Ev? {
    val c = cs.sorted()
    val n = c.size
    if (n == 0) return null
    val r = c.map { rk(it) }
    val top = c[n - 1]
    if (n <= 4 && r.all { it == r[0] }) return Ev(arrayOf(' ', 's', 'p', 't', 'q')[n], n, top)
    if (n >= 3 && r[n - 1] < 8) {
        var seq = true
        for (i in 1 until n) if (r[i] != r[i - 1] + 1) seq = false
        if (seq && c.all { su(it) == su(c[0]) }) return Ev('r', n, top)
    }
    // SERI SPESIAL: J Q K satu logo (3 kartu). Jadi seri 3 kartu tertinggi (kuncinya K), sama dengan web v45
    if (n == 3 && r[0] == 8 && r[1] == 9 && r[2] == 10 && c.all { su(it) == su(c[0]) }) return Ev('r', n, top)
    if (n == 5) {
        val gs = groupByRank(c)
        val tri = gs.firstOrNull { it.size == 3 }
        if (gs.size == 2 && tri != null) {
            val rest = c.filter { !tri.contains(it) }
            return Ev('f', n, tri.max(), rest.max(), tri)
        }
    }
    if (n == 8) {
        val gs = groupByRank(c)
        if (gs.size == 2 && gs.all { it.size == 4 }) return Ev('d', n, top)
    }
    if (n == 6) {
        val gs = groupByRank(c)
        val q4 = gs.firstOrNull { it.size == 4 }
        val pr = gs.firstOrNull { it.size == 2 }
        if (gs.size == 2 && q4 != null && pr != null) return Ev('g', n, q4.max(), pr.max(), q4)
    }
    if (n == 5) { // SERI SPESIAL J Q K satu logo + pasangan
        for (s in 0..3) {
            val core = listOf(8, 9, 10).map { it * 4 + s }
            if (c.containsAll(core)) {
                val rest = c.filter { !core.contains(it) }
                if (rest.size == 2 && rk(rest[0]) == rk(rest[1])) return Ev('x', n, core[2], rest.max(), core)
            }
        }
    }
    if (n >= 5) {
        val len = n - 2
        for (s in 0..3) {
            var st = 0
            while (st + len - 1 <= 7) {
                val core = (0 until len).map { (st + it) * 4 + s }
                if (c.containsAll(core)) {
                    val rest = c.filter { !core.contains(it) }
                    if (rest.size == 2 && rk(rest[0]) == rk(rest[1])) {
                        return Ev('x', n, core[len - 1], rest.max(), core)
                    }
                }
                st++
            }
        }
    }
    return null
}

// urutan tampil: bagian utama (seri / 3 kembar / 4 kembar) dulu, pasangan di belakang
fun arr(cards: List<Int>, e: Ev?): List<Int> {
    val c = cards.sorted()
    if (e == null || e.m.isEmpty()) return c
    return c.filter { e.m.contains(it) } + c.filter { !e.m.contains(it) }
}

fun twoPlay(l: Last?): Boolean = l != null && l.c.isNotEmpty() && l.c.all { rk(it) == 12 }

fun beats(a: Ev, l: Last?): Boolean {
    if (l == null) return true
    if (twoPlay(l)) return false
    if (a.t == l.t && a.n == l.n) return rk(a.k) > rk(l.k)
    return false
}

fun isBomb(e: Ev): Boolean = e.t == 'q' || e.t == 'g' || (e.t == 'r' && e.n == 5) || (e.t == 'x' && e.n == 7)
fun isDouble(e: Ev): Boolean = e.t == 'd' || (e.t == 'r' && e.n >= 10)

// bom menimpa kartu 2: game langsung berakhir
fun cuts(e: Ev, l: Last?): Boolean {
    if (l == null || !twoPlay(l)) return false
    return if (l.c.size == 1) (isBomb(e) || isDouble(e)) else isDouble(e)
}

fun act(g: Game): List<Int> = g.order.filter { !g.fin.contains(it) }
fun has3(g: Game, p: Int): Boolean = g.hands[p].any { rk(it) == 0 }
fun addLog(g: Game, e: LogE) { g.log.add(e) }

fun nextTurn(g: Game, passed: Set<Int>): Int {
    var i = g.turn
    do {
        i = (i + 1) % g.order.size
    } while (passed.contains(g.order[i]) || g.fin.contains(g.order[i]))
    return i
}

fun startRound(g: Game, p: Int) {
    g.last = null
    g.passed.clear()
    var i = g.order.indexOf(p)
    while (g.fin.contains(g.order[i])) i = (i + 1) % g.order.size
    g.turn = i
}

// pemain habis kartu / berhenti -> masuk daftar juara. true kalau game selesai (tinggal 1 pemain)
fun finish(g: Game, pid: Int, msg: String): Boolean {
    g.fin.add(pid)
    addLog(g, LogE(pid, m = msg + "juara " + g.fin.size))
    val a = act(g)
    if (a.size <= 1) {
        for (p in a) g.fin.add(p)
        g.over = true
        g.winner = g.fin[0]
        g.dec.clear()
        return true
    }
    return false
}

fun move(g: Game, pid: Int, cards: List<Int>, pass: Boolean): Boolean {
    if (g.over || g.order[g.turn] != pid) return false
    if (g.dec.isNotEmpty()) return false
    if (g.fin.contains(pid)) return false
    if (g.first) { // fase kumpulin 3: keluarin SEMUA kartu 3 sekaligus
        if (pass) return false
        val hand = g.hands[pid]
        val m3 = hand.filter { rk(it) == 0 }
        if (m3.isEmpty() || cards.size != m3.size || !m3.all { cards.contains(it) }) return false
        g.hands[pid] = hand.filter { rk(it) != 0 }.toMutableList()
        val sorted = cards.sorted()
        addLog(g, LogE(pid, c = sorted))
        g.last = Last(pid, sorted, 'c', sorted.size, sorted[sorted.size - 1], 0)
        for (k in 1..g.order.size) {
            val i = (g.turn + k) % g.order.size
            val p = g.order[i]
            if (!g.fin.contains(p) && has3(g, p)) {
                g.turn = i
                return true
            }
        }
        g.first = false
        addLog(g, LogE(g.st, m = "semua 3 terkumpul, jalan duluan"))
        startRound(g, g.st)
        return true
    }
    val last = g.last
    val passed = g.passed
    if (pass) {
        if (last == null) return false
        passed.add(pid)
        addLog(g, LogE(pid, x = true))
        val waiting = act(g).filter { !passed.contains(it) && it != last.p }
        if (waiting.isEmpty()) startRound(g, last.p)
        else g.turn = nextTurn(g, passed)
        return true
    }
    val hand = g.hands[pid]
    if (cards.isEmpty() || !cards.all { hand.contains(it) }) return false
    val e = ev(cards) ?: return false
    val cut = cuts(e, last)
    if (!cut && !beats(e, last)) return false
    g.hands[pid] = hand.filter { !cards.contains(it) }.toMutableList()
    val sorted = arr(cards, e)
    addLog(g, LogE(pid, c = sorted))
    g.first = false
    val nl = Last(pid, sorted, e.t, e.n, e.k, e.k2)
    if (cut) { // bom menimpa kartu 2: pengeluar bom menang
        g.last = nl
        g.over = true
        g.winner = pid
        g.bomb = true
        g.fin.clear()
        g.fin.add(pid)
        return true
    }
    if (g.hands[pid].isEmpty() && cards.any { rk(it) == 12 }) { // habis kartu pakai 2 = ngebom diri sendiri
        g.last = nl
        g.over = true
        g.winner = null
        g.self2 = pid
        g.fin.clear()
        g.dec.clear()
        return true
    }
    g.last = nl
    passed.clear()
    if (g.hands[pid].isEmpty()) {
        if (finish(g, pid, "habis kartu, ")) return true
        if (act(g).none { !passed.contains(it) }) {
            startRound(g, pid)
            return true
        }
        g.turn = nextTurn(g, passed)
        return true
    }
    g.turn = nextTurn(g, passed)
    if (g.order[g.turn] == pid) startRound(g, pid)
    return true
}

// pilihan pemain dengan kartu murni (tanpa J, Q, K)
fun decide(g: Game, pid: Int, stop: Boolean) {
    if (g.over || !g.dec.contains(pid)) return
    g.dec.remove(pid)
    if (!stop) {
        addLog(g, LogE(pid, m = "kartu murni, lanjut main"))
        return
    }
    g.hands[pid] = ArrayList()
    g.stopped.add(pid)
    if (finish(g, pid, "kartu murni, berhenti: ")) return
    if (g.first) {
        val a = act(g)
        val pool = a.filter { has3(g, it) }
        val src = if (pool.isNotEmpty()) pool else a
        val low = src.minOf { p -> g.hands[p].minOrNull() ?: 99 }
        g.st = g.order.first { p -> g.hands[p].contains(low) }
        g.turn = g.order.indexOf(g.st)
        g.first = pool.isNotEmpty()
    } else if (g.order[g.turn] == pid) {
        startRound(g, pid)
    }
}

// lead = juara 1 game sebelumnya (jalan duluan, bebas kombinasi). Tanpa lead: pemegang kartu terendah buka.
fun deal(pids: List<Int>, lead: Int?): Game {
    val d = (0 until 52).shuffled()
    val hands: Array<MutableList<Int>> = Array(pids.size) { i -> d.subList(i * 13, i * 13 + 13).sorted().toMutableList() }
    val g = Game(pids, hands)
    for (i in pids.indices) {
        if (hands[i].none { rk(it) in 8..10 }) g.dec.add(pids[i]) // murni: nol J, Q, K
    }
    val q2i = pids.indices.firstOrNull { i -> hands[i].count { rk(it) == 12 } == 4 }
    if (q2i != null) { // dapat 4 kartu 2 = kalah instan
        g.over = true
        g.quad2 = pids[q2i]
        g.st = pids[q2i]
        g.dec.clear()
        return g
    }
    var start = if (lead == null) -1 else pids.indexOf(lead)
    var low = -1
    var col = false
    if (start < 0) {
        low = hands.minOf { it[0] }
        start = hands.indexOfFirst { it.contains(low) }
        col = low < 4
    }
    g.s = low
    g.st = pids[start]
    g.turn = start
    g.first = col
    return g
}

// ---------- bot: 0 Mudah, 1 Sedang, 2 Susah, 3 Master ----------
val LEVELS = arrayOf("Mudah", "Sedang", "Susah", "Master")

fun pickK(a: List<Int>, k: Int): List<List<Int>> {
    val res = ArrayList<List<Int>>()
    fun f(i: Int, cur: ArrayList<Int>) {
        if (cur.size == k) { res.add(ArrayList(cur)); return }
        for (j in i until a.size) {
            cur.add(a[j])
            f(j + 1, cur)
            cur.removeAt(cur.size - 1)
        }
    }
    f(0, ArrayList())
    return res
}

fun combos(hand: List<Int>): List<Opt> {
    val c = hand.sorted()
    val by = java.util.TreeMap<Int, MutableList<Int>>()
    for (x in c) by.getOrPut(rk(x)) { ArrayList() }.add(x)
    val out = ArrayList<List<Int>>()
    val prs = ArrayList<List<Int>>()
    val sers = ArrayList<List<Int>>()
    val rs = by.keys.toList()
    for (r in rs) {
        val grp = by[r]!!
        for (k in 1..grp.size) {
            for (x in pickK(grp, k)) {
                out.add(x)
                if (k == 2) prs.add(x)
            }
        }
    }
    for (s in 0..3) {
        val cs = c.filter { su(it) == s && rk(it) < 8 }
        for (i in cs.indices) {
            var j = i + 2
            while (j < cs.size && rk(cs[j]) - rk(cs[i]) == j - i) {
                sers.add(cs.subList(i, j + 1).toList())
                j++
            }
        }
    }
    for (s in 0..3) { val q = listOf(8, 9, 10).map { it * 4 + s }; if (c.containsAll(q)) out.add(q) } // seri spesial J Q K satu logo
    out.addAll(sers)
    for (r in rs) {
        val b = by[r]!!
        if (b.size >= 3) {
            for (t in pickK(b, 3)) for (p in prs) if (rk(p[0]) != r) out.add(t + p)
        }
        if (b.size == 4) {
            for (p in prs) if (rk(p[0]) != r) out.add(b + p)
            for (r2 in rs) if (r2 > r && by[r2]!!.size == 4) out.add(b + by[r2]!!)
        }
    }
    for (sr in sers) for (p in prs) if (p.none { sr.contains(it) }) out.add(sr + p)
    for (s in 0..3) { val q = listOf(8, 9, 10).map { it * 4 + s }; if (c.containsAll(q)) for (p in prs) if (p.none { q.contains(it) }) out.add(q + p) } // seri spesial J Q K + pasangan
    val res = ArrayList<Opt>()
    for (cs in out) {
        val e = ev(cs)
        if (e != null) res.add(Opt(cs, e))
    }
    return res
}

fun unseenOf(g: Game, pid: Int): List<Int> {
    val seen = HashSet<Int>(g.hands[pid])
    for (e in g.log) e.c?.let { seen.addAll(it) }
    return (0 until 52).filter { !seen.contains(it) }
}

fun bombPossible(u: List<Int>): Boolean {
    val n = HashMap<Int, Int>()
    for (x in u) n[rk(x)] = (n[rk(x)] ?: 0) + 1
    if (n.values.any { it == 4 }) return true
    for (s in 0..3) {
        val ids = u.filter { su(it) == s }.map { rk(it) }.toSet()
        for (st in 0..3) if ((0..4).all { ids.contains(st + it) }) return true
    }
    return false
}

fun turnsLeft(hand: List<Int>): Int {
    var h = hand
    var n = 0
    while (h.isNotEmpty() && n < 14) {
        var b: List<Int>? = null
        var bk = 0
        for (o in combos(h)) {
            if (!isBomb(o.e) && !isDouble(o.e) && (b == null || o.cs.size > b.size || (o.cs.size == b.size && o.e.k < bk))) {
                b = o.cs
                bk = o.e.k
            }
        }
        val use: List<Int> = b ?: listOf(h[0])
        h = h.filter { !use.contains(it) }
        n++
    }
    return n
}

fun botMove(g: Game, pid: Int, lv: Int): BotMove {
    val hand = g.hands[pid]
    val last = g.last
    if (g.first) return BotMove(hand.filter { rk(it) == 0 }, false)
    val leg = combos(hand).filter { cuts(it.e, last) || beats(it.e, last) }
    if (leg.isEmpty()) return BotMove(null, true)
    fun has2(o: Opt): Boolean = o.cs.any { rk(it) == 12 }
    fun bomb(o: Opt): Boolean = isBomb(o.e) || isDouble(o.e)
    val cutters: List<Opt> = if (last != null && twoPlay(last)) leg.filter { cuts(it.e, last) } else emptyList()
    if (cutters.isNotEmpty() && (lv > 0 || Random.nextDouble() < .5)) {
        return BotMove(cutters.sortedBy { it.cs.size }[0].cs, false) // bom motong 2 = menang
    }
    if (lv > 0) {
        val fin = leg.firstOrNull { it.cs.size == hand.size }
        if (fin != null) return BotMove(fin.cs, false)
    }
    if (lv == 0) {
        if (last != null && Random.nextDouble() < .35) return BotMove(null, true)
        return BotMove(leg[Random.nextInt(leg.size)].cs, false)
    }
    val others = act(g).filter { it != pid }.map { g.hands[it].size }
    val minOpp = minOf(99, others.minOrNull() ?: 99)
    val danger = minOpp <= 2
    val cnt = HashMap<Int, Int>()
    for (c in hand) cnt[rk(c)] = (cnt[rk(c)] ?: 0) + 1
    val un: List<Int>? = if (lv == 3) unseenOf(g, pid) else null
    val bp = if (un != null) bombPossible(un) else true
    var best: Opt? = null
    var bs = 1e9
    for (o in leg) {
        val rs = HashMap<Int, Int>()
        for (c in o.cs) rs[rk(c)] = (rs[rk(c)] ?: 0) + 1
        var s = (o.e.k shr 2).toDouble()
        if (lv == 1) {
            if (has2(o) || bomb(o)) s += 20.0
        } else {
            if (has2(o)) s += if (lv == 3) (if (bp) 30.0 else 4.0) else 12.0 // 2 yang keluar bisa dipotong bom
            if (bomb(o) && !(last != null && twoPlay(last))) s += if (danger) 0.0 else 14.0 // bom disimpen buat motong 2
            for ((r, c0) in rs) {
                val total = cnt[r] ?: 0
                val rem = total - c0
                if (rem > 0 && total >= 2) s += if (total >= 3) 5.0 else 3.0 // jangan pecahin pasangan / tiga kembar
            }
            s -= o.cs.size * (if (lv == 3) 2.5 else 1.5)
            if (last == null && lv >= 2) {
                if (minOpp == 1 && o.cs.size == 1) s += 8.0
                if (minOpp == 2 && o.cs.size == 2) s += 5.0
            }
            if (last == null && lv == 3 && o.e.t == 's' && un != null) {
                val k = o.e.k
                val sure = if (rk(k) == 12) !bp else !un.any { rk(it) > rk(k) }
                if (sure) s -= 5.0 // kartu yang pasti nggak kebales
            }
            if (last != null && last.n == 1 && minOpp == 1) s = -o.e.k.toDouble() // lawan sisa 1 kartu: keluarin yang tertinggi
        }
        o.s = s
        if (s < bs) {
            bs = s
            best = o
        }
    }
    if (lv == 3) { // master: dari 6 kandidat terbaik, pilih yang bikin sisa kartu paling cepat habis
        var bb = 1e9
        for (o in leg.sortedBy { it.s }.take(6)) {
            val v = o.s + 8 * turnsLeft(hand.filter { !o.cs.contains(it) })
            if (v < bb) {
                bb = v
                best = o
            }
        }
    }
    val b = best ?: return BotMove(null, true)
    if (last != null && cutters.isEmpty() && (has2(b) || bomb(b)) && !(danger || hand.size <= 4) && (lv >= 2 || Random.nextDouble() < .7)) {
        return BotMove(null, true)
    }
    return BotMove(b.cs, false)
}

fun botDecide(lv: Int): Boolean = if (lv == 0) Random.nextDouble() < .5 else true

// pesan kenapa kartu yang dipilih pemain nggak valid ("" = valid)
fun why(g: Game, me: Int, cards: List<Int>): String {
    val hand = g.hands[me]
    val last = g.last
    val e = ev(cards)
    if (!cards.all { hand.contains(it) }) return "Kartu itu nggak ada di tangan kamu."
    if (g.first) {
        val m3 = hand.filter { rk(it) == 0 }
        return if (cards.size == m3.size && m3.all { cards.contains(it) }) "" else "Fase kumpulin 3: keluarin semua kartu 3 kamu sekaligus (" + m3.size + " kartu)."
    }
    if (e == null) {
        return if (cards.size > 2) "Bukan kombinasi valid. Seri harus satu logo semua (3+ kartu berurutan, angka 3 sampai 10), atau seri spesial J Q K satu logo (boleh + pasangan). Lainnya: pasangan, 3 kembar, 3 kembar + pasangan, seri + pasangan." else "Dua kartu harus angka sama (pasangan)."
    }
    if (cuts(e, last)) return ""
    if (last != null && twoPlay(last)) {
        return if (last.c.size == 1) "Kartu 2 nggak bisa dibales kartu biasa. Cuma bisa lewat atau dipotong bom (4 kembar, seri 5 satu logo, atau bom x2)." else "Kartu 2 kembar nggak bisa dibales kartu biasa. Cuma bisa lewat atau dipotong bom x2 (8 kartu kembar)."
    }
    if (last != null && !beats(e, last)) {
        if (e.t != last.t || e.n != last.n) {
            return "Harus jenis dan jumlah kartu yang sama: " + (if (isJqk(last)) jqkName(last).lowercase() else (TYPE_NAME[last.t] ?: "") + (if (last.t == 'r') " " + last.n + " kartu" else "") + "."
        }
        return if (e.t == 'f' || e.t == 'x' || e.t == 'g') "Bagian utamanya (3 kembar / seri / 4 kembar) harus lebih tinggi dari yang di meja. Pasangannya bebas." else "Kartunya kurang tinggi dari yang di meja."
    }
    return ""
}

// urutan tampil tangan: tunggal, pasangan, 3 kembar, seri, 4 kembar (kiri ke kanan, kecil ke besar)
fun groupHand(hs: List<Int>): List<Int> {
    val by = java.util.TreeMap<Int, MutableList<Int>>()
    for (c in hs) by.getOrPut(rk(c)) { ArrayList() }.add(c)
    val used = HashSet<Int>()
    val gs = ArrayList<List<Int>>(); val gp = ArrayList<List<Int>>(); val gt = ArrayList<List<Int>>()
    val gr = ArrayList<List<Int>>(); val gq = ArrayList<List<Int>>()
    for ((_, l) in by) if (l.size == 4) { gq.add(l.toList()); used.addAll(l) }
    for ((_, l) in by) { val a = l.filter { !used.contains(it) }; if (a.size == 3) { gt.add(a); used.addAll(a) } }
    for (sx in 0..3) {
        var run = ArrayList<Int>()
        for (r in 0..8) {
            val c = r * 4 + sx
            if (r <= 7 && hs.contains(c) && !used.contains(c)) run.add(c)
            else {
                if (run.size >= 3) { gr.add(run); used.addAll(run) }
                run = ArrayList()
            }
        }
    }
    for (sx in 0..3) { val q = listOf(8, 9, 10).map { it * 4 + sx }; if (q.all { hs.contains(it) && !used.contains(it) }) { gr.add(q); used.addAll(q) } } // seri spesial J Q K
    for ((_, l) in by) { val a = l.filter { !used.contains(it) }; if (a.size == 2) { gp.add(a); used.addAll(a) } }
    for (c in hs.filter { !used.contains(it) }.sorted()) gs.add(listOf(c))
    val res = ArrayList<Int>()
    for (grp in listOf(gs, gp, gt, gr, gq)) for (a in grp.sortedBy { it[0] }) res.addAll(a)
    return res
}
