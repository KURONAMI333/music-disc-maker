package com.kuronami.musicdiscmaker.client.audio;

import java.util.List;

import com.kuronami.musicdiscmaker.network.SpeakerEntry;
import com.kuronami.musicdiscmaker.network.SpeakerSetPayload;

import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;
//? if >=1.21.2 {
import net.minecraft.gametest.framework.GameTestHelper;
//?} else {
/*import com.kuronami.musicdiscmaker.MusicDiscMaker;

import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

@GameTestHolder(MusicDiscMaker.MODID)
*///?}
public final class GoldenEmitterAnchorGameTests {

    private static final String TEMPLATE = "empty8x3x8";

    private GoldenEmitterAnchorGameTests() {
    }

    //? if <1.21.2 {
    /*@PrefixGameTestTemplate(false)
    @GameTest(template = TEMPLATE)
    *///?}
    public static void flatVanillaRangeFadesAndRecovers(GameTestHelper helper) {
        final FlatPlaybackGate gate = new FlatPlaybackGate();
        helper.assertTrue(gate.update(false, 10, 16, false) == 1.0F, "範囲内の初期音量が不正");
        helper.assertTrue(gate.update(false, 16.5, 16, true) == 1.0F, "境界の微小移動で音が揺れる");
        float gain = gate.update(false, 18, 16, true);
        helper.assertTrue(gain > 0 && gain < 1, "範囲外でフェードせず即時切替");
        for (int i = 0; i < 15; i++) gain = gate.update(false, 18, 16, true);
        helper.assertTrue(gain < 0.0001F, "範囲外でフラット音が鳴り続ける");
        helper.assertTrue(gate.update(false, 16.5, 16, true) < 0.0001F, "再入前に音を戻す");
        gain = gate.update(false, 15, 16, true);
        helper.assertTrue(gain > 0 && gain < 1, "範囲へ戻ってもフェードインしない");
        for (int i = 0; i < 15; i++) gain = gate.update(false, 15, 16, true);
        helper.assertTrue(gain == 1, "範囲内で音量が復帰しない");
        helper.assertTrue(new FlatPlaybackGate().update(false, 40, 16, false) == 0,
                "範囲外で開始したspeakerから一瞬音が漏れる");
        helper.assertTrue(new FlatPlaybackGate().update(true, 40, 16, false) == 1,
                "定位モードの距離減衰をフラットゲートで二重適用");
        helper.succeed();
    }

    /** Wrapper越しでも、Speakerなし固定Goldenの同期済みclient BE設定を優先する。 */
    //? if <1.21.2 {
    /*@PrefixGameTestTemplate(false)
    @GameTest(template = TEMPLATE)
    *///?}
    public static void fixedGoldenWithoutSpeakersUsesLiveSettings(GameTestHelper helper) {
        final BlockPos sourcePos = new BlockPos(8, 64, -3);
        final SpeakerSetPayload startedAtThirtyPercent =
                new SpeakerSetPayload(sourcePos, 24, 30, true, List.of());
        final GoldenEmitterAnchor.AudioConfig liveHundredPercent =
                new GoldenEmitterAnchor.AudioConfig(64, 100, false);
        final int[] reads = {0};
        final GoldenEmitterAnchor anchor = new GoldenEmitterAnchor(new FixedConfigAnchor(sourcePos),
                startedAtThirtyPercent, pos -> {
                    helper.assertTrue(sourcePos.equals(pos), "固定Golden以外の座標から設定を読んでいる: " + pos);
                    reads[0]++;
                    return liveHundredPercent;
                });

        helper.assertTrue(anchor.rangeBlocks() == 64, "開始時payloadの範囲に固定されている");
        helper.assertTrue(anchor.volumePercent() == 100, "開始時30%のままで、同期済み100%を読んでいない");
        helper.assertFalse(anchor.directional(), "同期済みのdirectional OFFを読んでいない");
        helper.assertTrue(reads[0] == 3, "実getterがclient BE readerを通っていない: " + reads[0]);
        helper.succeed();
    }

    /** Speaker有りでは遠隔でも整合するserver snapshotを正本とし、client BEへ戻らない。 */
    //? if <1.21.2 {
    /*@PrefixGameTestTemplate(false)
    @GameTest(template = TEMPLATE)
    *///?}
    public static void speakerNetworkKeepsServerSnapshotAuthoritative(GameTestHelper helper) {
        final SpeakerSetPayload speakerSnapshot = new SpeakerSetPayload(BlockPos.ZERO, 96, 80, true,
                List.of(new SpeakerEntry(new BlockPos(4, 0, 0), 70, false, 0)));
        final int[] reads = {0};
        final GoldenEmitterAnchor anchor = new GoldenEmitterAnchor(new FixedConfigAnchor(BlockPos.ZERO),
                speakerSnapshot, ignored -> {
                    reads[0]++;
                    return new GoldenEmitterAnchor.AudioConfig(16, 20, false);
                });

        helper.assertTrue(anchor.rangeBlocks() == 96, "Speaker snapshotの範囲を失っている");
        helper.assertTrue(anchor.volumePercent() == 80, "Speaker snapshotの音量を失っている");
        helper.assertTrue(anchor.directional(), "Speaker snapshotのdirectional ONを失っている");
        helper.assertTrue(reads[0] == 0, "Speaker有りでclient BEを再読している: " + reads[0]);
        helper.succeed();
    }

    /** Server GameTestでclient-only StaticAnchorを解決せず、同じ能力契約だけを供給する。 */
    private record FixedConfigAnchor(BlockPos configPos) implements DiscAnchor, LiveConfigAnchor {
        @Override
        public boolean isValid() {
            return true;
        }

        @Override
        public Vec3 worldPos(float partialTicks) {
            return Vec3.atCenterOf(configPos);
        }
    }
}
