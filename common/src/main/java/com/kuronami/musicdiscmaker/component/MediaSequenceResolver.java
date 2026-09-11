package com.kuronami.musicdiscmaker.component;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

import com.kuronami.musicdiscmaker.item.CustomMusicDiscItem;
import com.kuronami.musicdiscmaker.register.ModItems;
//? if >=1.21 {
import com.kuronami.musicdiscmaker.register.ModDataComponents;
//?} else {
//?}

import net.minecraft.world.item.ItemStack;

/**
 * raw 単曲と MDM Album の実 {@link ItemStack} を再生可能位置へ展開する。
 *
 * <p>AA Album は既存の互換経路が所有しているため、ここでは扱わない。MDM Album の各盤は
 * {@link AlbumContents} に保存された順序と盤 index を保つ。再生できない盤は飛ばすが、
 * その前後の盤 index を詰めないため、同じ URL の盤もそれぞれの位置で残る。
 */
public final class MediaSequenceResolver {

    public enum Kind {
        RAW_SINGLE,
        MDM_ALBUM,
        INVALID
    }

    /** resolver が返す 1 曲。媒体の同一性は URL ではなく position にある。 */
    public record ResolvedTrack(MediaSequence.Position position, CustomTrackData track) {
        public ResolvedTrack {
            Objects.requireNonNull(position, "position");
            Objects.requireNonNull(track, "track");
        }
    }

    /** root 媒体の種類と、その媒体で再生可能な実位置。 */
    public record ResolvedSequence(Kind kind, List<ResolvedTrack> tracks) {
        public ResolvedSequence {
            Objects.requireNonNull(kind, "kind");
            Objects.requireNonNull(tracks, "tracks");
            tracks = List.copyOf(tracks);
        }

        public MediaSequence positions() {
            return MediaSequence.of(tracks.stream().map(ResolvedTrack::position).toList());
        }

        public Optional<ResolvedTrack> first() {
            return tracks.isEmpty() ? Optional.empty() : Optional.of(tracks.get(0));
        }

        public Optional<ResolvedTrack> at(MediaSequence.Position position) {
            return tracks.stream().filter(candidate -> candidate.position().equals(position)).findFirst();
        }

        public Optional<ResolvedTrack> next(MediaSequence.Position current, boolean repeat, boolean manual) {
            if (!manual) {
                final Optional<ResolvedTrack> playing = at(current);
                if (playing.isEmpty() || !MediaSequence.allowsAutomaticAdvance(
                        playing.get().track().durationMs(), playing.get().track().radio())) {
                    return Optional.empty();
                }
            }
            return positions().next(current, repeat).flatMap(this::at);
        }

        public Optional<ResolvedTrack> previous(MediaSequence.Position current, boolean repeat) {
            return positions().previous(current, repeat).flatMap(this::at);
        }
    }

    private MediaSequenceResolver() {
    }

    public static ResolvedSequence resolve(ItemStack medium) {
        Objects.requireNonNull(medium, "medium");
        if (medium.isEmpty()) {
            return new ResolvedSequence(Kind.INVALID, List.of());
        }
        if (medium.is(ModItems.ALBUM.get())) {
            return resolveAlbum(medium);
        }

        final List<ResolvedTrack> tracks = new ArrayList<>();
        appendDisc(tracks, medium, 0);
        if (tracks.isEmpty()) {
            return new ResolvedSequence(Kind.INVALID, List.of());
        }
        return new ResolvedSequence(Kind.RAW_SINGLE, tracks);
    }

    private static ResolvedSequence resolveAlbum(ItemStack album) {
        //? if >=1.21 {
        final AlbumContents contents = album.get(ModDataComponents.ALBUM_CONTENTS.get());
        final AlbumContents present = contents != null ? contents : AlbumContents.EMPTY;
        //?} else {
        /*final AlbumContents present = AlbumContents.of(album);
        *///?}
        final List<ResolvedTrack> tracks = new ArrayList<>();
        for (int discIndex = 0; discIndex < present.size(); discIndex++) {
            appendDisc(tracks, present.discAt(discIndex), discIndex);
        }
        return new ResolvedSequence(Kind.MDM_ALBUM, tracks);
    }

    private static void appendDisc(List<ResolvedTrack> output, ItemStack disc, int discIndex) {
        if (!disc.is(ModItems.CUSTOM_MUSIC_DISC.get())) {
            return;
        }
        final CustomTrackData track = singleTrackOf(disc);
        if (track != null && !track.isEmpty()) {
            output.add(new ResolvedTrack(new MediaSequence.Position(discIndex, 0), track));
        }
    }

    private static CustomTrackData singleTrackOf(ItemStack disc) {
        //? if >=1.21 {
        return disc.get(ModDataComponents.CUSTOM_TRACK.get());
        //?} else {
        /*return CustomMusicDiscItem.getTrack(disc);
        *///?}
    }
}
