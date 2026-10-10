package com.github.andreyasadchy.xtra.ui.tv

import android.graphics.Rect
import android.util.DisplayMetrics
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.paging.LoadState
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.LinearSmoothScroller
import androidx.recyclerview.widget.RecyclerView
import com.github.andreyasadchy.xtra.R
import com.github.andreyasadchy.xtra.databinding.TvHomeRowBinding
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/**
 * Home screen: one horizontal row per section, stacked vertically.
 * The row being browsed (or picked in the sidebar) is kept at the top of the screen.
 */
class TvHome(
    private val activity: AppCompatActivity,
    private val list: RecyclerView,
    private val viewModel: TvViewModel,
    private val onOpen: (Any, TvCardAdapter) -> Unit,
    private val onCardFocus: (TvCard, ImageView, Boolean) -> Unit,
    private val onRowFocus: (TvViewModel.Page) -> Unit,
    private val onRowCount: (TvViewModel.Page, Int?) -> Unit,
    /** Where LEFT on the first card of a row goes: the current sidebar entry. */
    private val sidebarEntry: () -> View?,
    /** OK held on a card. */
    private val onMenu: (Any) -> Unit,
) {
    private var sections = emptyList<TvViewModel.Page>()
    private val rows = HashMap<TvViewModel.Page, Row>()
    private var lastFocused: View? = null
    private var pendingFocus: TvViewModel.Page? = null
    private var pendingFocusRunning = false

    init {
        list.layoutManager = object : LinearLayoutManager(activity) {
            override fun requestChildRectangleOnScreen(parent: RecyclerView, child: View, rect: Rect, immediate: Boolean, focusedChildVisible: Boolean): Boolean {
                val dy = child.top - parent.paddingTop
                if (immediate) parent.scrollBy(0, dy) else parent.smoothScrollBy(0, dy)
                return true
            }

            // UP on the first row and DOWN on the last one stay there instead of jumping into the sidebar.
            override fun onInterceptFocusSearch(focused: View, direction: Int): View? {
                val row = findContainingItemView(focused) ?: return null
                val position = getPosition(row)
                return focused.takeIf {
                    (direction == View.FOCUS_UP && position == 0) || (direction == View.FOCUS_DOWN && position == itemCount - 1)
                }
            }
        }
        list.itemAnimator = null
        list.clipChildren = false
    }

    /** One loaded list per section, kept while the section is shown. */
    private inner class Row(val page: TvViewModel.Page) {
        var empty = false
        val adapter = TvCardAdapter(onOpen, { card, image, hasFocus ->
            if (hasFocus) {
                lastFocused = cardOf(image)
                onRowFocus(page)
            }
            onCardFocus(card, image, hasFocus)
        }, { cardWidth(page) }, onMenu)

        init {
            adapter.addLoadStateListener { states ->
                val isEmpty = states.refresh !is LoadState.Loading && adapter.itemCount == 0
                if (isEmpty != empty) {
                    empty = isEmpty
                    sections.indexOf(page).takeIf { it >= 0 }?.let { rowsAdapter.notifyItemChanged(it) }
                }
                onRowCount(page, adapter.itemCount.takeIf { states.append.endOfPaginationReached && it > 0 })
                if (states.refresh is LoadState.NotLoading && adapter.itemCount > 0) tryPendingFocus()
            }
        }

        val job: Job = activity.lifecycleScope.launch {
            if (page.usesLanguages) {
                viewModel.languages.collectLatest { viewModel.flowFor(page).collectLatest(adapter::submitData) }
            } else {
                viewModel.flowFor(page).collectLatest(adapter::submitData)
            }
        }
    }

    private class RowHolder(val binding: TvHomeRowBinding) : RecyclerView.ViewHolder(binding.root)

    private val rowsAdapter = object : RecyclerView.Adapter<RowHolder>() {
        override fun getItemCount() = sections.size

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RowHolder {
            val binding = TvHomeRowBinding.inflate(LayoutInflater.from(parent.context), parent, false)
            binding.cards.layoutManager = object : LinearLayoutManager(activity, HORIZONTAL, false) {
                // The focused card slides to the start of the row, like Android TV launchers.
                override fun requestChildRectangleOnScreen(parent: RecyclerView, child: View, rect: Rect, immediate: Boolean, focusedChildVisible: Boolean): Boolean {
                    // keep the card's margin and padding so its zoom is not cut off at the edge
                    val dx = child.left - (child.layoutParams as ViewGroup.MarginLayoutParams).leftMargin - parent.paddingLeft
                    if (immediate) parent.scrollBy(dx, 0) else parent.smoothScrollBy(dx, 0)
                    return true
                }

                // RIGHT on the last card stays there; LEFT on the first one goes back to the sidebar
                // (otherwise the focus could land on a scrolled-out card of another row).
                override fun onInterceptFocusSearch(focused: View, direction: Int): View? {
                    if (focused.layoutParams !is RecyclerView.LayoutParams) return null
                    return when {
                        direction == View.FOCUS_RIGHT && getPosition(focused) == itemCount - 1 -> focused
                        direction == View.FOCUS_LEFT && getPosition(focused) == 0 -> sidebarEntry()
                        else -> null
                    }
                }
            }
            binding.cards.itemAnimator = null
            // the focused card's zoom overflows its row a little
            binding.root.clipChildren = false
            binding.cards.clipChildren = false
            return RowHolder(binding)
        }

        override fun onBindViewHolder(holder: RowHolder, position: Int) {
            val row = rows.getValue(sections[position])
            with(holder.binding) {
                // a followed game with nobody live (in the chosen languages) gets no row at all
                val hidden = row.empty && row.page is TvViewModel.Page.GameRow
                root.layoutParams.height = if (hidden) 0 else ViewGroup.LayoutParams.WRAP_CONTENT
                root.visibility = if (hidden) View.GONE else View.VISIBLE
                title.text = rowTitle(row.page)
                if (cards.adapter !== row.adapter) cards.swapAdapter(row.adapter, true)
                cards.visibility = if (row.empty) View.GONE else View.VISIBLE
                empty.visibility = if (row.empty) View.VISIBLE else View.GONE
                empty.setText(when (row.page) {
                    TvViewModel.Page.Favorites -> R.string.tv_favorites_hint
                    TvViewModel.Page.Suggestions -> R.string.tv_suggestions_hint
                    else -> R.string.nothing_here
                })
            }
        }

        override fun onViewRecycled(holder: RowHolder) {
            holder.binding.cards.adapter = null
        }
    }

    /** Shows [pages] as rows; [rebuild] recreates the cards (e.g. after a card size change). */
    fun setSections(pages: List<TvViewModel.Page>, rebuild: Boolean = false) {
        if (pages == sections && !rebuild) return
        rows.keys.filter { rebuild || it !in pages }.forEach { rows.remove(it)?.job?.cancel() }
        pages.forEach { rows.getOrPut(it) { Row(it) } }
        val old = sections
        sections = pages
        list.setItemViewCacheSize(pages.size)
        if (rebuild || list.adapter == null) {
            list.adapter = rowsAdapter
        } else {
            // only insert, remove or move the rows that changed, so the others keep their cards and the focus
            DiffUtil.calculateDiff(object : DiffUtil.Callback() {
                override fun getOldListSize() = old.size
                override fun getNewListSize() = pages.size
                override fun areItemsTheSame(oldItemPosition: Int, newItemPosition: Int) = old[oldItemPosition] == pages[newItemPosition]
                override fun areContentsTheSame(oldItemPosition: Int, newItemPosition: Int) = true
            }).dispatchUpdatesTo(rowsAdapter)
        }
    }

    /** Refreshes the row titles (top streams shows its language filter). */
    fun refreshTitles() {
        rowsAdapter.notifyItemRangeChanged(0, sections.size)
    }

    /** Reloads a row, e.g. favorites after a change or suggestions after watching another channel. */
    fun refresh(page: TvViewModel.Page) {
        rows[page]?.adapter?.refresh()
    }

    /** Position of [page]'s row, or of the first row it groups (e.g. "My games live"). */
    private fun indexOf(page: TvViewModel.Page) = sections.indexOfFirst { it == page || it.parent == page }

    fun scrollTo(page: TvViewModel.Page, smooth: Boolean = true) {
        val position = indexOf(page).takeIf { it >= 0 } ?: return
        val layoutManager = list.layoutManager as LinearLayoutManager
        if (!smooth) {
            layoutManager.scrollToPositionWithOffset(position, 0)
            return
        }
        layoutManager.startSmoothScroll(object : LinearSmoothScroller(activity) {
            override fun getVerticalSnapPreference() = SNAP_TO_START
            override fun calculateSpeedPerPixel(displayMetrics: DisplayMetrics) = 40f / displayMetrics.densityDpi
        }.apply { targetPosition = position })
    }

    /** Moves the focus into the row of [page] as soon as it has cards (start of the app). */
    fun focusWhenLoaded(page: TvViewModel.Page) {
        pendingFocus = page
        tryPendingFocus()
    }

    fun cancelPendingFocus() {
        pendingFocus = null
    }

    private fun tryPendingFocus(attempt: Int = 0) {
        val page = pendingFocus ?: return
        if (attempt == 0 && pendingFocusRunning) return
        pendingFocusRunning = true
        // the row's cards are laid out a frame or two after their data arrives
        list.postDelayed({
            if (pendingFocus == page && !focusRow(page) && attempt < 20) {
                tryPendingFocus(attempt + 1)
            } else {
                if (pendingFocus == page) pendingFocus = null
                pendingFocusRunning = false
            }
        }, 100)
    }

    /** Moves the focus into the row of [page], on its first fully visible card. */
    fun focusRow(page: TvViewModel.Page): Boolean {
        // first row of the section that has cards (a game row may be empty)
        val position = sections.indices.firstOrNull { i ->
            (sections[i] == page || sections[i].parent == page) && rows[sections[i]]?.empty == false
        } ?: indexOf(page)
        val holder = list.findViewHolderForAdapterPosition(position) as? RowHolder ?: return false
        val layoutManager = holder.binding.cards.layoutManager as LinearLayoutManager
        val card = layoutManager.findFirstCompletelyVisibleItemPosition().takeIf { it >= 0 } ?: 0
        // FromTouch: also leaves touch mode, where Android gives no focus at all (e.g. app started without a key press)
        return holder.binding.cards.findViewHolderForAdapterPosition(card)?.itemView?.requestFocusFromTouch() == true
    }

    /** Gives the focus back to the card that was focused before opening a game, a channel or a stream. */
    fun restoreFocus(): Boolean = lastFocused?.takeIf { it.isAttachedToWindow }?.requestFocus() == true

    private fun rowTitle(page: TvViewModel.Page): String {
        if (page == TvViewModel.Page.Suggestions) {
            TvSettings.suggestionsSeed(activity)?.second?.let { return activity.getString(R.string.tv_suggestions_from, it) }
        }
        val languages = viewModel.languages.value
        return if (page.usesLanguages && languages.isNotEmpty()) {
            page.title(activity) + "  ·  " + languages.joinToString(", ") { it.uppercase() }
        } else {
            page.title(activity)
        }
    }

    private fun cardOf(view: View): View {
        var v = view
        while (v.parent !is RecyclerView) v = v.parent as View
        return v
    }

    private fun cardWidth(page: TvViewModel.Page): Int {
        val dp = when (TvSettings.cardSize(activity)) {
            TvSettings.SIZE_SMALL -> if (page.wideCards) 240 else 130
            TvSettings.SIZE_LARGE -> if (page.wideCards) 360 else 190
            else -> if (page.wideCards) 300 else 160
        }
        return (dp * activity.resources.displayMetrics.density).toInt()
    }
}
