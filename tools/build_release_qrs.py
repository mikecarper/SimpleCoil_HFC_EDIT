#!/usr/bin/env python3
"""Build labeled, vector QR posters in one US Letter landscape PDF.

Dependencies: reportlab and qrcode. Install them in a virtual environment.
Usage: python tools/build_release_qrs.py --version 1.52 \
    --gun 'Gun 1=AA:BB:CC:DD:EE:FF'
"""

import argparse
import re
from pathlib import Path
from urllib.parse import quote

import qrcode
from reportlab.lib import colors
from reportlab.lib.pagesizes import landscape, letter
from reportlab.pdfbase.pdfmetrics import stringWidth
from reportlab.pdfgen.canvas import Canvas


def parse_gun(value):
    label, separator, address = value.partition("=")
    label, address = label.strip(), address.strip().upper()
    if not separator or not label or len(label) > 40 or any(ord(char) < 32 for char in label):
        raise ValueError("Use a gun label of 1-40 characters followed by =AA:BB:CC:DD:EE:FF")
    if not re.fullmatch(r"(?:[0-9A-F]{2}:){5}[0-9A-F]{2}", address):
        raise ValueError("Gun QR content must be a plain Bluetooth MAC address")
    return label, address


def checked_guns(guns):
    result, labels, addresses = [], set(), set()
    for label, address in guns:
        label, address = parse_gun(f"{label}={address}")
        if label.casefold() in labels or address in addresses:
            raise ValueError("Each gun label and Bluetooth MAC address must be unique")
        labels.add(label.casefold())
        addresses.add(address)
        result.append((label, address))
    return result


def pages(version, guns=(), apk_name=None):
    if not re.fullmatch(r"[0-9]+(?:\.[0-9]+)+", version):
        raise ValueError("Use a numeric release version, such as 1.52")
    guns = checked_guns(guns)
    apk_name = apk_name or f"LAZER-GUN-RUN-{version}.apk"
    if (not apk_name.lower().endswith(".apk") or any(char in apk_name for char in "/\\")
            or any(ord(char) < 32 for char in apk_name)):
        raise ValueError("The APK asset name must be a filename ending in .apk")
    download = (
        "https://github.com/mikecarper/SimpleCoil_HFC_EDIT/"
        f"releases/download/{version}/{quote(apk_name, safe='')}"
    )
    result = [{
        "title": f"LAZER GUN RUN {version}",
        "subtitle": "APP DOWNLOAD | NOT AN IN-GAME CHECKPOINT",
        "payload": download,
        "heading": "INSTALL THE APP",
        "paragraphs": [
            "Scan with a normal QR reader to open the APK download. "
            "The in-game scanner does not handle download codes.",
            "Install the same release on every game phone. "
            "Use the matching laptop host from this release, too.",
            "Android 5.0 or newer is required. Allow installation from "
            "this source if Android asks.",
        ],
        "detail": "Game posters: teams on pages 2-5; CTF flags on pages 6-7; "
                  "power-up checkpoints on pages 8-15. "
                  + ("Gun pairing posters follow on pages 16 onward." if guns else ""),
    }]
    for team in range(1, 5):
        result.append({
            "title": f"TEAM {team}",
            "subtitle": "TEAM CHECK-IN / JOIN / RESPAWN / HOME BASE",
            "payload": f"SIMPLECOIL:RESPAWN:{team}",
            "heading": f"TEAM {team} CHECKPOINT",
            "paragraphs": [
                "Use this code to choose or join this team. "
                "In Balanced Random QR mode, scan it only when this "
                "is your assigned team.",
                "After death, scan your own team's code to respawn "
                "early when QR respawns are enabled.",
                "In Capture the Flag, this is your home base. "
                "Return here with the enemy flag to score. "
                "CTF uses only Teams 1 and 2.",
            ],
            "detail": "Join the game's Wi-Fi network first. This code does "
                      "not contain a host address or Wi-Fi password.",
        })
    for team in range(1, 3):
        result.append({
            "title": f"TEAM {team} FLAG",
            "subtitle": "CAPTURE THE FLAG | ENEMY FLAG PICKUP",
            "payload": f"SIMPLECOIL:FLAG:{team}",
            "heading": "CAPTURE THIS FLAG",
            "paragraphs": [
                f"Place this at Team {team}'s flag location.",
                "Scan the opposing team's flag during Capture the Flag, "
                "then return to your own team's home-base QR to score.",
                "Your home base is the matching TEAM checkpoint, "
                "not this flag code. Capture the Flag uses two teams.",
            ],
            "detail": "This is not a respawn or power-up code.",
        })
    for checkpoint in range(1, 9):
        result.append({
            "title": f"POWER-UP CHECKPOINT {checkpoint}",
            "subtitle": "DISTINCT SCAN | RANDOM REWARD",
            "payload": f"SIMPLECOIL:POWERUP:{checkpoint}",
            "heading": "COLLECT DIFFERENT CODES",
            "paragraphs": [
                "Scan different power-up checkpoints without dying "
                "to unlock the spinning mystery-box reward.",
                "The host sets 2-8 scans per reward. Teams far behind "
                "need fewer scans, down to a minimum of two, and have "
                "better odds of stronger rewards.",
                "Each checkpoint counts only once per life. Spent "
                "codes cannot be reused. Death resets all progress.",
                "This number identifies a checkpoint, NOT a specific "
                "power. All eight codes can award any eligible reward.",
            ],
            "detail": "Optional: the host must turn power-ups on. "
                      "Use Scan power-up during a running game.",
        })
    for label, address in guns:
        result.append({
            "title": f"GUN PAIRING: {label.upper()}",
            "subtitle": "CONNECT THIS BLASTER | NOT A PLAYER OR TEAM CODE",
            "payload": address,
            "heading": label,
            "paragraphs": [
                "Turn on this blaster. In the app, open Connect gun, "
                "then choose QR weapon and scan this code.",
                "Gun pairing uses an external ZXing-compatible Barcode Scanner app. "
                "Install that scanner first if the phone asks for it.",
                "This code selects only the blaster's Bluetooth address. "
                "It does not assign a player name, team, saved profile, or phone.",
            ],
            "detail": "Keep this label with the matching physical blaster. "
                      "The team, respawn, flag, and power-up scanners do not accept gun codes.",
        })
    return result


