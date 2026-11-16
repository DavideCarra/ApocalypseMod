"""Type preserving NBT reader/writer and region file rewriter, to edit saved entities in place.

Used to build control worlds: the same world with the mod's day stamp removed from every mob
(as if an older version of the mod had saved it).
"""
import os, struct, zlib, gzip

END, BYTE, SHORT, INT, LONG, FLOAT, DOUBLE, BYTES, STRING, LIST, COMPOUND, INTS, LONGS = range(13)


class R:
    def __init__(self, d):
        self.d = d
        self.i = 0

    def take(self, n):
        v = self.d[self.i:self.i + n]
        self.i += n
        return v

    def string(self):
        n = struct.unpack('>H', self.take(2))[0]
        return self.take(n)

    def payload(self, t):
        if t == BYTE:
            return self.take(1)
        if t == SHORT:
            return self.take(2)
        if t in (INT, FLOAT):
            return self.take(4)
        if t in (LONG, DOUBLE):
            return self.take(8)
        if t == BYTES:
            n = struct.unpack('>i', self.take(4))[0]
            return self.take(n)
        if t == STRING:
            return self.string()
        if t == LIST:
            et = self.take(1)[0]
            n = struct.unpack('>i', self.take(4))[0]
            return (et, [self.payload(et) for _ in range(n)])
        if t == COMPOUND:
            out = []
            while True:
                tt = self.take(1)[0]
                if tt == END:
                    return out
                name = self.string()
                out.append((name, tt, self.payload(tt)))
        if t == INTS:
            n = struct.unpack('>i', self.take(4))[0]
            return self.take(4 * n)
        if t == LONGS:
            n = struct.unpack('>i', self.take(4))[0]
            return self.take(8 * n)
        raise ValueError(t)


def parse(data):
    r = R(data)
    t = r.take(1)[0]
    name = r.string()
    return (name, t, r.payload(t))


def dump_payload(t, p, out):
    if t in (BYTE, SHORT, INT, LONG, FLOAT, DOUBLE):
        out.append(p)
    elif t == BYTES:
        out.append(struct.pack('>i', len(p)))
        out.append(p)
    elif t == STRING:
        out.append(struct.pack('>H', len(p)))
        out.append(p)
    elif t == LIST:
        et, items = p
        out.append(bytes([et]))
        out.append(struct.pack('>i', len(items)))
        for it in items:
            dump_payload(et, it, out)
    elif t == COMPOUND:
        for name, tt, pp in p:
            out.append(bytes([tt]))
            out.append(struct.pack('>H', len(name)))
            out.append(name)
            dump_payload(tt, pp, out)
        out.append(b'\x00')
    elif t == INTS:
        out.append(struct.pack('>i', len(p) // 4))
        out.append(p)
    elif t == LONGS:
        out.append(struct.pack('>i', len(p) // 8))
        out.append(p)
    else:
        raise ValueError(t)


def dump(root):
    name, t, p = root
    out = [bytes([t]), struct.pack('>H', len(name)), name]
    dump_payload(t, p, out)
    return b''.join(out)


def get(comp, key):
    for name, t, p in comp:
        if name == key:
            return t, p
    return None, None


def strip_entity(comp, key=b'judgementday_lastSeenDay'):
    """Removes the stamp from the entity compound (and its passengers). Returns how many were removed."""
    n = 0
    for i, (name, t, p) in enumerate(comp):
        if name in (b'ForgeData', b'NeoForgeData') and t == COMPOUND:
            before = len(p)
            p[:] = [e for e in p if e[0] != key]
            n += before - len(p)
        if name == b'Passengers' and t == LIST:
            for sub in p[1]:
                n += strip_entity(sub, key)
    # An empty ForgeData is what an entity without mod data has: drop it as Forge would not write it
    comp[:] = [e for e in comp if not (e[0] in (b'ForgeData', b'NeoForgeData') and e[1] == COMPOUND and not e[2])]
    return n


def rewrite_region(path, edit):
    """Rewrites the region file, passing every chunk's parsed NBT to edit(root) -> True if changed."""
    data = open(path, 'rb').read()
    if len(data) < 8192:
        return 0
    chunks = {}
    changed = 0
    for i in range(1024):
        loc = struct.unpack('>I', data[i * 4:i * 4 + 4])[0]
        off, cnt = loc >> 8, loc & 0xFF
        if off == 0 or cnt == 0:
            continue
        p = off * 4096
        length = struct.unpack('>I', data[p:p + 4])[0]
        comp = data[p + 4]
        raw = data[p + 5:p + 4 + length]
        if comp == 2:
            nbt = zlib.decompress(raw)
        elif comp == 1:
            nbt = gzip.decompress(raw)
        elif comp == 3:
            nbt = raw
        else:
            raise ValueError('compression %d' % comp)
        root = parse(nbt)
        assert dump(root) == nbt, 'round trip mismatch'
        if edit(root):
            changed += 1
            raw = zlib.compress(dump(root))
            comp = 2
        chunks[i] = (comp, raw, data[4096 + i * 4:4096 + i * 4 + 4])
    if not changed:
        return 0
    header = bytearray(8192)
    body = bytearray()
    sector = 2
    for i, (comp, raw, ts) in sorted(chunks.items()):
        blob = struct.pack('>I', len(raw) + 1) + bytes([comp]) + raw
        n = (len(blob) + 4095) // 4096
        blob += b'\x00' * (n * 4096 - len(blob))
        header[i * 4:i * 4 + 4] = struct.pack('>I', (sector << 8) | n)
        header[4096 + i * 4:4096 + i * 4 + 4] = ts
        body += blob
        sector += n
    open(path, 'wb').write(bytes(header) + bytes(body))
    return changed


def strip_stamps(world_dir):
    """Removes the day stamp from every saved entity of the world. Returns (chunks changed, stamps removed)."""
    total = [0]

    def edit(root):
        t, ents = get(root[2], b'Entities')
        n = 0
        if t == LIST:
            for e in ents[1]:
                n += strip_entity(e)
        total[0] += n
        return n > 0

    changed = 0
    d = os.path.join(world_dir, 'entities')
    for name in sorted(os.listdir(d)):
        if name.endswith('.mca'):
            changed += rewrite_region(os.path.join(d, name), edit)
    return changed, total[0]
