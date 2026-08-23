package ru.rfidhunter.app.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class PhotoProcessorTest {
    @Test
    fun fullHdPortraitIsKeptAtThreeByFour() {
        assertEquals(PhotoProcessor.OutputSize(1080, 1440), PhotoProcessor.outputSize(1080, 1440))
    }

    @Test
    fun largerPhotoIsLimitedToFullHdPortrait() {
        assertEquals(PhotoProcessor.OutputSize(1080, 1440), PhotoProcessor.outputSize(2160, 2880))
    }

    @Test
    fun smallerPhotoIsNotUpscaled() {
        assertEquals(PhotoProcessor.OutputSize(600, 800), PhotoProcessor.outputSize(600, 800))
    }
}
