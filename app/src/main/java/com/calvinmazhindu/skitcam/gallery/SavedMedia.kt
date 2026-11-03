package com.calvinmazhindu.skitcam.gallery

/** Gallery presentation only; never changes a session, its playback order or media files. */
data class SavedMedia(val uri: String, val mimeType: String, val name: String, val addedAtSeconds: Long, val mediaId: Long = 0) {
    val video get() = mimeType.startsWith("video/")
}
enum class GalleryFilter { All, Videos, Photos }
object SavedMediaPolicy {
    fun visible(items: List<SavedMedia>, filter: GalleryFilter): List<SavedMedia> = items.asSequence()
        .filter { it.uri.startsWith("content://") && (it.mimeType.startsWith("video/") || it.mimeType.startsWith("image/")) }
        .filter { filter == GalleryFilter.All || (filter == GalleryFilter.Videos) == it.video }
        .distinctBy { it.uri }
        .sortedWith(compareByDescending<SavedMedia> { it.addedAtSeconds }.thenByDescending { it.mediaId }.thenBy { it.uri })
        .toList()
}
