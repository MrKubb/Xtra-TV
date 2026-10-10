package com.github.andreyasadchy.xtra.ui.tv

import android.content.Intent
import android.graphics.Rect
import android.os.Bundle
import android.os.Parcelable
import android.text.format.DateUtils
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.core.content.edit
import androidx.core.net.toUri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.Companion.APPLICATION_KEY
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.paging.LoadState
import androidx.paging.Pager
import androidx.paging.PagingConfig
import androidx.paging.PagingData
import androidx.paging.PagingDataAdapter
import androidx.paging.PagingSource
import androidx.paging.cachedIn
import androidx.paging.map
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import coil3.imageLoader
import coil3.request.ImageRequest
import coil3.request.target
import coil3.request.transformations
import coil3.transform.CircleCropTransformation
import com.github.andreyasadchy.xtra.R
import com.github.andreyasadchy.xtra.XtraApp
import com.github.andreyasadchy.xtra.databinding.ActivityTvBinding
import com.github.andreyasadchy.xtra.databinding.TvCardBinding
import com.github.andreyasadchy.xtra.graphql.type.Language
import com.github.andreyasadchy.xtra.graphql.type.StreamSort
import com.github.andreyasadchy.xtra.graphql.type.VideoSort
import com.github.andreyasadchy.xtra.model.ui.Game
import com.github.andreyasadchy.xtra.model.ui.Stream
import com.github.andreyasadchy.xtra.model.ui.User
import com.github.andreyasadchy.xtra.model.ui.Video
import com.github.andreyasadchy.xtra.repository.datasource.ChannelVideosDataSource
import com.github.andreyasadchy.xtra.repository.datasource.FollowedChannelsDataSource
import com.github.andreyasadchy.xtra.repository.datasource.FollowedGamesDataSource
import com.github.andreyasadchy.xtra.repository.datasource.FollowedStreamsDataSource
import com.github.andreyasadchy.xtra.repository.datasource.FollowedVideosDataSource
import com.github.andreyasadchy.xtra.repository.datasource.GameStreamsDataSource
import com.github.andreyasadchy.xtra.repository.datasource.GamesDataSource
import com.github.andreyasadchy.xtra.repository.datasource.SearchChannelsDataSource
import com.github.andreyasadchy.xtra.repository.datasource.SearchGamesDataSource
import com.github.andreyasadchy.xtra.repository.datasource.SearchStreamsDataSource
import com.github.andreyasadchy.xtra.repository.datasource.StreamsDataSource
import com.github.andreyasadchy.xtra.ui.main.MainActivity
import com.github.andreyasadchy.xtra.ui.settings.SettingsActivity
import com.github.andreyasadchy.xtra.util.C
import com.github.andreyasadchy.xtra.util.TwitchApiHelper
import com.github.andreyasadchy.xtra.util.applyTheme
import com.github.andreyasadchy.xtra.util.getAlertDialogBuilder
import com.github.andreyasadchy.xtra.util.prefs
import com.github.andreyasadchy.xtra.util.tokenPrefs
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/** Remote-friendly home screen for Android TV. Reuses Xtra's data sources, player, login and settings. */
class TvActivity : AppCompatActivity() {

    private lateinit var binding: ActivityTvBinding
    private val viewModel: TvViewModel by viewModels { TvViewModel.Factory }
    private val adapter = TvCardAdapter { open(it) }
    private lateinit var layoutManager: GridLayoutManager
    private lateinit var tabs: Map<View, TvViewModel.Page>

    /** Scroll position + focused card per page, restored when coming back to it. */
    private val savedPositions = HashMap<TvViewModel.Page, Pair<Parcelable?, Int>>()
    private var pendingRestore: Pair<Parcelable?, Int>? = null

