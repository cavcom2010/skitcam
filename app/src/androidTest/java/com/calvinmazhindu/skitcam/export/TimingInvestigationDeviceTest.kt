package com.calvinmazhindu.skitcam.export

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.calvinmazhindu.skitcam.trim.TrimRange
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

/** Bounded, private re-rendering only. Never requests an export job or publishes to MediaStore. */
@RunWith(AndroidJUnit4::class)
class TimingInvestigationDeviceTest {
    @get:org.junit.Rule val timeout=org.junit.rules.Timeout.seconds(180)
    @Test fun copiedFixturesThreeTimesEach() {
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val root=File(context.filesDir,"validation-m71/inputs")
        org.junit.Assume.assumeTrue("Upload dedicated copies before running",root.isDirectory)
        assertFalse("Do not interfere with active exports",ExportJournal.busy)
        ExportJournal.cancelRequested=false
        val result=File(context.filesDir,"validation-m71/run-${UUID.randomUUID()}").apply { mkdirs() }
        val args=InstrumentationRegistry.getArguments()
        val kinds=args.getString("kinds")?.split(",") ?: listOf("failed","passed")
        require(kinds.all { it in setOf("failed","passed","zero","offset","trimmed") })
        val variant=args.getString("variant") ?: "default"
        require(variant in setOf("default","no-clip","no-processors","leading-gap","reference"))
        for(kind in kinds) {
            val job=JSONObject(File(root,"$kind/export/job.json").readText())
            val array=job.getJSONArray("spec")
            val spec=ExportSpec(kind,0,List(array.length()) { i ->
                val entry=array.getJSONObject(i)
                val filename=entry.optString("path").substringAfterLast('/').takeIf { it.matches(Regex("segment_[0-9]+\\.mp4")) }
                    ?: "segment_%03d.mp4".format(i+1)
                ExportClip(entry.getString("id"),File(root,"$kind/$filename").path,
                    TrimRange(entry.getLong("duration"),entry.getLong("start"),entry.getLong("end")))
            })
            val before=spec.clips.map { sha(File(it.path)) }
            repeat(3) { index ->
                val output=File(result,"$kind-$index.mp4")
                val report=JSONObject().put("fixture",kind).put("attempt",index).put("variant",variant)
                try {
                    if(variant=="default") transform(context,spec,output)
                    else probeTransform(context,spec,output,variant)
                    verify(output,spec.durationMs) { report.put("timing",it) }
                    report.put("accepted",true)
                } catch(e:Exception) { report.put("accepted",false).put("error",e.toString()).put("stack",e.stackTraceToString()) }
                File(result,"$kind-$index.json").writeText(report.toString(2))
                assertTrue("Transform must produce a diagnostic artifact",output.isFile)
                assertTrue("Verification must reach timing analysis",report.has("timing"))
                assertEquals(before,spec.clips.map { sha(File(it.path)) })
                if(variant in setOf("default","leading-gap")) assertTrue("Corrected fixture rejected: $report",report.getBoolean("accepted"))
                if(variant=="reference" && kind in setOf("failed","offset")) assertFalse("Known regression must reproduce in frozen reference",report.getBoolean("accepted"))
            }
        }
    }
}