def wrap(text, font, size, width):
    lines = []
    line = ""
    for word in text.split():
        candidate = word if not line else line + " " + word
        if line and stringWidth(candidate, font, size) > width:
            lines.append(line)
            line = word
        else:
            line = candidate
        # Long URL segments need character wrapping, too.
        while stringWidth(line, font, size) > width:
            split = len(line) - 1
            while stringWidth(line[:split], font, size) > width:
                split -= 1
            lines.append(line[:split])
            line = line[split:]
    if line:
        lines.append(line)
    return lines


def draw_text(canvas, text, x, y, width, size=12, leading=17,
              font="Helvetica", color=colors.black):
    canvas.setFillColor(color)
    canvas.setFont(font, size)
    for line in wrap(text, font, size, width):
        canvas.drawString(x, y, line)
        y -= leading
    return y


def draw_qr(canvas, payload, x, y, side):
    qr = qrcode.QRCode(error_correction=qrcode.constants.ERROR_CORRECT_M,
                       box_size=1, border=4)
    qr.add_data(payload)
    qr.make(fit=True)
    matrix = qr.get_matrix()
    module = side / len(matrix)
    canvas.setFillColor(colors.white)
    canvas.rect(x, y, side, side, stroke=0, fill=1)
    canvas.setFillColor(colors.black)
    for row, cells in enumerate(matrix):
        for column, dark in enumerate(cells):
            if dark:
                canvas.rect(x + column * module,
                            y + (len(matrix) - row - 1) * module,
                            module, module, stroke=0, fill=1)


def build(version, output, guns=(), apk_name=None):
    cards = pages(version, guns=guns, apk_name=apk_name)
    output.parent.mkdir(parents=True, exist_ok=True)
    canvas = Canvas(str(output), pagesize=landscape(letter), pageCompression=1)
    canvas.setTitle(f"LAZER GUN RUN {version} - All QR Codes - US Letter")
    canvas.setAuthor("LAZER GUN RUN")
    canvas.setSubject("App download, Teams 1-4, CTF flags, power-up checkpoints, and optional gun pairing")
    for number, card in enumerate(cards, start=1):
        canvas.bookmarkPage(f"qr-{number}")
        canvas.addOutlineEntry(card["title"], f"qr-{number}", level=0)
        canvas.setFillColor(colors.HexColor("#15364A"))
        title_size = min(30, 30 * 720 / stringWidth(card["title"], "Helvetica-Bold", 30))
        canvas.setFont("Helvetica-Bold", title_size)
        canvas.drawString(36, 564, card["title"])
        canvas.setFont("Helvetica-Bold", 12)
        canvas.drawString(36, 539, card["subtitle"])
        draw_qr(canvas, card["payload"], 36, 109, 396)
        y = draw_text(canvas, card["heading"], 460, 493, 296,
                      size=15, leading=19, font="Helvetica-Bold") - 13
        for paragraph in card["paragraphs"]:
            y = draw_text(canvas, paragraph, 460, y, 296) - 11
        y = draw_text(canvas, card["detail"], 460, y, 296,
                      size=10.5, leading=14,
                      color=colors.HexColor("#34444D"))
        if y < 100:
            raise ValueError(f"Instructions exceed page bounds: {card['title']}")
        draw_text(canvas, "EXACT QR CONTENT", 36, 95, 720,
                  size=9, leading=11, font="Helvetica-Bold")
        y = draw_text(canvas, card["payload"], 36, 80, 720,
                      size=9.5, leading=12, font="Courier")
        if y < 55:
            raise ValueError(f"Payload exceeds page bounds: {card['title']}")
        if number == 1:
            canvas.linkURL(card["payload"], (36, 109, 432, 505), relative=0)
        canvas.setStrokeColor(colors.HexColor("#CAD3D9"))
        canvas.line(36, 51, 756, 51)
        canvas.setFillColor(colors.HexColor("#34444D"))
        canvas.setFont("Helvetica", 9)
        canvas.drawString(36, 33, "US Letter landscape (11 x 8.5 in) | "
                          "Print at 100% / Actual Size | Keep the QR margin clear")
        canvas.drawRightString(756, 33, f"LAZER GUN RUN {version} | {number} / {len(cards)}")
        canvas.showPage()
    canvas.save()
    return len(cards)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--version", required=True)
    parser.add_argument("--output", type=Path)
    parser.add_argument("--apk-name", help="APK filename uploaded to the matching GitHub release")
    parser.add_argument("--gun", action="append", type=parse_gun, default=[], metavar="LABEL=MAC",
                        help="Append a labeled gun pairing poster; repeat for each blaster")
    args = parser.parse_args()
    output = args.output or Path("output/pdf") / (
        f"LAZER-GUN-RUN-{args.version}-QR-Codes-US-Letter.pdf")
    try:
        count = build(args.version, output, guns=args.gun, apk_name=args.apk_name)
    except ValueError as error:
        parser.error(str(error))
    print(f"Created {output} ({count} pages, US Letter landscape)")


if __name__ == "__main__":
    main()