    /** Login, logout and settings change tokens/prefs: restart to reload everything. */
    private val restartLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        finish()
        startActivity(Intent(this, TvActivity::class.java))
    }

    /** Xtra's settings ask for a recreate when something visible changed. */
    private val settingsLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        if (it.resultCode == RESULT_OK) recreate()
    }

    private lateinit var settings: TvSettings

    private val isLoggedIn: Boolean
        get() = !TwitchApiHelper.getGQLHeaders(this, true)[C.HEADER_TOKEN].isNullOrBlank() ||
                !TwitchApiHelper.getHelixHeaders(this)[C.HEADER_TOKEN].isNullOrBlank()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        applyTheme()
        binding = ActivityTvBinding.inflate(layoutInflater)
        setContentView(binding.root)
        with(binding) {
            layoutManager = object : GridLayoutManager(this@TvActivity, 4) {
                // Keep the focused row centered (like Leanback grids) instead of scrolling it just inside the edge.
                override fun requestChildRectangleOnScreen(parent: RecyclerView, child: View, rect: Rect, immediate: Boolean, focusedChildVisible: Boolean): Boolean {
                    val dy = child.top + child.height / 2 - parent.height / 2
                    if (immediate) parent.scrollBy(0, dy) else parent.smoothScrollBy(0, dy)
                    return true
                }

                // DOWN on the last row stays there instead of jumping out of the grid (e.g. into the sidebar).
                override fun onInterceptFocusSearch(focused: View, direction: Int): View? {
                    if (direction != View.FOCUS_DOWN || focused.layoutParams !is RecyclerView.LayoutParams) return null
                    return focused.takeIf { getPosition(it) / spanCount == (itemCount - 1) / spanCount }
                }
            }
            grid.layoutManager = layoutManager
            grid.adapter = adapter
            grid.itemAnimator = null

            tabs = mapOf(
                tabSearch to TvViewModel.Page.Search("", TvViewModel.SearchType.STREAMS),
                tabFollowing to TvViewModel.Page.Following,
                tabFollowedGames to TvViewModel.Page.FollowedGames,
                tabFollowedVideos to TvViewModel.Page.FollowedVideos,
                tabFollowedChannels to TvViewModel.Page.FollowedChannels,
                tabTopGames to TvViewModel.Page.TopGames,
                tabTopStreams to TvViewModel.Page.TopStreams,
                tabSettings to TvViewModel.Page.Settings,
            )
            settings = TvSettings(this@TvActivity, settingsList, onHomeChanged = ::applyHomeSettings) {
                settingsLauncher.launch(Intent(this@TvActivity, SettingsActivity::class.java))
            }
            applyHomeSettings()
            tabs.forEach { (view, page) ->
                view.setOnClickListener {
                    savedPositions.clear()
                    pendingRestore = null
                    viewModel.stack.value = listOf(page)
                    if (page is TvViewModel.Page.Search) searchInput.requestFocus()
                }
            }
            tabAccount.text = if (isLoggedIn) {
                tokenPrefs().getString(C.USERNAME, null) ?: getString(R.string.log_out)
            } else {
                getString(R.string.log_in)
            }
            tabAccount.setOnClickListener {
                if (isLoggedIn) {
                    getAlertDialogBuilder()
                        .setTitle(R.string.logout_title)
                        .setMessage(getString(R.string.logout_msg, tokenPrefs().getString(C.USERNAME, null)))
                        .setNegativeButton(R.string.no, null)
                        .setPositiveButton(R.string.yes) { _, _ ->
                            restartLauncher.launch(Intent(this@TvActivity, TvLoginActivity::class.java).putExtra(TvLoginActivity.EXTRA_LOGOUT, true))
                        }
                        .show()
                } else {
                    restartLauncher.launch(Intent(this@TvActivity, TvLoginActivity::class.java))
                }
            }

            btnLanguage.setOnClickListener { showLanguageDialog() }
            btnLive.setOnClickListener {
                (viewModel.stack.value.last() as? TvViewModel.Page.Channel)?.user?.let { user ->
                    play(Stream(channelId = user.id, channelLogin = user.login, channelName = user.name, channelImageURL = user.profileImageURL))
                }
            }
            searchInput.setOnEditorActionListener { v, actionId, event ->
                val submit = actionId == EditorInfo.IME_ACTION_SEARCH || event?.keyCode == KeyEvent.KEYCODE_ENTER
                if (submit) {
                    val type = (viewModel.stack.value.last() as? TvViewModel.Page.Search)?.type ?: TvViewModel.SearchType.STREAMS
                    viewModel.stack.value = listOf(TvViewModel.Page.Search(v.text.toString().trim(), type))
                    pendingRestore = null to 0
                }
                submit
            }
            mapOf(
                btnSearchStreams to TvViewModel.SearchType.STREAMS,
                btnSearchChannels to TvViewModel.SearchType.CHANNELS,
                btnSearchGames to TvViewModel.SearchType.GAMES,
            ).forEach { (view, type) ->
                view.setOnClickListener {
                    viewModel.stack.value = listOf(TvViewModel.Page.Search(searchInput.text.toString().trim(), type))
                }
            }

            // Entering the sidebar from the content always lands on the current tab.
            root.viewTreeObserver.addOnGlobalFocusChangeListener { old, new ->
                if (old != null && new != null && !sidebar.contains(old) && sidebar.contains(new)) {
                    activeTab()?.takeIf { it != new }?.let { sidebar.post { it.requestFocus() } }
                }
            }
            (activeTab() ?: tabTopStreams).requestFocus()
        }

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                when {
                    viewModel.stack.value.size > 1 -> {
                        val stack = viewModel.stack.value.dropLast(1)
                        pendingRestore = savedPositions.remove(stack.last()) ?: (null to 0)
                        viewModel.stack.value = stack
                    }
                    !binding.sidebar.hasFocus() -> activeTab()?.requestFocus()
                    else -> finish()
                }
            }
        })

        adapter.addLoadStateListener { states ->
            val page = viewModel.stack.value.last()
            val loading = states.refresh is LoadState.Loading
            val isList = page != TvViewModel.Page.Settings
            binding.progress.visibility = if (isList && loading && adapter.itemCount == 0) View.VISIBLE else View.GONE
            binding.empty.visibility = if (isList && !loading && adapter.itemCount == 0) View.VISIBLE else View.GONE
            binding.title.text = if (isList && states.append.endOfPaginationReached && adapter.itemCount > 0) {
                "${pageTitle(page)} (${adapter.itemCount})"
            } else {
                pageTitle(page)
            }
        }
        adapter.addOnPagesUpdatedListener {
            val restore = pendingRestore ?: return@addOnPagesUpdatedListener
            if (adapter.itemCount == 0) return@addOnPagesUpdatedListener
            pendingRestore = null
            restore.first?.let { layoutManager.onRestoreInstanceState(it) } ?: layoutManager.scrollToPosition(restore.second)
            binding.grid.post {
                binding.grid.findViewHolderForAdapterPosition(restore.second.coerceAtMost(adapter.itemCount - 1))?.itemView?.requestFocus()
            }
        }
        lifecycleScope.launch {
            viewModel.current.collectLatest { (page, _) ->
                render(page)
                viewModel.flowFor(page).collectLatest { adapter.submitData(it) }
            }
        }
    }

    private fun View.contains(view: View): Boolean {
        var v: View? = view
        while (v != null) {
            if (v == this) return true
            v = v.parent as? View
        }
        return false
    }

    /** Shows only the sidebar sections enabled in the TV settings. */
    private fun applyHomeSettings() {
        val hidden = TvSettings.hiddenSections(this)
        tabs.forEach { (view, page) ->
            page.key?.let { view.visibility = if (it in hidden) View.GONE else View.VISIBLE }
        }
    }

    private fun activeTab(): View? {
        val root = viewModel.stack.value.first()
        return tabs.entries.firstOrNull { it.value::class == root::class }?.key
    }

    private fun render(page: TvViewModel.Page) {
        with(binding) {
            tabs.keys.forEach { it.isActivated = it == activeTab() }
            title.text = pageTitle(page)
            val isSearch = page is TvViewModel.Page.Search
            searchBar.visibility = if (isSearch) View.VISIBLE else View.GONE
            if (page is TvViewModel.Page.Search) {
                btnSearchStreams.isActivated = page.type == TvViewModel.SearchType.STREAMS
                btnSearchChannels.isActivated = page.type == TvViewModel.SearchType.CHANNELS
                btnSearchGames.isActivated = page.type == TvViewModel.SearchType.GAMES
            }
            btnLanguage.visibility = if (page.usesLanguages) View.VISIBLE else View.GONE
            btnLanguage.text = getString(R.string.tv_languages_value, viewModel.languages.value.joinToString(", ") { it.uppercase() }.ifEmpty { getString(R.string.tv_all_languages) })
            btnLive.visibility = if ((page as? TvViewModel.Page.Channel)?.user?.isLive == true) View.VISIBLE else View.GONE
            val isSettings = page == TvViewModel.Page.Settings
            grid.visibility = if (isSettings) View.GONE else View.VISIBLE
            settingsScroll.visibility = if (isSettings) View.VISIBLE else View.GONE
            if (isSettings) {
                settings.show()
                settingsScroll.scrollTo(0, 0)
            } else {
                layoutManager.spanCount = (page.columns + TvSettings.columnOffset(this@TvActivity)).coerceAtLeast(2)
                if (pendingRestore == null) layoutManager.scrollToPosition(0)
            }
        }
    }

    private fun pageTitle(page: TvViewModel.Page): String = when (page) {
        TvViewModel.Page.Following, TvViewModel.Page.FollowedGames, TvViewModel.Page.FollowedVideos,
        TvViewModel.Page.FollowedChannels, TvViewModel.Page.TopGames, TvViewModel.Page.TopStreams -> getString(page.title)
        TvViewModel.Page.Settings -> getString(R.string.settings)
        is TvViewModel.Page.Game -> page.game.name.orEmpty()
        is TvViewModel.Page.Channel -> page.user.name ?: page.user.login.orEmpty()
        is TvViewModel.Page.Search -> page.query.ifEmpty { getString(R.string.search) }
    }

    private fun open(item: Any) {
        when (item) {
            is Stream -> play(item)
            is Video -> item.id?.let {
                startActivity(Intent(Intent.ACTION_VIEW, "https://www.twitch.tv/videos/$it".toUri(), this, MainActivity::class.java)
                    .putExtra(MainActivity.KEY_FROM_TV, true))
            }
            is Game -> push(TvViewModel.Page.Game(item))
            is User -> push(TvViewModel.Page.Channel(item))
        }
    }

    private fun push(page: TvViewModel.Page) {
        val focused = binding.grid.focusedChild?.let { binding.grid.getChildAdapterPosition(it) } ?: 0
        savedPositions[viewModel.stack.value.last()] = layoutManager.onSaveInstanceState() to focused
        pendingRestore = null to 0
        viewModel.stack.value += page
    }

    private fun play(stream: Stream) {
        startActivity(Intent(this, MainActivity::class.java).apply {
            action = MainActivity.INTENT_LIVE_NOTIFICATION
            putExtra(MainActivity.KEY_VIDEO, stream)
            putExtra(MainActivity.KEY_FROM_TV, true)
        })
    }

    private fun showLanguageDialog() {
        val names = resources.getStringArray(R.array.gqlUserLanguageEntries)
        val values = resources.getStringArray(R.array.gqlUserLanguageValues)
        val checked = values.map { it in viewModel.languages.value }.toBooleanArray()
        getAlertDialogBuilder()
            .setTitle(R.string.languages)
            .setMultiChoiceItems(names, checked) { _, which, isChecked -> checked[which] = isChecked }
            .setNeutralButton(R.string.tv_all_languages) { _, _ -> viewModel.setLanguages(emptySet()) }
            .setPositiveButton(android.R.string.ok) { _, _ ->
                viewModel.setLanguages(values.filterIndexed { i, _ -> checked[i] }.toSet())
            }
            .show()
    }
}

