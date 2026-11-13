# Kartu 13 (Android, native)

Port native Kotlin dari game web "Kartu 13 / Poker 13". Tanpa WebView; Firebase SDK resmi dipakai untuk multiplayer.

## Status
Tahap 1, 2, 2.5, dan 2.6 selesai. Implementasi awal Tahap 3a sudah ditambahkan; lihat batasan yang masih tersisa di bawah. Acuan fitur: web `index.html` + `game.js` v44.11 (tanda ✅ = sudah ada di app Android, ⬜ = belum).

Prinsip: tanpa WebView. Setiap tahap harus bisa di-build dan dimainkan sendiri sebelum lanjut ke tahap berikutnya.

## Roadmap

### ✅ Tahap 1: main lawan bot
- ✅ Mesin aturan lengkap (port `ev`, `beats`, `cuts`, `move`, `decide`, `deal`)
- ✅ Bot 4 level (Mudah, Sedang, Susah, Master)
- ✅ Halaman aturan (versi teks)
- ✅ Gambar kartu asli (webp, Vecteezy)

### ✅ Tahap 2: suara
- ✅ Efek suara: pilih kartu, mainkan 1-4 kartu, lewat, bom (+ nada pesan chat, siap dipakai Tahap 3)
- ✅ Musik ambient abad pertengahan (sintesis, tanpa file audio)
- ✅ Tombol Musik / Suara (menu dan layar main), pilihan tersimpan

### ✅ Tahap 2.5: animasi dan kenyamanan main (offline, lawan bot)
- ✅ Kartu meluncur dari pemain ke meja sebagai punggung kartu, lalu dibalik satu-satu (`flyIn`)
- ✅ Kartu naik-turun halus saat dipilih, kartu dibagi dari tengah meja di awal game
- ✅ Efek bom: layar goyang, kilat, dan gelombang cincin
- ✅ Atur kartu manual: tahan sebentar lalu seret kartu ke posisi baru (hasil port `onHandDown` / `dragFrame`)
- ✅ Kartu tidak lagi diurutkan otomatis: dibagi acak seperti dealer, sama seperti web
- ✅ Tombol **Kelompokkan** (`groupHand`) dan **Batal pilih**
- ✅ Notif "Rapikan kartu dulu" 30 detik di awal game (`arnShow`)
- ✅ Getar tipis saat giliran kamu (`turnBuzz`, izin `VIBRATE`)
- ✅ Opsi "Kartu: besar / normal" di menu depan (`tb`)
- ✅ Latar pakai cover SVG (`res/drawable/bg_cover_portrait.xml` dan `bg_cover_landscape.xml`, hasil konversi dari `cover/*.svg`), digelapkan lewat gradasi + vignette di `GameView.onSizeChanged`
- ✅ Kartu tersisa (gambar punggung + "x N") di kursi lawan dihapus, kursi jadi lebih pendek

### ✅ Tahap 2.6: tutorial
- ✅ Tutorial geser dengan contoh kartu asli (`TUT`, 14 halaman, bisa digeser kiri / kanan atau pakai tombol)
- ✅ Tutorial terpandu: 15 latihan langkah demi langkah, kombinasi dicek pakai mesin aturan asli (`GUIDE`, `gStep`, `gPlay`)
- ✅ Menu tutorial: pilih terpandu, aturan geser, atau ringkasan teks (`openTut`); tombol "Aturan main" di layar depan jadi "Tutorial"
- ✅ Teks tutorial disesuaikan buat Android (tombol Mainkan, tanpa petunjuk keyboard PC). Isi ada di `Tutorial.kt`

### 🟡 Tahap 3: multiplayer Firebase (2 sampai 4 pemain)
Implementasi awal **3a** sudah masuk ke source:
- ✅ Firebase Realtime Database SDK resmi, konfigurasi memakai Firebase project yang sama dengan web.
- ✅ Nama pemain maksimal 14 karakter dan disimpan di SharedPreferences.
- ✅ ID pemain lokal tetap, buat room 5 digit, join room, dan ingat kode room terakhir.
- ✅ Lobby: daftar pemain, penanda host, keluar room, dan mulai game minimal 2 pemain.
- ✅ Host dapat tambah/hapus bot, mengatur level bot, dan host aktif menjalankan giliran bot.
- ✅ Adapter `GameWire` membaca/menulis struktur data web v44 (`order`, `hands`, `turn`, `last`, `passed`, `dec`, `fin`, `log`, dan seterusnya).
- ✅ Aksi pemain dan keputusan kartu murni memakai transaksi Firebase; versi `VMAJ` dicek sebelum state dipakai.
- ⬜ Failover host saat host putus / AFK belum selesai. Ini bergantung pada presence Tahap 3b; untuk saat ini bot hanya diproses oleh host yang sedang aktif.
- ✅ Pemolesan visual awal dialog Multiplayer dan Lobby: panel felt gelap, aksen emas, kode room sebagai fokus, daftar pemain berbaris, dan tombol host lebih terstruktur.

