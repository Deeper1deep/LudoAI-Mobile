#!/usr/bin/env python3
"""Decode a ludoai-learning-*.txt file: writes events.jsonl and frame_*.jpg into an output folder."""
import sys, os, base64
src, out = sys.argv[1], (sys.argv[2] if len(sys.argv) > 2 else "learning_out")
os.makedirs(out, exist_ok=True)
ev = open(os.path.join(out, "events.jsonl"), "w")
for line in open(src, encoding="utf-8"):
    if line.startswith("EVENT "): ev.write(line[6:])
    elif line.startswith("FRAME "):
        _, name, b64 = line.rstrip("\n").split(" ", 2)
        open(os.path.join(out, name), "wb").write(base64.b64decode(b64))
print("decoded into", out)
