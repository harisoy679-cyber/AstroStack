package com.astrostack.engine

import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/** Pure Kotlin stacking core. Pixels are packed ARGB; processing is local and deterministic. */
object ImageStackEngine {
    data class Frame(val width: Int, val height: Int, val pixels: IntArray) {
        init { require(width > 0 && height > 0 && pixels.size == width * height) }
    }
    enum class Method { AVERAGE, MEDIAN, SIGMA_CLIP }
    data class Shift(val dx: Int, val dy: Int, val score: Double)
    data class Result(val frame: Frame, val shifts: List<Shift>, val rejectedPixels: Long)

    /** Estimates integer translation against reference using sampled normalized cross-correlation. */
    fun estimateTranslation(reference: Frame, moving: Frame, maxShift: Int = 24, sampleStep: Int = 4): Shift {
        require(reference.width == moving.width && reference.height == moving.height)
        val w = reference.width; val h = reference.height
        val step = max(1, sampleStep)
        var best = Shift(0, 0, Double.NEGATIVE_INFINITY)
        for (dy in -maxShift..maxShift) for (dx in -maxShift..maxShift) {
            val x0 = max(0, -dx); val x1 = min(w, w - dx)
            val y0 = max(0, -dy); val y1 = min(h, h - dy)
            if (x1 - x0 < 8 || y1 - y0 < 8) continue
            var n = 0; var sa = 0.0; var sb = 0.0; var saa = 0.0; var sbb = 0.0; var sab = 0.0
            var y = y0
            while (y < y1) {
                var x = x0
                while (x < x1) {
                    val a = luminance(reference.pixels[y*w+x]).toDouble()
                    val b = luminance(moving.pixels[(y+dy)*w+x+dx]).toDouble()
                    sa += a; sb += b; saa += a*a; sbb += b*b; sab += a*b; n++
                    x += step
                }
                y += step
            }
            if (n < 8) continue
            val cov = sab - sa*sb/n
            val va = saa - sa*sa/n; val vb = sbb - sb*sb/n
            val score = if (va <= 1e-9 || vb <= 1e-9) Double.NEGATIVE_INFINITY else cov / sqrt(va*vb)
            if (score > best.score) best = Shift(dx, dy, score)
        }
        return best
    }

    /** Calibration: (light - bias - darkResidual) / (flat - bias), normalized by flat mean. */
    fun calibrate(light: Frame, dark: Frame? = null, flat: Frame? = null, bias: Frame? = null): Frame {
        val all = listOfNotNull(dark, flat, bias)
        require(all.all { it.width == light.width && it.height == light.height })
        val out = IntArray(light.pixels.size)
        val flatPixels = flat?.pixels
        val biasPixels = bias?.pixels
        val darkPixels = dark?.pixels
        val flatMeans = DoubleArray(3)
        if (flat != null) {
            for (p in flat.pixels.indices) for (c in 0..2) flatMeans[c] = flatMeans[c] + channel(flat.pixels[p], c).toDouble() - (biasPixels?.let { channel(it[p], c) } ?: 0).toDouble()
            for (c in 0..2) flatMeans[c] = flatMeans[c] / flat.pixels.size
        }
        for (p in out.indices) {
            val values = IntArray(3)
            for (c in 0..2) {
                val b = biasPixels?.let { channel(it[p], c) } ?: 0
                val d = darkPixels?.let { channel(it[p], c) - b } ?: 0
                var v = (channel(light.pixels[p], c) - b - d).toDouble()
                if (flatPixels != null) {
                    val f = (channel(flatPixels[p], c) - b).toDouble()
                    v = if (f > 0.5) v * flatMeans[c] / f else 0.0
                }
                values[c] = v.toInt().coerceIn(0, 255)
            }
            out[p] = argb(values[0], values[1], values[2])
        }
        return Frame(light.width, light.height, out)
    }

