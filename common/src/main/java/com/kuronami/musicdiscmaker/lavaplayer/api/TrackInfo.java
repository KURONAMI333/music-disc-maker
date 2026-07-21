package com.kuronami.musicdiscmaker.lavaplayer.api;

/**
 * URL から解決した曲のメタ情報。LavaPlayer の AudioTrackInfo を mod 側に渡すための
 * 依存ゼロの DTO (隔離 classloader 境界をまたぐので LavaPlayer 型を直接露出しない)。
 */
public record TrackInfo(
        String title,
        String author,
        long durationMs,
        String uri,
        String identifier,
        boolean stream,
        String thumbnailUrl) {
}
