package com.calvinmazhindu.skitcam.ui.camera

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import com.calvinmazhindu.skitcam.ui.theme.SkitCamColors
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.pow

class UiContrastTest {
    private fun luminance(colour: Color): Double {
        fun linear(component: Float): Double = if (component <= .04045f) component / 12.92
            else ((component + .055) / 1.055).pow(2.4)
        return .2126 * linear(colour.red) + .7152 * linear(colour.green) + .0722 * linear(colour.blue)
    }
    private fun readable(foreground: Color, background: Color) {
        val a = luminance(foreground); val b = luminance(background)
        val ratio = (maxOf(a, b) + .05) / (minOf(a, b) + .05)
        assertTrue("Text contrast $ratio must be at least 4.5:1", ratio >= 4.5)
    }
    @Test fun actionableAndStatusTextHasAdequateContrast() {
        readable(Color.White, SkitCamColors.RoseStrong)
        readable(SkitCamColors.Ink, SkitCamColors.Rose)
        readable(SkitCamColors.TextSecondary, SkitCamColors.Surface)
        readable(SkitCamColors.Success, SkitCamColors.SurfaceRaised)
        readable(SkitCamColors.Warning, SkitCamColors.SurfaceRaised)
        readable(SkitCamColors.Warm, SkitCamColors.Surface)
    }
    @Test fun cameraStatusScrimsRemainReadableOverWhitePreview() {
        readable(SkitCamColors.TextPrimary, SkitCamColors.Ink.copy(alpha = .85f).compositeOver(Color.White))
        readable(SkitCamColors.Warning, SkitCamColors.Ink.copy(alpha = .9f).compositeOver(Color.White))
    }
}
