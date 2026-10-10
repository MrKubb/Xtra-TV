package com.github.andreyasadchy.xtra.ui.tv

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.paging.PagingDataAdapter
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.RecyclerView
import coil3.imageLoader
import coil3.request.ImageRequest
import coil3.request.target
import coil3.request.transformations
import coil3.transform.CircleCropTransformation
import com.github.andreyasadchy.xtra.databinding.TvCardBinding

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
    /** [AVATAR]: a round channel picture centered in a 16:9 card (offline favorites in a row of streams). */
    enum class Shape(val ratio: String) { WIDE("16:9"), POSTER("3:4"), ROUND("1:1"), AVATAR("16:9") }
}

/**
 * Cards for the home rows and the full screen lists.
 * [width] fixes the card width (home rows); null fills the grid cell.
 * [onFocus] gets the card and its image view, used to place the video preview.
 * [onLongClick] is called when OK is held on a card (favorites menu).
 */
class TvCardAdapter(
    private val onClick: (Any) -> Unit,
    private val onFocus: (TvCard, ImageView, Boolean) -> Unit,
    private val width: (() -> Int)? = null,
    private val onLongClick: (Any) -> Unit = {},
) : PagingDataAdapter<TvCard, TvCardAdapter.Holder>(object : DiffUtil.ItemCallback<TvCard>() {
    override fun areItemsTheSame(oldItem: TvCard, newItem: TvCard) = oldItem.key == newItem.key
    override fun areContentsTheSame(oldItem: TvCard, newItem: TvCard) =
        oldItem.line1 == newItem.line1 && oldItem.line2 == newItem.line2 && oldItem.line3 == newItem.line3 && oldItem.image == newItem.image
}) {

    class Holder(val binding: TvCardBinding) : RecyclerView.ViewHolder(binding.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
        val binding = TvCardBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        width?.let { binding.root.layoutParams.width = it() }
        val holder = Holder(binding)
        binding.root.setOnFocusChangeListener { v, hasFocus ->
            val scale = if (hasFocus) 1.06f else 1f
            v.animate().scaleX(scale).scaleY(scale).setDuration(120).start()
            holder.bindingAdapterPosition.takeIf { it != RecyclerView.NO_POSITION }?.let { peek(it) }?.let {
                onFocus(it, binding.image, hasFocus)
            }
        }
        return holder
    }

    override fun onBindViewHolder(holder: Holder, position: Int) {
        val card = getItem(position) ?: return
        with(holder.binding) {
            root.setOnClickListener { onClick(card.item) }
            root.setOnLongClickListener {
                onLongClick(card.item)
                true
            }
            (image.layoutParams as ConstraintLayout.LayoutParams).dimensionRatio = card.shape.ratio
            val avatar = card.shape == TvCard.Shape.AVATAR
            image.scaleType = if (avatar) ImageView.ScaleType.FIT_CENTER else ImageView.ScaleType.CENTER_CROP
            val padding = if (avatar) (root.resources.displayMetrics.density * 24).toInt() else 0
            image.setPadding(padding, padding, padding, padding)
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
                    if (card.shape == TvCard.Shape.ROUND || card.shape == TvCard.Shape.AVATAR) transformations(CircleCropTransformation())
                    target(image)
                }.build())
            }
        }
    }
}
