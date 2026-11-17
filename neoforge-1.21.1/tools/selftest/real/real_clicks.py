#!/usr/bin/env python3
"""The rituals and the buckets with real mouse clicks (real servers and real clients, see README.md).

usage: real_clicks.py base                    generates the base world once (on the Forge server)
       real_clicks.py <loader>:<day> ...      the click sequence on the loader at that apocalypse day
       real_clicks.py compare                 the results of the three loaders, row by row

A run writes clicks_<loader>_<day>.json and its screenshots in the work folder. The player stands
on a stone floor in the air and is aimed with tp; every click is a real one, done with xdotool.
"""
import json
import os
import re
import sys
import time

import realenv as srv

T = srv.WORK
HEART = 'judgementday:heart_of_oblivion'
ASH = 'judgementday:ash_block'
P = 'Tester'


def item_id(s):
    m = re.search(r'id: "([^"]+)"', s)
    return m.group(1) if m else None


class Run:
    def __init__(self, loader, day):
        self.l = loader
        self.day = day
        self.res = {}
        self.shots = []

    def q(self, *c):
        return srv.cmd(self.l, *c)

    def block(self, x, y, z, b):
        return 'passed' in self.q('execute if block %d %d %d %s' % (x, y, z, b))[0]

    def hearts(self):
        r = self.q('execute if entity @e[type=minecraft:item,nbt={Item:{id:"%s"}}]' % HEART)[0]
        m = re.search(r'count: (\d+)', r)
        return int(m.group(1)) if m else 0

    def inv(self, item):
        """How many of the item the player carries (clear with a maximum of 0 only counts)."""
        m = re.search(r'Found (\d+)', self.q('clear %s %s 0' % (P, item))[0])
        return int(m.group(1)) if m else 0

    def hearts_total(self):
        return self.hearts() + self.inv(HEART)

    def dry(self):
        # Every fluid left in the air of the arena: a current would push the player off their aim
        self.q('fill 990 150 990 1010 151 1010 minecraft:air')

    def hand(self):
        return item_id(self.q('data get entity %s SelectedItem' % P)[0])

    def offhand(self):
        return item_id(self.q('data get entity %s Inventory[{Slot:-106b}]' % P)[0])

    def give(self, item):
        self.q('item replace entity %s weapon.mainhand with %s' % (P, item))

    def aim(self, x, y, z, yaw, pitch):
        self.q('tp %s %s %s %s %s %s' % (P, x, y, z, yaw, pitch))
        time.sleep(1.3)

    def aim_floor(self):
        # Standing at 1000.5 150 1000.5, looking north and down at the top of the floor block 1000 149 998
        self.aim(1000.5, 150, 1000.5, 180, 39)

    def aim_pillar(self):
        # Two blocks south of the pillar, looking at the south face of its lowest block (1000 150 1000)
        self.aim(1000.5, 150, 1002.5, 180, 35)

    def click(self, wait=1.6):
        srv.click(3)
        time.sleep(wait)

    def cfg(self):
        c = srv.get_config(srv.world(self.l))
        return {k: c.get(k) for k in ('apocalypseCurrentDay', 'apocalypseMaxDays', 'apocalypseEndDay',
                                      'lastApocalypseStep', 'apocalypseActive', 'minDamageHeight',
                                      'minWaterEvaporationHeight', 'lastAppliedPhase')}

    def shot(self, name):
        p = '%s/clicks_%s_%d_%s.png' % (T, self.l, self.day, name)
        srv.shot(p)
        self.shots.append(p)

    def pillar(self, egg=True):
        self.q('setblock 1000 150 1000 %s' % ASH, 'setblock 1000 151 1000 minecraft:ancient_debris',
               'setblock 1000 152 1000 minecraft:reinforced_deepslate',
               'setblock 1000 153 1000 %s' % ('minecraft:dragon_egg' if egg else 'minecraft:air'))

    def pillar_state(self):
        return [self.block(1000, 150 + i, 1000, 'minecraft:air') for i in range(4)]

    def arena(self):
        # The arena in the air and a closed room deep underground (below every evaporation height)
        self.q('fill 990 149 990 1010 149 1010 minecraft:stone', 'fill 990 150 990 1010 157 1010 minecraft:air',
               'fill 995 -41 995 1005 -37 1005 minecraft:stone', 'fill 996 -40 996 1004 -38 1004 minecraft:air')

    def count(self, selector):
        r = self.q('execute if entity ' + selector)[0]
        m = re.search(r'count: (\d+)', r)
        return int(m.group(1)) if m else 0

    def eye_y(self):
        """Height of the player's eyes above their feet, as the server has it (1.62 standing, 1.27 sneaking)."""
        self.q('kill @e[tag=jd_eye]')
        self.q('execute as %s at @s anchored eyes positioned ^ ^ ^ run summon minecraft:marker ~ ~ ~ {Tags:["jd_eye"]}' % P)
        a = self.q('data get entity @e[tag=jd_eye,limit=1] Pos[1]', 'data get entity %s Pos[1]' % P)
        self.q('kill @e[tag=jd_eye]')
        v = [float(re.search(r'(-?[\d.]+)d', x).group(1)) for x in a]
        return round(v[0] - v[1], 3)

    def clean(self):
        self.q('kill @e[type=minecraft:item]', 'clear %s %s' % (P, HEART), 'fill 990 150 990 1010 153 1010 minecraft:air',
               'setblock 1000 149 998 minecraft:stone')

    def run(self):
        l, day, r = self.l, self.day, self.res
        active = day >= 50
        srv.set_world(l, T + '/w_clicks_base')
        kv = dict(apocalypseActive=str(active).lower(), apocalypseCurrentDay=day, apocalypseMaxDays=50,
                  apocalypseEndDay=200, minDamageHeight=60, minWaterEvaporationHeight=60, startBeforeApocalypse='false')
        if active:
            kv['lastAppliedPhase'] = 5
        srv.set_config(srv.world(l), **kv)
        srv.start(l)
        srv.join(l, P)
        fx = ['effect give %s minecraft:resistance infinite 4 true' % P,
              'effect give %s minecraft:fire_resistance infinite 0 true' % P]
        self.q(*fx)
        time.sleep(12)
        r['windows'] = srv.focus_game()
        time.sleep(1)
        # No random ticks: a grass block with water above turns to dirt at a random moment (vanilla)
        self.q('gamerule doDaylightCycle false', 'gamerule doWeatherCycle false', 'gamerule doMobSpawning false',
               'gamerule doFireTick false', 'gamerule randomTickSpeed 0', 'time set 6000', 'weather clear',
               'gamemode survival ' + P, 'clear ' + P,
               'forceload add 992 992 1008 1008', *fx)
        for i in range(60):
            if 'passed' in self.q('execute if loaded 1000 150 1000')[0]:
                break
            time.sleep(1)
        # The player sees the area first: its chunks are processed for the day, then the arena is built
        self.arena()
        self.aim_floor()
        time.sleep(15)
        self.arena()
        self.q('kill @e[type=minecraft:item]', 'kill @e[type=minecraft:falling_block]')
        self.aim_floor()
        r['cfg_start'] = self.cfg()
        r['eye_standing'] = self.eye_y()
        self.shot('0_start')

        # --- the click works and is aimed: a block placed on the floor block it aims at
        self.give('minecraft:cobblestone')
        self.click()
        r['sanity_block_placed'] = self.block(1000, 150, 998, 'minecraft:cobblestone')
        self.q('setblock 1000 150 998 minecraft:air')

        # --- buckets (the apocalypse is on: 60% at day 30, 100% at day 60)
        self.give('minecraft:water_bucket')
        self.click()
        r['bucket_water_above'] = dict(hand=self.hand(), water=self.block(1000, 150, 998, 'minecraft:water'))
        self.shot('1_bucket_above')
        self.dry()
        self.give('minecraft:powder_snow_bucket')
        self.click()
        r['bucket_snow_above'] = dict(hand=self.hand(), snow=self.block(1000, 150, 998, 'minecraft:powder_snow'))
        self.dry()
        self.give('minecraft:cod_bucket')
        self.click()
        r['bucket_cod_above'] = dict(hand=self.hand(), water=self.block(1000, 150, 998, 'minecraft:water'),
                                     cod=self.count('@e[type=minecraft:cod]'))
        self.dry()
        self.q('kill @e[type=minecraft:cod]')
        self.give('minecraft:lava_bucket')
        self.click()
        r['bucket_lava_above'] = dict(hand=self.hand(), lava=self.block(1000, 150, 998, 'minecraft:lava'))
        self.dry()
        self.aim_floor()
        self.give('minecraft:air')
        self.q('item replace entity %s weapon.offhand with minecraft:water_bucket' % P)
        self.click()
        r['bucket_water_offhand'] = dict(offhand=self.offhand(), water=self.block(1000, 150, 998, 'minecraft:water'))
        self.dry()
        self.q('item replace entity %s weapon.offhand with minecraft:air' % P)
        self.give('minecraft:water_bucket')
        self.aim(1000.5, -40, 1000.5, 180, 39)
        self.click()
        r['bucket_water_below'] = dict(hand=self.hand(), water=self.block(1000, -40, 998, 'minecraft:water'))
        self.q('setblock 1000 -40 998 minecraft:air')
        self.give('minecraft:air')

        # --- first ritual: the pattern on the ash block
        self.pillar(egg=False)
        self.aim_pillar()
        self.click()
        r['r1_incomplete'] = dict(air=self.pillar_state(), hearts=self.hearts_total())
        self.pillar(egg=True)
        # a click on another block of the pattern (the ancient debris, at eye level)
        self.aim(1000.5, 150, 1002.5, 180, 5)
        self.click()
        r['r1_click_debris'] = dict(air=self.pillar_state(), hearts=self.hearts_total())
        # a left click on the ash block
        self.aim_pillar()
        srv.click(1)
        time.sleep(1.6)
        r['r1_left_click'] = dict(air=self.pillar_state(), hearts=self.hearts_total())
        self.clean()
        self.pillar(egg=True)
        time.sleep(0.5)
        self.click(2.5)
        r['r1_complete'] = dict(air=self.pillar_state(), hearts=self.hearts_total())
        self.shot('2_ritual1')
        # the heart is picked up by walking on it
        self.q('tp %s 1000.5 150 1000.5 180 39' % P)
        time.sleep(2.5)
        r['r1_pickup'] = dict(hearts_carried=self.inv(HEART), hearts_left=self.hearts())

        # --- sneaking
        self.clean()
        self.pillar(egg=True)
        self.aim_pillar()
        srv.xdo('keydown', 'Shift_L')
        time.sleep(1.0)
        r['eye_sneaking'] = self.eye_y()
        self.click(2.5)
        srv.xdo('keyup', 'Shift_L')
        r['r1_sneaking'] = dict(air=self.pillar_state(), hearts=self.hearts_total())
        time.sleep(0.5)

        # --- with a block in hand
        self.clean()
        self.pillar(egg=True)
        self.aim_pillar()
        self.give('minecraft:cobblestone')
        self.click(2.5)
        r['r1_block_in_hand'] = dict(air=self.pillar_state(), hearts=self.hearts_total(),
                                     cobble_carried=self.inv('minecraft:cobblestone'),
                                     cobble=[self.block(1000, 150 + i, 1001, 'minecraft:cobblestone') for i in range(2)])

        # --- a spectator clicking the pattern (Forge fires its event before the spectator check)
        self.clean()
        self.pillar(egg=True)
        self.q('gamemode spectator ' + P)
        self.aim_pillar()
        self.click(2.5)
        r['r1_spectator'] = dict(air=self.pillar_state(), hearts=self.hearts_total())
        self.clean()
        self.q('gamemode %s %s' % ('creative' if active else 'survival', P), *fx)
        self.give(HEART)

        # --- second ritual: the heart on a grass block
        self.aim_floor()
        self.click()
        r['r2_on_stone'] = dict(hand=self.hand(), end=self.cfg()['apocalypseEndDay'])
        self.q('setblock 1000 149 998 minecraft:grass_block')
        self.q('item replace entity %s weapon.offhand with %s' % (P, HEART))
        self.give('minecraft:air')
        self.click()
        r['r2_offhand'] = dict(offhand=self.offhand(), end=self.cfg()['apocalypseEndDay'])
        self.q('item replace entity %s weapon.offhand with minecraft:air' % P)
        self.give(HEART)
        self.click(1.0)
        self.shot('3_ritual2_title')
        time.sleep(2.0)
        r['r2_done'] = dict(hand=self.hand(), cfg=self.cfg(), hearts_left=self.hearts(),
                            grass=self.block(1000, 149, 998, 'minecraft:grass_block'))
        time.sleep(5)
        self.shot('4_after_end')

        # --- after the end water is not evaporated any more
        self.give('minecraft:water_bucket')
        self.click()
        r['bucket_water_after_end'] = dict(hand=self.hand(), water=self.block(1000, 150, 998, 'minecraft:water'))
        self.dry()

        # --- the ritual again once the apocalypse is over
        self.give(HEART)
        self.aim_floor()
        on_grass = self.block(1000, 149, 998, 'minecraft:grass_block')
        self.click(3.0)
        r['r2_again'] = dict(on_grass=on_grass, hand=self.hand(), cfg=self.cfg(), hearts_left=self.hearts())

        # --- vanilla: a grass block with water above stays grass without random ticks and turns to dirt
        # with them (why this test runs without random ticks: with them the ritual above would find dirt
        # at a random moment)
        self.dry()
        self.q('setblock 1000 149 998 minecraft:grass_block', 'setblock 1000 150 998 minecraft:water')
        time.sleep(5)
        r['vanilla_grass_no_random_ticks'] = self.block(1000, 149, 998, 'minecraft:grass_block')
        self.q('gamerule randomTickSpeed 4096')
        time.sleep(3)
        r['vanilla_grass_random_ticks'] = dict(grass=self.block(1000, 149, 998, 'minecraft:grass_block'),
                                               dirt=self.block(1000, 149, 998, 'minecraft:dirt'))
        self.q('gamerule randomTickSpeed 0')

        r['server_errors'] = [x[:160] for x in srv.server_errors(l)][:5]
        srv.client_stop(l)
        srv.stop(l)
        cl = srv.client_log(l)
        r['client_errors'] = [x[:160] for x in cl.splitlines() if re.search(r'judgementday', x)
                              and re.search(r'ERROR|Exception', x)][:5]
        json.dump(dict(res=r, shots=self.shots), open('%s/clicks_%s_%d.json' % (T, l, day), 'w'), indent=1)
        print('=== %s day %d' % (l, day))
        for k, v in r.items():
            print('  %-24s %s' % (k, v))
        sys.stdout.flush()


