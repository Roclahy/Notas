package com.omgodse.notally.activities

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.FileProvider
import com.omgodse.notally.R
import java.io.File
import java.util.UUID

class DrawingActivity : NotasBaseActivity() {
    private lateinit var canvasView: FingerDrawingView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val d = resources.displayMetrics.density
        fun dp(n: Int) = (n * d).toInt()
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.BLACK)
        }
        val actions = LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(10),dp(12),dp(10),dp(12))
            orientation = LinearLayout.HORIZONTAL
        }
        fun button(textId: Int, onClick: () -> Unit): TextView = TextView(this).apply {
            text = getString(textId)
            textSize = 15f
            setTextColor(0xFFF1C45B.toInt())
            setPadding(dp(10),dp(12),dp(10),dp(12))
            setOnClickListener { onClick() }
        }
        actions.addView(button(R.string.cancel) { finish() })
        actions.addView(button(R.string.undo) { canvasView.undo() })
        actions.addView(button(R.string.clear) { canvasView.clear() })
        actions.addView(button(R.string.save) { saveDrawing() })
        root.addView(actions, LinearLayout.LayoutParams(-1, -2))
        canvasView = FingerDrawingView(this)
        root.addView(canvasView, LinearLayout.LayoutParams(-1,0,1f))
        val colors = LinearLayout(this).apply {
            gravity = Gravity.CENTER
            setPadding(dp(10),dp(4),dp(10),dp(12))
        }
        colors.addView(button(R.string.gold_ink) { canvasView.setGoldInk() })
        colors.addView(button(R.string.white_ink) { canvasView.setWhiteInk() })
        root.addView(colors)
        setContentView(root)
    }

    private fun saveDrawing() {
        if (!canvasView.hasDrawing()) {
            Toast.makeText(this, R.string.empty_drawing, Toast.LENGTH_SHORT).show()
            return
        }
        try {
            val directory = File(cacheDir, "drawings").apply { mkdirs() }
            val file = File(directory, UUID.randomUUID().toString() + ".png")
            val bitmap = canvasView.snapshot()
            file.outputStream().use { stream ->
                if (!bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG,100,stream)) {
                    throw java.io.IOException("PNG encoding failed")
                }
            }
            bitmap.recycle()
            val uri: Uri = FileProvider.getUriForFile(this, "$packageName.provider", file)
            setResult(Activity.RESULT_OK, Intent().setData(uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
            finish()
        } catch (_: Exception) {
            Toast.makeText(this, R.string.drawing_save_error, Toast.LENGTH_LONG).show()
        }
    }
}
