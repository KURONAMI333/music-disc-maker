package com.kuronami.musicdiscmaker.network;

import com.kuronami.musicdiscmaker.MusicDiscMaker;

import net.minecraft.resources.ResourceLocation;

/**
 * 1.20.1 の Fabric 経路だけが要る id 解決。common の中立 payload は id を持たない
 * (id の型が版で改名されているため) ので、channel 名はここで組み立てる。
 * 1.20.5+ は {@code ModPayloadTypes} が同じ役割を持つ。
 */
public final class LegacyPayloadIds {

    private LegacyPayloadIds() {
    }

    /** payload の PATH 定数から channel id を組む (受信側の登録で使う)。 */
    public static ResourceLocation of(String path) {
        return new ResourceLocation(MusicDiscMaker.MODID, path);
    }

    public static ResourceLocation of(ModPayload payload) {
        return new ResourceLocation(MusicDiscMaker.MODID, path(payload));
    }

    public static String path(ModPayload payload) {
        if (payload instanceof PlayVanillaDiscPayload) {
            return PlayVanillaDiscPayload.PATH;
        }
        if (payload instanceof PlayDiscPayload) {
            return PlayDiscPayload.PATH;
        }
        if (payload instanceof StopDiscPayload) {
            return StopDiscPayload.PATH;
        }
        if (payload instanceof ResolveUrlPayload) {
            return ResolveUrlPayload.PATH;
        }
        if (payload instanceof ConfigureJukeboxPayload) {
            return ConfigureJukeboxPayload.PATH;
        }
        if (payload instanceof SeekJukeboxPayload) {
            return SeekJukeboxPayload.PATH;
        }
        if (payload instanceof NavigateJukeboxPayload) {
            return NavigateJukeboxPayload.PATH;
        }
        if (payload instanceof ShuffleJukeboxPayload) {
            return ShuffleJukeboxPayload.PATH;
        }
        if (payload instanceof ControlBoomboxPayload) {
            return ControlBoomboxPayload.PATH;
        }
        if (payload instanceof BoomboxPlayPayload) {
            return BoomboxPlayPayload.PATH;
        }
        if (payload instanceof BoomboxStopPayload) {
            return BoomboxStopPayload.PATH;
        }
        if (payload instanceof BoomboxStatePayload) {
            return BoomboxStatePayload.PATH;
        }
        if (payload instanceof SpeakerSetPayload) {
            return SpeakerSetPayload.PATH;
        }
        throw new IllegalArgumentException("Unregistered payload: " + payload.getClass().getName());
    }
}

