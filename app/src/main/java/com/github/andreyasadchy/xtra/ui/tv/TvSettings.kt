package com.github.andreyasadchy.xtra.ui.tv

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.view.LayoutInflater
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
    private val onHomeChanged: () -> Unit,
    private val openXtraSettings: () -> Unit,
) {
    private val prefs = activity.prefs()
    private val inflater = LayoutInflater.from(activity)

    fun show() {
        val focused = container.focusedChild?.let { container.indexOfChild(it) }
        container.removeAllViews()

        section(R.string.tv_section_home)
        val pages = TvViewModel.Page.roots
        choice(R.string.tv_start_page, KEY_START_PAGE, START_AUTO,
            listOf(activity.getString(R.string.tv_start_auto)) + pages.map { activity.getString(it.title) },
            listOf(START_AUTO) + pages.map { it.key.orEmpty() },
            onHomeChanged)
        val hidden = hiddenSections(activity)
        row(R.string.tv_sections, "${pages.count { it.key !in hidden }} / ${pages.size}") { sectionsDialog() }
        choice(R.string.tv_card_size, KEY_CARD_SIZE, SIZE_MEDIUM,
            listOf(R.string.tv_size_small, R.string.tv_size_medium, R.string.tv_size_large).map { activity.getString(it) },
            listOf(SIZE_SMALL, SIZE_MEDIUM, SIZE_LARGE),
            onHomeChanged)
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

        focused?.let { container.getChildAt(it)?.requestFocus() }
    }

    private fun section(title: Int) {
        TvSettingHeaderBinding.inflate(inflater, container, true).root.setText(title)
    }

    private fun row(title: Int, value: String?, onClick: () -> Unit) {
        with(TvSettingRowBinding.inflate(inflater, container, true)) {
            this.title.setText(title)
            this.value.text = value
            root.setOnClickListener { onClick() }
        }
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

    private fun sectionsDialog() {
        val pages = TvViewModel.Page.roots
        val hidden = hiddenSections(activity)
        val checked = pages.map { it.key !in hidden }.toBooleanArray()
        activity.getAlertDialogBuilder()
            .setTitle(R.string.tv_sections)
            .setMultiChoiceItems(pages.map { activity.getString(it.title) }.toTypedArray(), checked) { _, which, isChecked -> checked[which] = isChecked }
            .setPositiveButton(android.R.string.ok) { _, _ ->
                prefs.edit { putStringSet(KEY_HIDDEN_SECTIONS, pages.filterIndexed { i, _ -> !checked[i] }.mapNotNull { it.key }.toSet()) }
                show()
                onHomeChanged()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    companion object {
        const val KEY_START_PAGE = "tv_start_page"
        const val KEY_HIDDEN_SECTIONS = "tv_hidden_sections"
        const val KEY_CARD_SIZE = "tv_card_size"
        const val KEY_START_ON_TV = "tv_start_on_tv"
        const val START_AUTO = "auto"
        const val SIZE_SMALL = "small"
        const val SIZE_MEDIUM = "medium"
        const val SIZE_LARGE = "large"

        fun isTvDevice(context: Context) = context.packageManager.hasSystemFeature(PackageManager.FEATURE_LEANBACK)

        /** Whether the app launcher should open the TV interface instead of Xtra's (on by default on TVs). */
        fun startOnTv(context: Context) = context.prefs().getBoolean(KEY_START_ON_TV, isTvDevice(context))

        fun hiddenSections(context: Context): Set<String> = context.prefs().getStringSet(KEY_HIDDEN_SECTIONS, null).orEmpty()

        /** Extra columns for the selected card size: small cards = more per row. */
        fun columnOffset(context: Context) = when (context.prefs().getString(KEY_CARD_SIZE, SIZE_MEDIUM)) {
            SIZE_SMALL -> 1
            SIZE_LARGE -> -1
            else -> 0
        }
    }
}
