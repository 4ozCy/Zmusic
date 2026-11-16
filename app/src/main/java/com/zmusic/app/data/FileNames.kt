package com.zmusic.app.data

/**
 * File name rules for renaming a song. Plain functions so they can be unit tested.
 *
 * The user edits the name without its extension; the extension of the current file is kept, so
 * a rename can never turn an .mp3 into something the player no longer recognises.
 */
object FileNames {
    /** Characters Android refuses on FAT and exFAT storage, which is what most SD cards use. */
    private const val FORBIDDEN = "/\\:*?\"<>|"
    private const val MAX_BYTES = 255

    /** "Song.mp3" -> "Song". A name without a dot is all base. A leading dot is not an extension. */
    fun baseName(fileName: String): String {
        val dot = fileName.lastIndexOf('.')
        return if (dot > 0) fileName.substring(0, dot) else fileName
    }

    /** "Song.mp3" -> "mp3", or "" when there is none. */
    fun extension(fileName: String): String {
        val dot = fileName.lastIndexOf('.')
        return if (dot > 0 && dot < fileName.lastIndex) fileName.substring(dot + 1) else ""
    }

    /**
     * The full new name for [input] typed against [current], or null if [input] is not usable
     * (see [problem]). Typing the extension too ("New.mp3") does not double it.
     */
    fun rename(input: String, current: String): String? {
        if (problem(input, current) != null) return null
        val ext = extension(current)
        var base = input.trim()
        if (ext.isNotEmpty() && base.endsWith(".$ext", ignoreCase = true)) base = base.dropLast(ext.length + 1).trimEnd()
        return if (ext.isEmpty()) base else "$base.$ext"
    }

    /** Why [input] cannot be used as a new name for [current], in a sentence, or null if it can. */
    fun problem(input: String, current: String): String? {
        val ext = extension(current)
        var base = input.trim()
        if (ext.isNotEmpty() && base.endsWith(".$ext", ignoreCase = true)) base = base.dropLast(ext.length + 1).trimEnd()
        return when {
            base.isEmpty() -> "Type a name."
            base.any { it in FORBIDDEN } -> "Names can't contain / \\ : * ? \" < > |"
            base.any { it.code < 32 || it.code == 127 } -> "That name has a character files can't use."
            base.startsWith(".") -> "Names can't start with a dot. Android would hide the file."
            (if (ext.isEmpty()) base else "$base.$ext").toByteArray(Charsets.UTF_8).size > MAX_BYTES -> "That name is too long."
            else -> null
        }
    }
}
