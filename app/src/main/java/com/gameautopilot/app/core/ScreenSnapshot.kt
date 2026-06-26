package com.gameautopilot.app.core

data class ScreenSnapshot(
    val width: Int,
    val height: Int,
    val foregroundPackage: String?,
    val ocrLines: List<String>,
    val a11yLines: List<String>,
    val screenshotBase64Jpeg: String
)
