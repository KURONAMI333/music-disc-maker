package com.kuronami.musicdiscmaker.gametest;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.util.ArrayList;
import java.util.List;

import com.kuronami.musicdiscmaker.MusicDiscMaker;
import com.kuronami.musicdiscmaker.component.AlbumContents;
import com.kuronami.musicdiscmaker.component.BoomboxContents;
import com.kuronami.musicdiscmaker.component.CustomTrackData;
import com.kuronami.musicdiscmaker.register.ModDataComponents;
import com.kuronami.musicdiscmaker.register.ModItems;

import io.netty.buffer.Unpooled;
import io.netty.handler.codec.EncoderException;
import net.minecraft.core.component.DataComponents;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.RegistryOps;
import net.minecraft.world.item.ItemStack;
//? if <1.21.2 {
/*import net.minecraft.gametest.framework.GameTest;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

@GameTestHolder(MusicDiscMaker.MODID)
@PrefixGameTestTemplate(false)
*///?}

/**
 * 実 registry の ItemStack codec で Album と、それを咥えた Boombox を測る。
 *
 * <p>AlbumContents の 2 MiB は Album component 本体の framed stream にしか掛からない。ここでは
 * menu の slot 同期で運ばれる ItemStack と同じ OPTIONAL_STREAM_CODEC まで符号化し、外側の
 * Boombox component がその制限を別に持たないことも記録する。
 */
public final class AlbumSizeGameTests {
    private static final String TEMPLATE = "empty8x3x8";
    private static final int[][] ALBUM_FIXTURES = {
            {1, 1}, {9, 1}, {25, 1}, {256, 1}
    };

    private AlbumSizeGameTests() {
    }

    //? if <1.21.2 {
    /*@GameTest(template = TEMPLATE, timeoutTicks = 400)
    *///?}
    public static void albumAndBoomboxCodecMeasurements(GameTestHelper helper) {
        for (final boolean longMetadata : List.of(false, true)) {
            measureStack(helper, longMetadata ? "long_ja_single_disc" : "normal_ja_single_disc",
                    "custom_disc", singleDisc(0, longMetadata), 1, 1, false);
            for (final int[] dimensions : ALBUM_FIXTURES) {
                final int discCount = dimensions[0];
                final int tracksPerDisc = dimensions[1];
                final String fixture = (longMetadata ? "long_ja" : "normal_ja")
                        + "_album_" + discCount + "_discs_" + tracksPerDisc + "_tracks_each";
                final ItemStack album = album(discCount, tracksPerDisc, longMetadata);
                final boolean expectsLimit = longMetadata && exceedsMeasuredAlbumBudget(discCount, tracksPerDisc);
                measureStack(helper, fixture, "album", album, discCount, discCount * tracksPerDisc, expectsLimit);
                if (discCount == 1 || discCount == 9) {
                    final ItemStack boombox = new ItemStack(ModItems.BOOMBOX.get());
                    boombox.set(ModDataComponents.BOOMBOX_CONTENTS.get(), new BoomboxContents(album, 4_242L));
                    measureStack(helper, fixture, "boombox_outer", boombox, discCount,
                            discCount * tracksPerDisc, expectsLimit);
                }
            }
        }
        helper.succeed();
    }

    //? if <1.21.2 {
    /*@GameTest(template = TEMPLATE, timeoutTicks = 400)
    *///?}
    public static void fullLengthMetadataIsRejectedAtAlbumWireBoundary(GameTestHelper helper) {
        final ItemStack album = album(256, 1, true);
        final RegistryFriendlyByteBuf wire = new RegistryFriendlyByteBuf(Unpooled.buffer(), helper.getLevel().registryAccess());
        RuntimeException failure = null;
        try {
            ItemStack.OPTIONAL_STREAM_CODEC.encode(wire, album);
        } catch (RuntimeException expected) {
            failure = expected;
        } finally {
            wire.release();
        }
        emit("full_ja_album_256_discs_1_track_each", "album", 256, 256,
                nbtBytes(helper, album), -1, true, false, failure);
        helper.assertTrue(isNeoAlbumWireLimit(failure),
                "full long Album did not fail at the measured Album 2 MiB guard");
        final ItemStack boombox = new ItemStack(ModItems.BOOMBOX.get());
        boombox.set(ModDataComponents.BOOMBOX_CONTENTS.get(), new BoomboxContents(album, 4_242L));
        final RegistryFriendlyByteBuf outerWire = new RegistryFriendlyByteBuf(
                Unpooled.buffer(), helper.getLevel().registryAccess());
        RuntimeException outerFailure = null;
        try {
            ItemStack.OPTIONAL_STREAM_CODEC.encode(outerWire, boombox);
        } catch (RuntimeException expected) {
            outerFailure = expected;
        } finally {
            outerWire.release();
        }
        emit("full_ja_album_256_discs_1_track_each", "boombox_outer", 256, 256,
                nbtBytes(helper, boombox), -1, true, false, outerFailure);
        helper.assertTrue(isNeoAlbumWireLimit(outerFailure),
                "Boombox outer stack did not preserve the inner Album 2 MiB guard");
        helper.succeed();
    }

