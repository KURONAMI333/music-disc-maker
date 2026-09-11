package com.kuronami.musicdiscmaker.gametest;

import java.util.List;

import com.kuronami.musicdiscmaker.color.DiscDye;
import com.kuronami.musicdiscmaker.component.AlbumContents;
import com.kuronami.musicdiscmaker.component.CustomTrackData;
import com.kuronami.musicdiscmaker.component.DiscDyeData;
import com.kuronami.musicdiscmaker.component.PlaylistTracks;
import com.kuronami.musicdiscmaker.register.ModDataComponents;
import com.kuronami.musicdiscmaker.register.ModItems;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import net.minecraft.core.component.DataComponents;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.RegistryOps;
import net.minecraft.world.item.ItemStack;
//? if <1.21.2 {
/*import com.kuronami.musicdiscmaker.MusicDiscMaker;
import net.minecraft.gametest.framework.GameTest;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

@GameTestHolder(MusicDiscMaker.MODID)
*///?}
public final class AlbumContentsGameTests {
    private static final String TEMPLATE = "empty8x3x8";

    private AlbumContentsGameTests() {
    }

    //? if <1.21.2 {
    /*@PrefixGameTestTemplate(false)
    @GameTest(template = TEMPLATE)
    *///?}
    public static void codecRoundTripKeepsDiscMetadata(GameTestHelper helper) {
        final CustomTrackData a = track("a", "曲A");
        final CustomTrackData b = track("b", "曲B");
        final ItemStack playlist = disc(a);
        playlist.set(DataComponents.CUSTOM_NAME, Component.literal("染色済みプレイリスト"));
        playlist.set(ModDataComponents.DISC_DYE.get(), new DiscDyeData(DiscDye.BLUE, DiscDye.ORANGE));
        playlist.set(ModDataComponents.PLAYLIST_TRACKS.get(), new PlaylistTracks(List.of(a, b)));
        final AlbumContents original = new AlbumContents(List.of(playlist, disc(b)));

        final RegistryOps<Tag> ops = RegistryOps.create(NbtOps.INSTANCE, helper.getLevel().registryAccess());
        final Tag encoded = AlbumContents.CODEC.encodeStart(ops, original).result().orElse(null);
        helper.assertTrue(encoded != null, "AlbumContents CODEC が実 ItemStack を保存できない");
        final AlbumContents persisted = encoded == null
                ? null
                : AlbumContents.CODEC.parse(ops, encoded).result().orElse(null);
        helper.assertTrue(original.equals(persisted), "永続 codec 往復で ItemStack の値が変わった");

        final RegistryFriendlyByteBuf wire = new RegistryFriendlyByteBuf(
                Unpooled.buffer(), helper.getLevel().registryAccess());
        try {
            AlbumContents.STREAM_CODEC.encode(wire, original);
            final AlbumContents synchronizedValue = AlbumContents.STREAM_CODEC.decode(wire);
            helper.assertTrue(original.equals(synchronizedValue), "通信 codec 往復で ItemStack の値が変わった");

            final ItemStack restored = synchronizedValue.discAt(0);
            helper.assertTrue("染色済みプレイリスト".equals(restored.getHoverName().getString()),
                    "custom name が往復で失われた");
            helper.assertTrue(new DiscDyeData(DiscDye.BLUE, DiscDye.ORANGE)
                            .equals(restored.get(ModDataComponents.DISC_DYE.get())),
                    "染色 component が往復で失われた");
            helper.assertTrue(new PlaylistTracks(List.of(a, b))
                            .equals(restored.get(ModDataComponents.PLAYLIST_TRACKS.get())),
                    "playlist component が往復で失われた");
        } finally {
            wire.release();
        }
        helper.succeed();
    }

    //? if <1.21.2 {
    /*@PrefixGameTestTemplate(false)
    @GameTest(template = TEMPLATE)
    *///?}
    public static void stackCopiesDoNotShareMutableOwnership(GameTestHelper helper) {
        final ItemStack supplied = disc(track("owned", "Owned"));
        final AlbumContents contents = new AlbumContents(List.of(supplied));

        supplied.setCount(0);
        helper.assertTrue(contents.discAt(0).getCount() == 1,
                "constructor が呼び元の mutable ItemStack を所有したままにしている");

        final ItemStack returned = contents.discAt(0);
        returned.setCount(0);
        helper.assertTrue(contents.discAt(0).getCount() == 1,
                "discAt が内部の mutable ItemStack を返している");

        final List<ItemStack> listed = contents.discs();
        listed.get(0).setCount(0);
        helper.assertTrue(contents.discAt(0).getCount() == 1,
                "discs accessor が内部の mutable ItemStack を返している");
        helper.succeed();
    }

