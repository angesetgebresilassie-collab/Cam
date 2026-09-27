package com.cam.photos.data

import android.content.ContentUris
import android.content.Context
import android.provider.MediaStore
import androidx.paging.PagingSource
import androidx.paging.PagingState

data class MediaItem(
    val id: Long,
    val uri: android.net.Uri,
    val dateTakenMillis: Long,
    val isVideo: Boolean,
    val durationMillis: Long = 0L
)

/**
 * Pages through MediaStore (images + video) newest-first without ever
 * loading the full library into memory. This is the key anti-lag piece
 * for the Library grid: each page is a small cursor window, and Coil
 * handles thumbnail decoding/caching per-item lazily as cells scroll in.
 */
class MediaPagingSource(
    private val context: Context
) : PagingSource<Int, MediaItem>() {

    override fun getRefreshKey(state: PagingState<Int, MediaItem>): Int? {
        return state.anchorPosition?.let { anchor ->
            state.closestPageToPosition(anchor)?.prevKey?.plus(1)
                ?: state.closestPageToPosition(anchor)?.nextKey?.minus(1)
        }
    }

    override fun load(params: LoadParams<Int>): LoadResult<Int, MediaItem> {
        val page = params.key ?: 0
        val pageSize = params.loadSize

        return try {
            val items = queryPage(offset = page * pageSize, limit = pageSize)
            LoadResult.Page(
                data = items,
                prevKey = if (page == 0) null else page - 1,
                nextKey = if (items.isEmpty()) null else page + 1
            )
        } catch (e: Exception) {
            LoadResult.Error(e)
        }
    }

    private fun queryPage(offset: Int, limit: Int): List<MediaItem> {
        val collection = MediaStore.Files.getContentUri("external")
        val projection = arrayOf(
            MediaStore.Files.FileColumns._ID,
            MediaStore.Files.FileColumns.DATE_TAKEN,
            MediaStore.Files.FileColumns.MEDIA_TYPE,
            MediaStore.Video.VideoColumns.DURATION
        )
        val selection = "${MediaStore.Files.FileColumns.MEDIA_TYPE} = ? OR " +
            "${MediaStore.Files.FileColumns.MEDIA_TYPE} = ?"
        val selectionArgs = arrayOf(
            MediaStore.Files.FileColumns.MEDIA_TYPE_IMAGE.toString(),
            MediaStore.Files.FileColumns.MEDIA_TYPE_VIDEO.toString()
        )
        val sortOrder = "${MediaStore.Files.FileColumns.DATE_TAKEN} DESC LIMIT $limit OFFSET $offset"

        val results = mutableListOf<MediaItem>()
        context.contentResolver.query(
            collection, projection, selection, selectionArgs, sortOrder
        )?.use { cursor ->
            val idCol = cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns._ID)
            val dateCol = cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns.DATE_TAKEN)
            val typeCol = cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns.MEDIA_TYPE)
            val durCol = cursor.getColumnIndex(MediaStore.Video.VideoColumns.DURATION)

            while (cursor.moveToNext()) {
                val id = cursor.getLong(idCol)
                val isVideo = cursor.getInt(typeCol) == MediaStore.Files.FileColumns.MEDIA_TYPE_VIDEO
                val baseUri = if (isVideo) MediaStore.Video.Media.EXTERNAL_CONTENT_URI
                    else MediaStore.Images.Media.EXTERNAL_CONTENT_URI
                results += MediaItem(
                    id = id,
                    uri = ContentUris.withAppendedId(baseUri, id),
                    dateTakenMillis = cursor.getLong(dateCol),
                    isVideo = isVideo,
                    durationMillis = if (isVideo && durCol >= 0) cursor.getLong(durCol) else 0L
                )
            }
        }
        return results
    }
}
