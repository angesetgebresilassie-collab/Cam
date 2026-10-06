package com.cam.app

import java.util.stream.IntStream

/** Runs [body] over disjoint row ranges of [0, h) on all available cores. */
internal fun parallelRows(h: Int, body: (y0: Int, y1: Int) -> Unit) {
    val n = Runtime.getRuntime().availableProcessors().coerceIn(1, 8)
    val chunk = (h + n - 1) / n
    IntStream.range(0, n).parallel().forEach { i ->
        val y0 = i * chunk
        val y1 = minOf(h, y0 + chunk)
        if (y0 < y1) body(y0, y1)
    }
}
