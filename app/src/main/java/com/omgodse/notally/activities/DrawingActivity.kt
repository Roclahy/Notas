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
import android.widget.SeekBar
import android.graphics.drawable.GradientDrawable
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import android.widget.ImageView
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
            setBackgroundColor(com.google.android.material.color.MaterialColors.getColor(this@DrawingActivity, android.R.attr.colorBackground, Color.BLACK))
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
        colors.addView(button(R.string.choose_color) { showColorPicker() })
        root.addView(colors)
        setContentView(root)
    }

    private fun showColorPicker() {
        val d = resources.displayMetrics.density
        fun dp(v: Int) = (v * d).toInt()
        val values = floatArrayOf(44f, 0.75f, 0.94f)
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(8), dp(24), dp(8))
        }
        val indicator = TextView(this).apply {
            textSize = 17f
            gravity = Gravity.CENTER
            setTextColor(Color.BLACK)
            setPadding(dp(10), dp(16), dp(10), dp(16))
        }
        fun updatePreview() {
            val value = Color.HSVToColor(values)
            indicator.text = String.format("#%06X", 0xFFFFFF and value)
            indicator.background = GradientDrawable().apply {
                setColor(value)
                cornerRadius = dp(16).toFloat()
            }
        }
        updatePreview()
        layout.addView(indicator, LinearLayout.LayoutParams(-1, dp(58)))
        val labels = arrayOf(R.string.hue, R.string.saturation, R.string.brightness)
        for (index in 0..2) {
            val label = TextView(this).apply {
                setText(labels[index])
                textSize = 13f
                setPadding(0, dp(15), 0, dp(5))
            }
            layout.addView(label)
            val bar = SeekBar(this).apply {
                max = if (index == 0) 360 else 100
                progress = when (index) {
                    0 -> values[0].toInt()
                    else -> (values[index] * 100).toInt()
                }
                progressTintList = android.content.res.ColorStateList.valueOf(0xFFF1C45B.toInt())
                thumbTintList = android.content.res.ColorStateList.valueOf(0xFFF1C45B.toInt())
                setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                    override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) {
                        values[index] = if (index == 0) progress.toFloat() else progress / 100f
                        updatePreview()
                    }
                    override fun onStartTrackingTouch(seekBar: SeekBar) {}
                    override fun onStopTrackingTouch(seekBar: SeekBar) {}
                })
            }
            layout.addView(bar)
        }
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.choose_color)
            .setView(layout)
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.save) { _, _ ->
                canvasView.setInkColor(Color.HSVToColor(values))
            }
            .show()
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