def base():
    """A new world of the Forge server on day 0: the same input for the three loaders."""
    srv.set_world('forge', None)
    os.makedirs(srv.world('forge'), exist_ok=True)
    srv.set_config(srv.world('forge'), apocalypseActive='false', apocalypseCurrentDay=0, apocalypseMaxDays=50,
                   apocalypseEndDay=200, minDamageHeight=60, minWaterEvaporationHeight=60,
                   startBeforeApocalypse='false')
    srv.start('forge')
    time.sleep(5)
    srv.stop('forge')
    srv.save_world('forge', T + '/w_clicks_base')
    print('base world saved in', T + '/w_clicks_base')


def compare():
    total = 0
    for day in sorted(set(int(f.split('_')[2][:-5]) for f in os.listdir(T) if re.match(r'clicks_\w+_\d+\.json$', f))):
        res = {}
        for l in srv.LOADERS:
            p = '%s/clicks_%s_%d.json' % (T, l, day)
            if os.path.exists(p):
                res[l] = json.load(open(p))['res']
        ref = res[next(iter(res))]
        print('== day %d: %s' % (day, ', '.join(res)))
        diff = 0
        for k in ref:
            vals = {l: json.dumps(r.get(k), sort_keys=True) for l, r in res.items()}
            same = len(set(vals.values())) == 1
            diff += not same
            print('  %-30s %s %s' % (k, 'same' if same else 'DIFFERENT', next(iter(vals.values()))[:150] if same else vals))
        print('  rows=%d different=%d' % (len(ref), diff))
        total += diff
    return total


if __name__ == '__main__':
    if sys.argv[1] == 'base':
        base()
    elif sys.argv[1] == 'compare':
        sys.exit(1 if compare() else 0)
    else:
        for a in sys.argv[1:]:
            l, d = a.split(':')
            try:
                Run(l, int(d)).run()
            except Exception as e:
                import traceback
                traceback.print_exc()
                print('RUN FAILED', a, e)
                try:
                    srv.client_stop(l)
                    srv.stop(l)
                except Exception as e2:
                    print(e2)
            sys.stdout.flush()
