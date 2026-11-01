package com.kuronami.musicdiscmaker.lavaplayer;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 通常の入力 probe が扱えなかった入力を、magic で振り分けて直 decode の {@link PcmInput} にする。
 *
 * <ul>
 *   <li>{@code ftyp} (MP4): ALAC の M4A を {@link AlacPcmDecoder} で読む。AAC の MP4 は ALAC として
 *       開けないので {@code null} (通常経路の失敗理由を使う)。</li>
 *   <li>EBML (matroska / WebM): {@code A_FLAC} の音声 track があれば {@link MatroskaFlacPcmDecoder}。</li>
 * </ul>
 *
 * <p>対象外・壊れた入力はすべて {@code null}。理由を作り直さず、呼び出し側が通常経路の失敗を返す。
 */
final class DirectInputs {

    private static final Logger LOGGER = LoggerFactory.getLogger(DirectInputs.class);

    private static final int MAGIC_BYTES = 12;

    enum Kind {
        MP4, MATROSKA, OTHER
    }

    private DirectInputs() {
    }

    /** 先頭 12 byte の magic で container を見分ける。読めなければ {@link Kind#OTHER}。 */
    static Kind sniff(Path file) {
        final byte[] head = new byte[MAGIC_BYTES];
        int read = 0;
        try (InputStream in = Files.newInputStream(file)) {
            while (read < head.length) {
                final int n = in.read(head, read, head.length - read);
                if (n < 0) {
                    break;
                }
                read += n;
            }
        } catch (final IOException unreadable) {
            return Kind.OTHER;
        }
        if (read >= 8 && head[4] == 'f' && head[5] == 't' && head[6] == 'y' && head[7] == 'p') {
            return Kind.MP4;
        }
        if (read >= 4 && (head[0] & 0xFF) == 0x1A && (head[1] & 0xFF) == 0x45
                && (head[2] & 0xFF) == 0xDF && (head[3] & 0xFF) == 0xA3) {
            return Kind.MATROSKA;
        }
        return Kind.OTHER;
    }

    /** 直 decode できる入力なら {@link PcmInput}、そうでなければ {@code null}。 */
    static PcmInput open(Path file) {
        final String name = String.valueOf(file.getFileName());
        try {
            switch (sniff(file)) {
                case MP4: {
                    final AlacPcmDecoder.Info info = AlacPcmDecoder.probe(file);
                    return new DirectPcmInput(new AlacPcmDecoder(file, info), info.durationMs(),
                            PcmInput.Origin.ALAC, name);
                }
                case MATROSKA: {
                    final MatroskaFlacPcmDecoder.Info info = MatroskaFlacPcmDecoder.probe(file);
                    if (info == null) {
                        return null;
                    }
                    return new DirectPcmInput(new MatroskaFlacPcmDecoder(file, info), info.durationMs(),
                            PcmInput.Origin.MATROSKA_FLAC, name);
                }
                default:
                    return null;
            }
        } catch (final IOException | RuntimeException notDirect) {
            LOGGER.debug("{} is not a direct-decode input: {}", name, notDirect.toString());
            return null;
        }
    }
}
