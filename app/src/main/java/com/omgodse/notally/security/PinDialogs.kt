package com.omgodse.notally.security

import android.app.Activity
import android.content.DialogInterface
import android.text.InputFilter
import android.text.TextWatcher
import android.text.Editable
import android.view.View
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import android.text.InputType
import android.view.WindowManager
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.view.Gravity
import android.graphics.drawable.GradientDrawable
import android.graphics.Color
import android.content.res.ColorStateList
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.omgodse.notally.R

object PinDialogs {
    private fun pinField(activity: Activity): Pair<LinearLayout, EditText> {
        val density = activity.resources.displayMetrics.density
        fun dp(i: Int) = (i * density).toInt()
        val panel = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(20),dp(10),dp(20),dp(14))
        }
        val lock = ImageView(activity).apply {
            setImageResource(R.drawable.notas_lock)
            imageTintList = ColorStateList.valueOf(0xFFF1C45B.toInt())
            setPadding(dp(10),dp(10),dp(10),dp(10))
        }
        panel.addView(lock, LinearLayout.LayoutParams(dp(58),dp(58)))
        val subtitle = android.widget.TextView(activity).apply {
            setText(R.string.four_digit_pin)
            textSize = 13f
            gravity = Gravity.CENTER
            setTextColor(com.google.android.material.color.MaterialColors.getColor(activity, com.google.android.material.R.attr.colorOnSurface, 0xFFEEEEEE.toInt()))
            setPadding(0,dp(8),0,dp(15))
        }
        panel.addView(subtitle)
        val input = EditText(activity).apply {
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD
            filters = arrayOf(InputFilter.LengthFilter(4))
            isSingleLine = true
            gravity = Gravity.CENTER
            textSize = 27f
            letterSpacing = 0.5f
            minHeight = dp(65)
            setPadding(dp(14),dp(10),dp(14),dp(10))
            background = GradientDrawable().apply {
                setColor(com.google.android.material.color.MaterialColors.getColor(activity, com.google.android.material.R.attr.colorSurface, Color.WHITE))
                cornerRadius = dp(17).toFloat()
                setStroke(dp(1), 0xFFF1C45B.toInt())
            }
        }
        panel.addView(input, LinearLayout.LayoutParams(-1,dp(65)))
        return panel to input
    }

    fun setup(activity: Activity, onCreated: (String) -> Unit) {
        ask(activity, R.string.create_pin, false, onCancel = {}) { first ->
            ask(activity, R.string.confirm_pin, false, onCancel = {}) { second ->
                if (first == second) onCreated(first)
                else {
                    android.widget.Toast.makeText(activity, R.string.pin_mismatch, android.widget.Toast.LENGTH_SHORT).show()
                    setup(activity, onCreated)
                }
                true
            }
            true
        }
    }

    fun check(activity: Activity, key: String, onVerified: () -> Unit, onCancel: () -> Unit = {}) {
        ask(activity, R.string.enter_pin, true, onCancel,
            validator = { input -> PinSecurity.verify(activity, key, input) }) {
            onVerified()
            true
        }
    }

    private fun ask(
        activity: Activity, title: Int, requiresValidation: Boolean,
        onCancel: () -> Unit,
        validator: ((String) -> Boolean)? = null,
        onEntered: (String) -> Boolean
    ) {
        val (panel, input) = pinField(activity)
        var finished = false
        val dialog = MaterialAlertDialogBuilder(activity)
            .setTitle(title)
            .setView(panel)
            .setPositiveButton(R.string.continue_, null)
            .setNegativeButton(R.string.cancel) { _, _ ->
                if (!finished) { finished = true; onCancel() }
            }
            .create()
        dialog.setOnCancelListener {
            if (!finished) { finished = true; onCancel() }
        }
        var verifying = false
        fun submitted(pin: String) {
            if (finished || verifying) return
            if (pin.length != 4 || !pin.all(Char::isDigit)) {
                input.error = activity.getString(R.string.four_digit_pin)
                return
            }
            if (validator == null) {
                // Solo la configuración inicial del PIN necesita continuar.
                if (onEntered(pin)) {
                    finished = true
                    input.text?.clear()
                    dialog.dismiss()
                } else {
                    input.text?.clear()
                    input.error = activity.getString(R.string.invalid_pin)
                }
                return
            }
            verifying = true
            input.isEnabled = false
            activity.lifecycleScope.launch {
                val valid = try {
                    withContext(Dispatchers.Default) { validator(pin) }
                } catch (_: Exception) { false }
                if (finished || !dialog.isShowing) return@launch
                verifying = false
                input.isEnabled = true
                if (valid) {
                    finished = true
                    input.text?.clear()
                    dialog.dismiss()
                    // El desbloqueo sucede automáticamente en el cuarto dígito.
                    onEntered(pin)
                } else {
                    input.text?.clear()
                    input.error = activity.getString(R.string.invalid_pin)
                    input.requestFocus()
                }
            }
        }
        dialog.setOnShowListener {
            val positive = dialog.getButton(DialogInterface.BUTTON_POSITIVE)
            if (requiresValidation && validator != null) {
                // Se oculta Aceptar: el PIN válido se reconoce al cuarto dígito.
                positive.visibility = View.GONE
                input.addTextChangedListener(object : TextWatcher {
                    override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                    override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
                    override fun afterTextChanged(s: Editable?) {
                        if (s?.length == 4) submitted(s.toString())
                    }
                })
            } else {
                positive.setOnClickListener { submitted(input.text.toString()) }
            }
        }
        dialog.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE)
        dialog.show()
        dialog.getButton(DialogInterface.BUTTON_POSITIVE)?.setTextColor(0xFFF1C45B.toInt())
        input.requestFocus()
        dialog.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE)
    }

}
