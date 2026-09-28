package com.cam.photos.ui.library

import android.content.Context
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.paging.Pager
import androidx.paging.PagingConfig
import androidx.paging.PagingData
import androidx.paging.cachedIn
import androidx.paging.compose.collectAsLazyPagingItems
import androidx.paging.compose.itemKey
import coil.ImageLoader
import coil.compose.AsyncImage
import coil.decode.VideoFrameDecoder
import coil.memory.MemoryCache
import coil.request.ImageRequest
import com.cam.photos.data.MediaItem
import com.cam.photos.data.MediaPagingSource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch

/**
 * Library grid, iOS-Photos style, with smooth pinch-to-zoom.
 *
 * Why pinching stays smooth with thousands of photos:
 *  - While fingers are down, the whole grid is only scaled with graphicsLayer
 *    (a GPU transform). No relayout, no recomposition, no image reloads.
 *  - Only when the pinch crosses a threshold do we swap the column count once,
 *    then spring the scale back to 1 so the change looks continuous.
 *  - Paging3 keeps just a few pages of lightweight MediaItem objects in memory,
 *    Coil decodes thumbnails lazily at the size each cell actually needs.
 */
fun mediaPagingFlow(context: Context, scope: CoroutineScope): Flow<PagingData<MediaItem>> {
    return Pager(
        config = PagingConfig(pageSize = 90, prefetchDistance = 60, enablePlaceholders = false),
        pagingSourceFactory = { MediaPagingSource(context) }
    ).flow.cachedIn(scope)
}

// Column counts for each zoom level, like iOS Photos (big -> tiny cells).
private val COLUMN_STEPS = listOf(1, 3, 5, 8)
private const val DEFAULT_STEP = 1 // 3 columns

@Composable
fun LibraryGridScreen(
    pagingFlow: Flow<PagingData<MediaItem>>,
    onItemClick: (MediaItem) -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val lazyItems = pagingFlow.collectAsLazyPagingItems()

    var stepIndex by rememberSaveable { mutableIntStateOf(DEFAULT_STEP) }
    val columns = COLUMN_STEPS[stepIndex]
    val pinchScale = remember { Animatable(1f) }

    // One shared loader: video frame decoding + a generous memory cache.
    val imageLoader = remember(context) {
        ImageLoader.Builder(context)
            .components { add(VideoFrameDecoder.Factory()) }
            .memoryCache { MemoryCache.Builder(context).maxSizePercent(0.30).build() }
            .crossfade(false) // crossfades cost frames during fast scrolling
            .build()
    }

    val screenWidthPx = context.resources.displayMetrics.widthPixels
    val thumbPx = (screenWidthPx / columns).coerceIn(120, 720)

    Box(
        modifier = Modifier
            .fillMaxSize()
            .pinchZoom(
                onZoom = { zoom ->
                    scope.launch {
                        var s = (pinchScale.value * zoom).coerceIn(0.4f, 3f)
                        val idx = stepIndex
                        if (s > 1.3f && idx > 0) {
                            // zoom in -> fewer, bigger cells
                            val newIdx = idx - 1
                            s *= COLUMN_STEPS[newIdx].toFloat() / COLUMN_STEPS[idx]
                            stepIndex = newIdx
                        } else if (s < 0.77f && idx < COLUMN_STEPS.lastIndex) {
                            // zoom out -> more, smaller cells
                            val newIdx = idx + 1
                            s *= COLUMN_STEPS[newIdx].toFloat() / COLUMN_STEPS[idx]
                            stepIndex = newIdx
                        }
                        pinchScale.snapTo(s)
                    }
                },
                onEnd = { scope.launch { pinchScale.animateTo(1f, spring()) } }
            )
    ) {
        LazyVerticalGrid(
            columns = GridCells.Fixed(columns),
            horizontalArrangement = Arrangement.spacedBy(1.dp),
            verticalArrangement = Arrangement.spacedBy(1.dp),
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer {
                    // Read inside the lambda: animates on the GPU, no recomposition.
                    scaleX = pinchScale.value
                    scaleY = pinchScale.value
                }
        ) {
            items(
                count = lazyItems.itemCount,
                key = lazyItems.itemKey { it.id }
            ) { index ->
                val item = lazyItems[index]
                if (item != null) {
                    ThumbnailCell(item = item, sizePx = thumbPx, imageLoader = imageLoader)
                }
            }
        }
    }
}

@Composable
private fun ThumbnailCell(item: MediaItem, sizePx: Int, imageLoader: ImageLoader) {
    AsyncImage(
        model = ImageRequest.Builder(LocalContext.current)
            .data(item.uri)
            .size(sizePx) // decode only as big as the cell needs
            .build(),
        imageLoader = imageLoader,
        contentDescription = null,
        contentScale = ContentScale.Crop,
        modifier = Modifier.aspectRatio(1f)
    )
}

/**
 * Watches for two-finger pinches BEFORE the grid sees them (Initial pass) and
 * only consumes the event when 2+ fingers are down, so one-finger scrolling
 * still works normally.
 */
private fun Modifier.pinchZoom(
    onZoom: (Float) -> Unit,
    onEnd: () -> Unit
): Modifier = pointerInput(Unit) {
    awaitEachGesture {
        awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
        do {
            val event = awaitPointerEvent(PointerEventPass.Initial)
            if (event.changes.count { it.pressed } >= 2) {
                val zoom = event.calculateZoom()
                if (zoom != 1f) onZoom(zoom)
                event.changes.forEach { it.consume() }
            }
        } while (event.changes.any { it.pressed })
        onEnd()
    }
}
