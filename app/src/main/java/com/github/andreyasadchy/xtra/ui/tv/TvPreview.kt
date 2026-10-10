package com.github.andreyasadchy.xtra.ui.tv

import android.view.View
import android.view.ViewGroup
import androidx.appcompat.app.AppCompatActivity
import androidx.core.net.toUri
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import coil3.imageLoader
import coil3.request.ImageRequest
import coil3.request.target
import coil3.request.transformations
import coil3.transform.CircleCropTransformation
import com.github.andreyasadchy.xtra.XtraApp
import com.github.andreyasadchy.xtra.databinding.ActivityTvBinding
import com.github.andreyasadchy.xtra.model.ui.CustomProxy
import com.github.andreyasadchy.xtra.model.ui.Stream
import com.github.andreyasadchy.xtra.util.C
import com.github.andreyasadchy.xtra.util.TwitchApiHelper
import com.github.andreyasadchy.xtra.util.prefs
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Live preview of the focused stream (with sound), after a short delay: a floating copy of the card,
 * [SCALE] times larger, above everything (so it is never cut by the lists), kept inside the screen.
 * Uses Xtra's ad-blocking custom proxies in order; Twitch is only used directly when they are turned off,
 * so a preview never shows ads the proxies would have removed.
 */
class TvPreview(private val activity: AppCompatActivity, private val binding: ActivityTvBinding) {

    private val repository get() = (activity.application as XtraApp).xtraModule.playerRepository
    private val popup get() = binding.previewPopup
    private var player: ExoPlayer? = null
    private var job: Job? = null
    private var anchor: View? = null
    private var sources: List<String?> = emptyList()
    private var sourceIndex = 0
    private var channelLogin: String? = null

    fun schedule(card: TvCard, stream: Stream, anchor: View) {
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
            fill(card)
            // the video needs a laid out surface to render its first frame: place the popup, still transparent
            popup.alpha = 0f
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
        anchor = null
        player?.run {
            stop()
            clearMediaItems()
        }
        popup.animate().cancel()
        popup.alpha = 0f
        popup.visibility = View.GONE
    }

    fun release() {
        stop()
        player?.release()
        player = null
        binding.previewView.player = null
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
        // enough for a preview, and easy on weak TV boxes and connections
        player.trackSelectionParameters = player.trackSelectionParameters.buildUpon().setMaxVideoSize(1280, 720).build()
        player.addListener(object : Player.Listener {
            override fun onRenderedFirstFrame() {
                val anchor = anchor ?: return
                // grow out of the card
                place(anchor)
                val startScale = anchor.width * scaleOf(anchor) / popup.layoutParams.width.coerceAtLeast(1)
                popup.scaleX = startScale
                popup.scaleY = startScale
                popup.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(200).start()
            }

            override fun onPlayerError(error: PlaybackException) {
                // like Xtra's player: try the next proxy
                sourceIndex += 1
                job = activity.lifecycleScope.launch { playCurrentSource() }
            }
        })
        binding.previewView.player = player
        this.player = player
    }

    private fun fill(card: TvCard) {
        with(binding) {
            previewLine1.text = card.line1
            previewLine2.text = card.line2
            previewLine2.visibility = if (card.line2.isNullOrBlank()) View.GONE else View.VISIBLE
            previewLine3.text = card.line3
            previewLine3.visibility = if (card.line3.isNullOrBlank()) View.GONE else View.VISIBLE
            previewAvatar.setImageDrawable(null)
            previewAvatar.visibility = if (card.avatar != null) View.VISIBLE else View.GONE
            card.avatar?.let {
                activity.imageLoader.enqueue(ImageRequest.Builder(activity).data(it).transformations(CircleCropTransformation()).target(previewAvatar).build())
            }
        }
    }

    /** Zoom of [view] on screen, including the focused card's own zoom. */
    private fun scaleOf(view: View): Float {
        var scale = 1f
        var v: View? = view
        while (v != null) {
            scale *= v.scaleX
            v = v.parent as? View
        }
        return scale
    }

    /**
     * Centers the popup on the card's picture, [SCALE] times larger, then moves it back inside the screen
     * if the card is near an edge.
     */
    private fun place(anchor: View) {
        val root = popup.parent as? ViewGroup ?: return
        val scale = scaleOf(anchor)
        val anchorWidth = anchor.width * scale
        val anchorHeight = anchor.height * scale
        val anchorPos = IntArray(2).also { anchor.getLocationInWindow(it) }
        val rootPos = IntArray(2).also { root.getLocationInWindow(it) }
        val width = (anchorWidth * SCALE).toInt()
        val videoHeight = width * 9 / 16
        binding.previewView.layoutParams = binding.previewView.layoutParams.apply { height = videoHeight }
        popup.layoutParams = popup.layoutParams.apply { this.width = width }
        popup.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
        val height = popup.measuredHeight
        val margin = 16 * activity.resources.displayMetrics.density
        val left = (anchorPos[0] - rootPos[0] + anchorWidth / 2 - width / 2f)
            .coerceIn(margin, (root.width - width - margin).coerceAtLeast(margin))
        val top = (anchorPos[1] - rootPos[1] + anchorHeight / 2 - videoHeight / 2f)
            .coerceIn(margin, (root.height - height - margin).coerceAtLeast(margin))
        popup.pivotX = (anchorPos[0] - rootPos[0] + anchorWidth / 2 - left).coerceIn(0f, width.toFloat())
        popup.pivotY = (anchorPos[1] - rootPos[1] + anchorHeight / 2 - top).coerceIn(0f, height.toFloat())
        popup.translationX = left
        popup.translationY = top
        popup.visibility = View.VISIBLE
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
        private const val SCALE = 1.5f
    }
}
