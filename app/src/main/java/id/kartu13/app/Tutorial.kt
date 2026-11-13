package id.kartu13.app

// Tahap 2.6: isi tutorial. Port dari `TUT` dan `GUIDE` di game.js (v44.11).
// Teks pakai tag <b> sederhana (dirender lewat Html.fromHtml). Kartu ditulis "5♠", "10♦", dst.

class Slide(
    val h: String,
    val p: List<String>,
    val ex: List<Pair<String, List<String>>> = emptyList(),
    val p2: List<String> = emptyList(),
    val practice: Boolean = false
)

class Step(
    val h: String,
    val t: String,
    val hand: List<String> = emptyList(),
    val table: Pair<String, List<String>>? = null,
    val play: List<String> = emptyList(),
    val pass: Boolean = false,
    val any: Boolean = false,
    val bad: Boolean = false,
    val ok: String = "",
    val done: Boolean = false
) {
    val act: Boolean get() = play.isNotEmpty() || pass || any
}

fun cid(t: String): Int = RANKS.indexOf(t.dropLast(1)) * 4 + "♠♣♦♥".indexOf(t.last())

val TUT: List<Slide> = listOf(
    Slide(
        "Tujuan dan urutan kartu",
        listOf("Tiap pemain dapat 13 kartu. Siapa yang kartunya habis duluan jadi juara 1.", "Urutan dari kecil ke besar: 3 4 5 6 7 8 9 10 J Q K A 2. Kartu 2 paling tinggi.", "Logo (♠ ♣ ♦ ♥) nggak dihitung buat membandingkan. Angka sama nggak bisa saling kalahin: 9 9 nggak bisa dilawan 9 9. Logo cuma dipakai di awal game buat nentuin 3♠."),
        ex = listOf("Contoh urutan" to listOf("3♠", "7♣", "10♦", "K♥", "A♠", "2♣"))
    ),
    Slide(
        "Kombinasi dasar",
        listOf("Lo bisa keluarin satu jenis kombinasi tiap giliran:"),
        ex = listOf("Tunggal (1 kartu)" to listOf("9♦"),
            "Pasangan (2 angka sama)" to listOf("5♦", "5♥"),
            "3 kembar (3 angka sama)" to listOf("8♠", "8♣", "8♦"))
    ),
    Slide(
        "Seri",
        listOf("Seri = minimal 3 kartu berurutan, wajib satu logo, dan cuma angka 3 sampai 10 (nggak boleh J, Q, K, A, 2). Seri terpanjang 8 kartu."),
        ex = listOf("✅ Seri sah" to listOf("5♦", "6♦", "7♦"),
            "❌ Beda logo" to listOf("5♦", "6♥", "7♦"),
            "❌ Ada J (di atas 10)" to listOf("9♠", "10♠", "J♠"))
    ),
    Slide(
        "Kombinasi + pasangan",
        listOf("Ada 3 jenis yang ditambah 1 pasangan (5 kartu atau lebih):"),
        ex = listOf("3 kembar + pasangan" to listOf("4♠", "4♣", "4♦", "5♠", "5♥"),
            "Seri + pasangan" to listOf("3♠", "4♠", "5♠", "7♣", "7♦"),
            "4 kembar + pasangan" to listOf("9♠", "9♣", "9♦", "9♥", "J♠", "J♥")),
        p2 = listOf("Pasangannya bebas angkanya, boleh sama dengan angka di serinya, asal kembar.")
    ),
    Slide(
        "Cara melawan kartu di meja",
        listOf("Lo harus keluarin kombinasi yang sejenis, jumlah kartunya sama, dan lebih tinggi. Atau pilih Lewat.", "Untuk kombinasi + pasangan, yang dibandingkan cuma bagian utamanya (3 kembar, seri, atau 4 kembar). Pasangannya bebas."),
        ex = listOf("Meja: pasangan 5. Lo boleh:" to listOf("7♠", "7♣"),
            "Meja: 4 4 4 + 5 5. Lo boleh:" to listOf("6♠", "6♣", "6♦", "4♥", "4♣"),
            "Meja: seri 3♠ 4♠ 5♠. Seri beda logo boleh:" to listOf("6♦", "7♦", "8♦"))
    ),
    Slide(
        "Lewat dan jalannya ronde",
        listOf("Lewat cuma berlaku untuk giliran itu. Kalau ada yang keluarin kartu lagi, semua pemain dapat giliran lagi.", "Kalau semua pemain lain lewat, pemain terakhir yang keluarin kartu buka ronde baru dengan kombinasi bebas.", "Giliran bergerak ke arah kanan.")
    ),
    Slide(
        "Kartu 2",
        listOf("Kartu 2 paling tinggi. Kartu 2 nggak bisa dibales kartu biasa, termasuk kartu 2 lain. Pemain lain cuma bisa Lewat atau memotongnya pakai bom.", "Ini berlaku juga untuk pemain yang tadi sudah lewat di ronde itu: mereka dapat kesempatan memotong."),
        ex = listOf("Contoh 2 tunggal" to listOf("2♣"),
            "2 kembar (pasangan 22)" to listOf("2♠", "2♥"))
    ),
    Slide(
        "Bom",
        listOf("Bom biasa: 4 kartu kembar, atau seri 5 kartu satu logo. Masing-masing boleh ditambah 1 pasangan.", "Seri 3 atau 4 kartu bukan bom. Seri 5 kartu atau lebih yang satu logo otomatis jadi bom seri (angka 3 sampai 10 saja).", "Bom x2: 8 kartu kembar (dua set 4 kembar)."),
        ex = listOf("4 kembar" to listOf("6♠", "6♣", "6♦", "6♥"),
            "Bom seri: 5 kartu satu logo" to listOf("6♥", "7♥", "8♥", "9♥", "10♥"),
            "Bom seri + pasangan" to listOf("3♦", "4♦", "5♦", "6♦", "7♦", "J♠", "J♣"),
            "Bom x2: 3333 + 6666" to listOf("3♠", "3♣", "3♦", "3♥", "6♠", "6♣", "6♦", "6♥"))
    ),
    Slide(
        "Bom memotong kartu 2",
        listOf("2 tunggal di meja bisa dipotong bom biasa atau bom x2. Pasangan 22, 3 kembar 2, atau 4 kartu 2 cuma bisa dipotong bom x2.", "Bom yang memotong kartu 2 bikin game langsung berakhir dan yang keluarin bom menang.", "Bom yang keluar bukan di atas kartu 2 cuma kombinasi biasa. Game lanjut, dan bom biasa cuma bisa dilawan jenis yang sama dengan nilai lebih tinggi."),
        ex = listOf("Meja: 2 tunggal. Dipotong:" to listOf("5♠", "5♣", "5♦", "5♥"))
    ),
    Slide(
        "Dua cara kalah instan",
        listOf("Ngebom diri sendiri: kalau kartu terakhir lo (yang bikin kartu habis) mengandung kartu 2, lo kalah. Game dibatalkan, nggak ada pemenang, dan game berikutnya mulai dari fase kumpulin 3.", "Dapat 4 kartu 2 di tangan awal: langsung kalah, game diulang dari fase kumpulin 3."),
        ex = listOf("Kartu terakhir ini bikin lo kalah:" to listOf("2♠"))
    ),
    Slide(
        "Awal game dan fase kumpulin 3",
        listOf("Game pertama di room (atau setelah game batal karena bom memotong 2 atau kalah instan) mulai dengan fase kumpulin 3: semua pemain yang punya angka 3 keluarin semua kartu 3-nya sekaligus.", "Setelah semua 3 keluar, pemain yang keluarin 3♠ jalan duluan. Kalau 3♠ nggak kebagi, pemegang 3 terendah.", "Di game biasa setelah ada juara, juara 1 jalan duluan dengan kombinasi bebas."),
        ex = listOf("Kartu 3" to listOf("3♠", "3♣", "3♦", "3♥"))
    ),
    Slide(
        "Kartu murni dan juara",
        listOf("Kartu murni = nggak dapat J, Q, K sama sekali. Di awal game lo bisa pilih Berhenti atau Lanjut main. Berhenti bikin lo langsung jadi juara berikutnya (juara 1 kalau yang pertama), dan game tetap lanjut.", "Juara lain harus ngabisin kartunya beneran. Juara terakhir otomatis yang masih punya sisa kartu."),
        ex = listOf("Contoh tangan murni (tanpa J, Q, K)" to listOf("3♠", "5♦", "7♥", "9♣", "A♠", "2♦"))
    ),
    Slide(
        "Mengatur kartu dan tombol",
        listOf("Kartu lo nggak diurutin otomatis. Tahan kartu sebentar lalu geser ke posisi baru. Tombol Kelompokkan merapikan jadi tunggal, pasangan, 3 kembar, seri, lalu 4 kembar.", "Pilih kartu dengan menekannya, lalu tekan Mainkan. Salah pilih? Tekan Batal pilih.")
    ),
    Slide(
        "Lawan bot",
        listOf("Lo lawan 3 bot. Tingkat: Mudah (main acak), Sedang (kartu terendah dulu, simpan bom dan 2), Susah (jaga pasangan dan 3 kembar), Master (ngitung kartu yang belum keluar).", "Aturannya sama persis dengan versi web. Coba dulu di tingkat Mudah buat latihan."),
        practice = true
    )
)

