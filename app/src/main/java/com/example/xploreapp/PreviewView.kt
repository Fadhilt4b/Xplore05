package com.example.xploreapp

import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import kotlin.math.max
import kotlin.math.min

class PreviewView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private var previewLines = listOf<PreviewLine>()

    // ── Paints (warna asli kamu) ──────────────
    private val tracePaint = Paint().apply {
        color = Color.BLUE
        strokeWidth = 2f
        style = Paint.Style.STROKE
        isAntiAlias = true
    }

    private val travelPaint = Paint().apply {
        color = Color.argb(60, 255, 140, 0)   // oranye tipis = G00 travel
        strokeWidth = 1f
        style = Paint.Style.STROKE
        isAntiAlias = true
        pathEffect = DashPathEffect(floatArrayOf(6f, 8f), 0f)
    }

    private val bgPaint = Paint().apply {
        color = Color.WHITE
        style = Paint.Style.FILL
    }

    private val textPaint = Paint().apply {
        color = Color.GRAY
        textSize = 40f
        isAntiAlias = true
    }

    private val gridPaint = Paint().apply {
        color = Color.rgb(230, 230, 230)
        strokeWidth = 1f
        style = Paint.Style.STROKE
    }

    private val axisPaint = Paint().apply {
        color = Color.rgb(180, 180, 180)
        strokeWidth = 2f
        style = Paint.Style.STROKE
    }

    private val axisTextPaint = Paint().apply {
        color = Color.rgb(100, 100, 100)
        textSize = 24f
        isAntiAlias = true
    }

    private val dimTextPaint = Paint().apply {
        color = Color.rgb(80, 80, 80)
        textSize = 28f
        isAntiAlias = true
        isFakeBoldText = true
    }

    // ── Bounds & transform (sama persis kamu) ─
    private var minX = 0f; private var minY = 0f
    private var maxX = 0f; private var maxY = 0f
    private var dataW = 0f; private var dataH = 0f
    private var scale = 1f
    private var offX = 0f; private var offY = 0f

    private var userZoom = 1f
    private val tableWidth  = 200f   // mm (20 cm)
    private val tableHeight = 100f   // mm (10 cm)
    private var panX = 0f
    private var panY = 0f

    private var lastTouchX = 0f
    private var lastTouchY = 0f
    private var isPanning = false

    private val scaleDetector = ScaleGestureDetector(
        context,
        object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScale(detector: ScaleGestureDetector): Boolean {
                userZoom *= detector.scaleFactor
                userZoom = userZoom.coerceIn(0.5f, 6f)
                invalidate()
                return true
            }
        })

    // ═══════════════════════════════════════
    fun updatePreview(lines: List<PreviewLine>) {
        previewLines = lines
        calcBounds()
        userZoom = 1f; panX = 0f; panY = 0f
        invalidate()
    }

    private fun calcBounds() {
        if (previewLines.isEmpty()) {
            minX = 0f; minY = 0f; maxX = 100f; maxY = 100f
            dataW = 100f; dataH = 100f
            return
        }

        minX = Float.MAX_VALUE; minY = Float.MAX_VALUE
        maxX = -Float.MAX_VALUE; maxY = -Float.MAX_VALUE

        for (l in previewLines) {
            if (l.isTravel) continue   // bounds hanya dari drawn lines
            minX = min(minX, min(l.x1, l.x2)); maxX = max(maxX, max(l.x1, l.x2))
            minY = min(minY, min(l.y1, l.y2)); maxY = max(maxY, max(l.y1, l.y2))
        }

        if (minX == Float.MAX_VALUE) {
            minX = 0f; minY = 0f; maxX = 100f; maxY = 100f
        }

        val px = (maxX - minX) * 0.1f
        val py = (maxY - minY) * 0.1f
        minX -= px; minY -= py; maxX += px; maxY += py

        dataW = maxX - minX
        dataH = maxY - minY
    }

    // ── World → screen (sama persis kamu) ─────
    private fun wx(x: Float) = x * scale + offX
    private fun wy(y: Float) = height - (y * scale + offY)   // flip Y

    // ═══════════════════════════════════════
    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), bgPaint)

        if (previewLines.isEmpty()) {
            val msg = "Preview will appear here"
            canvas.drawText(msg,
                (width - textPaint.measureText(msg)) / 2f,
                height / 2f, textPaint)
            return
        }

        val drawW = width.toFloat()
        val drawH = height.toFloat()

        val sx = drawW / tableWidth
        val sy = drawH / tableHeight

        scale = min(sx, sy) * 0.8f * userZoom

        val contentW = tableWidth * scale
        val contentH = tableHeight * scale

        offX = (drawW - contentW) / 2f + panX
        offY = (drawH - contentH) / 2f - panY

        drawGrid(canvas)
        drawBorder(canvas)
        drawPreviewLines(canvas)
