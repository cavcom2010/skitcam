package com.calvinmazhindu.skitcam.export

import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class ExportVerificationDeviceTest {
    @Test fun unchangedFullDecodeChecksOnTheSameBaselineBytes() {
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        check(context.packageName.endsWith(".m12cvalidation") || context.packageName.endsWith(".m12bvalidation"))
        check(!ExportJournal.busy)
        ExportJournal.cancelRequested=false
        val reports=JSONArray()
        for(kind in listOf("single","five")) {
            val result=JSONObject(File(context.filesDir,"validation-m12b/baseline-$kind/$kind-0.json").readText())
            val job=result.getJSONObject("job");val original=job.getJSONObject("verification")
            val output=File(context.filesDir,"sessions/${result.getString("sessionId")}/export/output.mp4")
            repeat(3) {
                val start=SystemClock.elapsedRealtimeNanos()
                val fresh=verify(output,original.getLong("durationMs"))
                assertEquals(original.getString("sha256"),fresh.getString("sha256"))
                assertEquals(original.getLong("durationMs"),fresh.getLong("durationMs"))
                val before=original.getJSONObject("timing").getJSONArray("tracks")
                val after=fresh.getJSONObject("timing").getJSONArray("tracks")
                for(index in 0 until before.length()) for(key in listOf("firstPtsUs","lastPtsUs","decodedBuffers","decodedPcmFrames","decodedPcmEndExclusiveUs")) {
                    val old=before.getJSONObject(index).get(key);val current=after.getJSONObject(index).get(key)
                    if(old is Number && current is Number) assertEquals("$kind track $index $key",old.toLong(),current.toLong())
                    else assertEquals("$kind track $index $key",old,current)
                }
                reports.put(JSONObject().put("case",kind).put("ms",(SystemClock.elapsedRealtimeNanos()-start)/1_000_000.0))
            }
        }
        val file=File(context.filesDir,"validation-m12b-checks/verifier-batching.json")
        file.parentFile!!.mkdirs();file.writeText(reports.toString(2))
    }
}
