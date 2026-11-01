package com.calvinmazhindu.skitcam.export

import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertTrue

/** Existing camera tests retain their original assertions and enter through New Recording. */
object ExportTestSupport {
    fun settle() {
        val end=System.nanoTime()+180_000_000_000L
        while(ExportJournal.busy && System.nanoTime()<end) Thread.sleep(100)
        assertTrue("Previous export did not settle: ${ExportJournal.status.value}",!ExportJournal.busy)
    }
    fun beforeCamera() {
        settle()
        if(ExportJournal.status.value==null) return
        val instrumentation=InstrumentationRegistry.getInstrumentation()
        fun find(n: AccessibilityNodeInfo): AccessibilityNodeInfo? {
            if(n.text?.toString()=="New Recording" && n.isVisibleToUser) return n
            for(i in 0 until n.childCount) n.getChild(i)?.let { find(it)?.let { found -> return found } }
            return null
        }
        val end=System.nanoTime()+10_000_000_000L
        var node: AccessibilityNodeInfo?=null
        while(node==null && System.nanoTime()<end) {
            node=instrumentation.uiAutomation.rootInActiveWindow?.let(::find)
            if(node==null) Thread.sleep(100)
        }
        // A previous export may belong to another session, so its screen need not be visible.
        node?.let {
            var button=it
            while(!button.isClickable && button.parent!=null) button=button.parent
            assertTrue(button.performAction(AccessibilityNodeInfo.ACTION_CLICK))
        }
    }
}
