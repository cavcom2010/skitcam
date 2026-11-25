package com.calvinmazhindu.skitcam.export

import android.annotation.SuppressLint
import android.content.Context
import android.os.Environment
import java.io.File

internal const val EXPORT_SAFETY_BYTES = 100L * 1024 * 1024

/** Verification streams buffers in memory; the remaining disk work is one Gallery copy. */
internal fun galleryCopyBytes(candidateBytes: Long): Long {
    require(candidateBytes > 0)
    return Math.addExact(candidateBytes, EXPORT_SAFETY_BYTES)
}

@SuppressLint("UsableSpace")
@Suppress("DEPRECATION")
internal fun checkGallerySpace(context: Context, file: File) {
    val available = if (ExportFaults.has(ExportFaults.Mode.GALLERY_STORAGE_EXHAUSTED)) 0L else ExportProcessor.availableBytesForTests?.takeIf {
        context.packageName.endsWith(".m12bvalidation")
    } ?: minOf(file.parentFile!!.usableSpace, Environment.getExternalStorageDirectory().usableSpace)
    check(available >= galleryCopyBytes(file.length())) {
        "Insufficient space for the verified Gallery copy. Original clips are preserved."
    }
}
