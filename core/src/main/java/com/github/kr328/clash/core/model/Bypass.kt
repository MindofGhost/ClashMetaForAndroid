package com.github.kr328.clash.core.model

import kotlinx.serialization.Serializable

@Serializable
data class BypassConfig(
    val type: String,
    val endpoint: String,
    val config: String,
    val check: List<BypassCheck> = emptyList(),
)

@Serializable
data class BypassCheck(val url: String, val alive: Boolean)

@Serializable
data class CoreHealthCheck(
    val round: String,
    val endpoint: String,
    val alive: Boolean,
    val finished: Boolean,
    val time: Long,
)

@Serializable
data class VkTurnEvent(
    val endpoint: String,
    val token: String,
    val message: String,
    val captcha: String? = null,
)
