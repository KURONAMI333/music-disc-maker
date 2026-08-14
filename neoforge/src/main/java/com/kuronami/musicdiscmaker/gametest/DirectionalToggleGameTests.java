package com.kuronami.musicdiscmaker.gametest;

import java.util.List;

import com.kuronami.musicdiscmaker.MusicDiscMaker;
import com.kuronami.musicdiscmaker.block.GoldenJukeboxBlockEntity;
import com.kuronami.musicdiscmaker.compat.aeronautics.SubLevelPlayDiscPayload;
import com.kuronami.musicdiscmaker.compat.create.ContraptionPlayDiscPayload;
import com.kuronami.musicdiscmaker.component.CustomTrackData;
import com.kuronami.musicdiscmaker.component.SilentSongs;
import com.kuronami.musicdiscmaker.network.PlayDiscPayload;
import com.kuronami.musicdiscmaker.platform.Services;
import com.kuronami.musicdiscmaker.platform.services.INetworkHelper;
import com.kuronami.musicdiscmaker.register.ModBlocks;
import com.kuronami.musicdiscmaker.register.ModDataComponents;
import com.kuronami.musicdiscmaker.register.ModItems;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.component.DataComponents;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.item.EitherHolder;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.JukeboxPlayable;
import net.minecraft.world.level.block.Blocks;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/**
 * 音の指向性 ON/OFF (範囲内フラット聴取 = BGM モード) が、設定した所から実際に鳴る client まで
 * 落ちずに届くことを headless で固定する。
 *
 * <p>ここが緩んだ時の症状は「GUI では OFF になっているのに立体音響のまま鳴る」で、build も
 * 既存 GameTest も素通しする。落ちうる箇所は 2 種類ある。
 * <ul>
 *   <li><b>配送元</b> — BE の値を再生 payload に載せ忘れる。強化版ジューク自身の再生
 *       ({@link PlayDiscPayload}) と、移動構造物へ引き継ぐ 2 経路
 *       ({@link ContraptionPlayDiscPayload} = Create contraption /
 *       {@link SubLevelPlayDiscPayload} = Sable sub-level) がある。</li>
 *   <li><b>wire</b> — codec の要素を足し忘れる、あるいは読み書きの順序がずれる。
 *       {@link ContraptionPlayDiscPayload} は 7 要素で
 *       {@code StreamCodec.composite} の上限 6 を超えるため手書き codec になっており、
 *       encode と decode の順序が 1 箇所で対応しているだけ = ずれても型は通る。</li>
 * </ul>
 *
 * <p>4 系統目のバックパック再生 ({@code BackpackPlayDiscPayload}) は意図的に運んでいない。
 * あの経路の音源は Sophisticated / Traveler's のストレージであって強化版ジュークではなく、
 * 指向性を設定する面 (GUI) も設定を保持する BE も存在しないため、載せる値そのものが無い。
 *
 * <p>NBT 側では既定が {@code true} (= 従来の positional) であることを固定する。ここが false へ
 * 倒れると、この機能より前のセーブにある全ての金ジュークの聴こえ方が黙って変わる。
 */
@GameTestHolder(MusicDiscMaker.MODID)
public class DirectionalToggleGameTests {

    private static final String TEMPLATE = "empty8x3x8";
    private static final long DURATION_MS = 120_000L;

    private static CustomTrackData track() {
        return new CustomTrackData("https://example.invalid/directional-disc", "Directional Disc", "tester",
                DURATION_MS, "", false);
    }

    private static ItemStack customDisc() {
        final ItemStack disc = new ItemStack(ModItems.CUSTOM_MUSIC_DISC.get());
        disc.set(ModDataComponents.CUSTOM_TRACK.get(), track());
        disc.set(DataComponents.JUKEBOX_PLAYABLE,
                new JukeboxPlayable(new EitherHolder<>(SilentSongs.pick(DURATION_MS)), false));
        return disc;
    }

    private static void withCapturedNetwork(java.util.function.Consumer<CapturingNetwork> body) {
        final CapturingNetwork net = new CapturingNetwork();
        final INetworkHelper previous = Services.swapNetwork(net);
        try {
            body.accept(net);
        } finally {
            Services.swapNetwork(previous);
        }
    }

    /** {@code pos} 宛ての再生 payload だけを取り出す (同 chunk の他テストの jukebox を除く)。 */
    private static List<PlayDiscPayload> playsFor(CapturingNetwork net, BlockPos pos) {
        return net.of(PlayDiscPayload.class).stream()
                .map(s -> (PlayDiscPayload) s.payload())
                .filter(p -> p.jukeboxPos().equals(pos))
                .toList();
    }

