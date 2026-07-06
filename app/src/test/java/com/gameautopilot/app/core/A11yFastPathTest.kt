package com.gameautopilot.app.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class A11yFastPathTest {

    private fun mark(id: Int, label: String) =
        MarkBox(id = id, left = 0, top = 0, right = 10, bottom = 10, source = MarkSource.A11Y, label = label)

    private fun snapshot(marks: List<MarkBox>) = ScreenSnapshot(
        width = 1080, height = 1920, foregroundPackage = "com.example.game",
        ocrLines = emptyList(), a11yLines = emptyList(), marks = marks,
        screenshotBase64Jpeg = "", perceptualHash = 0L
    )

    @Test
    fun `no fast path before any brain dispatch has been recorded`() {
        val fastPath = A11yFastPath()
        assertNull(fastPath.tryFastPath(snapshot(listOf(mark(1, "Collect"))), deltaSincePrev = 0))
    }

    @Test
    fun `repeats the last tapped mark's label when it reappears with a small delta`() {
        val fastPath = A11yFastPath()
        fastPath.recordBrainDispatch(listOf(Action.TapMark(1)), listOf(mark(1, "Collect")))

        val next = snapshot(listOf(mark(7, "Collect")))
        assertEquals(listOf(Action.TapMark(7)), fastPath.tryFastPath(next, deltaSincePrev = 1))
    }

    @Test
    fun `does not fast-path when the screen delta is negative or too large`() {
        val fastPath = A11yFastPath()
        fastPath.recordBrainDispatch(listOf(Action.TapMark(1)), listOf(mark(1, "Collect")))
        val next = snapshot(listOf(mark(1, "Collect")))

        assertNull(fastPath.tryFastPath(next, deltaSincePrev = -1))
        assertNull(fastPath.tryFastPath(next, deltaSincePrev = 5))
    }

    @Test
    fun `does not fast-path when the remembered label is no longer on screen`() {
        val fastPath = A11yFastPath()
        fastPath.recordBrainDispatch(listOf(Action.TapMark(1)), listOf(mark(1, "Collect")))
        val next = snapshot(listOf(mark(1, "Something Else")))

        assertNull(fastPath.tryFastPath(next, deltaSincePrev = 0))
    }

    @Test
    fun `caps at maxConsecutive fast-path ticks before requiring the brain again`() {
        val fastPath = A11yFastPath(maxConsecutive = 2)
        fastPath.recordBrainDispatch(listOf(Action.TapMark(1)), listOf(mark(1, "Collect")))
        val next = snapshot(listOf(mark(1, "Collect")))

        assertEquals(listOf(Action.TapMark(1)), fastPath.tryFastPath(next, deltaSincePrev = 0))
        assertEquals(listOf(Action.TapMark(1)), fastPath.tryFastPath(next, deltaSincePrev = 0))
        assertNull(fastPath.tryFastPath(next, deltaSincePrev = 0))
    }

    @Test
    fun `reset clears remembered label and consecutive count`() {
        val fastPath = A11yFastPath()
        fastPath.recordBrainDispatch(listOf(Action.TapMark(1)), listOf(mark(1, "Collect")))
        fastPath.reset()

        assertNull(fastPath.tryFastPath(snapshot(listOf(mark(1, "Collect"))), deltaSincePrev = 0))
    }

    @Test
    fun `recording a non-tapMark brain dispatch forgets the previous label`() {
        val fastPath = A11yFastPath()
        fastPath.recordBrainDispatch(listOf(Action.TapMark(1)), listOf(mark(1, "Collect")))
        fastPath.recordBrainDispatch(listOf(Action.Back), listOf(mark(1, "Collect")))

        assertNull(fastPath.tryFastPath(snapshot(listOf(mark(1, "Collect"))), deltaSincePrev = 0))
    }
}
