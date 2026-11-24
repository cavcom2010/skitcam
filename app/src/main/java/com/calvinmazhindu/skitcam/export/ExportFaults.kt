package com.calvinmazhindu.skitcam.export

import android.content.Context
import android.os.Process
import java.io.File
import java.io.RandomAccessFile

/**
 * Deterministic failures for the verification-recovery suite only.
 *
 * Armed solely by the isolated instrumentation package in a debug build. Production
 * scheduling never consults this object: every hook is a no-op unless [armed].
 */
internal object ExportFaults {
    enum class Mode {
        NONE,
        VIDEO_INIT,
        VIDEO_MID,
        VIDEO_RELEASE,
        AUDIO_MID,
        PERSISTENT_VIDEO_MID,
        CORRUPT_AFTER_CHECKPOINT,
        TRUNCATE_AFTER_CHECKPOINT,
        DELETE_AFTER_CHECKPOINT,
        KILL_BEFORE_CHECKPOINT,
        KILL_AFTER_CHECKPOINT
    }

    const val VALIDATION_PACKAGE = "com.calvinmazhindu.skitcam.m15b31a.m12bvalidation"
    const val FAILURE_OUTPUT = 10

    @Volatile private var mode = Mode.NONE
    @Volatile private var armed = false

    fun arm(context: Context, value: Mode) {
        require(com.calvinmazhindu.skitcam.BuildConfig.DEBUG &&
            context.packageName == VALIDATION_PACKAGE) { "Fault injection is limited to the debug validation package." }
        mode = value
        armed = true
    }

    fun disarm() {
        mode = Mode.NONE
        armed = false
    }

    fun current(): Mode = if (armed) mode else Mode.NONE

    /** Called after production and before the candidate checkpoint is committed. */
    fun beforeCheckpoint() {
        if (current() == Mode.KILL_BEFORE_CHECKPOINT) kill()
    }

    /** Called after the checkpoint is durable and before verification starts. */
    fun afterCheckpoint(candidate: File) {
        when (current()) {
            Mode.KILL_AFTER_CHECKPOINT -> kill()
            Mode.CORRUPT_AFTER_CHECKPOINT -> RandomAccessFile(candidate, "rw").use { file ->
                val offset = file.length() / 2
                file.seek(offset)
                val original = file.read()
                file.seek(offset)
                file.write(original xor 0x5a)
            }
            Mode.TRUNCATE_AFTER_CHECKPOINT -> RandomAccessFile(candidate, "rw").use {
                it.setLength(it.length() * 2 / 3)
            }
            Mode.DELETE_AFTER_CHECKPOINT -> check(candidate.delete()) { "Injected candidate deletion failed." }
            else -> Unit
        }
    }

    fun kill() {
        Process.killProcess(Process.myPid())
        // SIGKILL does not return; this guard only makes the intent explicit.
        Thread.sleep(60_000)
    }
}
