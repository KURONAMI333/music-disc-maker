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
     * 「engine 側がこの音源をもう抱えていない」の判定規則 (pure — headless テストはここを叩く)。
     *
     * <p>vanilla の {@code SoundEngine} は channel が自然終了 (stream の終端で AL source が
     * AL_STOPPED へ落ちる) しても {@code SoundInstance#stop()} を呼ばない — 出荷対象の全 band
     * (1.20.1 / 1.21.1 / 1.21.11 / 26.2) の {@code SoundEngine#tickNonPaused} を実機 bytecode で
     * 確認済み。engine は channel 登録 ({@code instanceToChannel} / {@code tickingSounds}) だけを
     * 外すので、自分側の停止フラグを読むだけでは「鳴り終わった」が永久に見えない。そのままだと
     * voice は {@link PlaybackSessions#sweep()} にも拾われず、同じ URL の再送は dedup で捨てられ、
     * 同時再生枠も消費し続ける。
     *
     * <p>{@code SoundManager#isActive(instance)} は「engine が今も channel を抱えているか」を
     * 返すが、それ単独を条件にはしない — 受理前 (stream 生成がまだ呼ばれていない) の音源は
     * 「死んだ」のではなく「まだ来ていない」で、その区間は受理確認と first-audio deadline が
     * 管轄する。だから「engine が一度 stream を作った (= 受理した) 後に channel が無い」
     * という組み合わせでだけ真を返す。
     *
     * @param engineAccepted engine がこの音源の stream を一度でも作った (受理の証拠)
     * @param channelActive  engine が今もこの音源の channel を抱えている
     * @param speakerChild   fanout の非 master branch — desync で自分の channel だけが外れる
     *                       子で、再建は {@link SpeakerVoiceGroup} の desync 回復が担う。
     *                       ここで「止まった」と答えると group 側の掃除が branch ごと畳んで
     *                       再建が二度と効かなくなる
     * @return engine 側からもう鳴っていなければ {@code true}
     */
    static boolean voiceGone(boolean engineAccepted, boolean channelActive, boolean speakerChild) {
        return engineAccepted && !channelActive && !speakerChild;
    }

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
