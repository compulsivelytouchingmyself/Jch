package com.jarron.jch.ai

import android.util.Log
import com.google.firebase.Firebase
import com.google.firebase.ai.ai
import com.google.firebase.ai.type.ThinkingLevel
import com.google.firebase.ai.type.generationConfig
import com.google.firebase.ai.type.thinkingConfig
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

data class ScriptCoachResult(
    val title: String = "",
    val hook: String = "",
    val teleprompterLines: List<String> = emptyList(),
    val fullScript: String = "",
    val recommendedPreRollMs: Int = 250,
    val recommendedPostRollMs: Int = 350,
    val cadenceTip: String = ""
)

sealed interface AiCoachUiState {
    data object Idle : AiCoachUiState
    data object Loading : AiCoachUiState
    data class Success(val result: ScriptCoachResult) : AiCoachUiState
    data class Error(val message: String) : AiCoachUiState
}

class AiScriptCoach {
    companion object {
        private const val TAG = "AiScriptCoach"
        private const val MODEL_NAME = "gemini-4-argon"
    }

    private val _uiState = MutableStateFlow<AiCoachUiState>(AiCoachUiState.Idle)
    val uiState: StateFlow<AiCoachUiState> = _uiState.asStateFlow()

    // Configure model with thinkingLevel = ThinkingLevel.HIGH and no maxOutputTokens
    private val model by lazy {
        val config = generationConfig {
            thinkingConfig = thinkingConfig {
                thinkingLevel = ThinkingLevel.HIGH
            }
        }
        Firebase.ai.generativeModel(
            modelName = MODEL_NAME,
            generationConfig = config
        )
    }

    suspend fun generateJumpCutScript(topicOrDraft: String) = withContext(Dispatchers.IO) {
        _uiState.value = AiCoachUiState.Loading
        try {
            val prompt = """
                You are the master Jump Cut Producer for viral short-form video (TikTok, YouTube Shorts, Reels).
                The creator is using 'Jump Cut Hero', an automated buffer-slicing camera app that cuts silence gaps between phrases using pre-roll and post-roll audio clamping.
                
                Topic or Draft: "$topicOrDraft"
                
                Task:
                Think through the psychological retention curve, speech cadence, and jump-cut transition points.
                Create an explosive 30-to-45-second jump-cut video script.
                
                Format your final answer strictly with these clear section headings:
                [TITLE]: Short punchy title
                [HOOK]: First 3 seconds attention grabber
                [SCRIPT]:
                Line 1
                [CUT]
                Line 2
                [CUT]
                Line 3
                [CUT]
                Line 4
                [RECOMMENDED_BUFFERS]: Pre-roll: <ms>, Post-roll: <ms>
                [PACING_TIP]: One golden rule for speech cadence when recording this script
            """.trimIndent()

            val response = withTimeout(60_000L) {
                model.generateContent(prompt)
            }

            val text = response.text
            if (!text.isNullOrBlank()) {
                val parsed = parseScriptResult(text)
                _uiState.value = AiCoachUiState.Success(parsed)
            } else {
                _uiState.value = AiCoachUiState.Error("Model returned an empty script response.")
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "AI Script generation failed", e)
            _uiState.value = AiCoachUiState.Error(e.localizedMessage ?: "Script generation failed.")
        }
    }

    private fun parseScriptResult(raw: String): ScriptCoachResult {
        var title = "Jump Cut Master Script"
        var hook = ""
        val scriptLines = mutableListOf<String>()
        var preRoll = 250
        var postRoll = 350
        var pacingTip = "Take deliberate pauses between sentences—the app will slice out all silence seamlessly!"

        val lines = raw.lines()
        var currentSection = ""
        val scriptBuilder = StringBuilder()

        for (line in lines) {
            val trimmed = line.trim()
            when {
                trimmed.startsWith("[TITLE]:", ignoreCase = true) -> {
                    title = trimmed.substringAfter(":").trim()
                }
                trimmed.startsWith("[HOOK]:", ignoreCase = true) -> {
                    hook = trimmed.substringAfter(":").trim()
                }
                trimmed.startsWith("[SCRIPT]:", ignoreCase = true) -> {
                    currentSection = "SCRIPT"
                }
                trimmed.startsWith("[RECOMMENDED_BUFFERS]:", ignoreCase = true) -> {
                    currentSection = "BUFFERS"
                    val content = trimmed.substringAfter(":")
                    val preMatch = Regex("pre[- ]?roll:?\\s*(\\d+)", RegexOption.IGNORE_CASE).find(content)
                    val postMatch = Regex("post[- ]?roll:?\\s*(\\d+)", RegexOption.IGNORE_CASE).find(content)
                    preMatch?.groupValues?.get(1)?.toIntOrNull()?.let { preRoll = it.coerceIn(100, 600) }
                    postMatch?.groupValues?.get(1)?.toIntOrNull()?.let { postRoll = it.coerceIn(100, 800) }
                }
                trimmed.startsWith("[PACING_TIP]:", ignoreCase = true) -> {
                    currentSection = "TIP"
                    pacingTip = trimmed.substringAfter(":").trim()
                }
                currentSection == "SCRIPT" -> {
                    if (trimmed.isNotEmpty() && !trimmed.startsWith("[")) {
                        scriptBuilder.appendLine(trimmed)
                        if (trimmed != "[CUT]") {
                            scriptLines.add(trimmed)
                        }
                    }
                }
                currentSection == "TIP" -> {
                    if (trimmed.isNotEmpty()) {
                        pacingTip = (pacingTip + " " + trimmed).trim()
                    }
                }
            }
        }

        if (scriptLines.isEmpty()) {
            scriptLines.addAll(raw.lines().filter { it.isNotBlank() }.take(5))
        }

        return ScriptCoachResult(
            title = title,
            hook = hook,
            teleprompterLines = scriptLines,
            fullScript = raw,
            recommendedPreRollMs = preRoll,
            recommendedPostRollMs = postRoll,
            cadenceTip = pacingTip
        )
    }

    fun reset() {
        _uiState.value = AiCoachUiState.Idle
    }
}