class TvViewModel(private val app: XtraApp) : ViewModel() {

    enum class SearchType { STREAMS, CHANNELS, GAMES }

    /** [key] and [title] are set on the sidebar sections that can be hidden or used as start page. */
    sealed class Page(val columns: Int, val usesLanguages: Boolean = false, val key: String? = null, val title: Int = 0) {
        data object Following : Page(4, key = "following", title = R.string.tv_followed_live)
        data object FollowedGames : Page(6, key = "followed_games", title = R.string.tv_followed_games)
        data object FollowedVideos : Page(4, key = "followed_videos", title = R.string.tv_past_broadcasts)
        data object FollowedChannels : Page(6, key = "followed_channels", title = R.string.tv_followed_channels)
        data object TopGames : Page(6, key = "top_games", title = R.string.tv_top_games)
        data object TopStreams : Page(4, usesLanguages = true, key = "top_streams", title = R.string.tv_top_streams)
        data object Settings : Page(1)
        class Game(val game: com.github.andreyasadchy.xtra.model.ui.Game) : Page(4, usesLanguages = true)
        class Channel(val user: User) : Page(4)
        data class Search(val query: String, val type: SearchType) : Page(if (type == SearchType.STREAMS) 4 else 6)

        companion object {
            val roots: List<Page>
                get() = listOf(Following, FollowedGames, FollowedVideos, FollowedChannels, TopGames, TopStreams)
        }
    }

