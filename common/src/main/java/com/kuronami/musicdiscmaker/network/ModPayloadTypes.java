package com.kuronami.musicdiscmaker.network;

//? if >=1.21 {
import com.kuronami.musicdiscmaker.MusicDiscMaker;
//?} else {
//?}

//? if >=1.21.2 {
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
//? if >=1.21.11 {
import net.minecraft.resources.Identifier;
//?} else {
/*import net.minecraft.resources.ResourceLocation;
*///?}

/**
 * 1.20.5+ 用のアダプタ。common の中立 payload ({@link ModPayload}) を {@code CustomPacketPayload} に
 * 適合させる薄いラッパを、id と codec つきで提供する。ラッパは中身をそのまま
 * {@link ModPayload#write} に流すので、wire 形式は中立形が正本になる。
 *
 * <p>1.20.1 ノードではこのファイルの中身ごと落ちる (その版に {@code CustomPacketPayload} が無い)。
 * loader 側は 1.20.1 なら生 channel に {@link ModPayload#write} をそのまま流す。
 */
public final class ModPayloadTypes {

    private ModPayloadTypes() {
    }

    //? if >=1.21.11 {
    private static Identifier id(String path) {
        return Identifier.fromNamespaceAndPath(MusicDiscMaker.MODID, path);
    }
    //?} else {
    /*private static ResourceLocation id(String path) {
        return ResourceLocation.fromNamespaceAndPath(MusicDiscMaker.MODID, path);
    }
    *///?}

    /** 中立 payload を送信可能な形に包む。未知の型は配線漏れなので例外にする。 */
    public static CustomPacketPayload wrap(ModPayload payload) {
        if (payload instanceof LocalAudioPayload p) return new LocalAudio(p);
        if (payload instanceof PlayVanillaDiscPayload p) {
            return new PlayVanilla(p);
        }
        if (payload instanceof PlayDiscPayload p) {
            return new Play(p);
        }
        if (payload instanceof SpeakerSetPayload p) {
            return new SpeakerSet(p);
        }
        if (payload instanceof StopDiscPayload p) {
            return new Stop(p);
        }
        if (payload instanceof ResolveUrlPayload p) {
            return new ResolveUrl(p);
        }
        if (payload instanceof RenameDiscPayload p) {
            return new RenameDisc(p);
        }
        if (payload instanceof CancelDiscPayload p) {
            return new CancelDisc(p);
        }
        if (payload instanceof ConfigureJukeboxPayload p) {
            return new ConfigureJukebox(p);
        }
        if (payload instanceof SeekJukeboxPayload p) {
            return new SeekJukebox(p);
        }
        if (payload instanceof NavigateJukeboxPayload p) {
            return new NavigateJukebox(p);
        }
        if (payload instanceof ShuffleJukeboxPayload p) {
            return new ShuffleJukebox(p);
        }
        if (payload instanceof ControlBoomboxPayload p) {
            return new ControlBoombox(p);
        }
        if (payload instanceof OpenEquippedBoomboxPayload p) {
            return new OpenEquippedBoombox(p);
        }
        if (payload instanceof VersionPayload p) {
            return new Version(p);
        }
        if (payload instanceof BoomboxPlayPayload p) {
            return new BoomboxPlay(p);
        }
        if (payload instanceof BoomboxStopPayload p) {
            return new BoomboxStop(p);
        }
        if (payload instanceof BoomboxStatePayload p) {
            return new BoomboxState(p);
        }
        if (payload instanceof GoldenCartPlayPayload p) {
            return new GoldenCartPlay(p);
        }
        if (payload instanceof GoldenCartStopPayload p) {
            return new GoldenCartStop(p);
        }
        if (payload instanceof GoldenCartSyncPayload p) {
            return new GoldenCartSync(p);
        }
        throw new IllegalArgumentException("Unregistered payload: " + payload.getClass().getName());
    }

