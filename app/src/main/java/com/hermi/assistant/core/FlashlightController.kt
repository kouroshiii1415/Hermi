package com.hermi.assistant.core

import android.content.Context
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager

/**
 * Flashlight control via CameraManager torch mode (no CAMERA permission needed).
 */
object FlashlightController {

    private var cameraId: String? = null
    private var isOn = false

    private fun resolve(cm: CameraManager): String? {
        cameraId?.let { return it }
        for (id in cm.cameraIdList) {
            val chars = cm.getCameraCharacteristics(id)
            if (chars.get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true &&
                chars.get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_BACK
            ) {
                cameraId = id
                return id
            }
        }
        return null
    }

    fun setTorch(context: Context, on: Boolean): String {
        val cm = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        val id = resolve(cm)
            ?: return "ERROR: this device has no flashlight unit."
        return try {
            cm.setTorchMode(id, on)
            isOn = on
            if (on) "Flashlight on." else "Flashlight off."
        } catch (e: Exception) {
            "ERROR: could not ${if (on) "enable" else "disable"} flashlight: ${e.message}"
        }
    }

    val on: Boolean get() = isOn
}
