package com.cam.photos.ui.library

import android.content.Context
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
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
 * Translation of the Swift UICollectionView Photos grid:
 * - starts at 4 items per row
 * - pinch in: 4 -> 3 -> 1
 * - pinch out: 1 -> 3 -> 4
 * - square aspect-fill thumbnails
 * - lazy/cached image loading
 */
fun mediaPagingFlow(
    context: Context,
    scope: CoroutineScope
): Flow<PagingData<MediaItem>> =
    Pager(
        config = PagingConfig(
            pageSize = 60,
            prefetchDistance = 30,
            enablePlaceholders = false
        ),
        pagingSourceFactory = { MediaPagingSource(context) }
    ).flow.cachedIn(scope)

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun LibraryGridScreen(
    pagingFlow: Flow<PagingData<MediaItem>>,
    onItemClick: (MediaItem) -> Unit = {}
) {
    val lazyItems = pagingFlow.collectAsLazyPagingItems()
    var columns by remember { mutableIntStateOf(4) }
    var accumulatedZoom by remember { mutableFloatStateOf(1f) }

    LazyVerticalGrid(
        columns = GridCells.Fixed(columns),
        modifier = Modifier
            .fillMaxWidth()
            .pointerInput(columns) {
                detectTransformGestures { _, _, zoom, _ ->
                    accumulatedZoom *= zoom

                    if (accumulatedZoom >= 1.35f) {
                        accumulatedZoom = 1f
                        columns = when (columns) {
                            4 -> 3
                            3 -> 1
                            else -> 1
                        }
                    } else if (accumulatedZoom <= 0.75f) {
                        accumulatedZoom = 1f
                        columns = when (columns) {
                            1 -> 3
                            3 -> 4
                            else -> 4
                        }
                    }
                }
            }
    ) {
        items(
            count = lazyItems.itemCount,
            key = { index -> lazyItems[index]?.id ?: index }
        ) { index ->
            lazyItems[index]?.let { item ->
                AsyncImage(
                    model = ImageRequest.Builder(LocalContext.current)
                        .data(item.uri)
                        .size(320)
                        .crossfade(true)
                        .build(),
                    contentDescription = "Photo",
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .fillMaxWidth()
                        .aspectRatio(1f)
                )
            }
        }
    }
}
