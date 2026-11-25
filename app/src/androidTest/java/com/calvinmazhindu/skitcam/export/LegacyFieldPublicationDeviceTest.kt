package com.calvinmazhindu.skitcam.export

import android.Manifest
import android.content.ContentUris
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.provider.MediaStore
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.calvinmazhindu.skitcam.recording.RecordingState
import com.calvinmazhindu.skitcam.recording.Segment
import com.calvinmazhindu.skitcam.recording.Session
import com.calvinmazhindu.skitcam.video.CaptureProfile
import com.calvinmazhindu.skitcam.video.VideoSettings
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

/** The legacy scanner is not covered by the scoped-storage Moto tests. */
@RunWith(AndroidJUnit4::class)
@Suppress("DEPRECATION")
class LegacyFieldPublicationDeviceTest {
    @Test fun storagePermissionRetryPublishesOneScannedVideo() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val pkg = "com.calvinmazhindu.skitcam.m15b1m15b33emu"
        check(context.packageName == pkg && Build.HARDWARE in setOf("goldfish", "ranchu") &&
            Build.VERSION.SDK_INT == 28)
        val permissions = listOf(Manifest.permission.READ_EXTERNAL_STORAGE, Manifest.permission.WRITE_EXTERNAL_STORAGE)
        permissions.forEach { assertEquals(PackageManager.PERMISSION_DENIED, context.checkSelfPermission(it)) }
        val golden = File(context.filesDir, "legacy-input.mp4")
        val digest = sha(golden)
        val sourceMs = inspect(golden).duration
        val id = UUID.randomUUID().toString()
        val root = File(context.filesDir, "sessions/$id").apply { check(mkdirs()) }
        val original = golden.copyTo(File(root, "segment_001.mp4"))
        val segment = Segment(1, original.path, sourceMs, 270, 0, id = "$id-1")
        val session = Session(id, System.currentTimeMillis(), 270, RecordingState.Completed, listOf(segment),
            playbackOrder = listOf(segment.id),
            captureProfile = CaptureProfile("fixture", VideoSettings("HD"), 1280, 720))
        ExportJournal.request(context, session)
        ExportJournal.finalised(context, session)
        fun finish(retry: Boolean): JSONObject {
            ExportService.launch(context, id, retry)
            val deadline = SystemClock.elapsedRealtime() + 90_000
            while (ExportJournal.busy && SystemClock.elapsedRealtime() < deadline) SystemClock.sleep(50)
            assertFalse("Service never became idle", ExportJournal.busy)
            return checkNotNull(ExportJournal.read(context, id))
        }
        val failed = finish(retry = false)
        assertEquals("Failed", failed.getString("state"))
        assertTrue(failed.getString("message").startsWith("Storage permission is needed"))
        assertTrue(failed.optString("uri").isBlank())
        val verified = failed.getString("verifiedSha")
        assertEquals(verified, sha(File(ExportJournal.directory(context, id), "output.mp4")))
        assertEquals(digest, sha(original))
        val publicPath = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES),
            "SkitCam/${galleryName(failed.getLong("createdAt"), failed.getString("id"))}")
        permissions.forEach { permission ->
            ParcelFileDescriptor.AutoCloseInputStream(
                instrumentation.uiAutomation.executeShellCommand("pm grant $pkg $permission")).use { it.readBytes() }
            assertEquals(PackageManager.PERMISSION_GRANTED, context.checkSelfPermission(permission))
        }
        assertFalse(publicPath.exists())
        val completed = finish(retry = true)
        assertEquals("Completed", completed.getString("state"))
        assertEquals(failed.getString("id"), completed.getString("id"))
        assertEquals(failed.getInt("productionAttempts"), completed.getInt("productionAttempts"))
        assertEquals(failed.getInt("verificationAttempts"), completed.getInt("verificationAttempts"))
        val collection = MediaStore.Video.Media.EXTERNAL_CONTENT_URI
        val rows = checkNotNull(context.contentResolver.query(collection, arrayOf(MediaStore.Video.Media._ID),
            "${MediaStore.Video.Media.DATA}=?", arrayOf(publicPath.path), null)).use { cursor ->
            buildList { while (cursor.moveToNext()) add(ContentUris.withAppendedId(collection, cursor.getLong(0))) }
        }
        assertEquals(listOf(Uri.parse(completed.getString("uri"))), rows)
        assertEquals(digest, sha(original))
        assertEquals(verified, sha(publicPath))
        val readback = checkNotNull(context.contentResolver.openInputStream(rows.single())).use(::hashGalleryStream)
        assertEquals(verified, readback)
        File(context.filesDir, "m15b33-legacy-receipt.json").apply {
            check(createNewFile())
            writeText(JSONObject().put("uri", rows.single().toString()).put("sha256", readback)
                .put("jobId", completed.getString("id")).put("durationMs", sourceMs)
                .put("path", completed.getString("selectedPath")).put("fallbackReason", completed.optString("fallbackReason"))
                .toString(2))
        }
    }
}
