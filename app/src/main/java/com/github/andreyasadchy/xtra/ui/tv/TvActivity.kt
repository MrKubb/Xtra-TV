package com.github.andreyasadchy.xtra.ui.tv

import android.content.Intent
import android.graphics.Rect
import android.os.Build
import android.os.Bundle
import android.os.Parcelable
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.text.style.RelativeSizeSpan
import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.ImageView
import android.widget.TextView
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.edit
import androidx.core.net.toUri
import androidx.lifecycle.lifecycleScope
import androidx.paging.LoadState
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.github.andreyasadchy.xtra.R
import com.github.andreyasadchy.xtra.databinding.ActivityTvBinding
import com.github.andreyasadchy.xtra.databinding.TvTabBinding
import com.github.andreyasadchy.xtra.model.ui.Game
import com.github.andreyasadchy.xtra.model.ui.Stream
import com.github.andreyasadchy.xtra.model.ui.User
import com.github.andreyasadchy.xtra.model.ui.Video
import com.github.andreyasadchy.xtra.ui.main.MainActivity
import com.github.andreyasadchy.xtra.ui.settings.SettingsActivity
import com.github.andreyasadchy.xtra.util.C
import com.github.andreyasadchy.xtra.util.TwitchApiHelper
import com.github.andreyasadchy.xtra.util.applyTheme
import com.github.andreyasadchy.xtra.util.getAlertDialogBuilder
import com.github.andreyasadchy.xtra.util.prefs
import com.github.andreyasadchy.xtra.util.tokenPrefs
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import com.github.andreyasadchy.xtra.ui.tv.TvViewModel.Page

/**
 * Remote-friendly interface for Android TV. Reuses Xtra's data sources, player, login and settings.
 *
 * The sidebar is an index of the home screen: moving the focus over a section scrolls the home rows
 * to it, no click needed. Search and settings also show up as soon as they are focused.
 */
class TvActivity : AppCompatActivity() {

    private lateinit var binding: ActivityTvBinding
    private val viewModel: TvViewModel by viewModels { TvViewModel.Factory }
    private lateinit var preview: TvPreview
    private lateinit var home: TvHome
    private lateinit var settings: TvSettings
    private val adapter = TvCardAdapter(::open, ::onCardFocus, onLongClick = ::showCardMenu)
    private lateinit var layoutManager: GridLayoutManager

    /** Sidebar entries and what they show. */
    private val tabs = LinkedHashMap<View, Page>()
    private var sidebarOpen = true
    private val sectionCounts = HashMap<Page, Int>()

    /** Scroll position + focused card per full screen list, restored when coming back to it. */
    private val savedPositions = HashMap<Page, Pair<Parcelable?, Int>>()
    private var pendingRestore: Pair<Parcelable?, Int>? = null

