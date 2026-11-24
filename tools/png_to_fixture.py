#!/usr/bin/env python3
"""Convert a PNG screenshot into the test fixture format (gzip of: int32 BE width, int32 BE height, then w*h*3 RGB bytes).
Usage: png_to_fixture.py shot.png out.rgb.gz   (needs Pillow)"""
import gzip, struct, sys
from PIL import Image
im = Image.open(sys.argv[1]).convert('RGBA'); w, h = im.size
px = im.load()
buf = bytearray(struct.pack('>ii', w, h))
for y in range(h):
    for x in range(w):
        r, g, b, a = px[x, y]
        buf += bytes((r, g, b))
gzip.open(sys.argv[2], 'wb').write(bytes(buf))
print('wrote', sys.argv[2], w, h)