    //? if <1.21.2 {
    /*@PrefixGameTestTemplate(false)
    @GameTest(template = TEMPLATE)
    *///?}
    public static void nestedAlbumIsRejectedBeforeRecursiveCodecExpansion(GameTestHelper helper) {
        final ItemStack nested = disc(track("nested", "Nested"));
        nested.set(ModDataComponents.ALBUM_CONTENTS.get(),
                new AlbumContents(List.of(disc(track("inner", "Inner")))));

        final boolean insertable = AlbumContents.canInsert(
                nested,
                stack -> stack.is(ModItems.CUSTOM_MUSIC_DISC.get()),
                stack -> stack.has(ModDataComponents.ALBUM_CONTENTS.get())
                        || stack.is(ModItems.BOOMBOX.get()));
        helper.assertTrue(!insertable, "通常の投入判定が nested album を受け入れた");
        final boolean boomboxInsertable = AlbumContents.canInsert(
                new ItemStack(ModItems.BOOMBOX.get()),
                stack -> true,
                stack -> stack.has(ModDataComponents.ALBUM_CONTENTS.get())
                        || stack.is(ModItems.BOOMBOX.get()));
        helper.assertTrue(!boomboxInsertable, "通常の投入判定がブームボックスを受け入れた");

        final AlbumContents malformed = new AlbumContents(List.of(nested));
        final RegistryOps<Tag> ops = RegistryOps.create(NbtOps.INSTANCE, helper.getLevel().registryAccess());
        helper.assertTrue(AlbumContents.CODEC.encodeStart(ops, malformed).error().isPresent(),
                "永続 codec が nested album を再帰的に符号化した");
        // ItemStack単体なら合法な内側のAlbumを、外側の列へ手で包んでdecode入口も検査する。
        final ListTag crafted = new ListTag();
        crafted.add(ItemStack.CODEC.encodeStart(ops, nested).result().orElseThrow());
        helper.assertTrue(AlbumContents.CODEC.parse(ops, crafted).error().isPresent(),
                "永続 codec が細工された nested album を復元した");

        final RegistryFriendlyByteBuf wire = new RegistryFriendlyByteBuf(
                Unpooled.buffer(), helper.getLevel().registryAccess());
        try {
            boolean rejected = false;
            try {
                AlbumContents.STREAM_CODEC.encode(wire, malformed);
            } catch (RuntimeException expected) {
                rejected = true;
            }
            helper.assertTrue(rejected, "通信 codec が nested album を再帰的に符号化した");
        } finally {
            wire.release();
        }
        final RegistryFriendlyByteBuf raw = new RegistryFriendlyByteBuf(
                Unpooled.buffer(), helper.getLevel().registryAccess());
        final RegistryFriendlyByteBuf craftedWire = new RegistryFriendlyByteBuf(
                Unpooled.buffer(), helper.getLevel().registryAccess());
        try {
            raw.writeVarInt(1);
            ItemStack.OPTIONAL_STREAM_CODEC.encode(raw, nested);
            craftedWire.writeVarInt(raw.readableBytes());
            craftedWire.writeBytes(raw, raw.readerIndex(), raw.readableBytes());
            helper.assertTrue(decodeFails(craftedWire), "通信 codec が細工された nested album を復元した");
        } finally {
            raw.release();
            craftedWire.release();
        }
        helper.succeed();
    }

    //? if <1.21.2 {
    /*@PrefixGameTestTemplate(false)
    @GameTest(template = TEMPLATE)
    *///?}
    public static void malformedWireLimitsFailBeforeItemExpansion(GameTestHelper helper) {
        final RegistryFriendlyByteBuf oversized = new RegistryFriendlyByteBuf(
                Unpooled.buffer(), helper.getLevel().registryAccess());
        try {
            oversized.writeVarInt(AlbumContents.WIRE_BYTE_LIMIT + 1);
            helper.assertTrue(decodeFails(oversized), "bytes 上限超過の album payload を受け入れた");
        } finally {
            oversized.release();
        }

        final ByteBuf raw = Unpooled.buffer();
        final RegistryFriendlyByteBuf excessiveCount = new RegistryFriendlyByteBuf(
                Unpooled.buffer(), helper.getLevel().registryAccess());
        try {
            ByteBufCodecs.VAR_INT.encode(raw, AlbumContents.WIRE_DECODE_LIMIT + 1);
            excessiveCount.writeVarInt(raw.readableBytes());
            excessiveCount.writeBytes(raw, raw.readerIndex(), raw.readableBytes());
            helper.assertTrue(decodeFails(excessiveCount), "件数上限超過の album payload を受け入れた");
        } finally {
            raw.release();
            excessiveCount.release();
        }
        helper.succeed();
    }

    private static boolean decodeFails(RegistryFriendlyByteBuf wire) {
        try {
            AlbumContents.STREAM_CODEC.decode(wire);
            return false;
        } catch (RuntimeException expected) {
            return true;
        }
    }

    private static CustomTrackData track(String id, String title) {
        return new CustomTrackData("https://example.invalid/" + id, title, "test", 120_000L, "", false);
    }

    private static ItemStack disc(CustomTrackData track) {
        final ItemStack disc = new ItemStack(ModItems.CUSTOM_MUSIC_DISC.get());
        disc.set(ModDataComponents.CUSTOM_TRACK.get(), track);
        return disc;
    }
}
