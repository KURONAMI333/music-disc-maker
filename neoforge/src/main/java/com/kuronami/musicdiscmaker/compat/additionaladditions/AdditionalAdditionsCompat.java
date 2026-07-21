package com.kuronami.musicdiscmaker.compat.additionaladditions;

import java.util.List;

import com.kuronami.musicdiscmaker.event.AlbumPlaybackMirror;
import com.kuronami.musicdiscmaker.register.ModDataComponents;
import com.kuronami.musicdiscmaker.register.ModItems;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.JukeboxBlockEntity;

import one.dqu.additionaladditions.feature.album.AlbumContents;
import one.dqu.additionaladditions.feature.album.AlbumJukeboxExtension;
import one.dqu.additionaladditions.registry.AAMisc;

/**
 * Additional Additions のアルバム互換 (NeoForge)。**このクラスだけが AA の型を参照する**。
 * 呼び出し元 ({@code AlbumJukeboxTickMixin}) は AA が loaded の時だけこのクラスに触れるので、
 * AA 不在環境ではこのクラスが class-load されず AA 型の解決も一切走らない (無害性の担保)。
 *
 * <p>AA の album 再生ロジックは vanilla {@code JukeboxBlockEntity.tick} 内で無音の
 * {@code JukeboxSong} を鳴らしてトラックを送るだけで、MDM ディスクの実音声は出ない。ここで
 * AA の再生状態 ({@link AlbumJukeboxExtension}) と現在トラックの disc を読み取り、MDM ディスクなら
 * {@link AlbumPlaybackMirror} 経由で server→client のストリーム packet を出して実音声を鳴らす。
 */
public final class AdditionalAdditionsCompat {

    private AdditionalAdditionsCompat() {
    }

    /**
     * album jukebox の毎 tick フック (server 側)。現在トラックが MDM custom disc ならそれを、
     * そうでなければ null を {@link AlbumPlaybackMirror#mirror} に渡す。
     */
    public static void onServerTick(Level level, BlockPos pos, JukeboxBlockEntity jukebox) {
        if (!(level instanceof ServerLevel serverLevel)) {
            return;
        }
        if (!(jukebox instanceof AlbumJukeboxExtension album)) {
            return;
        }

        ItemStack mdmDisc = null;
        if (album.additionaladditions$isPlaying()) {
            final int track = album.additionaladditions$getTrack();
            if (track >= 0) {
                final AlbumContents contents =
                        jukebox.getTheItem().getOrDefault(AAMisc.ALBUM_CONTENTS_COMPONENT.get(), AlbumContents.EMPTY);
                final List<ItemStack> items = contents.items();
                if (track < items.size()) {
                    final ItemStack candidate = items.get(track);
                    if (candidate.is(ModItems.CUSTOM_MUSIC_DISC.get())
                            && candidate.has(ModDataComponents.CUSTOM_TRACK.get())) {
                        mdmDisc = candidate;
                    }
                }
            }
        }

        AlbumPlaybackMirror.mirror(serverLevel, pos, mdmDisc);
    }
}
