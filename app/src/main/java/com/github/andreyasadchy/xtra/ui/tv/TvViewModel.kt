package com.github.andreyasadchy.xtra.ui.tv

import android.content.Context
import android.text.format.DateUtils
import androidx.core.content.edit
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.Companion.APPLICATION_KEY
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.paging.Pager
import androidx.paging.PagingConfig
import androidx.paging.PagingData
import androidx.paging.PagingSource
import androidx.paging.cachedIn
import androidx.paging.map
import com.github.andreyasadchy.xtra.R
import com.github.andreyasadchy.xtra.XtraApp
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
import com.github.andreyasadchy.xtra.util.C
import com.github.andreyasadchy.xtra.util.TwitchApiHelper
import com.github.andreyasadchy.xtra.util.prefs
import com.github.andreyasadchy.xtra.util.tokenPrefs
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map

class TvViewModel(private val app: XtraApp) : ViewModel() {

    enum class SearchType { STREAMS, CHANNELS, GAMES }

    /**
     * Sections ([key] != null) are the home screen rows and the sidebar index.
     * The other pages open full screen (game, channel, search) or replace the home screen (settings).
     */
    sealed class Page(val columns: Int = 4, val usesLanguages: Boolean = false, val key: String? = null) {
        data object Following : Page(key = "following")
        data class Language(val code: String) : Page(key = "lang_$code")
        data object FollowedGames : Page(6, key = "followed_games")
        data object FollowedVideos : Page(key = "followed_videos")
        data object FollowedChannels : Page(6, key = "followed_channels")
        data object TopGames : Page(6, key = "top_games")
        data object TopStreams : Page(usesLanguages = true, key = "top_streams")

        data object Home : Page()
        data object Settings : Page()
        class Game(val game: com.github.andreyasadchy.xtra.model.ui.Game) : Page(usesLanguages = true)
        class Channel(val user: User) : Page()
        data class Search(val query: String, val type: SearchType) : Page(if (type == SearchType.STREAMS) 4 else 6)

        val isSection: Boolean
            get() = key != null

        /** Wide 16:9 cards (streams, videos) vs narrow ones (game posters, channel avatars). */
        val wideCards: Boolean
            get() = columns < 6

        fun title(context: Context): String = when (this) {
            Following -> context.getString(R.string.tv_followed_live)
            is Language -> TvLanguages.name(context, code)
            FollowedGames -> context.getString(R.string.tv_followed_games)
            FollowedVideos -> context.getString(R.string.tv_past_broadcasts)
            FollowedChannels -> context.getString(R.string.tv_followed_channels)
            TopGames -> context.getString(R.string.tv_top_games)
            TopStreams -> context.getString(R.string.tv_top_streams)
            Home -> context.getString(R.string.tv_home)
            Settings -> context.getString(R.string.settings)
            is Game -> game.name.orEmpty()
            is Channel -> user.name ?: user.login.orEmpty()
            is Search -> query.ifEmpty { context.getString(R.string.search) }
        }

        companion object {
            /** All sections, in sidebar and home order. */
            fun sections(context: Context): List<Page> =
                listOf(Following) + TvSettings.languageTabs(context).map { Language(it) } +
                        listOf(FollowedGames, FollowedVideos, FollowedChannels, TopGames, TopStreams)

            fun visibleSections(context: Context): List<Page> {
                val hidden = TvSettings.hiddenSections(context)
                return sections(context).filter { it.key !in hidden }
            }
        }
    }

    /** Navigation stack: Home, Search or Settings first, then full screen drill-downs (game, channel). */
    val stack = MutableStateFlow<List<Page>>(listOf(Page.Home))

    /** Home row currently at the top of the screen, highlighted in the sidebar. */
    var section: Page = startSection()

    /** Language filter of top streams and game pages. */
    val languages = MutableStateFlow(app.prefs().getStringSet(KEY_LANGUAGES, null).orEmpty())
    val current = combine(stack, languages) { stack, languages -> stack.last() to languages }

    private val flows = HashMap<Pair<Page, Set<String>>, Flow<PagingData<TvCard>>>()

    private fun startSection(): Page {
        val visible = Page.visibleSections(app)
        val chosen = app.prefs().getString(TvSettings.KEY_START_PAGE, TvSettings.START_AUTO)
        return visible.find { it.key == chosen } ?: visible.firstOrNull() ?: Page.Following
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
        fun topStreams(languages: List<String>?) = pager(::streamCard) {
            StreamsDataSource(languages?.mapNotNull { l -> Language.entries.find { it.rawValue == l } }, StreamSort.VIEWER_COUNT, languages, "VIEWER_COUNT", null, gql, module.graphQLRepository, helix, module.helixRepository, integrity, network)
        }
        val gqlLanguages = languages.toList().ifEmpty { null }
        return when (page) {
            Page.Home, Page.Settings -> flowOf(PagingData.empty())
            Page.Following -> pager(::streamCard) {
                FollowedStreamsDataSource(userId, module.localChannelFollowsRepository, gqlAuth, module.graphQLRepository, helix, module.helixRepository, integrity, network)
            }
            is Page.Language -> topStreams(listOf(page.code))
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
            Page.TopStreams -> topStreams(gqlLanguages)
            is Page.Game -> pager(::streamCard) {
                GameStreamsDataSource(page.game.id, page.game.slug, page.game.name, gqlLanguages?.mapNotNull { l -> Language.entries.find { it.rawValue == l } }, StreamSort.VIEWER_COUNT, gqlLanguages, "VIEWER_COUNT", null, gql, module.graphQLRepository, helix, module.helixRepository, integrity, network)
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
