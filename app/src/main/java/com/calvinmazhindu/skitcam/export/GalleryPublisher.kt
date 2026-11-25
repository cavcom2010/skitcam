package com.calvinmazhindu.skitcam.export

import android.Manifest
import android.content.ContentResolver
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.media.MediaExtractor
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.ParcelFileDescriptor
import android.provider.MediaStore
import android.system.Os
import androidx.annotation.RequiresApi
import androidx.core.net.toUri
import java.io.File
import java.io.FileNotFoundException
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

internal const val GALLERY_PATH = "Movies/SkitCam/"

internal fun galleryName(created: Long, jobId: String): String {
    val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).apply {
        timeZone = TimeZone.getTimeZone("UTC")
    }.format(Date(created))
    return "SkitCam_${stamp}_${jobId}.mp4"
}

/** Serialize publication within the app even when invoked outside the export worker. */
@Synchronized
internal fun publish(context: Context, id: String, file: File, jobId: String): Uri {
    val journal = checkNotNull(ExportJournal.read(context, id))
    check(journal.getString("id") == jobId && journal.getString("state") == ExportState.Publishing.name)
    val verifiedSha = journal.getString("verifiedSha")
    require(verifiedSha.matches(Regex("[0-9a-f]{64}"))) { "Missing verified output digest" }
    val identity = PublicationIdentity(galleryName(journal.getLong("createdAt"), jobId),
        GALLERY_PATH, context.packageName, "SkitCam export job:$jobId")
    return if (Build.VERSION.SDK_INT < 29) publishLegacy(context, file, verifiedSha, identity.name)
    else MediaStorePublication(context, id, file, verifiedSha, identity, journal.optString("uri"),
        journal.optBoolean("publicationInsertAttempted") || !journal.optBoolean("publicationProtocolV2")).finish()
}

private fun prepareGalleryCopy(context: Context, file: File, verifiedSha: String) {
    check(Files.isRegularFile(file.toPath(), NOFOLLOW_LINKS) && file.length() > 0 && sha(file) == verifiedSha) {
        "Private output is missing or differs from the verified video. Original clips are preserved."
    }
    checkGallerySpace(context, file)
}

private fun publishLegacy(context: Context, file: File, verifiedSha: String, name: String): Uri {
    check(context.checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED &&
        context.checkSelfPermission(Manifest.permission.READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED) {
        "Storage permission is needed to save to the gallery. Open Retry to grant it."
    }
    @Suppress("DEPRECATION")
    val directory = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES), "SkitCam")
    check(directory.isDirectory || directory.mkdirs())
    val target = File(directory, name)
    if (!target.exists()) {
        prepareGalleryCopy(context, file, verifiedSha)
        val staging = File(directory, ".$name.partial")
        file.inputStream().use { input -> staging.outputStream().use { input.copyTo(it); it.fd.sync() } }
        check(sha(staging) == verifiedSha)
        // Never replace a finished target on retry.
        java.nio.file.Files.move(staging.toPath(), target.toPath())
    }
    check(sha(target) == verifiedSha) { "An existing gallery file does not match this job." }
    var uri: Uri? = null
    val done = CountDownLatch(1)
    MediaScannerConnection.scanFile(context, arrayOf(target.path), arrayOf("video/mp4")) { _, found ->
        uri = found
        done.countDown()
    }
    check(done.await(30, TimeUnit.SECONDS) && uri != null) { "Gallery indexing failed. Verified video retained." }
    return uri
}

