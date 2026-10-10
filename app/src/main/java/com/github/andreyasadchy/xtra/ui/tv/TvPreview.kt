package com.github.andreyasadchy.xtra.ui.tv

import android.view.View
import androidx.appcompat.app.AppCompatActivity
import androidx.core.net.toUri
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import androidx.recyclerview.widget.RecyclerView
import com.github.andreyasadchy.xtra.XtraApp
import com.github.andreyasadchy.xtra.model.ui.CustomProxy
import com.github.andreyasadchy.xtra.model.ui.Stream
import com.github.andreyasadchy.xtra.util.C
import com.github.andreyasadchy.xtra.util.TwitchApiHelper
import com.github.andreyasadchy.xtra.util.prefs
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Plays the focused live stream (with sound, low quality) on top of its card after a short delay,
 * and zooms the card a little while it plays.
 * Uses Xtra's ad-blocking custom proxies in order; Twitch is only used directly when they are turned off,
 * so a preview never shows ads the proxies would have removed.
 */
class TvPreview(private val activity: AppCompatActivity, private val view: PlayerView) {

    private val repository get() = (activity.application as XtraApp).xtraModule.playerRepository
    private var player: ExoPlayer? = null
    private var job: Job? = null
    private var anchor: View? = null
    private var sources: List<String?> = emptyList()
    private var sourceIndex = 0
    private var channelLogin: String? = null

    fun schedule(stream: Stream, anchor: View) {
        stop()
        val login = stream.channelLogin ?: return
        if (!TvSettings.previewEnabled(activity)) return
        this.anchor = anchor
        job = activity.lifecycleScope.launch {
            delay(DELAY_MS)
            channelLogin = login
            sources = if (activity.prefs().getBoolean(C.PLAYER_USE_CUSTOM_PROXY, true)) {
                proxyUrls(login).ifEmpty { listOf(null) }
            } else {
                listOf(null) // null = Twitch directly
            }
            sourceIndex = 0
            // the video needs a laid out surface to render its first frame: show the view, still transparent
            view.alpha = 0f
            place(anchor)
            playCurrentSource()
        }
    }

    /** Stops the preview if it belongs to [anchor] (the card losing focus). */
    fun stopIf(anchor: View) {
        if (this.anchor == anchor) stop()
    }

    fun stop() {
        job?.cancel()
        job = null
        anchor?.let { card(it) }?.let { card ->
            if (card.hasFocus()) card.animate().scaleX(FOCUSED_SCALE).scaleY(FOCUSED_SCALE).setDuration(150).start()
        }
        anchor = null
        player?.run {
            stop()
            clearMediaItems()
        }
        view.animate().cancel()
        view.alpha = 0f
        view.visibility = View.GONE
    }

    fun release() {
        stop()
        player?.release()
        player = null
        view.player = null
    }

    private suspend fun playCurrentSource() {
        val login = channelLogin ?: return
        if (sourceIndex !in sources.indices) return stop()
        val url = sources[sourceIndex] ?: try {
            val prefs = activity.prefs()
            repository.loadStreamPlaylistUrl(
                context = activity,
                networkLibrary = prefs.getString(C.NETWORK_LIBRARY, C.OKHTTP),
                gqlHeaders = TwitchApiHelper.getGQLHeaders(activity, prefs.getBoolean(C.TOKEN_INCLUDE_TOKEN_STREAM, true)),
                channelLogin = login,
                platform = prefs.getString(C.TOKEN_PLATFORM, "web"),
                playerType = prefs.getString(C.TOKEN_PLAYER_TYPE, "site"),
                supportedCodecs = prefs.getString(C.TOKEN_SUPPORTED_CODECS, "av1,h265,h264"),
                proxyPlaybackAccessToken = false,
                proxyHost = null,
                proxyPort = null,
                proxyUser = null,
                proxyPassword = null,
                proxyTimeout = prefs.getString(C.PROXY_TIMEOUT, "3000")?.toIntOrNull() ?: 3000,
                enableIntegrity = prefs.getBoolean(C.ENABLE_INTEGRITY, false),
            )
        } catch (e: Exception) {
            return stop()
        }
        player().run {
            setMediaItem(MediaItem.Builder().setUri(url).setMimeType(MimeTypes.APPLICATION_M3U8).build())
            prepare()
            playWhenReady = true
        }
    }

