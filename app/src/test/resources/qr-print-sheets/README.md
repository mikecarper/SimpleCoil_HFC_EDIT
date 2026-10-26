# Captured printed QR sheets

These fixtures are complete, unmodified printed page renders, not newly
generated QR symbols. They retain the detector failure seen in the actual
published print sheets. GameQrDecoderFactoryTest reads their stored luminance
through the same PlanarYUVLuminanceSource and decoder factory as the camera.

All pages are 612 by 792 pixels, rendered by Poppler at 72 DPI. RGB renders were
converted to 8-bit luminance using the ZXing RGBLuminanceSource formula
`(red + 2 * green + blue) / 4` with integer division. No cropping, thresholding,
resizing, or QR reconstruction was performed.

Each `.y.gz` fixture is a gzip-compressed stream containing two big-endian 32-bit
integers (width, then height), followed by exactly width * height unsigned 8-bit
luminance samples in row-major order. The test reads those samples directly
using DataInputStream, without Android graphics or desktop image libraries.
Conversion from the intermediate grayscale PNGs preserved every stored sample.

## Current sheets

`latest-01.y.gz` through `latest-14.y.gz` are pages 1 through 14 of
`output/pdf/LAZER-GUN-RUN-1.52-QR-Codes-US-Letter.pdf`, captured on 2026-10-25.
Source PDF SHA-256:

`21028a7d8d7dd5c905519c65650637000f4700b6c4c1c3766204ada351239794`

| Pages | Expected payloads |
| --- | --- |
| 01-04 | SIMPLECOIL:RESPAWN:1 through SIMPLECOIL:RESPAWN:4 |
| 05-06 | SIMPLECOIL:FLAG:1 through SIMPLECOIL:FLAG:2 |
| 07-14 | SIMPLECOIL:POWERUP:1 through SIMPLECOIL:POWERUP:8 |

The previous JourneyApps DefaultDecoderFactory, restricted to QR_CODE with
ZXing core 3.4.1, fails on pages 01, 05, and 10 while decoding the other pages.
The production regression expects every page to decode successfully; it does
not require the old decoder to keep failing.

## Older sheets

`legacy-team-N.y.gz` is the first page of the existing
`output/pdf/team-N-respawn-checkpoint-letter.pdf`, for N from 1 through 4. These
older standalone team sheets are compatibility references; they are not claimed
to be the unavailable exact version 1.21 artifact. Their expected payloads are
SIMPLECOIL:RESPAWN:1 through SIMPLECOIL:RESPAWN:4.

| Team | Source PDF SHA-256 |
| --- | --- |
| 1 | aa46a488a3984c3d76eeaf413766899733c1afef860ff9b57e4516030042aa01 |
| 2 | ffd2f6519235792196149c31b907f83064944a63731172ea4daaa018cab58fc1 |
| 3 | feefcd3f0b5a5541b8f61af8a99826fba8067b04b2d904205f8cf9822872e8d7 |
| 4 | e2c2e70f8eebc1248a5b4630fc7c2002e38c850a33e6cff32fb99f02836cd5ac |

The ambiguity regression stacks the captured current TEAM 1 and FLAG 1 pages
without resampling them. The fallback detects both different payloads and must
wait for one clear target. Blank frames and a supplied camera result-point
callback are also tested without adding generated fixture files.
