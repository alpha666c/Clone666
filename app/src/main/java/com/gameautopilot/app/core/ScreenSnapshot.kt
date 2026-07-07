package com.gameautopilot.app.core

import com.gameautopilot.app.vision.BoardReader

data class ScreenSnapshot(
    val width: Int,
    val height: Int,
    val foregroundPackage: String?,
    val ocrLines: List<String>,
    val a11yLines: List<String>,
    val marks: List<MarkBox>,
    val screenshotBase64Jpeg: String,
    val perceptualHash: Long,
    val boardState: BoardReader.BoardState? = null
)
