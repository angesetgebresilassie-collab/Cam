package com.cam.photos.ui.library

import android.content.Context
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.paging.Pager
import androidx.paging.PagingConfig
import androidx.paging.PagingData
import androidx.paging.cachedIn
import androidx.paging.compose.collectAsLazyPagingItems
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.cam.photos.data.MediaItem
import com.cam.photos.data.MediaPagingSource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow

/**
 * Library grid, iOS-Photos style: dense square thumbnails, newest first.
 * Paging3 keeps only a few pages of MediaItem in memory (no bitmaps), and
 * Coil decodes/caches each thumbnail lazily as its cell scrolls into view.
 */
fun mediaPagingFlow(context: Context, scope: CoroutineScope): Flow<PagingData<MediaItem>> {
    return Pager(
        config = PagingConfig(pageSize = 60, prefetchDistance = 30, enablePlaceholders = false),
        pagingSourceFactory = { MediaPagingSource(context) }
    ).flow.cachedIn(scope)
}

@Composable
fun LibraryGridScreen(
    pagingFlow: Flow<PagingData<MediaItem>>,
    onItemClick: (MediaItem) -> Unit
) {
    val lazyItems = pagingFlow.collectAsLazyPagingItems()

    LazyVerticalGrid(
        columns = GridCells.Fixed(3),
        modifier = Modifier.fillMaxSize()
    ) {
        items(lazyItems.itemCount) { index ->
            val item = lazyItems[index]
            if (item != null) {
                ThumbnailCell(item = item)
            }
        }
    }
}

@Composable
private fun ThumbnailCell(item: MediaItem) {
    AsyncImage(
        model = ImageRequest.Builder(LocalContext.current)
            .data(item.uri)
            .size(240) // small thumbnail, not full-res — keeps grid scrolling smooth
            .crossfade(true)
            .build(),
        contentDescription = null,
        contentScale = ContentScale.Crop,
        modifier = Modifier.aspectRatio(1f)
    )
}
