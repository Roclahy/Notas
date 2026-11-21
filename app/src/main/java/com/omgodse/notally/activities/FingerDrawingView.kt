package com.omgodse.notally.activities

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.view.MotionEvent
import android.view.View
import kotlin.math.max

/**
 * Lienzo ligero. La tinta se guarda en PNG transparente, sin bitmap gigante persistente.
 */
class FingerDrawingView(context: Context) : View(context) {
    private data class Stroke(val path: Path, val color: Int)
    private val strokes = ArrayList<Stroke>()
    private var current: Path? = null
    private var ink = Color.rgb(241,196,91)
    private val strokeWidth = 5.5f * resources.displayMetrics.density
    private val pen = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
        strokeWidth = this@FingerDrawingView.strokeWidth
    }

    fun setGoldInk() { ink = Color.rgb(241,196,91) }
    fun setWhiteInk() { ink = Color.WHITE }
    fun undo() {
        if (strokes.isNotEmpty()) {
            strokes.removeAt(strokes.lastIndex)
            invalidate()
        }
    }
    fun clear() { strokes.clear(); current = null; invalidate() }
    fun hasDrawing(): Boolean = strokes.isNotEmpty()

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        canvas.drawColor(Color.BLACK)
        drawStrokes(canvas)
    }

    private fun drawStrokes(canvas: Canvas) {
        for (entry in strokes) {
            pen.color = entry.color
            canvas.drawPath(entry.path, pen)
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                parent.requestDisallowInterceptTouchEvent(true)
                current = Path().apply { moveTo(event.x, event.y) }
                strokes.add(Stroke(current!!, ink))
                invalidate()
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                val p = current ?: return false
                for (i in 0 until event.historySize) {
                    p.lineTo(event.getHistoricalX(i), event.getHistoricalY(i))
                }
                p.lineTo(event.x, event.y)
                invalidate()
                return true
            }
            MotionEvent.ACTION_UP -> {
                current?.lineTo(event.x, event.y)
                current = null
                invalidate()
                return true
            }
            MotionEvent.ACTION_CANCEL -> {
                current = null
                return true
            }
        }
        return true
    }

    fun snapshot(): Bitmap {
        val width = max(1, width)
        val height = max(1, height)
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val output = Canvas(bitmap)
        drawStrokes(output)
        return bitmap
    }
}
