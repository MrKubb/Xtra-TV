package com.github.andreyasadchy.xtra.ui.tv

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.widget.LinearLayout
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.content.edit
import androidx.core.os.LocaleListCompat
import com.github.andreyasadchy.xtra.R
import com.github.andreyasadchy.xtra.databinding.TvSettingHeaderBinding
import com.github.andreyasadchy.xtra.databinding.TvSettingRowBinding
import com.github.andreyasadchy.xtra.ui.main.MainActivity
import com.github.andreyasadchy.xtra.util.C
import com.github.andreyasadchy.xtra.util.getAlertDialogBuilder
import com.github.andreyasadchy.xtra.util.prefs

/**
 * Remote-friendly settings page shown in the TV content area.
 * TV-only options use their own keys; the others are Xtra's own preference keys, so both interfaces stay in sync.
 * Everything else stays reachable through "All Xtra settings".
 */
class TvSettings(
    private val activity: AppCompatActivity,
    private val container: LinearLayout,
    private val viewModel: TvViewModel,
    /** Sections or card size changed; true when the cards themselves must be rebuilt. */
    private val onHomeChanged: (Boolean) -> Unit,
    private val openXtraSettings: () -> Unit,
) {
    private val prefs = activity.prefs()
    private val inflater = LayoutInflater.from(activity)

    /** The "Menu sections" editor replaces the settings list while it is open. */
    private var editingSections = false
    private var movingKey: String? = null

    /** Leaving the settings page closes the sections editor. */
    fun reset() {
        editingSections = false
        movingKey = null
    }

    /** Back while the sections editor is open goes back to the settings list. */
    fun closeSectionsEditor(): Boolean {
        if (!editingSections) return false
        editingSections = false
        movingKey = null
        show()
        container.getChildAt(SECTIONS_ROW_INDEX)?.requestFocus()
        return true
    }

    /**
     * Rebuilds the list without losing the focus: removing the focused row would otherwise send the
     * focus to the first view of the window (the sidebar).
     */
    private fun rebuild(build: () -> Unit, focus: () -> View?) {
        val hadFocus = container.hasFocus()
        if (hadFocus) {
            container.isFocusable = true
            container.requestFocus()
        }
        container.removeAllViews()
        build()
        // move the focus to the new row before the list stops being focusable, or it would be cleared
        if (hadFocus) (focus() ?: container.getChildAt(1))?.requestFocus()
        container.isFocusable = false
    }

    fun show() {
        if (editingSections) return showSectionsEditor(null)
        val focused = container.focusedChild?.let { container.indexOfChild(it) }
        rebuild({ buildList() }) { focused?.let { container.getChildAt(it) } }
    }

    private fun buildList() {

        section(R.string.tv_section_home)
        val pages = TvViewModel.Page.sections(activity)
        choice(R.string.tv_start_page, KEY_START_PAGE, START_AUTO,
            listOf(activity.getString(R.string.tv_start_auto)) + pages.map { it.title(activity) },
            listOf(START_AUTO) + pages.map { it.key.orEmpty() })
        val entries = TvSections.entries(activity)
        row(R.string.tv_sections, "${entries.count { it.visible }} / ${entries.size}") {
            editingSections = true
            showSectionsEditor(entries.firstOrNull()?.key)
        }
        val tabs = languageTabs(activity)
        row(R.string.tv_language_tabs, tabs.joinToString(", ") { it.uppercase() }.ifEmpty { activity.getString(R.string.tv_none) }) {
            TvLanguages.showDialog(activity, activity.getString(R.string.tv_language_tabs), tabs) {
                prefs.edit { putString(KEY_LANGUAGE_TABS, it.joinToString(",")) }
                show()
                onHomeChanged(false)
            }
        }
        row(R.string.tv_top_streams_languages, viewModel.languages.value.joinToString(", ") { it.uppercase() }.ifEmpty { activity.getString(R.string.tv_all_languages) }) {
            TvLanguages.showDialog(activity, activity.getString(R.string.tv_top_streams_languages), viewModel.languages.value) {
                viewModel.setLanguages(it.toSet())
                show()
            }
        }
        choice(R.string.tv_card_size, KEY_CARD_SIZE, SIZE_MEDIUM,
            listOf(R.string.tv_size_small, R.string.tv_size_medium, R.string.tv_size_large).map { activity.getString(it) },
            listOf(SIZE_SMALL, SIZE_MEDIUM, SIZE_LARGE)) { onHomeChanged(true) }
        toggle(R.string.tv_preview, KEY_PREVIEW, true)
        toggle(R.string.tv_start_on_tv, KEY_START_ON_TV, isTvDevice(activity))

        section(R.string.tv_section_general)
        val languages = activity.resources.getStringArray(R.array.language).toList()
        val currentLanguage = AppCompatDelegate.getApplicationLocales().takeUnless { it.isEmpty }?.toLanguageTags() ?: "auto"
        row(R.string.language, activity.resources.getStringArray(R.array.languageEntries).getOrNull(languages.indexOf(currentLanguage))) {
            choiceDialog(R.string.language, activity.resources.getStringArray(R.array.languageEntries).toList(), languages.indexOf(currentLanguage)) { i ->
                prefs.edit { putString(C.UI_LANGUAGE, languages[i]) }
                // same as Xtra's settings: recreates the activities in the new language
                AppCompatDelegate.setApplicationLocales(LocaleListCompat.forLanguageTags(languages[i].takeUnless { it == "auto" }))
            }
        }

        section(R.string.tv_section_playback)
        choice(R.string.player_defaultquality, C.PLAYER_DEFAULT_QUALITY, "saved",
            activity.resources.getStringArray(R.array.playerQualityEntries).toList(),
            activity.resources.getStringArray(R.array.playerQualityValues).toList())
        toggle(R.string.hide_during_ads, C.PLAYER_HIDE_ADS, false)
        toggle(R.string.save_video_positions, C.PLAYER_USE_VIDEO_POSITIONS, true)

        section(R.string.tv_section_chat)
        toggle(R.string.disable_chat, C.CHAT_DISABLE, false)
        val textSizes = listOf("12", "14", "16", "18", "20", "24")
        choice(R.string.text_size, C.CHAT_TEXT_SIZE, "14", textSizes, textSizes)
        toggle(R.string.timestamps, C.CHAT_TIMESTAMPS, false)
        toggle(R.string.animated_emotes, C.ANIMATED_EMOTES, true)

        section(R.string.tv_section_advanced)
        row(R.string.tv_all_settings, null, openXtraSettings)
        row(R.string.tv_full_interface, null) { activity.startActivity(Intent(activity, MainActivity::class.java)) }
    }

    private fun section(title: Int) {
        TvSettingHeaderBinding.inflate(inflater, container, true).root.setText(title)
    }

    private fun row(title: Int, value: String?, onClick: () -> Unit) = row(activity.getString(title), value, onClick)

    private fun row(title: String, value: String?, onClick: () -> Unit): View =
        with(TvSettingRowBinding.inflate(inflater, container, true)) {
            this.title.text = title
            this.value.text = value
            root.setOnClickListener { onClick() }
            root
        }

    /**
     * One row per section: OK offers to move or hide it. While moving, UP/DOWN move the section
     * and OK or Back put it down. Every change applies to the sidebar and the home rows right away.
     */
    private fun showSectionsEditor(focusKey: String?) {
        rebuild({ buildSectionsEditor() }) { (0 until container.childCount).map { container.getChildAt(it) }.find { it.tag == focusKey } }
    }

    private fun buildSectionsEditor() {
        section(R.string.tv_sections)
        TvSettingHeaderBinding.inflate(inflater, container, true).root.apply {
            isAllCaps = false
            setText(R.string.tv_sections_hint)
        }
        val entries = TvSections.entries(activity)
        entries.forEach { entry ->
            val page = TvViewModel.Page.fromKey(entry.key) ?: return@forEach
            val moving = entry.key == movingKey
            val view = row(
                (if (moving) "↕  " else "") + page.title(activity),
                activity.getString(if (moving) R.string.tv_moving else if (entry.visible) R.string.tv_shown else R.string.tv_hidden),
            ) {
                if (movingKey != null) {
                    movingKey = null
                    showSectionsEditor(entry.key)
                } else {
                    sectionMenu(entry)
                }
            }
            view.tag = entry.key
            view.setOnKeyListener { _, keyCode, event ->
                if (movingKey != entry.key) return@setOnKeyListener false
                when (keyCode) {
                    KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN -> {
                        if (event.action == KeyEvent.ACTION_DOWN) moveSection(entry.key, if (keyCode == KeyEvent.KEYCODE_DPAD_UP) -1 else 1)
                        true
                    }
                    KeyEvent.KEYCODE_BACK -> {
                        if (event.action == KeyEvent.ACTION_UP) {
                            movingKey = null
                            showSectionsEditor(entry.key)
                        }
                        true
                    }
                    else -> false
                }
            }
        }
    }

    private fun sectionMenu(entry: TvSections.Entry) {
        val title = TvViewModel.Page.fromKey(entry.key)?.title(activity)
        activity.getAlertDialogBuilder()
            .setTitle(title)
            .setItems(arrayOf(activity.getString(R.string.tv_move), activity.getString(if (entry.visible) R.string.tv_hide else R.string.tv_show))) { _, which ->
                if (which == 0) {
                    movingKey = entry.key
                } else {
                    TvSections.save(activity, TvSections.entries(activity).map { if (it.key == entry.key) TvSections.Entry(it.key, !it.visible) else it })
                    onHomeChanged(false)
                }
                showSectionsEditor(entry.key)
            }
            .show()
    }

    private fun moveSection(key: String, delta: Int) {
        val entries = TvSections.entries(activity).toMutableList()
        val from = entries.indexOfFirst { it.key == key }
        val to = from + delta
        if (from < 0 || to !in entries.indices) return
        entries.add(to, entries.removeAt(from))
        TvSections.save(activity, entries)
        onHomeChanged(false)
        showSectionsEditor(key)
    }

    private fun toggle(title: Int, key: String, default: Boolean) {
        val checked = prefs.getBoolean(key, default)
        row(title, activity.getString(if (checked) R.string.tv_on else R.string.tv_off)) {
            prefs.edit { putBoolean(key, !checked) }
            show()
        }
    }

    private fun choice(title: Int, key: String, default: String, names: List<String>, values: List<String>, onChange: () -> Unit = {}) {
        val index = values.indexOf(prefs.getString(key, default))
        row(title, names.getOrNull(index)) {
            choiceDialog(title, names, index) { i ->
                prefs.edit { putString(key, values[i]) }
                show()
                onChange()
            }
        }
    }

    private fun choiceDialog(title: Int, names: List<String>, index: Int, onSelect: (Int) -> Unit) {
        activity.getAlertDialogBuilder()
            .setTitle(title)
            .setSingleChoiceItems(names.toTypedArray(), index) { dialog, which ->
                dialog.dismiss()
                onSelect(which)
            }
            .show()
    }

    companion object {
        const val KEY_START_PAGE = "tv_start_page"
        const val KEY_CARD_SIZE = "tv_card_size"
        const val KEY_START_ON_TV = "tv_start_on_tv"
        const val KEY_LANGUAGE_TABS = "tv_language_tabs"
        const val KEY_PREVIEW = "tv_preview"
        private const val KEY_LAST_CHANNEL_LOGIN = "tv_last_channel_login"
        private const val KEY_LAST_CHANNEL_NAME = "tv_last_channel_name"
        /** Position of the "Menu sections" row in the settings list (after the section header and the start section). */
        private const val SECTIONS_ROW_INDEX = 2
        const val START_AUTO = "auto"
        const val SIZE_SMALL = "small"
        const val SIZE_MEDIUM = "medium"
        const val SIZE_LARGE = "large"

        fun isTvDevice(context: Context) = context.packageManager.hasSystemFeature(PackageManager.FEATURE_LEANBACK)

        private const val KEY_TV_DEFAULTS = "tv_defaults_version"

        /**
         * One-time defaults of the TV interface, applied to Xtra's own settings:
         * streams start at the best quality available ("Source" in Xtra). Still changeable afterwards.
         */
        fun applyTvDefaults(context: Context) {
            val prefs = context.prefs()
            if (prefs.getInt(KEY_TV_DEFAULTS, 0) >= 1) return
            prefs.edit {
                putString(C.PLAYER_DEFAULT_QUALITY, "Source")
                putInt(KEY_TV_DEFAULTS, 1)
            }
        }

        /** Whether the app launcher should open the TV interface instead of Xtra's (on by default on TVs). */
        fun startOnTv(context: Context) = context.prefs().getBoolean(KEY_START_ON_TV, isTvDevice(context))

        /** Remembers the last channel watched from the TV interface, for the suggestions row. */
        fun setLastChannel(context: Context, login: String?, name: String?) {
            if (login.isNullOrBlank()) return
            context.prefs().edit {
                putString(KEY_LAST_CHANNEL_LOGIN, login)
                putString(KEY_LAST_CHANNEL_NAME, name ?: login)
            }
        }

        /** Channel the suggestions are based on (login to name): the last one watched, else the first favorite. */
        fun suggestionsSeed(context: Context): Pair<String, String>? {
            val prefs = context.prefs()
            prefs.getString(KEY_LAST_CHANNEL_LOGIN, null)?.let { return it to (prefs.getString(KEY_LAST_CHANNEL_NAME, null) ?: it) }
            return TvFavorites.list(context).firstOrNull { it.login != null }?.let { it.login!! to (it.name ?: it.login) }
        }

        /** Languages that get their own home row and sidebar entry, in the order they were picked. */
        fun languageTabs(context: Context): List<String> =
            context.prefs().getString(KEY_LANGUAGE_TABS, null)?.split(',')?.filter { it.isNotBlank() }.orEmpty()

        fun previewEnabled(context: Context) = context.prefs().getBoolean(KEY_PREVIEW, true)

        fun cardSize(context: Context) = context.prefs().getString(KEY_CARD_SIZE, SIZE_MEDIUM)

        /** Extra columns for the selected card size: small cards = more per row. */
        fun columnOffset(context: Context) = when (cardSize(context)) {
            SIZE_SMALL -> 1
            SIZE_LARGE -> -1
            else -> 0
        }
    }
}
