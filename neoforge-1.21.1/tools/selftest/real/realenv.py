"""Real servers and real clients of the three loaders, for the tests of this folder (see README.md).

Set up once, with environment variables:
  JD_WORK              folder of the test worlds and results (default: work/ next to this file)
  JD_SERVER_FORGE      installed Forge 1.20.1 server (run.sh), the mod's jar in mods/
  JD_SERVER_NEOFORGE   installed NeoForge 1.21.1 server (run.sh), the mod's jar in mods/
  JD_SERVER_FABRIC     Fabric 1.20.1 server (fabric-server.jar), Fabric API and the mod's jar in mods/
  JD_PROJECT_FORGE, JD_PROJECT_NEOFORGE, JD_PROJECT_FABRIC
                       the three projects, for the dev clients (default: the folders of this repository)
  JD_JAVA17, JD_JAVA21 JDK homes (default: /usr/lib/jvm/java-17-openjdk-amd64 and java-21-openjdk-amd64)
  JD_DISPLAY           X display of the clients (default :99; Xvfb is started there if nothing answers)
  GRADLE_ARGS          extra arguments for gradlew (e.g. --offline)

The servers are driven through RCON, the clients with xdotool (real mouse clicks and keys) and
ImageMagick's import (screenshots). One test at a time: they share the display and the ports.
"""
import os
import re
import shutil
import socket
import struct
import subprocess
import sys
import time

HERE = os.path.dirname(os.path.abspath(__file__))
REPO = os.path.abspath(os.path.join(HERE, '..', '..', '..', '..'))
WORK = os.path.abspath(os.environ.get('JD_WORK', os.path.join(HERE, 'work')))
J17 = os.environ.get('JD_JAVA17', '/usr/lib/jvm/java-17-openjdk-amd64')
J21 = os.environ.get('JD_JAVA21', '/usr/lib/jvm/java-21-openjdk-amd64')
DISPLAY = os.environ.get('JD_DISPLAY', ':99')
LOADERS = ('forge', 'neo', 'fabric')

SERVERS = {
    'forge': dict(dir=os.environ.get('JD_SERVER_FORGE', WORK + '/server-forge'), java=J17,
                  cmd=['bash', 'run.sh', 'nogui'], port=25590, rcon=25591),
    'neo': dict(dir=os.environ.get('JD_SERVER_NEOFORGE', WORK + '/server-neoforge'), java=J21,
                cmd=['bash', 'run.sh', 'nogui'], port=25580, rcon=25575),
    'fabric': dict(dir=os.environ.get('JD_SERVER_FABRIC', WORK + '/server-fabric'), java=J17,
                   cmd=[J17 + '/bin/java', '-Xmx3G', '-jar', 'fabric-server.jar', 'nogui'], port=25583, rcon=25578),
}
# How each project's runClient task takes the game arguments
CLIENTS = {
    'forge': dict(dir=os.environ.get('JD_PROJECT_FORGE', REPO + '/forge-1.20.1'), java=J17, args='--args=%s'),
    'neo': dict(dir=os.environ.get('JD_PROJECT_NEOFORGE', REPO + '/neoforge-1.21.1'), java=J21, args='-PclientArgs=%s'),
    'fabric': dict(dir=os.environ.get('JD_PROJECT_FABRIC', REPO + '/fabric-1.20.1'), java=J21, args='--args=%s'),
}
CONFIG = 'serverconfig/judgementdaymod-apocalypse.properties'
# The client joins at once, keeps running without the focus and is silent
OPTIONS = ('onboardAccessibility:false\npauseOnLostFocus:false\nsoundCategory_master:0.0\nrenderDistance:6\n'
           'tutorialStep:none\njoinedFirstServer:true\nskipMultiplayerWarning:true\n')
PROCS = {}


# ---------------------------------------------------------------- servers

def rcon(cmds, port, password='test'):
    """Runs the commands on the server and returns their answers."""
    s = socket.create_connection(('127.0.0.1', port), timeout=60)

    def send(i, kind, body):
        b = body.encode() + b'\x00\x00'
        s.sendall(struct.pack('<iii', len(b) + 8, i, kind) + b)
        size = struct.unpack('<i', s.recv(4))[0]
        data = b''
        while len(data) < size:
            data += s.recv(size - len(data))
        return data[8:-2].decode(errors='replace')

    send(1, 3, password)
    out = [send(2, 2, c) for c in cmds]
    s.close()
    return out


def world(loader):
    return SERVERS[loader]['dir'] + '/world'


def set_world(loader, src):
    """Replaces the server's world with a copy of src (None: no world, a new one is generated)."""
    w = world(loader)
    # Only ever the world folder of one of the test servers
    assert w == os.path.join(SERVERS[loader]['dir'], 'world') and os.path.isfile(SERVERS[loader]['dir'] + '/eula.txt'), w
    if os.path.isdir(w):
        shutil.rmtree(w)
    if src:
        shutil.copytree(src, w)
        lock = w + '/session.lock'
        if os.path.exists(lock):
            os.remove(lock)


