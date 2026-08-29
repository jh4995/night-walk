package com.bammasil.poc.gl

/** Final presentation mode. Normal mode keeps the existing single-view path unchanged. */
enum class DisplayMode(val id: String) {
    NORMAL("normal"),
    CARDBOARD_SBS("cardboard_sbs"),
    ;

    companion object {
        val DEFAULT = NORMAL
        val CHOICES: List<String> = entries.map { it.id }

        fun fromId(value: String?): DisplayMode =
            entries.firstOrNull { it.id == value } ?: DEFAULT
    }
}
