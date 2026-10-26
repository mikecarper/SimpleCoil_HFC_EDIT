// SPDX-License-Identifier: Apache-2.0
package com.simplecoil.simplecoil;

import com.google.zxing.BarcodeFormat;
import com.google.zxing.BinaryBitmap;
import com.google.zxing.DecodeHintType;
import com.google.zxing.PlanarYUVLuminanceSource;
import com.google.zxing.Result;
import com.google.zxing.ResultPoint;
import com.google.zxing.ResultPointCallback;
import com.google.zxing.common.HybridBinarizer;
import com.google.zxing.multi.qrcode.QRCodeMultiReader;
import com.journeyapps.barcodescanner.Decoder;

import org.junit.Test;

import java.io.DataInputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.zip.GZIPInputStream;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;

public class GameQrDecoderFactoryTest {
    @Test public void decodesEveryCurrentPrintedSheetThroughTheCameraDecoder() throws Exception {
        Decoder decoder = newDecoder();
        for (int page = 1; page <= 14; page++) {
            String payload = page <= 4 ? "SIMPLECOIL:RESPAWN:" + page
                    : page <= 6 ? "SIMPLECOIL:FLAG:" + (page - 4)
                    : "SIMPLECOIL:POWERUP:" + (page - 6);
            assertDecodes(decoder, load(String.format(java.util.Locale.US,
                    "latest-%02d.y.gz", page)), payload);
        }
    }

    @Test public void stillDecodesTheFourOlderPrintedTeamSheets() throws Exception {
        Decoder decoder = newDecoder();
        for (int team = 1; team <= 4; team++) {
            assertDecodes(decoder, load("legacy-team-" + team + ".y.gz"),
                    "SIMPLECOIL:RESPAWN:" + team);
        }
    }

    @Test public void blankFrameDoesNotPreventTheNextPrintedCodeFromDecoding() throws Exception {
        Decoder decoder = newDecoder();
        assertNull(decoder.decode(blank(612, 792).source()));
        assertDecodes(decoder, load("latest-01.y.gz"), "SIMPLECOIL:RESPAWN:1");
        assertNull(decoder.decode(blank(612, 792).source()));
        assertDecodes(decoder, load("latest-05.y.gz"), "SIMPLECOIL:FLAG:1");
    }

    @Test public void ambiguousFallbackWaitsForOneTargetAndThenRecovers() throws Exception {
        Frame team = load("latest-01.y.gz");
        Frame flag = load("latest-05.y.gz");
        Frame together = stack(team, flag);
        // These captured pages produce two distinct fallback results. The camera
        // must wait for a single target instead of choosing a team or flag.
        Result[] candidates = new QRCodeMultiReader().decodeMultiple(
                new BinaryBitmap(new HybridBinarizer(together.source())));
        assertEquals(2, candidates.length);
        List<String> payloads = new ArrayList<>();
        for (Result candidate : candidates) payloads.add(candidate.getText());
        Collections.sort(payloads);
        assertEquals(Arrays.asList("SIMPLECOIL:FLAG:1", "SIMPLECOIL:RESPAWN:1"), payloads);

        Decoder decoder = newDecoder();
        assertNull(decoder.decode(together.source()));
        assertDecodes(decoder, team, "SIMPLECOIL:RESPAWN:1");
        assertNull(decoder.decode(together.source()));
        assertDecodes(decoder, flag, "SIMPLECOIL:FLAG:1");
    }

    @Test public void fallbackPreservesTheCameraResultPointCallback() throws Exception {
        List<ResultPoint> points = new ArrayList<>();
        Map<DecodeHintType, Object> hints = new EnumMap<>(DecodeHintType.class);
        hints.put(DecodeHintType.NEED_RESULT_POINT_CALLBACK,
                (ResultPointCallback) points::add);
        Decoder decoder = new GameQrDecoderFactory().createDecoder(hints);
        assertDecodes(decoder, load("latest-01.y.gz"), "SIMPLECOIL:RESPAWN:1");
        assertFalse("Camera result-point callback received no points", points.isEmpty());
    }

    private static Decoder newDecoder() {
        return new GameQrDecoderFactory().createDecoder(Collections.emptyMap());
    }

    private static void assertDecodes(Decoder decoder, Frame frame, String payload) {
        Result result = decoder.decode(frame.source());
        assertNotNull("Could not decode " + payload, result);
        assertEquals(payload, result.getText());
        assertEquals(BarcodeFormat.QR_CODE, result.getBarcodeFormat());
    }

    private static Frame load(String name) throws Exception {
        try (InputStream input = GameQrDecoderFactoryTest.class.getResourceAsStream(
                "/qr-print-sheets/" + name)) {
            assertNotNull("Missing captured print sheet " + name, input);
            try (DataInputStream data = new DataInputStream(new GZIPInputStream(input))) {
                int width = data.readInt();
                int height = data.readInt();
                assertEquals(612, width);
                assertEquals(792, height);
                byte[] luminance = new byte[width * height];
                data.readFully(luminance);
                assertEquals("Trailing bytes in " + name, -1, data.read());
                return new Frame(width, height, luminance);
            }
        }
    }

    private static Frame blank(int width, int height) {
        byte[] luminance = new byte[width * height];
        Arrays.fill(luminance, (byte) 255);
        return new Frame(width, height, luminance);
    }

    private static Frame stack(Frame top, Frame bottom) {
        assertEquals(top.width, bottom.width);
        byte[] luminance = Arrays.copyOf(top.luminance,
                top.luminance.length + bottom.luminance.length);
        System.arraycopy(bottom.luminance, 0, luminance,
                top.luminance.length, bottom.luminance.length);
        return new Frame(top.width, top.height + bottom.height, luminance);
    }

    private static final class Frame {
        final int width;
        final int height;
        final byte[] luminance;

        Frame(int width, int height, byte[] luminance) {
            this.width = width;
            this.height = height;
            this.luminance = luminance;
        }

        PlanarYUVLuminanceSource source() {
            return new PlanarYUVLuminanceSource(luminance, width, height,
                    0, 0, width, height, false);
        }
    }
}
