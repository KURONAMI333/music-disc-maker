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

    /**
     * 上げ直し (RESTORE) を始められるか。null なら通す。
     *
     * <p>製作機の BEGIN と違い menu を持たないので、枠は restores 側で数える。同じ hash を既に誰かが
     * 上げている時は先着 1 本だけ通す (同じ 32MiB を 2 人から受け取らない)。拒否理由を "busy" に
     * するのは、client が間を空けて取り直せる待ちだから。"missing" はこの段では返さない
     * (「原本が無いと答えた hash か」は IO 側で notice を消費して判定する)。
     */
    static String restoreRejection(boolean slotFree, boolean playerFree, boolean sameHashInFlight,
                                   String hash, long size, long limit) {
        if (!slotFree || !playerFree || sameHashInFlight) return "busy";
        if (hash == null || !hash.matches("[a-f0-9]{64}") || size <= 0 || size > limit) return "import_failed";
        return null;
    }

    /**
     * 完成した原本が、要求された hash の名前で置かれたか。中身の sha256 一致は
     * {@code LocalAudioStore.finish} が転送中の digest で先に検査している。ここは保存先の取り違えを
     * 見るだけで、MP3 専用期の {@code hash.mp3} も同じ原本として認める。
     */
    static boolean storedUnderRequestedHash(String requestedHash, String storedName) {
        return requestedHash != null && storedName != null
                && (storedName.equals(requestedHash) || storedName.equals(requestedHash + ".mp3"));
    }
}
