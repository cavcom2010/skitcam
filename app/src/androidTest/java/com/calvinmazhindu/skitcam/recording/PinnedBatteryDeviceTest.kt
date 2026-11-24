package com.calvinmazhindu.skitcam.recording

import android.app.Application
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

/** Exercises the isolated injection without opening a camera activity. */
@RunWith(AndroidJUnit4::class)
class PinnedBatteryDeviceTest {
    @Test fun platformReadingCannotReplacePinnedValue() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        check(context.packageName == "com.calvinmazhindu.skitcam.m15b1m15b31battery")
        instrumentation.runOnMainSync {
            val model = RecordingViewModel(context.applicationContext as Application)
            val live = BatteryReading(71.0, charging = true)
            try {
                model.pinValidationBattery(BatteryReading(5.0))
                model.updateBattery(live)
                assertEquals(BatteryReading(5.0), model.battery.value)
                model.pinValidationBattery(BatteryReading(80.0))
                assertEquals(BatteryReading(80.0), model.battery.value)
            } finally {
                model.pinValidationBattery(null)
            }
            assertEquals(live, model.battery.value)
        }
    }
}
