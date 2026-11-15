#!/usr/bin/env python3
"""Compares two saved worlds block by block, independently of the Minecraft version.

Reads the region files (Anvil) of both worlds and compares, chunk by chunk, every block
state (by name and properties, so the internal ids of each version do not matter), the
block entities (id and position), the heightmaps and the entities (type and block
position). Block names renamed between versions are mapped first (see RENAMES).

usage: worlddiff.py <world_a> <world_b> [--chunks x0:z0:x1:z1] [--samples N] [--entities]
                    [--ignore-heightmaps] [--ignore-state name,...]

Prints one line per chunk that differs and a TOTAL line. Only chunks present in both
worlds are compared (chunks in one world only are counted as ONLY_A / ONLY_B).
"""
import gzip
import io
import os
import struct
import sys
import zlib

# Blocks renamed between 1.20.1 and 1.21.1 (old name -> new name)
RENAMES = {
    "minecraft:grass": "minecraft:short_grass",
}


# ---------------------------------------------------------------- NBT
class Reader:
    def __init__(self, data):
        self.d = data
        self.i = 0

    def take(self, n):
        v = self.d[self.i:self.i + n]
        self.i += n
        return v

    def u8(self):
        v = self.d[self.i]
        self.i += 1
        return v

    def s8(self):
        return struct.unpack(">b", self.take(1))[0]

    def s16(self):
        return struct.unpack(">h", self.take(2))[0]

    def u16(self):
        return struct.unpack(">H", self.take(2))[0]

    def s32(self):
        return struct.unpack(">i", self.take(4))[0]

    def s64(self):
        return struct.unpack(">q", self.take(8))[0]

    def string(self):
        n = self.u16()
        return self.take(n).decode("utf-8", "replace")

    def payload(self, t):
        if t == 1:
            return self.s8()
        if t == 2:
            return self.s16()
        if t == 3:
            return self.s32()
        if t == 4:
            return self.s64()
        if t == 5:
            return struct.unpack(">f", self.take(4))[0]
        if t == 6:
            return struct.unpack(">d", self.take(8))[0]
        if t == 7:
            n = self.s32()
            return self.take(n)
        if t == 8:
            return self.string()
        if t == 9:
            et = self.u8()
            n = self.s32()
            return [self.payload(et) for _ in range(n)]
        if t == 10:
            out = {}
            while True:
                tt = self.u8()
                if tt == 0:
                    return out
                name = self.string()
                out[name] = self.payload(tt)
        if t == 11:
            n = self.s32()
            return list(struct.unpack(">%di" % n, self.take(4 * n)))
        if t == 12:
            n = self.s32()
            return list(struct.unpack(">%dq" % n, self.take(8 * n)))
        raise ValueError("bad tag %d" % t)

    def root(self):
        t = self.u8()
        self.string()
        return self.payload(t)


def read_nbt(data):
    return Reader(data).root()


# ---------------------------------------------------------------- regions
def chunks_of_region(path):
    with open(path, "rb") as f:
        data = f.read()
    if len(data) < 8192:
        return
    for i in range(1024):
        loc = struct.unpack(">I", data[i * 4:i * 4 + 4])[0]
        off, cnt = loc >> 8, loc & 0xFF
        if off == 0 or cnt == 0:
            continue
        p = off * 4096
        length = struct.unpack(">I", data[p:p + 4])[0]
        comp = data[p + 4]
        raw = data[p + 5:p + 4 + length]
        if comp & 0x80:
            # Stored in its own file (oversized chunk)
            ext = path[:-4] + ".c.%d.%d.mcc"
            continue
        if comp == 1:
            raw = gzip.decompress(raw)
        elif comp == 2:
            raw = zlib.decompress(raw)
        elif comp == 3:
            pass
        else:
            raise ValueError("compression %d not supported" % comp)
        yield read_nbt(raw)


def load_world(world, sub, want):
    out = {}
    d = os.path.join(world, sub)
    if not os.path.isdir(d):
        return out
    for name in os.listdir(d):
        if not name.endswith(".mca"):
            continue
        _, rx, rz, _ = name.split(".")
        rx, rz = int(rx), int(rz)
        if want and not want(rx, rz, True):
            continue
        for tag in chunks_of_region(os.path.join(d, name)):
            if sub == "region":
                x, z = tag.get("xPos"), tag.get("zPos")
            else:
                pos = tag.get("Position")
                if pos is None:
                    continue
                x, z = pos[0], pos[1]
            if x is None or (want and not want(x, z, False)):
                continue
            out[(x, z)] = tag
    return out


def state_name(entry, ignore):
    name = entry.get("Name", "?")
    name = RENAMES.get(name, name)
    props = entry.get("Properties")
    if props and name not in ignore:
        return name + "[" + ",".join("%s=%s" % (k, props[k]) for k in sorted(props)) + "]"
    return name


