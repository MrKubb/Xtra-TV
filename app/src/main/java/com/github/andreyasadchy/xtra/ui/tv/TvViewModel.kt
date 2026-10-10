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
import androidx.paging.PagingState
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
import com.github.andreyasadchy.xtra.repository.datasource.ChannelSuggestionsDataSource
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
import kotlinx.coroutines.launch

class TvViewModel(private val app: XtraApp) : ViewModel() {

    enum class SearchType { STREAMS, CHANNELS, GAMES }

    /**
     * Sections ([key] != null) are the home screen rows and the sidebar index.
     * The other pages open full screen (game, channel, search) or replace the home screen (settings).
     */
    sealed class Page(val columns: Int = 4, val usesLanguages: Boolean = false, val key: String? = null) {
        data object Following : Page(key = "following")
        data object Favorites : Page(key = "favorites")
        data class Language(val code: String) : Page(key = "lang_$code")
        /** "My games live": expanded into one [GameRow] per followed game on the home screen. */
        data object FollowedGameRows : Page(key = "followed_game_rows")
        data object Suggestions : Page(key = "suggestions")
        data object FollowedGames : Page(6, key = "followed_games")
        data object FollowedVideos : Page(key = "followed_videos")
        data object FollowedChannels : Page(6, key = "followed_channels")
        data object TopGames : Page(6, key = "top_games")
        data object TopStreams : Page(usesLanguages = true, key = "top_streams")

        /** Live streams of one followed game, a home row under [FollowedGameRows]. */
        class GameRow(val game: com.github.andreyasadchy.xtra.model.ui.Game) : Page(usesLanguages = true, key = "game_${game.id}") {
            override fun equals(other: Any?) = other is GameRow && other.game.id == game.id
            override fun hashCode() = game.id.hashCode()
        }

        data object Home : Page()
        data object Settings : Page()
        class Game(val game: com.github.andreyasadchy.xtra.model.ui.Game) : Page(usesLanguages = true)
        class Channel(val user: User) : Page()
        data class Search(val query: String, val type: SearchType) : Page(if (type == SearchType.STREAMS) 4 else 6)

        val isSection: Boolean
            get() = key != null

        /** Sidebar entry of a home row that has none of its own. */
        val parent: Page?
            get() = if (this is GameRow) FollowedGameRows else null

        /** Wide 16:9 cards (streams, videos) vs narrow ones (game posters, channel avatars). */
        val wideCards: Boolean
            get() = columns < 6

        fun title(context: Context): String = when (this) {
            Following -> context.getString(R.string.tv_followed_live)
            Favorites -> context.getString(R.string.tv_favorites)
            is Language -> TvLanguages.name(context, code)
            FollowedGameRows -> context.getString(R.string.tv_followed_game_rows)
            is GameRow -> game.name.orEmpty()
            Suggestions -> context.getString(R.string.tv_suggestions)
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
            fun fromKey(key: String): Page? = when (key) {
                Following.key -> Following
                Favorites.key -> Favorites
                FollowedGameRows.key -> FollowedGameRows
                Suggestions.key -> Suggestions
                FollowedGames.key -> FollowedGames
                FollowedVideos.key -> FollowedVideos
                FollowedChannels.key -> FollowedChannels
                TopGames.key -> TopGames
                TopStreams.key -> TopStreams
                else -> key.removePrefix("lang_").takeIf { key.startsWith("lang_") }?.let { Language(it) }
            }

            /** All sections, in the order chosen in the TV settings. */
            fun sections(context: Context): List<Page> = TvSections.entries(context).mapNotNull { fromKey(it.key) }

            fun visibleSections(context: Context): List<Page> =
                TvSections.entries(context).filter { it.visible }.mapNotNull { fromKey(it.key) }
        }
    }

    /** Navigation stack: Home, Search or Settings first, then full screen drill-downs (game, channel). */
    val stack = MutableStateFlow<List<Page>>(listOf(Page.Home))

    /** Home row currently at the top of the screen, highlighted in the sidebar. */
    var section: Page = startSection()

    /** Followed games, loaded once for the "My games live" rows. */
    val followedGames = MutableStateFlow<List<Game>?>(null)
    private var loadingFollowedGames = false

    /** Language filter of top streams and game pages. */
    val languages = MutableStateFlow(app.prefs().getStringSet(KEY_LANGUAGES, null).orEmpty())
    val current = combine(stack, languages) { stack, languages -> stack.last() to languages }

    private val flows = HashMap<Pair<Page, Set<String>>, Flow<PagingData<TvCard>>>()

