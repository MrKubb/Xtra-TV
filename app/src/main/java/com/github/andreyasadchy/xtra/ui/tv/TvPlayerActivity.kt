package com.github.andreyasadchy.xtra.ui.tv

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.text.format.DateUtils
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.fragment.app.commit
import androidx.lifecycle.lifecycleScope
import coil3.imageLoader
import coil3.request.ImageRequest
import coil3.request.target
import coil3.request.transformations
import coil3.transform.CircleCropTransformation
import com.github.andreyasadchy.xtra.R
import com.github.andreyasadchy.xtra.XtraApp
import com.github.andreyasadchy.xtra.databinding.ActivityTvPlayerBinding
import com.github.andreyasadchy.xtra.model.PlaybackState
import com.github.andreyasadchy.xtra.model.VideoQuality
import com.github.andreyasadchy.xtra.model.ui.Stream
import com.github.andreyasadchy.xtra.model.ui.User
import com.github.andreyasadchy.xtra.ui.chat.ChatFragment
import com.github.andreyasadchy.xtra.ui.player.BasePlaybackService
import com.github.andreyasadchy.xtra.ui.player.ExoPlayerService
import com.github.andreyasadchy.xtra.util.C
import com.github.andreyasadchy.xtra.util.TwitchApiHelper
import com.github.andreyasadchy.xtra.util.getAlertDialogBuilder
import com.github.andreyasadchy.xtra.util.prefs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import kotlin.time.Clock
import kotlin.time.Instant

/**
 * Full screen live player of the TV interface, on top of Xtra's own playback service (ExoPlayerService):
 * same ad-blocking proxies, ad handling and qualities as Xtra's player, with a remote-friendly screen.
 *
 * - OK: info panel (channel, title, game, uptime, viewers, language, quality) with chat, move chat,
 *   favorite, quality, channel and reload buttons. Back closes the panel.
 * - The chat floats over the full screen video, in a corner or along a side; "Move chat" lets the
 *   arrows move it, OK or Back puts it down.
 * - Left/Up or Right/Down while the panel is closed: previous or next stream of the row it was opened from.
 * - Back: leave the player.
 */
class TvPlayerActivity : AppCompatActivity() {

    private lateinit var binding: ActivityTvPlayerBinding
    private val xtraModule get() = (application as XtraApp).xtraModule
    private var streams: List<Stream> = emptyList()
    /** Broadcast language per channel id (e.g. "FR"), loaded with the stream info. */
    private val languages = HashMap<String, String>()
    private var index = 0
    private val stream: Stream get() = streams[index]

    private var service: ExoPlayerService? = null
    private var connection: ServiceConnection? = null
    private var startJob: Job? = null
    private var infoJob: Job? = null

    private val handler = Handler(Looper.getMainLooper())
    private val hideOverlay = Runnable { showOverlay(false) }
    private val hideBanner = Runnable { binding.banner.visibility = View.GONE }
    private val tickUptime = object : Runnable {
        override fun run() {
            binding.uptime.text = uptime(stream.createdAt)
            handler.postDelayed(this, 1000)
        }
    }

    private val chatVisible: Boolean
        get() = prefs().getBoolean(KEY_CHAT, false)

    /** Chat place (left or right side; top, middle or bottom) and size (width and height steps). */
    private var chatRight = true
    private var chatVertical = CHAT_BOTTOM
    private var chatWidth = 1
    private var chatHeight = CHAT_HEIGHTS.lastIndex
    /** Arrows move ([MODE_MOVE]) or resize ([MODE_RESIZE]) the chat instead of zapping. */
    private var chatMode = MODE_NONE

