package com.kuronami.musicdiscmaker.client;

import java.lang.ref.WeakReference;
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
import net.minecraft.world.level.Level;

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

    /** 試した (製作機, URL) の記録。同じ失敗で毎 tick 引き直さないため。 */
    private static final ClientResolveAttempts ATTEMPTS = new ClientResolveAttempts();

    /** 記録が属する Level。次元・サーバーが変わると位置が別の製作機を指すので、その目印に使う。 */
    private static WeakReference<Level> attemptLevel = new WeakReference<>(null);

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
        if (maker == null) {
            return;
        }
        clearWhenLevelChanges(maker.getLevel());
        final long pos = maker.getBlockPos().asLong();
        if (!maker.isResolveFailed()) {
            // 失敗表示が消えた = 焼けた・URL が変わった・入力が外れた。記録を戻さないと、
            // 同じ URL がもう一度失敗した時に二度と引き直せない。
            ATTEMPTS.release(pos);
            return;
        }
        if (maker.isResolving()) {
            return;
        }
        final String url = maker.getCurrentUrl();
        // server と同じ条件で絞る。client が取っても焼いてよい理由にならない失敗では動かない。
        if (!ClientResolvedTrack.shouldAskClient(maker.getFailureReason(), url)) {
            ATTEMPTS.release(pos);
            return;
        }
        if (!ATTEMPTS.claim(pos, url, System.currentTimeMillis())) {
            return;
        }
        final BlockPos blockPos = maker.getBlockPos();
        POOL.submit(() -> resolveAndSend(blockPos, url));
    }

    private static void resolveAndSend(BlockPos pos, String url) {
        final TrackInfo info;
        try {
            // 解決経路と同じ SSRF 遮断を通す。server と同じ URL だが、client から出る通信も
            // 内部ネットワークへ向かわせない。
            UrlGuard.enforce(url);
            info = LoaderHolder.get().resolve(url);
        } catch (final Throwable failure) {
            // 取れなかっただけ。画面は server が付けた失敗理由を出し続ける。ここで記録を戻すと
            // 画面が開いている間ずっと引き直すので、記録は時間切れ (ClientResolveAttempts) に任せる。
            return;
        }
        if (info == null) {
            return;
        }
        // 解決が別の住所を返した場合もそのまま送る。server が元の URL と同じ動画かを照合し、
        // 違えば焼かない (代替ソースの値を元の URL に貼らせない)。
        final String clientUrl = info.uri() != null && !info.uri().isBlank() ? info.uri() : url;
        final ClientResolvedTrackPayload payload = ClientResolvedTrackPayload.create(pos, clientUrl,
                info.title(), info.author(), info.durationMs());
        if (payload == null) {
            // 送れない値 (URL が上限超え等)。writeUtf の例外で接続を切らせない。同じ URL を
            // 引き直しても同じ値になるので、記録は戻さない。
            return;
        }
        final Minecraft minecraft = Minecraft.getInstance();
        minecraft.execute(() -> {
            // 解決中に画面を閉じた・切断した場合は送らない。server はその製作機の画面を開いている
            // player しか受け取らないので、送っても捨てられる。記録は戻して、開き直した時に
            // もう一度引き直せるようにする。
            if (minecraft.getConnection() == null || !isMakerScreenOpen()) {
                ATTEMPTS.release(pos.asLong());
                return;
            }
            Services.NETWORK.sendToServer(payload);
        });
    }

    /** 接続が切れたら記録を捨てる (次の接続では同じ位置が別の製作機を指す)。 */
    public static void clearAll() {
        ATTEMPTS.clear();
        attemptLevel = new WeakReference<>(null);
    }

    /** 次元・サーバーが変わったら記録を全部捨てる。位置だけでは別の製作機と区別できない。 */
    private static void clearWhenLevelChanges(Level level) {
        if (level == null || level == attemptLevel.get()) {
            return;
        }
        attemptLevel = new WeakReference<>(level);
        ATTEMPTS.clear();
    }

    /** 製作機の画面が開いているか。閉じている間に届いた報告は server が捨てるので送らない。 */
    private static boolean isMakerScreenOpen() {
        //? if >=26.2 {
        return Minecraft.getInstance().gui.screen() instanceof MusicDiscMakerScreen;
        //?} else {
        /*return Minecraft.getInstance().screen instanceof MusicDiscMakerScreen;
        *///?}
    }
}