    /** Navigation stack: the first page is the sidebar tab, the rest are drill-downs (game, channel). */
    val stack = MutableStateFlow<List<Page>>(listOf(startPage()))
    val languages = MutableStateFlow(app.prefs().getStringSet(KEY_LANGUAGES, null).orEmpty())
    val current = combine(stack, languages) { stack, languages -> stack.last() to languages }

    private val flows = HashMap<Pair<Page, Set<String>>, Flow<PagingData<TvCard>>>()

    /** Start page from the TV settings, or followed streams when logged in, else top streams. */
    private fun startPage(): Page {
        val hidden = TvSettings.hiddenSections(app)
        val visible = Page.roots.filter { it.key !in hidden }
        val chosen = app.prefs().getString(TvSettings.KEY_START_PAGE, TvSettings.START_AUTO)
        val loggedIn = app.tokenPrefs().getString(C.USER_ID, null) != null
        return visible.find { it.key == chosen }
            ?: visible.find { it == Page.Following && loggedIn }
            ?: visible.find { it == Page.TopStreams }
            ?: visible.firstOrNull()
            ?: Page.TopStreams
    }

    fun setLanguages(value: Set<String>) {
        app.prefs().edit { putStringSet(KEY_LANGUAGES, value) }
        languages.value = value
    }

