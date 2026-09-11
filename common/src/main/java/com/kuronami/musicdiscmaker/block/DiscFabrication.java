package com.kuronami.musicdiscmaker.block;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import com.kuronami.musicdiscmaker.MusicDiscMaker;
import com.kuronami.musicdiscmaker.audio.LoaderHolder;
import com.kuronami.musicdiscmaker.component.CustomTrackData;
import com.kuronami.musicdiscmaker.lavaplayer.api.FailureReason;
import com.kuronami.musicdiscmaker.lavaplayer.api.ResolveException;
import com.kuronami.musicdiscmaker.lavaplayer.api.TrackInfo;
import com.kuronami.musicdiscmaker.network.UrlBlockedException;
import com.kuronami.musicdiscmaker.network.UrlGuard;

import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.Level;

/**
 * ボタンレス GUI のコア: 「URL あり + 空ディスクあり + 出力空」が揃ったら自動で解決して
 * custom disc を出力する (server 側)。URL コミット時とスロット変更時の両方から呼ばれる。
 */
public final class DiscFabrication {

    /** これを超える長さ (ms) は無限長ストリーム扱い (12h)。lavaplayer の長さ不明は Long.MAX_VALUE。 */
    private static final long RADIO_DURATION_THRESHOLD_MS = 43_200_000L;

    /** URL 解決は最大 30s ブロックするので main thread から外す。 */
    private static final ExecutorService POOL = Executors.newFixedThreadPool(2, runnable -> {
        final Thread thread = new Thread(runnable, "music_disc_maker-fabricate");
        thread.setDaemon(true);
        return thread;
    });

    private DiscFabrication() {
    }