@RequiresApi(29)
private class MediaStorePublication(
    private val context: Context, private val id: String, private val file: File,
    private val verifiedSha: String, private val identity: PublicationIdentity,
    savedUri: String, private var insertMayHaveRun: Boolean,
) : PublicationRecovery.Port {
    private val resolver = context.contentResolver
    private val collection = MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
    private var savedRow = savedUri.takeIf { it.isNotBlank() }?.let { rowId(it.toUri()) }
    private fun uri(row: Long) = ContentUris.withAppendedId(collection, row)
    private fun rowId(uri: Uri): Long {
        val row = ContentUris.parseId(uri)
        require(row > 0 && this.uri(row) == uri) { "Invalid publication checkpoint URI" }
        return row
    }

    fun finish(): Uri = uri(PublicationRecovery(this).finish())

    override fun lookup(): PublicationLookup {
        val columns = mutableListOf("_id", "_display_name", "relative_path", "owner_package_name", "description", "is_pending")
        if (Build.VERSION.SDK_INT >= 30) columns += "is_trashed"
        // One snapshot: detect a recorded row that changed identity as well as job-name or
        // marker collisions, without filtering foreign ownership out of the result set.
        val selection = "description=? OR _display_name=?" + if (savedRow != null) " OR _id=?" else ""
        val args = listOfNotNull(identity.marker, identity.name, savedRow?.toString()).toTypedArray()
        val rows: List<PublicationRow>? = try {
            query(columns.toTypedArray(), selection, args)?.use { cursor ->
                buildList<PublicationRow> {
                    while (cursor.moveToNext()) {
                        add(PublicationRow(cursor.getLong(0), cursor.getString(1), cursor.getString(2),
                            cursor.getString(3), cursor.getString(4), if (cursor.isNull(5)) null else cursor.getInt(5),
                            if (Build.VERSION.SDK_INT < 30) 0 else if (cursor.isNull(6)) null else cursor.getInt(6)))
                        if (size > 1) break
                    }
                }
            }
        } catch (_: Exception) {
            null
        }
        return identity.resolve(rows, savedRow, insertMayHaveRun)
    }

    @Suppress("DEPRECATION")
    private fun query(columns: Array<String>, selection: String, args: Array<String>): android.database.Cursor? {
        if (ExportFaults.has(ExportFaults.Mode.NULL_GALLERY_QUERY)) return null
        if (ExportFaults.has(ExportFaults.Mode.FAILED_GALLERY_QUERY)) throw android.database.SQLException("Injected query failure")
        if (ExportFaults.has(ExportFaults.Mode.EMPTY_GALLERY_QUERY)) return android.database.MatrixCursor(columns)
        if (ExportFaults.has(ExportFaults.Mode.AMBIGUOUS_GALLERY_QUERY)) return android.database.MatrixCursor(columns).apply {
            repeat(2) { index ->
                newRow().add(index + 1).add(identity.name).add(identity.path).add(identity.owner)
                    .add(identity.marker).add(1).apply { if (columns.size == 7) add(0) }
            }
        }
        return if (Build.VERSION.SDK_INT >= 30) {
            resolver.query(collection, columns, Bundle().apply {
                putString(ContentResolver.QUERY_ARG_SQL_SELECTION, selection)
                putStringArray(ContentResolver.QUERY_ARG_SQL_SELECTION_ARGS, args)
                putInt(MediaStore.QUERY_ARG_MATCH_PENDING, MediaStore.MATCH_INCLUDE)
                putInt(MediaStore.QUERY_ARG_MATCH_TRASHED, MediaStore.MATCH_INCLUDE)
            }, null)
        } else resolver.query(MediaStore.setIncludePending(collection), columns, selection, args, null)
    }

    override fun prepareCopy() = prepareGalleryCopy(context, file, verifiedSha)

    override fun insertPending(): Long {
        checkCancelled()
        ExportFaults.publicationPoint(ExportFaults.Mode.KILL_BEFORE_INSERT)
        // After this durable write, even a successful empty query is inconclusive:
        // the provider may have accepted the insert or the row may have lost its marker.
        ExportJournal.markInsertAttempted(context, id)
        insertMayHaveRun = true
        ExportFaults.publicationPoint(ExportFaults.Mode.KILL_AFTER_INSERT_INTENT)
        val inserted = checkNotNull(resolver.insert(collection, ContentValues().apply {
            put(MediaStore.Video.Media.DISPLAY_NAME, identity.name)
            put(MediaStore.Video.Media.DESCRIPTION, identity.marker)
            put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
            put(MediaStore.Video.Media.RELATIVE_PATH, identity.path)
            put(MediaStore.Video.Media.IS_PENDING, 1)
        })) { "Cannot confirm Gallery insertion. Verified video retained." }
        ExportFaults.publicationPoint(ExportFaults.Mode.KILL_AFTER_INSERT)
        return rowId(inserted)
    }

    override fun saveRow(row: Long) {
        if (row != savedRow) {
            ExportJournal.update(context, id, ExportState.Publishing, uri = uri(row).toString())
            savedRow = row
        }
    }

    private fun openPendingForRead(row: Long): ParcelFileDescriptor? = try {
        checkNotNull(resolver.openFileDescriptor(uri(row), "r"))
    } catch (_: FileNotFoundException) {
        // MediaStore can allocate a row before the backing file exists. This is not
        // row absence; it still must be confirmed pending before an append open.
        null
    }

    override fun pendingMatches(row: Long): Boolean {
        val descriptor = openPendingForRead(row) ?: return false
        return ParcelFileDescriptor.AutoCloseInputStream(descriptor).use { input ->
            val matches = descriptor.statSize != 0L && hashGalleryStream(input) == verifiedSha
            if (matches) Os.fsync(descriptor.fileDescriptor)
            matches
        }
    }

    override fun writePending(row: Long) {
        checkCancelled()
        ExportTrace.measure(id, "galleryCopyAndSync") {
            file.inputStream().use { input ->
                val buffer = ByteArray(65536)
                // Compare existing bytes using a read-only descriptor, before requesting append.
                val existing = openPendingForRead(row)?.use { descriptor ->
                    val prefix = ByteArray(buffer.size)
                    val fd = descriptor.fileDescriptor
                    val stat = Os.fstat(fd)
                    check(stat.st_size in 0..file.length()) { "Pending Gallery copy has an unexpected length." }
                    var remaining = stat.st_size
                    while (remaining > 0) {
                        checkCancelled()
                        val count = minOf(remaining, buffer.size.toLong()).toInt()
                        var sourceRead = 0
                        while (sourceRead < count) {
                            val n = input.read(buffer, sourceRead, count - sourceRead)
                            check(n > 0) { "Verified output ended during prefix check." }
                            sourceRead += n
                        }
                        var read = 0
                        while (read < count) {
                            val n = Os.read(fd, prefix, read, count - read)
                            check(n > 0) { "Pending Gallery prefix could not be read." }
                            read += n
                        }
                        check((0 until count).all { buffer[it] == prefix[it] }) {
                            "Pending Gallery copy differs from the verified video. It was not overwritten."
                        }
                        remaining -= count
                    }
                    stat
                }
                checkCancelled()
                check(lookup() == PublicationLookup.Pending(row)) { "Gallery row changed before append." }
                check(!ExportFaults.has(ExportFaults.Mode.FORBID_GALLERY_WRITE)) { "Unexpected writable Gallery open" }
                // "wa" requests O_APPEND, never O_TRUNC. No existing byte can be overwritten.
                val descriptor = checkNotNull(resolver.openFileDescriptor(uri(row), "wa"))
                ParcelFileDescriptor.AutoCloseOutputStream(descriptor).use { output ->
                    val stat = Os.fstat(descriptor.fileDescriptor)
                    check(lookup() == PublicationLookup.Pending(row) && stat.st_size == (existing?.st_size ?: 0L) &&
                        (existing == null || (existing.st_ino == stat.st_ino && existing.st_dev == stat.st_dev))) {
                        "Gallery row changed during copy preparation."
                    }
                    var appended = 0L
                    while (true) {
                        checkCancelled()
                        val n = input.read(buffer)
                        if (n < 0) break
                        output.write(buffer, 0, n)
                        ExportFaults.publicationPoint(ExportFaults.Mode.KILL_DURING_GALLERY_WRITE)
                        appended += n
                    }
                    ExportTrace.bytes(id, "galleryCopyBytes", appended)
                    output.flush()
                    output.fd.sync()
                }
            }
        }
    }

    override fun verifyReadOnly(row: Long) {
        val target = uri(row)
        ExportTrace.measure(id, "galleryHashRecheck") {
            check(checkNotNull(resolver.openInputStream(target)).use(::hashGalleryStream) == verifiedSha) {
                "Gallery video does not match the verified output. It was not replaced; original clips are preserved."
            }
        }
        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(context, target, null)
            check(extractor.trackCount >= 2)
        } finally {
            extractor.release()
        }
    }

    override fun makeVisible(row: Long) {
        checkCancelled()
        ExportFaults.publicationPoint(ExportFaults.Mode.KILL_BEFORE_VISIBLE)
        check(resolver.update(uri(row), ContentValues().apply {
            put(MediaStore.Video.Media.IS_PENDING, 0)
        }, null, null) == 1) { "Cannot confirm Gallery publication. Verified video retained." }
        ExportFaults.publicationPoint(ExportFaults.Mode.KILL_AFTER_VISIBLE)
    }
}

internal fun hashGalleryStream(input: InputStream): String {
    val digest = MessageDigest.getInstance("SHA-256")
    val buffer = ByteArray(65536)
    while (true) {
        val n = input.read(buffer)
        if (n < 0) break
        digest.update(buffer, 0, n)
    }
    return digest.digest().joinToString("") { "%02x".format(it) }
}
