package com.gameautopilot.app.core

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ActionTest {

    private fun json(s: String) = JSONObject(s)

    @Test
    fun `parses tap with coordinates`() {
        val action = Action.fromJson(json("""{"type":"tap","x":10,"y":20}"""))
        assertEquals(Action.Tap(10, 20), action)
    }

    @Test
    fun `tap missing coordinates is rejected`() {
        assertNull(Action.fromJson(json("""{"type":"tap"}""")))
        assertNull(Action.fromJson(json("""{"type":"tap","x":10}""")))
    }

    @Test
    fun `parses tapMark and rejects non-positive ids`() {
        assertEquals(Action.TapMark(5), Action.fromJson(json("""{"type":"tapMark","markId":5}""")))
        assertNull(Action.fromJson(json("""{"type":"tapMark","markId":0}""")))
        assertNull(Action.fromJson(json("""{"type":"tapMark"}""")))
    }

    @Test
    fun `type aliases are accepted case-insensitively`() {
        assertTrue(Action.fromJson(json("""{"type":"TAP_MARK","markId":1}""")) is Action.TapMark)
        assertTrue(Action.fromJson(json("""{"type":"Long_Press","x":1,"y":1}""")) is Action.LongPress)
        assertTrue(Action.fromJson(json("""{"type":"WebSearch","query":"how to win"}""")) is Action.WebSearch)
    }

    @Test
    fun `parses swipe with all four coordinates required`() {
        val action = Action.fromJson(json("""{"type":"swipe","x1":0,"y1":0,"x2":100,"y2":200}"""))
        assertEquals(Action.Swipe(0, 0, 100, 200), action)
        assertNull(Action.fromJson(json("""{"type":"swipe","x1":0,"y1":0,"x2":100}""")))
    }

    @Test
    fun `typeText requires non-empty text`() {
        assertEquals(
            Action.TypeText("hello", true),
            Action.fromJson(json("""{"type":"typeText","text":"hello","submit":true}"""))
        )
        assertNull(Action.fromJson(json("""{"type":"typeText","text":""}""")))
    }

    @Test
    fun `webSearch requires a non-blank query`() {
        assertEquals(
            Action.WebSearch("best strategy"),
            Action.fromJson(json("""{"type":"webSearch","query":"best strategy"}"""))
        )
        assertNull(Action.fromJson(json("""{"type":"webSearch","query":"  "}""")))
        assertNull(Action.fromJson(json("""{"type":"websearch"}""")))
    }

    @Test
    fun `wait clamps ms into the allowed range`() {
        assertEquals(Action.Wait(500), Action.fromJson(json("""{"type":"wait"}""")))
        assertEquals(Action.Wait(60_000), Action.fromJson(json("""{"type":"wait","ms":999999}""")))
        assertEquals(Action.Wait(0), Action.fromJson(json("""{"type":"wait","ms":-50}""")))
    }

    @Test
    fun `back and noop parse to singletons`() {
        assertEquals(Action.Back, Action.fromJson(json("""{"type":"back"}""")))
        assertEquals(Action.NoOp, Action.fromJson(json("""{"type":"noop"}""")))
        assertEquals(Action.NoOp, Action.fromJson(json("""{}""")))
    }

    @Test
    fun `unknown type returns null`() {
        assertNull(Action.fromJson(json("""{"type":"selfDestruct"}""")))
    }

    @Test
    fun `shortLabel is human-readable and stable`() {
        assertEquals("tap(1,2)", Action.Tap(1, 2).shortLabel())
        assertEquals("tapMark(3)", Action.TapMark(3).shortLabel())
        assertEquals("webSearch(how to beat level 5)", Action.WebSearch("how to beat level 5").shortLabel())
        assertEquals("back", Action.Back.shortLabel())
        assertEquals("noop", Action.NoOp.shortLabel())
    }

    @Test
    fun `shortLabel truncates long webSearch queries`() {
        val longQuery = "a".repeat(100)
        assertEquals("webSearch(${"a".repeat(30)})", Action.WebSearch(longQuery).shortLabel())
    }
}