    fun flowFor(page: Page): Flow<PagingData<TvCard>> {
        val key = page to (if (page.usesLanguages) languages.value else emptySet())
        // drop flows of drill-down pages that are no longer reachable
        flows.keys.removeAll { (p, _) -> (p is Page.Game || p is Page.Channel || p is Page.Search) && p !in stack.value }
        return flows.getOrPut(key) { createFlow(page, key.second) }
    }

    private fun createFlow(page: Page, languages: Set<String>): Flow<PagingData<TvCard>> {
        val module = app.xtraModule
        val prefs = app.prefs()
        val gql = TwitchApiHelper.getGQLHeaders(app)
        val gqlAuth = TwitchApiHelper.getGQLHeaders(app, true)
        val helix = TwitchApiHelper.getHelixHeaders(app)
        val integrity = prefs.getBoolean(C.ENABLE_INTEGRITY, false)
        val network = prefs.getString(C.NETWORK_LIBRARY, C.OKHTTP)
        val userId = app.tokenPrefs().getString(C.USER_ID, null)
        val gqlLanguages = languages.toList().ifEmpty { null }
        val gqlQueryLanguages = gqlLanguages?.mapNotNull { l -> Language.entries.find { it.rawValue == l } }
        return when (page) {
            Page.Settings -> flowOf(PagingData.empty())
            Page.Following -> pager(::streamCard) {
                FollowedStreamsDataSource(userId, module.localChannelFollowsRepository, gqlAuth, module.graphQLRepository, helix, module.helixRepository, integrity, network)
            }
            Page.FollowedGames -> pager(::gameCard) {
                FollowedGamesDataSource(module.localGameFollowsRepository, gqlAuth, module.graphQLRepository, integrity, network)
            }
            Page.FollowedVideos -> pager(::videoCard) {
                FollowedVideosDataSource(null, VideoSort.TIME, gqlAuth, module.graphQLRepository, integrity, network)
            }
            Page.FollowedChannels -> pager(::channelCard) {
                FollowedChannelsDataSource("last_broadcast", "desc", userId, module.localChannelFollowsRepository, module.offlineVideosRepository, module.bookmarksRepository, gqlAuth, module.graphQLRepository, helix, module.helixRepository, integrity, network)
            }
            Page.TopGames -> pager(::gameCard) {
                GamesDataSource(null, gql, module.graphQLRepository, helix, module.helixRepository, integrity, network)
            }
            Page.TopStreams -> pager(::streamCard) {
                StreamsDataSource(gqlQueryLanguages, StreamSort.VIEWER_COUNT, gqlLanguages, "VIEWER_COUNT", null, gql, module.graphQLRepository, helix, module.helixRepository, integrity, network)
            }
            is Page.Game -> pager(::streamCard) {
                GameStreamsDataSource(page.game.id, page.game.slug, page.game.name, gqlQueryLanguages, StreamSort.VIEWER_COUNT, gqlLanguages, "VIEWER_COUNT", null, gql, module.graphQLRepository, helix, module.helixRepository, integrity, network)
            }
            is Page.Channel -> pager(::videoCard) {
                ChannelVideosDataSource(page.user.id, page.user.login, null, VideoSort.TIME, null, "TIME", "all", "all", "time", gql, module.graphQLRepository, helix, module.helixRepository, integrity, network)
            }
            is Page.Search -> when {
                page.query.isEmpty() -> flowOf(PagingData.empty())
                page.type == SearchType.CHANNELS -> pager(::channelCard) {
                    SearchChannelsDataSource(page.query, gql, module.graphQLRepository, helix, module.helixRepository, integrity, network)
                }
                page.type == SearchType.GAMES -> pager(::gameCard) {
                    SearchGamesDataSource(page.query, gql, module.graphQLRepository, helix, module.helixRepository, integrity, network)
                }
                else -> pager(::streamCard) {
                    SearchStreamsDataSource(page.query, gql, module.graphQLRepository, helix, module.helixRepository, integrity, network)
                }
            }
        }
    }

