package id.kartu13.app

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.media.SoundPool
import android.os.Process
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.tanh
import kotlin.random.Random

// Port dari modul Snd di game.js (v44.11): semua suara disintesis, tanpa file audio.
// Efek suara dirender sekali jadi WAV kecil di cache -> SoundPool. Musik disintesis live lewat AudioTrack.

private const val SR = 22050

// Balans volume musik (web: .55). Naikkan/turunkan kalau terlalu keras / pelan.
private const val MUSIC_GAIN = 0.55
private const val REVERB_WET = 0.5

// ---------------------------------------------------------------- filter biquad (RBJ cookbook)
private const val BP = 0
private const val HP = 1
private const val LP = 2

private class Bq(private val type: Int) {
    private var x1 = 0.0
    private var x2 = 0.0
    private var y1 = 0.0
    private var y2 = 0.0

    fun p(x: Double, f: Double): Double {
        val q = if (type == BP) 3.0 else 1.41
        val w = 2.0 * PI * f / SR
        val cw = cos(w)
        val al = sin(w) / (2.0 * q)
        val b0: Double
        val b1: Double
        val b2: Double
        when (type) {
            BP -> { b0 = al; b1 = 0.0; b2 = -al }
            HP -> { b0 = (1.0 + cw) / 2.0; b1 = -(1.0 + cw); b2 = b0 }
            else -> { b0 = (1.0 - cw) / 2.0; b1 = 1.0 - cw; b2 = b0 }
        }
        val a0 = 1.0 + al
        val a1 = -2.0 * cw
        val a2 = 1.0 - al
        val y = (b0 * x + b1 * x1 + b2 * x2 - a1 * y1 - a2 * y2) / a0
        x2 = x1; x1 = x
        y2 = y1; y1 = y
        return y
    }
}

// ---------------------------------------------------------------- render efek suara (sekali jalan)
private class Fx(secs: Double) {
    private val buf = FloatArray((secs * SR).toInt() + 1)
    private val rnd = Random(7)

    // nada sinus dengan sapuan frekuensi eksponensial f0 -> f1, volume vel -> ~0 eksponensial
    fun tone(t0: Double, f0: Double, f1: Double, dur: Double, vel: Double) {
        val s0 = (t0 * SR).toInt()
        val n = (dur * SR).toInt()
        var ph = 0.0
        for (i in 0 until n) {
            val k = i.toDouble() / n
            val f = f0 * (f1 / f0).pow(k)
            ph += 2.0 * PI * f / SR
            val g = vel * (0.0001 / vel).pow(k)
            val idx = s0 + i
            if (idx < buf.size) buf[idx] += (sin(ph) * g).toFloat()
        }
    }

    // derau terfilter dengan sapuan cutoff; attack 10 ms lalu meluruh
    fun noise(t0: Double, dur: Double, type: Int, f0: Double, f1: Double, vel: Double) {
        val s0 = (t0 * SR).toInt()
        val n = (dur * SR).toInt()
        val bq = Bq(type)
        val att = 0.01
        for (i in 0 until n) {
            val t = i.toDouble() / SR
            val f = f0 * (f1 / f0).pow(i.toDouble() / n)
            val g = if (t < att) 0.0001 * (vel / 0.0001).pow(t / att)
            else vel * (0.0001 / vel).pow((t - att) / (dur - att))
            val x = rnd.nextDouble() * 2.0 - 1.0
            val idx = s0 + i
            if (idx < buf.size) buf[idx] += (bq.p(x, f) * g).toFloat()
        }
    }

    fun out(): ShortArray = ShortArray(buf.size) { (tanh(buf[it].toDouble()) * 30000.0).toInt().toShort() }
}

private fun fxTick(up: Boolean): ShortArray {
    val fx = Fx(0.09)
    fx.noise(0.0, 0.05, BP, if (up) 2800.0 else 1800.0, if (up) 2200.0 else 1200.0, 0.4)
    fx.tone(0.0, if (up) 950.0 else 640.0, if (up) 700.0 else 480.0, 0.06, 0.12)
    return fx.out()
}

private fun fxPlay(n: Int): ShortArray {
    val c = n.coerceIn(1, 4)
    val fx = Fx(0.075 * (c - 1) + 0.3)
    for (i in 0 until c) {
        val u = i * 0.075
        fx.noise(u, 0.14, HP, 4200.0, 1400.0, 0.3)
        fx.tone(u + 0.06, 150.0, 55.0, 0.14, 0.38)
    }
    return fx.out()
}

