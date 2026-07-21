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
import com.kuronami.musicdiscmaker.register.ModItems;

import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.Level;

/**
 * ボタンレス GUI のコア: 「URL あり + 空ディスクあり + 出力空」が揃ったら自動で解決して
 * custom disc を出力する (server 側)。URL コミット時とスロット変更時の両方から呼ばれる。
 */
public final class DiscFabrication {

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
        if (level == null || level.isClientSide || be.isResolving()) {
            return;
        }
        if (!be.getItem(MusicDiscMakerBlockEntity.SLOT_INPUT).is(ModItems.BLANK_DISC.get())) {
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
            } catch (final UrlBlockedException blocked) {
                // SSRF ガードが拒否 → GUI に「blocked」理由を出す (gui.music_disc_maker.failed.blocked)。
                MusicDiscMaker.LOGGER.warn("URL を拒否 ({}): {}", blocked.reason(), url);
                failure = FailureReason.BLOCKED_URL;
                resolved = null;
            } catch (final ResolveException re) {
                // impl が分類済みの失敗理由 (非公開/地域/年齢/接続/対応外)。
                failure = re.reason();
                resolved = null;
            } catch (final Throwable t) {
                MusicDiscMaker.LOGGER.warn("URL 解決中に例外 ({}): {}", url, t.toString());
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
                if (result == null) {
                    be.clearResolvedTrack();
                    // GUI に理由別メッセージを出す
                    be.setResolveFailed(reason == null ? FailureReason.UNKNOWN : reason);
                    return;
                }
                final String storedUrl = (result.uri() != null && !result.uri().isBlank()) ? result.uri() : url;
                be.setResolvedTrack(new CustomTrackData(
                        storedUrl, result.title(), result.author(), result.durationMs(),
                        result.thumbnailUrl()), url);
                if (be.createDisc()) {
                    be.resetToNeutral(); // 1枚作ったら URL を消してニュートラルへ
                }
            });
        });
    }
}
