package com.kuronami.musicdiscmaker.lavaplayer;

import java.io.IOException;

/**
 * decoder の出力を 48kHz / mono の bytes にして queue へ積む段。{@link DirectPcmSource} が producer
 * スレッドから使う。実装は {@link MonoPcmConverter}。試験は差し替えて終了処理の順序を見る。
 */
interface PcmConverter extends PcmFrameSink {

    /** 末尾の flush と queue の終端記録。正常終了の時だけ呼ぶ。 */
    void finish() throws IOException;

    /** native 資源の解放。成功・失敗・中断のどれでも producer が 1 回呼ぶ。冪等。 */
    void close();
}
