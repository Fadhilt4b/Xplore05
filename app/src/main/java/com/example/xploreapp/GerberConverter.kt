package com.example.xploreapp


import android.os.Parcelable
import kotlinx.parcelize.Parcelize
import kotlin.math.*

class GerberConverter(private val settings: Settings) {

    private val CIRCLE_PASSES    get() = settings.circlePasses
    private val CIRCLE_OFFSET    get() = settings.circleOffset
    private val TRACE_MULTIPASS  get() = settings.traceMultiPass
    private val OBLONG_DIAMETER  = 1.524f
    private val OBLONG_DIAMETER_2 = 1.321f
    private val MAX_W            = 200f   // mm
    private val MAX_H            = 100f   // mm

    fun convert(input: String, fileName: String = ""): ConversionResult {
        val isSvg = fileName.lowercase().endsWith(".svg")
                || input.trimStart().startsWith("<svg")
                || input.trimStart().startsWith("<?xml")
                && input.contains("<svg", ignoreCase = true)

        return if (isSvg) convertSvg(input)
        else              convertGerber(input)
    }

    // ═══════════════════════════════════════════
    //  GERBER PIPELINE
    // ═══════════════════════════════════════════
    private fun convertGerber(text: String): ConversionResult {
        val data = parseGerber(text)
        return generateGcode(data)
    }

    private fun parseGerber(text: String): GerberData {
        val apertures = mutableMapOf<String, Aperture>()
        val flashes   = mutableListOf<FlashObj>()
        val traces    = mutableListOf<TraceObj>()
        var fmt       = FormatSpec(2, 4)
        var curAp: String? = null
        var curX = 0f; var curY = 0f

        val joined = text.replace("\r", "")

        Regex("FSLAX(\\d)(\\d)Y(\\d)(\\d)").find(joined)?.let {
            fmt = FormatSpec(it.groupValues[1].toInt(), it.groupValues[2].toInt())
        }

        Regex("%ADD(\\d+)([A-Z]),([^*]+)\\*%").findAll(joined).forEach { m ->
            val code   = "D${m.groupValues[1]}"
            val type   = m.groupValues[2]
            val params = m.groupValues[3].split("X").map { it.toFloatOrNull() ?: 0f }
            apertures[code] = Aperture(type, params)
        }

        for (rawLine in joined.split("\n")) {
            val line = rawLine.trim()
            if (line.isEmpty() || line.startsWith("%")) continue

            val selM = Regex("^(D\\d{2,})\\*$").find(line)
            if (selM != null && apertures.containsKey(selM.groupValues[1])) {
                curAp = selM.groupValues[1]
                continue
            }

            val coordM = Regex("^(?:X(-?\\d+))?(?:Y(-?\\d+))?(D0[123])\\*$").find(line)
                ?: continue
            val nx = if (coordM.groupValues[1].isNotEmpty()) toMM(coordM.groupValues[1], fmt) else curX
            val ny = if (coordM.groupValues[2].isNotEmpty()) toMM(coordM.groupValues[2], fmt) else curY
            val op = coordM.groupValues[3]

            when (op) {
                "D02" -> { curX = nx; curY = ny }
                "D01" -> {
                    val ap     = apertures[curAp] ?: Aperture("C", listOf(0.1f))
                    val apDiam = ap.params[0]
                    val segLen = sqrt((nx-curX).pow(2) + (ny-curY).pow(2))
                    val isOblong = ap.type == "C"
                            && abs(apDiam - OBLONG_DIAMETER) < 0.01f
                            && abs(segLen - apDiam) <= 0.01f
                    val isOblong2 = ap.type == "C"
                            && abs(apDiam - OBLONG_DIAMETER_2) < 0.01f
                            && abs(segLen - apDiam) <= 0.01f
                    if (isOblong || isOblong2) {
                        flashes.add(FlashObj((curX+nx)/2f, (curY+ny)/2f, "C", ap.params, isOblong=true))
                    } else if (segLen > 0.01f) {
                        traces.add(TraceObj(curX, curY, nx, ny, ap.type, ap.params))
                    }
                    curX = nx; curY = ny
                }
                "D03" -> {
                    val ap = apertures[curAp] ?: Aperture("C", listOf(0.1f))
                    flashes.add(FlashObj(nx, ny, ap.type, ap.params))
                    curX = nx; curY = ny
                }
            }
        }
        return GerberData(flashes, traces, apertures, isSvg = false)
    }

    private fun toMM(s: String, fmt: FormatSpec): Float {
        val neg    = s.startsWith("-")
        val digits = if (neg) s.substring(1) else s
        val padded = digits.padStart(fmt.xi + fmt.xd, '0')
        val v      = "${padded.substring(0, fmt.xi)}.${padded.substring(fmt.xi)}".toFloat()
        return if (neg) -v else v
    }

    // ═══════════════════════════════════════════
    //  SVG PIPELINE
    // ═══════════════════════════════════════════
    private fun convertSvg(svgText: String): ConversionResult {
        val data = parseSvg(svgText)
        return generateGcode(data)
    }

    private fun parseSvg(svgText: String): GerberData {
        val flashes   = mutableListOf<FlashObj>()
        val traces    = mutableListOf<TraceObj>()
        val apertures = mutableMapOf<String, Aperture>()

        // Baca viewBox / width / height
        val vbMatch = Regex("""viewBox=["']([^"']+)["']""").find(svgText)
        val vbParts = vbMatch?.groupValues?.get(1)?.trim()?.split(Regex("[\\s,]+"))?.map { it.toFloatOrNull() ?: 0f }
        val vbX     = vbParts?.getOrElse(0) { 0f } ?: 0f
        val vbY     = vbParts?.getOrElse(1) { 0f } ?: 0f
        val vbW     = vbParts?.getOrElse(2) { 0f } ?: 0f
        val vbH     = vbParts?.getOrElse(3) { 0f } ?: 0f

        val rawW = if (vbW > 0) vbW else attrFloat(svgText, "width",  100f)
        val rawH = if (vbH > 0) vbH else attrFloat(svgText, "height", 100f)

        // Margin 5mm semua sisi → area efektif 190×90mm
        val MARGIN  = 5f
        val areaW   = MAX_W - 2 * MARGIN   // 190mm
        val areaH   = MAX_H - 2 * MARGIN   // 90mm

        // Scale uniform agar fit area efektif
        val scale   = min(areaW / rawW, areaH / rawH)
        val scaledW = rawW * scale
        val scaledH = rawH * scale

        // Offset centering: geser agar gambar berada di tengah 200×100mm
        val centerOffX = MARGIN + (areaW - scaledW) / 2f
        val centerOffY = MARGIN + (areaH - scaledH) / 2f

        // Koordinat: X + centerOffset, Y flip + centerOffset
        val mx: (Float) -> Float = { v -> (v - vbX) * scale + centerOffX }
        val my: (Float) -> Float = { v -> (rawH - v + vbY) * scale + centerOffY }

        // Helper aperture trace
        fun apKey(sw: Float): String {
            val k = "S-%.2f".format(sw)
            if (!apertures.containsKey(k)) apertures[k] = Aperture("C", listOf(sw))
            return k
        }

        // Helper: polyline → traces
        fun addPolyline(pts: List<Pair<Float, Float>>, sw: Float) {
            val ap = apKey(sw)
            for (i in 0 until pts.size - 1) {
                val dx = pts[i+1].first  - pts[i].first
                val dy = pts[i+1].second - pts[i].second
                if (sqrt(dx*dx + dy*dy) < 0.001f) continue
                traces.add(TraceObj(pts[i].first, pts[i].second,
                    pts[i+1].first, pts[i+1].second, "C", listOf(sw)))
            }
        }

        // Parse setiap elemen SVG dengan regex sederhana
        val elemRe = Regex("""<(circle|ellipse|rect|line|polyline|polygon|path)(\s[^>]*)?>""")

        for (m in elemRe.findAll(svgText)) {
            val tag   = m.groupValues[1]
            val attrs = m.groupValues[2]

            val stroke    = attrStr(attrs, "stroke")
            val fill      = attrStr(attrs, "fill")
            val swRaw     = attrFloatInline(attrs, "stroke-width") ?: 1f
            val sw        = max(0.1f, swRaw * scale)

            // Gambar kalau ada stroke eksplisit, atau fill=none (berarti outline intent)
            val hasStroke = stroke.isNotEmpty() && stroke != "none"
            val noFill    = fill == "none" || fill.isEmpty()
            if (!hasStroke && !noFill && tag !in listOf("line", "polyline", "path")) continue

            when (tag) {
                "circle" -> {
                    val cx = mx(attrFloatInline(attrs, "cx") ?: 0f)
                    val cy = my(attrFloatInline(attrs, "cy") ?: 0f)
                    val r  = (attrFloatInline(attrs, "r") ?: 0f) * scale
                    if (r <= 0f) continue
                    val ck = "C-%.2f".format(r * 2)
                    apertures[ck] = Aperture("C", listOf(r * 2))
                    flashes.add(FlashObj(cx, cy, "C", listOf(r * 2)))
                }
                "ellipse" -> {
                    val cx = mx(attrFloatInline(attrs, "cx") ?: 0f)
                    val cy = my(attrFloatInline(attrs, "cy") ?: 0f)
                    val rx = (attrFloatInline(attrs, "rx") ?: 0f) * scale
                    val ry = (attrFloatInline(attrs, "ry") ?: 0f) * scale
                    val pts = (0..36).map { i ->
                        val a = 2 * PI * i / 36
                        Pair(cx + rx * cos(a).toFloat(), cy + ry * sin(a).toFloat())
                    }
                    addPolyline(pts, sw)
                }
                "rect" -> {
                    val x  = mx(attrFloatInline(attrs, "x") ?: 0f)
                    val y  = my(attrFloatInline(attrs, "y") ?: 0f)
                    val w  = (attrFloatInline(attrs, "width")  ?: 0f) * scale
                    val h  = (attrFloatInline(attrs, "height") ?: 0f) * scale
                    if (w <= 0f || h <= 0f) continue
                    val yt = my((attrFloatInline(attrs, "y") ?: 0f) + (attrFloatInline(attrs, "height") ?: 0f))
                    addPolyline(listOf(Pair(x,yt), Pair(x+w,yt), Pair(x+w,y), Pair(x,y), Pair(x,yt)), sw)
                }
                "line" -> {
                    val x1 = mx(attrFloatInline(attrs, "x1") ?: 0f)
                    val y1 = my(attrFloatInline(attrs, "y1") ?: 0f)
                    val x2 = mx(attrFloatInline(attrs, "x2") ?: 0f)
                    val y2 = my(attrFloatInline(attrs, "y2") ?: 0f)
                    addPolyline(listOf(Pair(x1,y1), Pair(x2,y2)), sw)
                }
                "polyline", "polygon" -> {
                    val pointsStr = attrStr(attrs, "points")
                    val nums = pointsStr.trim().split(Regex("[\\s,]+"))
                        .mapNotNull { it.toFloatOrNull() }
                    val pts = mutableListOf<Pair<Float,Float>>()
                    var i = 0
                    while (i < nums.size - 1) {
                        pts.add(Pair(mx(nums[i]), my(nums[i+1]))); i += 2
                    }
                    if (tag == "polygon" && pts.isNotEmpty()) pts.add(pts[0])
                    addPolyline(pts, sw)
                }
                "path" -> {
                    val d = attrStr(attrs, "d")
                    if (d.isNotEmpty()) {
                        val subpaths = flattenSvgPath(d, scale, vbX, vbY, rawH, centerOffX, centerOffY)
                        for (sp in subpaths) {
                            if (sp.size >= 2) addPolyline(sp, sw)
                        }
                    }
                }
            }
        }

        return GerberData(flashes, traces, apertures, isSvg = true,
            svgScaledW = scaledW, svgScaledH = scaledH)
    }

