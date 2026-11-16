#!/usr/bin/env python3
"""Compares what the mod did in two worlds, block by block.

Each loader runs the same scenario twice from the same base world: once without
processing (control) and once with it (after). The effect of the mod is the set of
blocks that differ between control and after. The effects of the two loaders are
compared on the blocks whose control state is the same in both (the same input):
a block changed by one loader and not by the other, or changed into something else,
is a difference.

usage: effectdiff.py <ctrl_a> <after_a> <ctrl_b> <after_b> [--chunks x0:z0:x1:z1] [--samples N]
"""
import collections
import os
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import worlddiff as w  # noqa: E402


def main():
    args = sys.argv[1:]
    if len(args) < 4:
        print(__doc__)
        sys.exit(2)
    box = None
    samples = 10
    i = 4
    while i < len(args):
        if args[i] == "--chunks":
            box = [int(v) for v in args[i + 1].split(":")]
            i += 2
        elif args[i] == "--samples":
            samples = int(args[i + 1])
            i += 2
        else:
            raise SystemExit("unknown option " + args[i])

    def want(x, z, region):
        if box is None:
            return True
        if region:
            return box[0] >> 5 <= x <= box[2] >> 5 and box[1] >> 5 <= z <= box[3] >> 5
        return box[0] <= x <= box[2] and box[1] <= z <= box[3]

    worlds = [w.load_world(p, "region", want) for p in args[:4]]
    keys = set(worlds[0]) & set(worlds[1]) & set(worlds[2]) & set(worlds[3])
    changed_a = changed_b = same_input = 0
    only_a = only_b = different = 0
    per_chunk = collections.Counter()
    shown = []
    kinds = collections.Counter()
    for key in sorted(keys):
        blocks = [w.chunk_blocks(t[key], set()) for t in worlds]
        for y in sorted(set().union(*[set(b) for b in blocks])):
            ca, aa, cb, ab = [(b.get(y) or ["minecraft:air"] * 4096) for b in blocks]
            if ca == aa and cb == ab:
                continue
            for idx in range(4096):
                if ca[idx] != cb[idx]:
                    continue  # not the same input
                ea = ca[idx] != aa[idx]
                eb = cb[idx] != ab[idx]
                if not ea and not eb:
                    continue
                same_input += 1
                changed_a += ea
                changed_b += eb
                kind = None
                if ea and not eb:
                    only_a += 1
                    kind = "only_a"
                elif eb and not ea:
                    only_b += 1
                    kind = "only_b"
                elif aa[idx] != ab[idx]:
                    different += 1
                    kind = "different"
                if kind:
                    per_chunk[key] += 1
                    kinds[(kind, ca[idx], aa[idx], ab[idx])] += 1
                    if len(shown) < samples:
                        lx, lz, ly = idx & 15, (idx >> 4) & 15, idx >> 8
                        shown.append("%s %d,%d,%d %s -> a:%s b:%s" % (kind, key[0] * 16 + lx, y * 16 + ly,
                                                                        key[1] * 16 + lz, ca[idx], aa[idx], ab[idx]))
    for s in shown:
        print("SAMPLE " + s)
    for (kind, c, a, b), n in kinds.most_common(20):
        print("KIND %d %s %s -> a:%s b:%s" % (n, kind, c, a, b))
    for key, n in per_chunk.most_common(15):
        print("CHUNK %d %d %d" % (key[0], key[1], n))
    print("EFFECT chunks=%d changed_a=%d changed_b=%d only_a=%d only_b=%d different=%d"
          % (len(keys), changed_a, changed_b, only_a, only_b, different))


if __name__ == "__main__":
    main()