def save_world(loader, dst):
    """Keeps a copy of the server's world in the work folder."""
    dst = os.path.abspath(dst)
    assert dst.startswith(WORK + os.sep) and os.path.basename(dst).startswith('w_'), dst
    if os.path.isdir(dst):
        shutil.rmtree(dst)
    shutil.copytree(world(loader), dst)


def get_config(world_dir):
    d = {}
    p = os.path.join(world_dir, CONFIG)
    if os.path.exists(p):
        for line in open(p).read().splitlines():
            if '=' in line and not line.startswith('#'):
                k, v = line.split('=', 1)
                d[k] = v
    return d


def set_config(world_dir, **values):
    """Sets keys of the world's apocalypse config (creates it if missing)."""
    p = os.path.join(world_dir, CONFIG)
    os.makedirs(os.path.dirname(p), exist_ok=True)
    lines = open(p).read().splitlines() if os.path.exists(p) else []
    out, seen = [], set()
    for line in lines:
        k = line.split('=', 1)[0]
        if k in values:
            out.append('%s=%s' % (k, values[k]))
            seen.add(k)
        else:
            out.append(line)
    out += ['%s=%s' % (k, v) for k, v in values.items() if k not in seen]
    with open(p, 'w') as f:
        f.write('\n'.join(out) + '\n')


def start(loader, timeout=600):
    """Starts the server and waits until it accepts players."""
    s = SERVERS[loader]
    with open(s['dir'] + '/server.properties', 'w') as f:
        f.write('online-mode=false\nenable-rcon=true\nrcon.password=test\nrcon.port=%d\nserver-port=%d\n'
                'level-seed=1234567\nview-distance=6\nspawn-protection=0\nmax-tick-time=-1\nlevel-name=world\n'
                % (s['rcon'], s['port']))
    with open(s['dir'] + '/eula.txt', 'w') as f:
        f.write('eula=true\n')
    if loader != 'fabric':
        with open(s['dir'] + '/user_jvm_args.txt', 'w') as f:
            f.write('-Xmx3G\n')
    env = dict(os.environ, JAVA_HOME=s['java'], PATH=s['java'] + '/bin:' + os.environ['PATH'])
    log = open(s['dir'] + '/console.log', 'w')
    p = subprocess.Popen(s['cmd'], cwd=s['dir'], env=env, stdout=log, stderr=subprocess.STDOUT,
                         stdin=subprocess.DEVNULL, start_new_session=True)
    PROCS[loader] = p
    t0 = time.time()
    while time.time() - t0 < timeout:
        txt = console(loader)
        if 'Done (' in txt:
            return
        if p.poll() is not None:
            raise RuntimeError('%s server exited: %s' % (loader, txt[-3000:]))
        time.sleep(1)
    raise RuntimeError('%s server did not start' % loader)


def cmd(loader, *cmds):
    return rcon(list(cmds), SERVERS[loader]['rcon'])


def stop(loader, timeout=180):
    try:
        cmd(loader, 'stop')
    except Exception as e:
        print('stop:', e)
    p = PROCS.get(loader)
    if p:
        try:
            p.wait(timeout)
        except subprocess.TimeoutExpired:
            p.kill()
    time.sleep(2)


def console(loader):
    return open(SERVERS[loader]['dir'] + '/console.log', errors='replace').read()


def server_errors(loader):
    return [l for l in console(loader).splitlines() if re.search(r'ERROR|Exception', l) and not re.search(r'[Rr][Cc][Oo][Nn]', l)]


def wait_for(loader, test, tries=90):
    """Waits until the 'execute if ...' command passes."""
    answer = ''
    for _ in range(tries):
        answer = cmd(loader, test)[0]
        if 'passed' in answer:
            return
        time.sleep(1)
    raise RuntimeError('never true: %s -> %s' % (test, answer))


# ---------------------------------------------------------------- real clients (dev clients on the display)

def xdo(*args):
    return subprocess.run(['xdotool'] + [str(a) for a in args], env=dict(os.environ, DISPLAY=DISPLAY),
                          capture_output=True, text=True)


def display():
    """Makes sure an X server answers on the display (starts Xvfb there if none does)."""
    if xdo('getdisplaygeometry').returncode == 0:
        return
    # Nothing answers: the lock of a display that is gone (a reboot) would keep Xvfb from starting
    number = DISPLAY.lstrip(':').split('.')[0]
    for stale in ('/tmp/.X%s-lock' % number, '/tmp/.X11-unix/X%s' % number):
        if os.path.exists(stale):
            os.remove(stale)
    subprocess.Popen(['Xvfb', DISPLAY, '-screen', '0', '1280x720x24'], stdout=subprocess.DEVNULL,
                     stderr=subprocess.DEVNULL, start_new_session=True)
    for _ in range(40):
        time.sleep(0.5)
        if xdo('getdisplaygeometry').returncode == 0:
            return
    raise RuntimeError('no X server on display ' + DISPLAY)


