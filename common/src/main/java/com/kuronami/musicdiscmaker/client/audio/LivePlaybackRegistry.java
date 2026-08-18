package com.kuronami.musicdiscmaker.client.audio;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongSupplier;

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
 * <h2>失敗は覚えるが、掛け金にはしない</h2>
 * 1Hz の再送をそのまま繋ぎ直すとログもチャットも埋まるので、失敗した URL は覚える。ただし
 * 覚えるのは「次にいつ繋ぎ直してよいか」だけで、二度と繋がない印にはしない (理由は
 * {@link #loadFailed})。
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
        /** ロード中、または直近の失敗の待ち時間の中。今回は何もしない (待ちが明ければ自分で繋ぎ直す)。 */
        SKIP
    }

    private record Slot(String url, PlaybackVoice voice) {
    }

    /**
     * 直近の失敗の記憶。
     *
     * @param url       失敗した URL
     * @param attempts  この URL で連続して失敗した回数 (待ち時間の算出に使う)
     * @param retryAtMs この時刻を過ぎたら自分で繋ぎ直してよい
     */
    private record Failure(String url, int attempts, long retryAtMs) {
    }

    /** 最初の失敗の後、繋ぎ直すまでに置く間隔 (ms)。 */
    public static final long FIRST_RETRY_MS = 5_000L;
    /** 失敗が続いた時の待ち時間の上限 (ms)。これ以上は延ばさず、諦めもしない。 */
    public static final long MAX_RETRY_MS = 60_000L;

    private final Map<K, Slot> active = new ConcurrentHashMap<>();
    /** ロード中の URL (in-flight ガード)。同じ URL の再送でロードを重ねない。 */
    private final Map<K, String> pending = new ConcurrentHashMap<>();
    /** 直近の失敗。<b>掛け金ではなく待ち時間</b> ({@link #loadFailed} の javadoc)。 */
    private final Map<K, Failure> failures = new ConcurrentHashMap<>();
    /** 失敗の報告の重複抑止。同じ理由を繋ぎ直しのたびにチャットへ積まない。 */
    private final PlaybackFailureNotices notices = new PlaybackFailureNotices();
    /** 鍵ごとの「いま鳴らしたい URL」。ロード完了時に、その間に曲が変わっていないかを見る。 */
    private final Map<K, String> wanted = new ConcurrentHashMap<>();

    private final LongSupplier clockMs;

    /** 実時計で動かす (本番)。 */
    public LivePlaybackRegistry() {
        this(System::currentTimeMillis);
    }

    /**
     * @param clockMs 現在時刻 (ms)。テストは実時間を待たずに進めるために差し替える
     *                ({@code PlaybackSessions} と同じ seam の切り方)
     */
    public LivePlaybackRegistry(LongSupplier clockMs) {
        this.clockMs = clockMs;
    }

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
            forgetFailure(key);
        }
        if (url.equals(pending.get(key))) {
            return Decision.SKIP; // 同じ URL を既にロード中 (周期再送)。ロードを重ねない。
        }
        final Failure failure = failures.get(key);
        if (failure != null) {
            if (!failure.url().equals(url)) {
                forgetFailure(key); // 別の曲になった。前の曲の失敗は関係ない
            } else if (clockMs.getAsLong() < failure.retryAtMs()) {
                return Decision.SKIP; // 待ち時間の中。明けたらこの経路がそのまま繋ぎ直す
            }
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
     * ロードが失敗したことを記録する。
     *
     * <p><b>掛け金にはしない。</b> 以前は失敗した URL をそのまま覚え、<b>曲が変わるか成功するまで二度と繋ぎ直さなかった</b>。
     * 成功する経路が閉じているので後者は起こりえず、実際には「曲が変わるまで」だけが効く。
     * つまり DNS の一瞬の失敗や回線の瞬断ひとつで、その音源は<b>恒久的に無音</b>になっていた
     * ({@code DependencyManager} が展開の失敗を掛け金にしていたのと同じ型)。
     *
     * <p>覚えるのは「次にいつ繋ぎ直してよいか」だけにする。待ち時間は失敗が続くほど伸びる
     * ({@link #FIRST_RETRY_MS} から倍々で {@link #MAX_RETRY_MS} まで) が、<b>打ち切りはしない</b> —
     * 一時的な失敗から自力で戻れることがこの型の存在理由なので、そこは閉じない。
     *
     * <p>報告してよいかもここが答える。繋ぎ直すようにした分、同じ理由をそのままチャットへ流すと
     * 今度はうるさくなる。理由 (ラベル) が変わった時だけ通す ({@link PlaybackFailureNotices})。
     * 成功したら記憶ごと捨てるので、直った後に同じ失敗が起きればまた 1 回出る。
     *
     * @param key 音源をまとめる鍵
     * @param url 失敗した URL
     * @param why 失敗の理由 ({@code null} なら記録だけして報告しない)
     * @return 利用者へ報告してよいなら {@code true}
     */
    public boolean loadFailed(K key, String url, @Nullable PlaybackFailure why) {
        final Failure previous = failures.get(key);
        final int attempts = previous != null && previous.url().equals(url) ? previous.attempts() + 1 : 1;
        failures.put(key, new Failure(url, attempts, clockMs.getAsLong() + backoffMs(attempts)));
        return why != null && notices.shouldReport(key, why);
    }

    /**
     * 失敗が {@code attempts} 回続いた後に置く待ち時間。
     *
     * @param attempts 連続失敗回数 (1 起算)
     * @return 待ち時間 (ms)
     */
    static long backoffMs(int attempts) {
        final int steps = Math.min(Math.max(attempts, 1) - 1, 8);
        return Math.min(FIRST_RETRY_MS << steps, MAX_RETRY_MS);
    }

    /** 失敗の記憶を捨てる (成功・曲の差し替え)。報告の抑止も一緒に解く。 */
    private void forgetFailure(K key) {
        failures.remove(key);
        notices.forget(key);
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
        // 成功したら記憶ごと捨てる。報告の抑止も一緒に解かないと、直った後に同じ理由で
        // また落ちた時に何も出なくなる (掛け金を別の形で戻すことになる)。
        forgetFailure(key);
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
