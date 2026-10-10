package com.github.andreyasadchy.xtra.ui.tv

import android.content.Context
import androidx.appcompat.app.AlertDialog
import com.github.andreyasadchy.xtra.R
import com.github.andreyasadchy.xtra.util.getAlertDialogBuilder
import java.util.Locale

/** Twitch stream languages, as listed in Xtra's own language filter. */
object TvLanguages {

    private fun values(context: Context) = context.resources.getStringArray(R.array.gqlUserLanguageValues).toList()
    private fun names(context: Context) = context.resources.getStringArray(R.array.gqlUserLanguageEntries).toList()

    fun name(context: Context, code: String): String = names(context).getOrNull(values(context).indexOf(code)) ?: code.uppercase()

    /**
     * Multi-choice list that applies every change right away (no OK button to reach at the bottom).
     * "All languages" comes first, then the selected ones, then the TV's language, then the rest.
     */
    fun showDialog(context: Context, title: String, selected: Collection<String>, onChange: (List<String>) -> Unit) {
        val values = values(context)
        val order = (selected + Locale.getDefault().language + values).distinct().filter { it in values }
        val labels = listOf(context.getString(R.string.tv_all_languages)) + order.map { name(context, it) }
        val current = selected.filter { it in values }.toMutableList()
        val checked = BooleanArray(labels.size) { if (it == 0) current.isEmpty() else order[it - 1] in current }
        lateinit var dialog: AlertDialog
        dialog = context.getAlertDialogBuilder()
            .setTitle(title)
            .setMultiChoiceItems(labels.toTypedArray(), checked) { _, which, isChecked ->
                if (which == 0) {
                    current.clear()
                    for (i in 1 until labels.size) dialog.listView.setItemChecked(i, false)
                } else {
                    val code = order[which - 1]
                    if (isChecked) current += code else current -= code
                }
                dialog.listView.setItemChecked(0, current.isEmpty())
                onChange(current.toList())
            }
            .show()
    }
}
