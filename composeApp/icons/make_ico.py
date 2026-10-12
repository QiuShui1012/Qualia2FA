"""Assemble a Windows .ico from the rendered PNG sizes of the Qualia2FA mark.

The layout is the classic one rather than PNG-in-ICO everywhere: sizes up to 128 are stored
as 32-bit DIB entries and only 256 goes in as PNG. That is what Windows itself writes and
what every tool that reads an icon -- Explorer, jpackage, the WiX toolset used by the MSI --
has accepted for years, whereas an icon whose small sizes are PNG is accepted by Windows
Vista and later but not by every build tool.

The script is run by hand when the mark changes; it is not part of the build, because the
icon is a committed artifact like any other image and regenerating it needs a browser to
rasterize the SVG. The sizes come from a headless browser: an HTML page holding the SVG in an
`<img>` sized to one square per size, screenshotted with a transparent background, for
example

    msedge --headless=new --disable-gpu --hide-scrollbars \
           --default-background-color=00000000 --window-size=32,32 \
           --screenshot=logo32.png file:///.../size32.html

Usage: python make_ico.py <png-directory> <output.ico>
"""

import struct
import sys
import zlib
from pathlib import Path

# Sizes stored as 32-bit DIB entries; 256 is added as PNG below.
DIB_SIZES = (16, 24, 32, 48, 64, 128)
PNG_SIZE = 256


def read_png(path):
    """Decode an 8-bit RGB/RGBA PNG into (width, height, channels, pixels)."""
    data = path.read_bytes()
    if data[:8] != b"\x89PNG\r\n\x1a\n":
        raise ValueError(f"{path} is not a PNG")

    position = 8
    idat = b""
    width = height = bit_depth = colour_type = None
    while position < len(data):
        length = int.from_bytes(data[position : position + 4], "big")
        kind = data[position + 4 : position + 8]
        chunk = data[position + 8 : position + 8 + length]
        position += 12 + length

        if kind == b"IHDR":
            width = int.from_bytes(chunk[0:4], "big")
            height = int.from_bytes(chunk[4:8], "big")
            bit_depth = chunk[8]
            colour_type = chunk[9]
        elif kind == b"IDAT":
            idat += chunk
        elif kind == b"IEND":
            break

    if bit_depth != 8 or colour_type not in (2, 6):
        raise ValueError(f"{path}: only 8-bit RGB and RGBA are supported, got depth={bit_depth} type={colour_type}")

    channels = 3 if colour_type == 2 else 4
    stride = width * channels
    raw = zlib.decompress(idat)
    pixels = bytearray(width * height * channels)
    previous = bytearray(stride)
    position = 0
    for row in range(height):
        filter_type = raw[position]
        position += 1
        line = bytearray(raw[position : position + stride])
        position += stride

        if filter_type == 1:
            for i in range(channels, stride):
                line[i] = (line[i] + line[i - channels]) & 0xFF
        elif filter_type == 2:
            for i in range(stride):
                line[i] = (line[i] + previous[i]) & 0xFF
        elif filter_type == 3:
            for i in range(stride):
                left = line[i - channels] if i >= channels else 0
                line[i] = (line[i] + ((left + previous[i]) >> 1)) & 0xFF
        elif filter_type == 4:
            for i in range(stride):
                left = line[i - channels] if i >= channels else 0
                above = previous[i]
                corner = previous[i - channels] if i >= channels else 0
                estimate = left + above - corner
                da = abs(estimate - left)
                db = abs(estimate - above)
                dc = abs(estimate - corner)
                nearest = left if (da <= db and da <= dc) else (above if db <= dc else corner)
                line[i] = (line[i] + nearest) & 0xFF

        pixels[row * stride : (row + 1) * stride] = line
        previous = line

    return width, height, channels, bytes(pixels)


def dib_entry(width, height, channels, pixels):
    """One icon entry as a 32-bit bottom-up DIB followed by its (empty) AND mask."""
    header = struct.pack("<IiiHHIIiiII", 40, width, height * 2, 1, 32, 0, 0, 0, 0, 0, 0)

    body = bytearray()
    for y in range(height - 1, -1, -1):
        for x in range(width):
            index = (y * width + x) * channels
            r = pixels[index]
            g = pixels[index + 1]
            b = pixels[index + 2]
            a = pixels[index + 3] if channels == 4 else 255
            body += bytes((b, g, r, a))

    # The AND mask is still part of the format; with 32-bit entries and a real alpha channel
    # it is all zero, which is what Windows and every writer of icons put there.
    mask_stride = ((width + 31) // 32) * 4
    body += bytes(mask_stride * height)

    return header + bytes(body)


def main():
    source = Path(sys.argv[1])
    target = Path(sys.argv[2])

    entries = []
    for size in DIB_SIZES:
        width, height, channels, pixels = read_png(source / f"logo{size}.png")
        if (width, height) != (size, size):
            raise ValueError(f"logo{size}.png is {width}x{height}")
        entries.append((size, dib_entry(width, height, channels, pixels)))

    png = (source / f"logo{PNG_SIZE}.png").read_bytes()
    entries.append((PNG_SIZE, png))

    offset = 6 + 16 * len(entries)
    directory = bytearray()
    body = bytearray()
    for size, data in entries:
        dimension = 0 if size >= 256 else size
        directory += struct.pack("<BBBBHHII", dimension, dimension, 0, 0, 1, 32, len(data), offset)
        offset += len(data)
        body += data

    target.write_bytes(struct.pack("<HHH", 0, 1, len(entries)) + bytes(directory) + bytes(body))
    print(f"{target}: {len(entries)} entries, {target.stat().st_size} bytes")


if __name__ == "__main__":
    main()
