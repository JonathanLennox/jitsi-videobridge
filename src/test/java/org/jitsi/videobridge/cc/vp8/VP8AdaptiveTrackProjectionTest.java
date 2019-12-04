package org.jitsi.videobridge.cc.vp8;

import org.jitsi.nlj.format.*;
import org.jitsi.nlj.rtp.codec.vp8.*;
import org.jitsi.rtp.rtp.*;
import org.jitsi.rtp.util.*;
import org.jitsi.utils.logging.DiagnosticContext;
import org.jitsi.utils.logging2.*;
import org.jitsi.videobridge.cc.*;
import org.jitsi_modified.impl.neomedia.codec.video.vp8.*;
import org.junit.*;

import javax.xml.bind.*;
import java.text.*;
import java.util.concurrent.*;

import static org.junit.Assert.*;

public class VP8AdaptiveTrackProjectionTest
{
    private final DiagnosticContext diagnosticContext = new DiagnosticContext();
    private final Logger logger = new LoggerImpl(getClass().getName());
    private final PayloadType payloadType = new Vp8PayloadType((byte)96,
        new ConcurrentHashMap<>(), new CopyOnWriteArraySet<>());

    @Test
    public void simpleProjectionTest() throws RewriteException
    {
        RtpState initialState =
            new RtpState(1, 10000, 1000000);

        VP8AdaptiveTrackProjectionContext context =
            new VP8AdaptiveTrackProjectionContext(diagnosticContext, payloadType,
                initialState, logger);

        Vp8PacketGenerator generator = new Vp8PacketGenerator(1);

        Vp8Packet packet = generator.nextPacket();

        assertTrue(context.accept(packet, 0, 0));

        context.rewriteRtp(packet);

        assertEquals(10001, packet.getSequenceNumber());
        assertEquals(1003000, packet.getTimestamp());
    }

    private static class Vp8PacketGenerator {
        private static final byte[] vp8PacketTemplate =
            DatatypeConverter.parseHexBinary(
                /* RTP Header */
                "80" + /* V, P, X, CC */
                    "60" + /* M, PT */
                    "0000" + /* Seq */
                    "00000000" + /* TS */
                    "cafebabe" + /* SSRC */
                    /* VP8 Payload descriptor */
                    "90" + /* First byte, X, S set, PID = 0 */
                    "e0" + /* X byte, I, L, T set */
                    "8000" + /* I byte (ext pic id), M set */
                    "00" + /* L byte (tl0 pic idx) */
                    "00" + /* T/K byte (tid) */
                    /* VP8 payload header */
                    "00" + /* P = 0. */
                /* Rest of payload is "don't care" for this code, except
                   keyframe frame size, so make this a valid-ish keyframe header.
                 */
                    "0000" + /* Length = 0. */
                    "9d012a" + /* Keyframe startcode */
                    "0050D002" /* 1280 × 720 (little-endian) */
            );

        private final int packetsPerFrame;

        Vp8PacketGenerator(int packetsPerFrame)
        {
            this.packetsPerFrame = packetsPerFrame;
        }

        private int seq = 0;
        private long ts = 0;
        private int picId = 0;
        private int tl0picidx = 0;
        private int packetOfFrame = 0;
        private boolean keyframe = true;
        private int tidCycle = 0;

        public Vp8Packet nextPacket()
        {
            int tid;
            switch(tidCycle % 4)
            {
            case 0:
                tid = 0;
                break;
            case 2:
                tid = 1;
                break;
            case 1:
            case 3:
                tid = 2;
                break;
            default:
                /* Convince Java that yes, tid is always initialized. */
                assert(false); /* Math is broken */
                tid = -1;
            }

            boolean startOfFrame = (packetOfFrame == 0);
            boolean endOfFrame = (packetOfFrame == packetsPerFrame - 1);

            byte[] buffer = vp8PacketTemplate.clone();

            RtpPacket rtpPacket = new RtpPacket(buffer,0, buffer.length);

            rtpPacket.setSequenceNumber(seq);
            rtpPacket.setTimestamp(ts);

            /* Do VP8 manipulations on buffer before constructing Vp8Packet, because
               Vp8Packet computes values at construct-time. */
            DePacketizer.VP8PayloadDescriptor.setStartOfPartition(rtpPacket.buffer,
                rtpPacket.getPayloadOffset(), startOfFrame);
            DePacketizer.VP8PayloadDescriptor.setTemporalLayerIndex(rtpPacket.buffer,
                rtpPacket.getPayloadOffset(), rtpPacket.length, tid);

            if (startOfFrame) {
                int szVP8PayloadDescriptor = DePacketizer
                    .VP8PayloadDescriptor.getSize(rtpPacket.buffer, rtpPacket.getPayloadOffset(), rtpPacket.length);

                DePacketizer.VP8PayloadHeader.setKeyFrame(rtpPacket.buffer,
                    rtpPacket.getPayloadOffset() + szVP8PayloadDescriptor, keyframe);
            }
            rtpPacket.setMarked(endOfFrame);

            Vp8Packet vp8Packet = rtpPacket.toOtherType(Vp8Packet::new);

            vp8Packet.setPictureId(picId);
            vp8Packet.setTL0PICIDX(tl0picidx);

            seq = RtpUtils.applySequenceNumberDelta(seq, 1);
            if (endOfFrame)
            {
                packetOfFrame = 0;
                ts = RtpUtils.applyTimestampDelta(ts, 3000);
                picId++;
                picId %= DePacketizer.VP8PayloadDescriptor.EXTENDED_PICTURE_ID_MASK;
                tidCycle++;
                keyframe = false;

                if (tid == 0) {
                    tl0picidx++;
                    tl0picidx %= DePacketizer.VP8PayloadDescriptor.TL0PICIDX_MASK;
                }
            }
            else
            {
                packetOfFrame++;
            }

            return vp8Packet;
        }

        private void requestKeyframe()
        {
            keyframe = true;
            tidCycle = 0;
        }
    }
}