    // ── Flatten SVG path d → list of subpath ────
    private fun flattenSvgPath(
        d: String, scale: Float, offX: Float, offY: Float, rawH: Float,
        centerOffX: Float = 0f, centerOffY: Float = 0f
    ): List<List<Pair<Float, Float>>> {
        val px: (Float) -> Float = { v -> (v - offX) * scale + centerOffX }
        val py: (Float) -> Float = { v -> (rawH - v + offY) * scale + centerOffY }

        val subpaths = mutableListOf<List<Pair<Float, Float>>>()
        var cur      = mutableListOf<Pair<Float, Float>>()
        var cx = 0f; var cy = 0f
        var sx = 0f; var sy = 0f

        val re   = Regex("([MmLlHhVvCcSsQqTtAaZz])|([+-]?(?:\\d+\\.?\\d*|\\.\\d+)(?:[eE][+-]?\\d+)?)")
        val toks = re.findAll(d).map { it.value }.toMutableList()
        var ti   = 0
        var lCmd = 'M'

        fun num(): Float = if (ti < toks.size && toks[ti].first().let { it.isDigit() || it == '-' || it == '+' || it == '.' })
            toks[ti++].toFloat() else Float.NaN
        fun hasNum() = ti < toks.size && toks[ti].first().let { it.isDigit() || it == '-' || it == '+' || it == '.' }
        fun abs(cmd: Char) = cmd.isUpperCase()

        while (ti < toks.size) {
            val t = toks[ti]
            if (t.length == 1 && t[0].isLetter()) { lCmd = t[0]; ti++ }

            val isAbs = abs(lCmd)

            when (lCmd.uppercaseChar()) {
                'M' -> {
                    if (cur.size > 1) subpaths.add(cur.toList())
                    cur = mutableListOf()
                    val nx = num(); val ny = num()
                    if (nx.isNaN() || ny.isNaN()) break
                    cx = if (isAbs) nx else cx+nx; cy = if (isAbs) ny else cy+ny
                    sx = cx; sy = cy
                    cur.add(Pair(px(cx), py(cy)))
                    lCmd = if (isAbs) 'L' else 'l'
                }
                'L' -> {
                    while (hasNum()) {
                        val nx = num(); val ny = num()
                        cx = if (isAbs) nx else cx+nx; cy = if (isAbs) ny else cy+ny
                        cur.add(Pair(px(cx), py(cy)))
                    }
                }
                'H' -> {
                    while (hasNum()) {
                        val nx = num()
                        cx = if (isAbs) nx else cx+nx
                        cur.add(Pair(px(cx), py(cy)))
                    }
                }
                'V' -> {
                    while (hasNum()) {
                        val ny = num()
                        cy = if (isAbs) ny else cy+ny
                        cur.add(Pair(px(cx), py(cy)))
                    }
                }
                'C' -> {
                    while (hasNum()) {
                        val x1=num();val y1=num();val x2=num();val y2=num();val ex=num();val ey=num()
                        val ax1=if(isAbs)x1 else cx+x1; val ay1=if(isAbs)y1 else cy+y1
                        val ax2=if(isAbs)x2 else cx+x2; val ay2=if(isAbs)y2 else cy+y2
                        val aex=if(isAbs)ex else cx+ex; val aey=if(isAbs)ey else cy+ey
                        for (s in 1..16) {
                            val t = s/16f; val mt = 1-t
                            val bx = mt*mt*mt*cx + 3*mt*mt*t*ax1 + 3*mt*t*t*ax2 + t*t*t*aex
                            val by = mt*mt*mt*cy + 3*mt*mt*t*ay1 + 3*mt*t*t*ay2 + t*t*t*aey
                            cur.add(Pair(px(bx), py(by)))
                        }
                        cx=aex; cy=aey
                    }
                }
                'Q' -> {
                    while (hasNum()) {
                        val x1=num();val y1=num();val ex=num();val ey=num()
                        val ax1=if(isAbs)x1 else cx+x1; val ay1=if(isAbs)y1 else cy+y1
                        val aex=if(isAbs)ex else cx+ex; val aey=if(isAbs)ey else cy+ey
                        for (s in 1..12) {
                            val t = s/12f; val mt = 1-t
                            val bx = mt*mt*cx + 2*mt*t*ax1 + t*t*aex
                            val by = mt*mt*cy + 2*mt*t*ay1 + t*t*aey
                            cur.add(Pair(px(bx), py(by)))
                        }
                        cx=aex; cy=aey
                    }
                }
                'A' -> {
                    while (hasNum()) {
                        num();num();num();num();num()  // rx,ry,rot,largeArc,sweep (approx)
                        val ex=num(); val ey=num()
                        val aex=if(isAbs)ex else cx+ex; val aey=if(isAbs)ey else cy+ey
                        for (s in 1..18) {
                            val t = s/18f
                            cur.add(Pair(px(cx + t*(aex-cx)), py(cy + t*(aey-cy))))
                        }
                        cx=aex; cy=aey
                    }
                }
                'Z' -> {
                    if (cur.isNotEmpty()) cur.add(Pair(px(sx), py(sy)))
                    subpaths.add(cur.toList()); cur = mutableListOf()
                    cx=sx; cy=sy
                }
                else -> ti++
            }
        }
        if (cur.size > 1) subpaths.add(cur.toList())
        return subpaths
    }

    // ── XML attr helpers ────────────────────────
    private fun attrStr(attrs: String, name: String): String {
        val m = Regex("""$name=["']([^"']*)["']""").find(attrs)
        return m?.groupValues?.get(1)?.trim() ?: ""
    }
    private fun attrFloat(src: String, name: String, default: Float): Float {
        val m = Regex("""$name=["']([^"']*)["']""").find(src)
        return m?.groupValues?.get(1)?.toFloatOrNull() ?: default
    }
    private fun attrFloatInline(attrs: String, name: String): Float? {
        val m = Regex("""$name=["']([^"']*)["']""").find(attrs)
        return m?.groupValues?.get(1)?.toFloatOrNull()
    }

    // ═══════════════════════════════════════════
    //  G-CODE GENERATOR  (sama untuk Gerber & SVG)
    // ═══════════════════════════════════════════
    private fun generateGcode(data: GerberData): ConversionResult {
        val lines = mutableListOf<String>()
        val F     = settings.feedrate
        val penW  = settings.penWidth

        lines += "G21"; lines += "G90"; lines += "G00 X0.000 Y0.000"; lines += ""

        for (obj in data.flashes) {
            when (obj.apType) {
                "C" -> genCircleFlash(obj, F, lines)
                "R" -> genRectFlash(obj, penW, F, lines)
                "P" -> genPolygonFlash(obj, F, lines)
            }
        }
        for (obj in data.traces) genTrace(obj, penW, F, lines)

        lines += "G00 X0.000 Y0.000"; lines += "M30"

        val gcodeText = lines.joinToString("\n")
        return ConversionResult(
            gcode        = gcodeText,
            previewLines = parseGcodeToPreview(gcodeText),
            stats        = Stats(data.traces.size, data.flashes.size, lines.size),
            apertures    = data.apertures,
            isSvg        = data.isSvg,
            svgScaledW   = data.svgScaledW,
            svgScaledH   = data.svgScaledH
        )
    }

    // ── Circle flash: oblong=3pass(out,mid,in), biasa=2pass ─
    private fun genCircleFlash(obj: FlashObj, F: Int, out: MutableList<String>) {
        val r = obj.apParams[0] / 2f
        if (obj.isOblong) {
            listOf(r + CIRCLE_OFFSET, r, r - CIRCLE_OFFSET).forEach { ri ->
                if (ri > 0f) drawCircleG02(obj.x, obj.y, ri, F, out)
            }
        } else {
            for (i in 0 until CIRCLE_PASSES) {
                val ri = r - i * CIRCLE_OFFSET
                if (ri <= 0f) break
                drawCircleG02(obj.x, obj.y, ri, F, out)
            }
        }
    }

    private fun genRectFlash(obj: FlashObj, penW: Float, F: Int, out: MutableList<String>) {
        val w = obj.apParams[0]; val h = if (obj.apParams.size > 1) obj.apParams[1] else w
        zigzag(obj.x-w/2f, obj.y-h/2f, obj.x+w/2f, obj.y+h/2f, penW, F, out)
    }

    private fun genPolygonFlash(obj: FlashObj, F: Int, out: MutableList<String>) {
        val r = obj.apParams[0] / 2f
        for (i in 0 until CIRCLE_PASSES) {
            val ri = r - i * CIRCLE_OFFSET; if (ri <= 0f) break
            drawCircleG02(obj.x, obj.y, ri, F, out)
        }
    }

    private fun drawCircleG02(cx: Float, cy: Float, r: Float, F: Int, out: MutableList<String>) {
        val sx = cx - r
        out += "G00 X${f3(sx)} Y${f3(cy)}"
        out += "G02 X${f3(sx)} Y${f3(cy)} I${f4(r)} J0.0000 F$F"
    }

    private fun zigzag(x0: Float, y0: Float, x1: Float, y1: Float, penW: Float, F: Int, out: MutableList<String>) {
        out += "G00 X${f3(x0)} Y${f3(y0)}"
        out += "G01 X${f3(x0)} Y${f3(y0)} F$F"
        var y = y0; var row = 0
        while (roundF(y) <= roundF(y1)) {
            val xe = if (row % 2 == 0) x1 else x0
            out += "G01 X${f3(xe)} Y${f3(y)} F$F"
            val ny = roundF(y + penW)
            if (ny <= roundF(y1)) out += "G01 X${f3(xe)} Y${f3(ny)} F$F"
            y = ny; row++
        }
    }

    private fun genTrace(obj: TraceObj, penW: Float, F: Int, out: MutableList<String>) {
        val w = obj.apParams[0]
        val passes = maxOf(1, round(w / penW).toInt())
        if (passes <= 1 || !TRACE_MULTIPASS) {
            out += "G00 X${f3(obj.x1)} Y${f3(obj.y1)}"
            out += "G01 X${f3(obj.x1)} Y${f3(obj.y1)} F$F"
            out += "G01 X${f3(obj.x2)} Y${f3(obj.y2)} F$F"
            return
        }
        val dx = obj.x2-obj.x1; val dy = obj.y2-obj.y1
        val len = sqrt(dx*dx+dy*dy); if (len < 0.001f) return
        val nx = -dy/len; val ny = dx/len
        val startOff = -(passes-1)*penW/2f
        for (i in 0 until passes) {
            val off = startOff + i*penW
            val px1=obj.x1+nx*off; val py1=obj.y1+ny*off
            val px2=obj.x2+nx*off; val py2=obj.y2+ny*off
            out += "G00 X${f3(px1)} Y${f3(py1)}"
            out += "G01 X${f3(px1)} Y${f3(py1)} F$F"
            out += "G01 X${f3(px2)} Y${f3(py2)} F$F"
        }
    }

    private fun parseGcodeToPreview(gcode: String): List<PreviewLine> {
        val result = mutableListOf<PreviewLine>()
        var curX = 0f; var curY = 0f
        val xRe = Regex("X([-.\\d]+)"); val yRe = Regex("Y([-.\\d]+)"); val iRe = Regex("I([-.\\d]+)")
        for (raw in gcode.split("\n")) {
            val t  = raw.trim()
            val nx = xRe.find(t)?.groupValues?.get(1)?.toFloatOrNull()
            val ny = yRe.find(t)?.groupValues?.get(1)?.toFloatOrNull()
            when {
                t.startsWith("G00") -> {
                    val tx=nx?:curX; val ty=ny?:curY
                    if (tx!=curX||ty!=curY) result.add(PreviewLine(curX,curY,tx,ty,true))
                    curX=tx; curY=ty
                }
                t.startsWith("G01") -> {
                    val tx=nx?:curX; val ty=ny?:curY
                    if (tx!=curX||ty!=curY) result.add(PreviewLine(curX,curY,tx,ty,false))
                    curX=tx; curY=ty
                }
                t.startsWith("G02") -> {
                    val iVal=iRe.find(t)?.groupValues?.get(1)?.toFloatOrNull()?:0f
                    val r=abs(iVal); val ccx=curX+iVal; var px=curX; var py=curY
                    for (i in 1..36) {
                        val angle=PI*2*i/36
                        val ex=(ccx-r* cos(angle)).toFloat(); val ey=(curY-r* sin(angle)).toFloat()
                        result.add(PreviewLine(px,py,ex,ey,false)); px=ex; py=ey
                    }
                }
            }
        }
        return result
    }

