package com.kuronami.musicdiscmaker.client.audio;

import com.kuronami.musicdiscmaker.component.CustomTrackData;
import com.kuronami.musicdiscmaker.lavaplayer.api.IAudioSource;

import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;

/**
 * compat 経路 (Create / Create Aeronautics / Sophisticated Core / Traveler's Backpack) が
 * {@link DiscSoundInstance} を作る唯一の入口。
 *
 * <h2>なぜ構築子を直接使わせないのか</h2>
 * {@code DiscSoundInstance} を作っただけでは、再生スレッドの中で落ちた失敗はどこにも届かない
 * ({@link CompatFaultWiring} の javadoc)。6 本ある compat 経路は全部その状態で、実機の症状は
 * 「再生中と出るのに無音・理由も曲名も出ない」だった。<b>繋ぎ忘れても正常系は動く</b>ので、
 * レビューでも実機でも見つかりにくい。
 *
 * <p>そこで {@code DiscSoundInstance} の構築子を package-private に落とし、
 * {@code compat.*} からはここを通るしか作る手段が無いようにしてある。配線を外すと
 * テストが赤くなるのではなく<b>コンパイルが通らない</b>。他バージョン帯へ写経したとき、
 * 一番落としやすいのがこの配線なので、機械で塞いでおく。
 *
 * <p>本体の再生 ({@link ClientPlaybackManager}) は同じ package なので構築子を直接使えるが、
 * そちらは失敗の重複抑止 ({@link PlaybackFailureNotices}) とラジオの沈黙という compat には
 * 無い事情を持つため、配線もそちら側に持っている。
 */
public final class CompatPlayback {

    private CompatPlayback() {
    }

    /**
     * 移動構造物などの任意アンカーに張り付く音源を作り、失敗の届け先を繋いで返す。
     *
     * @param anchor        音源の位置と存在を供給するアンカー
     * @param source        ロード済みの音源
     * @param rangeBlocks   可聴範囲 (ブロック)。0 = client config の既定を使う
     * @param volumePercent 音量 (%)。100 = 通常
     * @param directional   聴取モデル。true = 従来どおりの positional
     * @param track         報告に曲名を載せるための曲
     * @return 配線済みのインスタンス ({@code SoundManager.play} は呼び出し側が行う)
     */
    public static DiscSoundInstance wired(DiscAnchor anchor, IAudioSource source, int rangeBlocks,
            int volumePercent, boolean directional, CustomTrackData track) {
        final DiscSoundInstance instance =
                new DiscSoundInstance(anchor, source, rangeBlocks, volumePercent, null);
        instance.setDirectional(directional);
        return attach(source, instance, track);
    }

    /**
     * entity に追従する音源を作り、失敗の届け先を繋いで返す (バックパック系)。
     *
     * @param entity 追従先
     * @param source ロード済みの音源
     * @param track  報告に曲名を載せるための曲
     * @return 配線済みのインスタンス
     */
    public static DiscSoundInstance wired(Entity entity, IAudioSource source, CustomTrackData track) {
        return attach(source, new DiscSoundInstance(entity, source), track);
    }

    /**
     * 固定座標の音源を作り、失敗の届け先を繋いで返す (バックパックが地面に置かれている等)。
     *
     * @param pos    音源の位置
     * @param source ロード済みの音源
     * @param track  報告に曲名を載せるための曲
     * @return 配線済みのインスタンス
     */
    public static DiscSoundInstance wired(BlockPos pos, IAudioSource source, CustomTrackData track) {
        return attach(source, new DiscSoundInstance(pos, source), track);
    }

    private static DiscSoundInstance attach(IAudioSource source, DiscSoundInstance instance,
            CustomTrackData track) {
        CompatFaultWiring.attach(source, instance::setFailureSink,
                runnable -> Minecraft.getInstance().execute(runnable),
                failure -> PlaybackFailureReport.report(track, failure));
        return instance;
    }
}
