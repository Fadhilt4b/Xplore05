package com.example.xploreapp

import kotlin.math.sqrt
import kotlin.math.abs

class GerberConverter(private val settings: Settings) {

    fun convert(gerberText: String, drillText: String): ConversionResult {
        val gerberData = parseGerber(gerberText)
        val holes = if (drillText.isNotEmpty()) parseDrill(drillText) else emptyList()
        val pads = matchFlashesWithDrills(gerberData.flashes, gerberData.oblongPads, holes)
        val optimizedTraces = optimizeTraces(gerberData.traces)

        val lastX = if (optimizedTraces.isNotEmpty())
            optimizedTraces.last().x2 else 0f
        val lastY = if (optimizedTraces.isNotEmpty())
            optimizedTraces.last().y2 else 0f

        val optimizedPads = optimizePads(pads, lastX, lastY)
        return generateGcode(optimizedTraces, optimizedPads)
    }

    private fun parseGerber(text: String): GerberData {
        val lines = text.split("\n")
        val apertures = mutableMapOf<String, Float>()
        val traces = mutableListOf<List<TraceSegment>>()
        val flashes = mutableListOf<Flash>()
        val oblongPads = mutableListOf<OblongPad>()
        var currentAperture: String? = null
        var currentX = 0f
        var currentY = 0f
        var unit = 1f
        var currentPath = mutableListOf<TraceSegment>()

        for (line in lines) {
            val trimmed = line.trim()

            if (trimmed.contains("%MOMM*%")) unit = 1f
            else if (trimmed.contains("%MOIN*%")) unit = 25.4f

            // Aperture definition
            val apertureRegex = "%ADD(\\d+)([CRPOM])[,)]([^*]+)\\*%".toRegex()
            apertureRegex.find(trimmed)?.let { match ->
                val id = match.groupValues[1]
                val params = match.groupValues[3].split("[X,]".toRegex())

                var size = 0f
                for (param in params) {
                    val value = param.toFloatOrNull()
                    if (value != null && value > size) {
                        size = value
                    }
                }

                apertures[id] = size * unit
            }

            // Aperture selection
            val selectRegex = "^D(\\d+)\\*\$".toRegex()
            selectRegex.find(trimmed)?.let { match ->
                currentAperture = match.groupValues[1]
            }

            // Coordinates
            val coordRegex = "X(-?\\d+)Y(-?\\d+)D0([123])\\*\$".toRegex()
            coordRegex.find(trimmed)?.let { match ->
                if (currentAperture != null) {
                    val x = match.groupValues[1].toInt() / 10000f
                    val y = match.groupValues[2].toInt() / 10000f
                    val command = match.groupValues[3]
                    val apertureSize = apertures[currentAperture] ?: 0.5f

                    when (command) {
                        "3" -> { // Flash
                            if (apertureSize >= 0.1f && apertureSize <= 20f) {
                                flashes.add(Flash(x, y, apertureSize))
                            }
                        }
                        "2" -> { // Move
                            if (currentPath.isNotEmpty()) {
                                traces.add(currentPath.toList())
                                currentPath.clear()
                            }
                        }
                        "1" -> { // Draw
                            val length = sqrt((x - currentX) * (x - currentX) +
                                    (y - currentY) * (y - currentY))

                            // Oblong pad detection
                            if (length > 0.5f && length < 5f && apertureSize >= 1.0f &&
                                length < apertureSize * 3) {
                                val centerX = (currentX + x) / 2
                                val centerY = (currentY + y) / 2
                                val isVertical = abs(y - currentY) > abs(x - currentX)

                                oblongPads.add(OblongPad(
                                    x = centerX,
                                    y = centerY,
                                    width = if (isVertical) apertureSize else length + apertureSize,
                                    height = if (isVertical) length + apertureSize else apertureSize
                                ))
                            } else if (length > 0.01f) {
                                if (apertureSize >= 0.1f && apertureSize <= 3f) {
                                    currentPath.add(TraceSegment(currentX, currentY, x, y))
                                }
                            }
                        }
                    }

                    currentX = x
                    currentY = y
                }
            }
        }

        if (currentPath.isNotEmpty()) {
            traces.add(currentPath)
        }

        return GerberData(traces, flashes, oblongPads)
    }