private fun fxMsg(): ShortArray {
    val fx = Fx(0.3)
    fx.tone(0.0, 880.0, 880.0, 0.09, 0.08)
    fx.tone(0.11, 1175.0, 1175.0, 0.12, 0.08)
    return fx.out()
}

private fun fxPass(): ShortArray {
    val fx = Fx(0.16)
    fx.tone(0.0, 330.0, 220.0, 0.12, 0.1)
    return fx.out()
}

private fun fxBoom(): ShortArray {
    val fx = Fx(1.1)
    fx.tone(0.0, 95.0, 28.0, 0.9, 0.6)
    fx.noise(0.0, 0.7, LP, 900.0, 120.0, 0.5)
    val ch = doubleArrayOf(293.66, 349.23, 440.0)
    for (i in ch.indices) fx.tone(0.15 + i * 0.12, ch[i], ch[i], 0.7, 0.12)
    return fx.out()
}

private fun writeWav(f: File, s: ShortArray) {
    val bb = ByteBuffer.allocate(44 + s.size * 2).order(ByteOrder.LITTLE_ENDIAN)
    bb.put("RIFF".toByteArray())
    bb.putInt(36 + s.size * 2)
    bb.put("WAVE".toByteArray())
    bb.put("fmt ".toByteArray())
    bb.putInt(16)
    bb.putShort(1)          // PCM
    bb.putShort(1)          // mono
    bb.putInt(SR)
    bb.putInt(SR * 2)
    bb.putShort(2)
    bb.putShort(16)
    bb.put("data".toByteArray())
    bb.putInt(s.size * 2)
    for (v in s) bb.putShort(v)
    FileOutputStream(f).use { it.write(bb.array()) }
}

// ---------------------------------------------------------------- musik ambient (Dorian di D, kecapi + pad + drone)
private val SCALE = intArrayOf(0, 2, 3, 5, 7, 9, 10)
private const val D4 = 293.66
private val CHORDS = arrayOf(
    doubleArrayOf(146.83, 174.61, 220.0),  // Dm
    doubleArrayOf(130.81, 164.81, 196.0),  // C
    doubleArrayOf(98.0, 123.47, 146.83),   // G
    doubleArrayOf(110.0, 130.81, 164.81)   // Am
)
private val PROG = intArrayOf(0, 1, 0, 3, 2, 0, 1, 3)
private val STEPS = intArrayOf(-2, -1, -1, 0, 1, 1, 2)
private val DURS = doubleArrayOf(1.2, 1.8, 2.4)
private val GAPS = doubleArrayOf(0.7, 0.7, 1.4, 1.4, 2.1)

private fun freqOf(d: Int): Double {
    val oct = Math.floorDiv(d, 7)
    val deg = ((d % 7) + 7) % 7
    return D4 * 2.0.pow((SCALE[deg] + 12 * oct) / 12.0)
}

private abstract class Voice {
    var delay = 0
    var dead = false
    abstract fun next(): Double
}

// petikan kecapi: segitiga + sinus oktaf, lowpass menutup 2600 -> 500 Hz, envelope eksponensial
private class Lute(private val f: Double, vel: Double, dur: Double) : Voice() {
    private val att = (0.01 * SR).toInt()
    private val dn = max(2, (dur * SR).toInt() - att)
    private val total = att + dn + (0.05 * SR).toInt()
    private val ra = (vel / 0.0001).pow(1.0 / att)
    private val rd = (0.0001 / vel).pow(1.0 / dn)
    private val rc = (500.0 / 2600.0).pow(1.0 / (att + dn))
    private var env = 0.0001
    private var cut = 2600.0
    private var ph1 = 0.0
    private var ph2 = 0.0
    private var lp = 0.0
    private var i = 0

    override fun next(): Double {
        if (i >= total) { dead = true; return 0.0 }
        i++
        env *= if (i <= att) ra else rd
        ph1 += f / SR; if (ph1 >= 1.0) ph1 -= 1.0
        ph2 += 2.0 * f / SR; if (ph2 >= 1.0) ph2 -= 1.0
        val tri = 2.0 * abs(2.0 * (ph1 - floor(ph1 + 0.5))) - 1.0
        val x = tri + 0.25 * sin(2.0 * PI * ph2)
        cut *= rc
        val a = 1.0 - exp(-2.0 * PI * cut / SR)
        lp += a * (x - lp)
        return lp * env
    }
}

