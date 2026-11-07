# AstroStack — Android prototype 0.2

Aplikasi Android native Kotlin untuk pemrosesan stacking astronomi secara lokal.

## Fitur yang sudah diimplementasikan
- Pilih banyak light frames melalui Android Storage Access Framework.
- Mode Astro dan Planet (mode Planet saat ini memakai registrasi translasi yang sama).
- Registrasi translasi integer berbasis normalized cross-correlation terhadap frame pertama.
- Pemotongan otomatis ke area overlap setelah alignment.
- Kalibrasi opsional menggunakan satu frame Dark, Flat, dan Bias; dimensi harus sama dengan light.
- Stacking Average, Median, dan Sigma Clipping.
- Pratinjau dan ekspor PNG.
- Ekspor TIFF RGB 16-bit/channel tanpa kompresi. Nilai 8-bit input diperluas ke 16-bit (nilai dikali 257); ini bukan pemulihan data RAW 16-bit.
- Seluruh pemrosesan berlangsung lokal di perangkat.

## Batasan yang belum selesai
- DNG/CR2/CR3/NEF/ARW dan RAW kamera belum didukung. Dekoder Android `BitmapFactory` umumnya membaca JPEG/PNG/BMP; dukungan RAW memerlukan integrasi LibRaw/decoder native dan pengujian per kamera.
- Alignment saat ini hanya translasi integer; belum subpixel, rotasi, skala, distorsi lensa, deteksi bintang robust, atau drizzle.
- Mode Planet belum mempunyai alignment berbasis fitur/patch, quality ranking, atau frame rejection berdasarkan ketajaman.
- Kalibrasi memakai satu frame untuk tiap tipe, bukan master-frame hasil median/average. Flat dinormalisasi berdasarkan mean kanal. Pastikan dark/flat/bias sesuai exposure, gain, temperatur, dan workflow kamera.
- Sigma clipping adalah clipping per kanal sederhana; belum weighted stacking atau noise model.
- TIFF disimpan sebagai RGB unsigned 16-bit dengan data yang berasal dari pipeline 8-bit Android, sehingga rentang dinamis 16-bit asli tidak tersedia.
- Pemrosesan memuat frame ke RAM. Untuk sensor besar dan banyak frame, risiko kehabisan memori masih ada; belum ada tile-based processing.
- UI masih prototipe; belum ada progress bar, cancel, project save/load, metadata FITS, atau batch queue.

## Build APK
Persyaratan: Android Studio, JDK 17, Android SDK Platform 35, Gradle plugin 8.7.3.
1. Buka folder proyek `AstroStack` di Android Studio.
2. Sync Gradle.
3. Build > Build Bundle(s) / APK(s) > Build APK(s).
4. APK debug di `app/build/outputs/apk/debug/app-debug.apk`.

## Uji algoritma
Engine pemrosesan murni Kotlin ada di `app/src/main/java/com/astrostack/engine/ImageStackEngine.kt`.
Smoke test JVM berada di `engine-tests/EngineSmokeTest.kt`. Jalankan dengan Kotlin compiler:

```bash
kotlinc app/src/main/java/com/astrostack/engine/ImageStackEngine.kt engine-tests/EngineSmokeTest.kt -include-runtime -d engine-tests.jar
java -jar engine-tests.jar
```

Tes mencakup pergeseran translasi sintetis, median, sigma clipping, dark/bias calibration, dan header/payload TIFF 16-bit.