    private fun parseDrill(text: String): List<Hole> {
        val lines = text.split("\n")
        val tools = mutableMapOf<String, Float>()
        val holes = mutableListOf<Hole>()
        var currentTool: String? = null
        var unit = 1f

        for (line in lines) {
            val trimmed = line.trim()

            if (trimmed.contains("METRIC")) {
                unit = 1f
            } else if (trimmed.contains("INCH")) {
                unit = 25.4f
            }

            val toolRegex = "T(\\d+)C([\\d.]+)".toRegex()
            toolRegex.find(trimmed)?.let { match ->
                tools[match.groupValues[1]] = match.groupValues[2].toFloat() * unit
            }

            val selectRegex = "^T(\\d+)\$".toRegex()
            selectRegex.find(trimmed)?.let { match ->
                currentTool = match.groupValues[1]
            }

            val coordRegex = "X(-?\\d+)Y(-?\\d+)".toRegex()
            coordRegex.find(trimmed)?.let { match ->
                if (currentTool != null && tools[currentTool] != null) {
                    val diameter = tools[currentTool]!!

                    if (diameter <= 2.5f) {
                        holes.add(Hole(
                            x = match.groupValues[1].toInt() / 1000f,
                            y = match.groupValues[2].toInt() / 1000f,
                            diameter = diameter
                        ))
                    }
                }
            }
        }

        return holes
    }

    private fun matchFlashesWithDrills(
        flashes: List<Flash>,
        oblongPads: List<OblongPad>,
        holes: List<Hole>
    ): List<Pad> {
        val pads = mutableListOf<Pad>()
        val usedHoles = mutableSetOf<Int>()

        // Remove duplicates
        val uniqueFlashes = mutableListOf<Flash>()
        for (i in flashes.indices) {
            var isDuplicate = false
            for (j in uniqueFlashes.indices) {
                val dist = distance(flashes[i].x, flashes[i].y,
                    uniqueFlashes[j].x, uniqueFlashes[j].y)
                if (dist < 0.5f) {
                    isDuplicate = true
                    break
                }
            }
            if (!isDuplicate) {
                uniqueFlashes.add(flashes[i])
            }
        }

        // Match oblong pads
        for (oblong in oblongPads) {
            var matchedHole: Pair<Hole, Int>? = null
            var minDist = 0.5f

            for (i in holes.indices) {
                if (usedHoles.contains(i)) continue

                val hole = holes[i]
                val dist = distance(oblong.x, oblong.y, hole.x, hole.y)

                if (dist < minDist) {
                    minDist = dist
                    matchedHole = Pair(hole, i)
                }
            }

            if (matchedHole != null) {
                usedHoles.add(matchedHole.second)
                pads.add(Pad(
                    x = oblong.x,
                    y = oblong.y,
                    outerWidth = oblong.width,
                    outerHeight = oblong.height,
                    innerSize = matchedHole.first.diameter,
                    hasHole = true,
                    isRectangle = true
                ))
            } else {
                pads.add(Pad(
                    x = oblong.x,
                    y = oblong.y,
                    outerWidth = oblong.width,
                    outerHeight = oblong.height,
                    innerSize = 0f,
                    hasHole = false,
                    isRectangle = true
                ))
            }
        }

        // Match regular flashes
        for (flash in uniqueFlashes) {
            var matchedHole: Pair<Hole, Int>? = null
            var minDist = 0.5f

            for (i in holes.indices) {
                if (usedHoles.contains(i)) continue

                val hole = holes[i]
                val dist = distance(flash.x, flash.y, hole.x, hole.y)

                if (dist < minDist) {
                    minDist = dist
                    matchedHole = Pair(hole, i)
                }
            }

            if (matchedHole != null) {
                usedHoles.add(matchedHole.second)

                var outerSize = flash.size
                if (outerSize < matchedHole.first.diameter * 1.5f) {
                    outerSize = matchedHole.first.diameter * 2.2f
                }

                pads.add(Pad(
                    x = flash.x,
                    y = flash.y,
                    outerSize = outerSize,
                    innerSize = matchedHole.first.diameter,
                    hasHole = true,
                    isRectangle = false
                ))
            } else {
                pads.add(Pad(
                    x = flash.x,
                    y = flash.y,
                    outerSize = flash.size,
                    innerSize = 0f,
                    hasHole = false,
                    isRectangle = false
                ))
            }
        }

        // Unmatched holes
        for (i in holes.indices) {
            if (!usedHoles.contains(i)) {
                val hole = holes[i]
                pads.add(Pad(
                    x = hole.x,
                    y = hole.y,
                    outerSize = hole.diameter * 2.2f,
                    innerSize = hole.diameter,
                    hasHole = true,
                    isRectangle = false
                ))
            }
        }

        return pads
    }