    private fun <T : Any> pager(toCard: (T) -> TvCard, source: () -> PagingSource<Int, T>) =
        Pager(PagingConfig(pageSize = 30, prefetchDistance = 12, initialLoadSize = 30), pagingSourceFactory = source)
            .flow.map { data -> data.map(toCard) }.cachedIn(viewModelScope)

    private fun streamCard(it: Stream) = TvCard(
        key = "s" + (it.id ?: it.channelLogin), image = it.thumbnail, shape = TvCard.Shape.WIDE, live = true,
        line1 = it.channelName ?: it.channelLogin, line2 = it.title,
        line3 = listOfNotNull(it.viewerCount?.let { v -> "● " + TwitchApiHelper.formatCount(v, true) }, it.gameName).joinToString("  ·  "),
        item = it,
    )

    private fun videoCard(it: Video) = TvCard(
        key = "v" + it.id, image = it.thumbnail, shape = TvCard.Shape.WIDE,
        line1 = it.title, line2 = it.channelName ?: it.channelLogin,
        line3 = listOfNotNull(it.durationSeconds?.let { d -> DateUtils.formatElapsedTime(d.toLong()) }, it.gameName).joinToString("  ·  "),
        item = it,
    )

    private fun gameCard(it: Game) = TvCard(
        key = "g" + (it.id ?: it.name), image = it.boxArt, shape = TvCard.Shape.POSTER,
        line1 = it.name, line2 = it.viewerCount?.let { v -> "● " + TwitchApiHelper.formatCount(v, true) }, line3 = null,
        item = it,
    )

