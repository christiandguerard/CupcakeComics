package com.cupcakecomics.reader

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.cupcakecomics.reader.gl.NativeImageBridge
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class NativeImageBridgeInstrumentedTest {
    @Test
    fun lanczosKernelHasPeakAtCenter() {
        val appContext = InstrumentationRegistry.getInstrumentation().targetContext
        assertEquals("com.cupcakecomics.app.debug", appContext.packageName)
        val k = NativeImageBridge.lanczosKernel(64)
        assertEquals(64, k.size)
        assertTrue(k[32] > 0.9f)
        assertTrue(k[0] <= 0.01f)
    }
}
