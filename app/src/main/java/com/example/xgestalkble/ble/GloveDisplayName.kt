package com.example.xgestalkble.ble

/** Brand the glove in the app without changing its Bluetooth identity. */
fun gloveDisplayName(name: String?): String = when {
    name.isNullOrBlank() -> "Samvaad Glove"
    name.contains("gestalk", ignoreCase = true) ||
        name.contains("samvaad", ignoreCase = true) -> "Samvaad Glove"
    else -> name
}
