package com.kuronami.musicdiscmaker.client.audio;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Minecraft の OpenAL source を通常効果音とストリーミング音源へ分配する純粋計算。
 *
 * <p>MDM は最大 64 音源を使い、同じ streaming pool を使う BGM・バニラ盤・環境音へ
 * 8 枠を残す。通常効果音用の static pool にも最低 8 枠を残す。デバイスの source 数が
 * 足りない場合は物理上限を越えず、MDM の auto 上限を実際の streaming pool に合わせて下げる。
 */
public final class StreamingChannelPool {

    /** MDM が既定で同時再生できる最大数。設定の上限とも一致する。 */
    public static final int MAX_MDM_SOURCES = 64;
    /** BGM・バニラ盤・環境音のために streaming pool に残す数。 */
    public static final int STREAMING_RESERVE = 8;
    /** 足音や操作音などのために static pool に残す最小数。 */
    public static final int STATIC_RESERVE = 8;
    /** 通常デバイスで確保する streaming pool の数。 */
    public static final int TARGET_STREAMING_SOURCES = MAX_MDM_SOURCES + STREAMING_RESERVE;

    private static final int MIN_STREAMING_SOURCES = 2;
    private static final Pattern DEBUG_COUNTS = Pattern.compile("^Sounds: \\d+/\\d+ \\+ \\d+/(\\d+)$");

    private StreamingChannelPool() {
    }

    /**
     * OpenAL device の mono source 数から、MDM が要求する pool 分配を返す。
     *
     * <p>source 数が最低分配 ({@code static=8, streaming=2}) に満たない場合も、報告された
     * 総数を越えない範囲で streaming を最大 2 枠残す。
     *
     * @param channelCount OpenAL device が報告した mono source 数
     * @return static / streaming の要求上限
     */
    public static Allocation allocate(int channelCount) {
        final int available = Math.max(0, channelCount);
        final int minimumStreaming = Math.min(MIN_STREAMING_SOURCES, available);
        final int streaming = Math.min(TARGET_STREAMING_SOURCES,
                Math.max(minimumStreaming, available - STATIC_RESERVE));
        final int staticSources = available - streaming;
        return new Allocation(staticSources, streaming);
    }

    /**
     * 既存の static pool を増やさず、MDM 分の streaming source を確保できる上限へ下げる。
     * 他 MOD が既に static pool を小さくしている場合は、その値を保持する。
     */
    public static int staticLimit(int currentLimit, Allocation allocation) {
        return Math.min(currentLimit, allocation.staticLimit());
    }

    /**
     * MDM の要求値と既存値の大きい方を、static 確保後に残る物理上限までで返す。
     * 他 MOD が static 側も譲って streaming pool を拡張している場合は、その値を縮めない。
     */
    public static int streamingLimit(int currentLimit, Allocation allocation, int availableAfterStatic) {
        return Math.min(Math.max(currentLimit, allocation.streamingLimit()), Math.max(0, availableAfterStatic));
    }

    /**
     * 実際の streaming pool から、MDM の auto 同時再生数を返す。
     * BGM・バニラ盤・環境音用の余白を引き、設定上限の 64 を越えない。
     */
    public static int automaticMdmLimit(int actualStreamingLimit) {
        return Math.min(MAX_MDM_SOURCES, Math.max(1, actualStreamingLimit - STREAMING_RESERVE));
    }

    /** 設定値を実 pool の安全上限へ丸める。0 以下は auto を表す。 */
    public static int effectiveMdmLimit(int configuredLimit, int actualStreamingLimit) {
        final int automaticLimit = automaticMdmLimit(actualStreamingLimit);
        return configuredLimit <= 0 ? automaticLimit : Math.min(configuredLimit, automaticLimit);
    }

    /**
     * {@code Library#getDebugString/getChannelDebugString} が返す実 pool 表示から streaming の
     * {@code getMaxCount()} を読む。未初期化・形式変更・整数範囲外では 0 を返し、デバイス数から
     * 推測し直さない。
     *
     * @param debugString Minecraft の sound pool debug 文字列
     * @return 実 streaming pool 上限。取得不能または未初期化なら 0
     */
    public static int actualStreamingLimit(String debugString) {
        if (debugString == null) {
            return 0;
        }
        final Matcher matcher = DEBUG_COUNTS.matcher(debugString);
        if (!matcher.matches()) {
            return 0;
        }
        try {
            return Integer.parseInt(matcher.group(1));
        } catch (NumberFormatException ignored) {
            return 0;
        }
    }

    /** static / streaming pool の要求上限。 */
    public record Allocation(int staticLimit, int streamingLimit) {
        /** 不正な負数を pool 構築へ渡さない。 */
        public Allocation {
            if (staticLimit < 0 || streamingLimit < 0) {
                throw new IllegalArgumentException("Sound pool limits must be non-negative");
            }
        }
    }
}
