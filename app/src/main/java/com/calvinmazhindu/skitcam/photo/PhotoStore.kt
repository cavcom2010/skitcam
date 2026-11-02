package com.calvinmazhindu.skitcam.photo

import android.Manifest
import android.content.*
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
import androidx.exifinterface.media.ExifInterface
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.*
import android.provider.MediaStore
import android.util.AtomicFile
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

data class PhotoFacts(val width: Int, val height: Int, val orientation: Int)

/** Owns only new photo jobs. Never opens session files for writing. All methods run on IO. */
class PhotoStore(private val context: Context, private val freeBytes: () -> Long = { context.filesDir.usableSpace }) {
    private val root = File(context.filesDir, "photos")
    fun create(): File {
        check(PhotoPolicy.canCapture(freeBytes())) { "Low storage: 100 MiB free is required before taking a photo." }
        val dir = File(root, UUID.randomUUID().toString())
        check(dir.mkdirs()) { "Cannot prepare photo storage." }
        write(dir, JSONObject().put("version",1).put("id",dir.name).put("state","Capturing")
            .put("createdAt",System.currentTimeMillis()))
        return dir
    }
    fun read(dir: File): JSONObject = AtomicFile(File(dir,"capture.json")).openRead().bufferedReader().use { JSONObject(it.readText()) }
    fun write(dir: File, job: JSONObject) {
        val atomic = AtomicFile(File(dir,"capture.json")); val output = atomic.startWrite()
        try { output.write(job.toString(2).toByteArray()); atomic.finishWrite(output) }
        catch(e: Exception) { atomic.failWrite(output); throw e }
    }
    fun captured(dir: File) { write(dir, read(dir).put("state","Validating")) }
    fun validate(file: File): PhotoFacts {
        check(file.isFile && file.length() > 4) { "Photo is empty or unreadable." }
        file.inputStream().use { check(it.read() == 0xff && it.read() == 0xd8) { "Camera output is not JPEG." } }
        java.io.RandomAccessFile(file,"r").use { input ->
            input.seek(file.length()-2)
            check(input.readUnsignedShort() == 0xffd9) { "JPEG capture is incomplete. Original bytes retained." }
        }
        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.path, options)
        check(options.outWidth > 0 && options.outHeight > 0 && options.outMimeType == "image/jpeg") { "Photo dimensions unavailable." }
        // Undefined/missing EXIF means display the stored pixels without a transform.
        val rawOrientation = ExifInterface(file.path).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_UNDEFINED)
        val orientation = if(rawOrientation == ExifInterface.ORIENTATION_UNDEFINED) ExifInterface.ORIENTATION_NORMAL else rawOrientation
        check(orientation in 1..8) { "Photo orientation metadata is invalid." }
        var sample = 1
        while (maxOf(options.outWidth, options.outHeight) / sample > 2048) sample *= 2
        val bitmap = BitmapFactory.decodeFile(file.path, BitmapFactory.Options().apply { inSampleSize = sample })
            ?: error("Photo cannot be decoded.")
        bitmap.recycle()
        return PhotoFacts(options.outWidth, options.outHeight, orientation)
    }
    fun publish(dir: File): Pair<Uri, PhotoFacts> {
        val job = read(dir)
        if(job.getString("state") == "Completed") {
            val uri = Uri.parse(job.getString("uri"))
            check(hash { context.contentResolver.openInputStream(uri)!! } == job.getString("sha256")) { "Published photo is missing or changed." }
            return uri to PhotoFacts(job.getInt("width"),job.getInt("height"),job.getInt("orientation"))
        }
        val source = File(dir, "source.jpg")
        val facts = validate(source)
        val hash = hash { source.inputStream() }
        check(PhotoPolicy.canPublish(freeBytes(),source.length())) { "Low storage: photo retained privately. Free space and retry Save." }
        val name = "SkitCam_${java.text.SimpleDateFormat("yyyyMMdd_HHmmss",java.util.Locale.US).format(java.util.Date(job.getLong("createdAt")))}_${dir.name}.jpg"
        val resolver = context.contentResolver
        val uri: Uri
        if (Build.VERSION.SDK_INT >= 29) {
            val marker = "SkitCam photo job:${dir.name}"
            val collection = MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
            var existing: Uri? = null
            resolver.query(collection,arrayOf(MediaStore.Images.Media._ID),"${MediaStore.Images.Media.DESCRIPTION}=?",arrayOf(marker),null)?.use {
                check(it.count <= 1) { "Duplicate photo entries need attention. Photo retained." }
                if(it.moveToFirst()) existing = ContentUris.withAppendedId(collection,it.getLong(0))
            }
            uri = existing ?: resolver.insert(collection, ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME,name); put(MediaStore.Images.Media.MIME_TYPE,"image/jpeg")
                put(MediaStore.Images.Media.DESCRIPTION,marker); put(MediaStore.Images.Media.RELATIVE_PATH,"Pictures/SkitCam")
                put(MediaStore.Images.Media.IS_PENDING,1)
            }) ?: error("Gallery unavailable. Photo retained privately.")
            write(dir,job.put("state","Publishing").put("uri",uri.toString()))
            var pending = true
            resolver.query(uri,arrayOf(MediaStore.Images.Media.IS_PENDING),null,null,null)?.use { if(it.moveToFirst()) pending = it.getInt(0) != 0 }
            try {
                if (pending) resolver.openFileDescriptor(uri,"rwt")!!.use { descriptor ->
                    ParcelFileDescriptor.AutoCloseOutputStream(descriptor).use { output ->
                        source.inputStream().use { it.copyTo(output) }; output.flush(); output.fd.sync()
                    }
                }
                check(hash == hash { resolver.openInputStream(uri)!! }) { "Gallery copy does not match the photo." }
                resolver.openInputStream(uri)!!.use { input ->
                    val bitmap = BitmapFactory.decodeStream(input,null,BitmapFactory.Options().apply { inSampleSize = 16 })
                        ?: error("Gallery photo cannot be decoded.")
                    bitmap.recycle()
                }
                if(pending) check(resolver.update(uri,ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING,0) },null,null) == 1)
            } catch(e: Exception) {
                if(pending) runCatching { resolver.delete(uri,null,null) }
                throw e
            }
        } else {
            check(context.checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED) { "Storage permission is required to save this photo. Photo retained privately." }
            @Suppress("DEPRECATION") val directory = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES),"SkitCam")
            check(directory.isDirectory || directory.mkdirs())
            val target = File(directory,name)
            if (!target.exists()) {
                val staging = File(directory,".$name.partial")
                source.inputStream().use { input -> staging.outputStream().use { input.copyTo(it); it.fd.sync() } }
                check(hash == hash { staging.inputStream() }); check(staging.renameTo(target))
            }
            check(hash == hash { target.inputStream() })
            var found: Uri? = null; val done = CountDownLatch(1)
            MediaScannerConnection.scanFile(context,arrayOf(target.path),arrayOf("image/jpeg")) { _, value -> found = value; done.countDown() }
            check(done.await(30, TimeUnit.SECONDS) && found != null) { "Gallery indexing failed. Photo retained." }
            uri = found
        }
        write(dir,job.put("state","Completed").put("uri",uri.toString()).put("sha256",hash)
            .put("width",facts.width).put("height",facts.height).put("orientation",facts.orientation))
        // Only this new job's verified, byte-identical staging copy is temporary.
        source.delete()
        return uri to facts
    }
    fun recover(): File? {
        var retry: File? = null
        root.listFiles().orEmpty().filter { it.isDirectory }.sortedBy { it.lastModified() }.forEach { dir ->
            runCatching {
                val job = read(dir)
                check(job.getInt("version") == 1 && job.getString("id") == dir.name)
                when(job.getString("state")) {
                    "Capturing" -> write(dir,job.put("state","Interrupted")) // Never infer successful capture from a partial file.
                    "Validating", "Publishing" -> retry = dir
                }
            }
        }
        return retry
    }
    fun latestPublished(): Pair<Uri,Long>? = root.listFiles().orEmpty().filter { it.isDirectory }.mapNotNull { dir ->
        runCatching { read(dir).takeIf { it.getString("state") == "Completed" } }.getOrNull()
    }.maxByOrNull { it.getLong("createdAt") }?.let { Uri.parse(it.getString("uri")) to it.getLong("createdAt") }
    private fun hash(open: () -> java.io.InputStream): String = open().use { input ->
        val digest = MessageDigest.getInstance("SHA-256"); val buffer = ByteArray(65536)
        while(true) { val size = input.read(buffer); if(size < 0) break; digest.update(buffer,0,size) }
        digest.digest().joinToString("") { "%02x".format(it) }
    }
}