    private fun f3(v: Float) = "%.3f".format(v)
    private fun f4(v: Float) = "%.4f".format(v)
    private fun roundF(v: Float) = "%.4f".format(v).toFloat()
}

// ═══════════════════════════════════════════
//  Data Classes
// ═══════════════════════════════════════════
data class FormatSpec(val xi: Int, val xd: Int)
data class Aperture(val type: String, val params: List<Float>)

data class FlashObj(
    val x: Float, val y: Float,
    val apType: String, val apParams: List<Float>,
    val isOblong: Boolean = false
)
data class TraceObj(
    val x1: Float, val y1: Float, val x2: Float, val y2: Float,
    val apType: String, val apParams: List<Float>
)
data class GerberData(
    val flashes   : List<FlashObj>,
    val traces    : List<TraceObj>,
    val apertures : Map<String, Aperture>,
    val isSvg     : Boolean = false,
    val svgScaledW: Float   = 0f,
    val svgScaledH: Float   = 0f
)
data class Settings(
    val penWidth      : Float   = 0.5f,
    val feedrate      : Int     = 60,
    val circlePasses  : Int     = 2,
    val circleOffset  : Float   = 0.25f,
    val traceMultiPass: Boolean = true,
    val traceOffset   : Float   = 0.5f
)

@Parcelize
data class LineSegment(
    val x1: Float,
    val y1: Float,
    val x2: Float,
    val y2: Float
) : Parcelable

@Parcelize
data class PreviewLine(val x1: Float, val y1: Float, val x2: Float, val y2: Float, val isTravel: Boolean) : Parcelable
data class ConversionResult(
    val gcode        : String,
    val previewLines : List<PreviewLine>,
    val stats        : Stats,
    val apertures    : Map<String, Aperture> = emptyMap(),
    val isSvg        : Boolean = false,
    val svgScaledW   : Float   = 0f,
    val svgScaledH   : Float   = 0f
)
data class Stats(val traces: Int, val flashes: Int, val gcodeLines: Int)

//package com.example.xploreapp
//
//import kotlin.math.sqrt
//import kotlin.math.abs
//import kotlin.math.round
//
//class GerberConverter(private val settings: Settings) {
//
//    private val CIRCLE_PASSES    get() = settings.circlePasses
//    private val CIRCLE_OFFSET    get() = settings.circleOffset
//    private val TRACE_MULTIPASS  get() = settings.traceMultiPass
//
//    // Diameter aperture pin header oblong (EAGLE standard)
//    private val OBLONG_PAD_DIAMETER = 1.524f
//    private val MAX_W            = 200f   // mm
//    private val MAX_H            = 100f   // mm
//
//    fun convert(gerberText: String): ConversionResult {
//        val data = parseGerber(gerberText)
//        return generateGcode(data)
//    }
//
//    // ═══════════════════════════════════════════
//    //  PARSER
//    // ═══════════════════════════════════════════
//    private fun parseGerber(text: String): GerberData {
//        val apertures = mutableMapOf<String, Aperture>()
//        val flashes   = mutableListOf<FlashObj>()
//        val traces    = mutableListOf<TraceObj>()
//
//        var fmt = FormatSpec(2, 4)
//        var curAp: String? = null
//        var curX = 0f
//        var curY = 0f
//
//        val joined = text.replace("\r", "")
//
//        val fmtM = Regex("FSLAX(\\d)(\\d)Y(\\d)(\\d)").find(joined)
//        if (fmtM != null) {
//            fmt = FormatSpec(
//                xi = fmtM.groupValues[1].toInt(),
//                xd = fmtM.groupValues[2].toInt()
//            )
//        }
//
//        val apRe = Regex("%ADD(\\d+)([A-Z]),([^*]+)\\*%")
//        for (m in apRe.findAll(joined)) {
//            val code   = "D${m.groupValues[1]}"
//            val type   = m.groupValues[2]
//            val params = m.groupValues[3].split("X").map { it.toFloatOrNull() ?: 0f }
//            apertures[code] = Aperture(type, params)
//        }
//
//        for (rawLine in joined.split("\n")) {
//            val line = rawLine.trim()
//            if (line.isEmpty() || line.startsWith("%")) continue
//
//            val selM = Regex("^(D\\d{2,})\\*$").find(line)
//            if (selM != null && apertures.containsKey(selM.groupValues[1])) {
//                curAp = selM.groupValues[1]
//                continue
//            }
//
//            val coordM = Regex("^(?:X(-?\\d+))?(?:Y(-?\\d+))?(D0[123])\\*$").find(line)
//                ?: continue
//
//            val nx = if (coordM.groupValues[1].isNotEmpty()) toMM(coordM.groupValues[1], fmt) else curX
//            val ny = if (coordM.groupValues[2].isNotEmpty()) toMM(coordM.groupValues[2], fmt) else curY
//            val op = coordM.groupValues[3]
//
//            when (op) {
//                "D02" -> { curX = nx; curY = ny }
//
//                "D01" -> {
//                    val ap     = apertures[curAp] ?: Aperture("C", listOf(0.1f))
//                    val apDiam = ap.params[0]
//                    val dx     = nx - curX
//                    val dy     = ny - curY
//                    val segLen = sqrt(dx * dx + dy * dy)
//
//                    // Deteksi oblong pad pin header:
//                    // aperture C dengan diameter 1.524mm DAN panjang garis == diameter ±0.01
//                    val isOblong = ap.type == "C"
//                            && abs(apDiam - OBLONG_PAD_DIAMETER) < 0.01f
//                            && abs(segLen - apDiam) <= 0.01f
//
//                    if (isOblong) {
//                        // Jadikan flash circle di titik tengah, tandai isOblong
//                        flashes.add(FlashObj(
//                            x        = (curX + nx) / 2f,
//                            y        = (curY + ny) / 2f,
//                            apType   = "C",
//                            apParams = ap.params,
//                            isOblong = true
//                        ))
//                    } else if (segLen > 0.01f) {
//                        traces.add(TraceObj(
//                            x1 = curX, y1 = curY,
//                            x2 = nx,   y2 = ny,
//                            apType   = ap.type,
//                            apParams = ap.params
//                        ))
//                    }
//                    curX = nx; curY = ny
//                }
//
//                "D03" -> {
//                    val ap = apertures[curAp] ?: Aperture("C", listOf(0.1f))
//                    flashes.add(FlashObj(
//                        x        = nx, y = ny,
//                        apType   = ap.type,
//                        apParams = ap.params,
//                        isOblong = false
//                    ))
//                    curX = nx; curY = ny
//                }
//            }
//        }
//
//        return GerberData(flashes = flashes, traces = traces)
//    }
//
//    private fun toMM(s: String, fmt: FormatSpec): Float {
//        val neg    = s.startsWith("-")
//        val digits = if (neg) s.substring(1) else s
//        val total  = fmt.xi + fmt.xd
//        val padded = digits.padStart(total, '0')
//        val v      = "${padded.substring(0, fmt.xi)}.${padded.substring(fmt.xi)}".toFloat()
//        return if (neg) -v else v
//    }
//
//    // ═══════════════════════════════════════════
//    //  G-CODE GENERATOR
//    // ═══════════════════════════════════════════
//    private fun generateGcode(data: GerberData): ConversionResult {
//        val lines = mutableListOf<String>()
//        val F     = settings.feedrate
//        val penW  = settings.penWidth
//
//        lines += "G21"
//        lines += "G90"
//        lines += "G00 X0.000 Y0.000"
//        lines += ""
//
//        // 1. Flash pads
//        for (obj in data.flashes) {
//            when (obj.apType) {
//                "C" -> genCircleFlash(obj, F, lines)
//                "R" -> genRectFlash(obj, penW, F, lines)
//                "P" -> genPolygonFlash(obj, F, lines)
//            }
//        }
//
//        // 2. Traces
//        for (obj in data.traces) {
//            genTrace(obj, penW, F, lines)
//        }
//
//        lines += "G00 X0.000 Y0.000"
//        lines += "M30"
//
//        val gcodeText = lines.joinToString("\n")
//        return ConversionResult(
//            gcode        = gcodeText,
//            previewLines = parseGcodeToPreview(gcodeText),
//            stats        = Stats(
//                traces     = data.traces.size,
//                flashes    = data.flashes.size,
//                gcodeLines = lines.size
//            )
//        )
//    }
//
//    // ── Circle flash ──────────────────────────────
//    // Oblong pad : 3 pass → r+offset (luar), r (utama), r-offset (dalam)
//    // Circle biasa: 2 pass → r, r-offset (ke dalam saja)
//    // Polygon (P) : sama seperti circle biasa → 2 pass ke dalam
//    private fun genCircleFlash(obj: FlashObj, F: Int, out: MutableList<String>) {
//        val r  = obj.apParams[0] / 2f
//        val cx = obj.x
//        val cy = obj.y
//
//        if (obj.isOblong) {
//            // 3 pass: luar → utama → dalam
//            listOf(r + CIRCLE_OFFSET, r, r - CIRCLE_OFFSET).forEach { ri ->
//                if (ri > 0f) drawCircleG02(cx, cy, ri, F, out)
//            }
//        } else {
//            // 2 pass ke dalam
//            for (i in 0 until CIRCLE_PASSES) {
//                val ri = r - i * CIRCLE_OFFSET
//                if (ri <= 0f) break
//                drawCircleG02(cx, cy, ri, F, out)
//            }
//        }
//    }
//
//    // ── Rectangle flash → zig-zag fill ───────────
//    private fun genRectFlash(obj: FlashObj, penW: Float, F: Int, out: MutableList<String>) {
//        val w  = obj.apParams[0]
//        val h  = if (obj.apParams.size > 1) obj.apParams[1] else w
//        val x0 = obj.x - w / 2f; val x1 = obj.x + w / 2f
//        val y0 = obj.y - h / 2f; val y1 = obj.y + h / 2f
//        zigzag(x0, y0, x1, y1, penW, F, out)
//    }
//
//    // ── Polygon flash → circle 2 pass (sama seperti C biasa) ─
//    private fun genPolygonFlash(obj: FlashObj, F: Int, out: MutableList<String>) {
//        val r  = obj.apParams[0] / 2f
//        for (i in 0 until CIRCLE_PASSES) {
//            val ri = r - i * CIRCLE_OFFSET
//            if (ri <= 0f) break
//            drawCircleG02(obj.x, obj.y, ri, F, out)
//        }
//    }
//
//    // ── Gambar satu lingkaran G02 penuh ──────────
//    private fun drawCircleG02(cx: Float, cy: Float, r: Float, F: Int, out: MutableList<String>) {
//        val sx = cx - r
//        out += "G00 X${f3(sx)} Y${f3(cy)}"
//        out += "G02 X${f3(sx)} Y${f3(cy)} I${f4(r)} J0.0000 F$F"
//    }
//
//    // ── Zig-zag fill ─────────────────────────────
//    private fun zigzag(
//        x0: Float, y0: Float, x1: Float, y1: Float,
//        penW: Float, F: Int, out: MutableList<String>
//    ) {
//        out += "G00 X${f3(x0)} Y${f3(y0)}"
//        out += "G01 X${f3(x0)} Y${f3(y0)} F$F"
//        var y   = y0
//        var row = 0
//        while (roundF(y) <= roundF(y1)) {
//            val xe = if (row % 2 == 0) x1 else x0
//            out += "G01 X${f3(xe)} Y${f3(y)} F$F"
//            val ny = roundF(y + penW)
//            if (ny <= roundF(y1)) out += "G01 X${f3(xe)} Y${f3(ny)} F$F"
//            y = ny; row++
//        }
//    }
//
//    // ── Trace → multi-pass paralel ───────────────
//    private fun genTrace(obj: TraceObj, penW: Float, F: Int, out: MutableList<String>) {
//        val w      = obj.apParams[0]
//        val passes = maxOf(1, round(w / penW).toInt())
//
//        if (passes <= 1 || !TRACE_MULTIPASS) {
//            out += "G00 X${f3(obj.x1)} Y${f3(obj.y1)}"
//            out += "G01 X${f3(obj.x1)} Y${f3(obj.y1)} F$F"
//            out += "G01 X${f3(obj.x2)} Y${f3(obj.y2)} F$F"
//            return
//        }
//
//        val dx       = obj.x2 - obj.x1
//        val dy       = obj.y2 - obj.y1
//        val len      = sqrt(dx * dx + dy * dy)
//        if (len < 0.001f) return
//
//        val nx       = -dy / len
//        val ny       =  dx / len
//        val startOff = -(passes - 1) * penW / 2f
//
//        for (i in 0 until passes) {
//            val off = startOff + i * penW
//            val px1 = obj.x1 + nx * off; val py1 = obj.y1 + ny * off
//            val px2 = obj.x2 + nx * off; val py2 = obj.y2 + ny * off
//            out += "G00 X${f3(px1)} Y${f3(py1)}"
//            out += "G01 X${f3(px1)} Y${f3(py1)} F$F"
//            out += "G01 X${f3(px2)} Y${f3(py2)} F$F"
//        }
//    }
//
//    // ── Parse G-code → PreviewLine ───────────────
//    private fun parseGcodeToPreview(gcode: String): List<PreviewLine> {
//        val result = mutableListOf<PreviewLine>()
//        var curX   = 0f
//        var curY   = 0f
//        val xRe    = Regex("X([-.\\d]+)")
//        val yRe    = Regex("Y([-.\\d]+)")
//        val iRe    = Regex("I([-.\\d]+)")
//
//        for (raw in gcode.split("\n")) {
//            val t  = raw.trim()
//            val nx = xRe.find(t)?.groupValues?.get(1)?.toFloatOrNull()
//            val ny = yRe.find(t)?.groupValues?.get(1)?.toFloatOrNull()
//
//            when {
//                t.startsWith("G00") -> {
//                    val tx = nx ?: curX; val ty = ny ?: curY
//                    if (tx != curX || ty != curY)
//                        result.add(PreviewLine(curX, curY, tx, ty, isTravel = true))
//                    curX = tx; curY = ty
//                }
//                t.startsWith("G01") -> {
//                    val tx = nx ?: curX; val ty = ny ?: curY
//                    if (tx != curX || ty != curY)
//                        result.add(PreviewLine(curX, curY, tx, ty, isTravel = false))
//                    curX = tx; curY = ty
//                }
//                t.startsWith("G02") -> {
//                    val iVal = iRe.find(t)?.groupValues?.get(1)?.toFloatOrNull() ?: 0f
//                    val r    = abs(iVal)
//                    val cx   = curX + iVal
//                    val cy   = curY
//                    var px   = curX; var py = curY
//                    for (i in 1..36) {
//                        val angle = Math.PI * 2 * i / 36
//                        val ex = (cx - r * kotlin.math.cos(angle)).toFloat()
//                        val ey = (cy - r * kotlin.math.sin(angle)).toFloat()
//                        result.add(PreviewLine(px, py, ex, ey, isTravel = false))
//                        px = ex; py = ey
//                    }
//                }
//            }
//        }
//        return result
//    }
//
//    private fun f3(v: Float) = "%.3f".format(v)
//    private fun f4(v: Float) = "%.4f".format(v)
//    private fun roundF(v: Float) = "%.4f".format(v).toFloat()
//}
//
//// ═══════════════════════════════════════════
////  Data Classes
//// ═══════════════════════════════════════════
//data class FormatSpec(val xi: Int, val xd: Int)
//data class Aperture(val type: String, val params: List<Float>)
//
//data class FlashObj(
//    val x: Float,
//    val y: Float,
//    val apType: String,
//    val apParams: List<Float>,
//    val isOblong: Boolean = false   // true = oblong pad → 3 pass luar+utama+dalam
//)
//
//data class TraceObj(
//    val x1: Float, val y1: Float,
//    val x2: Float, val y2: Float,
//    val apType: String,
//    val apParams: List<Float>
//)
//
//data class GerberData(val flashes: List<FlashObj>, val traces: List<TraceObj>)
//data class Settings(
//    val penWidth      : Float = 0.5f,
//    val feedrate      : Int   = 60,
//    val circlePasses  : Int   = 2,
//    val circleOffset  : Float = 0.25f,
//    val traceMultiPass: Boolean = true,
//    val traceOffset   : Float = 0.5f
//)
//data class PreviewLine(val x1: Float, val y1: Float, val x2: Float, val y2: Float, val isTravel: Boolean)
//data class ConversionResult(val gcode: String, val previewLines: List<PreviewLine>, val stats: Stats)
//data class Stats(val traces: Int, val flashes: Int, val gcodeLines: Int)

