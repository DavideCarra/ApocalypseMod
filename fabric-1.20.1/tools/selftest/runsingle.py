#!/usr/bin/env python3
"""Runs the single player self test (scenario=single, see README.md) in the dev client.

usage: runsingle.py <project_dir> <base_world_dir> <out_dir> [--harness DIR] [--args-format FORMAT]
                    [--display :99] [--timeout SECONDS]

--harness: a folder whose harness files are used first (for another loader's project: the files
missing there are taken from that project's own tools/selftest, then from this folder).
--args-format: how the project's runClient task takes the game arguments, if not as this one.

Copies the harness into the project (its selftest folder must be ignored by git), copies the
base world twice into run/saves, starts the dev client on the X display straight into the
first world and does the mouse clicks and the screenshots the scenario asks for through the
file run/sp.request. Needs xdotool and ImageMagick's import; starts Xvfb on the display if
nothing answers there. Extra gradle arguments (e.g. --offline) are read from GRADLE_ARGS.
"""
import argparse
import os
import re
import shutil
import subprocess
import sys
import time

# How this project's runClient task takes the game arguments
CLIENT_ARGS = '--args=%s'

HARNESS_FILES = ['SelfTestCommon.java', 'SelfTestNew.java', 'SelfTestSingle.java', 'SelfTestClient.java']
CONFIG = 'serverconfig/judgementdaymod-apocalypse.properties'
WORLDS = {
    'sptest': dict(apocalypseCurrentDay=9, apocalypseMaxDays=50),
    'sptest2': dict(apocalypseCurrentDay=45, apocalypseMaxDays=80),
}
COMMON = dict(apocalypseActive='false', apocalypseEndDay=200, minDamageHeight=60, minWaterEvaporationHeight=60,
              startBeforeApocalypse='false')
# The game opens the world at once, keeps running without the focus and is silent
OPTIONS = ('onboardAccessibility:false\npauseOnLostFocus:false\nsoundCategory_master:0.0\nrenderDistance:6\n'
           'tutorialStep:none\njoinedFirstServer:true\nskipMultiplayerWarning:true\n')
SAVED_KEYS = ('apocalypseCurrentDay', 'apocalypseMaxDays', 'apocalypseEndDay', 'lastApocalypseStep',
              'lastAppliedPhase', 'minDamageHeight', 'minWaterEvaporationHeight', 'apocalypseActive')


def read_config(world_dir):
    d = {}
    p = os.path.join(world_dir, CONFIG)
    if os.path.exists(p):
        for line in open(p).read().splitlines():
            if '=' in line and not line.startswith('#'):
                k, v = line.split('=', 1)
                d[k] = v
    return d


def write_config(world_dir, values):
    d = read_config(world_dir)
    d.update({k: str(v) for k, v in values.items()})
    p = os.path.join(world_dir, CONFIG)
    os.makedirs(os.path.dirname(p), exist_ok=True)
    with open(p, 'w') as f:
        for k, v in d.items():
            f.write('%s=%s\n' % (k, v))