//        drawDimLabel(canvas)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        parent.requestDisallowInterceptTouchEvent(true)
        scaleDetector.onTouchEvent(event)

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                lastTouchX = event.x
                lastTouchY = event.y
                isPanning = true
            }
            MotionEvent.ACTION_MOVE -> {
                if (!scaleDetector.isInProgress && isPanning) {
                    panX += event.x - lastTouchX
                    panY += event.y - lastTouchY
                    lastTouchX = event.x
                    lastTouchY = event.y
                    invalidate()
                }
            }
            MotionEvent.ACTION_UP,
            MotionEvent.ACTION_CANCEL -> {
                isPanning = false
                parent.requestDisallowInterceptTouchEvent(false)
            }
        }
        return true
    }

    private fun drawGrid(canvas: Canvas) {
        val step = 10f   // 1 cm
        var x = 0f
        while (x <= tableWidth) {
            canvas.drawLine(wx(x), wy(0f), wx(x), wy(tableHeight), gridPaint)
            x += step
        }
        var y = 0f
        while (y <= tableHeight) {
            canvas.drawLine(wx(0f), wy(y), wx(tableWidth), wy(y), gridPaint)
            y += step
        }
    }

    private fun drawBorder(canvas: Canvas) {
        canvas.drawRect(wx(0f), wy(tableHeight), wx(tableWidth), wy(0f), axisPaint)
        canvas.drawText("X", wx(tableWidth) - 30f, wy(0f) + 40f, axisTextPaint)
        canvas.drawText("Y", wx(0f) - 40f, wy(tableHeight) + 20f, axisTextPaint)
    }

    // ── Render semua G-code lines ─────────────
    private fun drawPreviewLines(canvas: Canvas) {
        for (l in previewLines) {
            canvas.drawLine(
                wx(l.x1), wy(l.y1),
                wx(l.x2), wy(l.y2),
                if (l.isTravel) travelPaint else tracePaint
            )
        }
    }

    private fun drawDimLabel(canvas: Canvas) {
        val label = "${String.format("%.1f", dataW / 10)}cm × ${String.format("%.1f", dataH / 10)}cm"
        canvas.drawText(label,
            (width - dimTextPaint.measureText(label)) / 2f,
            36f, dimTextPaint)
    }
}