//package com.example.xploreapp
//
//import kotlin.math.sqrt
//import kotlin.math.abs
//import kotlin.math.round
//
//class GerberConverter(private val settings: Settings) {
//
//    private val CIRCLE_PASSES = 2
//    private val CIRCLE_OFFSET = 0.25f
//    private val TRACE_MULTIPASS = true
//
//    // Diameter aperture pin header oblong (EAGLE standard)
//    private val OBLONG_PAD_DIAMETER = 1.524f
//
//    fun convert(gerberText: String): ConversionResult {
//        val data = parseGerber(gerberText)
//        return generateGcode(data)
//    }
//
//    // ═══════════════════════════════════════════
//    //  PARSER
//    // ═══════════════════════════════════════════
//    private fun parseGerber(text: String): GerberData {
//        val apertures = mutableMapOf<String, Aperture>()
//        val flashes   = mutableListOf<FlashObj>()
//        val traces    = mutableListOf<TraceObj>()
//
//        var fmt = FormatSpec(2, 4)
//        var curAp: String? = null
//        var curX = 0f
//        var curY = 0f
//
//        val joined = text.replace("\r", "")
//
//        val fmtM = Regex("FSLAX(\\d)(\\d)Y(\\d)(\\d)").find(joined)
//        if (fmtM != null) {
//            fmt = FormatSpec(
//                xi = fmtM.groupValues[1].toInt(),
//                xd = fmtM.groupValues[2].toInt()
//            )
//        }
//
//        val apRe = Regex("%ADD(\\d+)([A-Z]),([^*]+)\\*%")
//        for (m in apRe.findAll(joined)) {
//            val code   = "D${m.groupValues[1]}"
//            val type   = m.groupValues[2]
//            val params = m.groupValues[3].split("X").map { it.toFloatOrNull() ?: 0f }
//            apertures[code] = Aperture(type, params)
//        }
//
//        for (rawLine in joined.split("\n")) {
//            val line = rawLine.trim()
//            if (line.isEmpty() || line.startsWith("%")) continue
//
//            val selM = Regex("^(D\\d{2,})\\*$").find(line)
//            if (selM != null && apertures.containsKey(selM.groupValues[1])) {
//                curAp = selM.groupValues[1]
//                continue
//            }
//
//            val coordM = Regex("^(?:X(-?\\d+))?(?:Y(-?\\d+))?(D0[123])\\*$").find(line)
//                ?: continue
//
//            val nx = if (coordM.groupValues[1].isNotEmpty()) toMM(coordM.groupValues[1], fmt) else curX
//            val ny = if (coordM.groupValues[2].isNotEmpty()) toMM(coordM.groupValues[2], fmt) else curY
//            val op = coordM.groupValues[3]
//
//            when (op) {
//                "D02" -> { curX = nx; curY = ny }
//
//                "D01" -> {
//                    val ap     = apertures[curAp] ?: Aperture("C", listOf(0.1f))
//                    val apDiam = ap.params[0]
//                    val dx     = nx - curX
//                    val dy     = ny - curY
//                    val segLen = sqrt(dx * dx + dy * dy)
//
//                    // Deteksi oblong pad pin header:
//                    // aperture C dengan diameter 1.524mm DAN panjang garis == diameter ±0.01
//                    val isOblong = ap.type == "C"
//                            && abs(apDiam - OBLONG_PAD_DIAMETER) < 0.01f
//                            && abs(segLen - apDiam) <= 0.01f
//
//                    if (isOblong) {
//                        // Jadikan flash circle di titik tengah, tandai isOblong
//                        flashes.add(FlashObj(
//                            x        = (curX + nx) / 2f,
//                            y        = (curY + ny) / 2f,
//                            apType   = "C",
//                            apParams = ap.params,
//                            isOblong = true
//                        ))
//                    } else if (segLen > 0.01f) {
//                        traces.add(TraceObj(
//                            x1 = curX, y1 = curY,
//                            x2 = nx,   y2 = ny,
//                            apType   = ap.type,
//                            apParams = ap.params
//                        ))
//                    }
//                    curX = nx; curY = ny
//                }
//
//                "D03" -> {
//                    val ap = apertures[curAp] ?: Aperture("C", listOf(0.1f))
//                    flashes.add(FlashObj(
//                        x        = nx, y = ny,
//                        apType   = ap.type,
//                        apParams = ap.params,
//                        isOblong = false
//                    ))
//                    curX = nx; curY = ny
//                }
//            }
//        }
//
//        return GerberData(flashes = flashes, traces = traces)
//    }
//
//    private fun toMM(s: String, fmt: FormatSpec): Float {
//        val neg    = s.startsWith("-")
//        val digits = if (neg) s.substring(1) else s
//        val total  = fmt.xi + fmt.xd
//        val padded = digits.padStart(total, '0')
//        val v      = "${padded.substring(0, fmt.xi)}.${padded.substring(fmt.xi)}".toFloat()
//        return if (neg) -v else v
//    }
//
//    // ═══════════════════════════════════════════
//    //  G-CODE GENERATOR
//    // ═══════════════════════════════════════════
//    private fun generateGcode(data: GerberData): ConversionResult {
//        val lines = mutableListOf<String>()
//        val F     = settings.feedrate
//        val penW  = settings.penWidth
//
//        lines += "G21"
//        lines += "G90"
//        lines += "G00 X0.000 Y0.000"
//        lines += ""
//
//        // 1. Flash pads
//        for (obj in data.flashes) {
//            when (obj.apType) {
//                "C" -> genCircleFlash(obj, F, lines)
//                "R" -> genRectFlash(obj, penW, F, lines)
//                "P" -> genPolygonFlash(obj, F, lines)
//            }
//        }
//
//        // 2. Traces
//        for (obj in data.traces) {
//            genTrace(obj, penW, F, lines)
//        }
//
//        lines += "G00 X0.000 Y0.000"
//        lines += "M30"
//
//        val gcodeText = lines.joinToString("\n")
//        return ConversionResult(
//            gcode        = gcodeText,
//            previewLines = parseGcodeToPreview(gcodeText),
//            stats        = Stats(
//                traces     = data.traces.size,
//                flashes    = data.flashes.size,
//                gcodeLines = lines.size
//            )
//        )
//    }
//
//    // ── Circle flash ──────────────────────────────
//    // Oblong pad : 3 pass → r+offset (luar), r (utama), r-offset (dalam)
//    // Circle biasa: 2 pass → r, r-offset (ke dalam saja)
//    // Polygon (P) : sama seperti circle biasa → 2 pass ke dalam
//    private fun genCircleFlash(obj: FlashObj, F: Int, out: MutableList<String>) {
//        val r  = obj.apParams[0] / 2f
//        val cx = obj.x
//        val cy = obj.y
//
//        if (obj.isOblong) {
//            // 3 pass: luar → utama → dalam
//            listOf(r + CIRCLE_OFFSET, r, r - CIRCLE_OFFSET).forEach { ri ->
//                if (ri > 0f) drawCircleG02(cx, cy, ri, F, out)
//            }
//        } else {
//            // 2 pass ke dalam
//            for (i in 0 until CIRCLE_PASSES) {
//                val ri = r - i * CIRCLE_OFFSET
//                if (ri <= 0f) break
//                drawCircleG02(cx, cy, ri, F, out)
//            }
//        }
//    }
//
//    // ── Rectangle flash → zig-zag fill ───────────
//    private fun genRectFlash(obj: FlashObj, penW: Float, F: Int, out: MutableList<String>) {
//        val w  = obj.apParams[0]
//        val h  = if (obj.apParams.size > 1) obj.apParams[1] else w
//        val x0 = obj.x - w / 2f; val x1 = obj.x + w / 2f
//        val y0 = obj.y - h / 2f; val y1 = obj.y + h / 2f
//        zigzag(x0, y0, x1, y1, penW, F, out)
//    }
//
//    // ── Polygon flash → circle 2 pass (sama seperti C biasa) ─
//    private fun genPolygonFlash(obj: FlashObj, F: Int, out: MutableList<String>) {
//        val r  = obj.apParams[0] / 2f
//        for (i in 0 until CIRCLE_PASSES) {
//            val ri = r - i * CIRCLE_OFFSET
//            if (ri <= 0f) break
//            drawCircleG02(obj.x, obj.y, ri, F, out)
//        }
//    }
//
//    // ── Gambar satu lingkaran G02 penuh ──────────
//    private fun drawCircleG02(cx: Float, cy: Float, r: Float, F: Int, out: MutableList<String>) {
//        val sx = cx - r
//        out += "G00 X${f3(sx)} Y${f3(cy)}"
//        out += "G02 X${f3(sx)} Y${f3(cy)} I${f4(r)} J0.0000 F$F"
//    }
//
//    // ── Zig-zag fill ─────────────────────────────
//    private fun zigzag(
//        x0: Float, y0: Float, x1: Float, y1: Float,
//        penW: Float, F: Int, out: MutableList<String>
//    ) {
//        out += "G00 X${f3(x0)} Y${f3(y0)}"
//        out += "G01 X${f3(x0)} Y${f3(y0)} F$F"
//        var y   = y0
//        var row = 0
//        while (roundF(y) <= roundF(y1)) {
//            val xe = if (row % 2 == 0) x1 else x0
//            out += "G01 X${f3(xe)} Y${f3(y)} F$F"
//            val ny = roundF(y + penW)
//            if (ny <= roundF(y1)) out += "G01 X${f3(xe)} Y${f3(ny)} F$F"
//            y = ny; row++
//        }
//    }
//
//    // ── Trace → multi-pass paralel ───────────────
//    private fun genTrace(obj: TraceObj, penW: Float, F: Int, out: MutableList<String>) {
//        val w      = obj.apParams[0]
//        val passes = maxOf(1, round(w / penW).toInt())
//
//        if (passes <= 1 || !TRACE_MULTIPASS) {
//            out += "G00 X${f3(obj.x1)} Y${f3(obj.y1)}"
//            out += "G01 X${f3(obj.x1)} Y${f3(obj.y1)} F$F"
//            out += "G01 X${f3(obj.x2)} Y${f3(obj.y2)} F$F"
//            return
//        }
//
//        val dx       = obj.x2 - obj.x1
//        val dy       = obj.y2 - obj.y1
//        val len      = sqrt(dx * dx + dy * dy)
//        if (len < 0.001f) return
//
//        val nx       = -dy / len
//        val ny       =  dx / len
//        val startOff = -(passes - 1) * penW / 2f
//
//        for (i in 0 until passes) {
//            val off = startOff + i * penW
//            val px1 = obj.x1 + nx * off; val py1 = obj.y1 + ny * off
//            val px2 = obj.x2 + nx * off; val py2 = obj.y2 + ny * off
//            out += "G00 X${f3(px1)} Y${f3(py1)}"
//            out += "G01 X${f3(px1)} Y${f3(py1)} F$F"
//            out += "G01 X${f3(px2)} Y${f3(py2)} F$F"
//        }
//    }
//
//    // ── Parse G-code → PreviewLine ───────────────
//    private fun parseGcodeToPreview(gcode: String): List<PreviewLine> {
//        val result = mutableListOf<PreviewLine>()
//        var curX   = 0f
//        var curY   = 0f
//        val xRe    = Regex("X([-.\\d]+)")
//        val yRe    = Regex("Y([-.\\d]+)")
//        val iRe    = Regex("I([-.\\d]+)")
//
//        for (raw in gcode.split("\n")) {
//            val t  = raw.trim()
//            val nx = xRe.find(t)?.groupValues?.get(1)?.toFloatOrNull()
//            val ny = yRe.find(t)?.groupValues?.get(1)?.toFloatOrNull()
//
//            when {
//                t.startsWith("G00") -> {
//                    val tx = nx ?: curX; val ty = ny ?: curY
//                    if (tx != curX || ty != curY)
//                        result.add(PreviewLine(curX, curY, tx, ty, isTravel = true))
//                    curX = tx; curY = ty
//                }
//                t.startsWith("G01") -> {
//                    val tx = nx ?: curX; val ty = ny ?: curY
//                    if (tx != curX || ty != curY)
//                        result.add(PreviewLine(curX, curY, tx, ty, isTravel = false))
//                    curX = tx; curY = ty
//                }
//                t.startsWith("G02") -> {
//                    val iVal = iRe.find(t)?.groupValues?.get(1)?.toFloatOrNull() ?: 0f
//                    val r    = abs(iVal)
//                    val cx   = curX + iVal
//                    val cy   = curY
//                    var px   = curX; var py = curY
//                    for (i in 1..36) {
//                        val angle = Math.PI * 2 * i / 36
//                        val ex = (cx - r * kotlin.math.cos(angle)).toFloat()
//                        val ey = (cy - r * kotlin.math.sin(angle)).toFloat()
//                        result.add(PreviewLine(px, py, ex, ey, isTravel = false))
//                        px = ex; py = ey
//                    }
//                }
//            }
//        }
//        return result
//    }
//
//    private fun f3(v: Float) = "%.3f".format(v)
//    private fun f4(v: Float) = "%.4f".format(v)
//    private fun roundF(v: Float) = "%.4f".format(v).toFloat()
//}
//
//data class FormatSpec(val xi: Int, val xd: Int)
//data class Aperture(val type: String, val params: List<Float>)
//
//data class FlashObj(
//    val x: Float,
//    val y: Float,
//    val apType: String,
//    val apParams: List<Float>,
//    val isOblong: Boolean = false   // true = oblong pad → 3 pass luar+utama+dalam
//)
//
//data class TraceObj(
//    val x1: Float, val y1: Float,
//    val x2: Float, val y2: Float,
//    val apType: String,
//    val apParams: List<Float>
//)
//
//data class GerberData(val flashes: List<FlashObj>, val traces: List<TraceObj>)
//data class Settings(val penWidth: Float = 0.5f, val feedrate: Int = 60)
//data class PreviewLine(val x1: Float, val y1: Float, val x2: Float, val y2: Float, val isTravel: Boolean)
//data class ConversionResult(val gcode: String, val previewLines: List<PreviewLine>, val stats: Stats)
//data class Stats(val traces: Int, val flashes: Int, val gcodeLines: Int)

