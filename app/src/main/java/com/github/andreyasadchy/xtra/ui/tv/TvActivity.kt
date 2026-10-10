package com.github.andreyasadchy.xtra.ui.tv

import android.content.Intent
import android.os.Bundle
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.Companion.APPLICATION_KEY
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.paging.LoadState
import androidx.paging.Pager
import androidx.paging.PagingConfig
import androidx.paging.PagingDataAdapter
import androidx.paging.cachedIn
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import coil3.imageLoader
import coil3.request.ImageRequest
import coil3.request.target
import com.github.andreyasadchy.xtra.XtraApp
import com.github.andreyasadchy.xtra.graphql.type.StreamSort
import com.github.andreyasadchy.xtra.databinding.ActivityTvBinding
import com.github.andreyasadchy.xtra.databinding.TvStreamItemBinding
import com.github.andreyasadchy.xtra.model.ui.Stream
import com.github.andreyasadchy.xtra.repository.datasource.FollowedStreamsDataSource
import com.github.andreyasadchy.xtra.repository.datasource.SearchStreamsDataSource
import com.github.andreyasadchy.xtra.repository.datasource.StreamsDataSource
import com.github.andreyasadchy.xtra.ui.main.MainActivity
import com.github.andreyasadchy.xtra.util.C
import com.github.andreyasadchy.xtra.util.TwitchApiHelper
import com.github.andreyasadchy.xtra.util.applyTheme
import com.github.andreyasadchy.xtra.util.prefs
import com.github.andreyasadchy.xtra.util.tokenPrefs
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.launch

/** Simple remote-friendly home screen for Android TV: sidebar + grid of live streams. */
class TvActivity : AppCompatActivity() {

    private lateinit var binding: ActivityTvBinding
    private val viewModel: TvViewModel by viewModels { TvViewModel.Factory }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        applyTheme()
        binding = ActivityTvBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val adapter = TvStreamsAdapter { stream ->
            startActivity(Intent(this, MainActivity::class.java).apply {
                action = MainActivity.INTENT_LIVE_NOTIFICATION
                putExtra(MainActivity.KEY_VIDEO, stream)
                putExtra(MainActivity.KEY_FROM_TV, true)
            })
        }
        with(binding) {
            grid.layoutManager = GridLayoutManager(this@TvActivity, 4)
            grid.adapter = adapter
            val tabs = listOf(tabFollowing, tabPopular, tabSearch)
            fun select(tab: View) {
                tabs.forEach { it.isActivated = it == tab }
                searchInput.visibility = if (tab == tabSearch) View.VISIBLE else View.GONE
            }
            tabFollowing.setOnClickListener { select(it); viewModel.tab.value = TvViewModel.Tab.Following }
            tabPopular.setOnClickListener { select(it); viewModel.tab.value = TvViewModel.Tab.Popular }
            tabSearch.setOnClickListener { select(it); searchInput.requestFocus() }
            tabFullUi.setOnClickListener { startActivity(Intent(this@TvActivity, MainActivity::class.java)) }
            searchInput.setOnEditorActionListener { v, actionId, event ->
                val submit = actionId == EditorInfo.IME_ACTION_SEARCH || event?.keyCode == KeyEvent.KEYCODE_ENTER
                if (submit && v.text.isNotBlank()) {
                    viewModel.tab.value = TvViewModel.Tab.Search(v.text.toString().trim())
                    grid.requestFocus()
                }
                submit
            }
            val start = if (viewModel.tab.value is TvViewModel.Tab.Following) tabFollowing else tabPopular
            select(start)
            start.requestFocus()
        }

        lifecycleScope.launch { viewModel.flow.collectLatest { adapter.submitData(it) } }
        adapter.addLoadStateListener {
            val loading = it.refresh is LoadState.Loading
            binding.progress.visibility = if (loading) View.VISIBLE else View.GONE
            binding.empty.visibility = if (!loading && adapter.itemCount == 0) View.VISIBLE else View.GONE
        }
    }
}

class TvViewModel(private val app: XtraApp) : ViewModel() {

