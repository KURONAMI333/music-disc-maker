package com.kuronami.musicdiscmaker.event;

import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

import com.kuronami.musicdiscmaker.MusicDiscMaker;
import com.kuronami.musicdiscmaker.component.CustomTrackData;
import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

import net.minecraft.core.BlockPos;
//? if <1.21.11 {
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtOps;
//?}
import net.minecraft.server.level.ServerLevel;
//? if >=1.21.11 {
import net.minecraft.resources.Identifier;
import net.minecraft.util.datafix.DataFixTypes;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.level.saveddata.SavedDataType;
//?} elif >=1.20.5 {
/*import net.minecraft.core.HolderLookup;
import net.minecraft.world.level.saveddata.SavedData;
*///?} else {
/*import net.minecraft.world.level.saveddata.SavedData;
*///?}

/**
 * {@link ActiveDiscRegistry} の永続層 (次元ごとの SavedData)。B4「server 再起動後、刺しっぱなしの
 * ディスクが一度だけ頭出しする」の修正で追加した。
 *
 * <p>registry は in-memory なので再起動で消え、chunk 再送の走査が鳴り終わったディスクを
 * 「まだ始まっていない」と読んで offset 0 で再生し直していた。ここへ書き出しておけば、
 * 再起動後の最初の chunk 走査 ({@code ActiveDiscPersistence#hydrate}) で復元され、
 * 鳴り終わりエントリが「既知」のまま残る。
 *
 * <p>保存内容は最小で、pos・開始時刻 (wall-clock)・トラック情報だけ。ディスクがスロットから
 * 消えたことは {@link ActiveDiscRegistry#stop} の経路で削除されるので、撤去済み jukebox の
 * ゴミが永続化され続けることはない。
 *
 * <p>版差は取得経路だけ。1.21.11+ は vanilla の codec 駆動 {@link SavedDataType}
 * (26.x で storage が {@code SavedDataStorage} へ改称・{@code getChunkSource()} 経由になる)、
 * 1.21.1 は {@code SavedData.Factory}・1.20.1 は (loadFn, ctorFn, name) の旧署名。
 * 中身の列挙は全帯で CODEC 1 本に統一してある。
 */
public final class ActiveDiscSaveData extends SavedData {

    private static final String DATA_NAME = "music_disc_maker_active_discs";

    /** 1 エントリ = jukebox 位置 + 開始時刻 + トラック情報。 */
    private static final Codec<PlayingEntry> ENTRY_CODEC = RecordCodecBuilder.create(instance ->
            instance.group(
                    Codec.LONG.fieldOf("pos").forGetter(e -> e.posAsLong()),
                    Codec.LONG.fieldOf("start").forGetter(e -> e.startMillis),
                    CustomTrackData.CODEC.fieldOf("track").forGetter(e -> e.track))
                    .apply(instance, PlayingEntry::new));

    /**
     * pos (asLong) の 10 進文字列を鍵にした map。
     *
     * <p>鍵を long にはできない。{@link Codec#unboundedMap} の encode は {@code ops.mapBuilder()} を
     * 通り、NbtOps が返す {@code NbtRecordBuilder} は全帯で {@code AbstractStringBuilder} を継承していて、
     * StringTag 以外の鍵を {@code "Not a string"} で弾く (1.20.1 / 1.21.1 の decompile で確認済み)。
     * 26.x と 1.21.11 では vanilla の SavedData がその失敗を投げて保存ごと落ち、1.21.1 と 1.20.1 では
     * {@code CodecBridge} が握り潰して空の tag を書く (無言で永続化が効かない)。
     */
    public static final Codec<ActiveDiscSaveData> CODEC = RecordCodecBuilder.create(instance ->
            instance.group(
                    Codec.unboundedMap(Codec.STRING, ENTRY_CODEC)
                            .fieldOf("entries").forGetter(ActiveDiscSaveData::encodedStringKeys))
                    .apply(instance, ActiveDiscSaveData::fromStringKeys));

    private record PlayingEntry(long posAsLong, long startMillis, CustomTrackData track) {
    }

    private final Map<BlockPos, ActiveDiscRegistry.Playing> entries = new HashMap<>();

    public ActiveDiscSaveData() {
    }

