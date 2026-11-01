package com.kuronami.musicdiscmaker.audio.local;

/** LocalAudioServer の、Minecraft に依存しない判定。 */
final class LocalServerDecisions {
    private LocalServerDecisions() {}

    /**
     * サーバーが転送を破棄した時に client へ返す理由。返さない (null) のは、既に取消・期限切れで
     * 通知済み・本人の取消の転送と、接続が切れた相手 (送り先が無い)。通知しないと client は
     * 「Sending audio」のまま止まる。
     */
    static String dropReason(boolean alreadyCancelled, boolean connected) {
        return !alreadyCancelled && connected ? "expired" : null;
    }
}
