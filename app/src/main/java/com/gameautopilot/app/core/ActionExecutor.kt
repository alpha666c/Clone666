package com.gameautopilot.app.core

/**
 * Seam between DecisionLoop and however actions actually get carried out on
 * the device. ActionDispatcher (accessibility gestures) is the only impl
 * today; this interface is what would let a future Shizuku-based executor
 * (root-adjacent shell `input tap`/`input swipe`) drop in without touching
 * DecisionLoop.
 */
interface ActionExecutor {
    suspend fun dispatch(action: Action, marks: List<MarkBox>): Boolean
}
