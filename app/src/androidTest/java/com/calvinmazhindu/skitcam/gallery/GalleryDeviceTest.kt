package com.calvinmazhindu.skitcam.gallery

import android.view.accessibility.AccessibilityNodeInfo
import androidx.lifecycle.Lifecycle
import androidx.media3.common.Player
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import com.calvinmazhindu.skitcam.export.FinishedPlaybackActivity
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class GalleryDeviceTest {
    private val inst=InstrumentationRegistry.getInstrumentation()
    private fun await(label: String, condition: ()->Boolean) {
        val end=System.nanoTime()+45_000_000_000L
        while(!condition() && System.nanoTime()<end) Thread.sleep(100)
        assertTrue(label,condition())
    }
    private fun click(label: String) {
        fun find(n: AccessibilityNodeInfo): AccessibilityNodeInfo? {
            if(n.isVisibleToUser && (n.contentDescription?.toString()==label || n.text?.toString()==label)) return n
            for(i in 0 until n.childCount) n.getChild(i)?.let { find(it)?.let { found->return found } }
            return null
        }
        var node: AccessibilityNodeInfo?=null
        await(label) { inst.uiAutomation.clearCache();node=inst.uiAutomation.rootInActiveWindow?.let(::find);node!=null }
        while(node?.isClickable!=true && node?.parent!=null) node=node?.parent
        assertTrue(node!!.performAction(AccessibilityNodeInfo.ACTION_CLICK))
    }
    @Test fun olderVideoCanBeSelectedPlayedAndReturnedToBrowser() {
        val context=inst.targetContext
        check(context.packageName.endsWith(".m10validation"))
        val entries=SavedMediaStore(context).read()
        val videos=SavedMediaPolicy.visible(entries,GalleryFilter.Videos)
        assertTrue("Record at least two dedicated validation exports before running",videos.size>=2)
        val older=videos[1]
        assertNotEquals(videos[0].uri,older.uri)
        val scenario=ActivityScenario.launch(SavedMediaActivity::class.java)
        try {
            await("Older and latest gallery entries") {
                var ready=false
                scenario.onActivity { ready=it.currentItems.any { e->e.uri==older.uri } && it.currentItems.any { e->e.uri==videos[0].uri } }
                ready
            }
            click("Videos");click("Open video ${older.name}")
            var player: FinishedPlaybackActivity?=null
            await("Selected older video decoded") {
                var ready=false
                inst.runOnMainSync {
                    player=ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED).filterIsInstance<FinishedPlaybackActivity>().firstOrNull()
                    ready=player?.currentPlayer?.playbackState==Player.STATE_READY
                }
                ready
            }
            inst.runOnMainSync { assertEquals(older.uri,player!!.intent.data.toString());player!!.currentPlayer!!.play() }
            Thread.sleep(500)
            inst.runOnMainSync { assertTrue(player!!.currentPlayer!!.currentPosition>100) }
            click("Back to gallery")
            await("Gallery restored") {
                var ready=false
                scenario.onActivity { ready=it.lifecycle.currentState==Lifecycle.State.RESUMED && it.currentItems.any { e->e.uri==older.uri } }
                ready
            }
            scenario.recreate()
            await("All saved videos survive recreation") {
                var count=0;scenario.onActivity { count=it.currentItems.count { e->e.video } };count==videos.size
            }
        } finally { scenario.close() }
    }
}
