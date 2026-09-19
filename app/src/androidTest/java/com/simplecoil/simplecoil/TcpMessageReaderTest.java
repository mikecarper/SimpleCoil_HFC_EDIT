package com.simplecoil.simplecoil;

import androidx.test.ext.junit.runners.AndroidJUnit4;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UTFDataFormatException;
import java.util.Arrays;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.fail;

@RunWith(AndroidJUnit4.class)
public class TcpMessageReaderTest {
    @Test
    public void fragmentedHeaderAndPayloadNeverReadPastAvailableBytes() throws Exception {
        String text = "Player \u0000 \u00e9 \u20ac \ud83c\udfaf";
        FragmentedInput input = new FragmentedInput(encode(text));
        TcpMessageReader reader = new TcpMessageReader();
        assertNull(reader.poll(input));
        for (int count = 1; count < input.bytes.length; count++) {
            input.released = count;
            assertNull(reader.poll(input));
            assertNull(reader.poll(input));
        }
        input.released = input.bytes.length;
        assertEquals(text, reader.poll(input));
        assertNull(reader.poll(input));
    }

    @Test
    public void completeFramesAreReturnedSeparatelyAndInOrder() throws Exception {
        ByteArrayInputStream input = new ByteArrayInputStream(encode("first", "second", "third"));
        TcpMessageReader reader = new TcpMessageReader();
        assertEquals("first", reader.poll(input));
        assertEquals("second", reader.poll(input));
        assertEquals("third", reader.poll(input));
        assertNull(reader.poll(input));
    }

    @Test
    public void emptyMessageDoesNotConsumeTheFollowingFrame() throws Exception {
        ByteArrayInputStream input = new ByteArrayInputStream(encode("", "next"));
        TcpMessageReader reader = new TcpMessageReader();
        assertEquals("", reader.poll(input));
        assertEquals("next", reader.poll(input));
        assertNull(reader.poll(input));
    }

    @Test
    public void maximumUnsignedLengthIsAccepted() throws Exception {
        char[] chars = new char[65535];
        Arrays.fill(chars, 'x');
        String text = new String(chars);
        TcpMessageReader reader = new TcpMessageReader();
        assertEquals(text, reader.poll(new ByteArrayInputStream(encode(text))));
    }

    @Test
    public void shortReadsAreAccumulatedWithoutLosingBytes() throws Exception {
        FragmentedInput input = new FragmentedInput(encode("short reads"));
        input.released = input.bytes.length;
        input.readLimit = 1;
        assertEquals("short reads", new TcpMessageReader().poll(input));
    }

    @Test
    public void zeroLengthReadDoesNotSpin() throws Exception {
        FragmentedInput input = new FragmentedInput(encode("later"));
        input.released = input.bytes.length;
        input.readLimit = 0;
        TcpMessageReader reader = new TcpMessageReader();
        assertNull(reader.poll(input));
        input.readLimit = Integer.MAX_VALUE;
        assertEquals("later", reader.poll(input));
    }

    @Test
    public void malformedModifiedUtfIsRejected() throws Exception {
        TcpMessageReader reader = new TcpMessageReader();
        try {
            reader.poll(new ByteArrayInputStream(new byte[]{0, 1, (byte) 0xc0}));
            fail("Incomplete modified UTF character was accepted");
        } catch (UTFDataFormatException expected) {
            // The caller can drop just this connection, without corrupting others.
        }
    }

    private static byte[] encode(String... messages) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        DataOutputStream output = new DataOutputStream(bytes);
        for (String message : messages)
            output.writeUTF(message);
        return bytes.toByteArray();
    }

    private static final class FragmentedInput extends InputStream {
        final byte[] bytes;
        int released;
        int readLimit = Integer.MAX_VALUE;
        private int position;

        FragmentedInput(byte[] bytes) { this.bytes = bytes; }
        @Override public int available() { return released - position; }
        @Override public int read() {
            if (available() <= 0) throw new AssertionError("Blocking read attempted");
            return bytes[position++] & 0xff;
        }
        @Override public int read(byte[] buffer, int offset, int length) {
            if (length > available()) throw new AssertionError("Blocking read attempted");
            int count = Math.min(length, readLimit);
            System.arraycopy(bytes, position, buffer, offset, count);
            position += count;
            return count;
        }
    }
}
