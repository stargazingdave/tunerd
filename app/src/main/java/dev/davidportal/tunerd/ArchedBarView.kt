// Grayacle Guitar Tuner: ArchedBarView.kt
package dev.davidportal.tunerd

import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.view.View
import kotlin.math.*

class ArchedBarView(context: Context, attrs: AttributeSet) : View(context, attrs) {
    private var targetFreq: Float? = null
    private var detectedFreq: Float? = null

    private val density = resources.displayMetrics.density
    private val scaleText = width.coerceAtLeast(1) / 400f

    private val arcPaint = Paint().apply {
        color = Color.parseColor("#90A4AE") // Softer steel
        strokeWidth = 8f * density
        style = Paint.Style.STROKE
        isAntiAlias = true
        setShadowLayer(10f * density, 0f, 0f, Color.BLACK)
    }

    private val tickPaint = Paint().apply {
        color = Color.parseColor("#CFD8DC")
        strokeWidth = 2f * density
        style = Paint.Style.STROKE
        isAntiAlias = true
    }

    private val centerLinePaint = Paint().apply {
        color = Color.WHITE
        strokeWidth = 3f * density
        isAntiAlias = true
        setShadowLayer(6f * density, 0f, 0f, Color.DKGRAY)
    }

    private val needlePaint = Paint().apply {
        shader = LinearGradient(0f, 0f, 0f, 100f * density, Color.RED, Color.YELLOW, Shader.TileMode.CLAMP)
        style = Paint.Style.FILL
        isAntiAlias = true
    }

    private val pivotPaint = Paint().apply {
        color = Color.LTGRAY
        style = Paint.Style.FILL
        isAntiAlias = true
        setShadowLayer(5f * density, 0f, 0f, Color.BLACK)
    }

    private val textPaint = Paint().apply {
        color = Color.LTGRAY
        textSize = 16f * density
        textAlign = Paint.Align.CENTER
        isAntiAlias = true
    }

    init {
        setLayerType(LAYER_TYPE_SOFTWARE, null)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        canvas.drawColor(Color.parseColor("#212121")) // Matte charcoal gray

        val padding = width * 0.1f
        val arcHeight = height * 0.6f
        val arcWidth = width - padding * 2
        val radius = arcWidth / 2f

        val centerX = width / 2f
        val centerY = padding + arcHeight

        val rect = RectF(
            centerX - radius,
            centerY - radius,
            centerX + radius,
            centerY + radius
        )

        canvas.drawArc(rect, 180f, 180f, false, arcPaint)

        for (i in -4..4) {
            val angle = 270f + i * 22.5f
            val outerX = centerX + radius * cos(Math.toRadians(angle.toDouble())).toFloat()
            val outerY = centerY + radius * sin(Math.toRadians(angle.toDouble())).toFloat()
            val innerX = centerX + (radius - 20f * density) * cos(Math.toRadians(angle.toDouble())).toFloat()
            val innerY = centerY + (radius - 20f * density) * sin(Math.toRadians(angle.toDouble())).toFloat()
            canvas.drawLine(innerX, innerY, outerX, outerY, tickPaint)
        }

        val centerAngle = Math.toRadians(270.0)
        val startX = centerX + (radius - 30f * density) * cos(centerAngle).toFloat()
        val startY = centerY + (radius - 30f * density) * sin(centerAngle).toFloat()
        val endX = centerX + radius * cos(centerAngle).toFloat()
        val endY = centerY + radius * sin(centerAngle).toFloat()
        canvas.drawLine(startX, startY, endX, endY, centerLinePaint)

        canvas.drawText("-20", centerX - radius + 30f * density, centerY - 10f * density, textPaint)
        canvas.drawText("0", centerX, centerY - radius + 40f * density, textPaint)
        canvas.drawText("+20", centerX + radius - 30f * density, centerY - 10f * density, textPaint)

        if (targetFreq != null && detectedFreq != null) {
            val range = 20f
            val offset = (detectedFreq!! - targetFreq!!).coerceIn(-range, range)
            val angleOffset = (offset / range) * 90f
            val angle = Math.toRadians((270f + angleOffset).toDouble())

            val needleLength = radius - 40f * density
            val baseRadius = 12f * density
            val tipX = centerX + needleLength * cos(angle).toFloat()
            val tipY = centerY + needleLength * sin(angle).toFloat()
            val leftX = centerX + baseRadius * cos(angle + Math.PI / 2).toFloat()
            val leftY = centerY + baseRadius * sin(angle + Math.PI / 2).toFloat()
            val rightX = centerX + baseRadius * cos(angle - Math.PI / 2).toFloat()
            val rightY = centerY + baseRadius * sin(angle - Math.PI / 2).toFloat()

            val path = Path().apply {
                moveTo(tipX, tipY)
                lineTo(leftX, leftY)
                lineTo(rightX, rightY)
                close()
            }

            canvas.drawPath(path, needlePaint)
            canvas.drawCircle(centerX, centerY, baseRadius, pivotPaint)
        }
    }

    fun updatePitch(detected: Float?, target: Float?) {
        this.detectedFreq = detected
        this.targetFreq = target
        invalidate()
    }
}