val GUIDE: List<Step> = listOf(
    Step(
        "Selamat datang",
        "Tujuan game ini: habiskan 13 kartu di tangan lo duluan. Di tutorial ini lo latihan langkah demi langkah dengan kartu contoh. Kalau salah, nggak apa-apa, nanti dijelasin kenapa."
    ),
    Step(
        "Keluarin kartu pertama",
        "Meja kosong, jadi lo yang buka ronde dan bebas pilih. <b>Tekan kartu 5♠</b> (kartunya naik), lalu tekan <b>Mainkan</b>.",
        hand = listOf("4♣", "5♠", "8♦", "9♥", "J♣"),
        play = listOf("5♠"),
        ok = "Mantap! 5♠ keluar sebagai tunggal (1 kartu)."
    ),
    Step(
        "Ngelawan tunggal",
        "Bot Budi keluarin 9♦. Lo harus keluarin <b>1 kartu yang angkanya lebih tinggi dari 9</b>. Urutan: 3 4 5 6 7 8 9 10 J Q K A 2. Logo (♠ ♣ ♦ ♥) nggak dihitung.",
        hand = listOf("6♣", "8♠", "J♣", "K♦"),
        table = "Bot Budi" to listOf("9♦"),
        any = true,
        ok = "Betul! Kartu yang lebih tinggi bisa ngalahin kartu di meja."
    ),
    Step(
        "Pasangan",
        "Bot Sari keluarin pasangan 6 (2 kartu angka sama). Lawan dengan <b>pasangan yang lebih tinggi</b>. Pilih dua kartu, lalu Mainkan.",
        hand = listOf("3♦", "7♥", "7♠", "10♣", "Q♦"),
        table = "Bot Sari" to listOf("6♠", "6♣"),
        any = true,
        ok = "Pas! Pasangan 7 lebih tinggi dari pasangan 6. Aturan penting: harus sejenis dan jumlah kartunya sama."
    ),
    Step(
        "3 kembar",
        "Meja kosong lagi. Keluarin <b>3 kartu angka sama</b>: ketiga kartu 4.",
        hand = listOf("4♠", "4♣", "4♦", "9♥", "K♠"),
        play = listOf("4♠", "4♣", "4♦"),
        ok = "Bagus! Itu 3 kembar."
    ),
    Step(
        "Seri",
        "<b>Seri</b> = minimal 3 kartu berurutan dan <b>wajib satu logo</b>. Cuma angka 3 sampai 10. Keluarin seri 5♦ 6♦ 7♦ (hati-hati, ada 6♥ yang logonya beda).",
        hand = listOf("5♦", "6♥", "6♦", "7♦", "9♣", "K♠"),
        play = listOf("5♦", "6♦", "7♦"),
        ok = "Tepat! Seri satu logo. 5♦ 6♥ 7♦ nggak sah karena beda logo."
    ),
    Step(
        "Kombinasi + pasangan",
        "Bot Joko keluarin <b>3 kembar 4 + pasangan 5</b> (5 kartu). Lawan dengan 5 kartu yang sejenis: <b>3 kembar yang lebih tinggi + pasangan apa saja</b>. Yang dibandingkan cuma 3 kembarnya.",
        hand = listOf("6♠", "6♣", "6♦", "J♥", "J♣", "3♠"),
        table = "Bot Joko" to listOf("4♠", "4♣", "4♦", "5♠", "5♥"),
        any = true,
        ok = "Keren! 6 6 6 lebih tinggi dari 4 4 4, dan pasangannya (J J) bebas."
    ),
    Step(
        "Kapan harus Lewat",
        "Bot Budi keluarin pasangan K. Lo nggak punya pasangan sama sekali. Kalau nggak ada yang bisa ngalahin, <b>tekan Lewat</b>.",
        hand = listOf("3♣", "5♦", "8♠", "9♥", "J♦"),
        table = "Bot Budi" to listOf("K♠", "K♥"),
        pass = true,
        ok = "Oke. Lewat cuma berlaku untuk giliran itu. Kalau ada yang keluarin kartu lagi, lo dapat giliran lagi. Kalau semua lewat, yang terakhir keluarin kartu buka ronde baru."
    ),
    Step(
        "Kartu 2",
        "Bot Sari keluarin <b>2♣</b>. Kartu 2 paling tinggi, dan nggak bisa dibales kartu biasa (A pun nggak bisa). Cuma bom yang bisa motong. Lo nggak punya bom, jadi <b>tekan Lewat</b>.",
        hand = listOf("3♦", "7♠", "A♥", "K♣"),
        table = "Bot Sari" to listOf("2♣"),
        pass = true,
        ok = "Betul. Kartu 2 kuat banget, tapi punya kelemahan: bisa dipotong bom."
    ),
    Step(
        "Bom memotong 2",
        "Bot Joko keluarin 2♣ lagi, tapi sekarang lo punya <b>4 kembar 5 = bom</b>. Bom bisa motong 2 tunggal. Pilih keempat 5, lalu Mainkan.",
        hand = listOf("5♠", "5♣", "5♦", "5♥", "8♠", "K♦"),
        table = "Bot Joko" to listOf("2♣"),
        play = listOf("5♠", "5♣", "5♦", "5♥"),
        ok = "Boom! Bom yang motong 2 bikin game langsung berakhir dan lo menang. Bom lain: seri 5 satu logo, dan bom x2 (8 kartu kembar, buat motong pasangan 22 atau 3 kembar 2)."
    ),
    Step(
        "Bom seri",
        "Seri biasa (3 atau 4 kartu) cuma kombinasi biasa. Tapi kalau seri-nya <b>5 kartu atau lebih dan satu logo</b>, itu otomatis <b>bom seri</b> (angka 3 sampai 10, boleh ditambah 1 pasangan). Bot Budi keluarin 2♣. Lo punya seri 6 sampai 10 satu logo hati. Pilih kelima kartu itu (bukan 6♠), lalu Mainkan.",
        hand = listOf("6♥", "7♥", "8♥", "9♥", "10♥", "6♠", "J♦"),
        table = "Bot Budi" to listOf("2♣"),
        play = listOf("6♥", "7♥", "8♥", "9♥", "10♥"),
        ok = "Boom! Bom seri motong 2 tunggal dan lo langsung menang, sama seperti 4 kembar. Kalau seri-nya beda logo, itu bukan bom dan nggak bisa motong 2."
    ),
    Step(
        "Jangan habisin pakai 2",
        "Sekarang kartu lo tinggal satu: <b>2♠</b>. Coba keluarin 2♠ dan lihat apa yang terjadi.",
        hand = listOf("2♠"),
        play = listOf("2♠"),
        bad = true,
        ok = "Lo kalah! Kalau kartu terakhir (yang bikin kartu habis) mengandung kartu 2, itu namanya ngebom diri sendiri: game dibatalkan dan nggak ada pemenang. Keluarin 2 selagi masih ada kartu lain, atau simpan buat nahan lawan."
    ),
    Step(
        "Awal game: kumpulin 3",
        "Game pertama (atau setelah game batal) mulai dengan <b>fase kumpulin 3</b>: semua pemain yang punya angka 3 keluarin semua kartu 3-nya sekaligus. Setelah itu, yang keluarin 3♠ jalan duluan. Kalau lo dapat 4 kartu 2 di awal, lo kalah instan dan game diulang."
    ),
    Step(
        "Kartu murni dan juara",
        "Kalau lo nggak dapat J, Q, K sama sekali (<b>kartu murni</b>), lo bisa pilih Berhenti: langsung jadi juara berikutnya dan game tetap lanjut. Pemain lain harus ngabisin kartunya beneran. Juara terakhir adalah yang masih punya sisa kartu."
    ),
    Step(
        "Selesai!",
        "Lo udah tahu semua dasarnya. Tips: kartu di tangan nggak diurutin otomatis. Tekan <b>Kelompokkan</b> buat merapikan, atau tahan lalu geser. Kalau lupa aturan, buka lagi menu <b>Tutorial</b> di layar depan.",
        done = true
    )
)