    private fun channelCard(it: User) = TvCard(
        key = "c" + (it.id ?: it.login), image = it.profileImage, shape = TvCard.Shape.ROUND,
        line1 = it.name ?: it.login, line2 = if (it.isLive == true) "● " + app.getString(R.string.live) else null, line3 = null,
        item = it,
    )

    companion object {
        private const val KEY_LANGUAGES = "tv_languages"

        val Factory = viewModelFactory {
            initializer { TvViewModel(this[APPLICATION_KEY] as XtraApp) }
        }
    }
}

class TvCard(
    val key: String,
    val image: String?,
    val shape: Shape,
    val line1: String?,
    val line2: String?,
    val line3: String?,
    val item: Any,
    val live: Boolean = false,
) {
    enum class Shape(val ratio: String) { WIDE("16:9"), POSTER("3:4"), ROUND("1:1") }
}

class TvCardAdapter(
    private val onClick: (Any) -> Unit,
) : PagingDataAdapter<TvCard, TvCardAdapter.Holder>(object : DiffUtil.ItemCallback<TvCard>() {
    override fun areItemsTheSame(oldItem: TvCard, newItem: TvCard) = oldItem.key == newItem.key
    override fun areContentsTheSame(oldItem: TvCard, newItem: TvCard) =
        oldItem.line1 == newItem.line1 && oldItem.line2 == newItem.line2 && oldItem.line3 == newItem.line3 && oldItem.image == newItem.image
}) {

    class Holder(val binding: TvCardBinding) : RecyclerView.ViewHolder(binding.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
        val binding = TvCardBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        binding.root.setOnFocusChangeListener { v, hasFocus ->
            val scale = if (hasFocus) 1.06f else 1f
            v.animate().scaleX(scale).scaleY(scale).setDuration(120).start()
        }
        return Holder(binding)
    }

    override fun onBindViewHolder(holder: Holder, position: Int) {
        val card = getItem(position) ?: return
        with(holder.binding) {
            root.setOnClickListener { onClick(card.item) }
            (image.layoutParams as ConstraintLayout.LayoutParams).dimensionRatio = card.shape.ratio
            listOf(line1 to card.line1, line2 to card.line2, line3 to card.line3).forEach { (view, text) ->
                view.text = text
                view.visibility = if (text.isNullOrBlank()) View.GONE else View.VISIBLE
            }
            image.setImageDrawable(null)
            card.image?.let {
                root.context.imageLoader.enqueue(ImageRequest.Builder(root.context).apply {
                    data(it)
                    // live thumbnails change: refresh them every 5 minutes, like StreamsAdapter
                    if (card.live) memoryCacheKeyExtra("minutes", (System.currentTimeMillis() / 300_000L).toString())
                    if (card.shape == TvCard.Shape.ROUND) transformations(CircleCropTransformation())
                    target(image)
                }.build())
            }
        }
    }
}