    private fun optimizeTraces(traces: List<List<TraceSegment>>): List<TraceSegment> {
        val allSegments = traces.map { trace ->
            TracePath(
                segments = trace,
                startX = trace.first().x1,
                startY = trace.first().y1,
                endX = trace.last().x2,
                endY = trace.last().y2
            )
        }.toMutableList()

        val result = mutableListOf<TraceSegment>()
        var currentX = 0f
        var currentY = 0f

        while (allSegments.isNotEmpty()) {
            var minDist = Float.MAX_VALUE
            var minIdx = 0
            var shouldReverse = false

            for (i in allSegments.indices) {
                val trace = allSegments[i]

                val distToStart = distance(currentX, currentY, trace.startX, trace.startY)
                if (distToStart < minDist) {
                    minDist = distToStart
                    minIdx = i
                    shouldReverse = false
                }

                val distToEnd = distance(currentX, currentY, trace.endX, trace.endY)
                if (distToEnd < minDist) {
                    minDist = distToEnd
                    minIdx = i
                    shouldReverse = true
                }
            }

            val selected = allSegments.removeAt(minIdx)

            if (shouldReverse) {
                val reversed = selected.segments.map { seg ->
                    TraceSegment(seg.x2, seg.y2, seg.x1, seg.y1)
                }.reversed()
                result.addAll(reversed)
                currentX = reversed.last().x2
                currentY = reversed.last().y2
            } else {
                result.addAll(selected.segments)
                currentX = selected.endX
                currentY = selected.endY
            }
        }

        return result
    }

    private fun optimizePads(pads: List<Pad>, startX: Float, startY: Float): List<Pad> {
        val result = mutableListOf<Pad>()
        val remaining = pads.toMutableList()
        var currentX = startX
        var currentY = startY

        while (remaining.isNotEmpty()) {
            var minDist = Float.MAX_VALUE
            var minIdx = 0

            for (i in remaining.indices) {
                val pad = remaining[i]
                val dist = distance(currentX, currentY, pad.x, pad.y)

                if (dist < minDist) {
                    minDist = dist
                    minIdx = i
                }
            }

            val selected = remaining.removeAt(minIdx)
            result.add(selected)
            currentX = selected.x
            currentY = selected.y
        }

        return result
    }

