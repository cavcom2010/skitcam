package com.calvinmazhindu.skitcam.export

import android.Manifest
import android.content.*
import android.content.pm.PackageManager
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.*
import android.provider.MediaStore
import androidx.core.net.toUri
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

internal fun publish(context: Context,id: String,file: File,jobId: String): Uri {
    val resolver=context.contentResolver
    val journal=ExportJournal.read(context,id)!!
    val verifiedSha=journal.getString("verifiedSha")
    require(verifiedSha.matches(Regex("[0-9a-f]{64}"))) { "Missing verified output digest" }
    val marker="SkitCam export job:$jobId"
    val created=ExportJournal.read(context,id)!!.getLong("createdAt")
    val stamp=java.text.SimpleDateFormat("yyyyMMdd_HHmmss",java.util.Locale.US).apply { timeZone=java.util.TimeZone.getTimeZone("UTC") }.format(java.util.Date(created))
    val name="SkitCam_${stamp}_${jobId}.mp4"
    if(Build.VERSION.SDK_INT<29) {
        check(context.checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE)==PackageManager.PERMISSION_GRANTED && context.checkSelfPermission(Manifest.permission.READ_EXTERNAL_STORAGE)==PackageManager.PERMISSION_GRANTED) { "Storage permission is needed to save to the gallery. Open Retry to grant it." }
        @Suppress("DEPRECATION") val directory=File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES),"SkitCam")
        check(directory.isDirectory||directory.mkdirs())
        val target=File(directory,name)
        if(!target.exists()) {
            val staging=File(directory,".$name.partial")
            file.inputStream().use { input -> staging.outputStream().use { input.copyTo(it);it.fd.sync() } }
            check(sha(staging)==verifiedSha);check(staging.renameTo(target))
        }
        check(sha(target)==verifiedSha) { "An existing gallery file does not match this job." }
        var uri: Uri?=null;val done=CountDownLatch(1)
        MediaScannerConnection.scanFile(context,arrayOf(target.path),arrayOf("video/mp4")) { _, found -> uri=found;done.countDown() }
        check(done.await(30,TimeUnit.SECONDS) && uri!=null) { "Gallery indexing failed. Verified video retained." }
        return uri!!
    }
    val collection=MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
    var uri: Uri?=null
    // A provider may replace DESCRIPTION when inspecting a failed pending copy.
    // The persisted URI is authoritative only after ownership and the unique job name
    // have been checked. Never insert a second row merely because its marker vanished.
    journal.optString("uri").takeIf { it.isNotBlank() }?.let { saved ->
        val recorded=saved.toUri()
        require(recorded.scheme=="content" && recorded.authority=="media" && recorded.pathSegments.take(3)==listOf("external_primary","video","media")) { "Invalid publication checkpoint URI" }
        resolver.query(recorded,arrayOf(MediaStore.Video.Media.DISPLAY_NAME,MediaStore.Video.Media.RELATIVE_PATH,
            MediaStore.MediaColumns.OWNER_PACKAGE_NAME),null,null,null)?.use { row ->
            if(row.moveToFirst()) {
                check(row.getString(0)==name && row.getString(1)=="Movies/SkitCam/" && row.getString(2)==context.packageName) { "Publication checkpoint does not belong to this job" }
                uri=recorded
            }
        }
    }
    resolver.query(collection,arrayOf(MediaStore.Video.Media._ID),"${MediaStore.Video.Media.DESCRIPTION}=?",arrayOf(marker),null)?.use {
        check(it.count<=1) { "Multiple gallery entries need attention; no new copy created." }
        if(it.moveToFirst()) {
            val marked=ContentUris.withAppendedId(collection,it.getLong(0))
            check(uri==null || uri==marked) { "Publication markers disagree; no new copy created" }
            uri=marked
        }
    }
    if(uri==null) uri=resolver.insert(collection,ContentValues().apply {
        put(MediaStore.Video.Media.DISPLAY_NAME,name);put(MediaStore.Video.Media.DESCRIPTION,marker)
        put(MediaStore.Video.Media.MIME_TYPE,"video/mp4");put(MediaStore.Video.Media.RELATIVE_PATH,"Movies/SkitCam")
        put(MediaStore.Video.Media.IS_PENDING,1)
    }) ?: error("Cannot create gallery entry. Verified video retained.")
    val target=uri!!
    ExportJournal.update(context,id,ExportState.Publishing,uri=target.toString())
    var pending=true
    resolver.query(target,arrayOf(MediaStore.Video.Media.IS_PENDING),null,null,null)?.use { if(it.moveToFirst()) pending=it.getInt(0)!=0 }
    fun hashUri(): String = resolver.openInputStream(target)!!.use { input ->
        val digest=MessageDigest.getInstance("SHA-256");val buffer=ByteArray(65536)
        while(true) { val n=input.read(buffer);if(n<0) break;digest.update(buffer,0,n) }
        digest.digest().joinToString("") { "%02x".format(it) }
    }
    if(pending) resolver.openFileDescriptor(target,"rwt")!!.use { descriptor ->
        ExportTrace.measure(id,"galleryCopyAndSync") { ParcelFileDescriptor.AutoCloseOutputStream(descriptor).use { output ->
            file.inputStream().use { ExportTrace.bytes(id,"galleryCopyBytes",it.copyTo(output)) };output.flush();output.fd.sync()
        } }
    }
    ExportTrace.measure(id,"galleryHashRecheck") {
        check(hashUri()==verifiedSha) { "Gallery copy verification failed. Original verified output retained." }
        ExportTrace.bytes(id,"galleryReadbackBytes",file.length())
    }
    val extractor=android.media.MediaExtractor()
    try { extractor.setDataSource(context,target,null);check(extractor.trackCount>=2) }
    finally { extractor.release() }
    if(pending) check(resolver.update(target,ContentValues().apply { put(MediaStore.Video.Media.IS_PENDING,0) },null,null)==1)
    resolver.openInputStream(target)!!.use { check(it.read()>=0) }
    return target
}