def client_log_path(loader):
    return os.path.join(WORK, 'client_%s.log' % loader)


def client_log(loader):
    return open(client_log_path(loader), errors='replace').read()


def client_pids(loader):
    d = CLIENTS[loader]['dir']
    out = subprocess.run(['ps', '-eo', 'pid,args'], capture_output=True, text=True).stdout
    return [int(l.split()[0]) for l in out.splitlines() if d in l and 'java' in l and 'ps -eo' not in l]


def client_start(loader, game_args):
    """Starts the dev client of the loader with the given game arguments (not waiting for it)."""
    display()
    c = CLIENTS[loader]
    os.makedirs(c['dir'] + '/run', exist_ok=True)
    with open(c['dir'] + '/run/options.txt', 'w') as f:
        f.write(OPTIONS)
    # No self test scenario in a client that only plays
    props = c['dir'] + '/run/selftest.properties'
    if os.path.exists(props):
        os.remove(props)
    os.makedirs(WORK, exist_ok=True)
    env = dict(os.environ, JAVA_HOME=c['java'], DISPLAY=DISPLAY)
    gradle = ['bash', 'gradlew', 'runClient'] + os.environ.get('GRADLE_ARGS', '').split() + [c['args'] % game_args]
    return subprocess.Popen(gradle, cwd=c['dir'], env=env, stdout=open(client_log_path(loader), 'w'),
                            stderr=subprocess.STDOUT, stdin=subprocess.DEVNULL, start_new_session=True)


def client_stop(loader):
    for sig in (15, 9):
        for pid in client_pids(loader):
            try:
                os.kill(pid, sig)
            except ProcessLookupError:
                pass
        for _ in range(20):
            if not client_pids(loader):
                return
            time.sleep(0.5)


def join(loader, name='Tester', timeout=600):
    """Starts the client, joins the loader's server and waits until the player is in."""
    client_start(loader, '--quickPlayMultiplayer localhost:%d --username %s' % (SERVERS[loader]['port'], name))
    t0 = time.time()
    while time.time() - t0 < timeout:
        if '%s joined the game' % name in console(loader):
            return
        if 'BUILD FAILED' in client_log(loader):
            raise RuntimeError('client failed: ' + client_log(loader)[-1500:])
        time.sleep(2)
    raise RuntimeError('client did not join')


def focus_game():
    """The pointer in the middle of the game window and the input focus on it (no window manager is
    needed). Before the first click: once the game has grabbed the mouse the pointer must not move."""
    best = None
    for w in xdo('search', '--name', 'Minecraft').stdout.split():
        g = dict(l.split('=') for l in xdo('getwindowgeometry', '--shell', w).stdout.splitlines() if '=' in l)
        try:
            size = int(g['WIDTH']) * int(g['HEIGHT'])
        except (KeyError, ValueError):
            continue
        if best is None or size > best[0]:
            best = (size, w, int(g['X']) + int(g['WIDTH']) // 2, int(g['Y']) + int(g['HEIGHT']) // 2)
    if best is None:
        return 0
    xdo('mousemove', best[2], best[3])
    xdo('windowfocus', best[1])
    return 1


def click(button=3):
    """A real mouse click where the pointer is (3 = right, 1 = left): press and release sent together,
    so the game sees exactly one click whatever its frame rate."""
    xdo('click', button)


def shot(path):
    subprocess.run(['import', '-display', DISPLAY, '-window', 'root', path], capture_output=True)
    return path


# ---------------------------------------------------------------- saved worlds

sys.path.insert(0, os.path.dirname(HERE))
import worlddiff  # noqa: E402  (the Anvil reader of the self tests)


def saved_blocks(world_dir, positions):
    """The block states saved at the given positions."""
    out, cache = {}, {}
    for (x, y, z) in positions:
        cx, cz = x >> 4, z >> 4
        if (cx, cz) not in cache:
            def want(a, b, region, cx=cx, cz=cz):
                return (a == cx >> 5 and b == cz >> 5) if region else (a == cx and b == cz)
            cache[(cx, cz)] = worlddiff.load_world(world_dir, 'region', want).get((cx, cz))
        tag = cache[(cx, cz)]
        name = 'no chunk' if tag is None else 'no section'
        for sec in (tag or {}).get('sections', []):
            if sec.get('Y') == y >> 4:
                blocks = worlddiff.section_blocks(sec, set())
                name = blocks[((y & 15) << 8) | ((z & 15) << 4) | (x & 15)] if blocks else 'minecraft:air'
        out[(x, y, z)] = name
    return out
