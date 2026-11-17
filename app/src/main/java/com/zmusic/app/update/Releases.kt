package com.zmusic.app.update

/** The newest build on the GitHub Releases page, as far as the updater cares. */
data class AppRelease(
    /** Same number CI passes as versionCode (the workflow run number). */
    val versionCode: Int,
    val versionName: String,
    val notes: String,
    val apkUrl: String,
    /** Bytes, or -1 if GitHub did not say. */
    val apkSize: Long,
)

/** Plain string rules, kept apart from Android so they can be unit tested. */
object Releases {
    /**
     * CI tags each release `v0.<minor>.<run number>` and builds it with versionCode = run number,
     * so the last number in the tag is the versionCode. `v0.2.57` -> 57.
     */
    fun versionFromTag(tag: String): Int? =
        Regex("""(\d+)\D*$""").find(tag.trim())?.groupValues?.get(1)?.toIntOrNull()

    /** "v0.3.58" -> "0.3.58". */
    fun displayName(tag: String): String = tag.trim().removePrefix("v").removePrefix("V")

    /**
     * Release bodies are the commit message of the push that built them. Drops the parts that are
     * noise in the app: GitHub's "Merge pull request #…" line and CI's install hint.
     */
    fun cleanNotes(body: String?): String =
        body.orEmpty()
            .lineSequence()
            .map { it.trimEnd() }
            .filterNot { it.startsWith("Merge pull request #") || it.startsWith("Signed release build.") }
            .joinToString("\n")
            .replace(Regex("\n{3,}"), "\n\n")
            .trim()
}
