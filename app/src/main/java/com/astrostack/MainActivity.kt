
package com.astrostack

import android.app.Activity
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BitmapRegionDecoder
import android.graphics.Rect
import android.net.Uri
import android.os.Bundle
import android.view.ViewGroup
import android.widget.*
import com.astrostack.engine.DiskStackEngine
import java.io.BufferedOutputStream
import java.io.File
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
            setBackgroundColor(
                android.graphics.Color.rgb(12, 17, 29)
            )
        }

        root.addView(label("AstroStack", 30f))
        root.addView(
            label("Disk-based astrophotography stacking", 13f)
        )

        mode = Spinner(this).apply {
            adapter = ArrayAdapter(
                this@MainActivity,
                android.R.layout.simple_spinner_dropdown_item,
                listOf(
                    "Astro — Deep Sky",
                    "Planet — Planetary"
                )
            )
        }

        root.addView(label("MODE"))
        root.addView(mode)

        method = Spinner(this).apply {
            adapter = ArrayAdapter(
                this@MainActivity,
                android.R.layout.simple_spinner_dropdown_item,
                listOf(
                    "Average",
                    "Median",
                    "Sigma Clipping (2.5σ)"
                )
            )
        }

        root.addView(label("STACK METHOD"))
        root.addView(method)

        root.addView(
            button("Pilih LIGHT frames (multi-select)") {
                chooseLights()
            }
        )

        count = label("Belum ada light frame")
        root.addView(count)

        root.addView(
            button("Mulai stacking") {
                startStack()
            }
        )

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

        status = label(
            "Pilih minimal dua gambar JPG, PNG, atau format lain yang didukung Android."
        )

        root.addView(status)

        root.addView(
            button("Simpan hasil TIFF 16-bit") {
                saveTiff()
            }
        )

        root.addView(
            label(
                "Catatan: Alignment otomatis, kalibrasi, RAW, dan PNG belum aktif.",
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

        val intent = Intent(
            Intent.ACTION_OPEN_DOCUMENT
        ).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "image/*"
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
        super.onActivityResult(
            requestCode,
            resultCode,
            data
        )

        if (resultCode != Activity.RESULT_OK || data == null) {
            return
        }

        if (requestCode == 41) {

            lightUris.clear()

            try {
                data.clipData?.let { clip ->

                    for (i in 0 until clip.itemCount) {
                        val uri = clip.getItemAt(i).uri
                        lightUris.add(uri)

                        try {
                            contentResolver.takePersistableUriPermission(
                                uri,
                                Intent.FLAG_GRANT_READ_URI_PERMISSION
                            )
                        } catch (_: Exception) {
                        }
                    }

                } ?: data.data?.let { uri ->

                    lightUris.add(uri)

                    try {
                        contentResolver.takePersistableUriPermission(
                            uri,
                            Intent.FLAG_GRANT_READ_URI_PERMISSION
                        )
                    } catch (_: Exception) {
                    }
                }

            } catch (e: Exception) {
                status.text = "Gagal memilih gambar: ${e.message}"
                return
            }

            count.text =
                "${lightUris.size} light frame dipilih"

            resultFile = null
            preview.setImageBitmap(null)
        }
    }

    private fun workDir(): File {
        return File(
            getExternalFilesDir(null),
            "AstroStack/work"
        ).apply {
            mkdirs()
        }
    }

    private fun outputDir(): File {
        return File(
            getExternalFilesDir(
                android.os.Environment.DIRECTORY_PICTURES
            ),
            "AstroStack"
        ).apply {
            mkdirs()
        }
    }

    /**
     * Decode the original image in horizontal strips.
     * No complete full-resolution IntArray is created.
     *
     * Raw format: A, R, G, B bytes.
     */
    private fun decodeToRaw(
        uri: Uri,
        destination: File
    ): Pair<Int, Int> {

        val input = contentResolver.openInputStream(uri)
            ?: throw Exception("Tidak dapat membuka gambar.")

        val decoder = input.use {
            BitmapRegionDecoder.newInstance(it, false)
                ?: throw Exception("Format gambar tidak didukung.")
        }

        try {

            val width = decoder.width
            val height = decoder.height

            require(width > 0 && height > 0)

            BufferedOutputStream(
                FileOutputStream(destination)
            ).use { output ->

                val rowsPerTile = 32

                var y = 0

                while (y < height) {

                    val rows = min(
                        rowsPerTile,
                        height - y
                    )

                    val rect = Rect(
                        0,
                        y,
                        width,
                        y + rows
                    )

                    val options = BitmapFactory.Options().apply {
                        inPreferredConfig = Bitmap.Config.ARGB_8888
                    }

                    val bitmap = decoder.decodeRegion(
                        rect,
                        options
                    ) ?: throw Exception(
                        "Gagal membaca bagian gambar pada baris $y."
                    )

                    try {

                        val pixels = IntArray(
                            bitmap.width * bitmap.height
                        )

                        bitmap.getPixels(
                            pixels,
                            0,
                            bitmap.width,
                            0,
                            0,
                            bitmap.width,
                            bitmap.height
                        )

                        for (pixel in pixels) {

                            output.write(
                                (pixel ushr 24) and 255
                            )

                            output.write(
                                (pixel ushr 16) and 255
                            )

                            output.write(
                                (pixel ushr 8) and 255
                            )

                            output.write(
                                pixel and 255
                            )
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
        }
    }

    /**
     * Build a small preview directly from the raw disk file.
     */
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

        val previewWidth = (width + sample - 1) / sample
        val previewHeight = (height + sample - 1) / sample

        val pixels = IntArray(
            previewWidth * previewHeight
        )

        RandomAccessFile(file, "r").use { input ->

            val rowBytes = ByteArray(width * 4)

            for (py in 0 until previewHeight) {

                val sourceY = min(
                    py * sample,
                    height - 1
                )

                input.seek(
                    sourceY.toLong() * width * 4L
                )

                input.readFully(rowBytes)

                for (px in 0 until previewWidth) {

                    val sourceX = min(
                        px * sample,
                        width - 1
                    )

                    val offset = sourceX * 4

                    val a = rowBytes[offset].toInt() and 255
                    val r = rowBytes[offset + 1].toInt() and 255
                    val g = rowBytes[offset + 2].toInt() and 255
                    val b = rowBytes[offset + 3].toInt() and 255

                    pixels[py * previewWidth + px] =
                        (a shl 24) or
                        (r shl 16) or
                        (g shl 8) or b
                }
            }
        }

        return Bitmap.createBitmap(
            previewWidth,
            previewHeight,
            Bitmap.Config.ARGB_8888
        ).apply {
            setPixels(
                pixels,
                0,
                previewWidth,
                0,
                0,
                previewWidth,
                previewHeight
            )
        }
    }

    private fun startStack() {

        if (lightUris.size < 2) {
            status.text = "Pilih minimal dua frame."
            return
        }

        val selectedUris = lightUris.toList()

        val selectedMethod = when (
            method.selectedItemPosition
        ) {
            1 -> DiskStackEngine.Method.MEDIAN
            2 -> DiskStackEngine.Method.SIGMA_CLIP
            else -> DiskStackEngine.Method.AVERAGE
        }

        val selectedMode = mode.selectedItem.toString()

        status.text = "Menyiapkan gambar..."

        thread {

            val temporaryFiles = mutableListOf<File>()

            try {

                var imageWidth = 0
                var imageHeight = 0

                for (i in selectedUris.indices) {

                    runOnUiThread {
                        status.text =
                            "Membaca frame ${i + 1}/${selectedUris.size}..."
                    }

                    val file = File(
                        workDir(),
                        "input_${System.currentTimeMillis()}_$i.argb"
                    )

                    temporaryFiles.add(file)

                    val dimensions = decodeToRaw(
                        selectedUris[i],
                        file
                    )

                    if (i == 0) {
                        imageWidth = dimensions.first
                        imageHeight = dimensions.second
                    } else {
                        require(
                            dimensions.first == imageWidth &&
                            dimensions.second == imageHeight
                        ) {
                            "Semua gambar harus memiliki dimensi sama."
                        }
                    }
                }

                val resultFilePath = File(
                    workDir(),
                    "result_${System.currentTimeMillis()}.argb"
                )

                runOnUiThread {
                    status.text = "Sedang stacking..."
                }

                val result = DiskStackEngine.stackFiles(
                    frameFiles = temporaryFiles,
                    width = imageWidth,
                    height = imageHeight,
                    outputFile = resultFilePath,
                    method = selectedMethod,
                    tileRows = 32
                )

                val smallPreview = createPreview(
                    result.outputFile,
                    result.width,
                    result.height
                )

                resultFile = result.outputFile
                resultWidth = result.width
                resultHeight = result.height

                runOnUiThread {

                    preview.setImageBitmap(smallPreview)

                    status.text =
                        "Stacking selesai.\n" +
                        "Mode: $selectedMode\n" +
                        "Frame: ${selectedUris.size}\n" +
                        "Resolusi: ${result.width} × ${result.height}\n" +
                        "Pixel ditolak: ${result.rejectedPixels}\n" +
                        "Alignment otomatis belum aktif."
                }

            } catch (e: OutOfMemoryError) {

                runOnUiThread {
                    status.text =
                        "Memori tidak cukup saat membaca atau membuat preview."
                }

            } catch (e: Exception) {

                runOnUiThread {
                    status.text =
                        "Gagal: ${e.message}"
                }

            } finally {

                temporaryFiles.forEach {
                    try {
                        it.delete()
                    } catch (_: Exception) {
                    }
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

        status.text = "Menyimpan TIFF 16-bit..."

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
                        "TIFF berhasil disimpan:\n${destination.absolutePath}"
                }

            } catch (e: Exception) {

                runOnUiThread {
                    status.text =
                        "Gagal menyimpan TIFF: ${e.message}"
                }
            }
        }
    }
}