    sealed interface Tab {
        data object Following : Tab
        data object Popular : Tab
        data class Search(val query: String) : Tab
    }

    val tab = MutableStateFlow<Tab>(
        if (app.tokenPrefs().getString(C.USER_ID, null) != null) Tab.Following else Tab.Popular
    )

    @OptIn(ExperimentalCoroutinesApi::class)
    val flow = tab.flatMapLatest { tab ->
        Pager(PagingConfig(pageSize = 30, prefetchDistance = 8, initialLoadSize = 30)) {
            val module = app.xtraModule
            val prefs = app.prefs()
            val helixHeaders = TwitchApiHelper.getHelixHeaders(app)
            val integrity = prefs.getBoolean(C.ENABLE_INTEGRITY, false)
            val network = prefs.getString(C.NETWORK_LIBRARY, C.OKHTTP)
            when (tab) {
                Tab.Following -> FollowedStreamsDataSource(
                    userId = app.tokenPrefs().getString(C.USER_ID, null),
                    localChannelFollowsRepository = module.localChannelFollowsRepository,
                    gqlHeaders = TwitchApiHelper.getGQLHeaders(app, true),
                    graphQLRepository = module.graphQLRepository,
                    helixHeaders = helixHeaders,
                    helixRepository = module.helixRepository,
                    enableIntegrity = integrity,
                    networkLibrary = network,
                )
                Tab.Popular -> StreamsDataSource(
                    gqlQueryLanguages = null,
                    gqlQuerySort = StreamSort.VIEWER_COUNT,
                    gqlLanguages = null,
                    gqlSort = "VIEWER_COUNT",
                    tags = null,
                    gqlHeaders = TwitchApiHelper.getGQLHeaders(app),
                    graphQLRepository = module.graphQLRepository,
                    helixHeaders = helixHeaders,
                    helixRepository = module.helixRepository,
                    enableIntegrity = integrity,
                    networkLibrary = network,
                )
                is Tab.Search -> SearchStreamsDataSource(
                    query = tab.query,
                    gqlHeaders = TwitchApiHelper.getGQLHeaders(app),
                    graphQLRepository = module.graphQLRepository,
                    helixHeaders = helixHeaders,
                    helixRepository = module.helixRepository,
                    enableIntegrity = integrity,
                    networkLibrary = network,
                )
            }
        }.flow
    }.cachedIn(viewModelScope)

    companion object {
        val Factory = viewModelFactory {
            initializer { TvViewModel(this[APPLICATION_KEY] as XtraApp) }
        }
    }
}

class TvStreamsAdapter(
    private val onClick: (Stream) -> Unit,
) : PagingDataAdapter<Stream, TvStreamsAdapter.Holder>(object : DiffUtil.ItemCallback<Stream>() {
    override fun areItemsTheSame(oldItem: Stream, newItem: Stream) = oldItem.id == newItem.id
    override fun areContentsTheSame(oldItem: Stream, newItem: Stream) =
        oldItem.viewerCount == newItem.viewerCount && oldItem.title == newItem.title
}) {

    class Holder(val binding: TvStreamItemBinding) : RecyclerView.ViewHolder(binding.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
        val binding = TvStreamItemBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        binding.root.setOnFocusChangeListener { v, hasFocus ->
            val scale = if (hasFocus) 1.08f else 1f
            v.animate().scaleX(scale).scaleY(scale).setDuration(120).start()
        }
        return Holder(binding)
    }

    override fun onBindViewHolder(holder: Holder, position: Int) {
        val item = getItem(position) ?: return
        with(holder.binding) {
            root.setOnClickListener { onClick(item) }
            channelName.text = item.channelName ?: item.channelLogin
            title.text = item.title
            info.text = listOfNotNull(
                item.viewerCount?.let { "● " + TwitchApiHelper.formatCount(it, true) },
                item.gameName,
            ).joinToString("  ·  ")
            thumbnail.setImageDrawable(null)
            item.thumbnail?.let {
                root.context.imageLoader.enqueue(
                    ImageRequest.Builder(root.context).data(it).target(thumbnail).build()
                )
            }
        }
    }
}
