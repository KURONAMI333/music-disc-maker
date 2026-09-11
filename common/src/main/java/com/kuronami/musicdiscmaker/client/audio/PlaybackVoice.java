package com.kuronami.musicdiscmaker.client.audio;

/**
 * 鳴っている 1 音源への最小の口。再生の管理側 ({@link PlaybackSessions} /
 * {@link LivePlaybackRegistry}) はこれ越しにしか音源を触らないので、判断のロジックが
 * {@code Minecraft} を掴まずに済む (= headless テストに載る)。
 *
 * <p>実装は {@link DiscSoundInstance} だけ。テストは偽物を差して、管理側が
 * 「いつ止めるか・いつ値を押し込むか」を実際にやっているかを見る。
 */
public interface PlaybackVoice {

    /** Number of OpenAL streaming voices owned by this logical playback. */
    default int voiceCount() {
        return 1;
    }

    /**
     * もう鳴っていないか (自然終了・撤去済み)。
     *
     * <p><b>名前を {@code isStopped} にしてはいけない。</b> 実装の {@link DiscSoundInstance} は
     * バニラの {@code AbstractTickableSoundInstance} を継承しており、そちらの {@code isStopped()} は
     * <b>remap の対象</b>。Fabric の production は intermediary 名なので、継承した実体は
     * {@code method_XXXX()} になり、このインターフェースの {@code isStopped()} を満たすものが
     * 階層のどこにも無くなる → {@code invokeinterface} した瞬間に {@code AbstractMethodError}。
     * dev は named マッピングで走るので<b>開発環境では絶対に再現しない</b>
     * (2026-09-02・出荷 jar の bytecode で確認)。
     *
     * @return 停止していれば {@code true}
     */
    boolean isVoiceStopped();

    /**
     * 聴取モデルのライブ更新。
     *
     * @param value true = 従来どおりの positional / false = フラット (BGM モード)
     */
    void setDirectional(boolean value);

    /**
     * 可聴範囲のライブ更新。
     *
     * @param value 可聴範囲 (ブロック)。0 = client config の既定を使う
     */
    void setRangeBlocks(int value);

    /**
     * 音量のライブ更新。
     *
     * @param value 音量 (%)。100 = 通常
     */
    void setVolumePercent(int value);

    /**
     * 停止して音源とチャンネルを手放す。
     *
     * <p>「停止フラグを立てる」だけでは足りない — {@code SoundManager} から外さないと
     * OpenAL のチャンネルが掴まれたままになる。二重に呼ばれても無害であること。
     */
    void stopAndRelease();
}
