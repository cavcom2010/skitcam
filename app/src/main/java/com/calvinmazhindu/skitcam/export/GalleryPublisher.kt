package com.calvinmazhindu.skitcam.export

import android.Manifest
import android.content.*
import android.content.pm.PackageManager
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.*
import android.provider.MediaStore
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

internal fun publish(context: Context,id: String,file: File,jobId: String): Uri {
    val resolver=context.contentResolver
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
            check(sha(staging)==sha(file));check(staging.renameTo(target))
        }
        check(sha(target)==sha(file)) { "An existing gallery file does not match this job." }
        var uri: Uri?=null;val done=CountDownLatch(1)
        MediaScannerConnection.scanFile(context,arrayOf(target.path),arrayOf("video/mp4")) { _, found -> uri=found;done.countDown() }
        check(done.await(30,TimeUnit.SECONDS) && uri!=null) { "Gallery indexing failed. Verified video retained." }
        return uri!!
    }
    val collection=MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
    var uri: Uri?=null
    resolver.query(collection,arrayOf(MediaStore.Video.Media._ID),"${MediaStore.Video.Media.DESCRIPTION}=?",arrayOf(marker),null)?.use {
        check(it.count<=1) { "Multiple gallery entries need attention; no new copy created." }
        if(it.moveToFirst()) uri=ContentUris.withAppendedId(collection,it.getLong(0))
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
        ParcelFileDescriptor.AutoCloseOutputStream(descriptor).use { output -> file.inputStream().use { it.copyTo(output) };output.flush();output.fd.sync() }
    }
    check(hashUri()==sha(file)) { "Gallery copy verification failed. Original verified output retained." }
    val extractor=android.media.MediaExtractor()
    try { extractor.setDataSource(context,target,null);check(extractor.trackCount>=2) }
    finally { extractor.release() }
    if(pending) check(resolver.update(target,ContentValues().apply { put(MediaStore.Video.Media.IS_PENDING,0) },null,null)==1)
    resolver.openInputStream(target)!!.use { check(it.read()>=0) }
    return target
}