    private static void measureStack(GameTestHelper helper, String fixture, String outer, ItemStack original,
            int discCount, int trackCount, boolean expectsLimit) {
        final RegistryOps<Tag> ops = RegistryOps.create(NbtOps.INSTANCE, helper.getLevel().registryAccess());
        final Tag encoded = ItemStack.CODEC.encodeStart(ops, original).result().orElse(null);
        helper.assertTrue(encoded != null, fixture + " persistent ItemStack codec encode failed");
        final ItemStack persisted = encoded == null
                ? ItemStack.EMPTY
                : ItemStack.CODEC.parse(ops, encoded).result().orElse(ItemStack.EMPTY);
        final boolean persistentEqual = ItemStack.matches(original, persisted);
        helper.assertTrue(persistentEqual, fixture + " persistent ItemStack codec changed contents");

        final RegistryFriendlyByteBuf wire = new RegistryFriendlyByteBuf(Unpooled.buffer(), helper.getLevel().registryAccess());
        int wireBytes = -1;
        boolean wireEqual = false;
        RuntimeException failure = null;
        try {
            ItemStack.OPTIONAL_STREAM_CODEC.encode(wire, original);
            wireBytes = wire.readableBytes();
            final ItemStack synchronizedStack = ItemStack.OPTIONAL_STREAM_CODEC.decode(wire);
            wireEqual = ItemStack.matches(original, synchronizedStack);
        } catch (RuntimeException expected) {
            failure = expected;
        } finally {
            wire.release();
        }
        emit(fixture, outer, discCount, trackCount, nbtBytes(helper, original), wireBytes,
                persistentEqual, wireEqual, failure);
        if (expectsLimit) {
            helper.assertTrue(isNeoAlbumWireLimit(failure),
                    fixture + " did not fail at the measured Album 2 MiB guard");
        } else {
            helper.assertTrue(failure == null, fixture + " ItemStack sync codec threw " + failureSummary(failure));
            helper.assertTrue(wireEqual, fixture + " menu ItemStack sync codec changed contents");
        }
    }

    private static int nbtBytes(GameTestHelper helper, ItemStack stack) {
        final RegistryOps<Tag> ops = RegistryOps.create(NbtOps.INSTANCE, helper.getLevel().registryAccess());
        final Tag encoded = ItemStack.CODEC.encodeStart(ops, stack).result().orElseThrow();
        try (ByteArrayOutputStream bytes = new ByteArrayOutputStream();
                DataOutputStream output = new DataOutputStream(bytes)) {
            NbtIo.write((CompoundTag) encoded, output);
            return bytes.size();
        } catch (Exception failure) {
            throw new AssertionError("could not measure persistent NBT bytes", failure);
        }
    }

    private static ItemStack album(int discCount, int tracksPerDisc, boolean longMetadata) {
        final List<ItemStack> discs = new ArrayList<>();
        for (int i = 0; i < discCount; i++) {
            discs.add(singleDisc(i * 10_000, longMetadata));
        }
        final ItemStack album = new ItemStack(ModItems.ALBUM.get());
        album.set(ModDataComponents.ALBUM_CONTENTS.get(), new AlbumContents(discs));
        return album;
    }

    private static ItemStack singleDisc(int start, boolean longMetadata) {
        final ItemStack disc = new ItemStack(ModItems.CUSTOM_MUSIC_DISC.get());
        disc.set(ModDataComponents.CUSTOM_TRACK.get(), track(start, longMetadata));
        disc.set(DataComponents.CUSTOM_NAME, Component.literal("日本語盤 " + start));
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

    private static boolean exceedsMeasuredAlbumBudget(int discCount, int tracksPerDisc) {
        return discCount == 256;
    }

    private static boolean isNeoAlbumWireLimit(RuntimeException failure) {
        return failure instanceof EncoderException && failure.getMessage() != null
                && failure.getMessage().equals("music_disc_maker: album payload exceeded 2097152 bytes");
    }

    private static String failureSummary(RuntimeException failure) {
        return failure == null ? "none" : failure.getClass().getName() + ":" + failure.getMessage();
    }

    private static void emit(String fixture, String outer, int discs, int tracks, int nbtBytes, int wireBytes,
            boolean persistentEqual, boolean wireEqual, RuntimeException failure) {
        MusicDiscMaker.LOGGER.info(
                "ALBUM_SIZE_MEASUREMENT {\"loader\":\"neoforge\",\"fixture\":\"{}\",\"outer\":\"{}\",\"disc_count\":{},\"track_count\":{},\"persistent_nbt_bytes\":{},\"menu_itemstack_wire_bytes\":{},\"persistent_equal\":{},\"wire_equal\":{},\"wire_rejected\":{},\"wire_exception_type\":\"{}\",\"wire_exception_message\":\"{}\"}",
                fixture, outer, discs, tracks, nbtBytes, wireBytes, persistentEqual, wireEqual,
                failure != null, failure == null ? "" : failure.getClass().getName(),
                failure == null || failure.getMessage() == null ? "" : json(failure.getMessage()));
    }
}
