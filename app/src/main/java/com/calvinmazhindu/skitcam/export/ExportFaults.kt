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
        KILL_AFTER_CHECKPOINT,
        KILL_DURING_RECHECK,
        KILL_BEFORE_INSERT,
        KILL_AFTER_INSERT,
        KILL_AFTER_INSERT_INTENT,
        KILL_DURING_GALLERY_WRITE,
        KILL_BEFORE_VISIBLE,
        KILL_AFTER_VISIBLE,
        FORBID_GALLERY_WRITE,
        NULL_GALLERY_QUERY,
        EMPTY_GALLERY_QUERY,
        FAILED_GALLERY_QUERY,
        AMBIGUOUS_GALLERY_QUERY,
        FORMAT_WITHOUT_AVC_CONFIG,
        RENDER_PRODUCTION_IO,
        CANCEL_AT_PASSTHROUGH,
        KILL_AFTER_PATH_TRANSITION,
        VERIFIER_EXTRACTOR_IO,
        WRONG_VERIFICATION_DURATION,
        GALLERY_STORAGE_EXHAUSTED,
    }

    const val VALIDATION_PACKAGE = "com.calvinmazhindu.skitcam.m15b31a.m12bvalidation"
    const val COMPATIBILITY_PACKAGE = "com.calvinmazhindu.skitcam.m15b31b.m12bvalidation"
    const val FAILURE_OUTPUT = 10

    const val HARDENING_PACKAGE = "com.calvinmazhindu.skitcam.m15b32.m12bvalidation"
    @Volatile private var modes = emptySet<Mode>()

    fun allowed(context: Context) = com.calvinmazhindu.skitcam.BuildConfig.DEBUG &&
        context.packageName in setOf(VALIDATION_PACKAGE, COMPATIBILITY_PACKAGE, HARDENING_PACKAGE)

    fun arm(context: Context, vararg values: Mode) {
        require(allowed(context)) { "Fault injection is limited to isolated debug validation packages." }
        modes = values.toSet()
    }

    fun disarm() { modes = emptySet() }

    fun current(): Mode = modes.firstOrNull() ?: Mode.NONE
    fun has(mode: Mode): Boolean = mode in modes

    fun publicationPoint(mode: Mode) {
        if (has(mode)) kill()
    }

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
