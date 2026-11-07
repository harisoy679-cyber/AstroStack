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

    data class Transform(
        val dx: Double,
        val dy: Double,
        val rotationRadians: Double,
        val scale: Double,
        val inliers: Int,
        val rmsError: Double
    )

    data class Result(
        val width: Int,
        val height: Int,
        val shifts: List<Shift>,
        val rejectedPixels: Long,
        val outputFile: File,
        val transforms: List<Transform> = emptyList()
    )

    private data class Star(
        val x: Double,
        val y: Double,
        val brightness: Double
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

    private data class Region(
        val x: Int,
        val y: Int,
        val width: Int,
        val height: Int,
        val data: ByteArray
    )

    fun detectStars(
        file: File,
        width: Int,
        height: Int,
        maxStars: Int = 150
    ): List<Star> {

        require(file.isFile)
        require(file.length() == width.toLong() * height * 4L)

        val step = maxOf(1, maxOf(width, height) / 1200)
        val sw = (width + step - 1) / step
        val sh = (height + step - 1) / step

        val gray = ByteArray(sw * sh)

        RandomAccessFile(file, "r").use { input ->
            val row = ByteArray(width * 4)

            for (sy in 0 until sh) {
                val sourceY = min(sy * step, height - 1)

                input.seek(sourceY.toLong() * width * 4L)
                input.readFully(row)

                for (sx in 0 until sw) {
                    val sourceX = min(sx * step, width - 1)
                    val offset = sourceX * 4

                    val r = row[offset + 1].toInt() and 255
                    val g = row[offset + 2].toInt() and 255
                    val b = row[offset + 3].toInt() and 255

                    gray[sy * sw + sx] =
                        (0.299 * r + 0.587 * g + 0.114 * b)
                            .toInt().coerceIn(0, 255).toByte()
                }
            }
        }

        val histogram = IntArray(256)
        gray.forEach {
            histogram[it.toInt() and 255]++
        }

        var accumulated = 0
        var background = 0

        for (i in histogram.indices) {
            accumulated += histogram[i]
            if (accumulated >= gray.size / 2) {
                background = i
                break
            }
        }

        val threshold = max(12, background + 12)
        val stars = ArrayList<Star>()

        for (y in 2 until sh - 2) {
            for (x in 2 until sw - 2) {

                val value = gray[y * sw + x].toInt() and 255
                if (value < threshold) continue

                var maximum = true

                for (ny in y - 1..y + 1) {
                    for (nx in x - 1..x + 1) {
                        if (nx == x && ny == y) continue

                        val neighbor =
                            gray[ny * sw + nx].toInt() and 255

                        if (neighbor >= value) {
                            maximum = false
                            break
                        }
                    }
                    if (!maximum) break
                }

                if (!maximum) continue

                var total = 0.0
                var weightedX = 0.0
                var weightedY = 0.0

                for (ny in y - 2..y + 2) {
                    for (nx in x - 2..x + 2) {
                        val pixel =
                            gray[ny * sw + nx].toInt() and 255

                        val weight =
                            max(0, pixel - background).toDouble()

                        total += weight
                        weightedX += nx * weight
                        weightedY += ny * weight
                    }
                }

                if (total <= 0.0) continue

                stars.add(
                    Star(
                        weightedX / total * step,
                        weightedY / total * step,
                        (value - background).toDouble()
                    )
                )
            }
        }

        return stars.sortedByDescending { it.brightness }
            .take(maxStars)
    }

    fun estimate(
        referenceFile: File,
        movingFile: File,
        width: Int,
        height: Int
    ): Transform? {

        val reference = detectStars(referenceFile, width, height)
        val moving = detectStars(movingFile, width, height)

        if (reference.size < 3 || moving.size < 3) return null

        val ref = reference.take(40)
        val mov = moving.take(40)

        var best: Transform? = null
        var bestScore = 0
        var bestError = Double.MAX_VALUE

        for (a in mov) {
            for (b in ref) {

                val dx = b.x - a.x
                val dy = b.y - a.y

                var score = 0
                var error = 0.0

                val used = HashSet<Int>()

                for (star in mov) {

                    val tx = star.x + dx
                    val ty = star.y + dy

                    var nearest = -1
                    var nearestDistance = 25.0

                    for (i in ref.indices) {
                        if (i in used) continue

                        val ddx = ref[i].x - tx
                        val ddy = ref[i].y - ty
                        val distance = ddx * ddx + ddy * ddy

                        if (distance < nearestDistance) {
                            nearestDistance = distance
                            nearest = i
                        }
                    }

                    if (nearest >= 0) {
                        used.add(nearest)
                        score++
                        error += nearestDistance
                    }
                }

                if (
                    score > bestScore ||
                    (score == bestScore && error < bestError)
                ) {
                    bestScore = score
                    bestError = error

                    best = Transform(
                        dx = dx,
                        dy = dy,
                        rotationRadians = 0.0,
                        scale = 1.0,
                        inliers = score,
                        rmsError = sqrt(error / max(1, score))
                    )
                }
            }
        }

        val result = best ?: return null
        return if (result.inliers >= 3) result else null
    }

    fun stackFiles(
        frameFiles: List<File>,
        width: Int,
        height: Int,
        outputFile: File,
        method: Method = Method.AVERAGE,
        shifts: List<Shift> = emptyList(),
        sigma: Double = 2.5,
        tileRows: Int = 16,
        transforms: List<Transform> = emptyList()
    ): Result {

        require(frameFiles.size >= 2)
        require(width > 0 && height > 0)
        require(tileRows > 0)
        require(sigma > 0.0 && sigma.isFinite())

        val expectedSize = width.toLong() * height * 4L

        frameFiles.forEach {
            require(it.isFile && it.length() == expectedSize)
        }

        val actualTransforms = when {
            transforms.isNotEmpty() -> {
                require(transforms.size == frameFiles.size)
                transforms
            }
            shifts.isNotEmpty() -> {
                require(shifts.size == frameFiles.size)
                shifts.map {
                    Transform(
                        it.dx.toDouble(),
                        it.dy.toDouble(),
                        0.0,
                        1.0,
                        0,
                        0.0
                    )
                }
            }
            else -> List(frameFiles.size) {
                Transform(0.0, 0.0, 0.0, 1.0, 0, 0.0)
            }
        }

        actualTransforms.forEach {
            require(
                it.dx.isFinite() &&
                it.dy.isFinite() &&
                it.rotationRadians.isFinite() &&
                it.scale.isFinite() &&
                it.scale > 0.0
            )
        }

        val bounds = calculateBounds(width, height, actualTransforms)

        require(bounds.width > 0 && bounds.height > 0) {
            "Area alignment tidak valid."
        }

        outputFile.parentFile?.mkdirs()

        val inputs = frameFiles.map { RandomAccessFile(it, "r") }
        var rejected = 0L

        try {
            BufferedOutputStream(FileOutputStream(outputFile)).use { output ->

                var tileY = 0

                while (tileY < bounds.height) {

                    val rows = min(tileRows, bounds.height - tileY)
                    val pixelCount = bounds.width * rows

                    val tiles = Array(frameFiles.size) {
                        IntArray(pixelCount)
                    }

                    for (i in inputs.indices) {
                        val region = readRegion(
                            inputs[i],
                            width,
                            height,
                            bounds,
                            tileY,
                            rows,
                            actualTransforms[i]
                        )

                        renderTile(
                            region,
                            bounds,
                            tileY,
                            rows,
                            actualTransforms[i],
                            tiles[i]
                        )
                    }

                    val values = DoubleArray(frameFiles.size)

                    for (p in 0 until pixelCount) {

                        val r = calculate(tiles, p, 16, method, sigma, values)
                        val g = calculate(tiles, p, 8, method, sigma, values)
                        val b = calculate(tiles, p, 0, method, sigma, values)

                        rejected += r.second + g.second + b.second

                        val pixel =
                            (255 shl 24) or
                            (r.first shl 16) or
                            (g.first shl 8) or
                            b.first

                        writeInt(output, pixel)
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
            bounds.width,
            bounds.height,
            actualTransforms.map {
                Shift(it.dx.roundToInt(), it.dy.roundToInt(), it.inliers.toDouble())
            },
            rejected,
            outputFile,
            actualTransforms
        )
    }

    private fun transformPoint(
        x: Double,
        y: Double,
        t: Transform
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
        t: Transform
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

    private fun calculateBounds(
        width: Int,
        height: Int,
        transforms: List<Transform>
    ): Bounds {

        var left = Double.NEGATIVE_INFINITY
        var top = Double.NEGATIVE_INFINITY
        var right = Double.POSITIVE_INFINITY
        var bottom = Double.POSITIVE_INFINITY

        for (t in transforms) {
            val corners = listOf(
                transformPoint(0.0, 0.0, t),
                transformPoint(width - 1.0, 0.0, t),
                transformPoint(0.0, height - 1.0, t),
                transformPoint(width - 1.0, height - 1.0, t)
            )

            left = max(left, corners.minOf { it.first })
            top = max(top, corners.minOf { it.second })
            right = min(right, corners.maxOf { it.first })
            bottom = min(bottom, corners.maxOf { it.second })
        }

        return Bounds(
            ceil(left).toInt(),
            ceil(top).toInt(),
            floor(right).toInt(),
            floor(bottom).toInt()
        )
    }

    private fun readRegion(
        input: RandomAccessFile,
        imageWidth: Int,
        imageHeight: Int,
        bounds: Bounds,
        tileY: Int,
        rows: Int,
        transform: Transform
    ): Region {

        val x0 = bounds.left.toDouble()
        val x1 = (bounds.right).toDouble()
        val y0 = (bounds.top + tileY).toDouble()
        val y1 = (bounds.top + tileY + rows - 1).toDouble()

        val corners = listOf(
            inversePoint(x0, y0, transform),
            inversePoint(x1, y0, transform),
            inversePoint(x0, y1, transform),
            inversePoint(x1, y1, transform)
        )

        val minX = max(0, floor(corners.minOf { it.first }).toInt() - 1)
        val minY = max(0, floor(corners.minOf { it.second }).toInt() - 1)
        val maxX = min(imageWidth - 1, ceil(corners.maxOf { it.first }).toInt() + 1)
        val maxY = min(imageHeight - 1, ceil(corners.maxOf { it.second }).toInt() + 1)

        require(maxX >= minX && maxY >= minY) {
            "Area sumber alignment berada di luar foto."
        }

        val rw = maxX - minX + 1
        val rh = maxY - minY + 1
        val bytes = ByteArray((rw.toLong() * rh * 4L).toInt())
        val row = ByteArray(rw * 4)

        var offset = 0

        for (y in 0 until rh) {
            input.seek(((minY + y).toLong() * imageWidth + minX) * 4L)
            input.readFully(row)
            System.arraycopy(row, 0, bytes, offset, row.size)
            offset += row.size
        }

        return Region(minX, minY, rw, rh, bytes)
    }

    private fun renderTile(
        region: Region,
        bounds: Bounds,
        tileY: Int,
        rows: Int,
        transform: Transform,
        destination: IntArray
    ) {

        var index = 0

        for (y in 0 until rows) {
            for (x in 0 until bounds.width) {

                val source = inversePoint(
                    (bounds.left + x).toDouble(),
                    (bounds.top + tileY + y).toDouble(),
                    transform
                )

                destination[index++] = sample(
                    region,
                    source.first,
                    source.second
                )
            }
        }
    }

    private fun sample(
        region: Region,
        sourceX: Double,
        sourceY: Double
    ): Int {

        val x = (sourceX - region.x).coerceIn(0.0, (region.width - 1).toDouble())
        val y = (sourceY - region.y).coerceIn(0.0, (region.height - 1).toDouble())

        val x0 = floor(x).toInt()
        val y0 = floor(y).toInt()
        val x1 = min(x0 + 1, region.width - 1)
        val y1 = min(y0 + 1, region.height - 1)

        val fx = x - x0
        val fy = y - y0

        fun pixel(px: Int, py: Int): Int {
            val o = (py * region.width + px) * 4
            val a = region.data[o].toInt() and 255
            val r = region.data[o + 1].toInt() and 255
            val g = region.data[o + 2].toInt() and 255
            val b = region.data[o + 3].toInt() and 255
            return (a shl 24) or (r shl 16) or (g shl 8) or b
        }

        val p00 = pixel(x0, y0)
        val p10 = pixel(x1, y0)
        val p01 = pixel(x0, y1)
        val p11 = pixel(x1, y1)

        fun channel(shift: Int): Int {
            val a = ((p00 ushr shift) and 255).toDouble()
            val b = ((p10 ushr shift) and 255).toDouble()
            val c = ((p01 ushr shift) and 255).toDouble()
            val d = ((p11 ushr shift) and 255).toDouble()

            val top = a * (1 - fx) + b * fx
            val bottom = c * (1 - fx) + d * fx

            return (top * (1 - fy) + bottom * fy)
                .roundToInt().coerceIn(0, 255)
        }

        return (255 shl 24) or
            (channel(16) shl 16) or
            (channel(8) shl 8) or
            channel(0)
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

        var rejected = 0L

        val result = when (method) {

            Method.AVERAGE -> sum / values.size

            Method.MEDIAN -> {
                val sorted = values.clone()
                sorted.sort()
                val middle = sorted.size / 2

                if (sorted.size % 2 == 1) {
                    sorted[middle]
                } else {
                    (sorted[middle - 1] + sorted[middle]) / 2.0
                }
            }

            Method.SIGMA_CLIP -> {
                val mean = sum / values.size
                var variance = 0.0

                for (v in values) {
                    variance += (v - mean) * (v - mean)
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

                if (keptCount > 0) keptSum / keptCount else mean
            }
        }

        return result.toInt().coerceIn(0, 255) to rejected
    }

    private fun writeInt(
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
        require(rawFile.length() == width.toLong() * height * 4L)

        outputFile.parentFile?.mkdirs()

        val entries = 10
        val ifdOffset = 8L
        val ifdSize = 2L + entries * 12L + 4L
        val bitsOffset = ifdOffset + ifdSize
        val pixelOffset = bitsOffset + 6L
        val byteCount = width.toLong() * height * 6L

        require(pixelOffset + byteCount <= 0xffffffffL)

        RandomAccessFile(rawFile, "r").use { input ->
            BufferedOutputStream(FileOutputStream(outputFile)).use { output ->

                fun u16(v: Int) {
                    output.write(v and 255)
                    output.write((v ushr 8) and 255)
                }

                fun u32(v: Long) {
                    u16((v and 65535).toInt())
                    u16(((v ushr 16) and 65535).toInt())
                }

                fun entry(tag: Int, type: Int, count: Long, value: Long) {
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
                repeat(3) { u16(16) }

                val buffer = ByteArray(4 * 4096)
                var remaining = width.toLong() * height

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
