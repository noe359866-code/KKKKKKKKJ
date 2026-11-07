package com.streamflixreborn.streamflix.utils

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters

/** No background catalog/artwork updates in the stream-only port. */
class AniWorldUpdateTvShowWorker(
    context: Context,
    workerParams: WorkerParameters,
) : CoroutineWorker(context, workerParams) {
    override suspend fun doWork(): Result = Result.success()
}
