/*
 * Copyright (C) 2024-2025 The LineageOS Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.systemui.biometrics

import android.animation.ValueAnimator
import android.annotation.UiThread
import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.hardware.biometrics.BiometricRequestConstants.REASON_AUTH_KEYGUARD
import android.hardware.biometrics.BiometricRequestConstants.RequestReason
import android.hardware.display.DisplayManager
import android.util.Log
import android.view.Display
import android.view.View
import android.view.WindowManager
import androidx.core.view.isVisible
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle
import com.android.systemui.brightness.domain.interactor.BrightnessMirrorShowingInteractor
import com.android.systemui.lifecycle.repeatWhenAttached
import com.android.systemui.shade.domain.interactor.ShadeInteractor
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

private const val TAG = "UdfpsHelper"

private const val DOZE_DIM_ALPHA = 0.98f

/**
 * Facilitates implementations that use GHBM where dim layer
 * and pressed icon aren't controlled by kernel
 */
@UiThread
class UdfpsHelper(
    private val context: Context,
    private val windowManager: WindowManager,
    @Suppress("UNUSED_PARAMETER") private val shadeInteractor: ShadeInteractor,
    @RequestReason val requestReason: Int,
    private val brightnessMirrorShowingInteractor: BrightnessMirrorShowingInteractor,
    private var view: View = View(context).apply {
        setBackgroundColor(Color.BLACK)
        visibility = View.GONE
    }
) {
    private val displayManager = context.getSystemService(DisplayManager::class.java)!!
    private val isKeyguard = requestReason == REASON_AUTH_KEYGUARD

    private val currentBrightness: Float get() =
        displayManager.getBrightness(Display.DEFAULT_DISPLAY)
    private val minBrightness: Float = context.resources
        .getFloat(com.android.internal.R.dimen.config_screenBrightnessSettingMinimumFloat)
    private val maxBrightness: Float = context.resources
        .getFloat(com.android.internal.R.dimen.config_screenBrightnessSettingMaximumFloat)
    private val brightnessAlphaMap: Map<Int, Int> = context.resources
        .getStringArray(com.android.systemui.res.R.array.config_udfpsDimmingBrightnessAlphaArray)
        .associate {
            val (brightness, alpha) = it.split(",").map { value -> value.trim().toInt() }
            brightness to alpha
        }
    val maxPanelBrightness: Int = brightnessAlphaMap.keys.max()

    private val dimLayoutParams = WindowManager.LayoutParams(
        WindowManager.LayoutParams.TYPE_NAVIGATION_BAR_PANEL,
        0 /* flags are set in computeLayoutParams() */,
        PixelFormat.TRANSPARENT
    ).apply {
        title = "Dim Layer for UDFPS"
        fitInsetsTypes = 0
        gravity = android.view.Gravity.TOP or android.view.Gravity.LEFT
        layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
        flags = Utils.FINGERPRINT_OVERLAY_LAYOUT_PARAM_FLAGS or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
        privateFlags = WindowManager.LayoutParams.PRIVATE_FLAG_TRUSTED_OVERLAY or
                WindowManager.LayoutParams.PRIVATE_FLAG_IS_ROUNDED_CORNERS_OVERLAY
        // Avoid announcing window title
        accessibilityTitle = " "
        inputFeatures = WindowManager.LayoutParams.INPUT_FEATURE_SPY
    }

    private val alphaAnimator = ValueAnimator().apply {
        duration = 800L
        addUpdateListener { animator ->
            val animAlpha = animator.animatedValue as Float
            view.alpha = animAlpha
            dimLayoutParams.alpha = animAlpha
            if (!view.isAttachedToWindow) {
                return@addUpdateListener
            }
            try {
                windowManager.updateViewLayout(view, dimLayoutParams)
            } catch (e: IllegalArgumentException) {
                Log.e(TAG, "View not attached to WindowManager", e)
            }
        }
    }

    private val displayListener = object : DisplayManager.DisplayListener {
        override fun onDisplayAdded(displayId: Int) {}

        override fun onDisplayChanged(displayId: Int) {
            if (displayId != Display.DEFAULT_DISPLAY || !view.isAttachedToWindow) {
                return
            }
            if (isKeyguard && !shouldApplyKeyguardDim()) {
                return
            }
            brightnessToAlpha()
            updateDimLayout()
        }

        override fun onDisplayRemoved(displayId: Int) {}
    }

    private fun interpolate(
        value: Float,
        fromMin: Int,
        fromMax: Int,
        toMin: Int,
        toMax: Int
    ): Float {
        return toMin + (value - fromMin) * (toMax - toMin) / (fromMax - fromMin)
    }

    private fun interpolateAlpha(brightness: Int): Float {
        val lowerEntry = brightnessAlphaMap.entries
            .lastOrNull { it.key <= brightness } ?: return 0f
        val upperEntry = brightnessAlphaMap.entries
            .firstOrNull { it.key >= brightness } ?: return 0f
        val (lowerBrightness, lowerAlpha) = lowerEntry
        val (upperBrightness, upperAlpha) = upperEntry

        return interpolate(
            brightness.toFloat(),
            lowerBrightness,
            upperBrightness,
            lowerAlpha,
            upperAlpha
        ).div(255.0f)
    }

    private fun isDozeActive(): Boolean {
        val display = displayManager.getDisplay(Display.DEFAULT_DISPLAY) ?: return false
        return display.state == Display.STATE_DOZE || display.state == Display.STATE_DOZE_SUSPEND
    }

    // The current function does not account for Doze state where the brightness can go lower
    // than what is set on config_screenBrightnessSettingMinimumFloat.
    // While it's possible to operate with floats, the dimming array was made by referencing
    // brightness_alpha_lut array from the kernel. This provides a comparable array.
    private fun brightnessToAlpha() {
        val targetAlpha: Float
        if (isDozeActive()) {
            // While dozing the panel luminance comes from the AOD (LP1) mode,
            // not from the backlight value, so the LUT below is not calibrated
            // for it. The sensor still needs the full panel HBM, so dim the
            // rest of the screen near-black to keep the AOD content from
            // visibly brightening during the fingerprint press.
            targetAlpha = DOZE_DIM_ALPHA
        } else {
            val adjustedBrightness =
                (currentBrightness.coerceIn(minBrightness, maxBrightness) * maxPanelBrightness)
                    .toInt()

            targetAlpha = brightnessAlphaMap[adjustedBrightness]?.div(255.0f)
                ?: interpolateAlpha(adjustedBrightness)
        }

        Log.i(TAG, "Dim Layer Alpha: $targetAlpha")

        alphaAnimator.setFloatValues(view.alpha, targetAlpha)
        applyDimAlpha(targetAlpha)
    }

    private fun applyDimAlpha(alpha: Float) {
        alphaAnimator.cancel()
        view.alpha = alpha
        dimLayoutParams.alpha = alpha
    }

    private fun updateDimLayout() {
        if (!view.isAttachedToWindow) {
            return
        }
        try {
            windowManager.updateViewLayout(view, dimLayoutParams)
        } catch (e: IllegalArgumentException) {
            Log.w(TAG, "UDFPS dim layer is no longer attached", e)
        }
    }

    fun addDimLayer() {
        // Keep the dim window in the tree for the overlay lifetime so press is an
        // alpha update, not addView. Keyguard stays at alpha 0 until finger-down
        // so SDM/kernel do not enable panel HBM on idle lock/unlock.
        if (isKeyguard) {
            applyDimAlpha(0f)
        } else {
            brightnessToAlpha()
        }
        view.isVisible = true
        windowManager.addView(view, dimLayoutParams)
        displayManager.registerDisplayListener(
            displayListener,
            null,
            DisplayManager.EVENT_TYPE_DISPLAY_BRIGHTNESS,
        )
    }

    fun removeDimLayer() {
        alphaAnimator.cancel()
        isFingerDown = false
        windowManager.removeView(view)
        displayManager.unregisterDisplayListener(displayListener)
    }

    init {
        view.repeatWhenAttached {
            repeatOnLifecycle(Lifecycle.State.CREATED) {
                listenForBrightnessMirror(this)
            }
        }

        if (!isKeyguard) {
            view.isVisible = true
        }
    }

    private suspend fun listenForBrightnessMirror(scope: CoroutineScope): Job {
        return scope.launch {
            brightnessMirrorShowingInteractor.isShowing.collect {
                isBrightnessMirrorShowing = it
                if (isKeyguard) {
                    updateKeyguardDim()
                } else {
                    view.isVisible = !it
                }
            }
        }
    }

    private var isBrightnessMirrorShowing: Boolean = false
    private var isFingerDown: Boolean = false

    fun setFingerDown(fingerDown: Boolean) {
        if (isFingerDown == fingerDown) {
            return
        }
        isFingerDown = fingerDown
        updateKeyguardDim()
    }

    private fun shouldApplyKeyguardDim(): Boolean {
        return isFingerDown && !isBrightnessMirrorShowing
    }

    private fun updateKeyguardDim() {
        if (!view.isAttachedToWindow) {
            return
        }
        view.isVisible = true
        if (shouldApplyKeyguardDim()) {
            // Snap alpha so HBM and the dim pixels share the next composition.
            brightnessToAlpha()
        } else {
            applyDimAlpha(0f)
        }
        updateDimLayout()
    }
}
