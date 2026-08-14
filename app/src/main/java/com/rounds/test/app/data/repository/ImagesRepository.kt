package com.rounds.test.app.data.repository

import com.rounds.test.app.presentation.model.ImageItem

/**
 * The presentation layer's only door to image-list data.
 *
 * It exists so the ViewModel never sees a connection, a status code or a JSON document, and so the
 * ViewModel's own tests can be driven by a fake. There is no use case or interactor above it — one
 * fetch does not need a third layer.
 */
internal interface ImagesRepository {

    suspend fun getImages(): List<ImageItem>
}
