package com.github.libretube.ui.dialogs

import android.content.Context
import android.text.InputType
import android.widget.LinearLayout
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import com.github.libretube.R
import com.github.libretube.extensions.dpToPx
import com.github.libretube.util.AccessControl
import com.github.libretube.util.PinPromptAdapter
import com.github.libretube.util.PinVerificationResult
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import kotlin.math.ceil

object ParentPinDialogs {
    private const val MILLIS_PER_SECOND = 1000.0

    fun promptAdapter(context: Context): PinPromptAdapter =
        PinPromptAdapter { onVerified, onCancelled ->
            showVerifyPinDialog(
                context = context,
                onVerified = onVerified,
                onCancelled = onCancelled,
            )
        }

    fun showSetupPinDialog(
        context: Context,
        onPinSet: () -> Unit = {},
        onCancelled: () -> Unit = {},
    ): AlertDialog {
        val container = createContainer(context)
        val (pinLayout, pinInput) = createPinField(context, R.string.parent_pin_hint)
        val (confirmLayout, confirmInput) =
            createPinField(context, R.string.confirm_parent_pin_hint)
        container.addView(pinLayout)
        container.addView(confirmLayout)

        var pinSaved = false
        val dialog =
            MaterialAlertDialogBuilder(context)
                .setTitle(R.string.set_parent_pin_title)
                .setMessage(R.string.set_parent_pin_message)
                .setView(container)
                .setPositiveButton(R.string.okay, null)
                .setNegativeButton(R.string.cancel) { d, _ ->
                    d.cancel()
                }
                .setOnCancelListener {
                    if (!pinSaved) onCancelled()
                }
                .create()

        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                pinLayout.error = null
                confirmLayout.error = null
                val pin = pinInput.text?.toString()?.trim().orEmpty()
                val confirm = confirmInput.text?.toString()?.trim().orEmpty()
                if (pin.isEmpty()) {
                    pinLayout.error = context.getString(R.string.parent_pin_empty)
                    return@setOnClickListener
                }
                if (pin != confirm) {
                    confirmLayout.error = context.getString(R.string.parent_pin_mismatch)
                    return@setOnClickListener
                }
                if (AccessControl.instance.setPin(pin)) {
                    pinSaved = true
                    dialog.dismiss()
                    onPinSet()
                }
            }
        }
        dialog.show()
        return dialog
    }

    private fun showVerifyPinDialog(
        context: Context,
        onVerified: () -> Unit,
        onCancelled: () -> Unit = {},
    ): AlertDialog {
        val container = createContainer(context)
        val (pinLayout, pinInput) = createPinField(context, R.string.parent_pin_hint)
        container.addView(pinLayout)

        var verified = false
        val dialog =
            MaterialAlertDialogBuilder(context)
                .setTitle(R.string.enter_parent_pin_title)
                .setView(container)
                .setPositiveButton(R.string.okay, null)
                .setNegativeButton(R.string.cancel) { d, _ ->
                    d.cancel()
                }
                .setOnCancelListener {
                    if (!verified) onCancelled()
                }
                .create()

        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                pinLayout.error = null
                val pin = pinInput.text?.toString()?.trim().orEmpty()
                when (val result = AccessControl.instance.verifyPin(pin)) {
                    PinVerificationResult.Verified -> {
                        verified = true
                        dialog.dismiss()
                        onVerified()
                    }
                    is PinVerificationResult.Incorrect,
                    is PinVerificationResult.LockedOut -> {
                        pinLayout.error = formatVerificationError(context, result)
                    }
                }
            }
        }
        dialog.show()
        return dialog
    }

    fun showChangePinDialog(context: Context): AlertDialog {
        val container = createContainer(context)
        val (currentLayout, currentInput) =
            createPinField(context, R.string.current_parent_pin_hint)
        val (newLayout, newInput) = createPinField(context, R.string.new_parent_pin_hint)
        val (confirmLayout, confirmInput) =
            createPinField(context, R.string.confirm_parent_pin_hint)
        container.addView(currentLayout)
        container.addView(newLayout)
        container.addView(confirmLayout)

        val dialog =
            MaterialAlertDialogBuilder(context)
                .setTitle(R.string.change_parent_pin_title)
                .setView(container)
                .setPositiveButton(R.string.okay, null)
                .setNegativeButton(R.string.cancel, null)
                .create()

        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                currentLayout.error = null
                newLayout.error = null
                confirmLayout.error = null
                val currentPin = currentInput.text?.toString()?.trim().orEmpty()
                val newPin = newInput.text?.toString()?.trim().orEmpty()
                val confirmPin = confirmInput.text?.toString()?.trim().orEmpty()

                if (newPin.isEmpty()) {
                    newLayout.error = context.getString(R.string.parent_pin_empty)
                    return@setOnClickListener
                }
                if (newPin != confirmPin) {
                    confirmLayout.error = context.getString(R.string.parent_pin_mismatch)
                    return@setOnClickListener
                }
                when (val result = AccessControl.instance.verifyPin(currentPin)) {
                    PinVerificationResult.Verified -> {
                        if (AccessControl.instance.setPin(newPin)) {
                            Toast.makeText(context, R.string.parent_pin_updated, Toast.LENGTH_SHORT)
                                .show()
                            dialog.dismiss()
                        }
                    }
                    is PinVerificationResult.Incorrect,
                    is PinVerificationResult.LockedOut -> {
                        currentLayout.error = formatVerificationError(context, result)
                    }
                }
            }
        }
        dialog.show()
        return dialog
    }

    private fun formatVerificationError(
        context: Context,
        result: PinVerificationResult,
    ): String? =
        when (result) {
            PinVerificationResult.Verified -> null
            is PinVerificationResult.Incorrect -> context.getString(R.string.parent_pin_incorrect)
            is PinVerificationResult.LockedOut -> {
                val seconds =
                    ceil(result.remainingMillis / MILLIS_PER_SECOND).toLong().coerceAtLeast(1L)
                context.resources.getQuantityString(
                    R.plurals.parent_pin_locked_out,
                    seconds.coerceAtMost(Int.MAX_VALUE.toLong()).toInt(),
                    seconds,
                )
            }
        }

    private fun createContainer(context: Context): LinearLayout {
        val padding = 20f.dpToPx()
        return LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(padding, padding / 2, padding, 0)
        }
    }

    private fun createPinField(
        context: Context,
        hintRes: Int,
    ): Pair<TextInputLayout, TextInputEditText> {
        val layout =
            TextInputLayout(context).apply {
                hint = context.getString(hintRes)
                endIconMode = TextInputLayout.END_ICON_PASSWORD_TOGGLE
            }
        val editText =
            TextInputEditText(layout.context).apply {
                inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD
            }
        layout.addView(editText)
        return layout to editText
    }
}
