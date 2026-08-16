package com.rounds.test.app.model

/**
 * One record of the supplied image list.
 *
 * The endpoint returns exactly these two fields, and both are shown on screen — the [id] as the
 * item's label, the [imageUrl] as the image the loader is asked for. There is no separate DTO
 * because there is nothing to translate: a second class with identical fields plus a mapper would
 * add indirection and no meaning.
 *
 * Note that [imageUrl] is not unique across the payload — the same picture appears under several
 * ids — so list identity must be derived from [id].
 */
internal data class ImageItem(
    val id: Int,
    val imageUrl: String,
)
