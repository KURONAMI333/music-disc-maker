package com.kuronami.musicdiscmaker.component;

import java.util.concurrent.ThreadLocalRandom;

//? if >=1.21 {
import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
//?} else {
/*import net.minecraft.nbt.CompoundTag;
*///?}
import net.minecraft.world.item.ItemStack;

/**
 * ブームボックスが咥えているディスク 1 枚と、その機体の識別子。アイテム側に載る値で、設置しても
 * 同じ ItemStack ごと BlockEntity に入るので、器 (アイテム) と中身 (ディスク) が離れない。
 *
 * <p>受けるのは MDM の custom disc・プレイリストディスク・アルバムのどれでもよい。
 * 種別で弾かないのは、v3 で「ディスクは 3 種とも携帯で鳴らせる」が裁定済みだから
 * (MDM_DECISIONS「ブームボックスと金ジュークは『範囲』で切る」)。実際に何を受けるかの
 * 検査は投入経路 (GUI) 側の責務で、この容れ物は種別を知らない。
 *
 * <h2>格納先は帯で違う</h2>
 * <ul>
 *   <li>1.21 以上 — DataComponent ({@code ModDataComponents#BOOMBOX_CONTENTS})。
 *       {@code CODEC} で永続化し {@code STREAM_CODEC} で同期する</li>
 *   <li>1.20.1 — アイテムの NBT。DataComponent が無い帯なので {@code toNbt} / {@code fromNbt} と、
 *       stack への出し入れ ({@code of} / {@code store}) をこの帯だけに持つ。
 *       {@link CustomTrackData} と同じ分業で、値の形はここ 1 箇所にある</li>
 * </ul>
 *
 * <h2>{@link #id} — 機体 1 台ごとの識別子</h2>
 * 「インベントリに在れば鳴る」×「複数台同時」を満たすには、<b>1 台ごとに独立した音源を張る鍵</b>が
 * 要る。座標では指せず (持ち歩ける)、スロット番号でも指せない (動かしても鳴り続ける)、持ち主でも
 * 指せない (1 人が複数台持つ)。残るのはアイテム個体そのもので、そこに番号を持たせる。
 * 先行実装の Sophisticated Backpacks が {@code storageUuid} でやっているのと同じ形。
 *
 * <p><b>採番するのは server の走査 1 回目</b> ({@code BoomboxPlayback#identify})。クラフト直後の
 * スタックに割り当てる口がバニラに無いので、「鳴らそうとした時に無ければ振る」に寄せてある。
 * {@link #UNASSIGNED} = まだ振っていない。
 *
 * <p><b>UUID ではなく {@code long} にしてある。</b> component の codec と payload の両方で使うが、
 * {@code UUIDUtil} を common から参照している前例が 1.21.1 と 1.20.1 の帯にしか無く、26.x で
 * 同じ名前で在ることを確かめていない。{@code Codec.LONG} / {@code ByteBufCodecs.VAR_LONG} は
 * 全帯で使用実績がある。衝突は 64bit 乱数なので実質起きないうえ、走査が同じインベントリ内の
 * 重複を見つけたら振り直す ({@code BoomboxPlayback#serve}) ので、当たっても自己修復する。
 *
 * <p>{@code equals} を書き直してあるのは、{@link ItemStack} の既定の等価が同一性判定だから。
 * record の自動生成のままだと、中身が同じ 2 つのブームボックスが別物と判定される。
 * <b>{@link #id} は等価に含める</b> — 含めないと、別々に鳴っている 2 台の component が
 * 「同じ値」に見えてスタック関連の判定を誤らせる (アイテム自体は {@code stacksTo(1)} なので
 * 併合は起きないが、等価の意味を実態からずらさない)。
 */
