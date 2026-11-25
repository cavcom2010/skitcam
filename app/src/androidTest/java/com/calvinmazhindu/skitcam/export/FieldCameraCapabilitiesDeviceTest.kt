package com.calvinmazhindu.skitcam.export

import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.media.MediaRecorder
import android.os.Build
import android.util.Size
import androidx.camera.camera2.interop.Camera2CameraInfo
import androidx.camera.camera2.interop.ExperimentalCamera2Interop
import androidx.camera.core.CameraSelector
import androidx.camera.core.DynamicRange
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.video.QualitySelector
import androidx.camera.video.Recorder
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.calvinmazhindu.skitcam.video.VideoController
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.TimeUnit

internal const val FIELD_ACCEPTANCE_PACKAGE = "com.calvinmazhindu.skitcam.m15b33field"

/** Reports the exact selectors and SDR Recorder profiles used by the app without opening a camera. */
@RunWith(AndroidJUnit4::class)
@androidx.annotation.OptIn(ExperimentalCamera2Interop::class)
class FieldCameraCapabilitiesDeviceTest {
    @Test fun inventory() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        check(context.packageName == FIELD_ACCEPTANCE_PACKAGE)
        val output = File(context.filesDir, "m15b33-camera-capabilities.json")
        check(!output.exists())
        val provider = ProcessCameraProvider.getInstance(context).get(15, TimeUnit.SECONDS)
        val manager = context.getSystemService(CameraManager::class.java)
        val selectable = JSONObject()
        for ((lens, selector) in listOf("rear" to CameraSelector.DEFAULT_BACK_CAMERA,
            "front" to CameraSelector.DEFAULT_FRONT_CAMERA)) {
            if (!provider.hasCamera(selector)) {
                selectable.put(lens, JSONObject().put("available", false))
                continue
            }
            val info = provider.getCameraInfo(selector)
            val id = Camera2CameraInfo.from(info).cameraId
            val caps = Recorder.getVideoCapabilities(info)
            val qualities = JSONObject()
            caps.getSupportedQualities(DynamicRange.SDR).forEach { quality ->
                val name = checkNotNull(VideoController.qualityName(quality))
                val size = checkNotNull(QualitySelector.getResolution(info, quality))
                qualities.put(name, "${size.width}x${size.height}")
            }
            selectable.put(lens, JSONObject().put("available", true)
                .put("id", id).put("sdrQualities", qualities)
                .put("stabilizationReported", caps.isStabilizationSupported))
        }
        val publicCameras = JSONArray()
        manager.cameraIdList.forEach { id ->
            val info = manager.getCameraCharacteristics(id)
            val sizes = info.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
                ?.getOutputSizes(MediaRecorder::class.java).orEmpty()
            publicCameras.put(JSONObject().put("id", id)
                .put("facing", info.get(CameraCharacteristics.LENS_FACING))
                .put("recorderSizes", JSONArray(sizes.sortedByDescending(Size::getWidth)
                    .map { "${it.width}x${it.height}" })))
        }
        check(output.createNewFile())
        output.writeText(JSONObject().put("model", Build.MODEL).put("build", Build.DISPLAY)
            .put("api", Build.VERSION.SDK_INT).put("selectable", selectable)
            .put("publicCameras", publicCameras).toString(2))
    }
}
