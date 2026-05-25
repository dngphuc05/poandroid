package com.pocketmocap.app.network

import java.net.URI
import java.net.URLDecoder
import java.nio.charset.StandardCharsets

object ServerLinkParser {
    fun parseServerUrl(raw: String): String? {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) return null

        val payload = if (trimmed.startsWith("pocap://", ignoreCase = true)) {
            extractServerQueryValue(trimmed) ?: return null
        } else {
            trimmed
        }
        return normalizeServerUrl(payload)
    }

    fun normalizeServerUrl(raw: String): String? {
        val trimmed = raw.trim().trimEnd('/')
        if (trimmed.isEmpty()) return null
        if ("://" in trimmed && !trimmed.startsWith("http://", true) && !trimmed.startsWith("https://", true)) {
            return null
        }

        val withScheme = if (trimmed.startsWith("http://", true) || trimmed.startsWith("https://", true)) {
            trimmed
        } else {
            "http://$trimmed"
        }

        return runCatching {
            val uri = URI(withScheme)
            if (uri.host.isNullOrBlank()) null else withScheme
        }.getOrNull()
    }

    private fun extractServerQueryValue(raw: String): String? {
        val query = runCatching { URI(raw).rawQuery }.getOrNull()
            ?: raw.substringAfter('?', missingDelimiterValue = "")
        if (query.isBlank()) return null
        return query.split('&')
            .mapNotNull { part ->
                val key = part.substringBefore('=', "")
                val value = part.substringAfter('=', "")
                if (key == "server" && value.isNotBlank()) {
                    URLDecoder.decode(value, StandardCharsets.UTF_8.name())
                } else {
                    null
                }
            }
            .firstOrNull()
    }
}
