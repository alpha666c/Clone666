package com.gameautopilot.app.vision

import org.junit.Assert.assertEquals
import org.junit.Test

class BoardReaderTest {

    @Test
    fun `classifies primary hues`() {
        assertEquals("red", BoardReader.nameColor(220, 30, 30))
        assertEquals("green", BoardReader.nameColor(30, 200, 40))
        assertEquals("blue", BoardReader.nameColor(30, 60, 220))
        assertEquals("yellow", BoardReader.nameColor(230, 220, 30))
        assertEquals("purple", BoardReader.nameColor(150, 40, 200))
    }

    @Test
    fun `classifies grayscale before hue`() {
        assertEquals("black", BoardReader.nameColor(10, 10, 10))
        assertEquals("white", BoardReader.nameColor(240, 245, 240))
        assertEquals("gray", BoardReader.nameColor(128, 130, 125))
    }
}
