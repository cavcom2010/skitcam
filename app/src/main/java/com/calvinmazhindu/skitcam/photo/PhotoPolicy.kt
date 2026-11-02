package com.calvinmazhindu.skitcam.photo

/** Conservative free-space preflights, without overflowing when adding a large source size. */
object PhotoPolicy {
    const val CAPTURE_RESERVE = 100L * 1024 * 1024
    const val PUBLISH_RESERVE = 50L * 1024 * 1024
    fun canCapture(freeBytes: Long) = freeBytes >= CAPTURE_RESERVE
    fun canPublish(freeBytes: Long, sourceBytes: Long) =
        sourceBytes > 0 && freeBytes >= PUBLISH_RESERVE && sourceBytes <= freeBytes - PUBLISH_RESERVE
}
