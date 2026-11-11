package com.kuronami.musicdiscmaker.lavaplayer.codec;

import com.jcraft.jogg.Packet;
import com.jcraft.jorbis.Block;
import com.jcraft.jorbis.Comment;
import com.jcraft.jorbis.DspState;
import com.jcraft.jorbis.Info;

import java.nio.ByteBuffer;

/**
 * JOrbis による Vorbis decoder。native {@code VorbisDecoderLibrary} (libvorbis) の代わり。
 *
 * <p>LavaPlayer は identification と setup の 2 header しか渡さない (comment は読み捨て)。
 * libvorbis / JOrbis とも 3 本目まで順に食わせる必要があるので、空の comment header を挟む。
 */
public final class JavaVorbisDecoder {

    /** packet type 3 + "vorbis" + vendor 長 0 + comment 数 0 + framing bit。 */
    private static final byte[] EMPTY_COMMENT_HEADER = {
            3, 'v', 'o', 'r', 'b', 'i', 's', 0, 0, 0, 0, 0, 0, 0, 0, 1 };

    private final Info info = new Info();
    private final Comment comment = new Comment();
    private final Packet packet = new Packet();
    private final float[][][] pcm = new float[1][][];
    private DspState dsp;
    private Block block;
    private int[] index;
    private long packetNumber;

    /** @return 初期化できたら true */
    public boolean initialise(ByteBuffer infoBuffer, ByteBuffer setupBuffer) {
        info.init();
        comment.init();
        packetNumber = 0;
        if (headerIn(bytes(infoBuffer), true) < 0
                || headerIn(EMPTY_COMMENT_HEADER, false) < 0
                || headerIn(bytes(setupBuffer), false) < 0) {
            return false;
        }
        dsp = new DspState();
        if (dsp.synthesis_init(info) != 0) {
            return false;
        }
        block = new Block(dsp);
        index = new int[info.channels];
        return true;
    }

    public int getChannelCount() {
        return info.channels;
    }

    /** 1 audio packet を入れる。audio でない / 壊れた packet は libvorbis 同様に合成を飛ばす。 */
    public void input(ByteBuffer buffer) {
        setPacket(bytes(buffer), false);
        if (block.synthesis(packet) == 0) {
            dsp.synthesis_blockin(block);
        }
    }

    /** @return channel ごとに書いた sample 数 (各 channel の配列長が上限) */
    public int output(float[][] channels) {
        final int available = dsp.synthesis_pcmout(pcm, index);
        if (available <= 0) {
            return 0;
        }
        final int count = Math.min(available, channels[0].length);
        for (int channel = 0; channel < channels.length; channel++) {
            System.arraycopy(pcm[0][channel], index[channel], channels[channel], 0, count);
        }
        dsp.synthesis_read(count);
        return count;
    }

    private int headerIn(byte[] data, boolean first) {
        setPacket(data, first);
        return info.synthesis_headerin(comment, packet);
    }

    private void setPacket(byte[] data, boolean first) {
        packet.packet_base = data;
        packet.packet = 0;
        packet.bytes = data.length;
        packet.b_o_s = first ? 1 : 0;
        packet.e_o_s = 0;
        packet.granulepos = -1;
        packet.packetno = packetNumber++;
    }

    private static byte[] bytes(ByteBuffer buffer) {
        final byte[] data = new byte[buffer.remaining()];
        buffer.duplicate().get(data);
        return data;
    }
}
