#!/usr/bin/env python3
"""A world with mobs moved between loaders (real servers, see README.md).

A pen is built in one forced chunk, with a mob for every case of UnattendedChunkHandler
(exposed, sheltered, in water, fire immune, riders...). The server is stopped (every mob gets
the day stamped), the world is copied to the other loaders with a later day in its config,
and the mobs left are compared exactly (by tag and UUID).

usage: moved_world.py <step> ...
  create:<loader>:<day>:<name>[:seen]        a new world on the loader, saved as w_<name>. With "seen" a
                                             real client looks at the place first, so that the chunk
                                             of the pen is saved with the steps of that day
  strip:<name>:<new name>                    a copy without the day stamps (as an older version saved it)
  reload:<loader>:<name>:<day>:<label>[:<end day>]
                                             opens a copy on the loader on that day and reports who is left
Every reload adds a line to moved_world.jsonl in the work folder.
"""
import gzip
import json
import os
import re
import shutil
import sys
import time

import nbtedit
import realenv as env
from realenv import worlddiff as wd

BASE = 'NoAI:1b,PersistenceRequired:1b,Silent:1b'


def mob(kind, x, y, z, tag, extra=''):
    return 'summon minecraft:%s %s %s %s {%s%s,Tags:["jdtest","jd_%s"]}' % (
        kind, x, y, z, BASE, (',' + extra) if extra else '', tag)


BUILD = [
    'fill 1600 199 1600 1615 199 1607 minecraft:stone',
    'setblock 1603 203 1602 minecraft:stone',
    'setblock 1605 203 1602 minecraft:glass',
    'setblock 1607 203 1602 minecraft:oak_leaves[persistent=true]',
    'fill 1608 200 1601 1610 200 1603 minecraft:stone',
    'setblock 1609 200 1602 minecraft:water',
    'fill 1611 200 1601 1613 200 1603 minecraft:stone',
    'setblock 1612 200 1602 minecraft:stone_slab[type=bottom,waterlogged=true]',
    'fill 1601 194 1601 1603 197 1603 minecraft:stone',
    'fill 1602 195 1602 1602 196 1602 minecraft:air',
    'fill 1611 -31 1611 1613 319 1613 minecraft:glass',
    'fill 1612 -30 1612 1612 319 1612 minecraft:air',
    'fill 1603 -31 1611 1605 -28 1613 minecraft:stone',
    'fill 1604 -30 1612 1604 -29 1612 minecraft:air',
    'setblock 1601 200 1607 judgementday:ash_block[spreads_left=1]',
    'setblock 1603 200 1607 judgementday:ash_block[spreads_left=0]',
    'setblock 1605 200 1607 minecraft:chest{Items:[{Slot:0b,id:"judgementday:heart_of_oblivion",Count:1b},{Slot:1b,id:"judgementday:ash_block",Count:64b}]}',
]
MOBS = [
    mob('cow', 1601.5, 200, 1602.5, 'open'),
    mob('cow', 1603.5, 200, 1602.5, 'roof'),
    mob('cow', 1605.5, 200, 1602.5, 'glass'),
    mob('cow', 1607.5, 200, 1602.5, 'leaves'),
    mob('cow', 1609.5, 200, 1602.5, 'water'),
    mob('cow', 1612.5, 200.5, 1602.5, 'wlog'),
    mob('villager', 1601.5, 200, 1605.5, 'villager'),
    mob('cat', 1603.5, 200, 1605.5, 'cat'),
    mob('cow', 1605.5, 200, 1605.5, 'baby', 'Age:-24000'),
    mob('magma_cube', 1607.5, 200, 1605.5, 'magma', 'Size:0'),
    mob('cow', 1609.5, 200, 1605.5, 'fireres'),
    mob('cow', 1611.5, 200, 1605.5, 'invul', 'Invulnerable:1b'),
    mob('pig', 1613.5, 200, 1605.5, 'mount',
        'Passengers:[{id:"minecraft:cow",%s,Tags:["jdtest","jd_rider"]}]' % BASE),
    'summon minecraft:armor_stand 1615.5 200 1605.5 {NoGravity:1b,Tags:["jdtest","jd_stand"]}',
    mob('cow', 1602.5, 195, 1602.5, 'room'),
    mob('cow', 1612.5, -30, 1612.5, 'shaft'),
    mob('cow', 1604.5, -30, 1612.5, 'deep'),
    'effect give @e[tag=jd_fireres] minecraft:fire_resistance infinite 0 true',
]
TAGS = ['open', 'roof', 'glass', 'leaves', 'water', 'wlog', 'villager', 'cat', 'baby', 'magma', 'fireres', 'invul',
        'mount', 'rider', 'stand', 'room', 'shaft', 'deep']

