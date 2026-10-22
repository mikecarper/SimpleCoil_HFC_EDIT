package com.simplecoil.simplecoil;

import com.simplecoil.protocol.GameStartCountdown;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.util.Arrays;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public class CountdownClosingAudioWaveTest {
    @Rule public final TemporaryFolder temporary = new TemporaryFolder();

    @Test public void unsignedEightBitPcmKeepsAllWordsAndTwentyMillisecondsOfSilence() throws Exception {
        byte[] input = wave(8, 1, 8_000, 8_000);
        input[44 + 200] = (byte) 180;
        input[44 + 999] = (byte) 132;
        // Unsigned 8-bit silence is 128; one-step noise must not extend speech.
        input[44 + 7_000] = (byte) 129;
        CountdownClosingAudio.Wav trimmed = CountdownClosingAudio.trimPcmWave(input);

        assertEquals(1_160, u32(trimmed.bytes, 40));
        assertEquals(145, trimmed.durationMillis);
        assertArrayEquals(Arrays.copyOfRange(input, 44, 44 + 1_160),
                Arrays.copyOfRange(trimmed.bytes, 44, trimmed.bytes.length));
        assertEquals(132, trimmed.bytes[44 + 999] & 255);
        assertEquals(128, trimmed.bytes[trimmed.bytes.length - 1] & 255);
    }

    @Test public void signedSixteenBitPcmKeepsPositiveAndNegativeWordsPlusShortTail() throws Exception {
        byte[] input = wave(16, 1, 8_000, 8_000);
        put16(input, 44 + 200 * 2, 2_000);
        put16(input, 44 + 999 * 2, -32_768);
        put16(input, 44 + 7_000 * 2, 8);
        CountdownClosingAudio.Wav trimmed = CountdownClosingAudio.trimPcmWave(input);

        assertEquals(1_160 * 2, u32(trimmed.bytes, 40));
        assertEquals(145, trimmed.durationMillis);
        assertArrayEquals(Arrays.copyOfRange(input, 44, 44 + 1_160 * 2),
                Arrays.copyOfRange(trimmed.bytes, 44, trimmed.bytes.length));
        assertEquals(0x8000, u16(trimmed.bytes, 44 + 999 * 2));
    }

    @Test public void speechInTheSecondStereoChannelRetainsWholeFrames() throws Exception {
        byte[] input = wave(16, 2, 1_000, 8_000);
        put16(input, 44 + 200 * 4 + 2, -1_000);
        CountdownClosingAudio.Wav trimmed = CountdownClosingAudio.trimPcmWave(input);

        assertEquals(361 * 4, u32(trimmed.bytes, 40));
        assertEquals(46, trimmed.durationMillis);
        assertEquals(0, u32(trimmed.bytes, 40) % u16(trimmed.bytes, 32));
        assertArrayEquals(Arrays.copyOfRange(input, 44, 44 + 361 * 4),
                Arrays.copyOfRange(trimmed.bytes, 44, trimmed.bytes.length));
    }

    @Test public void floatPcmTrimsValidSpeechButRejectsEarlierNonFiniteSamples() throws Exception {
        byte[] input = wave(32, 1, 8_000, 8_000);
        put16(input, 20, 3);
        put32(input, 44 + 200 * 4, Float.floatToRawIntBits(0.125f));
        put32(input, 44 + 999 * 4, Float.floatToRawIntBits(-0.25f));
        CountdownClosingAudio.Wav trimmed = CountdownClosingAudio.trimPcmWave(input);
        assertEquals(1_160 * 4, u32(trimmed.bytes, 40));
        assertEquals(145, trimmed.durationMillis);
        assertArrayEquals(Arrays.copyOfRange(input, 44, 44 + 1_160 * 4),
                Arrays.copyOfRange(trimmed.bytes, 44, trimmed.bytes.length));

        for (float invalid : new float[]{Float.NaN, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY}) {
            byte[] malformed = input.clone();
            // This lies before the final audible frame and must still be validated.
            put32(malformed, 44 + 100 * 4, Float.floatToRawIntBits(invalid));
            malformed(malformed);
        }
    }

    @Test public void fractionalTailAndDurationRoundUpWithoutDroppingSpeech() throws Exception {
        byte[] input = wave(8, 1, 2_000, 11_025);
        input[44 + 999] = (byte) 160;
        CountdownClosingAudio.Wav trimmed = CountdownClosingAudio.trimPcmWave(input);

        assertEquals(1_221, u32(trimmed.bytes, 40));
        assertEquals(111, trimmed.durationMillis);
    }

    @Test public void shortFileDoesNotGrowAndOddDataRetainsItsRiffPadding() throws Exception {
        byte[] input = wave(8, 1, 201, 8_000);
        input[44 + 200] = (byte) 170;
        CountdownClosingAudio.Wav trimmed = CountdownClosingAudio.trimPcmWave(input);

        assertEquals(201, u32(trimmed.bytes, 40));
        assertEquals(246, trimmed.bytes.length);
        assertEquals(trimmed.bytes.length - 8, u32(trimmed.bytes, 4));
        assertEquals(26, trimmed.durationMillis);
        assertArrayEquals(input, trimmed.bytes);
    }

    @Test public void speechLongerThanTheWindowIsMeasuredRatherThanCutOff() throws Exception {
        byte[] input = wave(16, 1, 48_000, 8_000);
        put16(input, 44 + 39_999 * 2, 1_000);
        CountdownClosingAudio.Wav trimmed = CountdownClosingAudio.trimPcmWave(input);

        assertEquals(5_020, trimmed.durationMillis);
        assertTrue(trimmed.durationMillis > GameStartCountdown.ENDING_VOICE_MS);
        assertEquals(1_000, u16(trimmed.bytes, 44 + 39_999 * 2));
    }

    @Test public void unknownOddSizedChunksAreSkippedAndOutputIsNormalized() throws Exception {
        byte[] input = wave(8, 1, 201, 8_000);
        input[44 + 200] = (byte) 170;
        byte[] metadata = new byte[input.length + 10];
        System.arraycopy(input, 0, metadata, 0, 12);
        tag(metadata, 12, "JUNK");
        put32(metadata, 16, 1);
        metadata[20] = 42;
        System.arraycopy(input, 12, metadata, 22, input.length - 12);
        put32(metadata, 4, metadata.length - 8);

        assertArrayEquals(input, CountdownClosingAudio.trimPcmWave(metadata).bytes);
    }

    @Test public void malformedHeaderAndRiffLengthsAreRejected() {
        malformed(null);
        malformed(new byte[43]);
        byte[] valid = audibleWave();
        byte[] input = valid.clone();
        input[0] = 'X';
        malformed(input);
        input = valid.clone();
        input[8] = 'X';
        malformed(input);
        input = valid.clone();
        put32(input, 4, input.length - 9);
        malformed(input);
        input = valid.clone();
        put32(input, 4, 0xffff_ffffL);
        malformed(input);
    }

    @Test public void chunkLengthsCannotReadPastTheFileOrOverflow() {
        byte[] input = audibleWave();
        put32(input, 16, 0xffff_ffffL);
        malformed(input);
        input = audibleWave();
        put32(input, 40, input.length);
        malformed(input);
        input = Arrays.copyOf(audibleWave(), audibleWave().length + 4);
        tag(input, input.length - 4, "JUNK");
        put32(input, 4, input.length - 8);
        malformed(input);
        input = Arrays.copyOf(wave(8, 1, 201, 8_000), 44 + 201);
        input[44] = (byte) 170;
        put32(input, 4, input.length - 8);
        malformed(input);
    }

    @Test public void missingDuplicateAndInconsistentPcmChunksAreRejected() {
        byte[] valid = audibleWave();
        byte[] input = valid.clone();
        tag(input, 12, "JUNK");
        malformed(input);
        input = valid.clone();
        tag(input, 36, "JUNK");
        malformed(input);
        input = new byte[valid.length + 24];
        System.arraycopy(valid, 0, input, 0, 36);
        System.arraycopy(valid, 12, input, 36, 24);
        System.arraycopy(valid, 36, input, 60, valid.length - 36);
        put32(input, 4, input.length - 8);
        malformed(input);
        for (int offset : new int[]{20, 22, 32, 34}) {
            input = valid.clone();
            put16(input, offset, 0);
            malformed(input);
        }
        input = valid.clone();
        put32(input, 24, 7_999);
        malformed(input);
        input = valid.clone();
        put32(input, 28, 1);
        malformed(input);
        input = wave(16, 2, 1, 8_000);
        put16(input, 44, 1_000);
        put32(input, 40, 3);
        malformed(input);
    }

    @Test public void entirelySilentPcmIsRejectedInsteadOfPreparingAnEmptyClip() {
        malformed(wave(8, 1, 1_000, 8_000));
        malformed(wave(16, 1, 1_000, 8_000));
    }

    @Test public void fileReaderUsesTheSameTrimAndRejectsOversizedFilesBeforeReading() throws Exception {
        byte[] input = audibleWave();
        File valid = temporary.newFile("closing.wav");
        try (FileOutputStream output = new FileOutputStream(valid)) { output.write(input); }
        CountdownClosingAudio.Wav expected = CountdownClosingAudio.trimPcmWave(input);
        CountdownClosingAudio.Wav actual = CountdownClosingAudio.readAndTrim(valid);
        assertArrayEquals(expected.bytes, actual.bytes);
        assertEquals(expected.durationMillis, actual.durationMillis);

        File oversized = temporary.newFile("oversized.wav");
        try (RandomAccessFile output = new RandomAccessFile(oversized, "rw")) {
            output.setLength(8 * 1024 * 1024L + 1);
        }
        try {
            CountdownClosingAudio.readAndTrim(oversized);
            fail("Oversized WAV was accepted");
        } catch (IOException expectedFailure) { }
    }

    private static byte[] audibleWave() {
        byte[] input = wave(8, 1, 1_000, 8_000);
        input[44 + 200] = (byte) 170;
        return input;
    }

    private static byte[] wave(int bits, int channels, int frames, int sampleRate) {
        int block = channels * bits / 8;
        int dataSize = frames * block;
        byte[] result = new byte[44 + dataSize + (dataSize & 1)];
        tag(result, 0, "RIFF");
        put32(result, 4, result.length - 8);
        tag(result, 8, "WAVE");
        tag(result, 12, "fmt ");
        put32(result, 16, 16);
        put16(result, 20, 1);
        put16(result, 22, channels);
        put32(result, 24, sampleRate);
        put32(result, 28, sampleRate * block);
        put16(result, 32, block);
        put16(result, 34, bits);
        tag(result, 36, "data");
        put32(result, 40, dataSize);
        if (bits == 8) Arrays.fill(result, 44, 44 + dataSize, (byte) 128);
        return result;
    }

    private static void malformed(byte[] input) {
        try {
            CountdownClosingAudio.trimPcmWave(input);
            fail("Malformed or silent WAV was accepted");
        } catch (IOException expected) { }
    }

    private static void tag(byte[] bytes, int at, String value) {
        for (int n = 0; n < 4; n++) bytes[at + n] = (byte) value.charAt(n);
    }

    private static int u16(byte[] bytes, int at) {
        return (bytes[at] & 255) | (bytes[at + 1] & 255) << 8;
    }

    private static long u32(byte[] bytes, int at) {
        return (bytes[at] & 255L) | (bytes[at + 1] & 255L) << 8
                | (bytes[at + 2] & 255L) << 16 | (bytes[at + 3] & 255L) << 24;
    }

    private static void put16(byte[] bytes, int at, int value) {
        bytes[at] = (byte) value;
        bytes[at + 1] = (byte) (value >>> 8);
    }

    private static void put32(byte[] bytes, int at, long value) {
        for (int n = 0; n < 4; n++) bytes[at + n] = (byte) (value >>> (n * 8));
    }
}
