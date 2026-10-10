package com.github.andreyasadchy.xtra.ui.tv

import android.content.Context
import androidx.core.content.edit
import com.github.andreyasadchy.xtra.model.ui.Stream
import com.github.andreyasadchy.xtra.model.ui.User
import com.github.andreyasadchy.xtra.model.ui.Video
import com.github.andreyasadchy.xtra.util.prefs
import org.json.JSONArray
import org.json.JSONObject

/** Channels pinned to the Favorites row (hold OK on a card), in the order they were added. */
object TvFavorites {

    private const val KEY = "tv_favorites"

    class Channel(val id: String, val login: String?, val name: String?, val image: String?)

    fun list(context: Context): List<Channel> {
        val array = JSONArray(context.prefs().getString(KEY, null) ?: "[]")
        return (0 until array.length()).map { array.getJSONObject(it) }.map {
            Channel(it.getString("id"), it.optString("login").ifEmpty { null }, it.optString("name").ifEmpty { null }, it.optString("image").ifEmpty { null })
        }
    }

    fun contains(context: Context, id: String) = list(context).any { it.id == id }

    /** Adds the channel, or removes it if it was already there. */
    fun toggle(context: Context, channel: Channel) {
        val list = list(context)
        val updated = if (list.any { it.id == channel.id }) list.filter { it.id != channel.id } else list + channel
        context.prefs().edit {
            putString(KEY, JSONArray(updated.map {
                JSONObject().put("id", it.id).put("login", it.login.orEmpty()).put("name", it.name.orEmpty()).put("image", it.image.orEmpty())
            }).toString())
        }
    }

    /** The channel behind a card, if it has one. */
    fun channelOf(item: Any): Channel? = when (item) {
        is Stream -> item.channelId?.let { Channel(it, item.channelLogin, item.channelName, item.channelImageURL) }
        is User -> item.id?.let { Channel(it, item.login, item.name, item.profileImageURL) }
        is Video -> item.channelId?.let { Channel(it, item.channelLogin, item.channelName, item.channelImageURL) }
        else -> null
    }
}
