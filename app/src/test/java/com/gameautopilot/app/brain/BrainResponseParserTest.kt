package com.gameautopilot.app.brain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BrainResponseParserTest {

    @Test
    fun `parses a well-formed decision with structured memory`() {
        val decision = BrainResponseParser.parse(
            """
            {
              "thought": "collect the ready building",
              "actions": [{"type":"tapMark","markId":3}],
              "confidence": 0.9,
              "memory": {"goal": "finish orders", "unlocks": ["level 5"], "notes": "avoid gems"}
            }
            """.trimIndent()
        )
        assertEquals("collect the ready building", decision.thought)
        assertEquals(1, decision.actions.size)
        assertEquals(0.9, decision.confidence, 0.0001)
        assertEquals("finish orders", decision.memoryUpdate?.goal)
        assertEquals(listOf("level 5"), decision.memoryUpdate?.unlocks)
        assertEquals("avoid gems", decision.memoryUpdate?.notes)
    }

    @Test
    fun `strips markdown code fences before parsing`() {
        val decision = BrainResponseParser.parse(
            "```json\n{\"thought\":\"ok\",\"actions\":[],\"confidence\":0.5}\n```"
        )
        assertEquals("ok", decision.thought)
        assertTrue(decision.actions.isEmpty())
    }

    @Test
    fun `skips individually malformed actions without failing the whole response`() {
        val decision = BrainResponseParser.parse(
            """{"thought":"x","actions":[{"type":"tap","x":1,"y":1},{"type":"tap"}],"confidence":0.5}"""
        )
        assertEquals(1, decision.actions.size)
    }

    @Test
    fun `missing memory field leaves memoryUpdate null`() {
        val decision = BrainResponseParser.parse("""{"thought":"x","actions":[],"confidence":0.5}""")
        assertNull(decision.memoryUpdate)
    }

    @Test
    fun `a bare string memory value is treated as leniency notes, not dropped`() {
        val decision = BrainResponseParser.parse(
            """{"thought":"x","actions":[],"confidence":0.5,"memory":"still on level 3"}"""
        )
        assertEquals("still on level 3", decision.memoryUpdate?.notes)
    }

    @Test
    fun `blank string memory is treated as no update`() {
        val decision = BrainResponseParser.parse(
            """{"thought":"x","actions":[],"confidence":0.5,"memory":""}"""
        )
        assertNull(decision.memoryUpdate)
    }

    @Test
    fun `entirely blank memory object is treated as no update`() {
        val decision = BrainResponseParser.parse(
            """{"thought":"x","actions":[],"confidence":0.5,"memory":{}}"""
        )
        assertNull(decision.memoryUpdate)
    }

    @Test(expected = BrainException::class)
    fun `non-JSON text throws BrainException`() {
        BrainResponseParser.parse("I refuse to answer in JSON today.")
    }

    @Test
    fun `missing confidence defaults to 0-5`() {
        val decision = BrainResponseParser.parse("""{"thought":"x","actions":[]}""")
        assertEquals(0.5, decision.confidence, 0.0001)
    }
}
