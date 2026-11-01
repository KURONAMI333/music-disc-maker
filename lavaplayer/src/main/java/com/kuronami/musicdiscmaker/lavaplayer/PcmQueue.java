package com.kuronami.musicdiscmaker.lavaplayer;

import java.io.InterruptedIOException;
import java.util.ArrayDeque;

/**
 * decoder と再生側の間の有界 queue。48kHz mono S16LE の chunk を
 * {@link #MAX_BUFFERED_BYTES} まで積み、溢れたら producer を待たせる (背圧)。
 *
 * <p>全曲を溜めないための境界で、2 時間の入力でも常駐は 128KB 程度に収まる。
 * {@link #poll()} は待たない ({@code null} = 今は無い) — {@code IAudioSource.read} が
 * ゲームの Sound engine スレッドから呼ばれるので、ここで待つと本体が止まる。
 */
final class PcmQueue {

    /** 積める上限。decoder はこれを超えて先行しない。 */
    static final int MAX_BUFFERED_BYTES = 128 * 1024;

    private final ArrayDeque<byte[]> chunks = new ArrayDeque<>();
    private int buffered;
    private boolean ended;
    private boolean closed;

    /**
     * chunk を積む。上限に達していれば空くまで待つ。
     *
     * @throws InterruptedIOException 待っている間に中断された、または queue が閉じられた
     */
    synchronized void put(byte[] chunk) throws InterruptedIOException {
        while (!closed && buffered >= MAX_BUFFERED_BYTES) {
            try {
                wait();
            } catch (final InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new InterruptedIOException("interrupted while buffering");
            }
        }
        if (closed) {
            throw new InterruptedIOException("closed");
        }
        chunks.addLast(chunk);
        buffered += chunk.length;
        notifyAll();
    }

    /** 積まれた chunk を 1 つ取り出す。無ければ {@code null} (待たない)。 */
    synchronized byte[] poll() {
        final byte[] head = chunks.pollFirst();
        if (head != null) {
            buffered -= head.length;
            notifyAll();
        }
        return head;
    }

    /** 取り切った後の終端を表す目印 (chunk としては使われない)。 */
    static final byte[] END = new byte[0];

    /**
     * 積まれた chunk を 1 つ取り出す。空で終端済みなら {@link #END}、空でまだ終端でなければ
     * {@code null}。「空か」と「終端か」を同じ lock の中で見るので、{@link #poll()} と
     * {@link #ended()} を別々に呼ぶ時の「最後の chunk が積まれた直後に終端と誤認する」競合が無い。
     */
    synchronized byte[] pollOrEnd() {
        final byte[] head = poll();
        if (head != null) {
            return head;
        }
        return ended ? END : null;
    }

    /** 終端が記録されたか (積まれた chunk が残っていても true になり得る)。 */
    synchronized boolean ended() {
        return ended;
    }

    /** 終端を記録する (積まれた chunk は {@link #poll()} で取り切れる)。 */
    synchronized void end() {
        ended = true;
        notifyAll();
    }

    /** 破棄して閉じる。待っている producer は {@link InterruptedIOException} で抜ける。 */
    synchronized void close() {
        closed = true;
        chunks.clear();
        buffered = 0;
        notifyAll();
    }
}