def section_blocks(sec, ignore):
    bs = sec.get("block_states")
    if bs is None:
        return None
    palette = [state_name(p, ignore) for p in bs.get("palette", [])]
    if len(palette) == 1:
        return palette * 4096
    data = bs.get("data")
    if data is None:
        return [palette[0]] * 4096
    bits = max(4, (len(palette) - 1).bit_length())
    per = 64 // bits
    mask = (1 << bits) - 1
    out = []
    for v in data:
        v &= (1 << 64) - 1
        for k in range(per):
            if len(out) == 4096:
                break
            out.append(palette[(v >> (k * bits)) & mask])
    return out


def chunk_blocks(tag, ignore):
    out = {}
    for sec in tag.get("sections", []):
        blocks = section_blocks(sec, ignore)
        if blocks is not None:
            out[sec["Y"]] = blocks
    return out


def main():
    args = sys.argv[1:]
    if len(args) < 2:
        print(__doc__)
        sys.exit(2)
    a, b = args[0], args[1]
    box = None
    samples = 5
    entities = False
    heightmaps = True
    ignore = set()
    i = 2
    while i < len(args):
        if args[i] == "--chunks":
            box = [int(v) for v in args[i + 1].split(":")]
            i += 2
        elif args[i] == "--samples":
            samples = int(args[i + 1])
            i += 2
        elif args[i] == "--entities":
            entities = True
            i += 1
        elif args[i] == "--ignore-heightmaps":
            heightmaps = False
            i += 1
        elif args[i] == "--ignore-state":
            ignore = set(args[i + 1].split(","))
            i += 2
        else:
            raise SystemExit("unknown option " + args[i])

    def want(x, z, region):
        if box is None:
            return True
        if region:
            return box[0] >> 5 <= x <= box[2] >> 5 and box[1] >> 5 <= z <= box[3] >> 5
        return box[0] <= x <= box[2] and box[1] <= z <= box[3]

    ca = load_world(a, "region", want)
    cb = load_world(b, "region", want)
    both = sorted(set(ca) & set(cb))
    only_a = len(set(ca) - set(cb))
    only_b = len(set(cb) - set(ca))
    total_blocks = 0
    diff_blocks = 0
    diff_chunks = 0
    diff_be = 0
    diff_hm = 0
    for key in both:
        ta, tb = ca[key], cb[key]
        ba, bb = chunk_blocks(ta, ignore), chunk_blocks(tb, ignore)
        n = 0
        shown = []
        for y in sorted(set(ba) | set(bb)):
            sa = ba.get(y) or ["minecraft:air"] * 4096
            sb = bb.get(y) or ["minecraft:air"] * 4096
            for idx in range(4096):
                if sa[idx] != sb[idx]:
                    n += 1
                    if len(shown) < samples:
                        lx, lz, ly = idx & 15, (idx >> 4) & 15, idx >> 8
                        shown.append("%d,%d,%d %s|%s" % (key[0] * 16 + lx, y * 16 + ly, key[1] * 16 + lz, sa[idx], sb[idx]))
            total_blocks += 4096
        bea = sorted((e.get("id"), e.get("x"), e.get("y"), e.get("z")) for e in ta.get("block_entities", []))
        beb = sorted((e.get("id"), e.get("x"), e.get("y"), e.get("z")) for e in tb.get("block_entities", []))
        be_diff = bea != beb
        hm_diff = False
        if heightmaps:
            ha, hb = ta.get("Heightmaps", {}), tb.get("Heightmaps", {})
            for t in ("WORLD_SURFACE", "OCEAN_FLOOR", "MOTION_BLOCKING", "MOTION_BLOCKING_NO_LEAVES"):
                if ha.get(t) != hb.get(t):
                    hm_diff = True
        if n or be_diff or hm_diff:
            diff_chunks += 1
            diff_blocks += n
            diff_be += be_diff
            diff_hm += hm_diff
            extra = ""
            if be_diff:
                extra += " BE a=%s b=%s" % (set(bea) - set(beb), set(beb) - set(bea))
            if hm_diff:
                extra += " HEIGHTMAPS"
            print("CHUNK %d %d blocks=%d%s %s" % (key[0], key[1], n, extra, " ".join(shown)))
    ent_line = ""
    if entities:
        ea = load_world(a, "entities", want)
        eb = load_world(b, "entities", want)
        ediff = 0
        for key in sorted(set(ea) | set(eb)):
            la = sorted((e.get("id"), tuple(int(v // 1) for v in e.get("Pos", [0, 0, 0]))) for e in ea.get(key, {}).get("Entities", []))
            lb = sorted((e.get("id"), tuple(int(v // 1) for v in e.get("Pos", [0, 0, 0]))) for e in eb.get(key, {}).get("Entities", []))
            if la != lb:
                ediff += 1
                print("ENTITIES %d %d a=%d b=%d only_a=%s only_b=%s" % (key[0], key[1], len(la), len(lb),
                      sorted(set(la) - set(lb))[:samples], sorted(set(lb) - set(la))[:samples]))
        ent_line = " entity_chunks_differing=%d" % ediff
    print("TOTAL chunks=%d differing=%d blocks=%d/%d block_entities=%d heightmaps=%d only_a=%d only_b=%d%s"
          % (len(both), diff_chunks, diff_blocks, total_blocks, diff_be, diff_hm, only_a, only_b, ent_line))


if __name__ == "__main__":
    main()