    private fun generateGcode(traces: List<TraceSegment>, pads: List<Pad>): ConversionResult {
        val gcode = mutableListOf<String>()
        gcode.add("; PCB Pen Plotter G-code")
        gcode.add("; Shapes: Circle to Square, Oblong to Rectangle")
        gcode.add("; Pen: ${settings.penWidth}mm")
        gcode.add("")
        gcode.add("G21")
        gcode.add("G90")
        gcode.add("G92 X0 Y0 Z0")
        gcode.add("G00 Z${String.format("%.1f", settings.penUpHeight)} F${settings.rapidFeed}")
        gcode.add("")

        var totalDistance = 0f
        var penUpMoves = 0
        var currentX = 0f
        var currentY = 0f
        var penDown = false

        gcode.add("; === TRACES ===")
        for (seg in traces) {
            val isConnected = (abs(currentX - seg.x1) < 0.01f && abs(currentY - seg.y1) < 0.01f)

            if (!isConnected) {
                if (penDown) {
                    gcode.add("G00 Z${String.format("%.1f", settings.penUpHeight)}")
                    penDown = false
                }

                val moveDist = distance(currentX, currentY, seg.x1, seg.y1)
                if (moveDist > 0.01f) {
                    gcode.add("G00 X${String.format("%.4f", seg.x1)} Y${String.format("%.4f", seg.y1)}")
                    totalDistance += moveDist
                    penUpMoves++
                }

                gcode.add("G00 Z${String.format("%.1f", settings.penDownHeight)}")
                penDown = true
            }

            gcode.add("G01 X${String.format("%.4f", seg.x2)} Y${String.format("%.4f", seg.y2)} F${settings.drawFeed}")
            currentX = seg.x2
            currentY = seg.y2
        }

        // Pen up before starting pads
        if (penDown && pads.isNotEmpty()) {
            gcode.add("G00 Z${String.format("%.1f", settings.penUpHeight)}")
            penDown = false
        }

        if (pads.isNotEmpty()) {
            gcode.add("")
            gcode.add("; === PADS/VIAS ===")

            for (pad in pads) {
                val (ox1, oy1, ox2, oy2) = if (pad.isRectangle) {
                    val halfWidth = pad.outerWidth / 2
                    val halfHeight = pad.outerHeight / 2
                    listOf(
                        pad.x - halfWidth,
                        pad.y - halfHeight,
                        pad.x + halfWidth,
                        pad.y + halfHeight
                    )
                } else {
                    val halfOuter = pad.outerSize / 2
                    listOf(
                        pad.x - halfOuter,
                        pad.y - halfOuter,
                        pad.x + halfOuter,
                        pad.y + halfOuter
                    )
                }

                val moveDist = distance(currentX, currentY, ox1, oy1)
                gcode.add("G00 X${String.format("%.4f", ox1)} Y${String.format("%.4f", oy1)}")
                totalDistance += moveDist
                penUpMoves++

                gcode.add("G00 Z${String.format("%.1f", settings.penDownHeight)}")

                gcode.add("G01 X${String.format("%.4f", ox2)} Y${String.format("%.4f", oy1)} F${settings.drawFeed}")
                gcode.add("G01 X${String.format("%.4f", ox2)} Y${String.format("%.4f", oy2)}")
                gcode.add("G01 X${String.format("%.4f", ox1)} Y${String.format("%.4f", oy2)}")
                gcode.add("G01 X${String.format("%.4f", ox1)} Y${String.format("%.4f", oy1)}")

                if (pad.hasHole && pad.innerSize > 0.01f) {
                    val halfInner = pad.innerSize / 2
                    val ix1 = pad.x - halfInner
                    val iy1 = pad.y - halfInner
                    val ix2 = pad.x + halfInner
                    val iy2 = pad.y + halfInner

                    gcode.add("G01 X${String.format("%.4f", ix1)} Y${String.format("%.4f", iy1)}")
                    gcode.add("G01 X${String.format("%.4f", ix2)} Y${String.format("%.4f", iy1)}")
                    gcode.add("G01 X${String.format("%.4f", ix2)} Y${String.format("%.4f", iy2)}")
                    gcode.add("G01 X${String.format("%.4f", ix1)} Y${String.format("%.4f", iy2)}")
                    gcode.add("G01 X${String.format("%.4f", ix1)} Y${String.format("%.4f", iy1)}")
                }

                gcode.add("G00 Z${String.format("%.1f", settings.penUpHeight)}")

                currentX = ox1
                currentY = oy1
            }
        }

        gcode.add("G00 X0.0000 Y0.0000")
        gcode.add("M02")
        gcode.add("")
        gcode.add("; Traces: ${traces.size} | Pads: ${pads.size} (${pads.count { it.hasHole }} with holes) | Pen-ups: $penUpMoves")

        return ConversionResult(
            gcode = gcode.joinToString("\n"),
            traces = traces,
            pads = pads,
            stats = Stats(
                traces = traces.size,
                pads = pads.size,
                padsWithHoles = pads.count { it.hasHole },
                penUpMoves = penUpMoves,
                travelDistance = String.format("%.2f", totalDistance)
            )
        )
    }

    private fun distance(x1: Float, y1: Float, x2: Float, y2: Float): Float {
        return sqrt((x2 - x1) * (x2 - x1) + (y2 - y1) * (y2 - y1))
    }
}

// Data classes
data class GerberData(
    val traces: List<List<TraceSegment>>,
    val flashes: List<Flash>,
    val oblongPads: List<OblongPad>
)

data class TraceSegment(val x1: Float, val y1: Float, val x2: Float, val y2: Float)

data class Flash(val x: Float, val y: Float, val size: Float)

data class OblongPad(val x: Float, val y: Float, val width: Float, val height: Float)

data class Hole(val x: Float, val y: Float, val diameter: Float)

data class Pad(
    val x: Float,
    val y: Float,
    val outerSize: Float = 0f,
    val outerWidth: Float = 0f,
    val outerHeight: Float = 0f,
    val innerSize: Float,
    val hasHole: Boolean,
    val isRectangle: Boolean
)

data class TracePath(
    val segments: List<TraceSegment>,
    val startX: Float,
    val startY: Float,
    val endX: Float,
    val endY: Float
)

data class ConversionResult(
    val gcode: String,
    val traces: List<TraceSegment>,
    val pads: List<Pad>,
    val stats: Stats
)

data class Stats(
    val traces: Int,
    val pads: Int,
    val padsWithHoles: Int,
    val penUpMoves: Int,
    val travelDistance: String
)

data class Settings(
    val penWidth: Float,
    val penUpHeight: Float,
    val penDownHeight: Float,
    val rapidFeed: Int,
    val drawFeed: Int
)