Catatan: source sudah ditambahkan, tetapi build Android penuh belum dijalankan di lingkungan ini karena Gradle/Android SDK tidak tersedia. Build dan uji dua perangkat (Android + web) masih wajib sebelum dianggap rilis.
- **3b. Presence**
  - ⬜ Online / away / putus / AFK, heartbeat 5 detik, `onDisconnect` (`presUp`, `beat`, `pinfo`)
  - ⬜ Bot ambil alih setelah pemain AFK (jeda 8 detik, maks 20 detik pindah app)
  - ⬜ Indikator ping server dan ping pemain (batang sinyal), status server
- **3c. Chat dan reaksi**
  - ⬜ Chat room (maks 140 huruf) + 4 chip pesan cepat, badge pesan belum dibaca, toast
  - ⬜ Reaksi emoji melayang: 😂 🔥 👏 😭 😡 🤡 💀 👀
  - ⬜ Bunyi `Snd.msg` saat ada pesan masuk

### ⬜ Tahap 4: voice chat
- ⬜ Suara antar pemain (WebRTC mesh, sinyal lewat Firebase `voice` + `vsig`)
- ⬜ Mute, mode open mic / push-to-talk, tombol keluar voice
- ⬜ Indikator siapa yang lagi ngomong, status sambungan per pemain
- ⬜ Izin `RECORD_AUDIO` diminta saat pertama kali gabung voice

### ⬜ Tahap 5: pemolesan
- ⬜ Meter FPS (opsional)
- ⬜ Halaman kredit dan versi, tombol keluar room yang aman (back button)
- ⬜ Layar tetap nyala selama di room (sudah aktif lewat `FLAG_KEEP_SCREEN_ON`)

## Catatan konfigurasi Firebase Android
- Dependensi `firebase-database` memakai Firebase Android BoM `35.2.0`.
- Konfigurasi Firebase Android diinisialisasi dari konfigurasi publik yang sama dengan web, jadi `google-services.json` belum dipakai.
- API key Firebase yang tertanam di aplikasi klien bukan kredensial rahasia. Batas keamanan sebenarnya ada pada Realtime Database Rules. Jangan memakai aturan `read/write: true` untuk database produksi; atur autentikasi dan validasi rules sebelum room dibuka ke publik.
- Aplikasi Android belum mengimplementasikan presence / `onDisconnect`. Jika aplikasi ditutup paksa, entri pemain bisa tertinggal di room sampai dibersihkan. Itu bagian Tahap 3b.

## Keputusan teknis berikutnya
- **Room campur web + Android:** sudah dipilih. Kedua klien memakai struktur Firebase dan `VMAJ` yang sama (`44`).
- **Presence dan failover:** lanjutkan di Tahap 3b dengan heartbeat dan `onDisconnect`, lalu pindahkan pemilihan host efektif ke mekanisme presence.
- **Voice chat:** WebRTC di Android membutuhkan library native berukuran besar. Putuskan di Tahap 4 apakah fitur ini tetap dipakai atau dicoret dari versi Android.
- **Tidak perlu di-port:** service worker (`sw.js`, cache offline) karena aset sudah ada di dalam APK, dan `build.sh` (minify JS) karena tidak ada JS di app ini.

## Cara rilis
1. Taruh seluruh isi folder ini di root sebuah repo GitHub (repo baru, terpisah dari repo GitHub Pages).
2. Push tag versi:
   ```
   git tag v1.0.0
   git push origin v1.0.0
   ```
3. GitHub Actions build APK lalu otomatis upload ke **Releases** (tab Actions -> "Build & Release APK").
   Jalankan manual lewat tab Actions juga bisa, tapi hasilnya cuma artifact, bukan Release.

## Tanda tangan (penting kalau mau update antar-rilis)
Tanpa secret, workflow bikin kunci sementara baru tiap build, jadi APK versi baru **tidak bisa menimpa** versi lama
(harus uninstall dulu). Buat sekali kunci tetap, lalu simpan di Settings -> Secrets and variables -> Actions:

```
keytool -genkeypair -keystore kartu13.jks -alias kartu13 -keyalg RSA -keysize 2048 -validity 10000
base64 -w0 kartu13.jks     # hasilnya jadi isi secret KEYSTORE_BASE64
```
Secrets: `KEYSTORE_BASE64`, `KEYSTORE_PASSWORD`, `KEY_ALIAS`, `KEY_PASSWORD`. Simpan file `.jks` di tempat aman (jangan di-commit).

## Kredit
Gambar kartu: Vecteezy.com (Set of poker cards, resource 53654344), Vecteezy Free License, wajib atribusi. Lihat `Vecteezy-License-Information.pdf` dan `app/src/main/assets/cards/CREDITS.txt`.
