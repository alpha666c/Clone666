package com.gameautopilot.app.util

import org.junit.Assert.assertEquals
import org.junit.Test

class PerceptualHashTest {

    @Test
    fun `identical hashes have zero hamming distance`() {
        assertEquals(0, PerceptualHash.hamming(0x1234567890ABCDEFL, 0x1234567890ABCDEFL))
    }

    @Test
    fun `single differing bit yields distance one`() {
        assertEquals(1, PerceptualHash.hamming(0L, 1L))
        assertEquals(1, PerceptualHash.hamming(0b1000L, 0b0000L))
    }

    @Test
    fun `distance counts all differing bits regardless of order`() {
        val a = 0b1111_0000L
        val b = 0b0000_1111L
        assertEquals(8, PerceptualHash.hamming(a, b))
        assertEquals(PerceptualHash.hamming(a, b), PerceptualHash.hamming(b, a))
    }

    @Test
    fun `all bits set differs from zero by 64`() {
        assertEquals(64, PerceptualHash.hamming(-1L, 0L))
    }
}
