package com.kuronami.musicdiscmaker.gametest;

import java.util.List;

import com.kuronami.musicdiscmaker.MusicDiscMaker;
import com.kuronami.musicdiscmaker.component.CustomTrackData;
import com.kuronami.musicdiscmaker.component.SilentSongs;
import com.kuronami.musicdiscmaker.event.ActiveDiscRegistry;
import com.kuronami.musicdiscmaker.event.AlbumPlaybackMirror;
import com.kuronami.musicdiscmaker.network.PlayDiscPayload;
import com.kuronami.musicdiscmaker.network.StopDiscPayload;
import com.kuronami.musicdiscmaker.platform.Services;
import com.kuronami.musicdiscmaker.platform.services.INetworkHelper;
import com.kuronami.musicdiscmaker.register.ModDataComponents;
import com.kuronami.musicdiscmaker.register.ModItems;

import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.EitherHolder;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.JukeboxPlayable;
import net.minecraft.world.level.Level;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/**
 * [Let's Do] Furniture の Gramophone (蓄音機) 向け {@code FurnitureGramophoneMixin} が使う再送手段
 * (S1: repeat ループの再送) を、Furniture 本体無しで headless に固定する。
 *
 * <p>{@code FurnitureGramophoneMixin} は Furniture が compileOnly のソフト依存 (build/gametest
 * classpath には無い) なので、この GameTest は実 Gramophone ブロックを置かない。mixin が実際に
 * 行う操作 —「repeat 復帰の tick で {@link AlbumPlaybackMirror#mirror} を stop→play の順で
 * 2回呼ぶ」— を {@link AlbumPlaybackMirror} の公開 API だけを使って直接再現する。これにより
 * (a) Furniture 不在でもビルド・テストが壊れないこと (Furniture 型を一切参照しない) と
 * (b) その再現手順が実際に url 不変のループでも resend を起こすことの両方を、実行時に固定する。
 */
@GameTestHolder(MusicDiscMaker.MODID)
public class GramophoneLoopResendGameTests {

    private static final String TEMPLATE = "empty8x3x8";
    private static final long DURATION_MS = 30_000L;

    private static ItemStack customDisc() {
        final ItemStack disc = new ItemStack(ModItems.CUSTOM_MUSIC_DISC.get());
        disc.set(ModDataComponents.CUSTOM_TRACK.get(),
                new CustomTrackData("https://example.invalid/gramophone-loop", "Gramophone Loop", "tester",
                        DURATION_MS, "", false));
        disc.set(DataComponents.JUKEBOX_PLAYABLE,
                new JukeboxPlayable(new EitherHolder<>(SilentSongs.pick(DURATION_MS)), false));
        return disc;
    }

    /**
     * <b>これが今回の回帰テスト (S1)。</b> まず対照として、同じ url を鳴らし続ける通常の毎 tick
     * ポーリングでは resend しないこと (= {@code FurnitureGramophoneMixin} が repeat 検知なしに
     * 毎 tick 呼んでも音声が暴れない前提) を確認する。続けて、{@code FurnitureGramophoneMixin} が
     * repeat ループの張り直しを検知した tick で行う「同じ url のまま mirror(null) → mirror(disc) を
     * 連続で呼ぶ」手順が、実際に Stop→Play の resend を起こし、client 側の再生が頭から鳴り直せる
     * 形になっていることを固定する。
     */
    @PrefixGameTestTemplate(false)
    @GameTest(template = TEMPLATE, timeoutTicks = 200, batch = "gramophoneLoopResend")
    public static void repeatRestartMarkerForcesStopThenPlayResend(GameTestHelper helper) {
        ActiveDiscRegistry.clear();
        final ServerLevel level = helper.getLevel();
        final BlockPos pos = helper.absolutePos(new BlockPos(1, 1, 1));
        final ResourceKey<Level> dim = level.dimension();
        final ItemStack disc = customDisc();

        withCapturedNetwork(net -> {
            AlbumPlaybackMirror.mirror(level, pos, disc);
            final ActiveDiscRegistry.Playing started = ActiveDiscRegistry.current(dim, pos);
            helper.assertTrue(started != null, "前提: 初回 mirror で再生が registry に登録されていない");

            net.clear();
            // 対照: 通常の毎 tick ポーリング (repeat 復帰ではない): url 不変 → 何も送らない。
            AlbumPlaybackMirror.mirror(level, pos, disc);
            AlbumPlaybackMirror.mirror(level, pos, disc);
            helper.assertTrue(playsFor(net, pos).isEmpty() && stopsFor(net, pos).isEmpty(),
                    "url 不変の毎 tick ポーリングで payload が送られている (通常再生が暴れる)");

            net.clear();
            // 本番: FurnitureGramophoneMixin が repeat 復帰 tick で行う手順そのもの。
            AlbumPlaybackMirror.mirror(level, pos, null);
            AlbumPlaybackMirror.mirror(level, pos, disc);

            helper.assertTrue(stopsFor(net, pos).size() == 1,
                    "repeat 復帰の再送手順で StopDiscPayload が飛んでいない (" + stopsFor(net, pos).size() + " 通)");
            final List<PlayDiscPayload> plays = playsFor(net, pos);
            helper.assertTrue(plays.size() == 1,
                    "repeat 復帰の再送手順で PlayDiscPayload が飛んでいない (" + plays.size() + " 通)");
            helper.assertTrue(plays.get(0).startOffsetMs() == 0L,
                    "repeat 復帰の再送が offset 0 (頭出し) で送られていない: " + plays.get(0).startOffsetMs());

            helper.assertTrue(ActiveDiscRegistry.current(dim, pos) != null,
                    "repeat 復帰の再送後に registry から再生エントリが消えている");
        });
        helper.succeed();
    }

    /** {@link CapturingNetwork} を挿して {@code body} を走らせ、必ず元の実装へ戻す。 */
    private static void withCapturedNetwork(java.util.function.Consumer<CapturingNetwork> body) {
        final CapturingNetwork net = new CapturingNetwork();
        final INetworkHelper previous = Services.swapNetwork(net);
        try {
            body.accept(net);
        } finally {
            Services.swapNetwork(previous);
        }
    }

    private static List<PlayDiscPayload> playsFor(CapturingNetwork net, BlockPos pos) {
        return net.of(PlayDiscPayload.class).stream()
                .map(s -> (PlayDiscPayload) s.payload())
                .filter(p -> p.jukeboxPos().equals(pos))
                .toList();
    }

    private static List<StopDiscPayload> stopsFor(CapturingNetwork net, BlockPos pos) {
        return net.of(StopDiscPayload.class).stream()
                .map(s -> (StopDiscPayload) s.payload())
                .filter(p -> p.jukeboxPos().equals(pos))
                .toList();
    }
}
