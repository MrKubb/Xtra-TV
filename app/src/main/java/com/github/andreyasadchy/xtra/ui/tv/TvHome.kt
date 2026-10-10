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
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.LinearSmoothScroller
import androidx.recyclerview.widget.RecyclerView
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
    private val onOpen: (Any) -> Unit,
    private val onCardFocus: (TvCard, ImageView, Boolean) -> Unit,
    private val onRowFocus: (TvViewModel.Page) -> Unit,
    private val onRowCount: (TvViewModel.Page, Int?) -> Unit,
    /** Where LEFT on the first card of a row goes: the current sidebar entry. */
    private val sidebarEntry: () -> View?,
) {
    private var sections = emptyList<TvViewModel.Page>()
    private val rows = HashMap<TvViewModel.Page, Row>()
    private var lastFocused: View? = null

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
        }, { cardWidth(page) })

        init {
            adapter.addLoadStateListener { states ->
                val isEmpty = states.refresh is LoadState.NotLoading && adapter.itemCount == 0
                if (isEmpty != empty) {
                    empty = isEmpty
                    sections.indexOf(page).takeIf { it >= 0 }?.let { rowsAdapter.notifyItemChanged(it) }
                }
                onRowCount(page, adapter.itemCount.takeIf { states.append.endOfPaginationReached && it > 0 })
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
            return RowHolder(binding)
        }

        override fun onBindViewHolder(holder: RowHolder, position: Int) {
            val row = rows.getValue(sections[position])
            with(holder.binding) {
                title.text = rowTitle(row.page)
                if (cards.adapter !== row.adapter) cards.swapAdapter(row.adapter, true)
                cards.visibility = if (row.empty) View.GONE else View.VISIBLE
                empty.visibility = if (row.empty) View.VISIBLE else View.GONE
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
        sections = pages
        list.setItemViewCacheSize(pages.size)
        list.adapter = rowsAdapter
    }

    /** Refreshes the row titles (top streams shows its language filter). */
    fun refreshTitles() {
        rowsAdapter.notifyItemRangeChanged(0, sections.size)
    }

    fun scrollTo(page: TvViewModel.Page, smooth: Boolean = true) {
        val position = sections.indexOf(page).takeIf { it >= 0 } ?: return
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

    /** Moves the focus into the row of [page], on its first fully visible card. */
    fun focusRow(page: TvViewModel.Page) {
        val holder = list.findViewHolderForAdapterPosition(sections.indexOf(page)) as? RowHolder ?: return
        val layoutManager = holder.binding.cards.layoutManager as LinearLayoutManager
        val position = layoutManager.findFirstCompletelyVisibleItemPosition().takeIf { it >= 0 } ?: 0
        holder.binding.cards.findViewHolderForAdapterPosition(position)?.itemView?.requestFocus()
    }

    /** Gives the focus back to the card that was focused before opening a game, a channel or a stream. */
    fun restoreFocus(): Boolean = lastFocused?.takeIf { it.isAttachedToWindow }?.requestFocus() == true

    private fun rowTitle(page: TvViewModel.Page): String {
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
