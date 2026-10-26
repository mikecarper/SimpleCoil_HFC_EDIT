// SPDX-License-Identifier: Apache-2.0
package com.simplecoil.simplecoil;

import com.google.zxing.BarcodeFormat;
import com.google.zxing.BinaryBitmap;
import com.google.zxing.DecodeHintType;
import com.google.zxing.MultiFormatReader;
import com.google.zxing.Result;
import com.google.zxing.multi.qrcode.QRCodeMultiReader;
import com.journeyapps.barcodescanner.Decoder;
import com.journeyapps.barcodescanner.DecoderFactory;

import java.util.Collections;
import java.util.EnumMap;
import java.util.Map;

/** Preserve ordinary QR scanning and recover valid codes with misleading data patterns. */
public final class GameQrDecoderFactory implements DecoderFactory {
    @Override public Decoder createDecoder(Map<DecodeHintType, ?> baseHints) {
        Map<DecodeHintType, Object> hints = new EnumMap<>(DecodeHintType.class);
        if (baseHints != null) hints.putAll(baseHints);
        hints.put(DecodeHintType.POSSIBLE_FORMATS, Collections.singletonList(BarcodeFormat.QR_CODE));
        MultiFormatReader reader = new MultiFormatReader();
        reader.setHints(hints);
        return new Decoder(reader) {
            private final QRCodeMultiReader fallback = new QRCodeMultiReader();

            @Override protected Result decode(BinaryBitmap bitmap) {
                Result ordinary = super.decode(bitmap);
                if (ordinary != null) return ordinary;
                try {
                    // The single-code finder can stop on a finder-like pattern
                    // inside the data. The multi-code detector checks additional
                    // candidate triangles without changing the printed code.
                    Result[] results = fallback.decodeMultiple(bitmap, hints);
                    // A retry must not assign an arbitrary team when several
                    // posters are visible. Keep waiting for one clear target.
                    return results.length == 1 ? results[0] : null;
                } catch (Exception missedFrame) {
                    // Match JourneyApps Decoder: a failed frame is retried by
                    // the camera loop, rather than terminating its worker.
                    return null;
                } finally {
                    fallback.reset();
                }
            }
        };
    }
}
