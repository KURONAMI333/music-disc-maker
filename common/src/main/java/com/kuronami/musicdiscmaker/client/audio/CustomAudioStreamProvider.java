package com.kuronami.musicdiscmaker.client.audio;

import java.util.concurrent.CompletableFuture;

import net.minecraft.client.resources.sounds.Sound;
import net.minecraft.client.sounds.AudioStream;
import net.minecraft.client.sounds.SoundBufferLibrary;

/**
 * SoundEngine が解決済みの {@link Sound} を渡す既存の loader 拡張点に接続する口。
 *
 * <p>custom disc は登録済みのダミーsoundを無視してLavaPlayer PCMを返す。vanilla speaker は
 * resource pack を通して選ばれた {@code Sound#getPath()} を利用する。loader mixinはこのinterface
 * だけを判定するため、音源の種類を増やしてもSoundEngine全体の再生経路を置き換えない。
 */
public interface CustomAudioStreamProvider {

    CompletableFuture<AudioStream> createAudioStream(SoundBufferLibrary buffers, Sound sound, boolean looping);
}
