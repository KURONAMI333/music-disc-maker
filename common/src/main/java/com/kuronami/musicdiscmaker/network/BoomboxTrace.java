package com.kuronami.musicdiscmaker.network;

import com.kuronami.musicdiscmaker.MusicDiscMaker;

/** Opt-in local diagnosis; only numeric session identifiers and fixed stage names are logged. */
public final class BoomboxTrace {
    private static final boolean ENABLED = Boolean.getBoolean("music_disc_maker.traceBoombox");

    private BoomboxTrace() { }

    public static void control(String stage, ControlBoomboxPayload payload, long currentGeneration) {
        if (ENABLED) {
            MusicDiscMaker.LOGGER.info("[Boombox trace] {} menu={} action={} generation={} current={}",
                    stage, payload.containerId(), payload.action(), payload.generation(), currentGeneration);
        }
    }

    public static void audio(String stage, BoomboxPlayPayload payload) {
        if (ENABLED) {
            MusicDiscMaker.LOGGER.info("[Boombox trace] {} box={} owner={} generation={} volume={}",
                    stage, payload.boomboxId(), payload.ownerEntityId(), payload.audioGeneration(), payload.volumePercent());
        }
    }

    public static void stop(String stage, long boomboxId) {
        if (ENABLED) {
            MusicDiscMaker.LOGGER.info("[Boombox trace] {} box={}", stage, boomboxId);
        }
    }

    public static void state(String stage, BoomboxStatePayload payload) {
        if (ENABLED) {
            MusicDiscMaker.LOGGER.info("[Boombox trace] {} menu={} generation={} state={}", stage,
                    payload.containerId(), payload.state().cursor().generation(), payload.state().cursor().state());
        }
    }
}
