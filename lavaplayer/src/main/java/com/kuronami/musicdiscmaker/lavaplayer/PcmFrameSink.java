package com.kuronami.musicdiscmaker.lavaplayer;

import java.io.IOException;

/**
 * 直 decode の受け口。decoder が native rate の PCM を frame 単位で押し出す。
 *
 * <p>受け取った側 ({@link MonoPcmConverter}) が 48kHz / mono へ直して queue に積む。
 * 全曲をメモリに溜めないための境界なので、実装は受け取った分をその場で流すこと。
 */
@FunctionalInterface
interface PcmFrameSink {

    /**
     * @param interleaved channel 順に並べた S16LE の sample (長さは {@code frames × channels} 以上)
     * @param frames      1 channel あたりの sample 数
     */
    void accept(short[] interleaved, int frames) throws IOException;
}
