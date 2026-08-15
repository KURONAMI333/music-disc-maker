package com.kuronami.musicdiscmaker.gametest;

import com.kuronami.musicdiscmaker.client.audio.PlaybackVoice;

/**
 * 鳴っている音源の代役。管理側が「いつ止めたか・どの値を押し込んだか」を後から読めるように、
 * 受けた操作をそのまま記録するだけの実装。
 *
 * <p>本物 ({@code DiscSoundInstance}) は {@code Minecraft} を掴んでいて dedicated server では
 * 読めないので、管理側が触る面だけを持つこれを差す。
 */
final class FakeVoice implements PlaybackVoice {

    /** 名前 (失敗メッセージで「どちらの曲か」を読めるようにするためだけ)。 */
    final String name;

    boolean stopped;
    int stopCalls;
    boolean directional = true;
    int rangeBlocks;
    int volumePercent = 100;

    FakeVoice(String name) {
        this.name = name;
    }

    @Override
    public boolean isStopped() {
        return stopped;
    }

    @Override
    public void setDirectional(boolean value) {
        this.directional = value;
    }

    @Override
    public void setRangeBlocks(int value) {
        this.rangeBlocks = value;
    }

    @Override
    public void setVolumePercent(int value) {
        this.volumePercent = value;
    }

    @Override
    public void stopAndRelease() {
        this.stopped = true;
        this.stopCalls++;
    }

    @Override
    public String toString() {
        return name;
    }
}