// akor panjang lembut: 3 segitiga sedikit fals, satu lowpass 750 Hz
private class Pad(fs: DoubleArray, rnd: Random, private val dur: Double) : Voice() {
    private val inc = DoubleArray(fs.size) { fs[it] * 2.0.pow((rnd.nextDouble() - 0.5) * 12.0 / 1200.0) / SR }
    private val ph = DoubleArray(fs.size)
    private val v = 0.045 * fs.size
    private val total = (dur * SR).toInt()
    private val a = 1.0 - exp(-2.0 * PI * 750.0 / SR)
    private var lp = 0.0
    private var i = 0

    override fun next(): Double {
        if (i >= total) { dead = true; return 0.0 }
        val t = i.toDouble() / SR
        i++
        val g = when {
            t < 2.0 -> v * (t / 2.0)
            t < dur - 2.2 -> v
            else -> v * max(0.0, (dur - t) / 2.2)
        }
        var x = 0.0
        for (k in ph.indices) {
            ph[k] += inc[k]; if (ph[k] >= 1.0) ph[k] -= 1.0
            x += 2.0 * abs(2.0 * (ph[k] - floor(ph[k] + 0.5))) - 1.0
        }
        lp += a * (x - lp)
        return lp * g
    }
}

private class Comb(delaySec: Double, rt60: Double) {
    private val buf = DoubleArray((delaySec * SR).toInt())
    private val g = 10.0.pow(-3.0 * delaySec / rt60)
    private var pos = 0
    private var damp = 0.0

    fun p(x: Double): Double {
        val y = buf[pos]
        damp += 0.35 * (y - damp)
        buf[pos] = x + g * damp
        pos++; if (pos == buf.size) pos = 0
        return y
    }
}

private class Ap(delaySec: Double, private val g: Double) {
    private val buf = DoubleArray((delaySec * SR).toInt())
    private var pos = 0

    fun p(x: Double): Double {
        val b = buf[pos]
        val w = x + g * b
        buf[pos] = w
        pos++; if (pos == buf.size) pos = 0
        return -g * w + b
    }
}

private class Synth(seed: Long) {
    private val rnd = Random(seed)
    private val voices = ArrayList<Voice>()
    private val combs = arrayOf(Comb(0.0537, 1.5), Comb(0.0613, 1.5), Comb(0.0709, 1.5), Comb(0.0793, 1.5))
    private val aps = arrayOf(Ap(0.0051, 0.7), Ap(0.0017, 0.7))
    private var n = 0L                       // sampel yang sudah dirender
    private var mt = (0.1 * SR).toLong()     // waktu not berikutnya (sampel)
    private var pt = (0.1 * SR).toLong()     // waktu akor berikutnya
    private var deg = 3
    private var ci = 0
    private var d1 = 0.0
    private var d2 = 0.0
    private var l1 = 0.0
    private var l2 = 0.0
    private val lf1 = (0.08 + rnd.nextDouble() * 0.05) / SR
    private val lf2 = (0.08 + rnd.nextDouble() * 0.05) / SR

    private fun lute(f: Double, delay: Long, dur: Double, vel: Double) {
        val v = Lute(f, vel, dur)
        v.delay = max(0L, delay).toInt()
        voices.add(v)
    }

    private fun schedule(end: Long) {
        while (pt < end) {
            val p = Pad(CHORDS[PROG[ci++ % PROG.size]], rnd, 7.0)
            p.delay = max(0L, pt - n).toInt()
            voices.add(p)
            pt += 6L * SR
        }
        while (mt < end) {
            deg = (deg + STEPS[rnd.nextInt(STEPS.size)]).coerceIn(-2, 9)
            if (rnd.nextDouble() < 0.25) {
                mt += ((0.7 + rnd.nextDouble() * 0.6) * SR).toLong()
                continue
            }
            val dur = DURS[rnd.nextInt(DURS.size)]
            lute(freqOf(deg), mt - n, dur, 0.13)
            if (rnd.nextDouble() < 0.2) lute(freqOf(deg - 2), mt - n + (0.02 * SR).toLong(), dur, 0.06)
            mt += (GAPS[rnd.nextInt(GAPS.size)] * SR).toLong()
        }
    }

