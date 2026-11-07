package com.astrostack

import android.app.Activity
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BitmapRegionDecoder
import android.graphics.Rect
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.view.ViewGroup
import android.widget.*
import com.astrostack.engine.DiskStackEngine
import com.tssystems.Libraw
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.RandomAccessFile
import kotlin.concurrent.thread
import kotlin.math.max
import kotlin.math.min

class MainActivity : Activity() {

    private val lightUris = mutableListOf<Uri>()

    private lateinit var count: TextView
    private lateinit var preview: ImageView
    private lateinit var status: TextView
    private lateinit var method: Spinner
    private lateinit var mode: Spinner

    private var resultFile: File? = null
    private var resultWidth = 0
    private var resultHeight = 0
    private var isProcessing = false

    private fun label(text: String, size: Float = 15f) =
        TextView(this).apply {
            this.text = text
            textSize = size
            setTextColor(android.graphics.Color.WHITE)
        }

    private fun button(text: String, action: () -> Unit) =
        Button(this).apply {
            this.text = text
            setOnClickListener { action() }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(22, 24, 22, 20)
            setBackgroundColor(android.graphics.Color.rgb(12, 17, 29))
        }

        root.addView(label("AstroStack", 30f))
        root.addView(label("Astrophotography Image Stacker", 13f))

        mode = Spinner(this).apply {
            adapter = ArrayAdapter(
                this@MainActivity,
                android.R.layout.simple_spinner_dropdown_item,
                listOf("Astro — Deep Sky", "Planet — Planetary")
            )
        }

        root.addView(label("MODE"))
        root.addView(mode)

        method = Spinner(this).apply {
            adapter = ArrayAdapter(
                this@MainActivity,
                android.R.layout.simple_spinner_dropdown_item,
                listOf("Average", "Median", "Sigma Clipping (2.5σ)")
            )
        }

        root.addView(label("STACK METHOD"))
        root.addView(method)

        root.addView(button("Pilih LIGHT frames") {
            chooseLights()
        })

        count = label("Belum ada foto dipilih")
        root.addView(count)

        root.addView(button("MULAI STACKING") {
            startStack()
        })

        preview = ImageView(this).apply {
            adjustViewBounds = true
            scaleType = ImageView.ScaleType.FIT_CENTER
        }

