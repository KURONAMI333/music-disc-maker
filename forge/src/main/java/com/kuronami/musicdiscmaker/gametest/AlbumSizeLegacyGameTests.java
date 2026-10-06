package com.kuronami.musicdiscmaker.gametest;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.util.ArrayList;
import java.util.List;

import com.kuronami.musicdiscmaker.MusicDiscMaker;
import com.kuronami.musicdiscmaker.component.AlbumContents;
import com.kuronami.musicdiscmaker.component.BoomboxContents;
import com.kuronami.musicdiscmaker.component.CustomTrackData;
import com.kuronami.musicdiscmaker.item.AlbumItem;
import com.kuronami.musicdiscmaker.item.CustomMusicDiscItem;
import com.kuronami.musicdiscmaker.register.ModItems;

import io.netty.buffer.Unpooled;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtIo;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;

/** Forge 1.20.1 の NBT 保存と menu が使う FriendlyByteBuf ItemStack 経路を測る。 */
@GameTestHolder(MusicDiscMaker.MODID)
@PrefixGameTestTemplate(false)
public final class AlbumSizeLegacyGameTests {
    private static final int[][] ALBUM_FIXTURES = {
            {1, 1}, {9, 1}, {25, 1}, {256, 1}
    };

    private AlbumSizeLegacyGameTests() {
    }

    @GameTest(template = "empty8x3x8", timeoutTicks = 400)
    public static void albumAndBoomboxNbtMeasurements(GameTestHelper helper) {
        for (final boolean longMetadata : List.of(false, true)) {
            measureStack(helper, longMetadata ? "long_ja_single_disc" : "normal_ja_single_disc",
                    "custom_disc", singleDisc(0, longMetadata), 1, 1, false);
            for (final int[] dimensions : ALBUM_FIXTURES) {
                final int discCount = dimensions[0];
                final int tracksPerDisc = dimensions[1];
                final String fixture = (longMetadata ? "long_ja" : "normal_ja")
                        + "_album_" + discCount + "_discs_" + tracksPerDisc + "_tracks_each";
                final ItemStack album = album(discCount, tracksPerDisc, longMetadata);
                final boolean expectsLimit = longMetadata && exceedsMeasuredLegacyBudget(discCount, tracksPerDisc);
                measureStack(helper, fixture, "album", album, discCount, discCount * tracksPerDisc, expectsLimit);
                if (discCount == 1 || discCount == 9) {
                    final ItemStack boombox = new ItemStack(ModItems.BOOMBOX.get());
                    BoomboxContents.store(boombox, new BoomboxContents(album, 4_242L));
                    measureStack(helper, fixture, "boombox_outer", boombox, discCount,
                            discCount * tracksPerDisc, expectsLimit);
                }
            }
        }
        helper.succeed();
    }

    @GameTest(template = "empty8x3x8", timeoutTicks = 400)
    public static void fullLengthMetadataHasNoLegacyPacketGuard(GameTestHelper helper) {
        final ItemStack album = album(256, 1, true);
        final FriendlyByteBuf wire = new FriendlyByteBuf(Unpooled.buffer());
        int wireBytes = -1;
        boolean wireEqual = false;
        RuntimeException failure = null;
        try {
            wire.writeItem(album);
            wireBytes = wire.readableBytes();
            wireEqual = ItemStack.matches(album, wire.readItem());
        } catch (RuntimeException expected) {
            failure = expected;
        } finally {
            wire.release();
        }
        emit("full_ja_album_256_discs_1_track_each", "album", 256, 256,
                nbtBytes(album), wireBytes, true, wireEqual, failure);
        helper.assertTrue(isLegacyNbtDecodeLimit(failure),
                "full long Album did not fail at the measured 2 MiB NBT decoder guard");
        final ItemStack boombox = new ItemStack(ModItems.BOOMBOX.get());
        BoomboxContents.store(boombox, new BoomboxContents(album, 4_242L));
        final FriendlyByteBuf outerWire = new FriendlyByteBuf(Unpooled.buffer());
        int outerBytes = -1;
        boolean outerEqual = false;
        RuntimeException outerFailure = null;
        try {
            outerWire.writeItem(boombox);
            outerBytes = outerWire.readableBytes();
            outerEqual = ItemStack.matches(boombox, outerWire.readItem());
        } catch (RuntimeException expected) {
            outerFailure = expected;
        } finally {
            outerWire.release();
        }
        emit("full_ja_album_256_discs_1_track_each", "boombox_outer", 256, 256,
                nbtBytes(boombox), outerBytes, true, outerEqual, outerFailure);
        helper.assertTrue(isLegacyNbtDecodeLimit(outerFailure),
                "Boombox outer stack did not hit the measured 2 MiB NBT decoder guard");
        helper.succeed();
    }

