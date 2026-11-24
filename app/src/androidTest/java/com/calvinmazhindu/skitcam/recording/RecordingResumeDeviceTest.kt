package com.calvinmazhindu.skitcam.recording

import android.Manifest
import android.hardware.camera2.CameraManager
import android.os.Build
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.view.OrientationEventListener
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.calvinmazhindu.skitcam.MainActivity
import com.calvinmazhindu.skitcam.R
import com.calvinmazhindu.skitcam.export.sha
import org.junit.After
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.atomic.AtomicInteger

/** Real lifecycle and capture. Each test retains its validation session for inspection. */
@RunWith(AndroidJUnit4::class)
class RecordingResumeDeviceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private val args get() = InstrumentationRegistry.getArguments()
    private val expectedPose get() = args.getString("m15b1Pose")?.toInt() ?: 0
    private lateinit var scenario: ActivityScenario<MainActivity>
    private lateinit var model: RecordingViewModel
    private val snapshot get() = model.snapshots.value
    private val store get() = SessionFileStore(File(context.filesDir, "sessions"))
    private val checkpoint get() = File(context.filesDir, "m15b1-checkpoint")
    private val resumeLabel get() = context.getString(R.string.resume_recording)

    @Before fun setup() {
        assumeTrue("M15B.1 validation build only",
            context.packageName.startsWith("com.calvinmazhindu.skitcam.m15b1"))
        for (permission in listOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO)) {
            instrumentation.uiAutomation.executeShellCommand("pm grant ${context.packageName} $permission")
                .use { ParcelFileDescriptor.AutoCloseInputStream(it).readBytes() }
        }
    }

    @After fun close() {
        if (::scenario.isInitialized) scenario.close()
    }

    private fun <T> activity(block: (MainActivity) -> T): T {
        var result: Result<T>? = null
        scenario.onActivity { result = runCatching { block(it) } }
        return checkNotNull(result).getOrThrow()
    }

    private fun phase(value: String) {
        instrumentation.sendStatus(2, Bundle().apply { putString("m15b1Phase", value) })
    }

    private fun await(label: String, timeoutMs: Long = 20_000, predicate: () -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + timeoutMs
        do {
            if (predicate()) return
            SystemClock.sleep(75)
        } while (SystemClock.elapsedRealtime() < deadline)
        fail("Timed out: $label; state=${snapshot.state}; count=${snapshot.count}; message=${snapshot.message}")
    }

    private fun diagnostic() = activity {
        it.orientation.diagnostic().put("previewRotation", it.cameraModel.previewDisplayRotation)
            .put("cameraReady", model.cameraReady).put("state", snapshot.state.name)
            .put("segments", snapshot.count)
    }

    private fun open() {
        scenario = ActivityScenario.launch(MainActivity::class.java)
        model = activity { it.recordingModel }
        await("recovered preview") { model.recovered.value && model.cameraReady }
    }

    private fun begin() {
        open()
        assertEquals("Use a fresh validation package for each case", RecordingState.Ready, snapshot.state)
        assertTrue(expectedPose in setOf(0, 90, 180, 270))
        await("initial physical pose $expectedPose and matching preview") { poseMatchesPreview() }
        activity { model.start(expectedPose) }
        awaitMedia()
    }

    private fun poseMatchesPreview() = activity {
        it.orientation.pose == expectedPose &&
            CameraOrientationPolicy.matchesDisplay(expectedPose, it.orientation.displayRotation) &&
            it.cameraModel.previewDisplayRotation == it.orientation.displayRotation
    }

    private fun awaitMedia() = await("at least 1.2 seconds of recording") {
        snapshot.state == RecordingState.Recording && snapshot.activeDurationMs >= 1200
    }

    private fun paused(count: Int): Session {
        await("exactly $count finalised clips") {
            snapshot.state == RecordingState.Paused && snapshot.count == count && !snapshot.unresolved
        }
        return checkNotNull(snapshot.session).also {
            assertNull(it.pending)
            assertEquals(expectedPose, it.orientationDegrees)
        }
    }

    private fun resumeButton(): AccessibilityNodeInfo? {
        fun find(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
            if (node.contentDescription?.toString() == resumeLabel || node.text?.toString() == resumeLabel) {
                var target: AccessibilityNodeInfo? = node
                while (target != null && target.isEnabled && !target.isClickable) target = target.parent
                return target
            }
            for (i in 0 until node.childCount) {
                node.getChild(i)?.let { find(it)?.let { result -> return result } }
            }
            return null
        }
        if (Build.VERSION.SDK_INT >= 33) instrumentation.uiAutomation.clearCache()
        return instrumentation.uiAutomation.rootInActiveWindow?.let(::find)
    }

    private fun resumeExplicitly() {
        await("enabled user-facing Resume") { resumeButton()?.isEnabled == true }
        assertTrue(checkNotNull(resumeButton()).performAction(AccessibilityNodeInfo.ACTION_CLICK))
        awaitMedia()
    }

    private fun digest(session: Session) = session.segments.associate { it.path to sha(File(it.path)) }

    private fun verifyUnchanged(session: Session, hashes: Map<String, String>) {
        assertEquals(session, snapshot.session)
        assertEquals(RecordingState.Paused, snapshot.state)
        assertEquals(hashes, digest(session))
        assertEquals(hashes.keys, mp4s(session))
    }

    private fun mp4s(session: Session): Set<String> =
        File(store.rootPath, session.id).listFiles().orEmpty().filter { it.extension == "mp4" }
            .map { it.path }.toSet()

    private fun verifyAppended(before: Session, hashes: Map<String, String>) {
        model.pause()
        val after = paused(before.segments.size + 1)
        assertEquals(before.id, after.id)
        assertEquals(before.segments, after.segments.dropLast(1))
        assertEquals(hashes, digest(before))
        val appended = after.segments.last()
        assertEquals(before.segments.last().order + 1, appended.order)
        assertFalse(appended.path in hashes)
        assertEquals(after.orientationDegrees, appended.orientationDegrees)
        assertEquals(appended.videoRotationDegrees, store.validate(appended.path))
        assertEquals(hashes.keys + appended.path, mp4s(after))
        phase("preserved ${hashes.size} original SHA-256 digest(s); added exactly one different, valid MP4")
    }

    private fun foreground() {
        scenario.moveToState(Lifecycle.State.RESUMED)
        await("camera ready after foreground") { model.cameraReady }
    }

    private fun awaitPose() {
        phase(diagnostic().toString())
        try {
            await("stationary matching pose after foreground") { poseMatchesPreview() }
        } finally {
            phase(diagnostic().toString())
        }
    }

    @Test fun recordingReturnsWithoutMotion() {
        begin()
        val cameraId = checkNotNull(snapshot.session?.captureProfile).cameraId
        val available = AtomicInteger()
        val cameraManager = context.getSystemService(CameraManager::class.java)
        val callback = object : CameraManager.AvailabilityCallback() {
            override fun onCameraAvailable(id: String) { if (id == cameraId) available.set(1) }
            override fun onCameraUnavailable(id: String) { if (id == cameraId) available.set(-1) }
        }
        cameraManager.registerAvailabilityCallback(ContextCompat.getMainExecutor(context), callback)
        try {
            await("camera owned during recording") { available.get() == -1 }
            scenario.moveToState(Lifecycle.State.CREATED)
            val before = paused(1)
            val hashes = digest(before)
            await("camera released in background") { available.get() == 1 }
            assertFalse(model.cameraReady)
            foreground()
            await("camera reacquired in foreground") { available.get() == -1 }
            awaitPose()
            verifyUnchanged(before, hashes)
            resumeExplicitly()
            verifyAppended(before, hashes)
        } finally {
            cameraManager.unregisterAvailabilityCallback(callback)
        }
    }

    @Test fun pausedAndReviewSurviveRepeatedReturnsWithoutBypassingBattery() {
        begin()
        model.pause()
        val before = paused(1)
        val hashes = digest(before)
        repeat(3) { index ->
            if (index == 1) {
                activity { it.reviewController.open(before.id, before.segments.single()) }
                assertNotNull(activity { it.reviewController.selection })
            }
            scenario.moveToState(Lifecycle.State.CREATED)
            assertFalse(model.cameraReady)
            assertNull(activity { it.reviewController.selection })
            foreground()
            awaitPose()
            verifyUnchanged(before, hashes)
        }
        await("Resume available before battery injection") { resumeButton()?.isEnabled == true }
        val critical = BatteryReading(5.0)
        activity {
            model.updateBattery(critical)
            assertEquals(critical, model.battery.value)
        }
        var sawReplacement = false
        var sawMissingButton = false
        var sawEnabledDuringInjection = false
        try {
            await("Resume disabled while the injected battery reading is active") {
                val active = model.battery.value == critical
                val enabled = resumeButton()?.isEnabled
                sawReplacement = sawReplacement || !active
                sawMissingButton = sawMissingButton || enabled == null
                sawEnabledDuringInjection = sawEnabledDuringInjection || (active && enabled == true)
                active && enabled == false
            }
        } finally {
            phase("battery probe: replaced=$sawReplacement missingResume=$sawMissingButton " +
                "enabledDuringInjection=$sawEnabledDuringInjection finalReading=${model.battery.value} " +
                "finalResumeEnabled=${resumeButton()?.isEnabled}")
        }
        model.resume(expectedPose)
        await("runtime rejected low-battery Resume") { snapshot.message?.contains("battery") == true }
        verifyUnchanged(before, hashes)
        activity { model.updateBattery(BatteryReading(80.0)) }
        resumeExplicitly()
        verifyAppended(before, hashes)
    }

    @Test fun rotatedInBackgroundBlocksResumeUntilOriginalPoseReturns() {
        assumeTrue("Use the host rotation prompts", args.getString("m15b1Rotate") == "true")
        begin()
        model.pause()
        val before = paused(1)
        val hashes = digest(before)
        scenario.moveToState(Lifecycle.State.CREATED)
        phase("ROTATE_WHILE_BACKGROUND: turn the device a quarter turn now")
        // Give the physical operator or emulator host time to rotate before foregrounding.
        SystemClock.sleep(5_000)
        foreground()
        await("stable different physical pose") {
            activity { it.orientation.pose?.let { pose -> pose != expectedPose } == true }
        }
        phase(diagnostic().toString())
        await("Resume disabled at mismatched orientation") { resumeButton()?.isEnabled == false }
        model.resume(activity { it.orientation.pose })
        await("runtime rejected mismatched orientation") { snapshot.message?.contains("original $expectedPose°") == true }
        verifyUnchanged(before, hashes)
        phase("RESTORE_ORIGINAL_POSE")
        awaitPose()
        resumeExplicitly()
        verifyAppended(before, hashes)
    }

    @Test fun checkpointForProcessDeath() {
        begin()
        model.pause()
        val session = paused(1)
        checkpoint.writeText(SessionCodec.encode(session) + "\n" + digest(session).values.single())
    }

    @Test fun explicitResumeAfterProcessDeath() {
        assertTrue("Run checkpointForProcessDeath, then force-stop the validation app only", checkpoint.isFile)
        val payload = checkpoint.readText()
        val before = SessionCodec.decode(payload.substringBeforeLast('\n'))
        val hashes = mapOf(before.segments.single().path to payload.substringAfterLast('\n'))
        open()
        awaitPose()
        verifyUnchanged(before, hashes)
        resumeExplicitly()
        verifyAppended(before, hashes)
    }

    /** Emulator sensor coverage even on virtual cameras that cannot produce accepted MP4s. */
    @Test fun controlledSensorForegroundTransitions() {
        assumeTrue("Requires host-controlled emulator acceleration",
            args.getString("m15b1ControlledSensor") == "true")
        open()
        await("initial sensor pose") { poseMatchesPreview() }
        scenario.moveToState(Lifecycle.State.CREATED)
        foreground()
        if (args.getString("m15b1BaselineProbe") == "true") {
            SystemClock.sleep(1_200)
            phase("stationary baseline: ${diagnostic()}")
            assertNull(activity { it.orientation.pose })
            phase("NUDGE_NOW")
            awaitPose()
            return
        }
        awaitPose()
        scenario.moveToState(Lifecycle.State.CREATED)
        phase("ROTATE_WHILE_BACKGROUND")
        SystemClock.sleep(5_000)
        foreground()
        val otherPose = (expectedPose + 270) % 360
        await("rotation while backgrounded replaces old pose") { activity { it.orientation.pose == otherPose } }
        phase(diagnostic().toString())
        phase("RESTORE_ORIGINAL_POSE")
        awaitPose()
        assertEquals(RecordingState.Ready, snapshot.state)
        assertNull(snapshot.session)
    }

    @Test fun compareStationaryAndNudgedBaseline() {
        assumeTrue("Run only against unchanged main with the diagnostic test APK",
            args.getString("m15b1BaselineProbe") == "true")
        begin()
        scenario.moveToState(Lifecycle.State.CREATED)
        val before = paused(1)
        val hashes = digest(before)
        foreground()
        SystemClock.sleep(1_200)
        val probeAngle = AtomicInteger(OrientationEventListener.ORIENTATION_UNKNOWN)
        val freshListener = activity { current ->
            object : OrientationEventListener(current) {
                override fun onOrientationChanged(angle: Int) { probeAngle.set(angle) }
            }.also { it.enable() }
        }
        try {
            await("fresh Android listener reports a valid angle without moving", 5_000) {
                probeAngle.get() != OrientationEventListener.ORIENTATION_UNKNOWN
            }
            val stationary = diagnostic()
            phase("stationary: freshListener=${probeAngle.get()}; app=$stationary")
            assertEquals(OrientationEventListener.ORIENTATION_UNKNOWN, stationary.getInt("rawPose"))
            assertTrue(stationary.isNull("filteredPose"))
            await("Resume disabled while baseline pose is missing") { resumeButton()?.isEnabled == false }
            verifyUnchanged(before, hashes)
            activity { Toast.makeText(it, "SkitCam test: gently tilt and return upright now", Toast.LENGTH_LONG).show() }
            phase("NUDGE_NOW: gently tilt sideways and return to the original pose")
            awaitPose()
            phase("after nudge: freshListener=${probeAngle.get()}; app=${diagnostic()}")
            verifyUnchanged(before, hashes)
            resumeExplicitly()
            verifyAppended(before, hashes)
        } finally {
            activity { freshListener.disable() }
        }
    }
}