    /**
     * 強化版ジュークをフラットに設定してからディスクを入れると、再生 payload の指向性も
     * フラットで飛ぶこと。ここを落とすと、client は BE を読み直す次の tick まで立体音響で鳴る
     * (= 鳴り始めだけ設定が効かない) し、BE を読めない client では永久に効かない。
     */
    @PrefixGameTestTemplate(false)
    @GameTest(template = TEMPLATE, timeoutTicks = 200)
    public static void playPayloadCarriesFlatMode(GameTestHelper helper) {
        final BlockPos rel = new BlockPos(1, 1, 1);
        helper.setBlock(rel, ModBlocks.GOLDEN_JUKEBOX.get());
        final BlockPos pos = helper.absolutePos(rel);
        final GoldenJukeboxBlockEntity jukebox = helper.getBlockEntity(rel);
        if (jukebox == null) {
            helper.fail("GoldenJukeboxBlockEntity が生成されていない", rel);
            return;
        }
        jukebox.setDirectional(false);

        withCapturedNetwork(net -> {
            jukebox.setItem(GoldenJukeboxBlockEntity.SLOT_DISC, customDisc());
            final List<PlayDiscPayload> plays = playsFor(net, pos);
            helper.assertTrue(plays.size() == 1,
                    "ディスク投入で再生 payload が 1 通飛んでいない (" + plays.size() + " 通)");
            helper.assertFalse(plays.get(0).directional(),
                    "フラットに設定した金ジュークの再生 payload が positional で飛んでいる "
                            + "(鳴り始めが立体音響になる)");
        });
        helper.succeed();
    }

    /** 何も触っていない金ジュークは従来どおり positional で飛ぶこと (既定を変えていないこと)。 */
    @PrefixGameTestTemplate(false)
    @GameTest(template = TEMPLATE, timeoutTicks = 200)
    public static void playPayloadDefaultsToDirectional(GameTestHelper helper) {
        final BlockPos rel = new BlockPos(3, 1, 1);
        helper.setBlock(rel, ModBlocks.GOLDEN_JUKEBOX.get());
        final BlockPos pos = helper.absolutePos(rel);
        final GoldenJukeboxBlockEntity jukebox = helper.getBlockEntity(rel);
        if (jukebox == null) {
            helper.fail("GoldenJukeboxBlockEntity が生成されていない", rel);
            return;
        }
        helper.assertTrue(jukebox.isDirectional(), "新規設置の金ジュークの既定が positional ではない");

        withCapturedNetwork(net -> {
            jukebox.setItem(GoldenJukeboxBlockEntity.SLOT_DISC, customDisc());
            final List<PlayDiscPayload> plays = playsFor(net, pos);
            helper.assertTrue(plays.size() == 1,
                    "ディスク投入で再生 payload が 1 通飛んでいない (" + plays.size() + " 通)");
            helper.assertTrue(plays.get(0).directional(),
                    "既定の金ジュークの再生 payload がフラットで飛んでいる (既存ワールドの聴こえ方が変わる)");
        });
        helper.succeed();
    }

    /** 強化版ジューク本体の再生 payload が指向性を wire 越しに保つこと。 */
    @PrefixGameTestTemplate(false)
    @GameTest(template = TEMPLATE)
    public static void playPayloadCodecKeepsDirectional(GameTestHelper helper) {
        for (final boolean directional : new boolean[] { true, false }) {
            final PlayDiscPayload sent =
                    new PlayDiscPayload(new BlockPos(11, 22, 33), track(), 4_000L, 96, 150, directional);
            final ByteBuf buf = Unpooled.buffer();
            PlayDiscPayload.STREAM_CODEC.encode(buf, sent);
            final PlayDiscPayload received = PlayDiscPayload.STREAM_CODEC.decode(buf);
            helper.assertTrue(received.directional() == directional,
                    "PlayDiscPayload の指向性が wire で化けている (送信 " + directional + ")");
            // 順序ずれは「型は通るが別のフィールドが入れ替わる」形で出るので、隣接値も見る。
            helper.assertTrue(received.rangeBlocks() == 96 && received.volumePercent() == 150
                    && received.startOffsetMs() == 4_000L && received.jukeboxPos().equals(sent.jukeboxPos()),
                    "PlayDiscPayload の codec で他のフィールドが入れ替わっている");
        }
        helper.succeed();
    }

