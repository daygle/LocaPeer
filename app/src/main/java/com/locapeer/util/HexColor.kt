package com.locapeer.util

/**
 * A hex colour string as it arrives from the wire, from preferences, or from stored rows.
 *
 * None of those sources is fully under this device's control: a peer picks the pin colour sent
 * in their heartbeat, a backup file can be edited by hand, and rows written by an older app
 * version are read back unchanged. `String.toColorInt()` throws on anything that is not
 * `#RRGGBB`/`#AARRGGBB`, so a malformed value reaching a renderer crashed the map instead of
 * merely looking wrong. Callers validate once at the boundary and treat "" as "no colour
 * chosen", which falls back to the generated palette.
 */
private val HEX_COLOR_REGEX = Regex("#(?:[0-9a-fA-F]{6}|[0-9a-fA-F]{8})")

/** [value] if it is a well-formed hex colour, otherwise "" (meaning "use the fallback"). */
internal fun normalizeHexColor(value: String): String =
    value.takeIf { HEX_COLOR_REGEX.matches(it) } ?: ""
