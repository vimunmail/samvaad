package com.example.xgestalkble.utils

import java.net.URI

/** Only explicit private IPv4 addresses may receive the local pairing key. */
internal object ComputerTranslationEndpoint {
    fun validate(address: String): String {
        require(address.isNotBlank()) { "Copy the computer address from the translator's pairing details." }
        val uri = runCatching { URI(address.trim()) }.getOrElse { error("Enter the computer address shown by the translator.") }
        require(uri.scheme in listOf("http", "https") && uri.userInfo == null && uri.query == null && uri.fragment == null && uri.path.orEmpty() in listOf("", "/")) {
            "Use a computer address such as http://192.168.1.5:8765, without a path."
        }
        val parts = uri.host.orEmpty().split('.').map { it.toIntOrNull() }
        require(parts.size == 4 && parts.all { it != null && it in 0..255 }) { "Enter your computer's local IPv4 address." }
        val a = parts[0]; val b = parts[1]
        require(a == 10 || a == 127 || (a == 192 && b == 168) || (a == 172 && b in 16..31)) {
            "The computer translator must use a private network address."
        }
        require(uri.port == -1 || uri.port in 1..65535) { "Invalid computer port." }
        return address.trim().trimEnd('/')
    }
}
