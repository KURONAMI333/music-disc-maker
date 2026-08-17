package com.kuronami.musicdiscmaker.compat.pocketjukebox;

import org.jetbrains.annotations.Nullable;

import com.kuronami.musicdiscmaker.compat.album.AlbumSupport;
import com.kuronami.musicdiscmaker.component.CustomTrackData;
import com.kuronami.musicdiscmaker.register.ModDataComponents;
import com.kuronami.musicdiscmaker.register.ModItems;

import net.minecraft.core.component.DataComponents;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.ItemContainerContents;

/**
 * 携帯ジュークボックス (Additional Additions の Pocket Jukebox) が今どのディスクを鳴らしているかの
 * 読み取り。**このクラスは AA の型を一切参照しない**。
 *
 * <h2>なぜ AA の型が要らないのか</h2>
 * AA の {@code PocketJukeboxItem} はアイテムを vanilla の {@link DataComponents#CONTAINER}
 * ({@link ItemContainerContents} の先頭 1 件) に格納する。挿されたのが素のディスクなら「1 トラックの
 * アルバム」として、アルバム item ならその中身をトラック列として扱う ({@code overrideOtherStackedOnMe}
 * の分岐そのまま)。前者は vanilla component、後者は既存の {@link AlbumSupport} アダプタで読めるので、
 * AA 固有の型 ({@code AlbumContents} / {@code PocketJukeboxPlayer}) に触れずに同じ列を再現できる。
 *
 * <p>{@link AlbumSupport} の実装は loader 側の compat が AA ロード時にだけ差し込む。未導入環境では
 * アルバム判定が常に false になるだけで、素のディスク経路はそのまま動く。
 */
public final class PocketJukeboxTracks {

    private PocketJukeboxTracks() {
    }

    /**
     * 携帯ジュークボックスが飲み込んでいる 1 アイテム (ディスク本体またはアルバム)。
     *
     * @param pocket 携帯ジュークボックスの stack
     * @return 格納物。空なら {@link ItemStack#EMPTY}
     */
    public static ItemStack stored(@Nullable ItemStack pocket) {
        if (pocket == null || pocket.isEmpty()) {
            return ItemStack.EMPTY;
        }
        final ItemContainerContents contents = pocket.get(DataComponents.CONTAINER);
        if (contents == null) {
            return ItemStack.EMPTY;
        }
        return contents.stream().findFirst().orElse(ItemStack.EMPTY);
    }

    /**
     * {@code index} 番目のトラックに当たるディスク。
     *
     * @param pocket 携帯ジュークボックスの stack
     * @param index  トラック番号 (AA の {@code currentTrack})
     * @return そのトラックのディスク。範囲外・未挿入なら {@link ItemStack#EMPTY}
     */
    public static ItemStack trackAt(@Nullable ItemStack pocket, int index) {
        if (index < 0) {
            return ItemStack.EMPTY;
        }
        final ItemStack stored = stored(pocket);
        if (stored.isEmpty()) {
            return ItemStack.EMPTY;
        }
        if (AlbumSupport.isAlbum(stored)) {
            return AlbumSupport.trackAt(stored, index);
        }
        // 素のディスクは AA 側でも「1 トラックのアルバム」として play() に渡される。
        return index == 0 ? stored : ItemStack.EMPTY;
    }

    /**
     * {@code index} 番目が MDM のカスタムディスクなら、その曲データ。
     *
     * @return 曲データ。MDM ディスクでない (バニラ / 他 MOD のディスク・空) なら {@code null}
     */
    @Nullable
    public static CustomTrackData mdmTrackAt(@Nullable ItemStack pocket, int index) {
        final ItemStack disc = trackAt(pocket, index);
        if (disc.isEmpty() || !disc.is(ModItems.CUSTOM_MUSIC_DISC.get())) {
            return null;
        }
        final CustomTrackData track = disc.get(ModDataComponents.CUSTOM_TRACK.get());
        return track == null || track.isEmpty() ? null : track;
    }

    /**
     * その曲を携帯ジュークボックスでストリームしてよいか。
     *
     * <p>ラジオ (無限長) は非対応。蓄音機 (Furniture) の判断 S2 に揃える。ここで {@code false} を返すと
     * MDM は音源を張らず、AA が自分の無音ディスクの尺で普段どおり止まる (= 挿しても止まる)。
     *
     * <p><b>ただし S2 の理由はこの経路には当てはまらない</b>。蓄音機の非対応は「金ジュークが持つ
     * 2 時間の再アーム機構が無い」= 無音ディスクの最長バケット (7200s) で切れることが理由だった。
     * 携帯ジュークボックスの保留判定 ({@link PocketJukeboxAdvance}) は無音ディスクの尺を一切読まないので
     * その制約を受けない。揃えているのは判断の一貫性のためで、技術的な障壁ではない。
     */
    public static boolean streamable(@Nullable CustomTrackData track) {
        return track != null && !track.isEmpty() && !track.radio();
    }
}