public record BoomboxContents(ItemStack disc, long id, PlaybackCursor cursor, long pausedOffsetMs,
        boolean repeat, boolean shuffle, long shuffleSeed, int shuffleAnchorDisc, int shuffleAnchorTrack,
        int volumePercent) {

    /** まだ採番されていない機体。 */
    public static final long UNASSIGNED = 0L;

    /**
     * 可聴範囲の<b>既定値</b> (ブロック)。KURONAMI333 裁定 2026-09-07 で 16 に確定した。
     *
     * <p><b>ゲーム内の GUI には出さない。</b>範囲調整・指向性・スピーカー網は金ジューク専権で、
     * 携帯は「自分の周りの小さい固定半径だけ」というのが差別化の軸
     * (KURONAMI333 裁定「範囲を伸ばせず、指向性も持たない」)。
     *
     * <p>ただし<b>client config からは変えられる</b> (KURONAMI333 裁定 2026-09-07「念の為、config で
     * 調整できるようにしておこうか」)。既定が気に入らない人の逃げ道であって機能ではないので、
     * 上限は金ジュークの 256 まで開けていない。実際に読むのは
     * {@code Config#boomboxRange()} で、ここはその既定値。
     */
    public static final int RANGE_DEFAULT = 16;

    /** 空のブームボックス。値が無い時と同じ意味 (既定色ならぬ既定中身を発明しない)。 */
    public static final BoomboxContents EMPTY = new BoomboxContents(ItemStack.EMPTY, UNASSIGNED);

    /** v2 の component / NBT を読むための互換コンストラクタ。 */
    public BoomboxContents(ItemStack disc, long id) {
        this(disc, id, PlaybackCursor.initial(), 0L, false, false, 0L,
                PlaybackCursor.NO_INDEX, PlaybackCursor.NO_INDEX, 100);
    }

    public BoomboxContents {
        cursor = cursor == null ? PlaybackCursor.initial() : cursor;
        pausedOffsetMs = Math.max(0L, pausedOffsetMs);
        volumePercent = Math.max(0, Math.min(100, volumePercent));
        if ((shuffleAnchorDisc == PlaybackCursor.NO_INDEX) != (shuffleAnchorTrack == PlaybackCursor.NO_INDEX)) {
            shuffleAnchorDisc = PlaybackCursor.NO_INDEX;
            shuffleAnchorTrack = PlaybackCursor.NO_INDEX;
        }
    }
    //? if >=1.21 {

    public static final Codec<BoomboxContents> CODEC = RecordCodecBuilder.create(instance -> instance.group(
            ItemStack.OPTIONAL_CODEC.optionalFieldOf("disc", ItemStack.EMPTY).forGetter(BoomboxContents::disc),
            Codec.LONG.optionalFieldOf("id", UNASSIGNED).forGetter(BoomboxContents::id),
            Codec.INT.optionalFieldOf("cursorDisc", PlaybackCursor.NO_INDEX)
                    .forGetter(value -> value.cursor.discIndex()),
            Codec.INT.optionalFieldOf("cursorTrack", PlaybackCursor.NO_INDEX)
                    .forGetter(value -> value.cursor.trackIndex()),
            Codec.intRange(0, 2).optionalFieldOf("cursorState", 0)
                    .forGetter(value -> value.cursor.state().ordinal()),
            Codec.LONG.optionalFieldOf("cursorGeneration", 0L)
                    .forGetter(value -> value.cursor.generation()),
            Codec.LONG.optionalFieldOf("pausedOffsetMs", 0L).forGetter(BoomboxContents::pausedOffsetMs),
            Codec.BOOL.optionalFieldOf("repeat", false).forGetter(BoomboxContents::repeat),
            Codec.BOOL.optionalFieldOf("shuffle", false).forGetter(BoomboxContents::shuffle),
            Codec.LONG.optionalFieldOf("shuffleSeed", 0L).forGetter(BoomboxContents::shuffleSeed),
            Codec.INT.optionalFieldOf("shuffleAnchorDisc", PlaybackCursor.NO_INDEX)
                    .forGetter(BoomboxContents::shuffleAnchorDisc),
            Codec.INT.optionalFieldOf("shuffleAnchorTrack", PlaybackCursor.NO_INDEX)
                    .forGetter(BoomboxContents::shuffleAnchorTrack),
            Codec.INT.optionalFieldOf("volumePercent", 100).forGetter(BoomboxContents::volumePercent)
    ).apply(instance, (disc, id, cursorDisc, cursorTrack, cursorState, cursorGeneration, pausedOffset,
            repeat, shuffle, shuffleSeed, shuffleAnchorDisc, shuffleAnchorTrack, volumePercent) ->
            new BoomboxContents(disc, id, cursorFrom(cursorDisc, cursorTrack, cursorState, cursorGeneration),
                    pausedOffset, repeat, shuffle, shuffleSeed, shuffleAnchorDisc, shuffleAnchorTrack, volumePercent)));

    public static final StreamCodec<RegistryFriendlyByteBuf, BoomboxContents> STREAM_CODEC =
            new StreamCodec<>() {
                @Override
                public BoomboxContents decode(RegistryFriendlyByteBuf buf) {
                    final ItemStack disc = ItemStack.OPTIONAL_STREAM_CODEC.decode(buf);
                    return new BoomboxContents(disc, buf.readVarLong(), cursorFrom(buf.readVarInt(),
                            buf.readVarInt(), buf.readVarInt(), buf.readVarLong()), buf.readVarLong(),
                            buf.readBoolean(), buf.readBoolean(), buf.readVarLong(), buf.readVarInt(),
                            buf.readVarInt(), buf.readVarInt());
                }

                @Override
                public void encode(RegistryFriendlyByteBuf buf, BoomboxContents value) {
                    ItemStack.OPTIONAL_STREAM_CODEC.encode(buf, value.disc);
                    buf.writeVarLong(value.id);
                    buf.writeVarInt(value.cursor.discIndex());
                    buf.writeVarInt(value.cursor.trackIndex());
                    buf.writeVarInt(value.cursor.state().ordinal());
                    buf.writeVarLong(value.cursor.generation());
                    buf.writeVarLong(value.pausedOffsetMs);
                    buf.writeBoolean(value.repeat);
                    buf.writeBoolean(value.shuffle);
                    buf.writeVarLong(value.shuffleSeed);
                    buf.writeVarInt(value.shuffleAnchorDisc);
                    buf.writeVarInt(value.shuffleAnchorTrack);
                    buf.writeVarInt(value.volumePercent);
                }
            };
    //?} else {
    /*
    /^* アイテムの NBT 内でこの値を置くキー。{@code CustomMusicDiscItem#NBT_KEY} と同じ流儀。 ^/
    public static final String NBT_KEY = "boombox";

    /^*
     * stack の NBT から中身を読む。
     *
     * @param stack ブームボックスのスタック
     * @return 中身。無ければ {@link #EMPTY}
     ^/
    public static BoomboxContents of(ItemStack stack) {
        final CompoundTag tag = stack.getTag();
        if (tag != null && tag.contains(NBT_KEY, CompoundTag.TAG_COMPOUND)) {
            return fromNbt(tag.getCompound(NBT_KEY));
        }
        return EMPTY;
    }

    /^*
     * stack の NBT へ中身を書く。
     *
     * @param stack    ブームボックスのスタック
     * @param contents 書き込む中身
     ^/
    public static void store(ItemStack stack, BoomboxContents contents) {
        stack.getOrCreateTag().put(NBT_KEY, contents.toNbt());
    }

    /^*
     * NBT へ書き出す。ディスクが空なら {@code disc} を書かない (無い = 空、を保つ)。
     *
     * @return 書き出した tag
     ^/
    public CompoundTag toNbt() {
        final CompoundTag tag = new CompoundTag();
        if (!disc.isEmpty()) {
            tag.put("disc", disc.save(new CompoundTag()));
        }
        tag.putLong("id", id);
        tag.putInt("cursorDisc", cursor.discIndex());
        tag.putInt("cursorTrack", cursor.trackIndex());
        tag.putInt("cursorState", cursor.state().ordinal());
        tag.putLong("cursorGeneration", cursor.generation());
        tag.putLong("pausedOffsetMs", pausedOffsetMs);
        tag.putBoolean("repeat", repeat);
        tag.putBoolean("shuffle", shuffle);
        tag.putLong("shuffleSeed", shuffleSeed);
        tag.putInt("shuffleAnchorDisc", shuffleAnchorDisc);
        tag.putInt("shuffleAnchorTrack", shuffleAnchorTrack);
        tag.putInt("volumePercent", volumePercent);
        return tag;
    }

    /^*
     * NBT から復元する。
     *
     * @param tag 読み出し元 ({@code null} 可)
     * @return 中身。読めなければ {@link #EMPTY}
     ^/
    public static BoomboxContents fromNbt(CompoundTag tag) {
        if (tag == null) {
            return EMPTY;
        }
        final ItemStack disc = tag.contains("disc", CompoundTag.TAG_COMPOUND)
                ? ItemStack.of(tag.getCompound("disc"))
                : ItemStack.EMPTY;
        return new BoomboxContents(disc, tag.getLong("id"),
                tag.contains("cursorDisc") ? cursorFrom(tag.getInt("cursorDisc"), tag.getInt("cursorTrack"),
                        tag.getInt("cursorState"), tag.getLong("cursorGeneration")) : PlaybackCursor.initial(),
                tag.getLong("pausedOffsetMs"), tag.getBoolean("repeat"), tag.getBoolean("shuffle"),
                tag.getLong("shuffleSeed"), tag.contains("shuffleAnchorDisc") ? tag.getInt("shuffleAnchorDisc")
                        : PlaybackCursor.NO_INDEX,
                tag.contains("shuffleAnchorTrack") ? tag.getInt("shuffleAnchorTrack")
                        : PlaybackCursor.NO_INDEX,
                tag.contains("volumePercent") ? tag.getInt("volumePercent") : 100);
    }
    *///?}

    public boolean isEmpty() {
        return disc.isEmpty();
    }

    /** ディスクが入っているか。 */
    public boolean hasDisc() {
        return !disc.isEmpty();
    }

    /** 採番済みか。 */
    public boolean hasId() {
        return id != UNASSIGNED;
    }

    /** ディスクだけ差し替えた複製 (GUI のスロット操作)。識別子は機体のものなので持ち回る。 */
    public BoomboxContents withDisc(ItemStack value) {
        return new BoomboxContents(value.copy(), id, cursor.clear().invalidate(), 0L, repeat, false, 0L,
                PlaybackCursor.NO_INDEX, PlaybackCursor.NO_INDEX, volumePercent);
    }

    /** 識別子だけ差し替えた複製 (採番・重複の振り直し)。 */
    public BoomboxContents withId(long value) {
        return new BoomboxContents(disc, value, cursor, pausedOffsetMs, repeat, shuffle, shuffleSeed,
                shuffleAnchorDisc, shuffleAnchorTrack, volumePercent);
    }

    public BoomboxContents withPlayback(PlaybackCursor value, long offset) {
        return new BoomboxContents(disc, id, value, offset, repeat, shuffle, shuffleSeed,
                shuffleAnchorDisc, shuffleAnchorTrack, volumePercent);
    }

    public BoomboxContents withRepeat(boolean value) {
        return new BoomboxContents(disc, id, cursor, pausedOffsetMs, value, shuffle, shuffleSeed,
                shuffleAnchorDisc, shuffleAnchorTrack, volumePercent);
    }

    public BoomboxContents withShuffle(boolean value, long seed, int anchorDisc, int anchorTrack) {
        return new BoomboxContents(disc, id, cursor, pausedOffsetMs, repeat, value, seed,
                anchorDisc, anchorTrack, volumePercent);
    }

    public BoomboxContents withVolumePercent(int value) {
        return new BoomboxContents(disc, id, cursor, pausedOffsetMs, repeat, shuffle, shuffleSeed,
                shuffleAnchorDisc, shuffleAnchorTrack, value);
    }

    /**
     * 新しい機体の識別子を振る。{@link #UNASSIGNED} は返さない (「未採番」と区別が付かなくなる)。
     *
     * @return 0 でない乱数
     */
    public static long mintId() {
        long minted = ThreadLocalRandom.current().nextLong();
        while (minted == UNASSIGNED) {
            minted = ThreadLocalRandom.current().nextLong();
        }
        return minted;
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof BoomboxContents contents
                && id == contents.id
                && cursor.equals(contents.cursor)
                && pausedOffsetMs == contents.pausedOffsetMs
                && repeat == contents.repeat
                && shuffle == contents.shuffle
                && shuffleSeed == contents.shuffleSeed
                && shuffleAnchorDisc == contents.shuffleAnchorDisc
                && shuffleAnchorTrack == contents.shuffleAnchorTrack
                && volumePercent == contents.volumePercent
                && ItemStack.matches(disc, contents.disc);
    }

    @Override
    public int hashCode() {
        //? if >=1.21 {
        int hash = 31 * ItemStack.hashItemAndComponents(disc) + Long.hashCode(id);
        hash = 31 * hash + cursor.hashCode();
        hash = 31 * hash + Long.hashCode(pausedOffsetMs);
        hash = 31 * hash + Boolean.hashCode(repeat);
        hash = 31 * hash + Boolean.hashCode(shuffle);
        hash = 31 * hash + Long.hashCode(shuffleSeed);
        hash = 31 * hash + shuffleAnchorDisc;
        hash = 31 * hash + shuffleAnchorTrack;
        return 31 * hash + volumePercent;
        //?} else {
        /*// 1.20.1 に hashItemAndComponents は無い。equals が見ている軸 (アイテム種別・NBT・個数) と
        // 同じものから組む。ItemStack#hashCode は同一性判定なので使えない。
        int hash = disc.getItem().hashCode();
        hash = 31 * hash + disc.getCount();
        hash = 31 * hash + (disc.getTag() == null ? 0 : disc.getTag().hashCode());
        hash = 31 * hash + Long.hashCode(id);
        hash = 31 * hash + cursor.hashCode();
        hash = 31 * hash + Long.hashCode(pausedOffsetMs);
        hash = 31 * hash + Boolean.hashCode(repeat);
        hash = 31 * hash + Boolean.hashCode(shuffle);
        hash = 31 * hash + Long.hashCode(shuffleSeed);
        hash = 31 * hash + shuffleAnchorDisc;
        hash = 31 * hash + shuffleAnchorTrack;
        return 31 * hash + volumePercent;
        *///?}
    }

    private static PlaybackCursor cursorFrom(int discIndex, int trackIndex, int stateOrdinal, long generation) {
        final PlaybackCursor.State[] states = PlaybackCursor.State.values();
        final PlaybackCursor.State state = stateOrdinal >= 0 && stateOrdinal < states.length
                ? states[stateOrdinal] : PlaybackCursor.State.STOPPED;
        final long safeGeneration = Math.max(0L, generation);
        final boolean hasPosition = discIndex >= 0 && trackIndex >= 0;
        if (!hasPosition) {
            return new PlaybackCursor(PlaybackCursor.NO_INDEX, PlaybackCursor.NO_INDEX,
                    PlaybackCursor.State.STOPPED, safeGeneration);
        }
        return new PlaybackCursor(discIndex, trackIndex, state, safeGeneration);
    }
}
