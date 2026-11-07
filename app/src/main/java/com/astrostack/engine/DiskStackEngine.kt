package com.astrostack.engine

import java.io.BufferedOutputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

object DiskStackEngine {

    enum class Method {
        AVERAGE,
        MEDIAN,
        SIGMA_CLIP
    }

    data class Shift(
        val dx: Int = 0,
        val dy: Int = 0,
        val score: Double = 1.0
    )

    data class Result(
        val width: Int,
        val height: Int,
        val shifts: List<Shift>,
        val rejectedPixels: Long,
        val outputFile: File
    )

    /**
     * File format:
     * 4 bytes per pixel, ARGB, big-endian.
     */
    fun stackFiles(
        frameFiles: List<File>,
        width: Int,
        height: Int,
        outputFile: File,
        method: Method = Method.AVERAGE,
        shifts: List<Shift> = emptyList(),
        sigma: Double = 2.5,
        tileRows: Int = 32
    ): Result {

        require(frameFiles.size >= 2) {
            "Pilih minimal dua frame."
        }

        require(width > 0 && height > 0)
        require(tileRows > 0)
        require(sigma > 0)

        val expectedSize = width.toLong() * height * 4L

        frameFiles.forEach {
            require(it.isFile && it.length() == expectedSize) {
                "File frame tidak valid: ${it.name}"
            }
        }

        val actualShifts = if (shifts.isEmpty()) {
            List(frameFiles.size) { Shift() }
        } else {
            require(shifts.size == frameFiles.size)
            shifts
        }

        val left = actualShifts.maxOf { max(0, -it.dx) }
        val right = actualShifts.maxOf { max(0, it.dx) }
        val top = actualShifts.maxOf { max(0, -it.dy) }
        val bottom = actualShifts.maxOf { max(0, it.dy) }

        val outWidth = width - left - right
        val outHeight = height - top - bottom

        require(outWidth > 0 && outHeight > 0) {
            "Area gambar setelah alignment tidak valid."
        }

        outputFile.parentFile?.mkdirs()

        val inputs = frameFiles.map {
            RandomAccessFile(it, "r")
        }

        var rejected = 0L

        try {
            DataOutputStream(
                BufferedOutputStream(
                    FileOutputStream(outputFile)
                )
            ).use { output ->

                var tileY = 0

                while (tileY < outHeight) {

                    val rows = min(tileRows, outHeight - tileY)
                    val pixelCount = outWidth * rows

                    val tiles = Array(frameFiles.size) {
                        IntArray(pixelCount)
                    }

                    for (i in inputs.indices) {
                        val shift = actualShifts[i]

                        readTile(
                            inputs[i],
                            width,
                            left + shift.dx,
                            top + tileY + shift.dy,
                            outWidth,
                            rows,
                            tiles[i]
                        )
                    }

                    val values = DoubleArray(frameFiles.size)

                    for (p in 0 until pixelCount) {

                        val red = calculate(
                            tiles, p, 16,
                            method, sigma, values
                        )

                        val green = calculate(
                            tiles, p, 8,
                            method, sigma, values
                        )

                        val blue = calculate(
                            tiles, p, 0,
                            method, sigma, values
                        )

                        rejected += red.second
                        rejected += green.second
                        rejected += blue.second

                        val pixel =
                            (0xFF shl 24) or
                            (red.first shl 16) or
                            (green.first shl 8) or
                            blue.first

                        output.writeInt(pixel)
                    }

                    tileY += rows
                }
            }
        } finally {
            inputs.forEach {
                try {
                    it.close()
                } catch (_: Exception) {
                }
            }
        }

        return Result(
            outWidth,
            outHeight,
            actualShifts,
            rejected,
            outputFile
        )
    }

    private fun readTile(
        input: RandomAccessFile,
        imageWidth: Int,
        x: Int,
        y: Int,
        tileWidth: Int,
        tileHeight: Int,
        destination: IntArray
    ) {

        val rowBytes = ByteArray(tileWidth * 4)
        var target = 0

        for (row in 0 until tileHeight) {

            val position =
                ((y + row).toLong() * imageWidth + x) * 4L

            input.seek(position)
            input.readFully(rowBytes)

            var offset = 0

            for (column in 0 until tileWidth) {

                val a = rowBytes[offset].toInt() and 255
                val r = rowBytes[offset + 1].toInt() and 255
                val g = rowBytes[offset + 2].toInt() and 255
                val b = rowBytes[offset + 3].toInt() and 255

                destination[target++] =
                    (a shl 24) or
                    (r shl 16) or
                    (g shl 8) or b

                offset += 4
            }
        }
    }