/*
package com.example.xploreapp

import kotlin.math.sqrt
import kotlin.math.abs
import kotlin.math.round

class GerberConverter(private val settings: Settings) {

    // ── Konstanta internal, tidak diubah dari UI ──
    private val CIRCLE_PASSES = 2
    private val CIRCLE_OFFSET = 0.25f
    private val TRACE_MULTIPASS = true   // selalu aktif

    // ═══════════════════════════════════════════
    //  ENTRY POINT
    // ═══════════════════════════════════════════
    fun convert(gerberText: String): ConversionResult {
        val data = parseGerber(gerberText)
        return generateGcode(data)
    }

    // ═══════════════════════════════════════════
    //  PARSER
    // ═══════════════════════════════════════════
    private fun parseGerber(text: String): GerberData {
        val apertures = mutableMapOf<String, Aperture>()
        val flashes   = mutableListOf<FlashObj>()
        val traces    = mutableListOf<TraceObj>()

        var fmt = FormatSpec(2, 4)
        var curAp: String? = null
        var curX = 0f
        var curY = 0f

        // Join multi-line params dulu
        val joined = text.replace("\r", "")

        // Format spec
        val fmtM = Regex("FSLAX(\\d)(\\d)Y(\\d)(\\d)").find(joined)
        if (fmtM != null) {
            fmt = FormatSpec(
                xi = fmtM.groupValues[1].toInt(),
                xd = fmtM.groupValues[2].toInt()
            )
        }

        // Aperture definitions - cari semua %ADD...%
        val apRe = Regex("%ADD(\\d+)([A-Z]),([^*]+)\\*%")
        for (m in apRe.findAll(joined)) {
            val code   = "D${m.groupValues[1]}"
            val type   = m.groupValues[2]
            val params = m.groupValues[3].split("X").map { it.toFloatOrNull() ?: 0f }
            apertures[code] = Aperture(type, params)
        }

        // Process line by line
        for (rawLine in joined.split("\n")) {
            val line = rawLine.trim()
            if (line.isEmpty() || line.startsWith("%")) continue

            // Aperture select: D10* D11* (bukan D01/D02/D03)
            val selM = Regex("^(D\\d{2,})\\*$").find(line)
            if (selM != null && apertures.containsKey(selM.groupValues[1])) {
                curAp = selM.groupValues[1]
                continue
            }

            // Koordinat + operasi
            val coordM = Regex("^(?:X(-?\\d+))?(?:Y(-?\\d+))?(D0[123])\\*$").find(line)
                ?: continue

            val nx = if (coordM.groupValues[1].isNotEmpty())
                toMM(coordM.groupValues[1], fmt) else curX
            val ny = if (coordM.groupValues[2].isNotEmpty())
                toMM(coordM.groupValues[2], fmt) else curY
            val op = coordM.groupValues[3]

            when (op) {
                "D02" -> { curX = nx; curY = ny }

                "D01" -> {
                    val ap = apertures[curAp] ?: Aperture("C", listOf(0.1f))
                    traces.add(TraceObj(
                        x1 = curX, y1 = curY,
                        x2 = nx,   y2 = ny,
                        apType  = ap.type,
                        apParams = ap.params
                    ))
                    curX = nx; curY = ny
                }

                "D03" -> {
                    val ap = apertures[curAp] ?: Aperture("C", listOf(0.1f))
                    flashes.add(FlashObj(
                        x = nx, y = ny,
                        apType  = ap.type,
                        apParams = ap.params
                    ))
                    curX = nx; curY = ny
                }
            }
        }

        return GerberData(flashes = flashes, traces = traces)
    }

    private fun toMM(s: String, fmt: FormatSpec): Float {
        val neg = s.startsWith("-")
        val digits = if (neg) s.substring(1) else s
        val total = fmt.xi + fmt.xd
        val padded = digits.padStart(total, '0')
        val intPart = padded.substring(0, fmt.xi)
        val decPart = padded.substring(fmt.xi)
        val v = "$intPart.$decPart".toFloat()
        return if (neg) -v else v
    }

    // ═══════════════════════════════════════════
    //  G-CODE GENERATOR
    // ═══════════════════════════════════════════
    private fun generateGcode(data: GerberData): ConversionResult {
        val lines = mutableListOf<String>()
        val F = settings.feedrate
        val penW = settings.penWidth

        lines += "G21"
        lines += "G90"
        lines += "G00 X0.000 Y0.000"
        lines += ""

        // 1. Flash pads
        for (obj in data.flashes) {
            when (obj.apType) {
                "C" -> genCircleFlash(obj, F, lines)
                "R" -> genRectFlash(obj, penW, F, lines)
                "P" -> genPolygonFlash(obj, penW, F, lines)
            }
        }

        // 2. Traces
        for (obj in data.traces) {
            genTrace(obj, penW, F, lines)
        }

        lines += "G00 X0.000 Y0.000"
        lines += "M30"

        val gcodeText = lines.joinToString("\n")
        return ConversionResult(
            gcode        = gcodeText,
            previewLines = parseGcodeToPreview(gcodeText),
            stats        = Stats(
                traces     = data.traces.size,
                flashes    = data.flashes.size,
                gcodeLines = lines.size
            )
        )
    }

    // ── Circle flash → G02 multi-pass ─────────
    private fun genCircleFlash(obj: FlashObj, F: Int, out: MutableList<String>) {
        val r = obj.apParams[0] / 2f
        val cx = obj.x; val cy = obj.y
        for (i in 0 until CIRCLE_PASSES) {
            val ri = r - i * CIRCLE_OFFSET
            if (ri <= 0f) break
            val sx = cx - ri
            out += "G00 X${f3(sx)} Y${f3(cy)}"
            out += "G02 X${f3(sx)} Y${f3(cy)} I${f4(ri)} J0.0000 F$F"
        }
    }

    // ── Rectangle flash → zig-zag fill ────────
    private fun genRectFlash(obj: FlashObj, penW: Float, F: Int, out: MutableList<String>) {
        val w = obj.apParams[0]
        val h = if (obj.apParams.size > 1) obj.apParams[1] else w
        val x0 = obj.x - w / 2f; val x1 = obj.x + w / 2f
        val y0 = obj.y - h / 2f; val y1 = obj.y + h / 2f
        zigzag(x0, y0, x1, y1, penW, F, out)
    }

    // ── Polygon flash → bounding box zig-zag ──
    private fun genPolygonFlash(obj: FlashObj, penW: Float, F: Int, out: MutableList<String>) {
        val r = obj.apParams[0] / 2f
        zigzag(obj.x - r, obj.y - r, obj.x + r, obj.y + r, penW, F, out)
    }

    // ── Trace → multi-pass paralel ────────────
    private fun genTrace(obj: TraceObj, penW: Float, F: Int, out: MutableList<String>) {
        val w = obj.apParams[0]
        val passes = maxOf(1, (w / penW).let { round(it).toInt() })

        if (passes <= 1 || !TRACE_MULTIPASS) {
            // 1 pass langsung
            out += "G00 X${f3(obj.x1)} Y${f3(obj.y1)}"
            out += "G01 X${f3(obj.x1)} Y${f3(obj.y1)} F$F"
            out += "G01 X${f3(obj.x2)} Y${f3(obj.y2)} F$F"
            return
        }

        // Hitung arah normal (tegak lurus garis)
        val dx = obj.x2 - obj.x1
        val dy = obj.y2 - obj.y1
        val len = sqrt(dx * dx + dy * dy)
        if (len < 0.001f) return

        val nx = -dy / len   // normal X
        val ny =  dx / len   // normal Y

        val totalW = (passes - 1) * penW
        val startOff = -totalW / 2f

        for (i in 0 until passes) {
            val off = startOff + i * penW
            val px1 = obj.x1 + nx * off; val py1 = obj.y1 + ny * off
            val px2 = obj.x2 + nx * off; val py2 = obj.y2 + ny * off
            out += "G00 X${f3(px1)} Y${f3(py1)}"
            out += "G01 X${f3(px1)} Y${f3(py1)} F$F"
            out += "G01 X${f3(px2)} Y${f3(py2)} F$F"
        }
    }

    // ── Zig-zag fill ──────────────────────────
    private fun zigzag(
        x0: Float, y0: Float, x1: Float, y1: Float,
        penW: Float, F: Int, out: MutableList<String>
    ) {
        out += "G00 X${f3(x0)} Y${f3(y0)}"
        out += "G01 X${f3(x0)} Y${f3(y0)} F$F"
        var y = y0
        var row = 0
        while (roundF(y) <= roundF(y1)) {
            val xe = if (row % 2 == 0) x1 else x0
            out += "G01 X${f3(xe)} Y${f3(y)} F$F"
            val ny = roundF(y + penW)
            if (ny <= roundF(y1)) {
                out += "G01 X${f3(xe)} Y${f3(ny)} F$F"
            }
            y = ny; row++
        }
    }

    // ── Parse G-code -> PreviewLine ──────────
    private fun parseGcodeToPreview(gcode: String): List<PreviewLine> {
        val result  = mutableListOf<PreviewLine>()
        var curX    = 0f
        var curY    = 0f
        val xRe     = Regex("X([-.\\d]+)")
        val yRe     = Regex("Y([-.\\d]+)")
        val iRe     = Regex("I([-.\\d]+)")

        for (raw in gcode.split("\n")) {
            val t = raw.trim()
            val nx = xRe.find(t)?.groupValues?.get(1)?.toFloatOrNull()
            val ny = yRe.find(t)?.groupValues?.get(1)?.toFloatOrNull()

            when {
                t.startsWith("G00") -> {
                    val tx = nx ?: curX
                    val ty = ny ?: curY
                    if (tx != curX || ty != curY)
                        result.add(PreviewLine(curX, curY, tx, ty, isTravel = true))
                    curX = tx; curY = ty
                }
                t.startsWith("G01") -> {
                    val tx = nx ?: curX
                    val ty = ny ?: curY
                    if (tx != curX || ty != curY)
                        result.add(PreviewLine(curX, curY, tx, ty, isTravel = false))
                    curX = tx; curY = ty
                }
                t.startsWith("G02") -> {
                    val iVal = iRe.find(t)?.groupValues?.get(1)?.toFloatOrNull() ?: 0f
                    val r    = kotlin.math.abs(iVal)
                    val cx   = curX + iVal
                    val cy   = curY
                    val steps = 36
                    var px = curX; var py = curY
                    for (i in 1..steps) {
                        val angle = Math.PI * 2 * i / steps
                        val ex = (cx - r * kotlin.math.cos(angle)).toFloat()
                        val ey = (cy - r * kotlin.math.sin(angle)).toFloat()
                        result.add(PreviewLine(px, py, ex, ey, isTravel = false))
                        px = ex; py = ey
                    }
                }
            }
        }
        return result
    }

    // ── Helpers ───────────────────────────────
    private fun f3(v: Float) = "%.3f".format(v)
    private fun f4(v: Float) = "%.4f".format(v)
    private fun roundF(v: Float) = "%.4f".format(v).toFloat()
}

// ═══════════════════════════════════════════
//  Data Classes
// ═══════════════════════════════════════════
data class FormatSpec(val xi: Int, val xd: Int)

data class Aperture(val type: String, val params: List<Float>)

data class FlashObj(
    val x: Float, val y: Float,
    val apType: String, val apParams: List<Float>
)

data class TraceObj(
    val x1: Float, val y1: Float,
    val x2: Float, val y2: Float,
    val apType: String, val apParams: List<Float>
)

data class GerberData(
    val flashes: List<FlashObj>,
    val traces: List<TraceObj>
)

data class Settings(
    val penWidth: Float = 0.5f,
    val feedrate: Int   = 60
)

data class PreviewLine(
    val x1: Float, val y1: Float,
    val x2: Float, val y2: Float,
    val isTravel: Boolean
)

data class ConversionResult(
    val gcode        : String,
    val previewLines : List<PreviewLine>,
    val stats        : Stats
)

data class Stats(
    val traces     : Int,
    val flashes    : Int,
    val gcodeLines : Int
)

/*
package com.example.xploreapp

import kotlin.math.sqrt
import kotlin.math.abs
import kotlin.math.round

class GerberConverter(private val settings: Settings) {

    // ── Konstanta internal, tidak diubah dari UI ──
    private val CIRCLE_PASSES = 2
    private val CIRCLE_OFFSET = 0.25f
    private val TRACE_MULTIPASS = true   // selalu aktif

    // ═══════════════════════════════════════════
    //  ENTRY POINT
    // ═══════════════════════════════════════════
    fun convert(gerberText: String): ConversionResult {
        val data = parseGerber(gerberText)
        return generateGcode(data)
    }

    // ═══════════════════════════════════════════
    //  PARSER
    // ═══════════════════════════════════════════
    private fun parseGerber(text: String): GerberData {
        val apertures = mutableMapOf<String, Aperture>()
        val flashes   = mutableListOf<FlashObj>()
        val traces    = mutableListOf<TraceObj>()

        var fmt = FormatSpec(2, 4)
        var curAp: String? = null
        var curX = 0f
        var curY = 0f

        // Join multi-line params dulu
        val joined = text.replace("\r", "")

        // Format spec
        val fmtM = Regex("FSLAX(\\d)(\\d)Y(\\d)(\\d)").find(joined)
        if (fmtM != null) {
            fmt = FormatSpec(
                xi = fmtM.groupValues[1].toInt(),
                xd = fmtM.groupValues[2].toInt()
            )
        }

        // Aperture definitions - cari semua %ADD...%
        val apRe = Regex("%ADD(\\d+)([A-Z]),([^*]+)\\*%")
        for (m in apRe.findAll(joined)) {
            val code   = "D${m.groupValues[1]}"
            val type   = m.groupValues[2]
            val params = m.groupValues[3].split("X").map { it.toFloatOrNull() ?: 0f }
            apertures[code] = Aperture(type, params)
        }

        // Process line by line
        for (rawLine in joined.split("\n")) {
            val line = rawLine.trim()
            if (line.isEmpty() || line.startsWith("%")) continue

            // Aperture select: D10* D11* (bukan D01/D02/D03)
            val selM = Regex("^(D\\d{2,})\\*$").find(line)
            if (selM != null && apertures.containsKey(selM.groupValues[1])) {
                curAp = selM.groupValues[1]
                continue
            }

            // Koordinat + operasi
            val coordM = Regex("^(?:X(-?\\d+))?(?:Y(-?\\d+))?(D0[123])\\*$").find(line)
                ?: continue

            val nx = if (coordM.groupValues[1].isNotEmpty())
                toMM(coordM.groupValues[1], fmt) else curX
            val ny = if (coordM.groupValues[2].isNotEmpty())
                toMM(coordM.groupValues[2], fmt) else curY
            val op = coordM.groupValues[3]

            when (op) {
                "D02" -> { curX = nx; curY = ny }

                "D01" -> {
                    val ap = apertures[curAp] ?: Aperture("C", listOf(0.1f))
                    traces.add(TraceObj(
                        x1 = curX, y1 = curY,
                        x2 = nx,   y2 = ny,
                        apType  = ap.type,
                        apParams = ap.params
                    ))
                    curX = nx; curY = ny
                }

                "D03" -> {
                    val ap = apertures[curAp] ?: Aperture("C", listOf(0.1f))
                    flashes.add(FlashObj(
                        x = nx, y = ny,
                        apType  = ap.type,
                        apParams = ap.params
                    ))
                    curX = nx; curY = ny
                }
            }
        }

        return GerberData(flashes = flashes, traces = traces)
    }

    private fun toMM(s: String, fmt: FormatSpec): Float {
        val neg = s.startsWith("-")
        val digits = if (neg) s.substring(1) else s
        val total = fmt.xi + fmt.xd
        val padded = digits.padStart(total, '0')
        val intPart = padded.substring(0, fmt.xi)
        val decPart = padded.substring(fmt.xi)
        val v = "$intPart.$decPart".toFloat()
        return if (neg) -v else v
    }

    // ═══════════════════════════════════════════
    //  G-CODE GENERATOR
    // ═══════════════════════════════════════════
    private fun generateGcode(data: GerberData): ConversionResult {
        val lines = mutableListOf<String>()
        val F = settings.feedrate
        val penW = settings.penWidth

        lines += "G21"
        lines += "G90"
        lines += "G00 X0.000 Y0.000"
        lines += ""

        // 1. Flash pads
        for (obj in data.flashes) {
            when (obj.apType) {
                "C" -> genCircleFlash(obj, F, lines)
                "R" -> genRectFlash(obj, penW, F, lines)
                "P" -> genPolygonFlash(obj, penW, F, lines)
            }
        }

        // 2. Traces
        for (obj in data.traces) {
            genTrace(obj, penW, F, lines)
        }

        lines += "G00 X0.000 Y0.000"
        lines += "M30"

        return ConversionResult(
            gcode       = lines.joinToString("\n"),
            traces      = data.traces,
            flashes     = data.flashes,
            stats       = Stats(
                traces     = data.traces.size,
                flashes    = data.flashes.size,
                gcodeLines = lines.size
            )
        )
    }

    // ── Circle flash → G02 multi-pass ─────────
    private fun genCircleFlash(obj: FlashObj, F: Int, out: MutableList<String>) {
        val r = obj.apParams[0] / 2f
        val cx = obj.x; val cy = obj.y
        for (i in 0 until CIRCLE_PASSES) {
            val ri = r - i * CIRCLE_OFFSET
            if (ri <= 0f) break
            val sx = cx - ri
            out += "G00 X${f3(sx)} Y${f3(cy)}"
            out += "G02 X${f3(sx)} Y${f3(cy)} I${f4(ri)} J0.0000 F$F"
        }
    }

    // ── Rectangle flash → zig-zag fill ────────
    private fun genRectFlash(obj: FlashObj, penW: Float, F: Int, out: MutableList<String>) {
        val w = obj.apParams[0]
        val h = if (obj.apParams.size > 1) obj.apParams[1] else w
        val x0 = obj.x - w / 2f; val x1 = obj.x + w / 2f
        val y0 = obj.y - h / 2f; val y1 = obj.y + h / 2f
        zigzag(x0, y0, x1, y1, penW, F, out)
    }

    // ── Polygon flash → bounding box zig-zag ──
    private fun genPolygonFlash(obj: FlashObj, penW: Float, F: Int, out: MutableList<String>) {
        val r = obj.apParams[0] / 2f
        zigzag(obj.x - r, obj.y - r, obj.x + r, obj.y + r, penW, F, out)
    }

    // ── Trace → multi-pass paralel ────────────
    private fun genTrace(obj: TraceObj, penW: Float, F: Int, out: MutableList<String>) {
        val w = obj.apParams[0]
        val passes = maxOf(1, (w / penW).let { round(it).toInt() })

        if (passes <= 1 || !TRACE_MULTIPASS) {
            // 1 pass langsung
            out += "G00 X${f3(obj.x1)} Y${f3(obj.y1)}"
            out += "G01 X${f3(obj.x1)} Y${f3(obj.y1)} F$F"
            out += "G01 X${f3(obj.x2)} Y${f3(obj.y2)} F$F"
            return
        }

        // Hitung arah normal (tegak lurus garis)
        val dx = obj.x2 - obj.x1
        val dy = obj.y2 - obj.y1
        val len = sqrt(dx * dx + dy * dy)
        if (len < 0.001f) return

        val nx = -dy / len   // normal X
        val ny =  dx / len   // normal Y

        val totalW = (passes - 1) * penW
        val startOff = -totalW / 2f

        for (i in 0 until passes) {
            val off = startOff + i * penW
            val px1 = obj.x1 + nx * off; val py1 = obj.y1 + ny * off
            val px2 = obj.x2 + nx * off; val py2 = obj.y2 + ny * off
            out += "G00 X${f3(px1)} Y${f3(py1)}"
            out += "G01 X${f3(px1)} Y${f3(py1)} F$F"
            out += "G01 X${f3(px2)} Y${f3(py2)} F$F"
        }
    }

    // ── Zig-zag fill ──────────────────────────
    private fun zigzag(
        x0: Float, y0: Float, x1: Float, y1: Float,
        penW: Float, F: Int, out: MutableList<String>
    ) {
        out += "G00 X${f3(x0)} Y${f3(y0)}"
        out += "G01 X${f3(x0)} Y${f3(y0)} F$F"
        var y = y0
        var row = 0
        while (roundF(y) <= roundF(y1)) {
            val xe = if (row % 2 == 0) x1 else x0
            out += "G01 X${f3(xe)} Y${f3(y)} F$F"
            val ny = roundF(y + penW)
            if (ny <= roundF(y1)) {
                out += "G01 X${f3(xe)} Y${f3(ny)} F$F"
            }
            y = ny; row++
        }
    }

    // ── Helpers ───────────────────────────────
    private fun f3(v: Float) = "%.3f".format(v)
    private fun f4(v: Float) = "%.4f".format(v)
    private fun roundF(v: Float) = "%.4f".format(v).toFloat()
}

// ═══════════════════════════════════════════
//  Data Classes
// ═══════════════════════════════════════════
data class FormatSpec(val xi: Int, val xd: Int)

data class Aperture(val type: String, val params: List<Float>)

data class FlashObj(
    val x: Float, val y: Float,
    val apType: String, val apParams: List<Float>
)

data class TraceObj(
    val x1: Float, val y1: Float,
    val x2: Float, val y2: Float,
    val apType: String, val apParams: List<Float>
)

data class GerberData(
    val flashes: List<FlashObj>,
    val traces: List<TraceObj>
)

data class Settings(
    val penWidth: Float = 0.5f,
    val feedrate: Int   = 60
)

data class ConversionResult(
    val gcode   : String,
    val traces  : List<TraceObj>,
    val flashes : List<FlashObj>,
    val stats   : Stats
)

data class Stats(
    val traces     : Int,
    val flashes    : Int,
    val gcodeLines : Int
)
*/
 */

