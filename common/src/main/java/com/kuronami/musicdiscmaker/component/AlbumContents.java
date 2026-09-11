package com.kuronami.musicdiscmaker.component;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.Predicate;
import java.util.function.Supplier;

//? if >=1.21 {
import com.mojang.datafixers.util.Pair;
import com.mojang.serialization.Codec;
import com.mojang.serialization.DataResult;
import com.mojang.serialization.Decoder;
import com.mojang.serialization.DynamicOps;
import com.mojang.serialization.Encoder;

import io.netty.buffer.ByteBuf;
import io.netty.handler.codec.DecoderException;
import io.netty.handler.codec.EncoderException;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
//?} else {
/*import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
*///?}
import net.minecraft.world.item.ItemStack;

/**
 * アルバムに収めたディスクの実物を、順序を保って所有する値。
 *
 * <p>曲情報へ平坦化せず {@link ItemStack} 全体を保存するため、名前、染色、プレイリスト、将来追加される
 * component/NBT も取り出した時に残る。受け取った stack と返す stack は Minecraft の
 * {@link ItemStack#copy()} でコピーし、呼び元の stack 操作が保存済みの内容へ届かないようにする。
 *
 * <p>アルバムの製品容量は9枚（2026-09-09確定）。この型にある上限は、壊れた保存値や細工された packet による
 * 無制限の確保と再帰を防ぐ decode 境界だけであり、GUI の容量を表さない。
 */
public record AlbumContents(List<ItemStack> discs) {

    /**
     * 永続データから一度に復元する stack 数の防御上限。バニラの ItemContainerContents と同じ 256。
     * 製品容量ではなく、破損データが無制限の List を作らないための境界。
     */
    public static final int STORAGE_DECODE_LIMIT = 256;

    /**
     * 通信から一度に復元する stack 数の防御上限。版間で製品容量が変わっても通信仕様と結合しない。
     */
    public static final int WIRE_DECODE_LIMIT = 256;

    /**
     * アルバム component 1 個の通信 payload 上限。Minecraft の標準 NBT 読み込み枠と同じ 2 MiB を使い、
     * 中の ItemStack を展開する前に拒否する。実測後に製品容量を決める値ではない。
     */
    public static final int WIRE_BYTE_LIMIT = 2 * 1024 * 1024;

    /** アルバム内アルバムによる codec の再帰を、内側の ItemStack 展開前に拒否する深度。 */
    public static final int CODEC_DEPTH_LIMIT = 1;

    /** ディスクを持たないアルバム。 */
    public static final AlbumContents EMPTY = new AlbumContents(List.of());

    //? if >=1.21 {
    private static final ThreadLocal<Integer> PERSISTENT_DEPTH = ThreadLocal.withInitial(() -> 0);
    private static final ThreadLocal<Integer> STREAM_DEPTH = ThreadLocal.withInitial(() -> 0);

    private static final Codec<AlbumContents> RAW_CODEC = ItemStack.CODEC
            .sizeLimitedListOf(STORAGE_DECODE_LIMIT)
            .xmap(AlbumContents::new, AlbumContents::copyDiscs);

    /** 永続化用 codec。ItemStack の component がこの codec へ戻る再帰を深度 1 で止める。 */
    public static final Codec<AlbumContents> CODEC = Codec.of(
            new Encoder<AlbumContents>() {
                @Override
                public <T> DataResult<T> encode(AlbumContents input, DynamicOps<T> ops, T prefix) {
                    return withPersistentDepth(() -> RAW_CODEC.encode(input, ops, prefix));
                }
            },
            new Decoder<AlbumContents>() {
                @Override
                public <T> DataResult<Pair<AlbumContents, T>> decode(DynamicOps<T> ops, T input) {
                    return withPersistentDepth(() -> RAW_CODEC.decode(ops, input));
                }
            },
            "music_disc_maker album contents");

    private static final StreamCodec<RegistryFriendlyByteBuf, AlbumContents> RAW_STREAM_CODEC = StreamCodec.of(
            (buf, value) -> {
                ByteBufCodecs.VAR_INT.encode(buf, value.discs.size());
                for (final ItemStack disc : value.discs) {
                    ItemStack.OPTIONAL_STREAM_CODEC.encode(buf, disc);
                }
            },
            buf -> {
                final int count = ByteBufCodecs.VAR_INT.decode(buf);
                if (count < 0 || count > WIRE_DECODE_LIMIT) {
                    throw new DecoderException("music_disc_maker: bad album length " + count);
                }
                final List<ItemStack> read = new ArrayList<>(count);
                for (int i = 0; i < count; i++) {
                    read.add(ItemStack.OPTIONAL_STREAM_CODEC.decode(buf));
                }
                return new AlbumContents(read);
            });

