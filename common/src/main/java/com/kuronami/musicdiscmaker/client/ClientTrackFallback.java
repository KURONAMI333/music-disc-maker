package com.kuronami.musicdiscmaker.client;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import com.kuronami.musicdiscmaker.audio.LoaderHolder;
import com.kuronami.musicdiscmaker.block.ClientResolvedTrack;
import com.kuronami.musicdiscmaker.block.MusicDiscMakerBlockEntity;
import com.kuronami.musicdiscmaker.lavaplayer.api.TrackInfo;
import com.kuronami.musicdiscmaker.network.ClientResolvedTrackPayload;
import com.kuronami.musicdiscmaker.network.UrlGuard;
import com.kuronami.musicdiscmaker.platform.Services;

import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;

/**
 * server が URL を解決できなかった時、この client の回線で同じ URL を引き直し、取れた曲名・尺を
 * server へ渡す。
 *
 * <p>なぜ client か: 専用サーバーの回線が YouTube に bot 判定されても、プレイヤーの自宅回線では
 * 同じ動画のメタ情報が取れることがある。盤の尺は server が焼くので、ここで尺を渡さないと
 * 長さ 0 (= ラジオ扱い) の盤しか作れない。
 *
 * <p>解決は数秒ブロックするので main thread から外す。取れた値をそのまま焼かせず、server が
 * 元の URL と照合してから焼く ({@code ClientResolvedTrack})。
 */
public final class ClientTrackFallback {

    /** 同じ (製作機, URL) を何度も引き直さないための記録の上限。 */
    private static final int MAX_ATTEMPTS = 64;

    /** 試した (製作機, URL)。失敗した時に毎 tick 引き直すと回線と待ち時間を無駄にする。 */
    private static final Map<String, Boolean> ATTEMPTED = new LinkedHashMap<>();

    private static final ExecutorService POOL = Executors.newSingleThreadExecutor(runnable -> {
        final Thread thread = new Thread(runnable, "music_disc_maker-client-resolve");
        thread.setDaemon(true);
        return thread;
    });

    private ClientTrackFallback() {
    }

    /**
     * 画面の tick から呼ぶ。server が「回線の都合」で失敗していて、まだ試していない時だけ引き直す。
     *
     * @param maker 画面が開いている製作機 (client 側の同期値)
     */
    public static void maybeResolve(MusicDiscMakerBlockEntity maker) {
        if (maker == null || !maker.isResolveFailed() || maker.isResolving()) {
            return;
        }
        final String url = maker.getCurrentUrl();
        // server と同じ条件で絞る。client が取っても焼いてよい理由にならない失敗では動かない。
        if (!ClientResolvedTrack.shouldAskClient(maker.getFailureReason(), url)) {
            return;
        }
        final BlockPos pos = maker.getBlockPos();
        if (!claim(pos.asLong() + "|" + url)) {
            return;
        }
        POOL.submit(() -> resolveAndSend(pos, url));
    }

    private static void resolveAndSend(BlockPos pos, String url) {
        final TrackInfo info;
        try {
            // 解決経路と同じ SSRF 遮断を通す。server と同じ URL だが、client から出る通信も
            // 内部ネットワークへ向かわせない。
            UrlGuard.enforce(url);
            info = LoaderHolder.get().resolve(url);
        } catch (final Throwable failure) {
            // 取れなかっただけ。画面は server が付けた失敗理由を出し続ける。
            return;
        }
        if (info == null) {
            return;
        }
        // 解決が別の住所を返した場合もそのまま送る。server が元の URL と同じ動画かを照合し、
        // 違えば焼かない (代替ソースの値を元の URL に貼らせない)。
        final String clientUrl = info.uri() != null && !info.uri().isBlank() ? info.uri() : url;
        final ClientResolvedTrackPayload payload = new ClientResolvedTrackPayload(pos, clientUrl,
                info.title(), info.author(), info.durationMs());
        final Minecraft minecraft = Minecraft.getInstance();
        minecraft.execute(() -> {
            // 解決中に切断・終了した場合に、接続の無い相手へ送らない。
            if (minecraft.getConnection() != null) {
                Services.NETWORK.sendToServer(payload);
            }
        });
    }

    /** この (製作機, URL) を初めて試す時だけ true。古い記録から捨てて上限を保つ。 */
    private static boolean claim(String key) {
        synchronized (ATTEMPTED) {
            if (ATTEMPTED.containsKey(key)) {
                return false;
            }
            if (ATTEMPTED.size() >= MAX_ATTEMPTS) {
                final var oldest = ATTEMPTED.keySet().iterator();
                if (oldest.hasNext()) {
                    oldest.next();
                    oldest.remove();
                }
            }
            ATTEMPTED.put(key, Boolean.TRUE);
            return true;
        }
    }
}
