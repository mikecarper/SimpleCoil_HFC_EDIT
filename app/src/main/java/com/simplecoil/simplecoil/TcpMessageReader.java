package com.simplecoil.simplecoil;

import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.util.Arrays;

/** Incrementally reads the length-prefixed modified UTF format used by writeUTF. */
final class TcpMessageReader {
    private byte[] frame = new byte[2];
    private int received;

    /** Returns null until a whole message is available, without waiting for more bytes. */
    String poll(InputStream input) throws IOException {
        while (true) {
            int available = input.available();
            if (available <= 0)
                return null;
            // available() may describe only part of the header or payload. In
            // particular, readUTF() here would wait for the rest of that frame.
            int count = input.read(frame, received, Math.min(available, frame.length - received));
            if (count < 0)
                throw new EOFException("TCP stream ended during a message");
            if (count == 0)
                return null;
            received += count;
            if (received < frame.length)
                continue;
            if (frame.length == 2) {
                int payloadLength = ((frame[0] & 0xff) << 8) | (frame[1] & 0xff);
                if (payloadLength > 0) {
                    // The unsigned two-byte length bounds each allocation to 64 KiB.
                    frame = Arrays.copyOf(frame, payloadLength + 2);
                    continue;
                }
            }
            byte[] complete = frame;
            frame = new byte[2];
            received = 0;
            return new DataInputStream(new ByteArrayInputStream(complete)).readUTF();
        }
    }
}
