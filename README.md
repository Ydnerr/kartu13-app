# Kartu 13 (Android, native)

Port native Kotlin dari game web "Kartu 13 / Poker 13". Tanpa WebView, tanpa library pihak ketiga.

## Status
Tahap 1, 2, 2.5, dan 2.6 selesai. Sisanya di roadmap di bawah. Acuan fitur: web `index.html` + `game.js` v44.11 (tanda ✅ = sudah ada di app Android, ⬜ = belum).

Prinsip: tanpa WebView, dan sebisa mungkin tanpa library pihak ketiga. Setiap tahap harus bisa di-build dan dimainkan sendiri sebelum lanjut ke tahap berikutnya.

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

### ⬜ Tahap 3: multiplayer Firebase (2 sampai 4 pemain)
Dipecah supaya bisa dirilis bertahap.
- **3a. Room dan sinkronisasi**
  - ⬜ Isi nama pemain (maks 14 huruf, tersimpan)
  - ⬜ Buat room (kode 5 digit) dan join pakai kode, kode room terakhir diingat
  - ⬜ Ruang tunggu: daftar pemain, tanda host, tombol Mulai (min 2 pemain), Keluar
  - ⬜ Host bisa tambah / hapus bot di ruang tunggu (min 2 manusia, maks 4 kursi) dan atur level bot
  - ⬜ Aksi main lewat transaksi Firebase (`play`, `decideCall`), cek versi data `VMAJ`
  - ⬜ "Host efektif": kalau host putus, pemain aktif berikutnya yang jalankan bot (`driver`, `botTick`)
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

## Keputusan teknis yang perlu diambil sebelum Tahap 3 dan 4
- **Firebase tanpa SDK:** Realtime Database punya REST API + streaming (SSE) yang bisa dipanggil pakai `HttpURLConnection`, jadi prinsip "tanpa library" bisa dipertahankan. Kekurangannya, transaksi (`transaction`) harus dibuat sendiri pakai ETag, dan `onDisconnect` nggak ada di REST. Alternatifnya pakai Firebase SDK resmi (lebih gampang, tapi butuh `google-services.json` dan menambah ukuran APK).
- **Satu room campur web + Android:** kalau mau, struktur data di Firebase harus persis sama dengan web dan `VMAJ` harus cocok (sekarang `v44`). Kalau Android nanti pakai format sendiri, pemain web dan Android nggak bisa satu room.
- **Voice chat:** WebRTC di Android butuh library (libwebrtc, puluhan MB). Ini satu-satunya bagian yang praktis nggak bisa tanpa library. Putuskan nanti di Tahap 4 apakah dipakai, atau voice dicoret dari versi Android.
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
