package com.calvinmazhindu.skitcam.gallery

import android.content.ContentUris
import android.content.Context
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import java.io.File

/** Read-only MediaStore query. Scoped storage limits modern results to accessible media. */
class SavedMediaStore(private val context: Context) {
    fun read(): List<SavedMedia> {
        val columns = mutableListOf(MediaStore.MediaColumns._ID, MediaStore.MediaColumns.DISPLAY_NAME,
            MediaStore.MediaColumns.MIME_TYPE, MediaStore.MediaColumns.DATE_ADDED,
            MediaStore.Files.FileColumns.MEDIA_TYPE)
        val where: String
        val arguments: Array<String>
        if (Build.VERSION.SDK_INT >= 29) {
            columns += MediaStore.MediaColumns.VOLUME_NAME
            where = "${MediaStore.MediaColumns.RELATIVE_PATH} IN (?,?) AND ${MediaStore.MediaColumns.IS_PENDING}=0"
            arguments = arrayOf("Movies/SkitCam/", "Pictures/SkitCam/")
        } else {
            @Suppress("DEPRECATION")
            val movies = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES), "SkitCam").path
            @Suppress("DEPRECATION")
            val photos = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES), "SkitCam").path
            @Suppress("DEPRECATION")
            where = "(${MediaStore.MediaColumns.DATA} LIKE ? OR ${MediaStore.MediaColumns.DATA} LIKE ?)"
            arguments = arrayOf("$movies/%", "$photos/%")
        }
        val selection = "($where) AND ${MediaStore.Files.FileColumns.MEDIA_TYPE} IN (1,3) AND ${MediaStore.MediaColumns.SIZE}>0"
        val result = mutableListOf<SavedMedia>()
        val cursor = context.contentResolver.query(MediaStore.Files.getContentUri("external"), columns.toTypedArray(),
            selection, arguments, "${MediaStore.MediaColumns.DATE_ADDED} DESC, ${MediaStore.MediaColumns._ID} DESC")
            ?: error("Gallery is unavailable. Try Refresh.")
        cursor.use {
            val id = it.getColumnIndexOrThrow(MediaStore.MediaColumns._ID)
            val name = it.getColumnIndexOrThrow(MediaStore.MediaColumns.DISPLAY_NAME)
            val mime = it.getColumnIndexOrThrow(MediaStore.MediaColumns.MIME_TYPE)
            val date = it.getColumnIndexOrThrow(MediaStore.MediaColumns.DATE_ADDED)
            val kind = it.getColumnIndexOrThrow(MediaStore.Files.FileColumns.MEDIA_TYPE)
            val volume = if(Build.VERSION.SDK_INT>=29) it.getColumnIndexOrThrow(MediaStore.MediaColumns.VOLUME_NAME) else -1
            while (it.moveToNext()) {
                val video = it.getInt(kind) == MediaStore.Files.FileColumns.MEDIA_TYPE_VIDEO
                val collection = if(Build.VERSION.SDK_INT>=29) {
                    val storage = it.getString(volume) ?: MediaStore.VOLUME_EXTERNAL_PRIMARY
                    if(video) MediaStore.Video.Media.getContentUri(storage) else MediaStore.Images.Media.getContentUri(storage)
                } else if(video) MediaStore.Video.Media.EXTERNAL_CONTENT_URI else MediaStore.Images.Media.EXTERNAL_CONTENT_URI
                val uri = ContentUris.withAppendedId(collection, it.getLong(id)).toString()
                result += SavedMedia(uri, it.getString(mime) ?: if(video) "video/mp4" else "image/jpeg",
                    it.getString(name) ?: if(video) "Video" else "Photo", it.getLong(date).coerceAtLeast(0), it.getLong(id))
            }
        }
        return SavedMediaPolicy.visible(result, GalleryFilter.All)
    }
}