CONFIG = dict(apocalypseActive='false', apocalypseMaxDays=50, apocalypseEndDay=200, minDamageHeight=60,
              minWaterEvaporationHeight=60, startBeforeApocalypse='false')
PLAYER = 'Tester'
# Where a player watches the pen from: 5 chunks away, in view, with no mob of the pen within 64 blocks
FAR = '1608 230 1524 0 20'
# The pen's chunk (100, 100) as the mod's chunk data names it
PEN_CHUNK = 100 + (100 << 32)


def path(name):
    return os.path.join(env.WORK, 'w_' + name)


def uuid_of(e):
    u = e.get('UUID')
    return '%08x%08x%08x%08x' % tuple(v & 0xffffffff for v in u) if u else None


def tagged(world_dir):
    """tag -> uuid, type and day stamps of the saved test entities (passengers included)."""
    out = {}

    def visit(e):
        for t in e.get('Tags', []):
            if t.startswith('jd_'):
                out[t[3:]] = dict(uuid=uuid_of(e), id=e.get('id'),
                                  forge=e.get('ForgeData', {}).get('judgementday_lastSeenDay'),
                                  neo=e.get('NeoForgeData', {}).get('judgementday_lastSeenDay'),
                                  pos=[round(v, 2) for v in e.get('Pos', [])])
        for p in e.get('Passengers', []):
            visit(p)

    for tag in wd.load_world(world_dir, 'entities', None).values():
        for e in tag.get('Entities', []):
            visit(e)
    return out


def phases(world_dir):
    """The mod's saved chunk data (None if the mod never ran in the world)."""
    p = os.path.join(world_dir, 'data', 'judgementday_chunkdata.dat')
    if not os.path.exists(p):
        return None
    return wd.read_nbt(gzip.decompress(open(p, 'rb').read()))['data']


def alive(loader):
    """Who of the pen is in the loaded world, read with one command (one moment of the server)."""
    answer = env.cmd(loader, 'execute as @e[tag=jdtest] run tag @s list')[0]
    present = set(re.findall(r'jd_([a-z]+)', answer))
    return {t: t in present for t in TAGS}


def create(loader, day, name, seen=False):
    env.set_world(loader, None)
    os.makedirs(env.world(loader), exist_ok=True)
    env.set_config(env.world(loader), apocalypseCurrentDay=day, **CONFIG)
    env.start(loader)
    env.cmd(loader, 'gamerule doDaylightCycle false', 'gamerule doWeatherCycle false', 'gamerule doMobSpawning false',
            'gamerule randomTickSpeed 0', 'weather clear', 'time set 6000', 'forceload add 1600 1600 1615 1615')
    env.wait_for(loader, 'execute if loaded 1608 200 1604')
    if seen:
        # A player looks at the place from afar (no mob heats up within 64 blocks of them): the
        # chunk is processed for the day, then the pen is built
        env.join(loader, PLAYER)
        env.cmd(loader, 'gamemode spectator ' + PLAYER, 'tp %s %s' % (PLAYER, FAR))
        time.sleep(30)
    res = env.cmd(loader, *BUILD)
    bad = [(c, r) for c, r in zip(BUILD, res) if not re.search(r'[Ff]illed|Changed the block|Successfully', r)]
    res = env.cmd(loader, *MOBS)
    bad += [(c, r) for c, r in zip(MOBS, res) if not re.search(r'Summoned|Applied', r)]
    time.sleep(8)
    count = env.cmd(loader, 'execute if entity @e[tag=jdtest]')[0]
    if seen:
        env.client_stop(loader)
        time.sleep(3)
    env.cmd(loader, 'save-all flush')
    env.stop(loader)
    env.save_world(loader, path(name))
    info = tagged(path(name))
    mobs = [v for t, v in info.items() if t != 'stand']
    print('CREATE %s day=%d on %s: %s bad=%s' % (name, day, loader, count.strip(), bad))
    chunk = phases(path(name)) or {}
    print('  stamps: ForgeData=%s NeoForgeData=%s; saved tags=%d; armor stand stamp=%s; config day=%s;'
          ' saved step of the pen chunk=%s' % (
              sorted(set(str(v['forge']) for v in mobs)), sorted(set(str(v['neo']) for v in mobs)), len(info),
              info.get('stand', {}).get('forge'), env.get_config(path(name)).get('apocalypseCurrentDay'),
              chunk.get('Chunks', {}).get(str(PEN_CHUNK))))
    json.dump(info, open(path(name) + '.json', 'w'), indent=1)


