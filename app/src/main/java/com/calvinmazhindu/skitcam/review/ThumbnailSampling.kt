package com.calvinmazhindu.skitcam.review

/**
 * Ordered source times to try for a recognisable still inside the retained range.
 *
 * Camera recordings commonly start with a dark first frame and use long keyframe
 * intervals, so a single early sample can resolve to that opening frame. These
 * candidates start where a person would look and spread through the clip.
 */
internal fun thumbnailSampleTimes(startMs: Long, endMs: Long): List<Long> {
    val start = startMs.coerceAtLeast(0L)
    val end = (endMs - 1L).coerceAtLeast(start)
    val span = end - start
    return listOf(
        start + minOf(500L, span / 2),
        start + span / 2,
        start + span * 3 / 4,
        start,
        end
    ).map { it.coerceIn(start, end) }.distinct()
}

/** True when the sampled pixels average close to black, so a still would look empty. */
internal fun isDarkImage(luma: IntArray, darkBelow: Int = 16): Boolean =
    luma.isEmpty() || luma.average() < darkBelow
