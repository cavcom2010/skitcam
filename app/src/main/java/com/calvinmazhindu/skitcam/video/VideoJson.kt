package com.calvinmazhindu.skitcam.video

import org.json.JSONObject

object VideoJson {
    private fun long(json: JSONObject,key: String): Long {
        val value=json.get(key)
        require(value is Int || value is Long) { "$key must be an integer." }
        return (value as Number).toLong()
    }
    private fun integer(json: JSONObject,key: String): Int {
        val value=long(json,key)
        require(value in Int.MIN_VALUE..Int.MAX_VALUE) { "$key is out of range." }
        return value.toInt()
    }
    fun settings(value: VideoSettings) = JSONObject().put("quality",value.quality)
        .put("fps",value.fps ?: JSONObject.NULL).put("stabilization",value.stabilization).put("whiteBalance",value.whiteBalance)
    fun settings(json: JSONObject) = VideoSettings(json.getString("quality"),if(json.isNull("fps")) null else integer(json,"fps"),
        json.getBoolean("stabilization"),json.getString("whiteBalance")).also { require(it.valid()) { "Invalid capture settings." } }
    fun profile(value: CaptureProfile) = JSONObject().put("version",1).put("cameraId",value.cameraId)
        .put("settings",settings(value.settings)).put("width",value.width).put("height",value.height).put("legacy",value.legacy)
    fun profile(json: JSONObject): CaptureProfile {
        require(integer(json,"version")==1)
        return CaptureProfile(json.getString("cameraId"),settings(json.getJSONObject("settings")),integer(json,"width"),integer(json,"height"),json.getBoolean("legacy"))
            .also { require(it.valid()) { "Invalid session capture profile." } }
    }
    fun media(value: SegmentMediaFacts) = JSONObject().put("width",value.width).put("height",value.height).put("rotation",value.rotation)
        .put("videoMime",value.videoMime).put("audioMime",value.audioMime).put("audioRate",value.audioRate).put("audioChannels",value.audioChannels)
        .put("videoFrames",value.videoFrames).put("firstPtsUs",value.firstPtsUs).put("lastPtsUs",value.lastPtsUs)
        .put("minimumIntervalUs",value.minimumIntervalUs ?: JSONObject.NULL).put("maximumIntervalUs",value.maximumIntervalUs ?: JSONObject.NULL)
        .put("reordered",value.reordered).put("videoBytes",value.videoBytes).put("trackDurationUs",value.trackDurationUs)
    fun media(json: JSONObject) = SegmentMediaFacts(integer(json,"width"),integer(json,"height"),integer(json,"rotation"),
        json.getString("videoMime"),json.getString("audioMime"),integer(json,"audioRate"),integer(json,"audioChannels"),
        long(json,"videoFrames"),long(json,"firstPtsUs"),long(json,"lastPtsUs"),
        if(json.isNull("minimumIntervalUs")) null else long(json,"minimumIntervalUs"),
        if(json.isNull("maximumIntervalUs")) null else long(json,"maximumIntervalUs"),json.getBoolean("reordered"),
        long(json,"videoBytes"),long(json,"trackDurationUs")).also { require(it.valid()) { "Invalid source media facts." } }
}
