# Kartu 13 (Android, native)

Port native Kotlin dari game web "Kartu 13 / Poker 13". Tanpa WebView, tanpa library pihak ketiga.

## Status
**Tahap 1**: main lawan bot (4 level), mesin aturan lengkap (hasil port `game.js` v44.11), halaman aturan.
Belum ada: tutorial terpandu, efek suara (Tahap 2), multiplayer/chat/reaksi Firebase (Tahap 3), voice chat (Tahap 4).

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
