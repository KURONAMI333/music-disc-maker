package com.kuronami.musicdiscmaker.lavaplayer;

import com.kuronami.musicdiscmaker.lavaplayer.api.IAudioSource;

/**
 * normalize の入力 1 本。宣言尺を取り、pass ごとに 48kHz / mono / S16LE の
 * {@link IAudioSource} を開き直す。
 *
 * <p>通常の入力は LavaPlayer の入力 registry (内容 probe) をそのまま包む。probe が
 * 対応しない入力 (ALAC の M4A・FLAC 音声を持つ MKV) だけ、container を自前で読む
 * 直 decode ({@link DirectPcmInput}) に差し替える。どちらも「48kHz mono S16LE を
 * 先頭から pull できる」という同じ契約で normalize から見える。
 */
interface PcmInput {

    /** 入力の種類。ログと試験が「通常経路か直 decode か」を区別するために持つ。 */
    enum Origin {
        /** LavaPlayer の入力 registry (内容 probe) 経由。 */
        PLAYER,
        /** ALAC (M4A) を自前で decode する。 */
        ALAC,
        /** MKV 内の FLAC を自前で decode する。 */
        MATROSKA_FLAC
    }

    /** 宣言尺 (ms)。分からない入力は {@code 0} / {@link Long#MAX_VALUE} のまま返す。 */
    long durationMs();

    Origin origin();

    /**
     * 48kHz / mono / 16bit little-endian の PCM を返すソースを開く。pass ごとに呼ばれ、
     * 毎回入力の先頭から decode する。返したソースの close は呼び出し側 (normalize) が行う。
     */
    IAudioSource open();
}