    /**
     * 同期用 codec。要素数に加えて payload 全体を長さ prefix で囲み、2 MiB を超える値を
     * ItemStack の展開前に拒否する。
     */
    public static final StreamCodec<RegistryFriendlyByteBuf, AlbumContents> STREAM_CODEC = new StreamCodec<>() {
        @Override
        public AlbumContents decode(RegistryFriendlyByteBuf input) {
            return withStreamDepth(() -> decodeFramed(input));
        }

        @Override
        public void encode(RegistryFriendlyByteBuf output, AlbumContents value) {
            withStreamDepth(() -> {
                encodeFramed(output, value);
                return value;
            });
        }
    };
    //?} else {
    /*/^* アイテム NBT 内でこの値を置くキー。 ^/
    public static final String NBT_KEY = "album";

    /^* stack の NBT からアルバムの中身を読む。 ^/
    public static AlbumContents of(ItemStack stack) {
        final CompoundTag tag = stack.getTag();
        if (tag != null && tag.contains(NBT_KEY, Tag.TAG_LIST)) {
            return fromNbt(tag.getList(NBT_KEY, Tag.TAG_COMPOUND));
        }
        return EMPTY;
    }

    /^* stack の NBT へアルバムの中身を書く。空ならキーを消す。 ^/
    public static void store(ItemStack stack, AlbumContents value) {
        if (value == null || value.isEmpty()) {
            final CompoundTag tag = stack.getTag();
            if (tag != null) {
                tag.remove(NBT_KEY);
            }
            return;
        }
        stack.getOrCreateTag().put(NBT_KEY, value.toNbt());
    }

    /^* 各 ItemStack を完全な NBT として保存する。 ^/
    public ListTag toNbt() {
        final ListTag list = new ListTag();
        for (final ItemStack disc : discs) {
            list.add(disc.save(new CompoundTag()));
        }
        return list;
    }

    /^* NBT から復元する。上限超過は切り捨てず、壊れた値として拒否する。 ^/
    public static AlbumContents fromNbt(ListTag list) {
        if (list == null || list.isEmpty()) {
            return EMPTY;
        }
        if (list.size() > STORAGE_DECODE_LIMIT) {
            throw new IllegalArgumentException("music_disc_maker: bad stored album length " + list.size());
        }
        final List<ItemStack> read = new ArrayList<>(list.size());
        for (final Tag element : list) {
            final ItemStack disc = ItemStack.of((CompoundTag) element);
            if (!disc.isEmpty()) {
                read.add(disc);
            }
        }
        return new AlbumContents(read);
    }
    *///?}

    /**
     * 入力列を深くコピーする。空 stack は実スロットではないので保存列から除く。
     *
     * @throws IllegalArgumentException 防御上限を超える場合
     */
    public AlbumContents {
        Objects.requireNonNull(discs, "discs");
        if (discs.size() > STORAGE_DECODE_LIMIT) {
            throw new IllegalArgumentException("album has too many stored stacks: " + discs.size());
        }
        final List<ItemStack> owned = new ArrayList<>(discs.size());
        for (final ItemStack disc : discs) {
            Objects.requireNonNull(disc, "disc");
            if (!disc.isEmpty()) {
                owned.add(disc.copy());
            }
        }
        discs = List.copyOf(owned);
    }

    /** コピーした変更不能な一覧を返す。要素を変更しても保存値は変わらない。 */
    @Override
    public List<ItemStack> discs() {
        return copyDiscs(this);
    }

    /** 収納中の盤数。 */
    public int size() {
        return discs.size();
    }

    /** 盤を持たないか。 */
    public boolean isEmpty() {
        return discs.isEmpty();
    }

    /** 指定位置の盤のコピー。範囲外なら {@link ItemStack#EMPTY}。 */
    public ItemStack discAt(int index) {
        return index < 0 || index >= discs.size() ? ItemStack.EMPTY : discs.get(index).copy();
    }

    /**
     * 候補を収納できるか。アルバムとブームボックスは {@code forbiddenContainer} で拒否する。
     *
     * @param candidate 収納候補
     * @param playableDisc MDM が再生できる盤かを判定する関数
     * @param forbiddenContainer アルバムまたはブームボックスかを判定する関数
     */
    public static boolean canInsert(ItemStack candidate, Predicate<ItemStack> playableDisc,
            Predicate<ItemStack> forbiddenContainer) {
        Objects.requireNonNull(candidate, "candidate");
        Objects.requireNonNull(playableDisc, "playableDisc");
        Objects.requireNonNull(forbiddenContainer, "forbiddenContainer");
        return !candidate.isEmpty() && playableDisc.test(candidate) && !forbiddenContainer.test(candidate);
    }

    /**
     * 盤のコピーを末尾へ足す。拒否対象を直接渡した場合は値を作らない。
     *
     * @throws IllegalArgumentException 再生不可の品、再帰容器、防御上限超過の場合
     */
    public AlbumContents withAppended(ItemStack candidate, Predicate<ItemStack> playableDisc,
            Predicate<ItemStack> forbiddenContainer) {
        if (!canInsert(candidate, playableDisc, forbiddenContainer)) {
            throw new IllegalArgumentException("album accepts playable discs but not recursive containers");
        }
        if (discs.size() >= STORAGE_DECODE_LIMIT) {
            throw new IllegalArgumentException("album reached its decode safety limit");
        }
        final List<ItemStack> changed = new ArrayList<>(discs);
        changed.add(candidate.copy());
        return new AlbumContents(changed);
    }

