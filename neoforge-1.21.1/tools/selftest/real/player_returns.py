#!/usr/bin/env python3
"""A player comes back to an area left alone with mobs, on a world moved between loaders
(real servers and real clients, see README.md).

The worlds of moved_world.py without their forced chunk: the pen is on disk only. The world is
opened on a loader with a later day, a real client joins and goes to the pen: the chunk is
loaded for the player, its mobs come back from disk, the chunk catches up on the steps it
missed under the player's eyes and the mobs that survived are judged again. Who is left is
sampled every second.

The steps a chunk missed are applied to a random share of its columns, except the step of a
day at or past the peak (100%): for the same result on every run the world must be saved with
the step before it (moved_world.py create ...:seen on day 40 to 49) and opened on day 50 to 59.

usage: player_returns.py <loader>:<world>:<day>:<label>[:near] ...
  near: the player stands on the pen itself, in creative mode, instead of watching from afar
Every run adds a line to player_returns.jsonl in the work folder.
"""
import json
import os
import re
import shutil
import sys
import time

import moved_world as mw
import realenv as env

P = mw.PLAYER
SECONDS = 75
# The columns of the pen that shelter a mob (x, z) and the heights looked at in the saved world
COVER = {'open': (1601, 1602), 'roof': (1603, 1602), 'glass': (1605, 1602), 'leaves': (1607, 1602),
         'water': (1609, 1602), 'wlog': (1612, 1602), 'room': (1602, 1602)}
COVER_Y = range(193, 206)


def cover(world_dir):
    """What is left of the pen above and around each mob: the blocks of its column that are not air."""
    out = {}
    for tag, (x, z) in COVER.items():
        blocks = env.saved_blocks(world_dir, [(x, y, z) for y in COVER_Y])
        out[tag] = ' '.join('%d:%s' % (y, re.sub(r'^minecraft:|^judgementday:|\[.*', '', blocks[(x, y, z)]))
                            for y in COVER_Y if 'air' not in blocks[(x, y, z)]) or 'nothing'
    return out


def unforced(name):
    """The world without its forced chunks (data/chunks.dat): the pen is not loaded at the start."""
    src, dst = mw.path(name), mw.path(name + 'u')
    if not os.path.isdir(dst):
        assert dst.startswith(env.WORK + os.sep)
        shutil.copytree(src, dst)
        os.remove(dst + '/data/chunks.dat')
        shutil.copy(src + '.json', dst + '.json')
    return dst


def run(loader, name, day, label, near=False):
    src = unforced(name)
    created = json.load(open(src + '.json'))
    env.set_world(loader, src)
    env.set_config(env.world(loader), apocalypseCurrentDay=day)
    env.start(loader)
    loaded_at_start = 'passed' in env.cmd(loader, 'execute if loaded 1608 200 1604')[0]
    env.join(loader, P)
    # The player watches from afar: the chunk of the pen is in view, but no mob is within the 64
    # blocks in which the sun burns them in front of a player, so only what the mod decides for the
    # mobs left alone is seen
    env.cmd(loader, 'gamemode %s %s' % ('creative' if near else 'spectator', P))
    time.sleep(10)
    env.cmd(loader, 'tp %s %s' % (P, '1614.5 200 1600.5 90 20' if near else mw.FAR))
    t0 = time.time()
    timeline = {}
    first = None
    at_load = []
    shots = os.path.join(env.WORK, 'returns_%s_%s_%d' % (label, loader, day))
    while time.time() - t0 < SECONDS + 30:
        now = mw.alive(loader)
        if first is None:
            # The mobs of the pen are back from disk (the armor stand is never removed)
            if not now['stand']:
                time.sleep(0.2)
                continue
            first = time.time()
            at_load = sorted(t for t in mw.TAGS if not now[t])
        sec = int(time.time() - first)
        for t in mw.TAGS:
            if not now[t] and t not in timeline:
                timeline[t] = sec
        if sec == 6 and not os.path.exists(shots + '_6s.png'):
            env.shot(shots + '_6s.png')
        if sec >= SECONDS:
            break
        time.sleep(max(0.0, 1.0 - (time.time() - first) % 1.0))
    env.shot(shots + '_end.png')
    final = mw.alive(loader)
    drops = env.cmd(loader, 'execute if entity @e[type=minecraft:item,x=1590,y=190,z=1590,dx=40,dy=20,dz=40]')[0]
    blocks = env.cmd(loader, 'execute if block 1601 200 1607 judgementday:ash_block[spreads_left=1]',
                     'execute if block 1603 200 1607 judgementday:ash_block[spreads_left=0]',
                     'execute if block 1609 200 1602 minecraft:water', 'execute if block 1607 203 1602 minecraft:oak_leaves',
                     'execute if block 1612 200 1602 minecraft:stone_slab[waterlogged=true]',
                     'data get block 1605 200 1607 Items')
    env.cmd(loader, 'save-all flush')
    errors = env.server_errors(loader)
    env.client_stop(loader)
    env.stop(loader)
    after = mw.tagged(env.world(loader))
    columns = cover(env.world(loader))
    gone = sorted(t for t in mw.TAGS if not final[t])
    same_uuid = all(after.get(t, {}).get('uuid') == created[t]['uuid'] for t in mw.TAGS if final[t])
    m = re.search(r'count: (\d+)', drops)
    row = dict(label=label, loader=loader, world=name, day=day, near=near, loaded_at_start=loaded_at_start,
               at_load=at_load, gone=gone, timeline=dict(sorted(timeline.items(), key=lambda kv: (kv[1], kv[0]))),
               gone_saved=sorted(t for t in mw.TAGS if t not in after), same_uuid=same_uuid,
               drops=int(m.group(1)) if m else 0, ash1='passed' in blocks[0], ash0='passed' in blocks[1],
               water='passed' in blocks[2], leaves='passed' in blocks[3], waterlogged='passed' in blocks[4],
               chest='heart_of_oblivion' in blocks[5] and 'ash_block' in blocks[5], errors=errors[:5],
               columns=columns, where={t: after[t]['pos'] for t in COVER if t in after})
    print('RETURN %-5s %-7s world=%-3s day=%-3d at_load=%s' % (label, loader, name, day, at_load))
    print('   gone after %d s=%s' % (SECONDS, gone))
    print('   timeline=%s' % row['timeline'])
    print('   saved gone=%s same_uuid=%s drops=%d ash=%s/%s water=%s leaves=%s waterlogged=%s chest=%s'
          ' pen_loaded_at_start=%s errors=%d' % (row['gone_saved'], same_uuid, row['drops'], row['ash1'], row['ash0'],
                                                 row['water'], row['leaves'], row['waterlogged'], row['chest'],
                                                 loaded_at_start, len(errors)))
    for t in COVER:
        print('   column of %-6s %s%s' % (t, columns[t], ('   (mob at %s)' % after[t]['pos']) if t in after else ''))
    for e in errors[:3]:
        print('   ERR', e[:200])
    with open(os.path.join(env.WORK, 'player_returns.jsonl'), 'a') as f:
        f.write(json.dumps(row) + '\n')


if __name__ == '__main__':
    for step in sys.argv[1:]:
        p = step.split(':')
        try:
            run(p[0], p[1], int(p[2]), p[3], len(p) > 4 and p[4] == 'near')
        except Exception as e:
            import traceback
            traceback.print_exc()
            print('RUN FAILED', step, e)
            try:
                env.client_stop(p[0])
                env.stop(p[0])
            except Exception as e2:
                print(e2)
        sys.stdout.flush()
