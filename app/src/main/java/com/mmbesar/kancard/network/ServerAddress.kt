package com.mmbesar.kancard.network

import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/**
 * Turns whatever the user typed into a clean Kanboard API address.
 *
 * Accepted input examples:
 *   kanboard.lan                      -> https://kanboard.lan/jsonrpc.php
 *   http://10.0.0.5:8080              -> http://10.0.0.5:8080/jsonrpc.php
 *   https://example.com/kanboard/     -> https://example.com/kanboard/jsonrpc.php
 *   https://example.com/jsonrpc.php   -> unchanged
 *
 * Unlike many apps, BOTH http:// and https:// are accepted. If no scheme is
 * typed we assume https:// (the safer choice).
 */
object ServerAddress {

    /** The cleaned-up address plus the facts the rest of the app needs. */
    data class Parsed(
        val endpoint: String,
        val isCleartext: Boolean,
        val host: String,
        val port: Int,
    )

    private const val ENDPOINT_FILE = "jsonrpc.php"

    /** Returns null when the text is not a usable http(s) address. */
    fun parse(input: String): Parsed? {
        val trimmed = input.trim()
        if (trimmed.isEmpty()) return null

        val withScheme = if (trimmed.contains("://")) trimmed else "https://$trimmed"
        val url = withScheme.toHttpUrlOrNull() ?: return null

        // Rebuild the URL from scratch: this drops any user:password, query or #fragment.
        val segments = url.pathSegments.filter { it.isNotEmpty() }
        val builder = HttpUrl.Builder()
            .scheme(url.scheme)
            .host(url.host)
            .port(url.port)
        segments.forEach { builder.addPathSegment(it) }
        if (segments.lastOrNull() != ENDPOINT_FILE) {
            builder.addPathSegment(ENDPOINT_FILE)
        }

        val endpoint = builder.build()
        return Parsed(
            endpoint = endpoint.toString(),
            isCleartext = endpoint.scheme == "http",
            host = endpoint.host,
            port = endpoint.port,
        )
    }
}
