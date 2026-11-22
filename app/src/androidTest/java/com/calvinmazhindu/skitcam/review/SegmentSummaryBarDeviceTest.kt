package com.calvinmazhindu.skitcam.review

import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.calvinmazhindu.skitcam.MainActivity
import com.calvinmazhindu.skitcam.ui.theme.SkitCamColors
import com.calvinmazhindu.skitcam.ui.theme.SkitCamTheme
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SegmentSummaryBarDeviceTest {
    @Test fun narrowLargeTextPreservesOnePercentAndFullAccessibilitySummary() {
        val instrument = InstrumentationRegistry.getInstrumentation()
        check(instrument.targetContext.packageName in setOf("com.calvinmazhindu.skitcam.m142validation",
            "com.calvinmazhindu.skitcam.m15avalidation"))
        val summary = mutableStateOf(SegmentSummary(99, 1))
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                activity.setContent {
                    SkitCamTheme {
                        CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density, 2f)) {
                            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                SegmentSummaryBar(summary.value, Modifier.width(128.dp))
                            }
                        }
                    }
                }
            }
            val green = SkitCamColors.Success.toArgb()
            val amber = SkitCamColors.Warning.toArgb()
            var initialBounds: Rect? = null
            for ((counts, description, left, right) in listOf(
                Check(SegmentSummary(99, 1), "99 kept, 1 discarded", green, amber),
                Check(SegmentSummary(1, 99), "1 kept, 99 discarded", green, amber),
                Check(SegmentSummary(0, 10), "0 kept, 10 discarded", amber, amber),
                Check(SegmentSummary(0, 0), "No recorded segments", null, null)
            )) {
                scenario.onActivity { summary.value = counts }
                val automation = instrument.uiAutomation
                fun find(node: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
                    if (node?.contentDescription?.toString() == description) return node
                    if (node != null) for (i in 0 until node.childCount) find(node.getChild(i))?.let { return it }
                    return null
                }
                var node: AccessibilityNodeInfo? = null
                repeat(50) {
                    if (node == null) {
                        instrument.waitForIdleSync()
                        if (android.os.Build.VERSION.SDK_INT >= 33) automation.clearCache()
                        node = find(automation.rootInActiveWindow)
                        if (node == null) Thread.sleep(50)
                    }
                }
                val accessible = requireNotNull(node) { description }
                assertFalse(accessible.isClickable)
                assertFalse(accessible.isScrollable)
                val bounds = Rect().also(accessible::getBoundsInScreen)
                if (initialBounds == null) initialBounds = bounds
                assertEquals(initialBounds, bounds)
                if (left != null && right != null) {
                    val split = bounds.left + (bounds.width() * counts.keptFraction).toInt()
                    val pixels = if (left == right) mapOf(bounds.left + 1 to left, bounds.right - 2 to right)
                    else mapOf(bounds.left + 1 to left, bounds.right - 2 to right, split - 1 to left, split + 1 to right)
                    var actual = emptyMap<Int, Int>()
                    for (frame in 0 until 20) {
                        val image = requireNotNull(automation.takeScreenshot())
                        try {
                            if (bounds.right <= image.width && bounds.bottom <= image.height) {
                                actual = pixels.mapValues { (x, _) -> image.getPixel(x, bounds.centerY()) }
                            }
                        } finally {
                            image.recycle()
                        }
                        if (actual == pixels) break
                        Thread.sleep(50)
                    }
                    assertEquals(description, pixels, actual)
                }
            }
        }
    }

    private data class Check(val counts: SegmentSummary, val description: String, val left: Int?, val right: Int?)
}
