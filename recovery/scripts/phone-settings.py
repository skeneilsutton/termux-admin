#!/data/data/com.termux/files/usr/bin/python
"""One-shot loopback reply authenticated by a fresh broadcast-only secret."""
import json
import secrets
import socket
import hmac
import hashlib
import subprocess
import sys
import time

if len(sys.argv) != 2 or sys.argv[1] not in {"STATE", "ENABLE", "DISABLE", "DISCOVER"}:
    raise SystemExit("Usage: phone-settings STATE|ENABLE|DISABLE|DISCOVER")
key = secrets.token_bytes(32)
with socket.socket(socket.AF_INET, socket.SOCK_STREAM) as listener:
    listener.bind(("127.0.0.1",0))
    listener.listen(4)
    listener.settimeout(12)
    request = subprocess.run(["am", "broadcast", "--user", "0", "--receiver-foreground",
        "-a", "com.termux.admin.recovery."+sys.argv[1], "-n",
        "com.termux.admin.recovery/.ControlReceiver", "--ei", "reply_port", str(listener.getsockname()[1]), "--es", "reply_key", key.hex()],
        capture_output=True, text=True, timeout=8)
    if request.returncode:
        print(request.stderr or request.stdout, file=sys.stderr)
        raise SystemExit(1)
    deadline = time.monotonic()+12
    while time.monotonic() < deadline:
        listener.settimeout(max(0.001, deadline-time.monotonic()))
        try:
            client, _ = listener.accept()
        except socket.timeout:
            raise SystemExit("The recovery helper did not return an authenticated reply.")
        with client:
            try:
                peer_deadline = min(deadline, time.monotonic()+0.5)
                chunks = bytearray()
                while len(chunks) <= 8192:
                    client.settimeout(max(0.001, peer_deadline-time.monotonic()))
                    part = client.recv(min(1024, 8193-len(chunks)))
                    if not part: break
                    chunks.extend(part)
                    if b"\n" in chunks: break
                    if time.monotonic() >= peer_deadline: break
                if len(chunks) > 8192:
                    continue
                reply = json.loads(chunks)
                if not isinstance(reply, dict):
                    continue
                body = reply["body"]
                mac = reply["mac"]
                if not isinstance(body, str) or not isinstance(mac, str) or len(mac) != 64:
                    continue
                if not hmac.compare_digest(mac, hmac.new(key,body.encode(),hashlib.sha256).hexdigest()):
                    continue
                state = json.loads(body)
                if not isinstance(state, dict) or not isinstance(state.get("ok"), bool):
                    continue
            except (ValueError, KeyError, TypeError, OSError):
                continue
            print(json.dumps(state, separators=(",", ":")))
            raise SystemExit(0 if state.get("ok") is True else 1)
raise SystemExit("The recovery helper reply deadline expired.")