    @GameTest(template = "empty8x3x8", timeoutTicks = 400)
    public static void albumOverflowNbtRoundTripKeepsOrderAndDecodeBoundary(GameTestHelper helper) {
        final List<ItemStack> source = new ArrayList<>();
        for (int index = 0; index < 24; index++) {
            final ItemStack disc = singleDisc(index, false);
            disc.setHoverName(Component.literal("overflow disc " + index));
            source.add(disc);
        }
        final AlbumContents original = new AlbumContents(source);
        source.get(0).setCount(0);
        original.discs().get(1).setCount(0);
        helper.assertTrue(original.size() == 24
                        && original.discAt(0).getCount() == 1
                        && original.discAt(1).getCount() == 1,
                "AlbumContentsが入力またはaccessorのmutable ItemStackを共有した");

        final ItemStack album = new ItemStack(ModItems.ALBUM.get());
        AlbumItem.setContents(album, original);
        final ItemStack persistedStack = ItemStack.of(album.save(new CompoundTag()).copy());
        final AlbumContents restored = AlbumItem.contents(persistedStack);
        helper.assertTrue(restored.size() == 24, "18枠を超えるAlbum保存値を切り捨てた");
        for (int index : List.of(18, 23)) {
            final ItemStack disc = restored.discAt(index);
            helper.assertTrue(CustomMusicDiscItem.getTrack(disc).url().endsWith("/" + index)
                            && disc.getHoverName().getString().equals("overflow disc " + index),
                    "保存overflowの位置またはItemStack値が変わった: " + index);
        }

        final List<ItemStack> atLimitStacks = new ArrayList<>();
        for (int index = 0; index < AlbumContents.STORAGE_DECODE_LIMIT; index++) {
            atLimitStacks.add(singleDisc(index, false));
        }
        final ListTag atLimit = new AlbumContents(atLimitStacks).toNbt();
        helper.assertTrue(AlbumContents.fromNbt(atLimit.copy()).size() == AlbumContents.STORAGE_DECODE_LIMIT,
                "保存防御上限256 stackを復元できない");
        final ListTag overLimit = atLimit.copy();
        overLimit.add(new ItemStack(ModItems.CUSTOM_MUSIC_DISC.get()).save(new CompoundTag()));
        boolean rejected = false;
        try {
            AlbumContents.fromNbt(overLimit);
        } catch (IllegalArgumentException expected) {
            rejected = true;
        }
        helper.assertTrue(rejected, "保存防御上限を超える257 stackを拒否しない");
        helper.succeed();
    }

    /** 共通の曲payloadとForgeのNBT ItemStackは別の文字符号化経路なので両方検査する。 */
    @GameTest(template = "empty8x3x8", timeoutTicks = 100)
    public static void supplementaryMetadataKeepsBothLegacyWireRoutes(GameTestHelper helper) {
        final String emoji = "\ud83c\udfb5";
        final String text = "x".repeat(255) + emoji;
        final String url = "x".repeat(2047) + emoji;
        final List<CustomTrackData> cases = List.of(
                new CustomTrackData("https://example.invalid/track", text, "artist", 1000L, "", false),
                new CustomTrackData("https://example.invalid/track", "title", text, 1000L, "", false),
                new CustomTrackData(url, "title", "artist", 1000L, "", false),
                new CustomTrackData("https://example.invalid/track", "title", "artist", 1000L, url, false),
                CustomTrackData.local("a".repeat(64), text, text, 1000L));
        for (int index = 0; index < cases.size(); index++) {
            final CustomTrackData track = cases.get(index);
            final FriendlyByteBuf payload = new FriendlyByteBuf(Unpooled.buffer());
            try {
                track.write(payload);
                helper.assertTrue(track.equals(CustomTrackData.read(payload)) && payload.readableBytes() == 0,
                        "supplementary metadata changed in UTF8 playback payload " + index);
            } finally {
                payload.release();
            }
            final ItemStack disc = new ItemStack(ModItems.CUSTOM_MUSIC_DISC.get());
            CustomMusicDiscItem.setTrack(disc, track);
            helper.assertTrue(track.title().equals(disc.getHoverName().getString()),
                    "legacy disc title changed " + index);
            final ItemStack album = new ItemStack(ModItems.ALBUM.get());
            AlbumItem.setContents(album, new AlbumContents(List.of(disc)));
            final ItemStack boombox = new ItemStack(ModItems.BOOMBOX.get());
            BoomboxContents.store(boombox, new BoomboxContents(album, 4242L));
            helper.assertTrue(com.kuronami.musicdiscmaker.component.AlbumStorageBudget.fits(disc)
                            && com.kuronami.musicdiscmaker.component.AlbumStorageBudget.fits(album)
                            && com.kuronami.musicdiscmaker.component.AlbumStorageBudget.fits(boombox),
                    "small supplementary metadata exceeded legacy budget " + index);
            measureStack(helper, "supplementary_" + index, "custom_disc", disc, 1, 1, false);
            measureStack(helper, "supplementary_" + index, "album", album, 1, 1, false);
            measureStack(helper, "supplementary_" + index, "boombox", boombox, 1, 1, false);
        }
        helper.succeed();
    }