def strip(name, new):
    """The control world: the same pen, saved as a version of the mod without day stamps did."""
    assert path(new).startswith(env.WORK + os.sep)
    if os.path.isdir(path(new)):
        shutil.rmtree(path(new))
    shutil.copytree(path(name), path(new))
    shutil.copy(path(name) + '.json', path(new) + '.json')
    changed, removed = nbtedit.strip_stamps(path(new))
    left = [t for t, v in tagged(path(new)).items() if v['forge'] is not None or v['neo'] is not None]
    chunk = phases(path(new))
    print('STRIP %s -> %s: chunks rewritten=%d stamps removed=%d still stamped=%s saved chunk data=%s' % (
        name, new, changed, removed, left, None if chunk is None else len(chunk.get('Chunks', {}))))


def reload(loader, name, day, label, end=None):
    created = json.load(open(path(name) + '.json'))
    env.set_world(loader, path(name))
    env.set_config(env.world(loader), apocalypseCurrentDay=day)
    if end is not None:
        # The apocalypse was over on that day: nothing burned from then on
        env.set_config(env.world(loader), apocalypseEndDay=end)
    env.start(loader)
    env.wait_for(loader, 'execute if entity @e[tag=jd_stand]')
    time.sleep(8)
    now = alive(loader)
    blocks = env.cmd(loader, 'execute if block 1601 200 1607 judgementday:ash_block[spreads_left=1]',
                     'execute if block 1603 200 1607 judgementday:ash_block[spreads_left=0]',
                     'data get block 1605 200 1607 Items')
    cfg_day = env.get_config(env.world(loader)).get('apocalypseCurrentDay')
    env.cmd(loader, 'save-all flush')
    env.stop(loader)
    after = tagged(env.world(loader))
    errors = env.server_errors(loader)
    removed = sorted(t for t in TAGS if not now[t])
    # Survivors keep their UUID; removed ones are gone from the saved files too
    same_uuid = all(after.get(t, {}).get('uuid') == created[t]['uuid'] for t in TAGS if now[t])
    gone_saved = sorted(t for t in TAGS if t not in after)
    key = 'neo' if loader == 'neo' else 'forge'
    stamps = sorted(set(str(v[key]) for t, v in after.items() if t != 'stand'))
    old_tag = sorted(set(str(v['forge']) for t, v in after.items() if t != 'stand')) if loader == 'neo' else None
    chunk = phases(env.world(loader))
    row = dict(label=label, loader=loader, world=name, day=day, end=end, removed=removed, gone_saved=gone_saved,
               same_uuid=same_uuid, stamps=stamps, old_forge_tag=old_tag, ash1='passed' in blocks[0],
               ash0='passed' in blocks[1], chest=re.sub(r'\s+', ' ', blocks[2])[:220], cfg_day=cfg_day,
               errors=errors[:5], phases=(len(chunk.get('Chunks', {})), chunk.get('OresRetrofit')) if chunk else None)
    print('RELOAD %-6s %-7s world=%-4s day=%-3d removed=%s' % (label, loader, name, day, removed))
    print('   gone from saved files=%s same_uuid=%s stamps(%s)=%s%s ash=%s/%s cfgday=%s phases=%s errors=%d' % (
        gone_saved, same_uuid, key, stamps, (' leftover ForgeData=%s' % old_tag) if old_tag else '',
        row['ash1'], row['ash0'], cfg_day, row['phases'], len(errors)))
    print('   chest:', row['chest'])
    for e in errors[:3]:
        print('   ERR', e[:200])
    with open(os.path.join(env.WORK, 'moved_world.jsonl'), 'a') as f:
        f.write(json.dumps(row) + '\n')


if __name__ == '__main__':
    for step in sys.argv[1:]:
        p = step.split(':')
        if p[0] == 'create':
            create(p[1], int(p[2]), p[3], len(p) > 4 and p[4] == 'seen')
        elif p[0] == 'strip':
            strip(p[1], p[2])
        elif p[0] == 'reload':
            reload(p[1], p[2], int(p[3]), p[4], int(p[5]) if len(p) > 5 else None)
        else:
            sys.exit('unknown step ' + step)
        sys.stdout.flush()
