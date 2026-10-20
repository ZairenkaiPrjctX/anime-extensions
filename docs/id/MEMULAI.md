# Memulai (Bahasa Indonesia)

Panduan singkat. Dokumentasi teknis lengkap tersedia dalam bahasa Inggris di folder `docs/`.

## Apa ini?

Repositori ekstensi sumber anime untuk aplikasi [Anikku](https://github.com/komikku-app/anikku)
(dan aplikasi lain yang mendukung *extensions-lib* 16). Setiap sumber adalah satu modul Gradle dan
menghasilkan satu APK.

Sumber yang tersedia:

- **Internet Archive** (`src/all/internetarchive`) — API publik resmi archive.org untuk animasi/kartun.
- **Jellyfin** (`src/all/jellyfin`) — server media Jellyfin milik Anda sendiri. Isi alamat server,
  nama pengguna, dan kata sandi di pengaturan ekstensi.

Enam situs anime Indonesia yang diminta (Sokuja, MiniOppai, Kuramanime, AnimeIndo, Kuronime,
Nimegami) sudah diaudit satu per satu. Hasil, bukti, dan langkah selanjutnya untuk masing-masing
ada di [docs/SOURCE_AUDIT.md](../SOURCE_AUDIT.md). Tidak satu pun saat ini memenuhi syarat untuk
diintegrasikan: dua memblokir akses dari lingkungan audit, tiga menyatakan sendiri bahwa mereka
tidak memegang hak atas video atau melarang penggunaan semacam ini dalam ketentuannya, dan satu
masih memerlukan peninjauan ketentuan serta bukti lisensi. Usulan sumber baru dapat diajukan melalui
*issue* "Source request".

## Menginstal ekstensi

Setelah katalog bertanda tangan dipublikasikan, tambahkan URL berikut di pengaturan repositori
ekstensi aplikasi:

```
https://raw.githubusercontent.com/ZairenkaiPrjctX/anime-extensions/repo/index.min.json
```

## Membangun dari kode sumber

Prasyarat: JDK 17+ (CI memakai 21), Android SDK platform 34 (`ANDROID_HOME`), Python 3.12+.

```bash
# Kompilasi dan uji satu ekstensi
./gradlew -Pextensions=all:internetarchive :src:all:internetarchive:testDebugUnitTest

# Buat APK debug
./gradlew -Pextensions=all:internetarchive :src:all:internetarchive:assembleDebug
# Hasil: src/all/internetarchive/build/outputs/apk/debug/

# Validasi metadata repositori
python3 tools/validate_extensions.py
```

## Membuat sumber baru

```bash
python3 tools/new_source.py id namasitus --display-name "Nama Situs" --base-url https://situs.example
./gradlew -Pextensions=id:namasitus :src:id:namasitus:compileDebugKotlin
```

Kerangka yang dihasilkan langsung bisa dikompilasi, tetapi `validate_extensions.py` akan menolaknya
sampai semua `notImplemented()` diganti dengan implementasi nyata. Aturan penting:

- Gunakan hanya antarmuka publik (API resmi, feed, atau HTML publik). Dilarang melewati DRM,
  paywall, login, atau CAPTCHA.
- Jangan mengarang data: judul dan URL wajib berasal dari sumber; jika tidak ada, lewati item.
- Tambahkan `README.md` modul dan uji berbasis *fixture* yang sudah disanitasi.
- Naikkan `extVersionCode` satu kali per *pull request*.

Detail selengkapnya: [CONTRIBUTING.md](../../CONTRIBUTING.md).
