"""Exercise the real callback client with fake am and host loopback sockets.

No Android commands run. No device or real pairing keys are involved.
"""
import contextlib
import hashlib
import hmac
import io
import json
from pathlib import Path
import runpy
import socket
import struct
import subprocess
import sys
import threading
import time
import unittest
from unittest.mock import patch


SOURCE = Path(__file__).resolve().parents[2] / 'recovery/scripts/phone-settings.py'
GOOD_STATE = {'ok': True, 'wifi': True, 'wireless': 0, 'source': 'genuine'}


def signed_reply(key, state):
    body = json.dumps(state, separators=(',', ':'))
    mac = hmac.new(key, body.encode(), hashlib.sha256).hexdigest()
    return json.dumps({'body': body, 'mac': mac}).encode() + b'\n'


def send(port, data):
    with socket.create_connection(('127.0.0.1', port), timeout=2) as peer:
        peer.sendall(data)


class CallbackTests(unittest.TestCase):
    def invoke(self, peer_action):
        """Let a fake helper observe broadcast arguments, never real am."""
        stop = threading.Event()
        threads = []
        peer_errors = []
        output = io.StringIO()

        def fake_am(args, **kwargs):
            self.assertEqual(args[:2], ['am', 'broadcast'])
            port = int(args[args.index('reply_port') + 1])
            key = bytes.fromhex(args[args.index('reply_key') + 1])

            def act():
                try:
                    peer_action(port, key, stop)
                except BaseException as error:
                    peer_errors.append(error)

            thread = threading.Thread(target=act, daemon=True)
            threads.append(thread)
            thread.start()
            return subprocess.CompletedProcess(args, 0, '', '')

        started = time.monotonic()
        try:
            with patch.object(sys, 'argv', [str(SOURCE), 'STATE']), \
                    patch('subprocess.run', fake_am), \
                    contextlib.redirect_stdout(output):
                with self.assertRaises(SystemExit) as result:
                    runpy.run_path(str(SOURCE), run_name='__main__')
            elapsed = time.monotonic() - started
        finally:
            stop.set()
            for thread in threads:
                thread.join(2)
                self.assertFalse(thread.is_alive(), 'Fake peer did not finish')
        self.assertFalse(peer_errors, str(peer_errors))
        return result.exception.code, output.getvalue(), elapsed

    def assert_genuine(self, peer_action):
        code, output, elapsed = self.invoke(peer_action)
        self.assertEqual(code, 0)
        self.assertEqual(json.loads(output), GOOD_STATE)
        self.assertLess(elapsed, 3)

    def test_malformed_envelopes_then_genuine_reply(self):
        payloads = [
            b'{"body":1,"mac":"x"}\n',
            b'[]\n',
            b'{"body":"{}","mac":1}\n',
            b'{"mac":"x"}\n',
            b'not json\n',
            json.dumps({'body': '{}', 'mac': '\u00e9' * 64}).encode() + b'\n',
            b'x' * 8193,
        ]
        for payload in payloads:
            with self.subTest(kind=payload[:35]):
                def peer_action(port, key, stop):
                    send(port, payload)
                    time.sleep(.02)
                    send(port, signed_reply(key, GOOD_STATE))
                self.assert_genuine(peer_action)

    def test_forged_state_followed_by_genuine_reply(self):
        def peer_action(port, key, stop):
            forged = {'body': json.dumps({'ok': True, 'source': 'forged'}),
                      'mac': '0' * 64}
            send(port, json.dumps(forged).encode() + b'\n')
            time.sleep(.02)
            send(port, signed_reply(key, GOOD_STATE))
        self.assert_genuine(peer_action)

    def test_silent_peer_followed_by_genuine_reply(self):
        def peer_action(port, key, stop):
            with socket.create_connection(('127.0.0.1', port), timeout=2):
                time.sleep(.05)
                send(port, signed_reply(key, GOOD_STATE))
                # The real client must discard this peer at its .5s deadline.
                stop.wait(1)
        self.assert_genuine(peer_action)

    def test_reset_peer_followed_by_genuine_reply(self):
        def peer_action(port, key, stop):
            with socket.create_connection(('127.0.0.1', port), timeout=2) as peer:
                peer.setsockopt(socket.SOL_SOCKET, socket.SO_LINGER,
                                struct.pack('ii', 1, 0))
            time.sleep(.02)
            send(port, signed_reply(key, GOOD_STATE))
        self.assert_genuine(peer_action)

    def test_authenticated_but_invalid_state_then_genuine_reply(self):
        def peer_action(port, key, stop):
            send(port, signed_reply(key, {'ok': 'true', 'source': 'bad shape'}))
            time.sleep(.02)
            send(port, signed_reply(key, GOOD_STATE))
        self.assert_genuine(peer_action)

    def test_authenticated_error_is_returned_as_failure(self):
        state = {'ok': False, 'error': 'synthetic_settings_failure'}
        def peer_action(port, key, stop):
            send(port, signed_reply(key, state))
        code, output, elapsed = self.invoke(peer_action)
        self.assertEqual(code, 1)
        self.assertEqual(json.loads(output), state)
        self.assertLess(elapsed, 3)

    def test_slow_unauthenticated_peers_do_not_extend_total_deadline(self):
        def peer_action(port, key, stop):
            while not stop.is_set():
                try:
                    with socket.create_connection(('127.0.0.1', port), timeout=1) as peer:
                        while not stop.wait(.08):
                            peer.sendall(b' ')
                except OSError:
                    if stop.wait(.01):
                        return
        code, output, elapsed = self.invoke(peer_action)
        self.assertIsInstance(code, str)
        self.assertIn('reply', code)
        self.assertEqual(output, '')
        self.assertGreaterEqual(elapsed, 11.5)
        self.assertLess(elapsed, 13.5)


if __name__ == '__main__':
    unittest.main()
