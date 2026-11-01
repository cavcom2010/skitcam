package com.calvinmazhindu.skitcam.export

import android.content.Intent
import android.net.Uri
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
            scenario.moveToState(androidx.lifecycle.Lifecycle.State.CREATED)
            scenario.onActivity { assertNull("Player must release on Stop",it.currentPlayer) }
            scenario.moveToState(androidx.lifecycle.Lifecycle.State.RESUMED)
        } finally { scenario.close() }
    }
}
