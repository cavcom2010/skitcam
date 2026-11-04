package com.calvinmazhindu.skitcam.project

import android.content.Intent
import android.net.Uri
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.platform.app.InstrumentationRegistry

/** Actual Android picker interactions; provider faults remain separately labelled simulations. */
object ProjectTestDocuments {
    private fun find(inst: android.app.Instrumentation,label: String): AccessibilityNodeInfo? {
        inst.uiAutomation.clearCache()
        fun walk(n: AccessibilityNodeInfo): AccessibilityNodeInfo? {
            if(n.isVisibleToUser && (n.text?.toString()==label || n.contentDescription?.toString()==label)) return n
            for(i in 0 until n.childCount) n.getChild(i)?.let { walk(it)?.let { found -> return found } };return null
        }
        return inst.uiAutomation.rootInActiveWindow?.let(::walk)
    }
    private fun click(inst: android.app.Instrumentation,label: String) {
        val end=System.nanoTime()+30_000_000_000L
        while(System.nanoTime()<end) {
            var node=find(inst,label)
            while(node?.isClickable!=true && node?.parent!=null) node=node?.parent
            if(node?.isEnabled==true && node.isClickable && node.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return
            Thread.sleep(150)
        }
        error("Picker control missing: $label")
    }
    fun pick(inst: android.app.Instrumentation,filename: String,simulated: Boolean): Uri {
        val testPackage=InstrumentationRegistry.getInstrumentation().context.packageName
        val tag=java.util.UUID.randomUUID().toString()
        inst.targetContext.startActivity(Intent().setClassName(testPackage,TestDocumentActivity::class.java.name).putExtra("filename",filename).putExtra("tag",tag).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        val end=System.nanoTime()+30_000_000_000L
        while(inst.uiAutomation.rootInActiveWindow?.packageName?.toString()?.contains("documentsui")!=true && System.nanoTime()<end) Thread.sleep(100)
        click(inst,"Show roots")
        click(inst,if(simulated) "Simulated M11 storage" else "Downloads")
        click(inst,"SAVE")
        val authority=testPackage+".project-docs"
        val deadline=System.nanoTime()+30_000_000_000L
        while(System.nanoTime()<deadline) {
            val result=runCatching { inst.targetContext.contentResolver.call(Uri.parse("content://$authority"),"test:stats",null,null) }.getOrNull()
            if(result?.getString("bridgeTag")==tag) return Uri.parse(result.getString("bridgeUri"))
            Thread.sleep(100)
        }
        error("Save picker result did not reach the test harness")
    }
}
