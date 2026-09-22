package com.kuronami.musicdiscmaker.client.audio;

import java.util.List;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;

/** vanilla が OpenAL sound engine 全体を作り直した後に MDM の voice を張り直す。 */
public final class ClientSoundReloadRecovery {
    private ClientSoundReloadRecovery() {}

    /** {@code SoundEngine.reload()} が新しい channel pool を作った後に呼ぶ。 */
    public static void afterSoundEngineReload() {
        final Minecraft minecraft = Minecraft.getInstance();
        final ClientLevel expectedLevel = minecraft.level;
        if (expectedLevel == null) return;
        // resource apply 全体の後へ回す。level の同一性で、この task と競合した退出/再入場も弾く。
        minecraft.execute(() -> {
            if (minecraft.level != expectedLevel) return;

            // 代わりを 1 つも開く前に stale voice を全て無効化する。先に開くと旧エントリが
            // 共有 streaming-channel 上限を使い続け、先に復帰した音源が後続を拒否する。
            final List<Runnable> resumes = List.of(
                    ClientPlaybackManager.get().prepareSoundReloadResume(),
                    BoomboxClientPlayback.prepareSoundReloadResume(),
                    VanillaSpeakerPlayback.prepareSoundReloadResume());
            if (minecraft.level != expectedLevel) return;
            resumes.forEach(Runnable::run);
        });
    }
}
