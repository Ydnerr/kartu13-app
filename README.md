# Kartu 13 (Android, native)

Port native Kotlin dari game web "Kartu 13 / Poker 13". Tanpa WebView; Firebase SDK resmi dipakai untuk multiplayer.

## Status
Tahap 1, 2, 2.5, dan 2.6 selesai. Tahap 3 dan 4 sudah ada implementasinya di app, tinggal validasi runtime Android ↔ web di perangkat nyata. Acuan fitur: web `index.html` + `game.js` v44.11 (tanda ✅ = sudah ada di app Android, ⬜ = belum).

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

### ✅ Tahap 3: multiplayer Firebase (2 sampai 4 pemain)
Implementasi awal **3a** sudah masuk ke source:
- ✅ Firebase Realtime Database SDK resmi, konfigurasi memakai Firebase project yang sama dengan web.
- ✅ Nama pemain maksimal 14 karakter dan disimpan di SharedPreferences.
- ✅ ID pemain lokal tetap, buat room 5 digit, join room, dan ingat kode room terakhir.
- ✅ Lobby: daftar pemain, penanda host, keluar room, dan mulai game minimal 2 pemain.
- ✅ Host dapat tambah/hapus bot, mengatur level bot, dan host aktif menjalankan giliran bot.
- ✅ Adapter `GameWire` membaca/menulis struktur data web v44 (`order`, `hands`, `turn`, `last`, `passed`, `dec`, `fin`, `log`, dan seterusnya).
- ✅ Aksi pemain dan keputusan kartu murni memakai transaksi Firebase; versi `VMAJ` dicek sebelum state dipakai.
- ✅ Perbaikan kompatibilitas web ↔ Android: `VMAJ` sekarang `v44` (web memakai `VER.split('.')[0]` = `v44`, sebelumnya app memakai `44` sehingga game yang dimulai host web ditolak dan app tertahan di ruang tunggu), `stopped` ditulis sebagai objek `{pid: true}` seperti web, dan penanda `x` (lewat) dari web yang bernilai angka `1` ikut terbaca
- ✅ Dialog Multiplayer otomatis tertutup setelah berhasil buat / gabung room (sebelumnya tertinggal di atas ruang tunggu kalau masuk lewat GABUNG)
- ✅ Failover host saat host putus / AFK mulai ditangani di Tahap 3b melalui effective driver. Hak administrasi lobby (tambah/hapus bot dan mulai game pertama) tetap milik host asli.
- ✅ Pemolesan visual awal dialog Multiplayer dan Lobby: panel felt gelap, aksen emas, kode room sebagai fokus, daftar pemain berbaris, dan tombol host lebih terstruktur.

Catatan: source sudah ditambahkan. Build Android penuh belum dijalankan di lingkungan ini karena Gradle/Android SDK tidak tersedia. Build dan uji dua perangkat (Android + web) masih wajib sebelum dianggap rilis.
- **🟡 3b. Presence (implementasi awal, perlu uji silang web + Android)**
  - ✅ Online / away / putus / AFK mengikuti protokol web v44 (`presence/{pid}`: `online`, `seen`, `ping`, `hid`, `off`); heartbeat 8 detik sesuai implementasi aktual `game.js` (komentar web menyebut 5 detik, interval kode adalah 8 detik).
  - ✅ `onDisconnect` menandai pemain offline; server time offset dipakai untuk perhitungan waktu.
  - ✅ Bot dapat mengambil giliran pemain AFK setelah grace period; effective driver mengikuti prioritas host aktif lalu pemain aktif berikutnya.
  - ✅ Indikator status dan ping di lobby serta kursi pemain; palet hijau / amber / merah mengikuti versi web.
  - ✅ Keluar dari app / tombol Keluar di tengah game kini sama dengan web: pemain **tidak dihapus** dari `players` (sebelumnya dihapus, sehingga lawan di web melihat nama "?", "Giliran undefined", status tetap "online", dan bot tidak mengambil alih). Presence ditandai `online:false` + `off`, lalu jadi AFK setelah masa tunggu dan kursinya dipegang bot. Room dibubarkan kalau tidak ada manusia lain.
  - ✅ App ditutup saat game berjalan: `onDisconnect` dibiarkan aktif (`stopListening(keepDisconnect = true)`) supaya server tetap menandai pemain putus walau heartbeat berhenti.
  - ⬜ Validasi runtime dua klien (Android ↔ web) dengan build terbaru: keluar via Back, tombol Home, swipe dari recent apps, koneksi putus mendadak, dan reconnect (lawan harus melihat AWAY → offline · bot, dan bot jalan di giliran pemain itu). Belum boleh dianggap rilis sebelum pengujian ini lolos.
