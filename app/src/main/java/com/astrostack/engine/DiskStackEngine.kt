package com.astrostack.engine

import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile
import kotlin.math.*

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
        val outputFile: File,
        val transforms: List<StarAlignmentEngine.Transform> = emptyList()
    )

    private data class Bounds(
        val left: Int,
        val top: Int,
        val right: Int,
        val bottom: Int
    ) {
        val width get() = right - left + 1
        val height get() = bottom - top + 1
    }

    private data class SourceRegion(
        val x: Int,
        val y: Int,
        val width: Int,
        val height: Int,
        val data: ByteArray
    )

    fun stackFiles(
        frameFiles: List<File>,
        width: Int,
        height: Int,
        outputFile: File,
        method: Method = Method.AVERAGE,
        shifts: List<Shift> = emptyList(),
        sigma: Double = 2.5,
        tileRows: Int = 32,
        transforms: List<StarAlignmentEngine.Transform> = emptyList()
    ): Result {

        require(frameFiles.size >= 2) {
            "Pilih minimal dua frame."
        }
        require(width > 0 && height > 0)
        require(tileRows > 0)
        require(sigma > 0.0 && sigma.isFinite())

        val expectedSize = width.toLong() * height * 4L

        frameFiles.forEach {
            require(it.isFile && it.length() == expectedSize) {
                "File frame tidak valid: ${it.name}"
            }
        }

        val actualTransforms = when {
            transforms.isNotEmpty() -> {
                require(transforms.size == frameFiles.size) {
                    "Jumlah transformasi tidak sama dengan jumlah foto."
                }
                transforms
            }

            else -> {
                val actualShifts = if (shifts.isEmpty()) {
                    List(frameFiles.size) { Shift() }
                } else {
                    require(shifts.size == frameFiles.size)
                    shifts
                }

                actualShifts.map {
                    StarAlignmentEngine.Transform(
                        dx = it.dx.toDouble(),
                        dy = it.dy.toDouble(),
                        rotationRadians = 0.0,
                        scale = 1.0,
                        inliers = 0,
                        rmsError = 0.0
                    )
                }
            }
        }

        actualTransforms.forEach {
            require(
                it.dx.isFinite() &&
                it.dy.isFinite() &&
                it.rotationRadians.isFinite() &&
                it.scale.isFinite() &&
                it.scale > 0.0
            ) {
                "Transformasi foto tidak valid."
            }
        }

        val bounds = calculateBounds(
            width,
            height,
            actualTransforms
        )

        require(bounds.width > 0 && bounds.height > 0) {
            "Area gambar setelah alignment tidak valid."
        }

        outputFile.parentFile?.mkdirs()

        val inputs = frameFiles.map {
            RandomAccessFile(it, "r")
        }

        var rejected = 0L

        try {
            BufferedOutputStream(
                FileOutputStream(outputFile)
            ).use { output ->

                var tileY = 0

                while (tileY < bounds.height) {

                    val rows = min(tileRows, bounds.height - tileY)
                    val pixelCount = bounds.width * rows

                    val tiles = Array(frameFiles.size) {
                        IntArray(pixelCount)
                    }

                    for (i in inputs.indices) {

                        val region = readTransformedRegion(
                            input = inputs[i],
                            imageWidth = width,
                            imageHeight = height,
                            bounds = bounds,
                            tileY = tileY,
                            tileRows = rows,
                            transform = actualTransforms[i]
                        )

                        renderTransformedTile(
                            region = region,
                            bounds = bounds,
                            tileY = tileY,
                            tileRows = rows,
                            transform = actualTransforms[i],
                            destination = tiles[i]
                        )
                    }

                    val values = DoubleArray(frameFiles.size)

                    for (p in 0 until pixelCount) {

                        val red = calculate(
                            tiles, p, 16, method, sigma, values
                        )

                        val green = calculate(
                            tiles, p, 8, method, sigma, values
                        )

                        val blue = calculate(
                            tiles, p, 0, method, sigma, values
                        )

                        rejected += red.second
                        rejected += green.second
                        rejected += blue.second

                        val pixel =
                            (0xFF shl 24) or
                            (red.first shl 16) or
                            (green.first shl 8) or
                            blue.first

                        writeIntBigEndian(output, pixel)
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

        val resultShifts = actualTransforms.map {
            Shift(
                dx = it.dx.roundToInt(),
                dy = it.dy.roundToInt(),
                score = it.inliers.toDouble()
            )
        }

        return Result(
            width = bounds.width,
            height = bounds.height,
            shifts = resultShifts,
            rejectedPixels = rejected,
            outputFile = outputFile,
            transforms = actualTransforms
        )
    }

    private fun calculateBounds(
        width: Int,
        height: Int,
        transforms: List<StarAlignmentEngine.Transform>
    ): Bounds {

        var commonLeft = Double.NEGATIVE_INFINITY
        var commonTop = Double.NEGATIVE_INFINITY
        var commonRight = Double.POSITIVE_INFINITY
        var commonBottom = Double.POSITIVE_INFINITY

        for (t in transforms) {

            val corners = listOf(
                Pair(0.0, 0.0),
                Pair((width - 1).toDouble(), 0.0),
                Pair(0.0, (height - 1).toDouble()),
                Pair((width - 1).toDouble(), (height - 1).toDouble())
            )

            val mapped = corners.map { (x, y) ->
                transformPoint(x, y, t)
            }

            commonLeft = max(
                commonLeft,
                mapped.minOf { it.first }
            )

            commonTop = max(
                commonTop,
                mapped.minOf { it.second }
            )

            commonRight = min(
                commonRight,
                mapped.maxOf { it.first }
            )

            commonBottom = min(
                commonBottom,
                mapped.maxOf { it.second }
            )
        }

        require(
            commonLeft.isFinite() &&
            commonTop.isFinite() &&
            commonRight.isFinite() &&
            commonBottom.isFinite()
        ) {
            "Batas alignment tidak valid."
        }

        return Bounds(
            left = ceil(commonLeft).toInt(),
            top = ceil(commonTop).toInt(),
            right = floor(commonRight).toInt(),
            bottom = floor(commonBottom).toInt()
        )
    }

    private fun transformPoint(
        x: Double,
        y: Double,
        t: StarAlignmentEngine.Transform
    ): Pair<Double, Double> {

        val c = cos(t.rotationRadians)
        val s = sin(t.rotationRadians)

        return Pair(
            t.scale * (c * x - s * y) + t.dx,
            t.scale * (s * x + c * y) + t.dy
        )
    }

    private fun inversePoint(
        x: Double,
        y: Double,
        t: StarAlignmentEngine.Transform
    ): Pair<Double, Double> {

        val dx = x - t.dx
        val dy = y - t.dy

        val c = cos(t.rotationRadians)
        val s = sin(t.rotationRadians)

        return Pair(
            (c * dx + s * dy) / t.scale,
            (-s * dx + c * dy) / t.scale
        )
    }

    private fun readTransformedRegion(
        input: RandomAccessFile,
        imageWidth: Int,
        imageHeight: Int,
        bounds: Bounds,
        tileY: Int,
        tileRows: Int,
        transform: StarAlignmentEngine.Transform
    ): SourceRegion {

        val outputLeft = bounds.left.toDouble()
        val outputRight = bounds.left + bounds.width - 1.0
        val outputTop = (bounds.top + tileY).toDouble()
        val outputBottom = outputTop + tileRows - 1.0

        val corners = listOf(
            inversePoint(outputLeft, outputTop, transform),
            inversePoint(outputRight, outputTop, transform),
            inversePoint(outputLeft, outputBottom, transform),
            inversePoint(outputRight, outputBottom, transform)
        )

        val minX = max(
            0,
            floor(corners.minOf { it.first }).toInt() - 1
        )

        val minY = max(
            0,
            floor(corners.minOf { it.second }).toInt() - 1
        )

        val maxX = min(
            imageWidth - 1,
            ceil(corners.maxOf { it.first }).toInt() + 1
        )

        val maxY = min(
            imageHeight - 1,
            ceil(corners.maxOf { it.second }).toInt() + 1
        )

        require(maxX >= minX && maxY >= minY) {
            "Area sumber alignment berada di luar foto."
        }

        val regionWidth = maxX - minX + 1
        val regionHeight = maxY - minY + 1

        val byteCount = regionWidth.toLong() *
            regionHeight.toLong() * 4L

        require(byteCount <= Int.MAX_VALUE) {
            "Area pemrosesan terlalu besar."
        }

        val data = ByteArray(byteCount.toInt())
        val rowBytes = ByteArray(regionWidth * 4)

        var destinationOffset = 0

        for (row in 0 until regionHeight) {

            val position =
                ((minY + row).toLong() * imageWidth + minX) * 4L

            input.seek(position)
            input.readFully(rowBytes)

            System.arraycopy(
                rowBytes,
                0,
                data,
                destinationOffset,
                rowBytes.size
            )

            destinationOffset += rowBytes.size
        }

        return SourceRegion(
            minX,
            minY,
            regionWidth,
            regionHeight,
            data
        )
    }

    private fun renderTransformedTile(
        region: SourceRegion,
        bounds: Bounds,
        tileY: Int,
        tileRows: Int,
        transform: StarAlignmentEngine.Transform,
        destination: IntArray
    ) {

        var outputIndex = 0

        for (row in 0 until tileRows) {

            val targetY = (bounds.top + tileY + row).toDouble()

            for (column in 0 until bounds.width) {

                val targetX = (bounds.left + column).toDouble()

                val source = inversePoint(
                    targetX,
                    targetY,
                    transform
                )

                destination[outputIndex++] =
                    bilinearSample(
                        region,
                        source.first,
                        source.second
                    )
            }
        }
    }

    private fun bilinearSample(
        region: SourceRegion,
        sourceX: Double,
        sourceY: Double
    ): Int {

        val x = (sourceX - region.x)
            .coerceIn(0.0, (region.width - 1).toDouble())

        val y = (sourceY - region.y)
            .coerceIn(0.0, (region.height - 1).toDouble())

        val x0 = floor(x).toInt()
        val y0 = floor(y).toInt()

        val x1 = min(x0 + 1, region.width - 1)
        val y1 = min(y0 + 1, region.height - 1)

        val fx = x - x0
        val fy = y - y0

        val p00 = readPixel(region, x0, y0)
        val p10 = readPixel(region, x1, y0)
        val p01 = readPixel(region, x0, y1)
        val p11 = readPixel(region, x1, y1)

        fun channel(shift: Int): Int {

            val a = ((p00 ushr shift) and 255).toDouble()
            val b = ((p10 ushr shift) and 255).toDouble()
            val c = ((p01 ushr shift) and 255).toDouble()
            val d = ((p11 ushr shift) and 255).toDouble()

            val top = a * (1.0 - fx) + b * fx
            val bottom = c * (1.0 - fx) + d * fx

            return (
                top * (1.0 - fy) + bottom * fy
            ).roundToInt().coerceIn(0, 255)
        }

        return (0xFF shl 24) or
            (channel(16) shl 16) or
            (channel(8) shl 8) or
            channel(0)
    }

    private fun readPixel(
        region: SourceRegion,
        x: Int,
        y: Int
    ): Int {

        val offset = (y * region.width + x) * 4

        val a = region.data[offset].toInt() and 255
        val r = region.data[offset + 1].toInt() and 255
        val g = region.data[offset + 2].toInt() and 255
        val b = region.data[offset + 3].toInt() and 255

        return (a shl 24) or
            (r shl 16) or
            (g shl 8) or b
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
            val v = (
                (tiles[i][pixel] ushr shift) and 255
            ).toDouble()

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
                    if (
                        sd < 1e-9 ||
                        abs(v - mean) <= sigma * sd
                    ) {
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

    private fun writeIntBigEndian(
        output: java.io.OutputStream,
        value: Int
    ) {
        output.write((value ushr 24) and 255)
        output.write((value ushr 16) and 255)
        output.write((value ushr 8) and 255)
        output.write(value and 255)
    }

    fun writeTiff16(
        rawFile: File,
        width: Int,
        height: Int,
        outputFile: File
    ) {

        require(rawFile.isFile)
        require(width > 0 && height > 0)
        require(rawFile.length() == width.toLong() * height * 4L)

        outputFile.parentFile?.mkdirs()

        val entries = 10
        val ifdOffset = 8L
        val ifdSize = 2L + entries * 12L + 4L
        val bitsOffset = ifdOffset + ifdSize
        val pixelOffset = bitsOffset + 6L
        val byteCount = width.toLong() * height * 6L

        require(pixelOffset + byteCount <= 0xffffffffL) {
            "Ukuran terlalu besar untuk format TIFF klasik."
        }

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