    /** 鍵は pos.asLong() の 10 進文字列。 */
    private static ActiveDiscSaveData fromStringKeys(Map<String, PlayingEntry> decoded) {
        final ActiveDiscSaveData data = new ActiveDiscSaveData();
        for (final PlayingEntry e : decoded.values()) {
            final BlockPos pos = BlockPos.of(e.posAsLong());
            data.entries.put(pos, new ActiveDiscRegistry.Playing(
                    pos.immutable(), e.track(), e.startMillis));
        }
        return data;
    }

    private Map<String, PlayingEntry> encodedStringKeys() {
        final Map<String, PlayingEntry> out = new HashMap<>();
        for (final ActiveDiscRegistry.Playing p : entries.values()) {
            out.put(Long.toString(p.pos().asLong()),
                    new PlayingEntry(p.pos().asLong(), p.startMillis(), p.track()));
        }
        return out;
    }

    /**
     * 次元の data storage から取得 (無ければ作る)。以降の変更は {@link #setDirty()} で
     * 次回のワールド保存に乗る。
     */
    //? if >=26.1 {
    public static ActiveDiscSaveData get(ServerLevel level) {
        return level.getChunkSource().getDataStorage().computeIfAbsent(new SavedDataType<>(
                Identifier.fromNamespaceAndPath(MusicDiscMaker.MODID, "active_discs"),
                ActiveDiscSaveData::new, CODEC, DataFixTypes.LEVEL));
    }
    //?} elif >=1.21.11 {
    /*public static ActiveDiscSaveData get(ServerLevel level) {
        return level.getDataStorage().computeIfAbsent(
                new SavedDataType<>(DATA_NAME, ActiveDiscSaveData::new, CODEC, DataFixTypes.LEVEL));
    }
    *///?} elif >=1.20.5 {
    /*public static ActiveDiscSaveData get(ServerLevel level) {
        return level.getDataStorage().computeIfAbsent(
                storageFactory(), storageDataName());
    }

    static SavedData.Factory<ActiveDiscSaveData> storageFactory() {
        return new SavedData.Factory<>(ActiveDiscSaveData::new,
                (tag, registries) -> CodecBridge.decode(tag), null);
    }

    static String storageDataName() {
        return DATA_NAME;
    }

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        return CodecBridge.encode(this);
    }
    */    //?} else {
    /*public static ActiveDiscSaveData get(ServerLevel level) {
        return level.getDataStorage()
                .computeIfAbsent(ActiveDiscSaveData::decodeBridge, ActiveDiscSaveData::new, DATA_NAME);
    }

    private static ActiveDiscSaveData decodeBridge(CompoundTag tag) {
        return CodecBridge.decode(tag);
    }

    @Override
    public CompoundTag save(CompoundTag tag) {
        return CodecBridge.encode(this);
    }
    *///?}

    /** 1 エントリを書き込んで dirty にする (挿入・chunk 走査の新規検出)。 */
    public void put(BlockPos pos, CustomTrackData track, long startMillis) {
        entries.put(pos.immutable(),
                new ActiveDiscRegistry.Playing(pos.immutable(), track, startMillis));
        setDirty();
    }

    /** 1 エントリを消して dirty にする (ディスクがスロットから消えた)。 */
    public void remove(BlockPos pos) {
        if (entries.remove(pos.immutable()) != null) {
            setDirty();
        }
    }

    /** 保存済みの全エントリ (読み取り専用)。hydrate の材料。 */
    public Collection<ActiveDiscRegistry.Playing> entries() {
        return Collections.unmodifiableCollection(entries.values());
    }

    /**
     * 旧 2 帯 (1.21.1 / 1.20.1) の CompoundTag 橋。CODEC を NbtOps で往復させるだけで、
     * 形式の定義は CODEC 1 本に寄せてある。1.21.11+ は SavedDataType が CODEC を直接
     * 受け取るのでこの橋は不要 (存在しない)。
     */
    //? if <1.21.11 {
    private static final class CodecBridge {
        private CodecBridge() {
        }

        static ActiveDiscSaveData decode(CompoundTag tag) {
            return CODEC.parse(NbtOps.INSTANCE, tag).result().orElse(new ActiveDiscSaveData());
        }

        static CompoundTag encode(ActiveDiscSaveData data) {
            return CODEC.encodeStart(NbtOps.INSTANCE, data)
                    .result().map(t -> (CompoundTag) t).orElse(new CompoundTag());
        }
    }
    //?}
}
