package com.example.xploreapp

import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.view.View
import kotlin.math.max
import kotlin.math.min

class PreviewView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private var traces = listOf<TraceSegment>()
    private var pads = listOf<Pad>()

    private val tracePaint = Paint().apply {
        color = Color.BLUE
        strokeWidth = 2f
        style = Paint.Style.STROKE
        isAntiAlias = true
    }

    private val padPaint = Paint().apply {
        color = Color.RED
        style = Paint.Style.STROKE
        strokeWidth = 2f
        isAntiAlias = true
    }

    private val holePaint = Paint().apply {
        color = Color.GREEN
        style = Paint.Style.STROKE
        strokeWidth = 2f
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

    private val axisPaint = Paint().apply {
        color = Color.rgb(200, 200, 200)
        strokeWidth = 2f
        style = Paint.Style.STROKE
        isAntiAlias = true
    }

    private val axisTextPaint = Paint().apply {
        color = Color.rgb(100, 100, 100)
        textSize = 24f
        isAntiAlias = true
    }

    private val gridPaint = Paint().apply {
        color = Color.rgb(230, 230, 230)
        strokeWidth = 1f
        style = Paint.Style.STROKE
        isAntiAlias = true
    }

    private val warningPaint = Paint().apply {
        color = Color.RED
        strokeWidth = 3f
        style = Paint.Style.STROKE
        isAntiAlias = true
        pathEffect = DashPathEffect(floatArrayOf(10f, 10f), 0f)
    }

    private val warningTextPaint = Paint().apply {
        color = Color.RED
        textSize = 32f
        isAntiAlias = true
        isFakeBoldText = true
    }

    private var minX = 0f
    private var minY = 0f
    private var maxX = 0f
    private var maxY = 0f
    private var scale = 1f
    private var offsetX = 0f
    private var offsetY = 0f
    private var dataWidth = 0f
    private var dataHeight = 0f

    fun updatePreview(newTraces: List<TraceSegment>, newPads: List<Pad>) {
        traces = newTraces
        pads = newPads
        calculateBounds()
        invalidate()
    }

    private fun calculateBounds() {
        if (traces.isEmpty() && pads.isEmpty()) {
            minX = 0f
            minY = 0f
            maxX = 100f
            maxY = 100f
            return
        }

        minX = Float.MAX_VALUE
        minY = Float.MAX_VALUE
        maxX = Float.MIN_VALUE
        maxY = Float.MIN_VALUE

        for (seg in traces) {
            minX = min(minX, min(seg.x1, seg.x2))
            minY = min(minY, min(seg.y1, seg.y2))
            maxX = max(maxX, max(seg.x1, seg.x2))
            maxY = max(maxY, max(seg.y1, seg.y2))
        }

        for (pad in pads) {
            val size = if (pad.isRectangle) {
                max(pad.outerWidth, pad.outerHeight) / 2
            } else {
                pad.outerSize / 2
            }
            minX = min(minX, pad.x - size)
            minY = min(minY, pad.y - size)
            maxX = max(maxX, pad.x + size)
            maxY = max(maxY, pad.y + size)
        }

        // Add padding
        val padX = (maxX - minX) * 0.1f
        val padY = (maxY - minY) * 0.1f
        minX -= padX
        minY -= padY
        maxX += padX
        maxY += padY

        dataWidth = maxX - minX
        dataHeight = maxY - minY
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), bgPaint)

        if (traces.isEmpty() && pads.isEmpty()) {
            val text = "Preview will appear here"
            val textWidth = textPaint.measureText(text)
            canvas.drawText(
                text,
                (width - textWidth) / 2,
                height / 2f,
                textPaint
            )
            return
        }

        // Calculate scale to fit data
        val scaleX = width / dataWidth
        val scaleY = height / dataHeight
        scale = min(scaleX, scaleY) * 0.80f // 80% to leave room for labels

        offsetX = (width - dataWidth * scale) / 2 - minX * scale
        offsetY = (height - dataHeight * scale) / 2 - minY * scale

        // Calculate grid spacing (2cm intervals)
        val gridSpacingMM = 20f
        val numGridLinesX = (dataWidth / gridSpacingMM).toInt() + 1
        val numGridLinesY = (dataHeight / gridSpacingMM).toInt() + 1

        // Draw grid
        for (i in 0..numGridLinesX) {
            val xMM = minX + i * gridSpacingMM
            val xPos = xMM * scale + offsetX
            if (xPos >= offsetX && xPos <= width - offsetX) {
                canvas.drawLine(xPos, offsetY, xPos, height - offsetY, gridPaint)
            }
        }
        for (i in 0..numGridLinesY) {
            val yMM = minY + i * gridSpacingMM
            val yPos = height - (yMM * scale + offsetY)
            if (yPos >= offsetY && yPos <= height - offsetY) {
                canvas.drawLine(offsetX, yPos, width - offsetX, yPos, gridPaint)
            }
        }

        // Draw border around data
        val borderRect = RectF(
            minX * scale + offsetX,
            height - (maxY * scale + offsetY),
            maxX * scale + offsetX,
            height - (minY * scale + offsetY)
        )
        canvas.drawRect(borderRect, axisPaint)

        // Draw axis labels
        val startCM = (minX / 10f).toInt()
        val endCM = (maxX / 10f).toInt() + 1

        // X-axis labels (every 2cm)
        for (cm in startCM..endCM step 2) {
            val xMM = cm * 10f
            val xPos = xMM * scale + offsetX
            if (xPos >= offsetX - 20 && xPos <= width - offsetX + 20) {
                val label = "${cm}cm"
                val textWidth = axisTextPaint.measureText(label)
                canvas.drawText(
                    label,
                    xPos - textWidth / 2,
                    height - offsetY + 30f,
                    axisTextPaint
                )
            }
        }

        // Y-axis labels (every 2cm)
        val startYCM = (minY / 10f).toInt()
        val endYCM = (maxY / 10f).toInt() + 1

        for (cm in startYCM..endYCM step 2) {
            val yMM = cm * 10f
            val yPos = height - (yMM * scale + offsetY)
            if (yPos >= offsetY - 20 && yPos <= height - offsetY + 20) {
                val label = "${cm}cm"
                canvas.drawText(
                    label,
                    offsetX - 50f,
                    yPos + 8f,
                    axisTextPaint
                )
            }
        }

        // Draw traces
        for (seg in traces) {
            val x1 = seg.x1 * scale + offsetX
            val y1 = height - (seg.y1 * scale + offsetY)
            val x2 = seg.x2 * scale + offsetX
            val y2 = height - (seg.y2 * scale + offsetY)
            canvas.drawLine(x1, y1, x2, y2, tracePaint)
        }

        // Draw pads
        for (pad in pads) {
            val cx = pad.x * scale + offsetX
            val cy = height - (pad.y * scale + offsetY)

            if (pad.isRectangle) {
                val halfWidth = pad.outerWidth * scale / 2
                val halfHeight = pad.outerHeight * scale / 2
                val rect = RectF(
                    cx - halfWidth,
                    cy - halfHeight,
                    cx + halfWidth,
                    cy + halfHeight
                )
                canvas.drawRect(rect, padPaint)

                if (pad.hasHole && pad.innerSize > 0.01f) {
                    val holeHalfWidth = pad.innerSize * scale / 2
                    val holeHalfHeight = pad.innerSize * scale / 2
                    val holeRect = RectF(
                        cx - holeHalfWidth,
                        cy - holeHalfHeight,
                        cx + holeHalfWidth,
                        cy + holeHalfHeight
                    )
                    canvas.drawRect(holeRect, holePaint)
                }
            } else {
                val outerRadius = pad.outerSize * scale / 2
                val outerHalf = outerRadius / 1.414f // Square approximation
                val rect = RectF(
                    cx - outerHalf,
                    cy - outerHalf,
                    cx + outerHalf,
                    cy + outerHalf
                )
                canvas.drawRect(rect, padPaint)

                if (pad.hasHole && pad.innerSize > 0.01f) {
                    val innerRadius = pad.innerSize * scale / 2
                    val innerHalf = innerRadius / 1.414f
                    val holeRect = RectF(
                        cx - innerHalf,
                        cy - innerHalf,
                        cx + innerHalf,
                        cy + innerHalf
                    )
                    canvas.drawRect(holeRect, holePaint)
                }
            }
        }

        // Draw dimension info
        val dimText = "${String.format("%.1f", dataWidth / 10)}cm × ${String.format("%.1f", dataHeight / 10)}cm"
        val dimWidth = axisTextPaint.measureText(dimText)
        canvas.drawText(
            dimText,
            (width - dimWidth) / 2,
            35f,
            axisTextPaint
        )
    }
}