    public record LocalAudio(LocalAudioPayload inner) implements CustomPacketPayload {
        public static final Type<LocalAudio> TYPE = new Type<>(id(LocalAudioPayload.PATH));
        public static final StreamCodec<FriendlyByteBuf, LocalAudio> STREAM_CODEC = StreamCodec.of(
                (buf, msg) -> msg.inner().write(buf), buf -> new LocalAudio(LocalAudioPayload.read(buf)));
        @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
    }

    public record Play(PlayDiscPayload inner) implements CustomPacketPayload {
        public static final Type<Play> TYPE = new Type<>(id(PlayDiscPayload.PATH));
        public static final StreamCodec<FriendlyByteBuf, Play> STREAM_CODEC = StreamCodec.of(
                (buf, msg) -> msg.inner().write(buf), buf -> new Play(PlayDiscPayload.read(buf)));

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    public record PlayVanilla(PlayVanillaDiscPayload inner) implements CustomPacketPayload {
        public static final Type<PlayVanilla> TYPE = new Type<>(id(PlayVanillaDiscPayload.PATH));
        public static final StreamCodec<FriendlyByteBuf, PlayVanilla> STREAM_CODEC = StreamCodec.of(
                (buf, msg) -> msg.inner().write(buf), buf -> new PlayVanilla(PlayVanillaDiscPayload.read(buf)));

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    public record SpeakerSet(SpeakerSetPayload inner) implements CustomPacketPayload {
        public static final Type<SpeakerSet> TYPE = new Type<>(id(SpeakerSetPayload.PATH));
        public static final StreamCodec<FriendlyByteBuf, SpeakerSet> STREAM_CODEC = StreamCodec.of(
                (buf, msg) -> msg.inner().write(buf), buf -> new SpeakerSet(SpeakerSetPayload.read(buf)));

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    public record Stop(StopDiscPayload inner) implements CustomPacketPayload {
        public static final Type<Stop> TYPE = new Type<>(id(StopDiscPayload.PATH));
        public static final StreamCodec<FriendlyByteBuf, Stop> STREAM_CODEC = StreamCodec.of(
                (buf, msg) -> msg.inner().write(buf), buf -> new Stop(StopDiscPayload.read(buf)));

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    public record ResolveUrl(ResolveUrlPayload inner) implements CustomPacketPayload {
        public static final Type<ResolveUrl> TYPE = new Type<>(id(ResolveUrlPayload.PATH));
        public static final StreamCodec<FriendlyByteBuf, ResolveUrl> STREAM_CODEC = StreamCodec.of(
                (buf, msg) -> msg.inner().write(buf), buf -> new ResolveUrl(ResolveUrlPayload.read(buf)));

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    public record RenameDisc(RenameDiscPayload inner) implements CustomPacketPayload {
        public static final Type<RenameDisc> TYPE = new Type<>(id(RenameDiscPayload.PATH));
        public static final StreamCodec<FriendlyByteBuf, RenameDisc> STREAM_CODEC = StreamCodec.of(
                (buf, msg) -> msg.inner().write(buf), buf -> new RenameDisc(RenameDiscPayload.read(buf)));

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    public record CancelDisc(CancelDiscPayload inner) implements CustomPacketPayload {
        public static final Type<CancelDisc> TYPE = new Type<>(id(CancelDiscPayload.PATH));
        public static final StreamCodec<FriendlyByteBuf, CancelDisc> STREAM_CODEC = StreamCodec.of(
                (buf, msg) -> msg.inner().write(buf), buf -> new CancelDisc(CancelDiscPayload.read(buf)));

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    public record ConfigureJukebox(ConfigureJukeboxPayload inner) implements CustomPacketPayload {
        public static final Type<ConfigureJukebox> TYPE = new Type<>(id(ConfigureJukeboxPayload.PATH));
        public static final StreamCodec<FriendlyByteBuf, ConfigureJukebox> STREAM_CODEC = StreamCodec.of(
                (buf, msg) -> msg.inner().write(buf),
                buf -> new ConfigureJukebox(ConfigureJukeboxPayload.read(buf)));

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    public record SeekJukebox(SeekJukeboxPayload inner) implements CustomPacketPayload {
        public static final Type<SeekJukebox> TYPE = new Type<>(id(SeekJukeboxPayload.PATH));
        public static final StreamCodec<FriendlyByteBuf, SeekJukebox> STREAM_CODEC = StreamCodec.of(
                (buf, msg) -> msg.inner().write(buf), buf -> new SeekJukebox(SeekJukeboxPayload.read(buf)));

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    public record NavigateJukebox(NavigateJukeboxPayload inner) implements CustomPacketPayload {
        public static final Type<NavigateJukebox> TYPE = new Type<>(id(NavigateJukeboxPayload.PATH));
        public static final StreamCodec<FriendlyByteBuf, NavigateJukebox> STREAM_CODEC = StreamCodec.of(
                (buf, msg) -> msg.inner().write(buf), buf -> new NavigateJukebox(NavigateJukeboxPayload.read(buf)));

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    public record ShuffleJukebox(ShuffleJukeboxPayload inner) implements CustomPacketPayload {
        public static final Type<ShuffleJukebox> TYPE = new Type<>(id(ShuffleJukeboxPayload.PATH));
        public static final StreamCodec<FriendlyByteBuf, ShuffleJukebox> STREAM_CODEC = StreamCodec.of(
                (buf, msg) -> msg.inner().write(buf), buf -> new ShuffleJukebox(ShuffleJukeboxPayload.read(buf)));

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    public record ControlBoombox(ControlBoomboxPayload inner) implements CustomPacketPayload {
        public static final Type<ControlBoombox> TYPE = new Type<>(id(ControlBoomboxPayload.PATH));
        public static final StreamCodec<FriendlyByteBuf, ControlBoombox> STREAM_CODEC = StreamCodec.of(
                (buf, msg) -> msg.inner().write(buf), buf -> new ControlBoombox(ControlBoomboxPayload.read(buf)));

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    /** B5/C12: play 段ハンドシェイク (server → client へ PROTOCOL_VERSION を 1 回送る)。 */
    public record Version(VersionPayload inner) implements CustomPacketPayload {
        public static final Type<Version> TYPE = new Type<>(id(VersionPayload.PATH));
        public static final StreamCodec<FriendlyByteBuf, Version> STREAM_CODEC = StreamCodec.of(
                (buf, msg) -> msg.inner().write(buf), buf -> new Version(VersionPayload.read(buf)));

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    // 柱4: 携帯ブームボックス。鍵は座標ではなく機体の識別子 (BoomboxPlayPayload の javadoc)。
    public record BoomboxPlay(BoomboxPlayPayload inner) implements CustomPacketPayload {
        public static final Type<BoomboxPlay> TYPE = new Type<>(id(BoomboxPlayPayload.PATH));
        public static final StreamCodec<FriendlyByteBuf, BoomboxPlay> STREAM_CODEC = StreamCodec.of(
                (buf, msg) -> msg.inner().write(buf), buf -> new BoomboxPlay(BoomboxPlayPayload.read(buf)));

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    public record BoomboxStop(BoomboxStopPayload inner) implements CustomPacketPayload {
        public static final Type<BoomboxStop> TYPE = new Type<>(id(BoomboxStopPayload.PATH));
        public static final StreamCodec<FriendlyByteBuf, BoomboxStop> STREAM_CODEC = StreamCodec.of(
                (buf, msg) -> msg.inner().write(buf), buf -> new BoomboxStop(BoomboxStopPayload.read(buf)));

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    public record BoomboxState(BoomboxStatePayload inner) implements CustomPacketPayload {
        public static final Type<BoomboxState> TYPE = new Type<>(id(BoomboxStatePayload.PATH));
        public static final StreamCodec<FriendlyByteBuf, BoomboxState> STREAM_CODEC = StreamCodec.of(
                (buf, msg) -> msg.inner().write(buf), buf -> new BoomboxState(BoomboxStatePayload.read(buf)));

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    // 柱5: 金ジューク付きトロッコ。追従先は entityId、同一性は PlaybackSourceStamp。
    public record GoldenCartPlay(GoldenCartPlayPayload inner) implements CustomPacketPayload {
        public static final Type<GoldenCartPlay> TYPE = new Type<>(id(GoldenCartPlayPayload.PATH));
        public static final StreamCodec<FriendlyByteBuf, GoldenCartPlay> STREAM_CODEC = StreamCodec.of(
                (buf, msg) -> msg.inner().write(buf), buf -> new GoldenCartPlay(GoldenCartPlayPayload.read(buf)));

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    public record GoldenCartStop(GoldenCartStopPayload inner) implements CustomPacketPayload {
        public static final Type<GoldenCartStop> TYPE = new Type<>(id(GoldenCartStopPayload.PATH));
        public static final StreamCodec<FriendlyByteBuf, GoldenCartStop> STREAM_CODEC = StreamCodec.of(
                (buf, msg) -> msg.inner().write(buf), buf -> new GoldenCartStop(GoldenCartStopPayload.read(buf)));

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    public record GoldenCartSync(GoldenCartSyncPayload inner) implements CustomPacketPayload {
        public static final Type<GoldenCartSync> TYPE = new Type<>(id(GoldenCartSyncPayload.PATH));
        public static final StreamCodec<FriendlyByteBuf, GoldenCartSync> STREAM_CODEC = StreamCodec.of(
                (buf, msg) -> msg.inner().write(buf), buf -> new GoldenCartSync(GoldenCartSyncPayload.read(buf)));

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    /** C2S: Curios 装備中の機体を開く要求。Curios 型は持たない中立形。 */
    public record OpenEquippedBoombox(OpenEquippedBoomboxPayload inner) implements CustomPacketPayload {
        public static final Type<OpenEquippedBoombox> TYPE = new Type<>(id(OpenEquippedBoomboxPayload.PATH));
        public static final StreamCodec<FriendlyByteBuf, OpenEquippedBoombox> STREAM_CODEC = StreamCodec.of(
                (buf, msg) -> msg.inner().write(buf),
                buf -> new OpenEquippedBoombox(OpenEquippedBoomboxPayload.read(buf)));

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }
}

//?} elif >=1.21 {
/*import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/^*
 * 1.20.5+ 用のアダプタ。common の中立 payload ({@link ModPayload}) を {@code CustomPacketPayload} に
 * 適合させる薄いラッパを、id と codec つきで提供する。ラッパは中身をそのまま
 * {@link ModPayload#write} に流すので、wire 形式は中立形が正本になる。
 *
 * <p>1.20.1 ノードではこのファイルの中身ごと落ちる (その版に {@code CustomPacketPayload} が無い)。
 * loader 側は 1.20.1 なら生 channel に {@link ModPayload#write} をそのまま流す。
 ^/
public final class ModPayloadTypes {

    private ModPayloadTypes() {
    }

    private static ResourceLocation id(String path) {
        return ResourceLocation.fromNamespaceAndPath(MusicDiscMaker.MODID, path);
    }

    /^* 中立 payload を送信可能な形に包む。未知の型は配線漏れなので例外にする。 ^/
    public static CustomPacketPayload wrap(ModPayload payload) {
        if (payload instanceof LocalAudioPayload p) return new LocalAudio(p);
        if (payload instanceof PlayVanillaDiscPayload p) {
            return new PlayVanilla(p);
        }
        if (payload instanceof PlayDiscPayload p) {
            return new Play(p);
        }
        if (payload instanceof SpeakerSetPayload p) {
            return new SpeakerSet(p);
        }
        if (payload instanceof StopDiscPayload p) {
            return new Stop(p);
        }
        if (payload instanceof ResolveUrlPayload p) {
            return new ResolveUrl(p);
        }
        if (payload instanceof RenameDiscPayload p) {
            return new RenameDisc(p);
        }
        if (payload instanceof CancelDiscPayload p) {
            return new CancelDisc(p);
        }
        if (payload instanceof ConfigureJukeboxPayload p) {
            return new ConfigureJukebox(p);
        }
        if (payload instanceof SeekJukeboxPayload p) {
            return new SeekJukebox(p);
        }
        if (payload instanceof NavigateJukeboxPayload p) {
            return new NavigateJukebox(p);
        }
        if (payload instanceof ShuffleJukeboxPayload p) {
            return new ShuffleJukebox(p);
        }
        if (payload instanceof ControlBoomboxPayload p) {
            return new ControlBoombox(p);
        }
        if (payload instanceof OpenEquippedBoomboxPayload p) {
            return new OpenEquippedBoombox(p);
        }
        if (payload instanceof VersionPayload p) {
            return new Version(p);
        }
        if (payload instanceof BoomboxPlayPayload p) {
            return new BoomboxPlay(p);
        }
        if (payload instanceof BoomboxStopPayload p) {
            return new BoomboxStop(p);
        }
        if (payload instanceof BoomboxStatePayload p) {
            return new BoomboxState(p);
        }
        if (payload instanceof GoldenCartPlayPayload p) {
            return new GoldenCartPlay(p);
        }
        if (payload instanceof GoldenCartStopPayload p) {
            return new GoldenCartStop(p);
        }
        if (payload instanceof GoldenCartSyncPayload p) {
            return new GoldenCartSync(p);
        }
        throw new IllegalArgumentException("Unregistered payload: " + payload.getClass().getName());
    }

    public record LocalAudio(LocalAudioPayload inner) implements CustomPacketPayload {
        public static final Type<LocalAudio> TYPE = new Type<>(id(LocalAudioPayload.PATH));
        public static final StreamCodec<FriendlyByteBuf, LocalAudio> STREAM_CODEC = StreamCodec.of(
                (buf, msg) -> msg.inner().write(buf), buf -> new LocalAudio(LocalAudioPayload.read(buf)));
        @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
    }

    public record Play(PlayDiscPayload inner) implements CustomPacketPayload {
        public static final Type<Play> TYPE = new Type<>(id(PlayDiscPayload.PATH));
        public static final StreamCodec<FriendlyByteBuf, Play> STREAM_CODEC = StreamCodec.of(
                (buf, msg) -> msg.inner().write(buf), buf -> new Play(PlayDiscPayload.read(buf)));

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    public record PlayVanilla(PlayVanillaDiscPayload inner) implements CustomPacketPayload {
        public static final Type<PlayVanilla> TYPE = new Type<>(id(PlayVanillaDiscPayload.PATH));
        public static final StreamCodec<FriendlyByteBuf, PlayVanilla> STREAM_CODEC = StreamCodec.of(
                (buf, msg) -> msg.inner().write(buf), buf -> new PlayVanilla(PlayVanillaDiscPayload.read(buf)));

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    public record SpeakerSet(SpeakerSetPayload inner) implements CustomPacketPayload {
        public static final Type<SpeakerSet> TYPE = new Type<>(id(SpeakerSetPayload.PATH));
        public static final StreamCodec<FriendlyByteBuf, SpeakerSet> STREAM_CODEC = StreamCodec.of(
                (buf, msg) -> msg.inner().write(buf), buf -> new SpeakerSet(SpeakerSetPayload.read(buf)));

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    public record Stop(StopDiscPayload inner) implements CustomPacketPayload {
        public static final Type<Stop> TYPE = new Type<>(id(StopDiscPayload.PATH));
        public static final StreamCodec<FriendlyByteBuf, Stop> STREAM_CODEC = StreamCodec.of(
                (buf, msg) -> msg.inner().write(buf), buf -> new Stop(StopDiscPayload.read(buf)));

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    public record ResolveUrl(ResolveUrlPayload inner) implements CustomPacketPayload {
        public static final Type<ResolveUrl> TYPE = new Type<>(id(ResolveUrlPayload.PATH));
        public static final StreamCodec<FriendlyByteBuf, ResolveUrl> STREAM_CODEC = StreamCodec.of(
                (buf, msg) -> msg.inner().write(buf), buf -> new ResolveUrl(ResolveUrlPayload.read(buf)));

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    public record RenameDisc(RenameDiscPayload inner) implements CustomPacketPayload {
        public static final Type<RenameDisc> TYPE = new Type<>(id(RenameDiscPayload.PATH));
        public static final StreamCodec<FriendlyByteBuf, RenameDisc> STREAM_CODEC = StreamCodec.of(
                (buf, msg) -> msg.inner().write(buf), buf -> new RenameDisc(RenameDiscPayload.read(buf)));

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    public record CancelDisc(CancelDiscPayload inner) implements CustomPacketPayload {
        public static final Type<CancelDisc> TYPE = new Type<>(id(CancelDiscPayload.PATH));
        public static final StreamCodec<FriendlyByteBuf, CancelDisc> STREAM_CODEC = StreamCodec.of(
                (buf, msg) -> msg.inner().write(buf), buf -> new CancelDisc(CancelDiscPayload.read(buf)));

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    public record ConfigureJukebox(ConfigureJukeboxPayload inner) implements CustomPacketPayload {
        public static final Type<ConfigureJukebox> TYPE = new Type<>(id(ConfigureJukeboxPayload.PATH));
        public static final StreamCodec<FriendlyByteBuf, ConfigureJukebox> STREAM_CODEC = StreamCodec.of(
                (buf, msg) -> msg.inner().write(buf),
                buf -> new ConfigureJukebox(ConfigureJukeboxPayload.read(buf)));

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    public record SeekJukebox(SeekJukeboxPayload inner) implements CustomPacketPayload {
        public static final Type<SeekJukebox> TYPE = new Type<>(id(SeekJukeboxPayload.PATH));
        public static final StreamCodec<FriendlyByteBuf, SeekJukebox> STREAM_CODEC = StreamCodec.of(
                (buf, msg) -> msg.inner().write(buf), buf -> new SeekJukebox(SeekJukeboxPayload.read(buf)));

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    public record NavigateJukebox(NavigateJukeboxPayload inner) implements CustomPacketPayload {
        public static final Type<NavigateJukebox> TYPE = new Type<>(id(NavigateJukeboxPayload.PATH));
        public static final StreamCodec<FriendlyByteBuf, NavigateJukebox> STREAM_CODEC = StreamCodec.of(
                (buf, msg) -> msg.inner().write(buf), buf -> new NavigateJukebox(NavigateJukeboxPayload.read(buf)));

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    public record ShuffleJukebox(ShuffleJukeboxPayload inner) implements CustomPacketPayload {
        public static final Type<ShuffleJukebox> TYPE = new Type<>(id(ShuffleJukeboxPayload.PATH));
        public static final StreamCodec<FriendlyByteBuf, ShuffleJukebox> STREAM_CODEC = StreamCodec.of(
                (buf, msg) -> msg.inner().write(buf), buf -> new ShuffleJukebox(ShuffleJukeboxPayload.read(buf)));

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    public record ControlBoombox(ControlBoomboxPayload inner) implements CustomPacketPayload {
        public static final Type<ControlBoombox> TYPE = new Type<>(id(ControlBoomboxPayload.PATH));
        public static final StreamCodec<FriendlyByteBuf, ControlBoombox> STREAM_CODEC = StreamCodec.of(
                (buf, msg) -> msg.inner().write(buf), buf -> new ControlBoombox(ControlBoomboxPayload.read(buf)));

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    public record Version(VersionPayload inner) implements CustomPacketPayload {
        public static final Type<Version> TYPE = new Type<>(id(VersionPayload.PATH));
        public static final StreamCodec<FriendlyByteBuf, Version> STREAM_CODEC = StreamCodec.of(
                (buf, msg) -> msg.inner().write(buf), buf -> new Version(VersionPayload.read(buf)));

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    // 柱4: 携帯ブームボックス。鍵は座標ではなく機体の識別子 (BoomboxPlayPayload の javadoc)。
    public record BoomboxPlay(BoomboxPlayPayload inner) implements CustomPacketPayload {
        public static final Type<BoomboxPlay> TYPE = new Type<>(id(BoomboxPlayPayload.PATH));
        public static final StreamCodec<FriendlyByteBuf, BoomboxPlay> STREAM_CODEC = StreamCodec.of(
                (buf, msg) -> msg.inner().write(buf), buf -> new BoomboxPlay(BoomboxPlayPayload.read(buf)));

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    public record BoomboxStop(BoomboxStopPayload inner) implements CustomPacketPayload {
        public static final Type<BoomboxStop> TYPE = new Type<>(id(BoomboxStopPayload.PATH));
        public static final StreamCodec<FriendlyByteBuf, BoomboxStop> STREAM_CODEC = StreamCodec.of(
                (buf, msg) -> msg.inner().write(buf), buf -> new BoomboxStop(BoomboxStopPayload.read(buf)));

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    public record BoomboxState(BoomboxStatePayload inner) implements CustomPacketPayload {
        public static final Type<BoomboxState> TYPE = new Type<>(id(BoomboxStatePayload.PATH));
        public static final StreamCodec<FriendlyByteBuf, BoomboxState> STREAM_CODEC = StreamCodec.of(
                (buf, msg) -> msg.inner().write(buf), buf -> new BoomboxState(BoomboxStatePayload.read(buf)));

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    // 柱5: 金ジューク付きトロッコ。追従先は entityId、同一性は PlaybackSourceStamp。
    public record GoldenCartPlay(GoldenCartPlayPayload inner) implements CustomPacketPayload {
        public static final Type<GoldenCartPlay> TYPE = new Type<>(id(GoldenCartPlayPayload.PATH));
        public static final StreamCodec<FriendlyByteBuf, GoldenCartPlay> STREAM_CODEC = StreamCodec.of(
                (buf, msg) -> msg.inner().write(buf), buf -> new GoldenCartPlay(GoldenCartPlayPayload.read(buf)));

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    public record GoldenCartStop(GoldenCartStopPayload inner) implements CustomPacketPayload {
        public static final Type<GoldenCartStop> TYPE = new Type<>(id(GoldenCartStopPayload.PATH));
        public static final StreamCodec<FriendlyByteBuf, GoldenCartStop> STREAM_CODEC = StreamCodec.of(
                (buf, msg) -> msg.inner().write(buf), buf -> new GoldenCartStop(GoldenCartStopPayload.read(buf)));

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    public record GoldenCartSync(GoldenCartSyncPayload inner) implements CustomPacketPayload {
        public static final Type<GoldenCartSync> TYPE = new Type<>(id(GoldenCartSyncPayload.PATH));
        public static final StreamCodec<FriendlyByteBuf, GoldenCartSync> STREAM_CODEC = StreamCodec.of(
                (buf, msg) -> msg.inner().write(buf), buf -> new GoldenCartSync(GoldenCartSyncPayload.read(buf)));

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    /^* C2S: Curios 装備中の機体を開く要求。Curios 型は持たない中立形。 ^/
    public record OpenEquippedBoombox(OpenEquippedBoomboxPayload inner) implements CustomPacketPayload {
        public static final Type<OpenEquippedBoombox> TYPE = new Type<>(id(OpenEquippedBoomboxPayload.PATH));
        public static final StreamCodec<FriendlyByteBuf, OpenEquippedBoombox> STREAM_CODEC = StreamCodec.of(
                (buf, msg) -> msg.inner().write(buf),
                buf -> new OpenEquippedBoombox(OpenEquippedBoomboxPayload.read(buf)));

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }
}

*///?} else {
//?}
