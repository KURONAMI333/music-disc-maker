package com.kuronami.musicdiscmaker.lavaplayer;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class PcmQueueTest {

    /**
     * 競合の再現: reader が空を見た (poll が null) 直後に producer が最後の chunk を積んで終端を
     * 記録しても、終端は「空かつ終端」を同じ lock で見た時だけ返る。最後の chunk は捨てられない。
     */
    @Test
    void lastChunkPutRightAfterAnEmptyPollIsNotLost() throws Exception {
        final PcmQueue queue = new PcmQueue();
        assertNull(queue.pollOrEnd(), "空でまだ終端でない");

        queue.put(new byte[] {1, 2, 3});
        queue.end();

        assertTrue(queue.ended(), "旧実装はここで ended() だけを見て終端にしていた");
        assertArrayEquals(new byte[] {1, 2, 3}, queue.pollOrEnd(), "終端より先に最後の chunk を返す");
        assertSame(PcmQueue.END, queue.pollOrEnd());
        assertSame(PcmQueue.END, queue.pollOrEnd(), "終端は繰り返し返る");
    }

    /** close した queue は終端を返す (read が 0 を返し続けない)。 */
    @Test
    void closedQueueReportsTheEnd() {
        final PcmQueue queue = new PcmQueue();
        queue.close();

        assertSame(PcmQueue.END, queue.pollOrEnd());
    }

    @Test
    void closedResamplerConverterReleasesWithoutError() {
        final MonoPcmConverter converter = new MonoPcmConverter(44_100, 2, new PcmQueue());
        converter.close();
        converter.close();
    }
}