    private fun calculate(
        tiles: Array<IntArray>,
        pixel: Int,
        shift: Int,
        method: Method,
        sigma: Double,
        values: DoubleArray
    ): Pair<Int, Long> {

        var sum = 0.0

        for (i in tiles.indices) {
            val v = ((tiles[i][pixel] ushr shift) and 255).toDouble()
            values[i] = v
            sum += v
        }

        val result: Double
        var rejected = 0L

        when (method) {

            Method.AVERAGE -> {
                result = sum / values.size
            }

            Method.MEDIAN -> {
                val sorted = values.clone()
                sorted.sort()

                val middle = sorted.size / 2

                result = if (sorted.size % 2 == 1) {
                    sorted[middle]
                } else {
                    (sorted[middle - 1] + sorted[middle]) / 2.0
                }
            }

            Method.SIGMA_CLIP -> {

                val mean = sum / values.size

                var variance = 0.0

                for (v in values) {
                    val difference = v - mean
                    variance += difference * difference
                }

                val sd = sqrt(variance / values.size)

                var keptSum = 0.0
                var keptCount = 0

                for (v in values) {
                    if (sd < 1e-9 || abs(v - mean) <= sigma * sd) {
                        keptSum += v
                        keptCount++
                    } else {
                        rejected++
                    }
                }

                result = if (keptCount > 0) {
                    keptSum / keptCount
                } else {
                    mean
                }
            }
        }

        return result.toInt().coerceIn(0, 255) to rejected
    }

    /**
     * Exports the raw ARGB result to uncompressed RGB TIFF.
     * The 8-bit values are expanded to 16-bit.
     */
    fun writeTiff16(
        rawFile: File,
        width: Int,
        height: Int,
        outputFile: File
    ) {

        require(rawFile.isFile)
        require(rawFile.length() == width.toLong() * height * 4L)

        outputFile.parentFile?.mkdirs()

        val entries = 10
        val ifdOffset = 8L
        val ifdSize = 2L + entries * 12L + 4L
        val bitsOffset = ifdOffset + ifdSize
        val pixelOffset = bitsOffset + 6L
        val byteCount = width.toLong() * height * 6L

        RandomAccessFile(rawFile, "r").use { input ->

            BufferedOutputStream(
                FileOutputStream(outputFile)
            ).use { output ->

                fun u16(v: Int) {
                    output.write(v and 255)
                    output.write((v ushr 8) and 255)
                }

                fun u32(v: Long) {
                    u16((v and 0xffff).toInt())
                    u16(((v ushr 16) and 0xffff).toInt())
                }

                fun entry(
                    tag: Int,
                    type: Int,
                    count: Long,
                    value: Long
                ) {
                    u16(tag)
                    u16(type)
                    u32(count)
                    u32(value)
                }

                output.write('I'.code)
                output.write('I'.code)
                u16(42)
                u32(ifdOffset)

                u16(entries)

                entry(256, 4, 1, width.toLong())
                entry(257, 4, 1, height.toLong())
                entry(258, 3, 3, bitsOffset)
                entry(259, 3, 1, 1)
                entry(262, 3, 1, 2)
                entry(273, 4, 1, pixelOffset)
                entry(277, 3, 1, 3)
                entry(278, 4, 1, height.toLong())
                entry(279, 4, 1, byteCount)
                entry(284, 3, 1, 1)

                u32(0)

                repeat(3) {
                    u16(16)
                }

                val buffer = ByteArray(4 * 4096)
                val pixelCount = width.toLong() * height
                var remaining = pixelCount

                while (remaining > 0) {

                    val count = min(4096L, remaining).toInt()

                    input.readFully(buffer, 0, count * 4)

                    var offset = 0

                    repeat(count) {

                        val r = buffer[offset + 1].toInt() and 255
                        val g = buffer[offset + 2].toInt() and 255
                        val b = buffer[offset + 3].toInt() and 255

                        u16(r * 257)
                        u16(g * 257)
                        u16(b * 257)

                        offset += 4
                    }

                    remaining -= count
                }
            }
        }
    }
}