        root.addView(
            preview,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                0,
                1f
            )
        )

        status = label("Pilih minimal dua foto.")
        root.addView(status)

        root.addView(button("SIMPAN TIFF 16-BIT") {
            saveTiff()
        })

        root.addView(
            label(
                "RAW menggunakan LibRaw. " +
                "Output sementara masih 8-bit. " +
                "Calibration frames dan ekspor PNG belum tersedia.",
                12f
            )
        )

        setContentView(
            ScrollView(this).apply {
                addView(root)
            }
        )
    }

    private fun chooseLights() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"
            putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
        }

        startActivityForResult(intent, 41)
    }

    @Deprecated("Legacy result API")
    override fun onActivityResult(
        requestCode: Int,
        resultCode: Int,
        data: Intent?
    ) {
        super.onActivityResult(requestCode, resultCode, data)

        if (resultCode != RESULT_OK || data == null) return

        if (requestCode == 41) {
            lightUris.clear()

            data.clipData?.let { clip ->
                for (i in 0 until clip.itemCount) {
                    addImageUri(clip.getItemAt(i).uri, data)
                }
            } ?: data.data?.let {
                addImageUri(it, data)
            }

            count.text = "${lightUris.size} foto dipilih"
            resultFile = null
            preview.setImageBitmap(null)
        }
    }

    private fun addImageUri(uri: Uri, data: Intent) {
        lightUris.add(uri)

        try {
            val flags =
                data.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION

            contentResolver.takePersistableUriPermission(uri, flags)
        } catch (_: Exception) {
        }
    }

    private fun workDir(): File =
        File(cacheDir, "AstroStack").apply { mkdirs() }

    private fun outputDir(): File {
        val parent =
            getExternalFilesDir(Environment.DIRECTORY_PICTURES)
                ?: filesDir

        return File(parent, "AstroStack").apply { mkdirs() }
    }

    private fun copyUriToFile(uri: Uri, destination: File) {
        val input = contentResolver.openInputStream(uri)
            ?: throw Exception("Tidak dapat membuka file.")

        input.use { source ->
            FileOutputStream(destination).use { output ->
                source.copyTo(output)
            }
        }
    }

    private fun decodeWithAndroid(
        source: File,
        destination: File
    ): Pair<Int, Int> {

        val input = FileInputStream(source)

        val decoder = try {
            BitmapRegionDecoder.newInstance(input, false)
                ?: throw Exception("Decoder Android tidak mendukung format ini.")
        } catch (e: Throwable) {
            input.close()
            throw e
        }

        try {
            val width = decoder.width
            val height = decoder.height

            require(width > 0 && height > 0)

            BufferedOutputStream(FileOutputStream(destination)).use { output ->
                val tileRows = 32
                var y = 0

                while (y < height) {
                    val rows = min(tileRows, height - y)
                    val rect = Rect(0, y, width, y + rows)

                    val options = BitmapFactory.Options().apply {
                        inPreferredConfig = Bitmap.Config.ARGB_8888
                    }

                    val bitmap = decoder.decodeRegion(rect, options)
                        ?: throw Exception("Gagal membaca bagian gambar.")

                    try {
                        val pixels =
                            IntArray(bitmap.width * bitmap.height)

                        bitmap.getPixels(
                            pixels, 0, bitmap.width,
                            0, 0, bitmap.width, bitmap.height
                        )

                        for (pixel in pixels) {
                            output.write((pixel ushr 24) and 255)
                            output.write((pixel ushr 16) and 255)
                            output.write((pixel ushr 8) and 255)
                            output.write(pixel and 255)
                        }
                    } finally {
                        bitmap.recycle()
                    }

                    y += rows
                }
            }

            return Pair(width, height)
        } finally {
            decoder.recycle()
            input.close()
        }
    }

    private fun decodeWithLibraw(
        source: File,
        destination: File
    ): Pair<Int, Int> {

        val raw = Libraw()

        try {
            val openResult = raw.open(source.absolutePath)

            if (openResult != 0) {
                throw Exception("LibRaw gagal membuka RAW. Kode: $openResult")
            }

            raw.setOutputBps(8)
            raw.setAutoWhitebalance(true)
            raw.setAutoBrightness(false)

            val pixels = raw.getPixels8()
                ?: throw Exception("LibRaw gagal mendekode piksel RAW.")

            val width = raw.getBitmapWidth()
            val height = raw.getBitmapHeight()

            if (width <= 0 || height <= 0) {
                throw Exception("Dimensi RAW tidak valid.")
            }

            val expected = width.toLong() * height.toLong()

            if (expected != pixels.size.toLong()) {
                throw Exception(
                    "Jumlah piksel RAW tidak cocok. " +
                    "Ukuran: ${width}x${height}, data: ${pixels.size}"
                )
            }

            BufferedOutputStream(FileOutputStream(destination)).use { output ->
                for (pixel in pixels) {
                    output.write((pixel ushr 24) and 255)
                    output.write((pixel ushr 16) and 255)
                    output.write((pixel ushr 8) and 255)
                    output.write(pixel and 255)
                }
            }

            return Pair(width, height)

        } finally {
            try {
                raw.cleanup()
            } catch (_: Exception) {
            }
        }
    }

    private fun decodeToRaw(
        uri: Uri,
        destination: File
    ): Pair<Int, Int> {

        val source = File(
            workDir(),
            "source_${System.currentTimeMillis()}_${destination.name}"
        )

        try {
            copyUriToFile(uri, source)

            try {
                return decodeWithAndroid(source, destination)
            } catch (androidError: Exception) {
                destination.delete()

                try {
                    return decodeWithLibraw(source, destination)
                } catch (rawError: Exception) {
                    destination.delete()

                    throw Exception(
                        "Decoder Android: ${androidError.message}\n" +
                        "Decoder LibRaw: ${rawError.message}"
                    )
                }
            }
        } finally {
            source.delete()
        }
    }

    private fun createPreview(
        file: File,
        width: Int,
        height: Int
    ): Bitmap {

        val sample = max(
            1,
            max(
                (width + 999) / 1000,
                (height + 999) / 1000
            )
        )

        val pw = (width + sample - 1) / sample
        val ph = (height + sample - 1) / sample
        val pixels = IntArray(pw * ph)

        RandomAccessFile(file, "r").use { input ->
            val row = ByteArray(width * 4)

            for (py in 0 until ph) {
                val sy = min(py * sample, height - 1)

                input.seek(sy.toLong() * width * 4L)
                input.readFully(row)

                for (px in 0 until pw) {
                    val sx = min(px * sample, width - 1)
                    val offset = sx * 4

                    val a = row[offset].toInt() and 255
                    val r = row[offset + 1].toInt() and 255
                    val g = row[offset + 2].toInt() and 255
                    val b = row[offset + 3].toInt() and 255

                    pixels[py * pw + px] =
                        (a shl 24) or
                        (r shl 16) or
                        (g shl 8) or b
                }
            }
        }

        return Bitmap.createBitmap(
            pixels, pw, ph, Bitmap.Config.ARGB_8888
        )
    }

    private fun startStack() {
        if (isProcessing) return

        if (lightUris.size < 2) {
            status.text = "Pilih minimal dua foto."
            return
        }

        val selectedUris = lightUris.toList()

        val selectedMethod = when (method.selectedItemPosition) {
            1 -> DiskStackEngine.Method.MEDIAN
            2 -> DiskStackEngine.Method.SIGMA_CLIP
            else -> DiskStackEngine.Method.AVERAGE
        }

        val selectedMode = mode.selectedItem.toString()

        isProcessing = true
        resultFile = null
        status.text = "Memulai proses..."

        thread {
            val temporaryFiles = mutableListOf<File>()

            try {
                var width = 0
                var height = 0

                for (i in selectedUris.indices) {
                    runOnUiThread {
                        status.text =
                            "Membaca foto ${i + 1}/${selectedUris.size}..."
                    }

                    val file = File(
                        workDir(),
                        "frame_${System.currentTimeMillis()}_$i.argb"
                    )

                    temporaryFiles.add(file)

                    val dimensions = decodeToRaw(selectedUris[i], file)

                    if (i == 0) {
                        width = dimensions.first
                        height = dimensions.second
                    } else {
                        require(
                            dimensions.first == width &&
                            dimensions.second == height
                        ) {
                            "Semua foto harus memiliki ukuran yang sama."
                        }
                    }
                }

                runOnUiThread {
                    status.text = "Mendeteksi dan menyelaraskan bintang..."
                }

                val alignedFiles = mutableListOf<File>()

                val transforms =
                    mutableListOf<DiskStackEngine.Transform>()

                alignedFiles.add(temporaryFiles.first())

                transforms.add(
                    DiskStackEngine.Transform(
                        dx = 0.0,
                        dy = 0.0,
                        rotationRadians = 0.0,
                        scale = 1.0,
                        inliers = 0,
                        rmsError = 0.0
                    )
                )

                var skippedFrames = 0

                for (i in 1 until temporaryFiles.size) {
                    runOnUiThread {
                        status.text =
                            "Alignment foto ${i + 1}/${temporaryFiles.size}..."
                    }

                    val transform = DiskStackEngine.estimate(
                        referenceFile = temporaryFiles.first(),
                        movingFile = temporaryFiles[i],
                        width = width,
                        height = height
                    )

                    if (
                        transform != null &&
                        transform.inliers >= 3 &&
                        transform.dx.isFinite() &&
                        transform.dy.isFinite() &&
                        transform.scale > 0.0
                    ) {
                        alignedFiles.add(temporaryFiles[i])
                        transforms.add(transform)
                    } else {
                        skippedFrames++
                    }
                }

                if (alignedFiles.size < 2) {
                    throw Exception(
                        "Alignment hanya berhasil pada satu foto. " +
                        "Bintang tidak cukup terdeteksi atau pergeseran " +
                        "antarfoto terlalu besar."
                    )
                }

                val output = File(
                    workDir(),
                    "result_${System.currentTimeMillis()}.argb"
                )

                runOnUiThread {
                    status.text = "Stacking ${alignedFiles.size} foto..."
                }

                val result = DiskStackEngine.stackFiles(
                    frameFiles = alignedFiles,
                    width = width,
                    height = height,
                    outputFile = output,
                    method = selectedMethod,
                    transforms = transforms,
                    tileRows = 16
                )

                val bitmap = createPreview(
                    result.outputFile,
                    result.width,
                    result.height
                )

                resultFile = result.outputFile
                resultWidth = result.width
                resultHeight = result.height

                runOnUiThread {
                    preview.setImageBitmap(bitmap)

                    status.text =
                        "STACKING SELESAI\n" +
                        "Mode: $selectedMode\n" +
                        "Foto dipilih: ${selectedUris.size}\n" +
                        "Berhasil alignment: ${alignedFiles.size}\n" +
                        "Dilewati: $skippedFrames\n" +
                        "Resolusi: ${result.width} × ${result.height}\n" +
                        "Kanal ditolak: ${result.rejectedPixels}"
                }

            } catch (e: OutOfMemoryError) {
                runOnUiThread {
                    status.text =
                        "Memori tidak cukup. Coba kurangi jumlah foto."
                }

            } catch (e: Exception) {
                runOnUiThread {
                    status.text = "Gagal: ${e.message}"
                }

            } finally {
                temporaryFiles.forEach {
                    try {
                        it.delete()
                    } catch (_: Exception) {
                    }
                }

                runOnUiThread {
                    isProcessing = false
                }
            }
        }
    }

    private fun saveTiff() {
        val source = resultFile

        if (source == null || !source.exists()) {
            status.text = "Belum ada hasil stacking."
            return
        }

        if (isProcessing) return

        isProcessing = true
        status.text = "Menyimpan TIFF..."

        thread {
            try {
                val destination = File(
                    outputDir(),
                    "AstroStack_${System.currentTimeMillis()}.tif"
                )

                DiskStackEngine.writeTiff16(
                    rawFile = source,
                    width = resultWidth,
                    height = resultHeight,
                    outputFile = destination
                )

                runOnUiThread {
                    status.text =
                        "TIFF berhasil disimpan:\n" +
                        destination.absolutePath
                }

            } catch (e: Exception) {
                runOnUiThread {
                    status.text =
                        "Gagal menyimpan TIFF: ${e.message}"
                }

            } finally {
                runOnUiThread {
                    isProcessing = false
                }
            }
        }
    }
}