    fun render(out: ShortArray) {
        schedule(n + out.size)
        for (k in out.indices) {
            var dry = 0.0
            for (vi in 0 until voices.size) {
                val v = voices[vi]
                if (v.dead) continue
                if (v.delay > 0) { v.delay--; continue }
                dry += v.next()
            }
            // drone rendah dengan LFO pelan
            l1 += lf1; if (l1 >= 1.0) l1 -= 1.0
            l2 += lf2; if (l2 >= 1.0) l2 -= 1.0
            d1 += 73.42 / SR; if (d1 >= 1.0) d1 -= 1.0
            d2 += 110.0 / SR; if (d2 >= 1.0) d2 -= 1.0
            dry += sin(2.0 * PI * d1) * 0.06 * (1.0 + 0.5 * sin(2.0 * PI * l1))
            dry += sin(2.0 * PI * d2) * 0.035 * (1.0 + 0.5 * sin(2.0 * PI * l2))
            // reverb
            var w = 0.0
            for (c in combs) w += c.p(dry)
            w *= 0.25
            for (a in aps) w = a.p(w)
            val fade = min(1.0, (n + k).toDouble() / (1.5 * SR)) // masuk pelan, biar nggak kaget
            val s = tanh((dry + REVERB_WET * w) * MUSIC_GAIN) * fade
            out[k] = (s * 32000.0).toInt().toShort()
        }
        n += out.size
        voices.removeAll { it.dead }
    }
}

// ---------------------------------------------------------------- API yang dipakai GameView
class Snd(private val ctx: Context) {
    private val prefs = ctx.getSharedPreferences("k13", Context.MODE_PRIVATE)

    @Volatile
    var musicOn: Boolean = prefs.getBoolean("music", true)
        private set

    @Volatile
    var sfxOn: Boolean = prefs.getBoolean("sfx", true)
        private set

    @Volatile
    private var gen = 0
    private var musicRunning = false
    private var pool: SoundPool? = null
    private val ids = ConcurrentHashMap<String, Int>()
    private val ready = Collections.synchronizedSet(HashSet<Int>())

    init {
        try {
            val sp = SoundPool.Builder()
                .setMaxStreams(6)
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_GAME)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build()
                )
                .build()
            sp.setOnLoadCompleteListener { _, id, status -> if (status == 0) ready.add(id) }
            pool = sp
            Thread {
                try {
                    val all = HashMap<String, ShortArray>()
                    all["tu"] = fxTick(true)
                    all["td"] = fxTick(false)
                    for (i in 1..4) all["p$i"] = fxPlay(i)
                    all["msg"] = fxMsg()
                    all["pass"] = fxPass()
                    all["boom"] = fxBoom()
                    for ((name, data) in all) {
                        val f = File(ctx.cacheDir, "sfx_$name.wav")
                        writeWav(f, data)
                        ids[name] = sp.load(f.absolutePath, 1)
                    }
                } catch (_: Throwable) {
                }
            }.start()
        } catch (_: Throwable) {
            pool = null
        }
    }

    private fun fire(name: String) {
        if (!sfxOn) return
        val sp = pool ?: return
        val id = ids[name] ?: return
        if (!ready.contains(id)) return
        try {
            sp.play(id, 1f, 1f, 1, 0, 1f)
        } catch (_: Throwable) {
        }
    }

    fun tick(up: Boolean) = fire(if (up) "tu" else "td")
    fun play(n: Int) = fire("p" + n.coerceIn(1, 4))
    fun pass() = fire("pass")
    fun msg() = fire("msg")
    fun boom() = fire("boom")

    fun setMusic(on: Boolean) {
        musicOn = on
        prefs.edit().putBoolean("music", on).apply()
        if (on) startMusic() else stopMusic()
    }

    fun setSfx(on: Boolean) {
        sfxOn = on
        prefs.edit().putBoolean("sfx", on).apply()
    }

    // dipanggil dari Activity.onResume / onPause / onDestroy
    fun resume() { if (musicOn) startMusic() }
    fun pause() { stopMusic() }
    fun release() {
        stopMusic()
        try { pool?.release() } catch (_: Throwable) {}
        pool = null
    }

    private fun startMusic() {
        if (musicRunning) return
        musicRunning = true
        val my = ++gen
        Thread { musicLoop(my) }.also { it.isDaemon = true }.start()
    }

    private fun stopMusic() {
        musicRunning = false
        gen++
    }

    private fun musicLoop(my: Int) {
        var track: AudioTrack? = null
        try {
            Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
            val minBuf = AudioTrack.getMinBufferSize(SR, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT)
            val t = AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_GAME)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                        .build()
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(SR)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .build()
                )
                .setBufferSizeInBytes(max(minBuf, 4096) * 2)
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build()
            track = t
            t.play()
            val synth = Synth(System.nanoTime())
            val chunk = ShortArray(1024)
            while (gen == my) {
                synth.render(chunk)
                if (t.write(chunk, 0, chunk.size) < 0) break
            }
        } catch (_: Throwable) {
        } finally {
            try { track?.pause(); track?.flush() } catch (_: Throwable) {}
            try { track?.release() } catch (_: Throwable) {}
        }
    }
}