    /**
     * Create contraption 経路。7 要素で {@code composite} の上限を超えるため手書き codec になっており、
     * encode/decode の順序保証が人手の対応だけになっている面。
     */
    @PrefixGameTestTemplate(false)
    @GameTest(template = TEMPLATE)
    public static void contraptionPayloadCodecKeepsDirectional(GameTestHelper helper) {
        for (final boolean directional : new boolean[] { true, false }) {
            final ContraptionPlayDiscPayload sent = new ContraptionPlayDiscPayload(
                    77, new BlockPos(1, 2, 3), track(), 8_000L, 128, 175, directional);
            final ByteBuf buf = Unpooled.buffer();
            ContraptionPlayDiscPayload.STREAM_CODEC.encode(buf, sent);
            final ContraptionPlayDiscPayload received = ContraptionPlayDiscPayload.STREAM_CODEC.decode(buf);
            helper.assertTrue(received.directional() == directional,
                    "ContraptionPlayDiscPayload の指向性が wire で化けている (送信 " + directional
                            + ")。フラットの金ジュークが contraption に載った瞬間 positional へ戻る");
            helper.assertTrue(received.contraptionEntityId() == 77 && received.rangeBlocks() == 128
                    && received.volumePercent() == 175 && received.startOffsetMs() == 8_000L
                    && received.localPos().equals(sent.localPos()),
                    "手書き codec の encode/decode の順序がずれている (他のフィールドが入れ替わっている)");
            helper.assertTrue(buf.readableBytes() == 0,
                    "手書き codec の decode が読み残している (要素の数が encode と合っていない)");
        }
        helper.succeed();
    }

    /** Sable (Create Aeronautics) の sub-level 経路。 */
    @PrefixGameTestTemplate(false)
    @GameTest(template = TEMPLATE)
    public static void subLevelPayloadCodecKeepsDirectional(GameTestHelper helper) {
        for (final boolean directional : new boolean[] { true, false }) {
            final SubLevelPlayDiscPayload sent = new SubLevelPlayDiscPayload(
                    new BlockPos(5, 6, 7), track(), 12_000L, 200, 80, directional);
            final ByteBuf buf = Unpooled.buffer();
            SubLevelPlayDiscPayload.STREAM_CODEC.encode(buf, sent);
            final SubLevelPlayDiscPayload received = SubLevelPlayDiscPayload.STREAM_CODEC.decode(buf);
            helper.assertTrue(received.directional() == directional,
                    "SubLevelPlayDiscPayload の指向性が wire で化けている (送信 " + directional + ")");
            helper.assertTrue(received.rangeBlocks() == 200 && received.volumePercent() == 80
                    && received.startOffsetMs() == 12_000L && received.plotPos().equals(sent.plotPos()),
                    "SubLevelPlayDiscPayload の codec で他のフィールドが入れ替わっている");
        }
        helper.succeed();
    }

    /**
     * NBT 往復。保存・復元されること、そして<b>タグが無い旧セーブは positional (既定) になること</b>。
     * 後者が本命 — ここが false へ倒れると、この機能より前に置かれた全ての金ジュークが黙って
     * フラットになる (additive でなくなる)。
     */
    @PrefixGameTestTemplate(false)
    @GameTest(template = TEMPLATE, timeoutTicks = 200)
    public static void nbtRoundTripsDirectionalAndDefaultsToTrue(GameTestHelper helper) {
        final BlockPos rel = new BlockPos(5, 1, 1);
        helper.setBlock(rel, ModBlocks.GOLDEN_JUKEBOX.get());
        final GoldenJukeboxBlockEntity jukebox = helper.getBlockEntity(rel);
        if (jukebox == null) {
            helper.fail("GoldenJukeboxBlockEntity が生成されていない", rel);
            return;
        }
        final HolderLookup.Provider registries = helper.getLevel().registryAccess();

        // false を保存 → 復元
        jukebox.setDirectional(false);
        final CompoundTag flat = jukebox.saveWithoutMetadata(registries);
        helper.assertTrue(flat.contains("directional") && !flat.getBoolean("directional"),
                "フラット設定が NBT に保存されていない");
        jukebox.setDirectional(true);
        jukebox.loadWithComponents(flat, registries);
        helper.assertFalse(jukebox.isDirectional(), "NBT から復元したフラット設定が positional に戻っている");

        // true を保存 → 復元
        jukebox.setDirectional(true);
        final CompoundTag positional = jukebox.saveWithoutMetadata(registries);
        helper.assertTrue(positional.getBoolean("directional"), "positional 設定が NBT に保存されていない");
        jukebox.setDirectional(false);
        jukebox.loadWithComponents(positional, registries);
        helper.assertTrue(jukebox.isDirectional(), "NBT から復元した positional 設定がフラットになっている");

        // タグ欠落 (この機能より前のセーブ) → 既定 = positional
        jukebox.setDirectional(false);
        final CompoundTag legacy = jukebox.saveWithoutMetadata(registries);
        legacy.remove("directional");
        jukebox.loadWithComponents(legacy, registries);
        helper.assertTrue(jukebox.isDirectional(),
                "directional タグの無い旧セーブがフラットとして読まれている "
                        + "(既存ワールドの金ジュークが黙ってフラットになる)");

        helper.setBlock(rel, Blocks.AIR);
        helper.succeed();
    }
}
