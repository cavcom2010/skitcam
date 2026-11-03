package com.calvinmazhindu.skitcam.gallery

import org.junit.Assert.*
import org.junit.Test

class SavedMediaPolicyTest {
    private val old=SavedMedia("content://media/video/10","video/mp4","Old video",10,10)
    private val latest=SavedMedia("content://media/video/20","video/mp4","Latest video",20,20)
    private val photo=SavedMedia("content://media/images/30","image/jpeg","Photo",30,30)
    @Test fun olderVideosRemainBrowsableAlongsideLatest() {
        assertEquals(listOf(latest,old),SavedMediaPolicy.visible(listOf(old,latest,photo),GalleryFilter.Videos))
    }
    @Test fun allAndPhotoFiltersKeepCorrectMedia() {
        assertEquals(listOf(photo,latest,old),SavedMediaPolicy.visible(listOf(old,latest,photo),GalleryFilter.All))
        assertEquals(listOf(photo),SavedMediaPolicy.visible(listOf(old,latest,photo),GalleryFilter.Photos))
    }
    @Test fun stableOrderingUsesNumericIdsForSameSecond() {
        val a=old.copy(uri="content://media/video/99",mediaId=99)
        val b=old.copy(uri="content://media/video/100",mediaId=100)
        assertEquals(listOf(b,a),SavedMediaPolicy.visible(listOf(a,b),GalleryFilter.All))
    }
    @Test fun duplicatesAndUnplayableSchemesDoNotAddRows() {
        val input=listOf(old,old,latest.copy(uri="file:///private.mp4"),photo.copy(mimeType="text/plain"))
        assertEquals(listOf(old),SavedMediaPolicy.visible(input,GalleryFilter.All))
    }
    @Test fun browsingDoesNotChangeInputOrRecordingChronology() {
        val input=listOf(old,latest,photo);val before=input.toList()
        for(filter in GalleryFilter.entries) SavedMediaPolicy.visible(input,filter)
        assertEquals(before,input)
    }
    @Test fun emptyCollectionsHaveNoFakeLatestEntry() {
        for(filter in GalleryFilter.entries) assertTrue(SavedMediaPolicy.visible(emptyList(),filter).isEmpty())
    }
}
