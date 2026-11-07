package com.astrostack

import android.app.Activity
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.view.ViewGroup
import android.widget.*
import com.astrostack.engine.ImageStackEngine
import java.io.File
import java.io.FileOutputStream
import kotlin.concurrent.thread

class MainActivity : Activity() {
    private val lightUris = mutableListOf<Uri>()
    private var darkUri: Uri? = null
    private var flatUri: Uri? = null
    private var biasUri: Uri? = null
    private var pendingCalibration = ""
    private lateinit var count: TextView
    private lateinit var preview: ImageView
    private lateinit var status: TextView
    private lateinit var method: Spinner
    private lateinit var align: CheckBox
    private var currentResult: Bitmap? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(22, 24, 22, 20)
            setBackgroundColor(Color.rgb(12, 17, 29))
        }
        fun label(s: String, size: Float = 15f) = TextView(this).apply {
            text = s; textSize = size; setTextColor(Color.WHITE)
        }
        fun button(s: String, action: () -> Unit) = Button(this).apply { text = s; setOnClickListener { action() } }
        root.addView(label("AstroStack", 30f))
        root.addView(label("Local astrophotography stacking • Android", 13f))
        val mode = Spinner(this).apply {
            adapter = ArrayAdapter(this@MainActivity, android.R.layout.simple_spinner_dropdown_item,
                listOf("Astro — deep sky", "Planet — translational alignment"))
        }
        root.addView(label("MODE")); root.addView(mode)
        method = Spinner(this).apply {
            adapter = ArrayAdapter(this@MainActivity, android.R.layout.simple_spinner_dropdown_item,
                listOf("Average", "Median", "Sigma clipping (2.5σ)"))
        }
        root.addView(label("STACK METHOD")); root.addView(method)
        align = CheckBox(this).apply { text = "Align frame (integer translation)"; isChecked = true; setTextColor(Color.WHITE) }
        root.addView(align)
        root.addView(button("Pilih LIGHT frames (multi-select)") { chooseLights() })
        count = label("Belum ada light frame")
        root.addView(count)
        root.addView(label("CALIBRATION FRAMES (opsional)"))
        root.addView(button("Pilih DARK frame") { chooseCalibration("dark") })
        root.addView(button("Pilih FLAT frame") { chooseCalibration("flat") })
        root.addView(button("Pilih BIAS frame") { chooseCalibration("bias") })
        root.addView(button("Mulai stacking") { startStack() })
        preview = ImageView(this).apply { adjustViewBounds = true; scaleType = ImageView.ScaleType.FIT_CENTER }
        root.addView(preview, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        status = label("Pilih minimal dua frame JPG/PNG/BMP yang dimensinya sama.")
        root.addView(status)
        root.addView(button("Simpan hasil PNG") { savePng() })
        root.addView(button("Simpan hasil TIFF 16-bit") { saveTiff() })
        setContentView(ScrollView(this).apply { addView(root) })
    }

    private fun chooseLights() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE); type = "image/*"; putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
        }
        startActivityForResult(intent, 41)
    }

    private fun chooseCalibration(kind: String) {
        pendingCalibration = kind
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply { addCategory(Intent.CATEGORY_OPENABLE); type = "image/*" }
        startActivityForResult(intent, 42)
    }

    @Deprecated("Legacy result API retained for broad Android compatibility")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (resultCode != Activity.RESULT_OK || data == null) return
        if (requestCode == 41) {
            lightUris.clear()
            data.clipData?.let { clip -> for (i in 0 until clip.itemCount) lightUris.add(clip.getItemAt(i).uri) }
                ?: data.data?.let { lightUris.add(it) }
            count.text = "${lightUris.size} light frame dipilih"
        } else if (requestCode == 42) {
            when (pendingCalibration) { "dark" -> darkUri = data.data; "flat" -> flatUri = data.data; "bias" -> biasUri = data.data }
            status.text = "${pendingCalibration.uppercase()} frame dipilih."
        }
    }

    private fun decode(uri: Uri): ImageStackEngine.Frame {
        val bmp = contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it) }
            ?: throw IllegalArgumentException("Gambar tidak dapat dibaca: $uri")
        val pixels = IntArray(bmp.width * bmp.height)
        bmp.getPixels(pixels, 0, bmp.width, 0, 0, bmp.width, bmp.height)
        return ImageStackEngine.Frame(bmp.width, bmp.height, pixels)
    }

    private fun startStack() {
        if (lightUris.size < 2) { status.text = "Pilih minimal 2 light frame."; return }
        val selectedLights = lightUris.toList()
        val d = darkUri; val f = flatUri; val b = biasUri
        val selectedMethod = when (method.selectedItemPosition) {
            1 -> ImageStackEngine.Method.MEDIAN
            2 -> ImageStackEngine.Method.SIGMA_CLIP
            else -> ImageStackEngine.Method.AVERAGE
        }
        val doAlign = align.isChecked
        status.text = "Memuat dan memproses frame…"
        thread {
            try {
                val lights = selectedLights.map { decode(it) }
                val dark = d?.let { decode(it) }; val flat = f?.let { decode(it) }; val bias = b?.let { decode(it) }
                val calibrated = lights.map { ImageStackEngine.calibrate(it, dark, flat, bias) }
                val result = ImageStackEngine.stack(calibrated, selectedMethod, doAlign)
                val out = Bitmap.createBitmap(result.frame.width, result.frame.height, Bitmap.Config.ARGB_8888)
                out.setPixels(result.frame.pixels, 0, result.frame.width, 0, 0, result.frame.width, result.frame.height)
                runOnUiThread {
                    currentResult = out; preview.setImageBitmap(out)
                    val shifts = result.shifts.joinToString { "(${it.dx},${it.dy})" }
                    status.text = "Selesai: ${lights.size} frame • ${out.width}×${out.height}\nShift: $shifts\nPixel ditolak sigma clip: ${result.rejectedPixels}"
                }
            } catch (e: OutOfMemoryError) {
                runOnUiThread { status.text = "Memori tidak cukup. Kurangi jumlah/ukuran frame." }
            } catch (e: Exception) {
                runOnUiThread { status.text = "Gagal: ${e.message}" }
            }
        }
    }

    private fun outputDir(): File = File(getExternalFilesDir(android.os.Environment.DIRECTORY_PICTURES), "AstroStack").apply { mkdirs() }

    private fun savePng() {
        val bmp = currentResult ?: run { status.text = "Belum ada hasil."; return }
        try {
            val file = File(outputDir(), "AstroStack_${System.currentTimeMillis()}.png")
            FileOutputStream(file).use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
            status.text = "PNG tersimpan: ${file.absolutePath}"
        } catch (e: Exception) { status.text = "Gagal menyimpan PNG: ${e.message}" }
    }

    private fun saveTiff() {
        val bmp = currentResult ?: run { status.text = "Belum ada hasil."; return }
        try {
            val pixels = IntArray(bmp.width * bmp.height)
            bmp.getPixels(pixels, 0, bmp.width, 0, 0, bmp.width, bmp.height)
            val file = File(outputDir(), "AstroStack_${System.currentTimeMillis()}_16bit.tif")
            ImageStackEngine.writeTiff16(ImageStackEngine.Frame(bmp.width, bmp.height, pixels), file)
            status.text = "TIFF 16-bit tersimpan: ${file.absolutePath}"
        } catch (e: Exception) { status.text = "Gagal menyimpan TIFF: ${e.message}" }
    }
}
