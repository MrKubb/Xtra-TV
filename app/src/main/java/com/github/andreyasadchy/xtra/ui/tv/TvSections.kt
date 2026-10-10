package com.github.andreyasadchy.xtra.ui.tv

import android.content.Context
import androidx.core.content.edit
import com.github.andreyasadchy.xtra.util.prefs

/** Order and visibility of the home sections (sidebar entries and home rows), chosen in the TV settings. */
object TvSections {

    private const val KEY = "tv_sections"

    class Entry(val key: String, val visible: Boolean)

    /** Default order; "!" marks the sections hidden by default. */
    private fun defaults(context: Context): List<String> =
        listOf("following", "favorites") + TvSettings.languageTabs(context).map { "lang_$it" } +
                listOf("followed_game_rows", "suggestions", "followed_videos", "followed_channels", "!followed_games", "top_games", "top_streams")

    /** Saved order, with sections added since then inserted at their default place. */
    fun entries(context: Context): List<Entry> {
        val defaults = defaults(context)
        val keys = defaults.map { it.removePrefix("!") }
        val result = context.prefs().getString(KEY, null)?.split(',').orEmpty()
            .filter { it.isNotBlank() }
            .map { Entry(it.removePrefix("!"), !it.startsWith("!")) }
            .filter { it.key in keys }
            .distinctBy { it.key }
            .toMutableList()
        defaults.forEachIndexed { i, raw ->
            val key = raw.removePrefix("!")
            if (result.none { it.key == key }) {
                val after = keys.subList(0, i).lastOrNull { k -> result.any { it.key == k } }
                val index = after?.let { k -> result.indexOfFirst { it.key == k } + 1 } ?: 0
                result.add(index, Entry(key, !raw.startsWith("!")))
            }
        }
        return result
    }

    fun save(context: Context, entries: List<Entry>) {
        context.prefs().edit { putString(KEY, entries.joinToString(",") { (if (it.visible) "" else "!") + it.key }) }
    }
}