    /** 指定位置の盤を除いた値。範囲外なら同じインスタンス。 */
    public AlbumContents without(int index) {
        if (index < 0 || index >= discs.size()) {
            return this;
        }
        final List<ItemStack> changed = new ArrayList<>(discs);
        changed.remove(index);
        return changed.isEmpty() ? EMPTY : new AlbumContents(changed);
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof AlbumContents contents) || discs.size() != contents.discs.size()) {
            return false;
        }
        for (int i = 0; i < discs.size(); i++) {
            if (!ItemStack.matches(discs.get(i), contents.discs.get(i))) {
                return false;
            }
        }
        return true;
    }

    @Override
    public int hashCode() {
        int hash = 1;
        for (final ItemStack disc : discs) {
            //? if >=1.21 {
            hash = 31 * hash + ItemStack.hashItemAndComponents(disc);
            //?} else {
            /*int discHash = disc.getItem().hashCode();
            discHash = 31 * discHash + disc.getCount();
            discHash = 31 * discHash + (disc.getTag() == null ? 0 : disc.getTag().hashCode());
            hash = 31 * hash + discHash;
            *///?}
        }
        return hash;
    }

    private static List<ItemStack> copyDiscs(AlbumContents contents) {
        final List<ItemStack> copy = new ArrayList<>(contents.discs.size());
        for (final ItemStack disc : contents.discs) {
            copy.add(disc.copy());
        }
        return List.copyOf(copy);
    }

    //? if >=1.21 {
    private static <T> DataResult<T> withPersistentDepth(Supplier<DataResult<T>> operation) {
        final int depth = PERSISTENT_DEPTH.get();
        if (depth >= CODEC_DEPTH_LIMIT) {
            return DataResult.error(() -> "music_disc_maker: nested album contents are not allowed");
        }
        PERSISTENT_DEPTH.set(depth + 1);
        try {
            return operation.get();
        } finally {
            restoreDepth(PERSISTENT_DEPTH, depth);
        }
    }

    private static <T> T withStreamDepth(Supplier<T> operation) {
        final int depth = STREAM_DEPTH.get();
        if (depth >= CODEC_DEPTH_LIMIT) {
            throw new DecoderException("music_disc_maker: nested album contents are not allowed");
        }
        STREAM_DEPTH.set(depth + 1);
        try {
            return operation.get();
        } finally {
            restoreDepth(STREAM_DEPTH, depth);
        }
    }

    private static void restoreDepth(ThreadLocal<Integer> holder, int depth) {
        if (depth == 0) {
            holder.remove();
        } else {
            holder.set(depth);
        }
    }

    private static AlbumContents decodeFramed(RegistryFriendlyByteBuf input) {
        final int size = input.readVarInt();
        if (size < 0 || size > WIRE_BYTE_LIMIT) {
            throw new DecoderException("music_disc_maker: album payload is too large: " + size);
        }
        if (size > input.readableBytes()) {
            throw new DecoderException("music_disc_maker: truncated album payload");
        }
        final ByteBuf slice = input.readSlice(size);
        final RegistryFriendlyByteBuf limited = new RegistryFriendlyByteBuf(slice, input.registryAccess());
        final AlbumContents decoded = RAW_STREAM_CODEC.decode(limited);
        if (limited.isReadable()) {
            throw new DecoderException("music_disc_maker: trailing bytes in album payload");
        }
        return decoded;
    }

    private static void encodeFramed(RegistryFriendlyByteBuf output, AlbumContents value) {
        // 後段で readableBytes を見るだけでは、細工された値の符号化中に scratch が際限なく育つ。
        // Netty 側の最大 capacity も同じ境界へ固定し、確保の時点から 2 MiB を越えさせない。
        final ByteBuf scratch = output.alloc().buffer(256, WIRE_BYTE_LIMIT);
        try {
            final RegistryFriendlyByteBuf framed = new RegistryFriendlyByteBuf(scratch, output.registryAccess());
            try {
                RAW_STREAM_CODEC.encode(framed, value);
            } catch (IndexOutOfBoundsException tooLarge) {
                throw new EncoderException(
                        "music_disc_maker: album payload exceeded " + WIRE_BYTE_LIMIT + " bytes", tooLarge);
            }
            final int size = scratch.readableBytes();
            if (size > WIRE_BYTE_LIMIT) {
                throw new EncoderException("music_disc_maker: album payload is too large: " + size);
            }
            output.writeVarInt(size);
            output.writeBytes(scratch, scratch.readerIndex(), size);
        } finally {
            scratch.release();
        }
    }
    //?} else {
    //?}
}
