/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv.pairing

import app.opentv.data.model.Source
import app.opentv.data.model.SourceKind
import java.net.URLDecoder

/**
 * The pure, testable half of the phone-pairing server.
 *
 * Request parsing and form decoding live here, apart from sockets, so they can be tested
 * without standing up a server or a device. Everything in this file is a pure function.
 */
object PairingHttp {

    data class Request(
        val method: String,
        val path: String,
        val query: Map<String, String>,
        val headers: Map<String, String>,
        val body: String,
    ) {
        val contentLength: Int
            get() = headers["content-length"]?.toIntOrNull() ?: 0
    }

    /** Parses a request line such as `GET /pair?t=abc HTTP/1.1`. Null if it is not one. */
    fun parseRequestLine(line: String): Triple<String, String, Map<String, String>>? {
        val parts = line.trim().split(' ')
        if (parts.size < 2) return null
        val method = parts[0].uppercase()
        val target = parts[1]
        val path = target.substringBefore('?')
        val query = parseFormEncoded(target.substringAfter('?', ""))
        return Triple(method, path, query)
    }

    /**
     * Decodes `application/x-www-form-urlencoded` data, used for both query strings and
     * POST bodies.
     *
     * Tolerant on purpose: a malformed pair is skipped rather than throwing, because the
     * only thing on the other end is a form the user is typing into on a phone.
     */
    fun parseFormEncoded(raw: String): Map<String, String> {
        if (raw.isBlank()) return emptyMap()
        return raw.split('&').mapNotNull { pair ->
            if (pair.isBlank()) return@mapNotNull null
            val key = pair.substringBefore('=')
            val value = pair.substringAfter('=', "")
            if (key.isBlank()) return@mapNotNull null
            runCatching {
                decode(key) to decode(value)
            }.getOrNull()
        }.toMap()
    }

    private fun decode(value: String): String =
        URLDecoder.decode(value.replace("+", "%20"), "UTF-8")

    /** Parses one header line into a lowercased name and its value. */
    fun parseHeader(line: String): Pair<String, String>? {
        val index = line.indexOf(':')
        if (index <= 0) return null
        return line.substring(0, index).trim().lowercase() to line.substring(index + 1).trim()
    }

    /**
     * Constant-time-ish comparison for the pairing token.
     *
     * The token is only valid for a few minutes on a local network, so this is not load
     * bearing — but comparing secrets with `==` is a habit worth not forming.
     */
    fun secretsMatch(a: String, b: String): Boolean {
        if (a.length != b.length) return false
        var difference = 0
        for (i in a.indices) {
            difference = difference or (a[i].code xor b[i].code)
        }
        return difference == 0
    }

    /**
     * True for addresses on a private network.
     *
     * The server binds to the LAN, so a request from a public address should be impossible.
     * Checking anyway costs nothing and means a misconfigured router cannot turn this into
     * an internet-facing form that accepts IPTV credentials.
     */
    fun isPrivateAddress(host: String?): Boolean {
        if (host.isNullOrBlank()) return false
        val address = host.substringAfterLast('%') // strip IPv6 zone id
        if (address == "127.0.0.1" || address == "::1" || address == "0:0:0:0:0:0:0:1") return true

        val octets = address.split('.')
        if (octets.size != 4) {
            // IPv6 unique-local (fc00::/7) and link-local (fe80::/10).
            val lower = address.lowercase()
            return lower.startsWith("fd") || lower.startsWith("fc") || lower.startsWith("fe80")
        }
        val numbers = octets.map { it.toIntOrNull() ?: return false }
        return when {
            numbers[0] == 10 -> true
            numbers[0] == 192 && numbers[1] == 168 -> true
            numbers[0] == 172 && numbers[1] in 16..31 -> true
            numbers[0] == 169 && numbers[1] == 254 -> true
            // 100.64.0.0/10 — CGNAT range, which is what Tailscale hands out. Treat it as private
            // so device sync works over a tailnet (advertised as an address and accepted as a peer).
            numbers[0] == 100 && numbers[1] in 64..127 -> true
            else -> false
        }
    }

    sealed interface FormResult {
        data class Ok(val source: Source) : FormResult
        data object MissingUrl : FormResult
        data object MissingMac : FormResult
    }

    /**
     * Turns what the phone submitted into a [Source].
     *
     * Only the fields that belong to the chosen type are kept: the page hides the others but still
     * posts them, so a login typed under Xtream and then abandoned for an M3U link must not leak
     * into the saved playlist. The page's `required` attributes are client-side only, so what the
     * server cannot do without (the address, and the MAC for Stalker) is checked again here.
     * Default names are passed in because they are shown on this device, in its language, not in
     * the phone's.
     */
    fun sourceFromForm(form: Map<String, String>, providerName: String, playlistName: String): FormResult {
        val kind = when (form["kind"]) {
            "m3u" -> SourceKind.M3U
            "stalker" -> SourceKind.STALKER
            else -> SourceKind.XTREAM
        }
        val url = form["url"].orEmpty().trim()
        if (url.isEmpty()) return FormResult.MissingUrl
        val mac = form["mac"]?.trim()?.takeIf { it.isNotEmpty() }
        if (kind == SourceKind.STALKER && mac == null) return FormResult.MissingMac

        return FormResult.Ok(
            Source(
                name = form["name"].orEmpty().trim().ifBlank {
                    if (kind == SourceKind.M3U) playlistName else providerName
                },
                kind = kind,
                url = url,
                username = form["username"]?.trim()?.takeIf { it.isNotEmpty() && kind == SourceKind.XTREAM },
                password = form["password"]?.takeIf { it.isNotEmpty() && kind == SourceKind.XTREAM },
                macAddress = mac.takeIf { kind == SourceKind.STALKER },
                epgUrl = form["epg"]?.trim()?.takeIf { it.isNotEmpty() },
            ),
        )
    }

    fun httpResponse(
        status: String,
        contentType: String,
        body: String,
    ): ByteArray {
        val bytes = body.toByteArray(Charsets.UTF_8)
        val head = buildString {
            append("HTTP/1.1 ").append(status).append("\r\n")
            append("Content-Type: ").append(contentType).append("; charset=utf-8\r\n")
            append("Content-Length: ").append(bytes.size).append("\r\n")
            append("Connection: close\r\n")
            // This page exists for a few minutes on a private network and handles
            // credentials. Nothing about it should be cached or indexed anywhere.
            append("Cache-Control: no-store\r\n")
            append("X-Content-Type-Options: nosniff\r\n")
            append("Referrer-Policy: no-referrer\r\n")
            append("\r\n")
        }
        return head.toByteArray(Charsets.US_ASCII) + bytes
    }
}