    private val serviceListener = object : ExoPlayerService.Listener {
        override fun started() {}
        override fun loaded() = updateQuality()
        override fun changePlayerMode() = updateQuality()
        override fun toast(resId: Int, duration: Int) = Toast.makeText(this@TvPlayerActivity, resId, duration).show()
        override fun toast(text: CharSequence, duration: Int) = Toast.makeText(this@TvPlayerActivity, text, duration).show()

        override fun updateVideoInfo() {
            service?.let { service ->
                service.title?.let { stream.title = it }
                service.gameName?.let { stream.gameName = it }
                updateInfo()
            }
        }

        override fun setPlayerSurface(secondaryPlayer: Boolean) {
            binding.playerView.player = if (secondaryPlayer) service?.secondaryPlayer else service?.player
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityTvPlayerBinding.inflate(layoutInflater)
        setContentView(binding.root)
        WindowCompat.getInsetsController(window, window.decorView).apply {
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            hide(WindowInsetsCompat.Type.systemBars())
        }
        streams = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableArrayListExtra(KEY_STREAMS, Stream::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableArrayListExtra(KEY_STREAMS)
        }.orEmpty()
        if (streams.isEmpty()) {
            finish()
            return
        }
        index = intent.getIntExtra(KEY_INDEX, 0).coerceIn(streams.indices)

        // the chat is read-only on a TV: hide its message bar whenever Xtra's chat shows it
        binding.chatContainer.viewTreeObserver.addOnGlobalLayoutListener {
            binding.chatContainer.findViewById<View>(R.id.messageView)?.takeIf { it.visibility != View.GONE }?.visibility = View.GONE
        }
        chatRight = prefs().getBoolean(KEY_CHAT_RIGHT, true)
        chatVertical = prefs().getInt(KEY_CHAT_VERTICAL, CHAT_BOTTOM).coerceIn(CHAT_TOP, CHAT_BOTTOM)
        chatWidth = prefs().getInt(KEY_CHAT_WIDTH, 1).coerceIn(CHAT_WIDTHS.indices)
        chatHeight = prefs().getInt(KEY_CHAT_HEIGHT, CHAT_HEIGHTS.lastIndex).coerceIn(CHAT_HEIGHTS.indices)
        placeChat()
        with(binding) {
            btnChat.setOnClickListener {
                prefs().edit().putBoolean(KEY_CHAT, !chatVisible).apply()
                updateChat()
                updateButtons()
            }
            btnMoveChat.setOnClickListener { startChatMode(MODE_MOVE) }
            btnResizeChat.setOnClickListener { startChatMode(MODE_RESIZE) }
            // icon-only buttons: the focused one says what it does
            listOf(btnChat, btnMoveChat, btnResizeChat, btnFavorite, btnQuality, btnChannel, btnReload).forEach { button ->
                button.setOnFocusChangeListener { v, hasFocus -> if (hasFocus) buttonLabel.text = v.contentDescription }
            }
            btnFavorite.setOnClickListener {
                TvFavorites.channelOf(stream)?.let { TvFavorites.toggle(this@TvPlayerActivity, it) }
                updateButtons()
            }
            btnQuality.setOnClickListener { showQualities() }
            btnChannel.setOnClickListener {
                setResult(RESULT_OK, Intent().putExtra(KEY_OPEN_CHANNEL, User(
                    id = stream.channelId, login = stream.channelLogin, name = stream.channelName, profileImageURL = stream.channelImageURL, isLive = true,
                )))
                finish()
            }
            btnReload.setOnClickListener {
                showOverlay(false)
                play(index)
            }
        }
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (binding.overlay.visibility == View.VISIBLE) showOverlay(false) else finish()
            }
        })
        play(index)
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (chatMode != MODE_NONE) {
            if (event.action == KeyEvent.ACTION_DOWN) {
                if (chatMode == MODE_MOVE) {
                    when (event.keyCode) {
                        KeyEvent.KEYCODE_DPAD_LEFT -> chatRight = false
                        KeyEvent.KEYCODE_DPAD_RIGHT -> chatRight = true
                        KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN -> {
                            // a full height chat cannot go up or down: make it shorter first
                            if (chatHeight == CHAT_HEIGHTS.lastIndex) chatHeight = CHAT_HEIGHTS.lastIndex - 1
                            chatVertical = (chatVertical + if (event.keyCode == KeyEvent.KEYCODE_DPAD_UP) -1 else 1).coerceIn(CHAT_TOP, CHAT_BOTTOM)
                        }
                    }
                } else {
                    when (event.keyCode) {
                        KeyEvent.KEYCODE_DPAD_LEFT -> chatWidth = (chatWidth - 1).coerceAtLeast(0)
                        KeyEvent.KEYCODE_DPAD_RIGHT -> chatWidth = (chatWidth + 1).coerceAtMost(CHAT_WIDTHS.lastIndex)
                        KeyEvent.KEYCODE_DPAD_UP -> chatHeight = (chatHeight + 1).coerceAtMost(CHAT_HEIGHTS.lastIndex)
                        KeyEvent.KEYCODE_DPAD_DOWN -> chatHeight = (chatHeight - 1).coerceAtLeast(0)
                    }
                }
                placeChat()
            } else if (event.action == KeyEvent.ACTION_UP && event.keyCode in DONE_KEYS) {
                stopChatMode()
            }
            return true
        }
        if (event.action == KeyEvent.ACTION_DOWN) {
            if (binding.overlay.visibility != View.VISIBLE) {
                when (event.keyCode) {
                    KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER,
                    KeyEvent.KEYCODE_MENU, KeyEvent.KEYCODE_INFO -> {
                        showOverlay(true)
                        return true
                    }
                    KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_CHANNEL_DOWN -> {
                        zap(-1)
                        return true
                    }
                    KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_CHANNEL_UP -> {
                        zap(1)
                        return true
                    }
                }
            } else {
                // any key keeps the panel open a bit longer
                handler.removeCallbacks(hideOverlay)
                handler.postDelayed(hideOverlay, OVERLAY_TIMEOUT_MS)
            }
        }
        return super.dispatchKeyEvent(event)
    }

    override fun onStop() {
        super.onStop()
        // leaving the player (Home, another app) stops the stream: no background playback on a TV
        if (!isChangingConfigurations) {
            closeService()
            finish()
        }
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        closeService()
        super.onDestroy()
    }

    /** Previous (-1) or next (+1) stream of the row; at the ends, just shows the current one again. */
    private fun zap(delta: Int) {
        val next = index + delta
        if (next in streams.indices) play(next) else showBanner()
    }

    private fun play(position: Int) {
        val wasPlaying = connection != null
        index = position
        val stream = stream
        TvSettings.setLastChannel(this, stream.channelLogin, stream.channelName)
        updateInfo()
        refreshInfo()
        updateButtons()
        updateChat()
        showBanner()
        closeService()
        startJob?.cancel()
        startJob = lifecycleScope.launch {
            // let the previous playback service go away before starting the next one
            if (wasPlaying) delay(SWITCH_DELAY_MS)
            xtraModule.playerRepository.savePlaybackStates(listOf(PlaybackState(
                type = BasePlaybackService.STREAM,
                streamId = stream.id,
                channelId = stream.channelId,
                channelLogin = stream.channelLogin,
                channelName = stream.channelName,
                channelImage = stream.channelImage,
                gameId = stream.gameId,
                gameSlug = stream.gameSlug,
                gameName = stream.gameName,
                title = stream.title,
                thumbnail = stream.thumbnail,
                createdAt = stream.createdAt,
                viewerCount = stream.viewerCount,
            )))
            startService()
        }
    }

    /** Starts and binds Xtra's playback service, which reads the stream saved just before. */
    private fun startService() {
        val intent = Intent(this, ExoPlayerService::class.java).setAction(ExoPlayerService.INTENT_START)
        val connection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
                val service = (binder as ExoPlayerService.ServiceBinder).getService()
                this@TvPlayerActivity.service = service
                service.serviceListener = serviceListener
                service.setStopServiceTimer(false)
                binding.playerView.player = service.secondaryPlayer ?: service.player
                if (service.started) updateQuality()
            }

            override fun onServiceDisconnected(name: ComponentName?) {
                service = null
            }
        }
        startService(intent)
        bindService(intent, connection, Context.BIND_AUTO_CREATE)
        this.connection = connection
    }

    /** Same as closing Xtra's player: stop, unbind and stop the service. */
    private fun closeService() {
        service?.let {
            it.pause()
            it.stop()
            it.serviceListener = null
            it.stopSelf()
        }
        connection?.let { unbindService(it) }
        connection = null
        service = null
        binding.playerView.player = null
    }

    private fun showOverlay(show: Boolean) {
        handler.removeCallbacks(hideOverlay)
        handler.removeCallbacks(tickUptime)
        if (show) {
            binding.banner.visibility = View.GONE
            // the panel takes the bottom of the screen: the chat steps aside while it is open
            binding.chatWindow.visibility = View.GONE
            binding.overlay.visibility = View.VISIBLE
            binding.btnChat.requestFocus()
            handler.post(tickUptime)
            handler.postDelayed(hideOverlay, OVERLAY_TIMEOUT_MS)
            refreshInfo()
        } else {
            binding.overlay.visibility = View.GONE
            if (chatVisible) binding.chatWindow.visibility = View.VISIBLE
            binding.root.requestFocus()
        }
    }

    private fun showBanner() {
        val stream = stream
        with(binding) {
            bannerName.text = stream.channelName ?: stream.channelLogin
            bannerTitle.text = listOfNotNull(stream.gameName, stream.title).joinToString("  ·  ")
            bannerPosition.text = if (streams.size > 1) getString(R.string.tv_player_position, index + 1, streams.size) else null
            bannerPosition.visibility = if (streams.size > 1) View.VISIBLE else View.GONE
            loadAvatar(stream.channelImage, bannerAvatar)
            if (overlay.visibility != View.VISIBLE) banner.visibility = View.VISIBLE
        }
        handler.removeCallbacks(hideBanner)
        handler.postDelayed(hideBanner, BANNER_TIMEOUT_MS)
    }

    private fun updateInfo() {
        val stream = stream
        with(binding) {
            channelName.text = stream.channelName ?: stream.channelLogin
            game.text = stream.gameName
            game.visibility = if (stream.gameName.isNullOrBlank()) View.GONE else View.VISIBLE
            title.text = stream.title
            uptime.text = uptime(stream.createdAt)
            viewers.text = stream.viewerCount?.let { resources.getQuantityString(R.plurals.viewers, it, TwitchApiHelper.formatCount(it, false)) }
            viewers.visibility = if (stream.viewerCount != null) View.VISIBLE else View.GONE
            val lang = stream.channelId?.let { languages[it] }
            language.text = lang?.let { TvLanguages.name(this@TvPlayerActivity, it) }
            language.visibility = if (lang != null) View.VISIBLE else View.GONE
            loadAvatar(stream.channelImage, avatar)
        }
        updateQuality()
    }

    /** Fresh title, game, viewers, start time and language when the panel opens or the stream changes. */
    private fun refreshInfo() {
        val stream = stream
        val id = stream.channelId ?: return
        infoJob?.cancel()
        infoJob = lifecycleScope.launch {
            val user = withContext(Dispatchers.IO) {
                try {
                    val query = "query { users(ids: [\"$id\"]) { broadcastSettings { language } " +
                            "stream { createdAt viewersCount game { displayName } broadcaster { broadcastSettings { title } } } } }"
                    val request = Request.Builder()
                        .url("https://gql.twitch.tv/gql")
                        .apply { TwitchApiHelper.getGQLHeaders(this@TvPlayerActivity).forEach { (key, value) -> header(key, value) } }
                        .post(JSONObject().put("query", query).toString().toRequestBody("application/json".toMediaType()))
                        .build()
                    xtraModule.okHttpClient.value.newCall(request).execute().use { response ->
                        JSONObject(response.body.string()).getJSONObject("data").getJSONArray("users").optJSONObject(0)
                    }
                } catch (e: Exception) {
                    null
                }
            } ?: return@launch
            if (this@TvPlayerActivity.stream !== stream) return@launch
            user.optJSONObject("broadcastSettings")?.optString("language")?.takeIf { it.isNotBlank() }?.let { languages[id] = it }
            user.optJSONObject("stream")?.let { live ->
                live.optJSONObject("broadcaster")?.optJSONObject("broadcastSettings")?.optString("title")?.takeIf { it.isNotBlank() }?.let { stream.title = it }
                live.optJSONObject("game")?.optString("displayName")?.takeIf { it.isNotBlank() }?.let { stream.gameName = it }
                live.optInt("viewersCount", -1).takeIf { it >= 0 }?.let { stream.viewerCount = it }
                live.optString("createdAt").takeIf { it.isNotBlank() }?.let { stream.createdAt = it }
            }
            updateInfo()
        }
    }

    /** Chosen quality, with the resolution actually played (e.g. "Auto · 720p60"). */
    private fun updateQuality() {
        val format = (service?.secondaryPlayer ?: service?.player)?.videoFormat
        val playing = format?.height?.takeIf { it > 0 }?.let { height ->
            "${height}p" + (format.frameRate.takeIf { it > 0 }?.let { Math.round(it).toString() } ?: "")
        }
        val chosen = service?.quality?.let { qualityName(it) }
        val text = when {
            chosen == null -> playing
            playing == null || chosen == playing -> chosen
            else -> "$chosen · $playing"
        }
        binding.qualityLabel.text = text
        binding.qualityLabel.visibility = if (text != null) View.VISIBLE else View.GONE
    }

    private fun updateButtons() {
        val favorite = stream.channelId?.let { TvFavorites.contains(this, it) } == true
        with(binding) {
            btnFavorite.contentDescription = getString(if (favorite) R.string.tv_remove_favorite else R.string.tv_add_favorite)
            btnFavorite.setImageResource(if (favorite) R.drawable.baseline_favorite_black_24 else R.drawable.baseline_favorite_border_black_24)
            btnChat.contentDescription = getString(if (chatVisible) R.string.tv_player_hide_chat else R.string.tv_player_show_chat)
            // crossed out icon while the chat is hidden
            btnChat.setImageResource(if (chatVisible) R.drawable.baseline_speaker_notes_black_24 else R.drawable.baseline_speaker_notes_off_black_24)
            // refresh the label of the focused button (e.g. after toggling it)
            root.findFocus()?.contentDescription?.let { buttonLabel.text = it }
        }
    }

    /** Arrows now move or resize the chat (shown if it was hidden), OK or Back puts it down. */
    private fun startChatMode(mode: Int) {
        if (!chatVisible) {
            prefs().edit().putBoolean(KEY_CHAT, true).apply()
            updateChat()
            updateButtons()
        }
        showOverlay(false)
        chatMode = mode
        binding.chatWindow.isActivated = true
        binding.chatMoveHint.setText(if (mode == MODE_MOVE) R.string.tv_chat_move_hint else R.string.tv_chat_resize_hint)
        binding.chatMoveHint.visibility = View.VISIBLE
    }

    private fun stopChatMode() {
        chatMode = MODE_NONE
        binding.chatWindow.isActivated = false
        binding.chatMoveHint.visibility = View.GONE
        prefs().edit()
            .putBoolean(KEY_CHAT_RIGHT, chatRight)
            .putInt(KEY_CHAT_VERTICAL, chatVertical)
            .putInt(KEY_CHAT_WIDTH, chatWidth)
            .putInt(KEY_CHAT_HEIGHT, chatHeight)
            .apply()
    }

    /** Places and sizes the floating chat over the video. */
    private fun placeChat() {
        val params = binding.chatWindow.layoutParams as android.widget.FrameLayout.LayoutParams
        val screen = resources.displayMetrics
        val fullHeight = chatHeight == CHAT_HEIGHTS.lastIndex
        params.gravity = (if (chatRight) Gravity.END else Gravity.START) or when {
            fullHeight -> Gravity.CENTER_VERTICAL
            chatVertical == CHAT_TOP -> Gravity.TOP
            chatVertical == CHAT_BOTTOM -> Gravity.BOTTOM
            else -> Gravity.CENTER_VERTICAL
        }
        params.width = (screen.widthPixels * CHAT_WIDTHS[chatWidth]).toInt()
        params.height = if (fullHeight) ViewGroup.LayoutParams.MATCH_PARENT else (screen.heightPixels * CHAT_HEIGHTS[chatHeight]).toInt()
        binding.chatWindow.layoutParams = params
    }

    /** Shows Xtra's live chat of the current channel over the video, or removes it. */
    private fun updateChat() {
        val stream = stream
        if (chatVisible) {
            if (binding.overlay.visibility != View.VISIBLE) binding.chatWindow.visibility = View.VISIBLE
            val current = supportFragmentManager.findFragmentById(R.id.chatContainer)
            if (current == null || current.tag != stream.channelId) {
                supportFragmentManager.commit {
                    replace(R.id.chatContainer, ChatFragment.newInstance(stream.channelId, stream.channelLogin, stream.channelName, stream.id), stream.channelId)
                }
            }
        } else {
            binding.chatWindow.visibility = View.GONE
            supportFragmentManager.findFragmentById(R.id.chatContainer)?.let { supportFragmentManager.commit { remove(it) } }
        }
    }

    private fun showQualities() {
        val service = service ?: return
        val qualities = service.qualities?.filter { it.name != VideoQuality.CHAT_ONLY_QUALITY }.orEmpty()
        if (qualities.isEmpty()) return
        val current = qualities.indexOfFirst { it.name == service.quality?.name }
        getAlertDialogBuilder()
            .setTitle(R.string.tv_player_quality)
            .setSingleChoiceItems(qualities.map { qualityName(it) }.toTypedArray(), current) { dialog, which ->
                dialog.dismiss()
                service.changeQuality(qualities[which])
                updateQuality()
            }
            .show()
    }

    private fun qualityName(quality: VideoQuality): String = when (quality.name) {
        VideoQuality.AUTO_QUALITY -> getString(R.string.auto)
        VideoQuality.SOURCE_QUALITY -> getString(R.string.source)
        VideoQuality.AUDIO_ONLY_QUALITY -> getString(R.string.audio_only)
        VideoQuality.CHAT_ONLY_QUALITY -> getString(R.string.chat_only)
        else -> quality.name.orEmpty()
    }

    private fun uptime(createdAt: String?): String? =
        createdAt?.let { Instant.parseOrNull(it) }?.takeIf { it.toEpochMilliseconds() > 0 }?.let { start ->
            (Clock.System.now() - start).takeIf { it.isPositive() }?.let { DateUtils.formatElapsedTime(it.inWholeSeconds) }
        }

    private fun loadAvatar(url: String?, view: android.widget.ImageView) {
        view.setImageDrawable(null)
        url ?: return
        imageLoader.enqueue(ImageRequest.Builder(this).data(url).transformations(CircleCropTransformation()).target(view).build())
    }

    companion object {
        const val KEY_STREAMS = "streams"
        const val KEY_INDEX = "index"
        const val KEY_OPEN_CHANNEL = "openChannel"
        private const val KEY_CHAT = "tv_player_chat"
        private const val KEY_CHAT_RIGHT = "tv_player_chat_right"
        private const val KEY_CHAT_VERTICAL = "tv_player_chat_place"
        private const val KEY_CHAT_WIDTH = "tv_player_chat_width"
        private const val KEY_CHAT_HEIGHT = "tv_player_chat_height"
        private const val CHAT_TOP = 0
        private const val CHAT_BOTTOM = 2
        /** Width steps (part of the screen width) and height steps (part of the screen height, last = full). */
        private val CHAT_WIDTHS = floatArrayOf(0.2f, 0.27f, 0.35f)
        private val CHAT_HEIGHTS = floatArrayOf(0.35f, 0.55f, 1f)
        private const val MODE_NONE = 0
        private const val MODE_MOVE = 1
        private const val MODE_RESIZE = 2
        private val DONE_KEYS = setOf(KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER, KeyEvent.KEYCODE_BACK)
        private const val OVERLAY_TIMEOUT_MS = 8000L
        private const val BANNER_TIMEOUT_MS = 4000L
        private const val SWITCH_DELAY_MS = 300L
    }
}
