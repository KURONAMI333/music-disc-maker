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

    /** I/O ワーカーの失敗として取込失敗へ畳んでよいか。JVM 自体の異常 (OOM 等) は握りつぶさない。 */
    static boolean recoverable(Throwable failure) {
        return !(failure instanceof VirtualMachineError);
    }
}
