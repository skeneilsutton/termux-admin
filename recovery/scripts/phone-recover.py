#!/data/data/com.termux/files/usr/bin/python
"""Restore the local privileged watcher using a paired, temporary TLS ADB connection."""
import fcntl
import json
import os
from pathlib import Path
import subprocess
import sys
import time

HOME = Path.home()
STATE = HOME / '.local/state/phone-recovery'
os.umask(0o077)
STATE.mkdir(mode=0o700, parents=True, exist_ok=True)
STATE.chmod(0o700)
# TCP loopback is shared by Android apps. Keep the ADB control socket behind
# Termux's private filesystem boundary, including during the temporary TLS lease.
ADB = ['adb', '-L', 'localfilesystem:'+str(STATE/'adb.sock')]
ATTEMPT_DEADLINE = None

def run(args, timeout=8):
    if ATTEMPT_DEADLINE is not None:
        remaining = ATTEMPT_DEADLINE-time.monotonic()
        if remaining <= 0:
            raise TimeoutError('Recovery lease budget exhausted')
        timeout = min(timeout, remaining)
    return subprocess.run(args, capture_output=True, text=True, timeout=timeout)

def helper(action, timeout=15):
    r = run([str(HOME/'bin/phone-settings'), action], timeout)
    if r.returncode:
        raise RuntimeError(r.stderr.strip() or 'Settings helper failed')
    return json.loads(r.stdout)

def privileged(command, timeout=20):
    result = run([str(HOME/'bin/termux-admin'), 'shell', '--', '/system/bin/sh', '-c', command], timeout)
    if result.returncode:
        raise RuntimeError('Privileged operation failed with exit '+str(result.returncode))
    return result.stdout

def bridge_alive():
    try:
        result = run([str(HOME/'bin/termux-admin'), 'status'], 6)
        return result.returncode == 0 and json.loads(result.stdout).get('shell_uid') == 2000
    except (subprocess.TimeoutExpired, ValueError, OSError):
        return False

def recover():
    global ATTEMPT_DEADLINE
    with (STATE/'attempt.lock').open('w') as lock:
        try:
            fcntl.flock(lock, fcntl.LOCK_EX | fcntl.LOCK_NB)
        except BlockingIOError:
            raise RuntimeError('Recovery already in progress; requested command was not executed')
        if bridge_alive():
            privileged('setsid /system/bin/sh /data/local/tmp/termux-admin-recovery/watch-admin.sh </dev/null >>/data/local/tmp/termux-admin-recovery/watch.log 2>&1 &')
            return True
        if not helper('STATE')['wifi']:
            return False
        endpoint = None
        # The helper's lease lasts 45 seconds. Leave time for verified cleanup.
        ATTEMPT_DEADLINE = time.monotonic()+30
        try:
            helper('ENABLE')
            time.sleep(2)
            ports = helper("DISCOVER").get("ports", [])
            for port in ports:
                target = '127.0.0.1:'+str(port)
                connected = run(ADB+['connect', target], 4)
                if 'connected to '+target not in connected.stdout:
                    continue
                uid = run(ADB+['-s', target, 'shell', 'id', '-u'], 4)
                if uid.returncode == 0 and uid.stdout.strip() == '2000':
                    endpoint = target
                    break
                run(ADB+['disconnect', target], 3)
            if endpoint is None:
                raise RuntimeError('Paired local TLS ADB endpoint unavailable')
            command = ('setsid /system/bin/sh /data/local/tmp/termux-admin-recovery/watch-admin.sh '
                       '</dev/null >>/data/local/tmp/termux-admin-recovery/watch.log 2>&1 &')
            r = run(ADB+['-s', endpoint, 'shell', command], 5)
            if r.returncode:
                raise RuntimeError('Could not start privileged watcher')
            for _ in range(5):
                if bridge_alive():
                    return True
                time.sleep(1)
            raise RuntimeError('Privileged bridge did not return')
        finally:
            ATTEMPT_DEADLINE = None
            try:
                helper('DISABLE', 8)
            finally:
                try:
                    if endpoint:
                        run(ADB+['disconnect', endpoint], 3)
                finally:
                    stopped = run(ADB+['kill-server'], 3)
                    if stopped.returncode:
                        raise RuntimeError('Private ADB server shutdown failed; inspect recovery state')

def log(message):
    path = STATE/'recovery.log'
    if path.exists() and path.stat().st_size > 65536:
        path.write_bytes(path.read_bytes()[-32768:])
    with path.open('a') as out:
        out.write(time.strftime('%Y-%m-%dT%H:%M:%S%z')+' '+message+'\n')

if sys.argv[1:] == ['--once']:
    try:
        ok = recover()
        print('Privileged access ready' if ok else 'Recovery needs a trusted Wi-Fi connection')
        raise SystemExit(0 if ok else 1)
    except Exception as error:
        log(str(error))
        raise SystemExit(str(error))
elif sys.argv[1:] == ['--loop']:
    with (STATE/'loop.lock').open('a+') as lock:
        try:
            fcntl.flock(lock, fcntl.LOCK_EX | fcntl.LOCK_NB)
        except BlockingIOError:
            raise SystemExit(0)
        lock.seek(0); lock.truncate(); lock.write(str(os.getpid())); lock.flush()
        log('Recovery loop started')
        while True:
            delay = 30
            try:
                if recover():
                    delay = 300
            except Exception as error:
                log(str(error))
                delay = 900
            time.sleep(delay)
else:
    raise SystemExit('Usage: phone-recover --once|--loop')
