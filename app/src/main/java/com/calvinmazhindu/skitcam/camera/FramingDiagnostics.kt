package com.calvinmazhindu.skitcam.camera

import android.hardware.camera2.*
import android.os.Build
import android.os.SystemClock
import androidx.camera.camera2.interop.Camera2CameraInfo
import androidx.camera.camera2.interop.ExperimentalCamera2Interop
import androidx.camera.core.Camera
import androidx.camera.core.Preview
import androidx.camera.core.ImageCapture
import androidx.camera.core.UseCase
import androidx.camera.view.PreviewView
import org.json.JSONObject

/** Latest passive measurements only. No capture requests, image buffers or persistent history. */
@androidx.annotation.OptIn(ExperimentalCamera2Interop::class)
class FramingDiagnostics {
    private var preview: Preview? = null
    private var capture: UseCase? = null
    private var camera: Camera? = null
    @Volatile private var generation=0
    @Volatile private var result = JSONObject()

    fun bind(camera: Camera, preview: Preview?, capture: UseCase?) {
        this.camera=camera; this.preview=preview; this.capture=capture
    }
    fun release() { generation++;camera=null;preview=null;capture=null;result=JSONObject() }

    fun callback(delegate: CameraCaptureSession.CaptureCallback? = null): CameraCaptureSession.CaptureCallback {
        val token=++generation
        return object : CameraCaptureSession.CaptureCallback() {
            private var last=0L
            override fun onCaptureCompleted(session: CameraCaptureSession, request: CaptureRequest, value: TotalCaptureResult) {
                delegate?.onCaptureCompleted(session,request,value)
                val now=SystemClock.elapsedRealtime()
                if(token!=generation || now-last<500) return
                last=now
                result=JSONObject().put("elapsedRealtimeMs",now)
                    .put("cropRegion",value.get(CaptureResult.SCALER_CROP_REGION)?.toShortString())
                    .put("requestedCropRegion",request.get(CaptureRequest.SCALER_CROP_REGION)?.toShortString())
                    .put("focalLengthMm",value.get(CaptureResult.LENS_FOCAL_LENGTH))
                    .put("videoStabilization",value.get(CaptureResult.CONTROL_VIDEO_STABILIZATION_MODE))
                    .put("opticalStabilization",value.get(CaptureResult.LENS_OPTICAL_STABILIZATION_MODE))
                    .apply { if(Build.VERSION.SDK_INT>=30) put("zoomRatio",value.get(CaptureResult.CONTROL_ZOOM_RATIO)) }
            }
        }
    }

    /** Call on main, while bound. ResolutionInfo is read afresh because target rotation may change. */
    fun snapshot(view: PreviewView): JSONObject {
        fun resolution(useCase: UseCase?): JSONObject? = when(useCase) {
            is Preview -> useCase.resolutionInfo
            is ImageCapture -> useCase.resolutionInfo
            is androidx.camera.video.VideoCapture<*> -> useCase.resolutionInfo
            else -> null
        }?.let { info ->
            JSONObject().put("width",info.resolution.width).put("height",info.resolution.height)
                .put("cropRect",info.cropRect.toShortString()).put("rotationDegrees",info.rotationDegrees)
        }
        val info=camera?.cameraInfo
        val c2=info?.let { Camera2CameraInfo.from(it) }
        val zoom=info?.zoomState?.value
        val xy=IntArray(2);view.getLocationOnScreen(xy)
        return JSONObject().put("cameraId",c2?.cameraId).put("scaleType",view.scaleType.name)
            .put("viewWidth",view.width).put("viewHeight",view.height).put("viewX",xy[0]).put("viewY",xy[1])
            .put("preview",resolution(preview)).put("capture",resolution(capture))
            .put("zoomRatio",zoom?.zoomRatio).put("linearZoom",zoom?.linearZoom)
            .put("minZoom",zoom?.minZoomRatio).put("maxZoom",zoom?.maxZoomRatio)
            .put("sensorActiveArray",c2?.getCameraCharacteristic(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE)?.toShortString())
            .put("sensorOrientation",c2?.getCameraCharacteristic(CameraCharacteristics.SENSOR_ORIENTATION))
            .put("captureResult",result)
    }
}
