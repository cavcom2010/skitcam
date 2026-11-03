package com.calvinmazhindu.skitcam.camera

import com.calvinmazhindu.skitcam.export.*
import com.calvinmazhindu.skitcam.recording.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class SaveReturnTest {
    private val session=Session("s",1,0,RecordingState.Completed)
    private val snapshot=RecordingSnapshot(RecordingState.Completed,session)
    private val video=SavedVideo("s","job","content://media/external/video/media/42",100)
    private val completed=ExportStatus("s",ExportState.Completed,uri=video.uri)
    @Test fun publicationAndIdleServiceAreBothRequired() {
        assertTrue(SaveReturnPolicy.eligible(snapshot,completed,false))
        assertFalse(SaveReturnPolicy.eligible(snapshot,completed,true))
        for(state in ExportState.entries.filter { it!=ExportState.Completed })
            assertFalse(state.name,SaveReturnPolicy.eligible(snapshot,completed.copy(state=state),false))
    }
    @Test fun completedUriMustBeUsableByGalleryFlow() {
        for(uri in listOf(null,"","file:///partial.mp4")) assertFalse(SaveReturnPolicy.eligible(snapshot,completed.copy(uri=uri),false))
    }
    @Test fun oldOrUnrelatedExportCannotReturnAnotherSession() {
        assertFalse(SaveReturnPolicy.eligible(snapshot,completed.copy(sessionId="other"),false))
        assertFalse(SaveReturnPolicy.eligible(RecordingSnapshot(),completed,false))
    }
    @Test fun recordingAndRecoveryBlockCompletionHandling() {
        for(state in RecordingState.entries.filter { it!=RecordingState.Completed })
            assertFalse(state.name,SaveReturnPolicy.eligible(snapshot.copy(state=state),completed,false))
        assertFalse(SaveReturnPolicy.eligible(snapshot.copy(unresolved=true),completed,false))
    }
    @Test fun duplicateSuccessJobDoesNotCreateAnotherReceipt() {
        assertTrue(SaveReturnPolicy.accepts(video,null))
        assertFalse(SaveReturnPolicy.accepts(video,SaveReceipt(video)))
        assertFalse(SaveReturnPolicy.accepts(video,SaveReceipt(video,true)))
        assertTrue(SaveReturnPolicy.accepts(video.copy(jobId="next"),SaveReceipt(video,true)))
    }
    @Test fun pendingReceiptSurvivesRestartUntilCameraActuallyReturns() {
        val pending=SaveReceipt.decode(SaveReceipt(video).encode())
        assertEquals(video,pending.video)
        assertFalse(SaveReturnPolicy.handled("s",pending,null))
        assertTrue(SaveReturnPolicy.handled("s",SaveReceipt.decode(pending.copy(handled=true).encode()),null))
    }
    @Test fun handlingOneSessionDoesNotConsumeAnotherSession() {
        assertFalse(SaveReturnPolicy.handled("next",SaveReceipt(video,true),"s"))
        assertTrue(SaveReturnPolicy.handled("s",null,"s"))
        assertFalse(SaveReturnPolicy.handled("s",null,null))
    }
    @Test fun malformedReceiptsAndFutureVersionsAreRejected() {
        val json=JSONObject(SaveReceipt(video).encode())
        for(value in listOf("{}",json.toString().replace("content://","file://"),json.put("version",2).toString()))
            assertThrows(Exception::class.java) { SaveReceipt.decode(value) }
        assertFalse(SaveReturnPolicy.accepts(video.copy(createdAtMs=-1),null))
        assertFalse(SaveReturnPolicy.accepts(video.copy(jobId=""),null))
    }
    @Test fun unseenExportOverridesPhotoOnceWithoutChangingSavedChoice() {
        val preference=CameraChoice(CameraMode.Photo,CameraLens.Front)
        val pending=SaveReceipt(video)
        assertEquals(CameraMode.SkitVideo,CameraPolicy.initial(preference,snapshot,!SaveReturnPolicy.handled("s",pending,null)).mode)
        assertEquals(preference,CameraPolicy.initial(preference,snapshot,!SaveReturnPolicy.handled("s",pending.copy(handled=true),null)))
        assertEquals(CameraMode.Photo,preference.mode)
    }
    @Test fun pausedRecoveryStillWinsAfterPreviousSaveWasHandled() {
        val paused=session.copy(id="next",state=RecordingState.Paused,lens=CameraLens.Front)
        assertEquals(CameraChoice(CameraMode.SkitVideo,CameraLens.Front),CameraPolicy.initial(CameraChoice(CameraMode.Photo),RecordingSnapshot(paused.state,paused),false))
    }
    @Test fun shortQualityLabelDescribesBoundSizeAndRequest() {
        assertEquals("1080p · Auto",BoundCameraInfo(1920,1080,false,null).concise)
        assertEquals("720p · 30 target",BoundCameraInfo(720,1280,false,30).concise)
        assertEquals("4K · 60 target",BoundCameraInfo(3840,2160,false,60).concise)
        assertEquals("Photo",BoundCameraInfo(4096,3072,true,null).concise)
        assertEquals("1440p · Auto",BoundCameraInfo(1920,1440,false,null).concise)
    }
}
