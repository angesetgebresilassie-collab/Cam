package com.cam.photos.data

import android.content.ContentUris
import android.content.Context
import android.provider.MediaStore
import androidx.paging.PagingSource
import androidx.paging.PagingState

/**
 * Android translation of the Swift app's PHAsset image fetch.
 * Equivalent to PHAsset.fetchAssets(with: .image, ...).
 */
data class MediaItem(
    val id: Long,
    val uri: android.net.Uri,
    val dateTakenMillis: Long
)

class MediaPagingSource(
    private val context: Context
) : PagingSource<Int, MediaItem>() {

    override fun getRefreshKey(state: PagingState<Int, MediaItem>): Int? {
        return state.anchorPosition?.let { anchor ->
            state.closestPageToPosition(anchor)?.prevKey?.plus(1)
                ?: state.closestPageToPosition(anchor)?.nextKey?.minus(1)
        }
    }

    override suspend fun load(params: LoadParams<Int>): LoadResult<Int, MediaItem> {
        val page = params.key ?: 0
        val pageSize = params.loadSize

        return try {
            val items = queryImages(page * pageSize, pageSize)
            LoadResult.Page(
                data = items,
                prevKey = if (page == 0) null else page - 1,
                nextKey = if (items.isEmpty()) null else page + 1
            )
        } catch (error: Exception) {
            LoadResult.Error(error)
        }
    }

    private fun queryImages(offset: Int, limit: Int): List<MediaItem> {
        val collection = MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        val projection = arrayOf(
            MediaStore.Images.Media._ID,
            MediaStore.Images.Media.DATE_TAKEN
        )
        val sortOrder =
            "${MediaStore.Images.Media.DATE_TAKEN} DESC LIMIT $limit OFFSET $offset"

        val result = ArrayList<MediaItem>(limit)

        context.contentResolver.query(
            collection, projection, null, null, sortOrder
        )?.use { cursor ->
            val idColumn =
                cursor.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
            val dateColumn =
                cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DATE_TAKEN)

            while (cursor.moveToNext()) {
                val id = cursor.getLong(idColumn)
                result += MediaItem(
                    id = id,
                    uri = ContentUris.withAppendedId(collection, id),
                    dateTakenMillis = cursor.getLong(dateColumn)
                )
            }
        }
        return result
    }
}
