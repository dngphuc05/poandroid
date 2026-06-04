package com.pocketmocap.app.camera

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class ArCoreFrameConversionTest {
    @Test
    fun yuvConversionUsesNativeFastPathBeforeKotlinFallback() {
        val source = File("src/main/java/com/pocketmocap/app/camera/ArCoreFrameCapture.kt").readText()

        assertTrue(source.contains("nativeYuv420ToArgb("))
        assertTrue(source.contains("yuv420ImageToBitmapFallback("))
        assertTrue(source.indexOf("nativeYuv420ToArgb(") < source.indexOf("yuv420ImageToBitmapFallback("))
    }

    @Test
    fun yuvPixelToArgbMapsNeutralBlackWithoutJpegRoundTrip() {
        val argb = yuvPixelToArgb(yValue = 16, uValue = 128, vValue = 128)

        assertEquals(0xff000000.toInt(), argb)
    }

    @Test
    fun yuvPixelToArgbMapsNeutralWhiteWithoutJpegRoundTrip() {
        val argb = yuvPixelToArgb(yValue = 235, uValue = 128, vValue = 128)

        assertEquals(0xffffffff.toInt(), argb)
    }

    @Test
    fun yuvPixelToArgbKeepsPrimaryRedCloseToExpectedRgb() {
        val argb = yuvPixelToArgb(yValue = 81, uValue = 90, vValue = 240)
        val red = (argb shr 16) and 0xff
        val green = (argb shr 8) and 0xff
        val blue = argb and 0xff

        assertTrue(red >= 240)
        assertTrue(green <= 20)
        assertTrue(blue <= 20)
    }
}