//package com.example.xploreapp
//
//import kotlin.math.sqrt
//import kotlin.math.abs
//
//class GerberConverter(private val settings: Settings) {
//
//    fun convert(gerberText: String, drillText: String): ConversionResult {
//        val gerberData = parseGerber(gerberText)
//        val holes = if (drillText.isNotEmpty()) parseDrill(drillText) else emptyList()
//        val pads = matchFlashesWithDrills(gerberData.flashes, gerberData.oblongPads, holes)
//        val optimizedTraces = optimizeTraces(gerberData.traces)
//
//        val lastX = if (optimizedTraces.isNotEmpty())
//            optimizedTraces.last().x2 else 0f
//        val lastY = if (optimizedTraces.isNotEmpty())
//            optimizedTraces.last().y2 else 0f
//
//        val optimizedPads = optimizePads(pads, lastX, lastY)
//        return generateGcode(optimizedTraces, optimizedPads)
//    }
//
//    private fun parseGerber(text: String): GerberData {
//        val lines = text.split("\n")
//        val apertures = mutableMapOf<String, Float>()
//        val traces = mutableListOf<List<TraceSegment>>()
//        val flashes = mutableListOf<Flash>()
//        val oblongPads = mutableListOf<OblongPad>()
//        var currentAperture: String? = null
//        var currentX = 0f
//        var currentY = 0f
//        var unit = 1f
//        var currentPath = mutableListOf<TraceSegment>()
//
//        for (line in lines) {
//            val trimmed = line.trim()
//
//            if (trimmed.contains("%MOMM*%")) unit = 1f
//            else if (trimmed.contains("%MOIN*%")) unit = 25.4f
//
//            // Aperture definition
//            val apertureRegex = "%ADD(\\d+)([CRPOM])[,)]([^*]+)\\*%".toRegex()
//            apertureRegex.find(trimmed)?.let { match ->
//                val id = match.groupValues[1]
//                val params = match.groupValues[3].split("[X,]".toRegex())
//
//                var size = 0f
//                for (param in params) {
//                    val value = param.toFloatOrNull()
//                    if (value != null && value > size) {
//                        size = value
//                    }
//                }
//
//                apertures[id] = size * unit
//            }
//
//            // Aperture selection
//            val selectRegex = "^D(\\d+)\\*\$".toRegex()
//            selectRegex.find(trimmed)?.let { match ->
//                currentAperture = match.groupValues[1]
//            }
//
//            // Coordinates
//            val coordRegex = "X(-?\\d+)Y(-?\\d+)D0([123])\\*\$".toRegex()
//            coordRegex.find(trimmed)?.let { match ->
//                if (currentAperture != null) {
//                    val x = match.groupValues[1].toInt() / 10000f
//                    val y = match.groupValues[2].toInt() / 10000f
//                    val command = match.groupValues[3]
//                    val apertureSize = apertures[currentAperture] ?: 0.5f
//
//                    when (command) {
//                        "3" -> { // Flash
//                            if (apertureSize >= 0.1f && apertureSize <= 20f) {
//                                flashes.add(Flash(x, y, apertureSize))
//                            }
//                        }
//                        "2" -> { // Move
//                            if (currentPath.isNotEmpty()) {
//                                traces.add(currentPath.toList())
//                                currentPath.clear()
//                            }
//                        }
//                        "1" -> { // Draw
//                            val length = sqrt((x - currentX) * (x - currentX) +
//                                    (y - currentY) * (y - currentY))
//
//                            // Oblong pad detection
//                            if (length > 0.5f && length < 5f && apertureSize >= 1.0f &&
//                                length < apertureSize * 3) {
//                                val centerX = (currentX + x) / 2
//                                val centerY = (currentY + y) / 2
//                                val isVertical = abs(y - currentY) > abs(x - currentX)
//
//                                oblongPads.add(OblongPad(
//                                    x = centerX,
//                                    y = centerY,
//                                    width = if (isVertical) apertureSize else length + apertureSize,
//                                    height = if (isVertical) length + apertureSize else apertureSize
//                                ))
//                            } else if (length > 0.01f) {
//                                if (apertureSize >= 0.1f && apertureSize <= 3f) {
//                                    currentPath.add(TraceSegment(currentX, currentY, x, y))
//                                }
//                            }
//                        }
//                    }
//
//                    currentX = x
//                    currentY = y
//                }
//            }
//        }
//
//        if (currentPath.isNotEmpty()) {
//            traces.add(currentPath)
//        }
//
//        return GerberData(traces, flashes, oblongPads)
//    }
//
//    private fun parseDrill(text: String): List<Hole> {
//        val lines = text.split("\n")
//        val tools = mutableMapOf<String, Float>()
//        val holes = mutableListOf<Hole>()
//        var currentTool: String? = null
//        var unit = 1f
//
//        for (line in lines) {
//            val trimmed = line.trim()
//
//            if (trimmed.contains("METRIC")) {
//                unit = 1f
//            } else if (trimmed.contains("INCH")) {
//                unit = 25.4f
//            }
//
//            val toolRegex = "T(\\d+)C([\\d.]+)".toRegex()
//            toolRegex.find(trimmed)?.let { match ->
//                tools[match.groupValues[1]] = match.groupValues[2].toFloat() * unit
//            }
//
//            val selectRegex = "^T(\\d+)\$".toRegex()
//            selectRegex.find(trimmed)?.let { match ->
//                currentTool = match.groupValues[1]
//            }
//
//            val coordRegex = "X(-?\\d+)Y(-?\\d+)".toRegex()
//            coordRegex.find(trimmed)?.let { match ->
//                if (currentTool != null && tools[currentTool] != null) {
//                    val diameter = tools[currentTool]!!
//
//                    if (diameter <= 2.5f) {
//                        holes.add(Hole(
//                            x = match.groupValues[1].toInt() / 1000f,
//                            y = match.groupValues[2].toInt() / 1000f,
//                            diameter = diameter
//                        ))
//                    }
//                }
//            }
//        }
//
//        return holes
//    }
//
//    private fun matchFlashesWithDrills(
//        flashes: List<Flash>,
//        oblongPads: List<OblongPad>,
//        holes: List<Hole>
//    ): List<Pad> {
//        val pads = mutableListOf<Pad>()
//        val usedHoles = mutableSetOf<Int>()
//
//        // Remove duplicates
//        val uniqueFlashes = mutableListOf<Flash>()
//        for (i in flashes.indices) {
//            var isDuplicate = false
//            for (j in uniqueFlashes.indices) {
//                val dist = distance(flashes[i].x, flashes[i].y,
//                    uniqueFlashes[j].x, uniqueFlashes[j].y)
//                if (dist < 0.5f) {
//                    isDuplicate = true
//                    break
//                }
//            }
//            if (!isDuplicate) {
//                uniqueFlashes.add(flashes[i])
//            }
//        }
//
//        // Match oblong pads
//        for (oblong in oblongPads) {
//            var matchedHole: Pair<Hole, Int>? = null
//            var minDist = 0.5f
//
//            for (i in holes.indices) {
//                if (usedHoles.contains(i)) continue
//
//                val hole = holes[i]
//                val dist = distance(oblong.x, oblong.y, hole.x, hole.y)
//
//                if (dist < minDist) {
//                    minDist = dist
//                    matchedHole = Pair(hole, i)
//                }
//            }
//
//            if (matchedHole != null) {
//                usedHoles.add(matchedHole.second)
//                pads.add(Pad(
//                    x = oblong.x,
//                    y = oblong.y,
//                    outerWidth = oblong.width,
//                    outerHeight = oblong.height,
//                    innerSize = matchedHole.first.diameter,
//                    hasHole = true,
//                    isRectangle = true
//                ))
//            } else {
//                pads.add(Pad(
//                    x = oblong.x,
//                    y = oblong.y,
//                    outerWidth = oblong.width,
//                    outerHeight = oblong.height,
//                    innerSize = 0f,
//                    hasHole = false,
//                    isRectangle = true
//                ))
//            }
//        }
//
//        // Match regular flashes
//        for (flash in uniqueFlashes) {
//            var matchedHole: Pair<Hole, Int>? = null
//            var minDist = 0.5f
//
//            for (i in holes.indices) {
//                if (usedHoles.contains(i)) continue
//
//                val hole = holes[i]
//                val dist = distance(flash.x, flash.y, hole.x, hole.y)
//
//                if (dist < minDist) {
//                    minDist = dist
//                    matchedHole = Pair(hole, i)
//                }
//            }
//
//            if (matchedHole != null) {
//                usedHoles.add(matchedHole.second)
//
//                var outerSize = flash.size
//                if (outerSize < matchedHole.first.diameter * 1.5f) {
//                    outerSize = matchedHole.first.diameter * 2.2f
//                }
//
//                pads.add(Pad(
//                    x = flash.x,
//                    y = flash.y,
//                    outerSize = outerSize,
//                    innerSize = matchedHole.first.diameter,
//                    hasHole = true,
//                    isRectangle = false
//                ))
//            } else {
//                pads.add(Pad(
//                    x = flash.x,
//                    y = flash.y,
//                    outerSize = flash.size,
//                    innerSize = 0f,
//                    hasHole = false,
//                    isRectangle = false
//                ))
//            }
//        }
//
//        // Unmatched holes
//        for (i in holes.indices) {
//            if (!usedHoles.contains(i)) {
//                val hole = holes[i]
//                pads.add(Pad(
//                    x = hole.x,
//                    y = hole.y,
//                    outerSize = hole.diameter * 2.2f,
//                    innerSize = hole.diameter,
//                    hasHole = true,
//                    isRectangle = false
//                ))
//            }
//        }
//
//        return pads
//    }
//
//    private fun optimizeTraces(traces: List<List<TraceSegment>>): List<TraceSegment> {
//        val allSegments = traces.map { trace ->
//            TracePath(
//                segments = trace,
//                startX = trace.first().x1,
//                startY = trace.first().y1,
//                endX = trace.last().x2,
//                endY = trace.last().y2
//            )
//        }.toMutableList()
//
//        val result = mutableListOf<TraceSegment>()
//        var currentX = 0f
//        var currentY = 0f
//
//        while (allSegments.isNotEmpty()) {
//            var minDist = Float.MAX_VALUE
//            var minIdx = 0
//            var shouldReverse = false
//
//            for (i in allSegments.indices) {
//                val trace = allSegments[i]
//
//                val distToStart = distance(currentX, currentY, trace.startX, trace.startY)
//                if (distToStart < minDist) {
//                    minDist = distToStart
//                    minIdx = i
//                    shouldReverse = false
//                }
//
//                val distToEnd = distance(currentX, currentY, trace.endX, trace.endY)
//                if (distToEnd < minDist) {
//                    minDist = distToEnd
//                    minIdx = i
//                    shouldReverse = true
//                }
//            }
//
//            val selected = allSegments.removeAt(minIdx)
//
//            if (shouldReverse) {
//                val reversed = selected.segments.map { seg ->
//                    TraceSegment(seg.x2, seg.y2, seg.x1, seg.y1)
//                }.reversed()
//                result.addAll(reversed)
//                currentX = reversed.last().x2
//                currentY = reversed.last().y2
//            } else {
//                result.addAll(selected.segments)
//                currentX = selected.endX
//                currentY = selected.endY
//            }
//        }
//
//        return result
//    }
//
//    private fun optimizePads(pads: List<Pad>, startX: Float, startY: Float): List<Pad> {
//        val result = mutableListOf<Pad>()
//        val remaining = pads.toMutableList()
//        var currentX = startX
//        var currentY = startY
//
//        while (remaining.isNotEmpty()) {
//            var minDist = Float.MAX_VALUE
//            var minIdx = 0
//
//            for (i in remaining.indices) {
//                val pad = remaining[i]
//                val dist = distance(currentX, currentY, pad.x, pad.y)
//
//                if (dist < minDist) {
//                    minDist = dist
//                    minIdx = i
//                }
//            }
//
//            val selected = remaining.removeAt(minIdx)
//            result.add(selected)
//            currentX = selected.x
//            currentY = selected.y
//        }
//
//        return result
//    }
//
//    private fun generateGcode(traces: List<TraceSegment>, pads: List<Pad>): ConversionResult {
//        val gcode = mutableListOf<String>()
//        gcode.add("; PCB Pen Plotter G-code")
//        gcode.add("; Shapes: Circle to Square, Oblong to Rectangle")
//        gcode.add("; Pen: ${settings.penWidth}mm")
//        gcode.add("")
//        gcode.add("G21")
//        gcode.add("G90")
//        gcode.add("G92 X0 Y0 Z0")
//        gcode.add("G00 Z${String.format("%.1f", settings.penUpHeight)} F${settings.rapidFeed}")
//        gcode.add("")
//
//        var totalDistance = 0f
//        var penUpMoves = 0
//        var currentX = 0f
//        var currentY = 0f
//        var penDown = false
//
//        gcode.add("; === TRACES ===")
//        for (seg in traces) {
//            val isConnected = (abs(currentX - seg.x1) < 0.01f && abs(currentY - seg.y1) < 0.01f)
//
//            if (!isConnected) {
//                if (penDown) {
//                    gcode.add("G00 Z${String.format("%.1f", settings.penUpHeight)}")
//                    penDown = false
//                }
//
//                val moveDist = distance(currentX, currentY, seg.x1, seg.y1)
//                if (moveDist > 0.01f) {
//                    gcode.add("G00 X${String.format("%.4f", seg.x1)} Y${String.format("%.4f", seg.y1)}")
//                    totalDistance += moveDist
//                    penUpMoves++
//                }
//
//                gcode.add("G00 Z${String.format("%.1f", settings.penDownHeight)}")
//                penDown = true
//            }
//
//            gcode.add("G01 X${String.format("%.4f", seg.x2)} Y${String.format("%.4f", seg.y2)} F${settings.drawFeed}")
//            currentX = seg.x2
//            currentY = seg.y2
//        }
//
//        // Pen up before starting pads
//        if (penDown && pads.isNotEmpty()) {
//            gcode.add("G00 Z${String.format("%.1f", settings.penUpHeight)}")
//            penDown = false
//        }
//
//        if (pads.isNotEmpty()) {
//            gcode.add("")
//            gcode.add("; === PADS/VIAS ===")
//
//            for (pad in pads) {
//                val (ox1, oy1, ox2, oy2) = if (pad.isRectangle) {
//                    val halfWidth = pad.outerWidth / 2
//                    val halfHeight = pad.outerHeight / 2
//                    listOf(
//                        pad.x - halfWidth,
//                        pad.y - halfHeight,
//                        pad.x + halfWidth,
//                        pad.y + halfHeight
//                    )
//                } else {
//                    val halfOuter = pad.outerSize / 2
//                    listOf(
//                        pad.x - halfOuter,
//                        pad.y - halfOuter,
//                        pad.x + halfOuter,
//                        pad.y + halfOuter
//                    )
//                }
//
//                val moveDist = distance(currentX, currentY, ox1, oy1)
//                gcode.add("G00 X${String.format("%.4f", ox1)} Y${String.format("%.4f", oy1)}")
//                totalDistance += moveDist
//                penUpMoves++
//
//                gcode.add("G00 Z${String.format("%.1f", settings.penDownHeight)}")
//
//                gcode.add("G01 X${String.format("%.4f", ox2)} Y${String.format("%.4f", oy1)} F${settings.drawFeed}")
//                gcode.add("G01 X${String.format("%.4f", ox2)} Y${String.format("%.4f", oy2)}")
//                gcode.add("G01 X${String.format("%.4f", ox1)} Y${String.format("%.4f", oy2)}")
//                gcode.add("G01 X${String.format("%.4f", ox1)} Y${String.format("%.4f", oy1)}")
//
//                if (pad.hasHole && pad.innerSize > 0.01f) {
//                    val halfInner = pad.innerSize / 2
//                    val ix1 = pad.x - halfInner
//                    val iy1 = pad.y - halfInner
//                    val ix2 = pad.x + halfInner
//                    val iy2 = pad.y + halfInner
//
//                    gcode.add("G01 X${String.format("%.4f", ix1)} Y${String.format("%.4f", iy1)}")
//                    gcode.add("G01 X${String.format("%.4f", ix2)} Y${String.format("%.4f", iy1)}")
//                    gcode.add("G01 X${String.format("%.4f", ix2)} Y${String.format("%.4f", iy2)}")
//                    gcode.add("G01 X${String.format("%.4f", ix1)} Y${String.format("%.4f", iy2)}")
//                    gcode.add("G01 X${String.format("%.4f", ix1)} Y${String.format("%.4f", iy1)}")
//                }
//
//                gcode.add("G00 Z${String.format("%.1f", settings.penUpHeight)}")
//
//                currentX = ox1
//                currentY = oy1
//            }
//        }
//
//        gcode.add("G00 X0.0000 Y0.0000")
//        gcode.add("M02")
//        gcode.add("")
//        gcode.add("; Traces: ${traces.size} | Pads: ${pads.size} (${pads.count { it.hasHole }} with holes) | Pen-ups: $penUpMoves")
//
//        return ConversionResult(
//            gcode = gcode.joinToString("\n"),
//            traces = traces,
//            pads = pads,
//            stats = Stats(
//                traces = traces.size,
//                pads = pads.size,
//                padsWithHoles = pads.count { it.hasHole },
//                penUpMoves = penUpMoves,
//                travelDistance = String.format("%.2f", totalDistance)
//            )
//        )
//    }
//
//    private fun distance(x1: Float, y1: Float, x2: Float, y2: Float): Float {
//        return sqrt((x2 - x1) * (x2 - x1) + (y2 - y1) * (y2 - y1))
//    }
//}
//
//// Data classes
//data class GerberData(
//    val traces: List<List<TraceSegment>>,
//    val flashes: List<Flash>,
//    val oblongPads: List<OblongPad>
//)
//
//data class TraceSegment(val x1: Float, val y1: Float, val x2: Float, val y2: Float)
//
//data class Flash(val x: Float, val y: Float, val size: Float)
//
//data class OblongPad(val x: Float, val y: Float, val width: Float, val height: Float)
//
//data class Hole(val x: Float, val y: Float, val diameter: Float)
//
//data class Pad(
//    val x: Float,
//    val y: Float,
//    val outerSize: Float = 0f,
//    val outerWidth: Float = 0f,
//    val outerHeight: Float = 0f,
//    val innerSize: Float,
//    val hasHole: Boolean,
//    val isRectangle: Boolean
//)
//
//data class TracePath(
//    val segments: List<TraceSegment>,
//    val startX: Float,
//    val startY: Float,
//    val endX: Float,
//    val endY: Float
//)
//
//data class ConversionResult(
//    val gcode: String,
//    val traces: List<TraceSegment>,
//    val pads: List<Pad>,
//    val stats: Stats
//)
//
//data class Stats(
//    val traces: Int,
//    val pads: Int,
//    val padsWithHoles: Int,
//    val penUpMoves: Int,
//    val travelDistance: String
//)
//
//data class Settings(
//    val penWidth: Float,
//    val penUpHeight: Float,
//    val penDownHeight: Float,
//    val rapidFeed: Int,
//    val drawFeed: Int
//)