    private fun player(): ExoPlayer = player ?: ExoPlayer.Builder(activity).build().also { player ->
        // a small preview does not need more, and keeps weak TV boxes and connections happy
        player.trackSelectionParameters = player.trackSelectionParameters.buildUpon().setMaxVideoSize(854, 480).build()
        player.addListener(object : Player.Listener {
            override fun onRenderedFirstFrame() {
                val anchor = anchor ?: return
                val card = card(anchor) ?: return
                // zoom the card a bit, then lay the video exactly over its picture
                card.animate().scaleX(PLAYING_SCALE).scaleY(PLAYING_SCALE).setDuration(150).withEndAction {
                    if (this@TvPreview.anchor == anchor) {
                        place(anchor)
                        view.animate().alpha(1f).setDuration(150).start()
                    }
                }.start()
            }

            override fun onPlayerError(error: PlaybackException) {
                // like Xtra's player: try the next proxy
                sourceIndex += 1
                job = activity.lifecycleScope.launch { playCurrentSource() }
            }
        })
        view.player = player
        this.player = player
    }

    /** The card (direct child of a RecyclerView) that contains [view]. */
    private fun card(view: View): View? {
        var v: View? = view
        while (v != null && v.parent !is RecyclerView) v = v.parent as? View
        return v
    }

    /** Puts the video exactly over [anchor], including the card's zoom. */
    private fun place(anchor: View) {
        val parent = view.parent as? View ?: return
        var scale = 1f
        var v: View? = anchor
        while (v != null && v != parent) {
            scale *= v.scaleX
            v = v.parent as? View
        }
        val anchorPos = IntArray(2).also { anchor.getLocationInWindow(it) }
        val parentPos = IntArray(2).also { parent.getLocationInWindow(it) }
        view.layoutParams = view.layoutParams.apply {
            width = (anchor.width * scale).toInt().coerceAtLeast(1)
            height = (anchor.height * scale).toInt().coerceAtLeast(1)
        }
        view.translationX = (anchorPos[0] - parentPos[0]).toFloat()
        view.translationY = (anchorPos[1] - parentPos[1]).toFloat()
        view.visibility = View.VISIBLE
    }

    private suspend fun proxyUrls(login: String): List<String> =
        repository.getCustomProxies()
            .filter { it.enabled && !it.url.isNullOrBlank() }
            .sortedBy { it.position }
            .map { proxyUrl(it, login) }

    /** Same URL building as Xtra's player, minus the options a preview does not need. */
    private fun proxyUrl(proxy: CustomProxy, login: String): String {
        val raw = proxy.url!!
        val uri = raw.toUri().takeIf { it.host != null } ?: "https://$raw".toUri()
        return (if (proxy.addQueryParams) {
            uri.buildUpon().apply {
                if (uri.getQueryParameter("allow_source") == null) appendQueryParameter("allow_source", "true")
                if (uri.getQueryParameter("fast_bread") == null) appendQueryParameter("fast_bread", "true")
                val codecs = activity.prefs().getString(C.TOKEN_SUPPORTED_CODECS, "av1,h265,h264")
                if (!codecs.isNullOrBlank() && uri.getQueryParameter("supported_codecs") == null) appendQueryParameter("supported_codecs", codecs)
            }.build()
        } else uri).toString().replace("\$channel", login)
    }

    companion object {
        private const val DELAY_MS = 1000L
        const val FOCUSED_SCALE = 1.06f
        private const val PLAYING_SCALE = 1.12f
    }
}
