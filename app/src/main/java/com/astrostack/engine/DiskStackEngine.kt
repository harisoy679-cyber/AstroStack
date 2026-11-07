package com.astrostack.engine

import java.io.BufferedOutputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.util.Arrays
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Disk-based stacking engine.
 *
 * Input:
 * - Raw ARGB pixels, 4 bytes per pixel.
 * - Big-endian integer format.
 *
 * Output:
 * - Raw ARGB pixels, 4 bytes per pixel.
 * - Big-endian integer format.
 *
 * Processing is performed tile by tile.
 */
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
     * Stack raw image files without loading complete frames into RAM.
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
            "Minimal dua frame diperlukan."
        }

        require(width > 0 && height > 0)

        require(tileRows > 0)

        val expectedSize = width.toLong() * height * 4L

        frameFiles.forEach {
            require(it.exists() && it.length() == expectedSize) {
                "Ukuran file frame tidak sesuai: ${it.name}"
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

        val outputWidth = width - left - right
        val outputHeight = height - top - bottom

        require(outputWidth > 0 && outputHeight > 0) {
            "Area gambar setelah alignment terlalu kecil."
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

                while (tileY < outputHeight) {

                    val currentRows = min(
                        tileRows,
                        outputHeight - tileY
                    )

                    val tilePixels =
                        outputWidth * currentRows

                    // Only one tile per input frame is held in RAM.
                    val tiles = Array(frameFiles.size) {
                        IntArray(tilePixels)
                    }

                    for (i in inputs.indices) {

                        val shift = actualShifts[i]

                        val sourceX = left + shift.dx
                        val sourceY = top + tileY + shift.dy

                        readTile(
                            inputs[i],
                            width,
                            sourceX,
                            sourceY,
                            outputWidth,
                            currentRows,
                            tiles[i]
                        )
                    }

                    val values = DoubleArray(frameFiles.size)

                    for (p in 0 until tilePixels) {

                        val red = calculateChannel(
                            tiles,
                            p,
                            16,
                            method,
                            sigma,
                            values
                        )

                        val green = calculateChannel(
                            tiles,
                            p,
                            8,
                            method,
                            sigma,
                            values
                        )

                        val blue = calculateChannel(
                            tiles,
                            p,
                            0,
                            method,
                            sigma,
                            values
                        )

                        rejected += rejectedCount(
                            tiles,
                            p,
                            method,
                            sigma
                        )

                        val pixel =
                            (0xFF shl 24) or
                            (red shl 16) or
                            (green shl 8) or
                            blue

                        output.writeInt(pixel)
                    }

                    tileY += currentRows
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
            outputWidth,
            outputHeight,
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
                val b = rowBytes[offset + 1].toInt() and 255
                val c = rowBytes[offset + 2].toInt() and 255
                val d = rowBytes[offset + 3].toInt() and 255

                destination[target++] =
                    (a shl 24) or
                    (b shl 16) or
                    (c shl 8) or d

                offset += 4
            }
        }
    }

    private fun calculateChannel(
        tiles: Array<IntArray>,
        pixel: Int,
        shift: Int,
        method: Method,
        sigma: Double,
        values: DoubleArray
    ): Int {

        var sum = 0.0

        for (i in tiles.indices) {
            val value =
                ((tiles[i][pixel] ushr shift) and 255).toDouble()

            values[i] = value
            sum += value
        }

        val result = when (method) {

            Method.AVERAGE -> {
                sum / values.size
            }

            Method.MEDIAN -> {
                Arrays.sort(values)
                val middle = values.size / 2

                if (values.size % 2 == 1) {
                    values[middle]
                } else {
                    (values[middle - 1] + values[middle]) / 2.0
                }
            }

            Method.SIGMA_CLIP -> {

                val mean = sum / values.size

                var variance = 0.0

                for (v in values) {
                    val difference = v - mean
                    variance += difference * difference
                }

                variance /= values.size

                val sd = sqrt(variance)

                var keptSum = 0.0
                var keptCount = 0

                for (v in values) {
                    if (sd < 1e-9 ||
                        kotlin.math.abs(v - mean) <= sigma * sd
                    ) {
                        keptSum += v
                        keptCount++
                    }
                }

                if (keptCount == 0) mean
                else keptSum / keptCount
            }
        }

        return result.toInt().coerceIn(0, 255)
    }

    private fun rejectedCount(
        tiles: Array<IntArray>,
        pixel: Int,
        method: Method,
        sigma: Double
    ): Long {

        if (method != Method.SIGMA_CLIP) return 0L

        var sum = 0.0

        for (tile in tiles) {
            val px = tile[pixel]

            val luminance =
                (((px ushr 16) and 255) * 299 +
                 ((px ushr 8) and 255) * 587 +
                 (px and 255) * 114) / 1000.0

            sum += luminance
        }

        val mean = sum / tiles.size

        var variance = 0.0

        for (tile in tiles) {
            val px = tile[pixel]

            val luminance =
                (((px ushr 16) and 255) * 299 +
                 ((px ushr 8) and 255) * 587 +
                 (px and 255) * 114) / 1000.0

            val difference = luminance - mean
            variance += difference * difference
        }

        val sd = sqrt(variance / tiles.size)

        if (sd < 1e-9) return 0L

        var rejected = 0L

        for (tile in tiles) {
            val px = tile[pixel]

            val luminance =
                (((px ushr 16) and 255) * 299 +
                 ((px ushr 8) and 255) * 587 +
                 (px and 255) * 114) / 1000.0

            if (kotlin.math.abs(luminance - mean) > sigma * sd) {
                rejected++
            }
        }

        return rejected
    }
}
