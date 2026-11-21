package com.omgodse.notally.security

import android.app.Activity
import android.content.DialogInterface
import android.text.InputFilter
import android.text.InputType
import android.view.WindowManager
import android.widget.EditText
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.omgodse.notally.R

object PinDialogs {
    private fun pinField(activity: Activity): EditText = EditText(activity).apply {
        inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD
        filters = arrayOf(InputFilter.LengthFilter(4))
        hint = activity.getString(R.string.four_digit_pin)
        isSingleLine = true
        setPadding(32, 20, 32, 20)
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
        ask(activity, R.string.enter_pin, true, onCancel) { input ->
            val good = PinSecurity.verify(activity, key, input)
            if (good) onVerified()
            good
        }
    }

    private fun ask(
        activity: Activity, title: Int, requiresValidation: Boolean,
        onCancel: () -> Unit,
        onEntered: (String) -> Boolean
    ) {
        val input = pinField(activity)
        var finished = false
        val dialog = MaterialAlertDialogBuilder(activity)
            .setTitle(title)
            .setView(input)
            .setPositiveButton(R.string.continue_, null)
            .setNegativeButton(R.string.cancel) { _, _ ->
                if (!finished) { finished = true; onCancel() }
            }
            .create()
        dialog.setOnCancelListener {
            if (!finished) { finished = true; onCancel() }
        }
        dialog.setOnShowListener {
            dialog.getButton(DialogInterface.BUTTON_POSITIVE).setOnClickListener {
                val pin = input.text.toString()
                if (pin.length != 4 || !pin.all(Char::isDigit)) {
                    input.error = activity.getString(R.string.four_digit_pin)
                    return@setOnClickListener
                }
                if (requiresValidation) {
                    val remaining = PinSecurity.remainingLockout(activity, getSecurityKeyForError(activity))
                    // The verifier below is authoritative; this value is not used to bypass lockout.
                }
                if (onEntered(pin)) {
                    finished = true
                    input.text?.clear()
                    dialog.dismiss()
                } else {
                    input.text?.clear()
                    input.error = activity.getString(R.string.invalid_pin)
                }
            }
        }
        dialog.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE)
        dialog.show()
    }

    private fun getSecurityKeyForError(activity: Activity): String = "pin:app"
}