    private static void measureStack(GameTestHelper helper, String fixture, String outer, ItemStack original,
            int discCount, int trackCount, boolean expectsLimit) {
        final CompoundTag persistedTag = original.save(new CompoundTag());
        final ItemStack persisted = ItemStack.of(persistedTag.copy());
        final boolean persistentEqual = ItemStack.matches(original, persisted);
        helper.assertTrue(persistentEqual, fixture + " NBT ItemStack round trip changed contents");

        final FriendlyByteBuf wire = new FriendlyByteBuf(Unpooled.buffer());
        int wireBytes = -1;
        boolean wireEqual = false;
        RuntimeException failure = null;
        try {
            wire.writeItem(original);
            wireBytes = wire.readableBytes();
            wireEqual = ItemStack.matches(original, wire.readItem());
        } catch (RuntimeException expected) {
            failure = expected;
        } finally {
            wire.release();
        }
        emit(fixture, outer, discCount, trackCount, nbtBytes(original), wireBytes,
                persistentEqual, wireEqual, failure);
        if (expectsLimit) {
            helper.assertTrue(isLegacyNbtDecodeLimit(failure),
                    fixture + " did not fail at the measured 2 MiB NBT decoder guard");
        } else {
            helper.assertTrue(failure == null, fixture + " ItemStack sync codec threw " + failureSummary(failure));
            helper.assertTrue(wireEqual, fixture + " menu ItemStack sync codec changed contents");
        }
    }

    private static int nbtBytes(ItemStack stack) {
        try (ByteArrayOutputStream bytes = new ByteArrayOutputStream();
                DataOutputStream output = new DataOutputStream(bytes)) {
            NbtIo.write(stack.save(new CompoundTag()), output);
            return bytes.size();
        } catch (Exception failure) {
            throw new AssertionError("could not measure legacy persistent NBT bytes", failure);
        }
    }

    private static ItemStack album(int discCount, int tracksPerDisc, boolean longMetadata) {
        final List<ItemStack> discs = new ArrayList<>();
        for (int i = 0; i < discCount; i++) {
            discs.add(singleDisc(i * 10_000, longMetadata));
        }
        final ItemStack album = new ItemStack(ModItems.ALBUM.get());
        AlbumItem.setContents(album, new AlbumContents(discs));
        return album;
    }

    private static ItemStack singleDisc(int start, boolean longMetadata) {
        final ItemStack disc = new ItemStack(ModItems.CUSTOM_MUSIC_DISC.get());
        CustomMusicDiscItem.setTrack(disc, track(start, longMetadata));
        disc.setHoverName(Component.literal("日本語盤 " + start));
        return disc;
    }

    private static CustomTrackData track(int index, boolean longMetadata) {
        if (!longMetadata) {
            return new CustomTrackData("https://example.invalid/曲/" + index,
                    "通常曲 " + index, "作曲者", 180_000L, "https://example.invalid/cover/" + index, false);
        }
        final String suffix = Integer.toString(index);
        return new CustomTrackData(
                "https://example.invalid/" + "道".repeat(2_000) + suffix,
                "長い日本語曲名".repeat(30) + suffix,
                "長い日本語アーティスト".repeat(20) + suffix,
                180_000L,
                "https://example.invalid/cover/" + "絵".repeat(2_000) + suffix,
                false);
    }

    private static String json(String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "\\r");
    }

    private static boolean exceedsMeasuredLegacyBudget(int discCount, int tracksPerDisc) {
        return discCount == 256;
    }

    private static boolean isLegacyNbtDecodeLimit(RuntimeException failure) {
        return failure != null && failure.getClass().equals(RuntimeException.class)
                && failure.getMessage() != null
                && failure.getMessage().contains("Tried to read NBT tag that was too big")
                && failure.getMessage().contains("max allowed: 2097152");
    }

    private static String failureSummary(RuntimeException failure) {
        return failure == null ? "none" : failure.getClass().getName() + ":" + failure.getMessage();
    }

    private static void emit(String fixture, String outer, int discs, int tracks, int nbtBytes, int wireBytes,
            boolean persistentEqual, boolean wireEqual, RuntimeException failure) {
        MusicDiscMaker.LOGGER.info(
                "ALBUM_SIZE_MEASUREMENT {\"loader\":\"forge_1_20_1\",\"fixture\":\"{}\",\"outer\":\"{}\",\"disc_count\":{},\"track_count\":{},\"persistent_nbt_bytes\":{},\"menu_itemstack_wire_bytes\":{},\"persistent_equal\":{},\"wire_equal\":{},\"wire_rejected\":{},\"wire_exception_type\":\"{}\",\"wire_exception_message\":\"{}\"}",
                fixture, outer, discs, tracks, nbtBytes, wireBytes, persistentEqual, wireEqual,
                failure != null, failure == null ? "" : failure.getClass().getName(),
                failure == null || failure.getMessage() == null ? "" : json(failure.getMessage()));
    }
}
