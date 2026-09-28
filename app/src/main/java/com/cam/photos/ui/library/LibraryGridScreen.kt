package com.cam.photos.ui.library

import android.content.Context
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.spring
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
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
import androidx.compose.ui.unit.Dp
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
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch

/**
 * Library grid with pinch-to-zoom.
 *
 * Lag fixes vs. the first version:
 *  - ONE fixed thumbnail size for every zoom level, so changing columns never
 *    invalidates the image cache or triggers a wave of re-decodes.
 *  - Wide hysteresis (1.5 / 0.65) and continuity scaling, so a wobbling pinch
 *    can't flip-flop between column counts every few frames.
 *  - Pinch state is a plain float read in a graphicsLayer lambda: no coroutine
 *    per touch event, no recomposition while fingers move.
 *  - RGB_565 thumbnails (half the memory / bandwidth), stable item keys,
 *    requests remembered per cell.
 */
fun mediaPagingFlow(context: Context, scope: CoroutineScope): Flow<PagingData<MediaItem>> {
    return Pager(
        config = PagingConfig(pageSize = 90, prefetchDistance = 60, enablePlaceholders = false),
        pagingSourceFactory = { MediaPagingSource(context) }
    ).flow.cachedIn(scope)
}

private val COLUMN_STEPS = listOf(2, 3, 5, 8)
private const val DEFAULT_STEP = 1 // 3 columns
private const val THUMB_PX = 320
private const val ZOOM_IN_THRESHOLD = 1.5f
private const val ZOOM_OUT_THRESHOLD = 0.65f

@Composable
fun LibraryGridScreen(
    pagingFlow: Flow<PagingData<MediaItem>>,
    bottomPadding: Dp,
    onItemClick: (MediaItem) -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val lazyItems = pagingFlow.collectAsLazyPagingItems()

    var stepIndex by rememberSaveable { mutableIntStateOf(DEFAULT_STEP) }
    var liveScale by remember { mutableFloatStateOf(1f) }
    var settleJob by remember { mutableStateOf<Job?>(null) }

    val imageLoader = remember(context) {
        ImageLoader.Builder(context)
            .components { add(VideoFrameDecoder.Factory()) }
            .memoryCache { MemoryCache.Builder(context).maxSizePercent(0.30).build() }
            .crossfade(false)
            .build()
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .pinchZoom(
                onZoom = { zoom ->
                    settleJob?.cancel()
                    var s = (liveScale * zoom).coerceIn(0.4f, 3f)
                    var idx = stepIndex
                    while (s > ZOOM_IN_THRESHOLD && idx > 0) {
                        val next = idx - 1
                        s *= COLUMN_STEPS[next].toFloat() / COLUMN_STEPS[idx]
                        idx = next
                    }
                    while (s < ZOOM_OUT_THRESHOLD && idx < COLUMN_STEPS.lastIndex) {
                        val next = idx + 1
                        s *= COLUMN_STEPS[next].toFloat() / COLUMN_STEPS[idx]
                        idx = next
                    }
                    if (idx != stepIndex) stepIndex = idx
                    liveScale = s
                },
                onEnd = {
                    settleJob?.cancel()
                    settleJob = scope.launch {
                        animate(
                            initialValue = liveScale,
                            targetValue = 1f,
                            animationSpec = spring(stiffness = Spring.StiffnessMediumLow)
                        ) { value, _ -> liveScale = value }
                    }
                }
            )
    ) {
        LazyVerticalGrid(
            columns = GridCells.Fixed(COLUMN_STEPS[stepIndex]),
            contentPadding = PaddingValues(bottom = bottomPadding),
            horizontalArrangement = Arrangement.spacedBy(1.dp),
            verticalArrangement = Arrangement.spacedBy(1.dp),
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer {
                    scaleX = liveScale
                    scaleY = liveScale
                }
        ) {
            items(
                count = lazyItems.itemCount,
                key = lazyItems.itemKey { it.id }
            ) { index ->
                val item = lazyItems[index]
                if (item != null) {
                    ThumbnailCell(item = item, imageLoader = imageLoader)
                }
            }
        }
    }
}

@Composable
private fun ThumbnailCell(item: MediaItem, imageLoader: ImageLoader) {
    val context = LocalContext.current
    val request = remember(item.id) {
        ImageRequest.Builder(context)
            .data(item.uri)
            .size(THUMB_PX)
            .allowRgb565(true)
            .memoryCacheKey("t${item.id}")
            .build()
    }
    AsyncImage(
        model = request,
        imageLoader = imageLoader,
        contentDescription = null,
        contentScale = ContentScale.Crop,
        modifier = Modifier.aspectRatio(1f)
    )
}

/**
 * Sees two-finger pinches BEFORE the grid (Initial pass) and only consumes
 * events while 2+ fingers are down, so one-finger scrolling is untouched.
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
