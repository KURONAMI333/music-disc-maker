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

    /**
     * JVM 自体の異常 (OOM 等) か。取込は他の失敗と同じく畳むが、ログは error で残す。投げ直しても
     * ScheduledThreadPoolExecutor の Future に飲まれて誰にも届かない。
     */
    static boolean vmFatal(Throwable failure) {
        return failure instanceof VirtualMachineError;
    }

    /**
     * BEGIN の形式的な拒否理由。null なら通す。盤の有無 ("disc") と区別するのは、client が
     * "disc" を「盤を入れれば通る」待ちとして扱うため。menu が無い (画面が閉じた・container のずれ) 時は
     * 待っても通らないので "expired"、hash や size の不正は取込失敗として終える。
     */
    static String beginRejection(boolean menuPresent, String hash, long size, long limit) {
        if (!menuPresent) return "expired";
        if (hash == null || !hash.matches("[a-f0-9]{64}") || size <= 0 || size > limit) return "import_failed";
        return null;
    }
}
