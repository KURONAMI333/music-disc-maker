package com.kuronami.musicdiscmaker.event;

import com.kuronami.musicdiscmaker.compat.album.AlbumSupport;
import com.kuronami.musicdiscmaker.component.CustomTrackData;
import com.kuronami.musicdiscmaker.debug.MdmProbe;
import com.kuronami.musicdiscmaker.network.PlayDiscPayload;
import com.kuronami.musicdiscmaker.network.StopDiscPayload;
import com.kuronami.musicdiscmaker.platform.Services;
import com.kuronami.musicdiscmaker.register.ModDataComponents;

import org.jetbrains.annotations.Nullable;

import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;

/**
 * Additional Additions のアルバムが jukebox で custom disc を「再生中」の状態を、MDM の
 * ストリーミング再生にミラーする単一の入口。**AA 固有の型を一切参照しない** (loader 側の
 * {@code AdditionalAdditionsCompat} が AA 状態を読み、ここには vanilla/MDM 型だけ渡す)。
 *
 * <p>state は {@link ActiveDiscRegistry} を唯一の真実として再利用する。これにより既存の
 * late-joiner 同期 (ChunkWatch) が album 再生にもそのまま効く。毎 tick 呼ばれる前提で
 * idempotent (差分が無ければ何も送らない)。全操作は server thread からのみ。
 *
 * <p>入口は 2 つある。{@link #mirror} は「この座標で今この disc が鳴っている」を素直に反映する
 * 汎用の口で、蓄音機 (Furniture) のように MDM が中身を直接読める音源が使う。
 * {@link #mirrorAlbumJukebox} はバニラ jukebox の tick フック専用で、アルバムが挿さっている座標
 * だけに絞ってから {@link #mirror} へ流す。
 */
public final class AlbumPlaybackMirror {

    private AlbumPlaybackMirror() {
    }

    /**
     * バニラ jukebox の tick から album 再生を反映する (server 側・毎 tick)。
     *
     * <p><b>この口が管轄するのはアルバムが挿さっている座標だけ</b>。AA はバニラ
     * {@code JukeboxBlockEntity} に mixin するので、呼び出し元の tick フックは<b>全ての</b>
     * バニラ jukebox で走り、素のディスクが入っているだけの座標もここへ来る。そこを
     * {@code mdmDisc == null} の停止側に落とすと、{@link JukeboxDiscController} が挿入時に
     * 張ったばかりの再生を次の tick で消してしまう (登録まで消えるので late-join 再送も効かず、
     * 入れ直しても 1 tick 後にまた止まる)。素のディスクの開始/停止は
     * {@link JukeboxDiscController} の管轄なので、ここは触らずに返す。
     *
     * @param held    その jukebox が今保持している stack (アルバム本体)
     * @param mdmDisc いま album が再生中の MDM custom disc stack。null なら「MDM ストリーム対象なし」
     *                (アルバム停止・空・vanilla disc トラック・トラック index 範囲外を含む)。
     */
    public static void mirrorAlbumJukebox(ServerLevel level, BlockPos pos, ItemStack held,
            @Nullable ItemStack mdmDisc) {
        if (!AlbumSupport.isAlbum(held)) {
            return;
        }
        mirror(level, pos, mdmDisc);
    }

    /**
     * その座標の現在状態を反映する (server 側・毎 tick)。
     *
     * @param mdmDisc いま鳴っている MDM custom disc stack。null なら「MDM ストリーム対象なし」
     *                = 直前まで鳴らしていたなら停止する。
     */
    public static void mirror(ServerLevel level, BlockPos pos, @Nullable ItemStack mdmDisc) {
        final ResourceKey<Level> dim = level.dimension();
        final BlockPos key = pos.immutable();
        final ActiveDiscRegistry.Playing existing = ActiveDiscRegistry.current(dim, key);

        if (mdmDisc != null) {
            final CustomTrackData track = mdmDisc.get(ModDataComponents.CUSTOM_TRACK.get());
            if (track != null && !track.isEmpty()) {
                // 同じ url を既にストリーム中なら何もしない (毎 tick の再送を防ぐ)。
                if (existing != null && track.url().equals(existing.track().url())) {
                    MdmProbe.mirrorBranch("dedup", level, key, track.url(), -1);
                    return;
                }
                // 新規トラック (差し替え含む)。client 側 startPlayback が旧ストリームを停止して張り替える。
                ActiveDiscRegistry.start(dim, key, track, System.currentTimeMillis());
                // アルバム再生は強化版ジュークボックスではないので vanilla 経路 (既定 range / volume 100)。
                final ChunkPos chunk = new ChunkPos(key);
                MdmProbe.mirrorBranch("start", level, key, track.url(), MdmProbe.recipients(level, chunk));
                Services.NETWORK.sendToPlayersTrackingChunk(level, chunk,
                        PlayDiscPayload.vanilla(key, track, 0L));
                return;
            }
        }

        // MDM ストリーム対象なし → 直前まで鳴らしていたなら停止する。
        if (existing != null) {
            ActiveDiscRegistry.stop(dim, key);
            final ChunkPos chunk = new ChunkPos(key);
            MdmProbe.mirrorBranch("stop", level, key, existing.track().url(),
                    MdmProbe.recipients(level, chunk));
            Services.NETWORK.sendToPlayersTrackingChunk(level, chunk, new StopDiscPayload(key));
            return;
        }
        // 何も鳴っていない座標。mdmDisc が来ているのに track が無い形 (= MDM のディスクではない)
        // をここで見分けられるようにする。
        MdmProbe.mirrorBranch(mdmDisc == null ? "idle (no disc)" : "idle (disc without track)",
                level, key, null, -1);
    }
}
