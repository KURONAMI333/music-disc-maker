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

    /**
     * もう鳴っていないか (自然終了・撤去済み)。
     *
     * @return 停止していれば {@code true}
     */
    boolean isStopped();

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