    /** Login and logout change tokens: restart to reload everything. */
    private val restartLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        finish()
        startActivity(Intent(this, TvActivity::class.java))
    }

    /** The TV player can ask to open the channel of the stream it was playing. */
    private val playerLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val user = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            result.data?.getParcelableExtra(TvPlayerActivity.KEY_OPEN_CHANNEL, User::class.java)
        } else {
            @Suppress("DEPRECATION")
            result.data?.getParcelableExtra(TvPlayerActivity.KEY_OPEN_CHANNEL)
        }
        if (result.resultCode == RESULT_OK && user != null) push(Page.Channel(user))
    }

    /** Xtra's settings ask for a recreate when something visible changed. */
    private val settingsLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        if (it.resultCode == RESULT_OK) recreate()
    }

    private val isLoggedIn: Boolean
        get() = !TwitchApiHelper.getGQLHeaders(this, true)[C.HEADER_TOKEN].isNullOrBlank() ||
                !TwitchApiHelper.getHelixHeaders(this)[C.HEADER_TOKEN].isNullOrBlank()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Xtra never ran on this device: let it set itself up once (settings, default ad-blocking proxies)
        if (prefs().getInt(C.SETTINGS_VERSION, 0) == 0) {
            startActivity(Intent(this, MainActivity::class.java).putExtra(MainActivity.KEY_TV_SETUP, true))
            finish()
            return
        }
        applyTheme()
        binding = ActivityTvBinding.inflate(layoutInflater)
        setContentView(binding.root)
        preview = TvPreview(this, binding)
        home = TvHome(this, binding.homeRows, viewModel, ::open, ::onCardFocus,
            onRowFocus = { page ->
                viewModel.section = page.parent ?: page
                updateActiveTab()
            },
            onRowCount = { page, count ->
                if (count != null) sectionCounts[page] = count else sectionCounts.remove(page)
                updateTabLabel(page)
            },
            sidebarEntry = ::activeTab,
            onMenu = ::showCardMenu,
        )
        settings = TvSettings(this, binding.settingsList, viewModel, onHomeChanged = { applyHomeSettings(rebuild = it) }) {
            settingsLauncher.launch(Intent(this, SettingsActivity::class.java))
        }
        with(binding) {
            layoutManager = object : GridLayoutManager(this@TvActivity, 4) {
                // Keep the focused row centered instead of scrolling it just inside the edge.
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

            addTab(tabSearch, Page.Search("", TvViewModel.SearchType.STREAMS))
            addTab(tabSettings, Page.Settings)
            applyHomeSettings()
            setupAccountTab()

            btnLanguage.setOnClickListener {
                TvLanguages.showDialog(this@TvActivity, getString(R.string.languages), viewModel.languages.value) {
                    viewModel.setLanguages(it.toSet())
                }
            }
            btnLive.setOnClickListener {
                (viewModel.stack.value.last() as? Page.Channel)?.user?.let { user ->
                    play(Stream(channelId = user.id, channelLogin = user.login, channelName = user.name, channelImageURL = user.profileImageURL), emptyList())
                }
            }
            searchInput.setOnEditorActionListener { v, actionId, event ->
                val submit = actionId == EditorInfo.IME_ACTION_SEARCH || event?.keyCode == KeyEvent.KEYCODE_ENTER
                if (submit) {
                    val type = (viewModel.stack.value.last() as? Page.Search)?.type ?: TvViewModel.SearchType.STREAMS
                    viewModel.stack.value = listOf(Page.Search(v.text.toString().trim(), type))
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
                    viewModel.stack.value = listOf(Page.Search(searchInput.text.toString().trim(), type))
                }
            }

            root.viewTreeObserver.addOnGlobalFocusChangeListener { old, new ->
                setSidebarOpen(new != null && sidebar.contains(new))
                if (new == null || !sidebar.contains(new)) return@addOnGlobalFocusChangeListener
                // Entering the sidebar from the content lands on the current entry, without changing the page.
                val active = activeTab()
                if ((old == null || !sidebar.contains(old)) && active != null && active != new) {
                    sidebar.post { active.requestFocus() }
                    return@addOnGlobalFocusChangeListener
                }
                tabs[new]?.let { select(it) }
            }
        }

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                when {
                    viewModel.stack.value.first() == Page.Settings && settings.closeSectionsEditor() -> Unit
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
            val isList = page.isList
            val loading = states.refresh is LoadState.Loading
            binding.progress.visibility = if (isList && loading && adapter.itemCount == 0) View.VISIBLE else View.GONE
            binding.empty.visibility = if (isList && !loading && adapter.itemCount == 0) View.VISIBLE else View.GONE
            binding.title.text = if (isList && states.append.endOfPaginationReached && adapter.itemCount > 0) {
                "${page.title(this)} (${adapter.itemCount})"
            } else {
                page.title(this)
            }
        }
        adapter.addOnPagesUpdatedListener {
            val restore = pendingRestore ?: return@addOnPagesUpdatedListener
            if (!viewModel.stack.value.last().isList || adapter.itemCount == 0) return@addOnPagesUpdatedListener
            pendingRestore = null
            restore.first?.let { layoutManager.onRestoreInstanceState(it) } ?: layoutManager.scrollToPosition(restore.second)
            binding.grid.post {
                binding.grid.findViewHolderForAdapterPosition(restore.second.coerceAtMost(adapter.itemCount - 1))?.itemView?.requestFocus()
            }
        }
        lifecycleScope.launch {
            // "My games live" rows appear once the followed games are loaded
            viewModel.followedGames.collect { if (it != null) applyHomeSettings() }
        }
        lifecycleScope.launch {
            viewModel.current.collectLatest { (page, _) ->
                render(page)
                viewModel.flowFor(page).collectLatest { adapter.submitData(it) }
            }
        }
        home.scrollTo(viewModel.section, smooth = false)
        activeTab()?.requestFocus()
        // start in the content, the sidebar slides away
        home.focusWhenLoaded(viewModel.section)
    }

    /** The sidebar slides over the content while it has the focus, and away when the content has it. */
    private fun setSidebarOpen(open: Boolean) {
        if (open == sidebarOpen) return
        sidebarOpen = open
        val width = binding.sidebarPanel.width.takeIf { it > 0 } ?: (250 * resources.displayMetrics.density).toInt()
        binding.sidebarPanel.animate().translationX(if (open) 0f else -width.toFloat()).setDuration(200).start()
        binding.content.animate().alpha(if (open) 0.4f else 1f).setDuration(200).start()
    }

    override fun onRestart() {
        super.onRestart()
        // live status of favorites and the channel suggestions are based on may have changed
        home.refresh(Page.Favorites)
        home.refresh(Page.Suggestions)
        home.refreshTitles()
    }

    override fun onStop() {
        preview.release()
        super.onStop()
    }

    /** Pages shown as a full screen grid. */
    private val Page.isList: Boolean
        get() = this is Page.Game || this is Page.Channel || this is Page.Search

    private fun addTab(view: View, page: Page) {
        tabs[view] = page
        view.setOnClickListener { enter(page) }
        // RIGHT goes into this entry's content, not into whatever card happens to be next to it on screen
        view.setOnKeyListener { _, keyCode, event ->
            if (keyCode == KeyEvent.KEYCODE_DPAD_RIGHT && event.action == KeyEvent.ACTION_DOWN) {
                enter(page)
                true
            } else false
        }
    }

    /** Rebuilds the sidebar sections and the home rows from the TV settings. */
    private fun applyHomeSettings(rebuild: Boolean = false) {
        val sections = Page.visibleSections(this)
        // only rebuild the sidebar when its sections changed, so it keeps the focus otherwise
        if (tabs.values.filter { it.isSection } != sections) {
            val focused = tabs[binding.sectionTabs.focusedChild]
            tabs.keys.filter { tabs[it]?.isSection == true }.forEach { tabs.remove(it) }
            binding.sectionTabs.removeAllViews()
            sections.forEach { page ->
                addTab(TvTabBinding.inflate(layoutInflater, binding.sectionTabs, true).root, page)
                updateTabLabel(page)
            }
            focused?.let { page -> tabs.entries.firstOrNull { it.value == page }?.key?.requestFocus() }
        }
        if (viewModel.section !in sections) viewModel.section = sections.firstOrNull() ?: Page.Following
        if (Page.FollowedGameRows in sections) viewModel.loadFollowedGames()
        home.setSections(viewModel.homeRows(this), rebuild)
        updateActiveTab()
    }

    private fun setupAccountTab() {
        with(binding.tabAccount) {
            if (isLoggedIn) {
                maxLines = 2
                text = SpannableStringBuilder(tokenPrefs().getString(C.USERNAME, null).orEmpty()).append("\n").append(
                    getString(R.string.log_out),
                    RelativeSizeSpan(0.75f),
                    Spanned.SPAN_EXCLUSIVE_EXCLUSIVE,
                ).also {
                    it.setSpan(ForegroundColorSpan(0xFF9A9AA2.toInt()), it.length - getString(R.string.log_out).length, it.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                }
                setOnClickListener {
                    getAlertDialogBuilder()
                        .setTitle(R.string.logout_title)
                        .setMessage(getString(R.string.logout_msg, tokenPrefs().getString(C.USERNAME, null)))
                        .setNegativeButton(R.string.no, null)
                        .setPositiveButton(R.string.yes) { _, _ ->
                            restartLauncher.launch(Intent(this@TvActivity, TvLoginActivity::class.java).putExtra(TvLoginActivity.EXTRA_LOGOUT, true))
                        }
                        .show()
                }
            } else {
                setText(R.string.log_in)
                setOnClickListener { restartLauncher.launch(Intent(this@TvActivity, TvLoginActivity::class.java)) }
            }
        }
    }

    /** The focused sidebar entry shows its content right away. */
    private fun select(page: Page) {
        val stack = viewModel.stack.value
        if (page.isSection) {
            // already there, e.g. coming back to the sidebar from a game page
            if (stack.first() == Page.Home && viewModel.section == page) return
            home.cancelPendingFocus()
            viewModel.section = page
            if (stack != listOf(Page.Home)) showRoot(Page.Home)
            home.scrollTo(page)
        } else if (stack.first()::class != page::class) {
            showRoot(page)
        }
        updateActiveTab()
    }

    /** OK on a sidebar entry moves into its content. */
    private fun enter(page: Page) {
        when (page) {
            is Page.Search -> binding.searchInput.requestFocus()
            Page.Settings -> binding.settingsList.requestFocus()
            else -> home.focusRow(page)
        }
    }

    private fun showRoot(page: Page) {
        savedPositions.clear()
        pendingRestore = null
        viewModel.stack.value = listOf(page)
    }

    private fun View.contains(view: View): Boolean {
        var v: View? = view
        while (v != null) {
            if (v == this) return true
            v = v.parent as? View
        }
        return false
    }

    private fun activeTab(): View? {
        val root = viewModel.stack.value.first()
        val target = if (root == Page.Home) viewModel.section else root
        return tabs.entries.firstOrNull { it.value == target || (target is Page.Search && it.value is Page.Search) }?.key
    }

    private fun updateActiveTab() {
        val active = activeTab()
        tabs.keys.forEach { it.isActivated = it == active }
    }

    private fun updateTabLabel(page: Page) {
        val view = tabs.entries.firstOrNull { it.value == page }?.key as? TextView ?: return
        view.text = sectionCounts[page]?.let { "${page.title(this)} ($it)" } ?: page.title(this)
    }

    private fun render(page: Page) {
        preview.stop()
        if (page != Page.Settings) settings.reset()
        with(binding) {
            val isHome = page == Page.Home
            val isSettings = page == Page.Settings
            val isList = page.isList
            header.visibility = if (isHome) View.GONE else View.VISIBLE
            homeRows.visibility = if (isHome) View.VISIBLE else View.GONE
            grid.visibility = if (isList) View.VISIBLE else View.GONE
            settingsScroll.visibility = if (isSettings) View.VISIBLE else View.GONE
            if (!isList) {
                progress.visibility = View.GONE
                empty.visibility = View.GONE
            }
            title.text = page.title(this@TvActivity)
            searchBar.visibility = if (page is Page.Search) View.VISIBLE else View.GONE
            if (page is Page.Search) {
                btnSearchStreams.isActivated = page.type == TvViewModel.SearchType.STREAMS
                btnSearchChannels.isActivated = page.type == TvViewModel.SearchType.CHANNELS
                btnSearchGames.isActivated = page.type == TvViewModel.SearchType.GAMES
            }
            btnLanguage.visibility = if (isList && page.usesLanguages) View.VISIBLE else View.GONE
            btnLanguage.text = getString(R.string.tv_languages_value, viewModel.languages.value.joinToString(", ") { it.uppercase() }.ifEmpty { getString(R.string.tv_all_languages) })
            btnLive.visibility = if ((page as? Page.Channel)?.user?.isLive == true) View.VISIBLE else View.GONE
            when {
                isSettings -> {
                    settings.show()
                    settingsScroll.scrollTo(0, 0)
                }
                isHome -> {
                    home.refreshTitles()
                    // back from a game or channel page: focus the card that opened it
                    if (pendingRestore != null) {
                        pendingRestore = null
                        homeRows.post { home.restoreFocus() }
                    }
                }
                else -> {
                    layoutManager.spanCount = (page.columns + TvSettings.columnOffset(this@TvActivity)).coerceAtLeast(2)
                    if (pendingRestore == null) layoutManager.scrollToPosition(0)
                }
            }
        }
        updateActiveTab()
    }

    private fun onCardFocus(card: TvCard, image: ImageView, hasFocus: Boolean) {
        when {
            hasFocus && card.item is Stream -> preview.schedule(card, card.item, image)
            !hasFocus -> preview.stopIf(image)
        }
    }

    private fun open(item: Any, adapter: TvCardAdapter) {
        when (item) {
            is Stream -> play(item, adapter.streams())
            is Video -> item.id?.let {
                preview.stop()
                openFullScreen()
                TvSettings.setLastChannel(this, item.channelLogin, item.channelName)
                startActivity(Intent(Intent.ACTION_VIEW, "https://www.twitch.tv/videos/$it".toUri(), this, MainActivity::class.java)
                    .putExtra(MainActivity.KEY_FROM_TV, true))
            }
            is Game -> push(Page.Game(item))
            is User -> push(Page.Channel(item))
        }
    }

    private fun push(page: Page) {
        val current = viewModel.stack.value.last()
        if (current.isList) {
            val focused = binding.grid.focusedChild?.let { binding.grid.getChildAdapterPosition(it) } ?: 0
            savedPositions[current] = layoutManager.onSaveInstanceState() to focused
        }
        pendingRestore = null to 0
        viewModel.stack.value += page
    }

    /** Opens the TV player on [stream], with the streams of its row to zap through. */
    private fun play(stream: Stream, row: List<Stream>) {
        preview.stop()
        val list = row.takeIf { stream in it } ?: listOf(stream)
        val position = list.indexOf(stream)
        // a reasonable window around the stream keeps the intent small
        val from = (position - MAX_ZAP / 2).coerceAtLeast(0)
        val window = list.subList(from, (from + MAX_ZAP).coerceAtMost(list.size))
        playerLauncher.launch(Intent(this, TvPlayerActivity::class.java)
            .putParcelableArrayListExtra(TvPlayerActivity.KEY_STREAMS, ArrayList(window))
            .putExtra(TvPlayerActivity.KEY_INDEX, position - from))
    }

    /** OK held on a card: add its channel to the favorites (or remove it), or open the channel. */
    private fun showCardMenu(item: Any) {
        val channel = TvFavorites.channelOf(item) ?: return
        val favorite = TvFavorites.contains(this, channel.id)
        val actions = listOfNotNull(
            getString(if (favorite) R.string.tv_remove_favorite else R.string.tv_add_favorite) to {
                TvFavorites.toggle(this, channel)
                home.refresh(Page.Favorites)
            },
            (getString(R.string.tv_open_channel) to {
                push(Page.Channel(User(id = channel.id, login = channel.login, name = channel.name, profileImageURL = channel.image)))
            }).takeIf { item !is User },
        )
        getAlertDialogBuilder()
            .setTitle(channel.name ?: channel.login)
            .setItems(actions.map { it.first }.toTypedArray()) { _, which -> actions[which].second() }
            .show()
    }

    companion object {
        private const val MAX_ZAP = 100
    }

    /** Videos opened from the TV interface start full screen: the chat only shows up when asked for. */
    private fun openFullScreen() {
        prefs().edit {
            putBoolean(C.KEY_CHAT_OPENED, false)
            // no picture-in-picture on a TV
            if (TvSettings.isTvDevice(this@TvActivity)) putBoolean(C.PLAYER_PICTURE_IN_PICTURE, false)
        }
    }
}
