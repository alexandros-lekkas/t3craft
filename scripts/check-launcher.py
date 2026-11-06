#!/usr/bin/env python3
"""Exercise the launcher lifecycle with disposable console servers, no Minecraft or user worlds."""
import os
from pathlib import Path
import shutil
import signal
import socket
import subprocess
import tempfile
import time


used_ports = set()

def port():
    with socket.socket() as s:
        s.bind(('127.0.0.1', 0))
        number = s.getsockname()[1]
        if number in used_ports:
            return port()
        used_ports.add(number)
        return number


def listening(number):
    try:
        with socket.create_connection(('127.0.0.1', number), timeout=.2):
            return True
    except OSError:
        return False


def wait_for(check):
    for _ in range(100):
        if check():
            return
        time.sleep(.1)
    raise RuntimeError('launcher fixture timed out')


with tempfile.TemporaryDirectory(prefix='t3craft-launcher-check-') as folder:
    root = Path(folder)
    (root / 'scripts').mkdir()
    shutil.copy2(Path(__file__).with_name('launch-macos.sh'), root / 'scripts/launch-macos.sh')
    (root / 'bin').mkdir()
    lock_root = root / 'locks'; lock_root.mkdir()

    def run_launcher(extra_env=None, expected=0):
        # Startup allows 180s; graceful stop and TERM escalation allow another 25s.
        # The old 20s deadline could kill the launcher before its cleanup completed.
        with subprocess.Popen(['bash', str(root / 'scripts/launch-macos.sh')],
                              env={**env, **(extra_env or {})}, stdout=subprocess.PIPE,
                              stderr=subprocess.PIPE, text=True, start_new_session=True) as process:
            try:
                stdout, stderr = process.communicate(timeout=220)
            except subprocess.TimeoutExpired:
                os.killpg(process.pid, signal.SIGTERM)
                try:
                    stdout, stderr = process.communicate(timeout=30)
                except subprocess.TimeoutExpired:
                    os.killpg(process.pid, signal.SIGKILL)
                    stdout, stderr = process.communicate()
                raise AssertionError('launcher timed out\n' + diagnostics(stdout, stderr))
        assert process.returncode == expected, (
            f'expected exit {expected}, got {process.returncode}\n' + diagnostics(stdout, stderr))

    def diagnostics(stdout, stderr):
        logs = ''.join(f'\n{p.name}:\n{p.read_text()}' for p in (root / 'logs').glob('*.log'))
        return f'stdout:\n{stdout}\nstderr:\n{stderr}' + logs

    for name, body in [('osascript', 'exit 0'), ('pgrep', 'exit 1')]:
        p = root / 'bin' / name
        p.write_text('#!/bin/sh\n' + body + '\n')
        p.chmod(0o755)
    (root / 'jdk/bin').mkdir(parents=True)
    (root / 'jdk/bin/java').write_text('#!/bin/sh\necho \'openjdk version "25.0.1"\' >&2\n')
    (root / 'jdk/bin/java').chmod(0o755)
    server = root / 'console.py'
    server.write_text('''import socket,sys,threading
from pathlib import Path
with socket.socket() as listener:
    listener.setsockopt(socket.SOL_SOCKET,socket.SO_REUSEADDR,1)
    listener.bind(('127.0.0.1',int(sys.argv[1])))
    listener.listen()
    listener.settimeout(.1)
    done = threading.Event()
    def accept_connections():
        while not done.is_set():
            try:
                connection, _ = listener.accept()
                connection.close()
            except socket.timeout:
                pass
    worker = threading.Thread(target=accept_connections)
    worker.start()
    for line in sys.stdin:
        if line.strip() == 'stop':
            Path(sys.argv[2]).write_text('saved and stopped')
            break
    done.set()
    worker.join()
''')
    wrapper = root / 'gradlew'
    wrapper.write_text('''#!/usr/bin/env python3
import os,sys,time
from pathlib import Path
args=sys.argv[1:]
if args[0] == 'runClient':
    import socket
    number=int(next(x.split(':')[-1] for x in args if x.startswith('-Pjoin=')))
    with socket.create_connection(('127.0.0.1', number), timeout=2): pass
    sys.exit(int(os.environ.get('FIXTURE_CLIENT_EXIT','0')))
world=Path(next(x.split('=',1)[1] for x in args if x.startswith('-PserverDir=')))
number=next(x.split('=',1)[1] for x in (world/'server.properties').read_text().splitlines() if x.startswith('server-port='))
marker=next(x.split('=',1)[1] for x in args if x.startswith('-PlauncherId='))
(world/'started-pid').write_text(str(os.getpid()))
os.execv(sys.executable,[sys.executable,str(Path(__file__).with_name('console.py')),number,str(world/'stopped'), 'java', '-Ddli.env=server', '-Dt3craft.launcherId='+marker])
''')
    wrapper.chmod(0o755)
    # Reproduce the pre-exec window: the owned child is alive but its command
    # line does not yet contain Gradle's launcher marker.
    boot = root / 'boot.py'
    boot.write_text('''import json,os,time
from pathlib import Path
time.sleep(.3)
args=json.loads(Path(__file__).with_name('boot-args.json').read_text())
os.execvp(args[0], args)
''')
    nohup = root / 'bin/nohup'
    nohup.write_text('''#!/usr/bin/env python3
import json,os,sys
from pathlib import Path
root=Path(__file__).resolve().parents[1]
(root/'boot-args.json').write_text(json.dumps(sys.argv[1:]))
os.execv(sys.executable,[sys.executable,str(root/'boot.py')])
''')
    nohup.chmod(0o755)
    env = {**os.environ, 'PATH': str(root / 'bin') + ':' + os.environ['PATH'], 'JAVA_HOME': str(root / 'jdk'),
           'TMPDIR': str(lock_root), 'T3CRAFT_LOG_DIR': str(root / 'logs')}
    # Reject a preset old JDK before starting a server, and serialize simultaneous launchers.
    java = root / 'jdk/bin/java'
    java.write_text('#!/bin/sh\necho \'openjdk version "24.0.1"\' >&2\n')
    run_launcher(expected=1)
    java.write_text('#!/bin/sh\necho \'openjdk version "25.0.1"\' >&2\n')
    lock = lock_root / ('t3craft-launcher-' + str(os.getuid()) + '.lock'); lock.mkdir()
    run_launcher(expected=1)
    assert lock.is_dir(), 'second launch did not respect the lock'
    lock.rmdir()
    peers = []
    try:
        # A second console server intentionally matches the old broad pkill expression.
        peer_port = port()
        peer = subprocess.Popen(['python3', str(server), str(peer_port), str(root / 'peer-stopped'), 'java', '-Ddli.env=server'], stdin=subprocess.PIPE)
        peers.append(peer)
        wait_for(lambda: listening(peer_port))
        for exit_code in [0, 7]:
            world = root / ('world-' + str(exit_code)); world.mkdir()
            number = port(); (world / 'server.properties').write_text('server-port=' + str(number) + '\n')
            profile = root / 'scripts/check.local'
            profile.write_text('T3CRAFT_SERVER_DIR="' + str(world) + '"\nT3CRAFT_LOG_DIR="' + str(root / 'logs') + '"\n')
            run_launcher({'T3CRAFT_PROFILE': str(profile), 'FIXTURE_CLIENT_EXIT': str(exit_code)}, expected=exit_code)
            assert (world / 'stopped').read_text() == 'saved and stopped', 'owned server did not receive stop'
            assert not listening(number), 'owned server remained listening'
            assert peer.poll() is None and listening(peer_port), 'unrelated server was stopped'
        profile.write_text('T3CRAFT_SERVER_DIR="' + str(root / 'reuse') + '"\nT3CRAFT_LOG_DIR="' + str(root / 'logs') + '"\n')
        (root / 'reuse').mkdir(); (root / 'reuse/server.properties').write_text('server-port=' + str(peer_port) + '\n')
        run_launcher({'T3CRAFT_PROFILE': str(profile)})
        assert peer.poll() is None and listening(peer_port), 'reused server was stopped'
        print('PASS launcher owns only its new server, saves on client success/failure, and preserves reused/unrelated servers')
    finally:
        for receipt in root.glob('world-*/started-pid'):
            pid = int(receipt.read_text())
            command = subprocess.run(['ps', '-p', str(pid), '-o', 'command='], capture_output=True, text=True).stdout
            if str(root / 'console.py') in command:
                try: os.kill(pid, 15)
                except ProcessLookupError: pass
        for process in peers:
            if process.poll() is None:
                process.stdin.write(b'stop\n'); process.stdin.flush()
                try: process.wait(timeout=5)
                except subprocess.TimeoutExpired: process.terminate(); process.wait(timeout=5)