- **🟡 3c. Chat dan reaksi (implementasi awal)**
  - ✅ Chat realtime di `rooms/{kode}/chat`, kompatibel dengan format web v44 (`p`, `n`, `m`, `t`), maksimal 50 pesan terakhir.
  - ✅ Batas pesan 140 karakter dan jeda kirim minimum 700 ms.
  - ✅ Empat pesan cepat sesuai teks web.
  - ✅ Reaksi di `rooms/{kode}/react` dengan emoji 😂 🔥 👏 😭 😡 🤡 💀 👀; entri reaksi dihapus setelah 8 detik.
  - ✅ Bunyi `Snd.msg` untuk pesan/reaksi baru dari pemain lain.
  - 🟡 UI Android memakai dialog chat dan pemilih reaksi. Toast dipakai sebagai notifikasi reaksi; animasi emoji melayang, badge unread, dan toast pratinjau chat belum diparitas penuh dengan web.
  - ⬜ Validasi runtime dua klien (Android ↔ web) dan build release masih wajib.

### 🟡 Tahap 4: voice chat
- 🟡 Suara antar pemain (WebRTC mesh, sinyal lewat Firebase `voice` + `vsig`, kompatibel dengan web v44). Sudah jalan di kode; masih perlu validasi audio Android ↔ web, relay TURN, dan reconnect di perangkat nyata
- ✅ Perbaikan force close saat gabung voice (aturan R8 `org.jni_zero.**`, `catch Throwable`, izin `MODIFY_AUDIO_SETTINGS`)
- ✅ Mute: tap tombol mic saat mode open mic (🎙 / 🔇)
- ✅ Mode open mic / tahan-ngomong (push-to-talk), pilihan tersimpan (`ptt`, sama dengan `k13ptt` di web). Mode tahan: mulai mute, tahan tombol ✋ untuk bicara, lepas untuk mute
- ✅ Tombol **Keluar** voice (baris voice di lobby dan layar main)
- ✅ Voice hanya bisa dimulai kalau ada minimal 2 pemain asli di room (sama dengan web)
- ✅ Indikator siapa yang lagi ngomong: cincin hijau + 🎙 di kursi pemain, baris lobby, dan tombol mic sendiri (`VoiceChatManager.speakingIds`; level mic dari sampel audio, level lawan dari statistik `inbound-rtp`; ambang sama dengan web)
- ✅ Status sambungan per pemain (`peerState`): `VOICE ✓ / … / ✕` di lobby, `VC ✓ / … / ✕` di kursi, plus ringkasan "nyambung x/y" di baris voice
- ✅ Izin `RECORD_AUDIO` diminta saat pertama kali gabung voice
- ⬜ Validasi runtime dua klien (Android ↔ web): audio dua arah, jaringan seluler/CGNAT lewat TURN, reconnect, app ke background, audio focus saat ada telepon
- ⬜ Peringatan "mic diblok sistem" (ada di web, belum di Android)

### ⬜ Tahap 5: pemolesan
- ⬜ Meter FPS (opsional)
- ⬜ Halaman kredit dan versi, tombol keluar room yang aman (back button)
- ⬜ Layar tetap nyala selama di room (sudah aktif lewat `FLAG_KEEP_SCREEN_ON`)

## Catatan konfigurasi Firebase Android
- Dependensi `firebase-database` memakai Firebase Android BoM `35.2.0`.
- Konfigurasi Firebase Android diinisialisasi dari konfigurasi publik yang sama dengan web, jadi `google-services.json` belum dipakai.
- API key Firebase yang tertanam di aplikasi klien bukan kredensial rahasia. Batas keamanan sebenarnya ada pada Realtime Database Rules. Jangan memakai aturan `read/write: true` untuk database produksi; atur autentikasi dan validasi rules sebelum room dibuka ke publik.
- Presence Android kini menulis heartbeat dan mendaftarkan `onDisconnect`. Ketepatan status saat proses dihentikan dan kompatibilitas penuh dengan web tetap perlu diuji pada perangkat nyata.

