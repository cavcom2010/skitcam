package com.calvinmazhindu.skitcam.export

import android.content.Intent
import android.net.Uri
import android.graphics.Bitmap
import androidx.media3.common.Player
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class FinishedPlaybackDeviceTest {
    @Test fun publishedVideoPreparesPlaysAndReleases() {
        val instrumentation=InstrumentationRegistry.getInstrumentation()
        val context=instrumentation.targetContext
        check(context.packageName != "com.calvinmazhindu.skitcam") { "Use an isolated validation app." }
        val uri=InstrumentationRegistry.getArguments().getString("videoUri")
        org.junit.Assume.assumeNotNull(uri)
        val scenario=ActivityScenario.launch<FinishedPlaybackActivity>(Intent(context,FinishedPlaybackActivity::class.java).setData(Uri.parse(uri)))
        try {
            var ready=false
            val deadline=System.nanoTime()+20_000_000_000L
            while(!ready && System.nanoTime()<deadline) {
                scenario.onActivity { ready=it.currentPlayer?.playbackState==Player.STATE_READY }
                Thread.sleep(100)
            }
            assertTrue("Published video must prepare",ready)
            scenario.onActivity { it.currentPlayer!!.play() }
            Thread.sleep(700)
            scenario.onActivity { assertTrue("Published video position must advance",it.currentPlayer!!.currentPosition>=200);assertNull(it.currentPlayer!!.playerError) }
            if (context.packageName == "com.calvinmazhindu.skitcam.m13validation") {
                val visibleDeadline=System.nanoTime()+10_000_000_000L
                while (instrumentation.uiAutomation.rootInActiveWindow?.packageName?.toString()!=context.packageName &&
                    System.nanoTime()<visibleDeadline) {
                    if(android.os.Build.VERSION.SDK_INT>=33) instrumentation.uiAutomation.clearCache()
                    Thread.sleep(100)
                }
                assertEquals(context.packageName, instrumentation.uiAutomation.rootInActiveWindow?.packageName?.toString())
                val bitmap=instrumentation.uiAutomation.takeScreenshot()!!
                val file=java.io.File(context.filesDir,"validation-m13/finished-playback.png")
                file.parentFile!!.mkdirs()
                file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG,100,it) }
                bitmap.recycle()
            }
            scenario.moveToState(androidx.lifecycle.Lifecycle.State.CREATED)
            scenario.onActivity { assertNull("Player must release on Stop",it.currentPlayer) }
            scenario.moveToState(androidx.lifecycle.Lifecycle.State.RESUMED)
        } finally { scenario.close() }
    }
}