    /** 条件が揃っていれば (必要なら非同期で) custom disc を生成する。server 側のみ。 */
    public static void process(MusicDiscMakerBlockEntity be) {
        final Level level = be.getLevel();
        //? if >=1.21.2 {
        if (level == null || level.isClientSide() || be.isResolving()) {
        //?} else {
        /*if (level == null || level.isClientSide || be.isResolving()) {
        *///?}
            return;
        }
        if (!MusicDiscMakerBlockEntity.isBlankDisc(be.getItem(MusicDiscMakerBlockEntity.SLOT_INPUT))) {
            return;
        }
        if (!be.getItem(MusicDiscMakerBlockEntity.SLOT_OUTPUT).isEmpty()) {
            return;
        }
        final String url = be.getCurrentUrl();
        if (url == null || url.isBlank()) {
            return;
        }

        // 既に同じ URL を解決済みなら再解決しない (再入時の二重解決を防ぐキャッシュ経路)。
        if (be.hasResolvedTrack() && url.equals(be.getResolvedForUrl())) {
            if (be.createDisc()) {
                be.resetToNeutral(); // 1枚作ったら URL を消してニュートラルへ
            }
            return;
        }

        final MinecraftServer server = level.getServer();
        if (server == null) {
            return;
        }

        be.setResolving(true);
        POOL.submit(() -> {
            TrackInfo resolved;
            FailureReason failure = null;
            try {
                UrlGuard.enforce(url); // SSRF 遮断: 内部 IP / 非 http(s) scheme を解決前に弾く
                resolved = LoaderHolder.get().resolve(url);
                // 解決が返した URL は入力と別物でありうる (YouTube が弾かれた時の代替ソース /
                // Spotify 経路 / リダイレクト後の実体)。ディスクに焼くのは
                // こちらなので、入力と同じ検査をここでも通す。
                //
                if (resolved != null && resolved.uri() != null && !resolved.uri().isBlank()
                        && !resolved.uri().equals(url)) {
                    UrlGuard.enforce(resolved.uri());
                }
            } catch (final UrlBlockedException blocked) {
                // SSRF ガードが拒否 → GUI に「blocked」理由を出す (gui.music_disc_maker.failed.blocked)。
                //? if >=1.21.2 {
                MusicDiscMaker.LOGGER.warn("Rejected URL ({}): {}", blocked.reason(), url);
                //?} elif >=1.21 {
                /*MusicDiscMaker.LOGGER.warn("Rejected URL ({}): {}", blocked.reason(), url);
                *///?} else {
                /*MusicDiscMaker.LOGGER.warn("Rejected URL ({}): {}", blocked.reason(), url);
                *///?}
                failure = FailureReason.BLOCKED_URL;
                resolved = null;
            } catch (final ResolveException re) {
                // impl が分類済みの失敗理由 (非公開/地域/年齢/接続/対応外)。
                // GUI の失敗表示は client 側にしか出ないので、ここで server ログにも 1 行残す
                // (dedicated server の管理者は client のチャット/GUI を見られない)。
                MusicDiscMaker.LOGGER.warn("URL resolution failed [{}] url={} detail={}",
                        re.reason(), url, re.detail());
                failure = re.reason();
                resolved = null;
            } catch (final Throwable t) {
                //? if >=1.21.2 {
                MusicDiscMaker.LOGGER.warn("Exception while resolving URL ({}): {}", url, t.toString());
                //?} elif >=1.21 {
                /*MusicDiscMaker.LOGGER.warn("Exception while resolving URL ({})", url, t);
                *///?} else {
                /*MusicDiscMaker.LOGGER.warn("Exception while resolving URL ({}): {}", url, t.toString());
                *///?}
                failure = FailureReason.UNKNOWN;
                resolved = null;
            }
            final TrackInfo result = resolved;
            final FailureReason reason = failure;
            server.execute(() -> {
                // 解決中 (最大30s) にブロック破壊/ワールドアンロードされた BE には触らない
                if (be.isRemoved() || be.getLevel() == null) {
                    return;
                }
                be.setResolving(false);
                if (!url.equals(be.getCurrentUrl())) {
                    // 解決中に編集されたURLへ古い曲を書き込まない。
                    process(be);
                    return;
                }
                if (result == null) {
                    be.clearResolvedTrack();
                    // GUI に理由別メッセージを出す
                    be.setResolveFailed(reason == null ? FailureReason.UNKNOWN : reason);
                    return;
                }
                final CustomTrackData track = toTrackData(result, url);
                if (track == null) {
                    be.clearResolvedTrack();
                    be.setResolveFailed(reason == null ? FailureReason.UNKNOWN : reason);
                    return;
                }
                be.setResolvedTrack(track, url);
                if (be.createDisc()) {
                    be.resetToNeutral(); // 1枚作ったら URL を消してニュートラルへ
                }
            });
        });
    }

    /**
     * 解決結果 1 件をディスクに焼く形へ直す。
     *
     * <p>ラジオ判定: ライブフラグ / 長さ不明 (lavaplayer は Long.MAX_VALUE) / 12h 超の
     * いずれかで無限長ストリーム扱い。radio 時は durationMs を 0 sentinel で保存する
     * (ActiveDiscRegistry の prune を避け、UI は「LIVE」を出す)。
     *
     * @param info      解決結果
     * @param fallback  住所が返らなかった時に使う入力 URL
     * @return 焼く形。住所が得られなければ {@code null}
     */
    private static CustomTrackData toTrackData(TrackInfo info, String fallback) {
        if (info == null) {
            return null;
        }
        final String storedUrl = (info.uri() != null && !info.uri().isBlank()) ? info.uri() : fallback;
        if (storedUrl == null || storedUrl.isBlank()) {
            return null;
        }
        final boolean radio = info.stream()
                || info.durationMs() <= 0L
                || info.durationMs() > RADIO_DURATION_THRESHOLD_MS;
        final long storedDuration = radio ? 0L : info.durationMs();
        return new CustomTrackData(storedUrl, info.title(), info.author(), storedDuration,
                info.thumbnailUrl(), radio);
    }
}