/*
package com.example.xploreapp

import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import kotlin.math.max
import kotlin.math.min

class PreviewView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private var traces  = listOf<TraceObj>()
    private var flashes = listOf<FlashObj>()

    // ── Paints ───────────────────────────────
    private val tracePaint = Paint().apply {
        color = Color.BLUE
        strokeWidth = 2f
        style = Paint.Style.STROKE
        isAntiAlias = true
    }

    private val padFillPaint = Paint().apply {
        color = Color.argb(80, 255, 0, 0)   // merah transparan
        style = Paint.Style.FILL
        isAntiAlias = true
    }

    private val padStrokePaint = Paint().apply {
        color = Color.RED
        strokeWidth = 2f
        style = Paint.Style.STROKE
        isAntiAlias = true
    }

    private val bgPaint = Paint().apply {
        color = Color.WHITE
        style = Paint.Style.FILL
    }

    private val textPaint = Paint().apply {
        color = Color.GRAY
        textSize = 40f
        isAntiAlias = true
    }

    private val gridPaint = Paint().apply {
        color = Color.rgb(230, 230, 230)
        strokeWidth = 1f
        style = Paint.Style.STROKE
    }

    private val axisPaint = Paint().apply {
        color = Color.rgb(180, 180, 180)
        strokeWidth = 2f
        style = Paint.Style.STROKE
    }

    private val axisTextPaint = Paint().apply {
        color = Color.rgb(100, 100, 100)
        textSize = 24f
        isAntiAlias = true
    }

    private val dimTextPaint = Paint().apply {
        color = Color.rgb(80, 80, 80)
        textSize = 28f
        isAntiAlias = true
        isFakeBoldText = true
    }

    // ── Bounds ───────────────────────────────
    private var minX = 0f; private var minY = 0f
    private var maxX = 0f; private var maxY = 0f
    private var dataW = 0f; private var dataH = 0f
    private var scale = 1f
    private var offX = 0f; private var offY = 0f

    private var userZoom = 1f
    private val tableWidth = 200f   // mm (20 cm)
    private val tableHeight = 100f  // mm (10 cm)
    private var panX = 0f
    private var panY = 0f

    private var lastTouchX = 0f
    private var lastTouchY = 0f
    private var isPanning = false
    private val scaleDetector =
        ScaleGestureDetector(
            context,
            object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
                override fun onScale(detector: ScaleGestureDetector): Boolean {
                    userZoom *= detector.scaleFactor
                    userZoom = userZoom.coerceIn(0.5f, 6f)
                    invalidate()
                    return true
                }
            })



    // ═══════════════════════════════════════
    fun updatePreview(newTraces: List<TraceObj>, newFlashes: List<FlashObj>) {
        traces  = newTraces
        flashes = newFlashes
        calcBounds()
        invalidate()
    }

    // ── Hitung bounding box semua objek ──────
    private fun calcBounds() {
        if (traces.isEmpty() && flashes.isEmpty()) {
            minX = 0f; minY = 0f; maxX = 100f; maxY = 100f
            dataW = 100f; dataH = 100f
            return
        }

        minX = Float.MAX_VALUE; minY = Float.MAX_VALUE
        maxX = -Float.MAX_VALUE; maxY = -Float.MAX_VALUE

        for (t in traces) {
            minX = min(minX, min(t.x1, t.x2)); maxX = max(maxX, max(t.x1, t.x2))
            minY = min(minY, min(t.y1, t.y2)); maxY = max(maxY, max(t.y1, t.y2))
        }

        for (f in flashes) {
            val r = f.apParams[0] / 2f
            val rx = if (f.apType == "R" && f.apParams.size > 1) f.apParams[0] / 2f else r
            val ry = if (f.apType == "R" && f.apParams.size > 1) f.apParams[1] / 2f else r
            minX = min(minX, f.x - rx); maxX = max(maxX, f.x + rx)
            minY = min(minY, f.y - ry); maxY = max(maxY, f.y + ry)
        }

        // Padding 10%
        val px = (maxX - minX) * 0.1f
        val py = (maxY - minY) * 0.1f
        minX -= px; minY -= py; maxX += px; maxY += py

        dataW = maxX - minX
        dataH = maxY - minY
    }

    // ── World → screen ────────────────────────
    private fun wx(x: Float) = x * scale + offX
    private fun wy(y: Float) = height - (y * scale + offY)   // flip Y

    private val tableFillPaint = Paint().apply {
        color = Color.parseColor("#111111")
        style = Paint.Style.FILL
    }
    private val tableBorderPaint = Paint().apply {
        color = Color.GREEN
        style = Paint.Style.STROKE
        strokeWidth = 4f
        isAntiAlias = true
    }

    // ═══════════════════════════════════════
    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), bgPaint)

        if (traces.isEmpty() && flashes.isEmpty()) {
            val msg = "Preview will appear here"
            canvas.drawText(msg,
                (width - textPaint.measureText(msg)) / 2f,
                height / 2f, textPaint)
            return
        }

        val drawW = width.toFloat()
        val drawH = height.toFloat()

        val sx = drawW / tableWidth
        val sy = drawH / tableHeight

        scale = min(sx, sy) * 0.85f * userZoom

        val contentW = tableWidth * scale
        val contentH = tableHeight * scale

        offX = (drawW - contentW) / 2f + panX
        offY = (drawH - contentH) / 2f - panY

        drawGrid(canvas)
        drawBorder(canvas)
        drawTraces(canvas)
        drawFlashes(canvas)
        drawDimLabel(canvas)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {

        // cegah ScrollView intercept saat disentuh
        parent.requestDisallowInterceptTouchEvent(true)

        scaleDetector.onTouchEvent(event)

        when (event.actionMasked) {

            MotionEvent.ACTION_DOWN -> {
                lastTouchX = event.x
                lastTouchY = event.y
                isPanning = true
            }

            MotionEvent.ACTION_MOVE -> {
                if (!scaleDetector.isInProgress && isPanning) {

                    val dx = event.x - lastTouchX
                    val dy = event.y - lastTouchY

                    panX += dx
                    panY += dy

                    lastTouchX = event.x
                    lastTouchY = event.y

                    invalidate()
                }
            }

            MotionEvent.ACTION_UP,
            MotionEvent.ACTION_CANCEL -> {
                isPanning = false

                // izinkan scroll lagi setelah selesai
                parent.requestDisallowInterceptTouchEvent(false)
            }
        }

        return true
    }


//    private fun drawGrid(canvas: Canvas) {
//        val step = 10f   // 1 cm
//        var x = Math.floor((minX / step).toDouble()).toFloat() * step
//        while (x <= maxX) {
//            canvas.drawLine(wx(x), wy(minY), wx(x), wy(maxY), gridPaint); x += step
//        }
//        var y = Math.floor((minY / step).toDouble()).toFloat() * step
//        while (y <= maxY) {
//            canvas.drawLine(wx(minX), wy(y), wx(maxX), wy(y), gridPaint); y += step
//        }
//    }

    private fun drawGrid(canvas: Canvas) {

        val step = 10f   // 1 cm

        var x = 0f
        while (x <= tableWidth) {
            canvas.drawLine(wx(x), wy(0f), wx(x), wy(tableHeight), gridPaint)
            x += step
        }

        var y = 0f
        while (y <= tableHeight) {
            canvas.drawLine(wx(0f), wy(y), wx(tableWidth), wy(y), gridPaint)
            y += step
        }
    }


    private fun drawBorder(canvas: Canvas) {
        canvas.drawRect(
            wx(0f), wy(tableHeight),
            wx(tableWidth), wy(0f),
            axisPaint
        )

        // Label X dan Y saja
        canvas.drawText("X",
            wx(tableWidth) - 30f,
            wy(0f) + 40f,
            axisTextPaint
        )

        canvas.drawText("Y",
            wx(0f) - 40f,
            wy(tableHeight) + 20f,
            axisTextPaint
        )
//        canvas.drawRect(wx(minX), wy(maxY), wx(maxX), wy(minY), axisPaint)

        // X axis labels setiap 2cm
//        var x = Math.ceil((minX / 20f).toDouble()).toFloat() * 20f
//        while (x <= maxX) {
//            val label = "${(x / 10).toInt()}cm"
//            canvas.drawText(label, wx(x) - axisTextPaint.measureText(label) / 2f,
//                wy(minY) + 30f, axisTextPaint)
//            x += 20f
//        }
//        // Y axis labels setiap 2cm
//        var y = Math.ceil((minY / 20f).toDouble()).toFloat() * 20f
//        while (y <= maxY) {
//            canvas.drawText("${(y / 10).toInt()}cm", wx(minX) - 52f,
//                wy(y) + 8f, axisTextPaint)
//            y += 20f
//        }

//        canvas.drawText(
//            "X",
//            wx(maxX) - 20f,
//            wy(minY) + 40f,
//            axisTextPaint
//        )
//
//        // Y di ujung kiri atas
//        canvas.drawText(
//            "Y",
//            wx(minX) - 40f,
//            wy(maxY) + 20f,
//            axisTextPaint
//        )
    }

    private fun drawTraces(canvas: Canvas) {
        for (t in traces) {
            canvas.drawLine(wx(t.x1), wy(t.y1), wx(t.x2), wy(t.y2), tracePaint)
        }
    }

    private fun drawFlashes(canvas: Canvas) {
        for (f in flashes) {
            val cx = wx(f.x)
            val cy = wy(f.y)

            when (f.apType) {
                "C" -> {
                    // Circle — gambar sebagai lingkaran
                    val r = f.apParams[0] / 2f * scale
                    canvas.drawCircle(cx, cy, r, padFillPaint)
                    canvas.drawCircle(cx, cy, r, padStrokePaint)
                }
                "R" -> {
                    // Rectangle
                    val hw = f.apParams[0] / 2f * scale
                    val hh = (if (f.apParams.size > 1) f.apParams[1] else f.apParams[0]) / 2f * scale
                    val rect = RectF(cx - hw, cy - hh, cx + hw, cy + hh)
                    canvas.drawRect(rect, padFillPaint)
                    canvas.drawRect(rect, padStrokePaint)
                }
                "P" -> {
                    // Polygon → bounding box kotak
                    val r = f.apParams[0] / 2f * scale
                    val rect = RectF(cx - r, cy - r, cx + r, cy + r)
                    canvas.drawRect(rect, padFillPaint)
                    canvas.drawRect(rect, padStrokePaint)
                }
            }
        }
    }

    private fun drawDimLabel(canvas: Canvas) {
        val label = "${String.format("%.1f", dataW / 10)}cm × ${String.format("%.1f", dataH / 10)}cm"
        canvas.drawText(label,
            (width - dimTextPaint.measureText(label)) / 2f,
            36f, dimTextPaint)
    }
}
*/
//package com.example.xploreapp
//
//import android.content.Context
//import android.graphics.*
//import android.util.AttributeSet
//import android.view.View
//import kotlin.math.max
//import kotlin.math.min
//
//class PreviewView @JvmOverloads constructor(
//    context: Context,
//    attrs: AttributeSet? = null,
//    defStyleAttr: Int = 0
//) : View(context, attrs, defStyleAttr) {
//
//    private var traces = listOf<TraceSegment>()
//    private var pads = listOf<Pad>()
//
//    private val tracePaint = Paint().apply {
//        color = Color.BLUE
//        strokeWidth = 2f
//        style = Paint.Style.STROKE
//        isAntiAlias = true
//    }
//
//    private val padPaint = Paint().apply {
//        color = Color.RED
//        style = Paint.Style.STROKE
//        strokeWidth = 2f
//        isAntiAlias = true
//    }
//
//    private val holePaint = Paint().apply {
//        color = Color.GREEN
//        style = Paint.Style.STROKE
//        strokeWidth = 2f
//        isAntiAlias = true
//    }
//
//    private val bgPaint = Paint().apply {
//        color = Color.WHITE
//        style = Paint.Style.FILL
//    }
//
//    private val textPaint = Paint().apply {
//        color = Color.GRAY
//        textSize = 40f
//        isAntiAlias = true
//    }
//
//    private val axisPaint = Paint().apply {
//        color = Color.rgb(200, 200, 200)
//        strokeWidth = 2f
//        style = Paint.Style.STROKE
//        isAntiAlias = true
//    }
//
//    private val axisTextPaint = Paint().apply {
//        color = Color.rgb(100, 100, 100)
//        textSize = 24f
//        isAntiAlias = true
//    }
//
//    private val gridPaint = Paint().apply {
//        color = Color.rgb(230, 230, 230)
//        strokeWidth = 1f
//        style = Paint.Style.STROKE
//        isAntiAlias = true
//    }
//
//    private val warningPaint = Paint().apply {
//        color = Color.RED
//        strokeWidth = 3f
//        style = Paint.Style.STROKE
//        isAntiAlias = true
//        pathEffect = DashPathEffect(floatArrayOf(10f, 10f), 0f)
//    }
//
//    private val warningTextPaint = Paint().apply {
//        color = Color.RED
//        textSize = 32f
//        isAntiAlias = true
//        isFakeBoldText = true
//    }
//
//    private var minX = 0f
//    private var minY = 0f
//    private var maxX = 0f
//    private var maxY = 0f
//    private var scale = 1f
//    private var offsetX = 0f
//    private var offsetY = 0f
//    private var dataWidth = 0f
//    private var dataHeight = 0f
//
//    fun updatePreview(newTraces: List<TraceSegment>, newPads: List<Pad>) {
//        traces = newTraces
//        pads = newPads
//        calculateBounds()
//        invalidate()
//    }
//
//    private fun calculateBounds() {
//        if (traces.isEmpty() && pads.isEmpty()) {
//            minX = 0f
//            minY = 0f
//            maxX = 100f
//            maxY = 100f
//            return
//        }
//
//        minX = Float.MAX_VALUE
//        minY = Float.MAX_VALUE
//        maxX = Float.MIN_VALUE
//        maxY = Float.MIN_VALUE
//
//        for (seg in traces) {
//            minX = min(minX, min(seg.x1, seg.x2))
//            minY = min(minY, min(seg.y1, seg.y2))
//            maxX = max(maxX, max(seg.x1, seg.x2))
//            maxY = max(maxY, max(seg.y1, seg.y2))
//        }
//
//        for (pad in pads) {
//            val size = if (pad.isRectangle) {
//                max(pad.outerWidth, pad.outerHeight) / 2
//            } else {
//                pad.outerSize / 2
//            }
//            minX = min(minX, pad.x - size)
//            minY = min(minY, pad.y - size)
//            maxX = max(maxX, pad.x + size)
//            maxY = max(maxY, pad.y + size)
//        }
//
//        // Add padding
//        val padX = (maxX - minX) * 0.1f
//        val padY = (maxY - minY) * 0.1f
//        minX -= padX
//        minY -= padY
//        maxX += padX
//        maxY += padY
//
//        dataWidth = maxX - minX
//        dataHeight = maxY - minY
//    }
//
//    override fun onDraw(canvas: Canvas) {
//        super.onDraw(canvas)
//
//        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), bgPaint)
//
//        if (traces.isEmpty() && pads.isEmpty()) {
//            val text = "Preview will appear here"
//            val textWidth = textPaint.measureText(text)
//            canvas.drawText(
//                text,
//                (width - textWidth) / 2,
//                height / 2f,
//                textPaint
//            )
//            return
//        }
//
//        // Calculate scale to fit data
//        val scaleX = width / dataWidth
//        val scaleY = height / dataHeight
//        scale = min(scaleX, scaleY) * 0.80f // 80% to leave room for labels
//
//        offsetX = (width - dataWidth * scale) / 2 - minX * scale
//        offsetY = (height - dataHeight * scale) / 2 - minY * scale
//
//        // Calculate grid spacing (2cm intervals)
//        val gridSpacingMM = 20f
//        val numGridLinesX = (dataWidth / gridSpacingMM).toInt() + 1
//        val numGridLinesY = (dataHeight / gridSpacingMM).toInt() + 1
//
//        // Draw grid
//        for (i in 0..numGridLinesX) {
//            val xMM = minX + i * gridSpacingMM
//            val xPos = xMM * scale + offsetX
//            if (xPos >= offsetX && xPos <= width - offsetX) {
//                canvas.drawLine(xPos, offsetY, xPos, height - offsetY, gridPaint)
//            }
//        }
//        for (i in 0..numGridLinesY) {
//            val yMM = minY + i * gridSpacingMM
//            val yPos = height - (yMM * scale + offsetY)
//            if (yPos >= offsetY && yPos <= height - offsetY) {
//                canvas.drawLine(offsetX, yPos, width - offsetX, yPos, gridPaint)
//            }
//        }
//
//        // Draw border around data
//        val borderRect = RectF(
//            minX * scale + offsetX,
//            height - (maxY * scale + offsetY),
//            maxX * scale + offsetX,
//            height - (minY * scale + offsetY)
//        )
//        canvas.drawRect(borderRect, axisPaint)
//
//        // Draw axis labels
//        val startCM = (minX / 10f).toInt()
//        val endCM = (maxX / 10f).toInt() + 1
//
//        // X-axis labels (every 2cm)
//        for (cm in startCM..endCM step 2) {
//            val xMM = cm * 10f
//            val xPos = xMM * scale + offsetX
//            if (xPos >= offsetX - 20 && xPos <= width - offsetX + 20) {
//                val label = "${cm}cm"
//                val textWidth = axisTextPaint.measureText(label)
//                canvas.drawText(
//                    label,
//                    xPos - textWidth / 2,
//                    height - offsetY + 30f,
//                    axisTextPaint
//                )
//            }
//        }
//
//        // Y-axis labels (every 2cm)
//        val startYCM = (minY / 10f).toInt()
//        val endYCM = (maxY / 10f).toInt() + 1
//
//        for (cm in startYCM..endYCM step 2) {
//            val yMM = cm * 10f
//            val yPos = height - (yMM * scale + offsetY)
//            if (yPos >= offsetY - 20 && yPos <= height - offsetY + 20) {
//                val label = "${cm}cm"
//                canvas.drawText(
//                    label,
//                    offsetX - 50f,
//                    yPos + 8f,
//                    axisTextPaint
//                )
//            }
//        }
//
//        // Draw traces
//        for (seg in traces) {
//            val x1 = seg.x1 * scale + offsetX
//            val y1 = height - (seg.y1 * scale + offsetY)
//            val x2 = seg.x2 * scale + offsetX
//            val y2 = height - (seg.y2 * scale + offsetY)
//            canvas.drawLine(x1, y1, x2, y2, tracePaint)
//        }
//
//        // Draw pads
//        for (pad in pads) {
//            val cx = pad.x * scale + offsetX
//            val cy = height - (pad.y * scale + offsetY)
//
//            if (pad.isRectangle) {
//                val halfWidth = pad.outerWidth * scale / 2
//                val halfHeight = pad.outerHeight * scale / 2
//                val rect = RectF(
//                    cx - halfWidth,
//                    cy - halfHeight,
//                    cx + halfWidth,
//                    cy + halfHeight
//                )
//                canvas.drawRect(rect, padPaint)
//
//                if (pad.hasHole && pad.innerSize > 0.01f) {
//                    val holeHalfWidth = pad.innerSize * scale / 2
//                    val holeHalfHeight = pad.innerSize * scale / 2
//                    val holeRect = RectF(
//                        cx - holeHalfWidth,
//                        cy - holeHalfHeight,
//                        cx + holeHalfWidth,
//                        cy + holeHalfHeight
//                    )
//                    canvas.drawRect(holeRect, holePaint)
//                }
//            } else {
//                val outerRadius = pad.outerSize * scale / 2
//                val outerHalf = outerRadius / 1.414f // Square approximation
//                val rect = RectF(
//                    cx - outerHalf,
//                    cy - outerHalf,
//                    cx + outerHalf,
//                    cy + outerHalf
//                )
//                canvas.drawRect(rect, padPaint)
//
//                if (pad.hasHole && pad.innerSize > 0.01f) {
//                    val innerRadius = pad.innerSize * scale / 2
//                    val innerHalf = innerRadius / 1.414f
//                    val holeRect = RectF(
//                        cx - innerHalf,
//                        cy - innerHalf,
//                        cx + innerHalf,
//                        cy + innerHalf
//                    )
//                    canvas.drawRect(holeRect, holePaint)
//                }
//            }
//        }
//
//        // Draw dimension info
//        val dimText = "${String.format("%.1f", dataWidth / 10)}cm × ${String.format("%.1f", dataHeight / 10)}cm"
//        val dimWidth = axisTextPaint.measureText(dimText)
//        canvas.drawText(
//            dimText,
//            (width - dimWidth) / 2,
//            35f,
//            axisTextPaint
//        )
//    }
//}