    private fun startSection(): Page {
        val visible = Page.visibleSections(app)
        val chosen = app.prefs().getString(TvSettings.KEY_START_PAGE, TvSettings.START_AUTO)
        return visible.find { it.key == chosen } ?: visible.firstOrNull() ?: Page.Following
    }

    fun loadFollowedGames() {
        if (loadingFollowedGames || followedGames.value != null) return
        loadingFollowedGames = true
        viewModelScope.launch {
            val prefs = app.prefs()
            val result = try {
                FollowedGamesDataSource(
                    app.xtraModule.localGameFollowsRepository, TwitchApiHelper.getGQLHeaders(app, true), app.xtraModule.graphQLRepository,
                    prefs.getBoolean(C.ENABLE_INTEGRITY, false), prefs.getString(C.NETWORK_LIBRARY, C.OKHTTP),
                ).load(PagingSource.LoadParams.Refresh(null, 100, false))
            } catch (e: Exception) {
                null
            }
            followedGames.value = (result as? PagingSource.LoadResult.Page)?.data.orEmpty()
            loadingFollowedGames = false
        }
    }

    /** Home rows: the visible sections, "My games live" expanded into one row per followed game. */
    fun homeRows(context: Context): List<Page> = Page.visibleSections(context).flatMap { page ->
        if (page == Page.FollowedGameRows) {
            followedGames.value?.takeIf { it.isNotEmpty() }?.map { Page.GameRow(it) } ?: listOf(page)
        } else listOf(page)
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
            Page.Home, Page.Settings, Page.FollowedGameRows -> flowOf(PagingData.empty())
            Page.Favorites -> pager({ card: TvCard -> card }) { FavoritesSource(gql, network) }
            Page.Suggestions -> pager(::streamCard) {
                ChannelSuggestionsDataSource(TvSettings.suggestionsSeed(app)?.first, gql, module.graphQLRepository, integrity, network)
            }
            is Page.GameRow -> pager(::streamCard) {
                GameStreamsDataSource(page.game.id, page.game.slug, page.game.name, gqlLanguages?.mapNotNull { l -> Language.entries.find { it.rawValue == l } }, StreamSort.VIEWER_COUNT, gqlLanguages, "VIEWER_COUNT", null, gql, module.graphQLRepository, helix, module.helixRepository, integrity, network)
            }
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

    /** Favorite channels: live ones first (as streams), then offline ones (as channels), each group in pinned order. */
    private inner class FavoritesSource(private val gqlHeaders: Map<String, String>, private val networkLibrary: String?) : PagingSource<Int, TvCard>() {
        override fun getRefreshKey(state: PagingState<Int, TvCard>): Int? = null

        override suspend fun load(params: LoadParams<Int>): LoadResult<Int, TvCard> {
            val favorites = TvFavorites.list(app)
            if (favorites.isEmpty()) return LoadResult.Page(emptyList(), null, null)
            return try {
                val users = favorites.chunked(100).flatMap { chunk ->
                    app.xtraModule.graphQLRepository.loadQueryUsersStream(networkLibrary, gqlHeaders, chunk.map { it.id }).data?.users.orEmpty()
                }.filterNotNull()
                val cards = favorites.map { favorite ->
                    val user = users.find { it.id == favorite.id }
                    val stream = user?.stream?.takeIf { it.viewersCount != null }
                    if (user != null && stream != null) {
                        streamCard(Stream(
                            id = stream.id,
                            channelId = user.id,
                            channelLogin = user.login,
                            channelName = user.displayName,
                            channelImageURL = user.profileImageURL,
                            gameId = stream.game?.id,
                            gameSlug = stream.game?.slug,
                            gameName = stream.game?.displayName,
                            title = stream.broadcaster?.broadcastSettings?.title,
                            thumbnailURL = stream.previewImageURL,
                            createdAt = stream.createdAt?.toString(),
                            viewerCount = stream.viewersCount,
                        ))
                    } else {
                        offlineCard(User(
                            id = favorite.id,
                            login = user?.login ?: favorite.login,
                            name = user?.displayName ?: favorite.name,
                            profileImageURL = user?.profileImageURL ?: favorite.image,
                            isLive = false,
                        ))
                    }
                }.sortedByDescending { it.live }
                LoadResult.Page(cards, null, null)
            } catch (e: Exception) {
                LoadResult.Error(e)
            }
        }
    }

    private fun offlineCard(it: User) = TvCard(
        key = "o" + it.id, image = it.profileImage, shape = TvCard.Shape.AVATAR,
        line1 = it.name ?: it.login, line2 = app.getString(R.string.tv_offline), line3 = null,
        item = it,
    )

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