class Display:
    def __init__(self, name):
        self.env = dict(os.environ, DISPLAY=name)
        self.name = name
        if self.xdo('getdisplaygeometry').returncode == 0:
            return
        # Nothing answers: the lock of a display that is gone (a reboot) would keep Xvfb from starting
        number = name.lstrip(':').split('.')[0]
        for stale in ('/tmp/.X%s-lock' % number, '/tmp/.X11-unix/X%s' % number):
            if os.path.exists(stale):
                os.remove(stale)
        subprocess.Popen(['Xvfb', name, '-screen', '0', '1280x720x24'], stdout=subprocess.DEVNULL,
                         stderr=subprocess.DEVNULL, start_new_session=True)
        for _ in range(40):
            time.sleep(0.5)
            if self.xdo('getdisplaygeometry').returncode == 0:
                return
        raise RuntimeError('no X server on display ' + name)

    def xdo(self, *args):
        return subprocess.run(['xdotool'] + [str(a) for a in args], env=self.env, capture_output=True, text=True)

    def focus_game(self):
        """The pointer inside the game window and the input focus on it (no window manager is needed)."""
        ids = self.xdo('search', '--name', 'Minecraft').stdout.split()
        best = None
        for w in ids:
            geo = self.xdo('getwindowgeometry', '--shell', w).stdout
            g = dict(l.split('=') for l in geo.splitlines() if '=' in l)
            try:
                size = int(g['WIDTH']) * int(g['HEIGHT'])
            except (KeyError, ValueError):
                continue
            if best is None or size > best[0]:
                best = (size, w, int(g['X']) + int(g['WIDTH']) // 2, int(g['Y']) + int(g['HEIGHT']) // 2)
        if best is None:
            return []
        # Before the game grabs the mouse: the pointer in the middle of its window
        self.xdo('mousemove', best[2], best[3])
        self.xdo('windowfocus', best[1])
        return [best[1]]

    def click(self, button):
        # Press and release together, the pointer where it is: with the mouse grabbed by the game a
        # movement would turn the player, and a held button would repeat the click at low frame rates
        self.xdo('click', button)

    def shot(self, path):
        subprocess.run(['import', '-display', self.name, '-window', 'root', path], capture_output=True)


def game_pids(project):
    out = subprocess.run(['ps', '-eo', 'pid,args'], capture_output=True, text=True).stdout
    return [int(l.split()[0]) for l in out.splitlines() if project in l and 'java' in l and 'ps -eo' not in l]


def stop_game(project):
    for sig in (15, 9):
        for pid in game_pids(project):
            try:
                os.kill(pid, sig)
            except ProcessLookupError:
                pass
        for _ in range(20):
            if not game_pids(project):
                return
            time.sleep(0.5)


def prepare(project, base, harness):
    dst = os.path.join(project, 'src/main/java/com/creatormc/judgementdaymod/selftest')
    os.makedirs(dst, exist_ok=True)
    here = os.path.dirname(os.path.abspath(__file__))
    for f in HARNESS_FILES:
        # The given folder first, then the project's own harness, then this one
        for folder in (harness, os.path.join(project, 'tools', 'selftest'), here):
            if folder and os.path.exists(os.path.join(folder, f)):
                shutil.copy(os.path.join(folder, f), os.path.join(dst, f))
                break
    run = os.path.join(project, 'run')
    os.makedirs(os.path.join(run, 'saves'), exist_ok=True)
    for world, values in WORLDS.items():
        d = os.path.join(run, 'saves', world)
        if os.path.isdir(d):
            shutil.rmtree(d)
        shutil.copytree(base, d)
        lock = os.path.join(d, 'session.lock')
        if os.path.exists(lock):
            os.remove(lock)
        write_config(d, dict(COMMON, **values))
    for f in ('sp.request', 'sp.request.tmp', 'single-out.txt'):
        if os.path.exists(os.path.join(run, f)):
            os.remove(os.path.join(run, f))
    with open(os.path.join(run, 'options.txt'), 'w') as f:
        f.write(OPTIONS)
    with open(os.path.join(run, 'selftest.properties'), 'w') as f:
        f.write('scenario=single\noutput=single-out.txt\nworld2=sptest2\nexpectDay2=%d\nexpectMax2=%d\n'
                % (WORLDS['sptest2']['apocalypseCurrentDay'], WORLDS['sptest2']['apocalypseMaxDays']))
    return run


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument('project')
    ap.add_argument('base_world')
    ap.add_argument('out')
    ap.add_argument('--harness', default=None)
    ap.add_argument('--args-format', default=CLIENT_ARGS)
    ap.add_argument('--display', default=':99')
    ap.add_argument('--timeout', type=int, default=2400)
    a = ap.parse_args()
    project = os.path.abspath(a.project)
    out = os.path.abspath(a.out)
    os.makedirs(out, exist_ok=True)
    for f in os.listdir(out):
        if f.endswith('.png'):
            os.remove(os.path.join(out, f))
    run = prepare(project, os.path.abspath(a.base_world), a.harness)
    display = Display(a.display)

    gradle = ['bash', 'gradlew', 'runClient'] + os.environ.get('GRADLE_ARGS', '').split()
    gradle.append(a.args_format % '--quickPlaySingleplayer sptest --username Tester')
    log = open(os.path.join(out, 'gradle.log'), 'w')
    proc = subprocess.Popen(gradle, cwd=project, env=display.env, stdout=log, stderr=subprocess.STDOUT,
                            stdin=subprocess.DEVNULL, start_new_session=True)
    t0 = time.time()
    focused = False
    done = []
    last_progress = t0
    timed_out = True
    req = os.path.join(run, 'sp.request')
    while time.time() - t0 < a.timeout:
        if proc.poll() is not None:
            timed_out = False
            break
        if not focused and time.time() - t0 > 20:
            ids = display.focus_game()
            if ids:
                focused = True
                print('game window %s after %.0f s' % (ids, time.time() - t0))
                sys.stdout.flush()
        if os.path.exists(req):
            r = open(req).read().strip().split()
            if not focused:
                focused = bool(display.focus_game())
            if r and r[0] == 'click':
                display.click(int(r[1]))
            elif r and r[0] == 'shot':
                display.shot(os.path.join(out, r[1] + '.png'))
            done.append(' '.join(r))
            os.remove(req)
        elif time.time() - last_progress > 60:
            # What the screen shows every minute, to see where a stuck run is
            last_progress = time.time()
            display.shot(os.path.join(out, 'progress.png'))
        time.sleep(0.05)
    if timed_out:
        print('TIMEOUT: stopping the game')
        display.shot(os.path.join(out, 'timeout.png'))
        stop_game(project)
    try:
        proc.wait(120)
    except subprocess.TimeoutExpired:
        stop_game(project)

    res = os.path.join(run, 'single-out.txt')
    text = open(res).read() if os.path.exists(res) else ''
    with open(os.path.join(out, 'single-out.txt'), 'w') as f:
        f.write(text)
    latest = os.path.join(run, 'logs', 'latest.log')
    game_log = ''
    if os.path.exists(latest):
        shutil.copy(latest, os.path.join(out, 'latest.log'))
        game_log = open(latest, errors='replace').read()
    os.remove(os.path.join(run, 'selftest.properties'))

    print('=== %.0f s, %d requests done, exit=%s' % (time.time() - t0, len(done), proc.returncode))
    for line in text.splitlines():
        if re.match(r'PASS|FAIL|RESULT|EXCEPTION|HUD|HEAT|QUIET|LIGHT|SESSION|   at ', line):
            print('  ' + line[:400])
    # What the game saved for the two worlds
    for world in WORLDS:
        c = read_config(os.path.join(run, 'saves', world))
        print('  SAVED %s: %s' % (world, ' '.join('%s=%s' % (k, c.get(k)) for k in SAVED_KEYS)))
    errs = [l for l in game_log.splitlines() if re.search(r'/ERROR\]|/FATAL\]|Exception', l)]
    mod_errs = [l for l in errs if re.search(r'judgementday|JudgementDay|creatormc', l)]
    print('  LOG errors=%d mod_errors=%d stopped=%s' % (len(errs), len(mod_errs), 'Stopping!' in game_log))
    for line in (mod_errs or errs)[:6]:
        print('    ' + line[:260])
    ok = bool(re.search(r'^RESULT fails=0$', text, re.M)) and bool(re.search(r'^RESULT1 fails=0$', text, re.M))
    sys.exit(0 if ok and not timed_out else 1)


if __name__ == '__main__':
    main()