    /** Registers translations, crops to shared overlap, then stacks. */
    fun stack(frames: List<Frame>, method: Method = Method.AVERAGE, align: Boolean = true,
              maxShift: Int = 24, sigma: Double = 2.5): Result {
        require(frames.size >= 2)
        val w = frames.first().width; val h = frames.first().height
        require(frames.all { it.width == w && it.height == h }) { "All frames must have identical dimensions" }
        val shifts = if (align) frames.mapIndexed { i, f -> if (i == 0) Shift(0,0,1.0) else estimateTranslation(frames[0], f, maxShift) }
                     else frames.map { Shift(0,0,1.0) }
        val left = shifts.maxOf { max(0, -it.dx) }; val right = shifts.maxOf { max(0, it.dx) }
        val top = shifts.maxOf { max(0, -it.dy) }; val bottom = shifts.maxOf { max(0, it.dy) }
        val ow = w-left-right; val oh = h-top-bottom
        require(ow > 0 && oh > 0) { "Alignment shifts leave no common image area" }
        val out = IntArray(ow*oh); val values = DoubleArray(frames.size); var rejected = 0L
        for (y in 0 until oh) for (x in 0 until ow) {
            val p = y*ow+x; val srcX=x+left; val srcY=y+top
            val channels = IntArray(3)
            for (c in 0..2) {
                for (i in frames.indices) {
                    val s=shifts[i]; values[i]=channel(frames[i].pixels[(srcY+s.dy)*w+srcX+s.dx],c).toDouble()
                }
                channels[c] = when(method) {
                    Method.AVERAGE -> values.average().toInt()
                    Method.MEDIAN -> median(values)
                    Method.SIGMA_CLIP -> {
                        val mean=values.average(); val variance=values.sumOf { (it-mean)*(it-mean) }/values.size
                        val sd=sqrt(variance); val kept=values.filter { abs(it-mean) <= sigma*sd || sd < 1e-9 }
                        rejected += (values.size-kept.size).toLong()
                        (if (kept.isEmpty()) mean else kept.average()).toInt()
                    }
                }.coerceIn(0,255)
            }
            out[p]=argb(channels[0],channels[1],channels[2])
        }
        return Result(Frame(ow,oh,out), shifts, rejected)
    }

    /** Writes uncompressed RGB TIFF, 16 bits/channel, little-endian. 8-bit input is expanded to 16-bit. */
    fun writeTiff16(frame: Frame, file: File) {
        val entries = 10
        val ifdOffset = 8
        val ifdSize = 2 + entries*12 + 4
        val bitsOffset = ifdOffset + ifdSize
        val pixelOffset = bitsOffset + 6
        BufferedOutputStream(FileOutputStream(file)).use { out ->
            fun u16(v:Int) { out.write(v and 255); out.write((v ushr 8) and 255) }
            fun u32(v:Long) { u16((v and 0xffff).toInt()); u16(((v ushr 16) and 0xffff).toInt()) }
            out.write('I'.code); out.write('I'.code); u16(42); u32(ifdOffset.toLong())
            u16(entries)
            fun entry(tag:Int,type:Int,count:Long,value:Long) { u16(tag); u16(type); u32(count); u32(value) }
            entry(256,4,1,frame.width.toLong()); entry(257,4,1,frame.height.toLong())
            entry(258,3,3,bitsOffset.toLong()); entry(259,3,1,1); entry(262,3,1,2)
            entry(273,4,1,pixelOffset.toLong()); entry(277,3,1,3); entry(278,4,1,frame.height.toLong())
            entry(279,4,1,(frame.width.toLong()*frame.height*6)); entry(284,3,1,1)
            u32(0); repeat(3) { u16(16) }
            for (px in frame.pixels) { u16((channel(px,0)*257)); u16((channel(px,1)*257)); u16((channel(px,2)*257)) }
        }
    }

    private fun median(a: DoubleArray): Int { val s=a.sorted(); val n=s.size; return (if(n%2==1) s[n/2] else (s[n/2-1]+s[n/2])/2).toInt() }
    private fun channel(px:Int,c:Int)=when(c){0->(px ushr 16) and 255;1->(px ushr 8) and 255;else->px and 255}
    private fun luminance(px:Int)=((channel(px,0)*299+channel(px,1)*587+channel(px,2)*114)/1000)
    private fun argb(r:Int,g:Int,b:Int)=(-0x1000000) or (r shl 16) or (g shl 8) or b
}
