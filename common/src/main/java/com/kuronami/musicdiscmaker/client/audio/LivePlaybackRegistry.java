package com.kuronami.musicdiscmaker.client.audio;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.jetbrains.annotations.Nullable;

/**
 * server が<b>現在値を周期送信してくる</b>音源 (Create Aeronautics の sub-level 等) の
 * 再生スロット管理 (MC 非依存)。
 *
 * <h2>これが要る理由 — 「再生中だから何もしない」が設定変更を永久に捨てていた</h2>
 * Sable の server は sub-level を追っている player へ 1 秒ごとに現在の指向性・範囲・音量を
 * 載せた payload を送る。受け手は「同じ座標で既に再生中なら早期 return」していたので、
 * <b>2 回目以降の payload が一度も読まれなかった</b>。症状は「GUI で指向性・範囲・音量を
 * 変えても、その音源にだけ永久に反映されない」。
 *
 * <p>本体の強化版ジュークボックスは {@code DiscSoundInstance#tick} が client 側 BE を毎 tick
 * 再読して追従するが、その経路は {@link StaticAnchor} 限定で、移動構造物のアンカーは入らない。
 * だから移動構造物では「周期送信された現在値を押し込む」のがライブ更新の唯一の経路になる。
 *
 * <h2>世代 (トークン) を使わない理由</h2>
 * {@link PlaybackGenerations} 型の「後発が勝つ」世代をここに入れると、<b>再送間隔 (1 秒) より
 * ロードが遅い音源が一度も鳴らない</b> (常に後発が先発を無効化し、後発の完了前にさらに後発が来る)。
 * ここで要るのは「同じ URL の再送は追い越さず、値だけ取り込む」判定なので、鍵は世代ではなく URL。
 *
 * @param <K> 音源をまとめる鍵 (Sable なら plot 座標)
 */
public final class LivePlaybackRegistry<K> {

    /** {@link #request} の答え。 */
    public enum Decision {
        /** 新しくロードして鳴らす。 */
        LOAD,
        /** 既に同じ曲が鳴っている。値だけ取り込んだのでロードしない。 */
        LIVE_UPDATE,
        /** ロード中 / 直近に失敗した URL。何もしない。 */
        SKIP
    }

    private record Slot(String url, PlaybackVoice voice) {
    }

    private final Map<K, Slot> active = new ConcurrentHashMap<>();
    /** ロード中の URL (in-flight ガード)。同じ URL の再送でロードを重ねない。 */
    private final Map<K, String> pending = new ConcurrentHashMap<>();
    /** 直近に失敗し報告済みの URL。曲が変わる/成功するまで繋ぎ直さない (1Hz でログを埋めない)。 */
    private final Map<K, String> failed = new ConcurrentHashMap<>();
    /** 鍵ごとの「いま鳴らしたい URL」。ロード完了時に、その間に曲が変わっていないかを見る。 */
    private final Map<K, String> wanted = new ConcurrentHashMap<>();

    /**
     * 周期送信された再生要求を捌く。<b>同じ URL なら値だけ取り込んでロードしない</b>のが要点。
     *
     * @param key           音源をまとめる鍵
     * @param url           要求された曲の URL
     * @param rangeBlocks   可聴範囲 (ブロック)
     * @param volumePercent 音量 (%)
     * @param directional   聴取モデル
     * @return 呼び出し側が次に何をすべきか
     */
    public Decision request(K key, String url, int rangeBlocks, int volumePercent, boolean directional) {
        final Slot slot = active.get(key);
        if (slot != null && !slot.voice().isStopped()) {
            if (slot.url().equals(url)) {
                // 同じ曲の再送 = server が載せてきた現在値。鳴らし直さずにその場で反映する。
                // ここを早期 return にしていたのが、設定変更が永久に無視されていた原因。
                slot.voice().setDirectional(directional);
                slot.voice().setRangeBlocks(rangeBlocks);
                slot.voice().setVolumePercent(volumePercent);
                wanted.put(key, url);
                return Decision.LIVE_UPDATE;
            }
            // 曲が変わった。古い音源を止めてから新しいロードへ落ちる。
            slot.voice().stopAndRelease();
            active.remove(key, slot);
            failed.remove(key);
        }
        if (url.equals(pending.get(key))) {
            return Decision.SKIP; // 同じ URL を既にロード中 (周期再送)。ロードを重ねない。
        }
        if (url.equals(failed.get(key))) {
            return Decision.SKIP; // 同じ URL が直近に失敗済み。
        }
        pending.put(key, url);
        wanted.put(key, url);
        return Decision.LOAD;
    }

    /**
     * ロードが終わった (成否を問わず) ことを記録し、in-flight ガードを解く。
     * 残すと以後の再送が全部黙って無視されるので、成否に関わらず必ず呼ぶこと。
     *
     * @param key 音源をまとめる鍵
     * @param url ロードしていた URL
     */
    public void loadFinished(K key, String url) {
        pending.remove(key, url);
    }

    /**
     * ロードが失敗したことを記録する。同じ URL の再送は次から {@link Decision#SKIP} になる。
     *
     * @param key 音源をまとめる鍵
     * @param url 失敗した URL
     */
    public void loadFailed(K key, String url) {
        failed.put(key, url);
    }

    /**
     * 鳴り始めたインスタンスを登録する。
     *
     * <p>ロードしている間に曲が変わっていたら受け付けない ({@code false})。呼び出し側はその音源を
     * 捨てること。ここを見ないと、遅れて完了した古い曲が新しい曲を上書きして鳴る。
     *
     * @param key    音源をまとめる鍵
     * @param url    鳴らそうとしている曲の URL
     * @param voice  鳴り始めたインスタンスへの口
     * @return 受け付けたなら {@code true}
     */
    public boolean install(K key, String url, PlaybackVoice voice) {
        if (!url.equals(wanted.get(key))) {
            return false; // ロード中に曲が変わった (この音源はもう要らない)
        }
        failed.remove(key, url);
        final Slot previous = active.put(key, new Slot(url, voice));
        if (previous != null && !previous.voice().isStopped()) {
            // 取りこぼしの防波堤。ここに来る = 上の判定をすり抜けた同時完了なので、
            // 放置すると 2 音源が重なって古い方が管理不能になる。
            previous.voice().stopAndRelease();
        }
        return true;
    }

    /**
     * 現在鳴っている URL (テストと診断用)。
     *
     * @param key 音源をまとめる鍵
     * @return 鳴っている URL。無ければ {@code null}
     */
    @Nullable
    public String activeUrl(K key) {
        final Slot slot = active.get(key);
        return slot == null ? null : slot.url();
    }
}