## Keputusan teknis berikutnya
- **Room campur web + Android:** sudah dipilih. Kedua klien memakai struktur Firebase dan `VMAJ` yang sama (`v44`).
- **Presence dan failover:** implementasi awal 3b sudah ditambahkan; uji silang runtime Android ↔ web tetap diperlukan.
- **Chat dan reaksi:** implementasi awal 3c sudah ditambahkan; uji silang runtime Android ↔ web tetap diperlukan.
- **Voice chat:** sudah dipilih, dipakai `io.github.webrtc-sdk:android` (libwebrtc). APK jadi jauh lebih besar (library native per ABI). Kalau ukuran jadi masalah, pertimbangkan `abiFilters` atau split APK per ABI.
- **Tidak perlu di-port:** service worker (`sw.js`, cache offline) karena aset sudah ada di dalam APK, dan `build.sh` (minify JS) karena tidak ada JS di app ini.

## Cara rilis
1. Taruh seluruh isi folder ini di root sebuah repo GitHub (repo baru, terpisah dari repo GitHub Pages).
2. **Build harian, tanpa tag:** cukup `git push` ke `main`. GitHub Actions build APK lalu menimpa release **latest** (tab Releases, ditandai pre-release). Nama file tetap `Kartu13-latest.apk`, jadi link unduhnya stabil: `https://github.com/<user>/<repo>/releases/download/latest/Kartu13-latest.apk`. Perubahan yang cuma menyentuh file `.md` tidak memicu build. Push beruntun membatalkan build lama.
3. **Rilis resmi (jarang):** push tag versi, hasilnya release baru bernomor.
   ```
   git tag v1.0.0
   git push origin v1.0.0
   ```
4. Jalankan manual lewat tab Actions juga bisa; hasilnya juga masuk release **latest**.
5. Jangan unduh lewat link artifact (`.../actions/runs/.../artifacts/...`): link itu butuh login GitHub di browser yang sama, kadaluarsa (workflow menyimpannya 7 hari), dan memberi 404 kalau belum login / sudah kadaluarsa. Pakai halaman Releases.

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


## Stage 4 — WebRTC Voice Chat (initial Android implementation)

- Adds native WebRTC audio mesh using `io.github.webrtc-sdk:android:144.7559.15`.
- Uses the web v44 Firebase signaling paths: `rooms/{code}/voice` and `rooms/{code}/vsig/{playerId}`.
- Uses lexicographic player-ID offer ownership, matching `game.js`.
- Uses the existing Metered TURN endpoints and Opus SDP parameters capped at 24 kbps.
- Lobby/game microphone control requests Android `RECORD_AUDIO`; tapping while connected toggles mute.
- This is an initial integration. Validate Android↔Web audio, TURN relay, reconnect, audio focus, and Android lifecycle on physical devices before treating Stage 4 as complete.
- Perbaikan force close saat mic ditekan: R8 membuang kelas `org.jni_zero.*` yang dipanggil library native WebRTC lewat JNI. Aturan keep ada di `app/proguard-rules.pro`; `join()` juga menangkap `Throwable` supaya error native tidak mematikan app. Izin `MODIFY_AUDIO_SETTINGS` ditambahkan.
- Indikator bicara memakai ambang yang sama dengan web (mic sendiri 0,02, pemain lain 0,03) dan menahan status 450 ms supaya tidak berkedip. Perlu dites di perangkat nyata, terutama sensitivitas mic dan echo.
- Mode tahan-ngomong: tekan tombol mic (✋) = bicara, lepas = mute. Diatur lewat sentuhan `ACTION_DOWN` / `ACTION_UP` di `GameView.onTouchEvent`, bukan klik biasa.
- Status sambungan per pemain memakai `PeerConnection.connectionState()` (CONNECTED = ✓, FAILED / DISCONNECTED / CLOSED = ✕, selain itu …). Status dan indikator bicara dipoll tiap 250 ms dari thread terpisah.
