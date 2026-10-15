"""Run the real recovery entry point with synthetic helper and ADB processes."""
import contextlib
import io
import json
from pathlib import Path
import runpy
import subprocess
import sys
import tempfile
import unittest
from unittest.mock import patch

SOURCE = Path(__file__).resolve().parents[2] / 'recovery/scripts/phone-recover.py'


class RecoveryTests(unittest.TestCase):
    def recover(self, disable_fails=False, shutdown_fails=False):
        calls = []
        probes = 0
        with tempfile.TemporaryDirectory(prefix='termux-recovery-test-') as temp:
            home = Path(temp)

            def fake_run(args, **kwargs):
                nonlocal probes
                calls.append(args)
                code = 0; output = ''
                if Path(args[0]).name == 'termux-admin':
                    self.assertEqual(args[1:], ['status'])
                    probes += 1
                    output = json.dumps({'shell_uid': -1 if probes == 1 else 2000})
                elif Path(args[0]).name == 'phone-settings':
                    action = args[1]
                    if action == 'STATE': output = json.dumps({'wifi': True})
                    elif action == 'DISCOVER': output = json.dumps({'ports': [12345]})
                    elif action == 'DISABLE' and disable_fails: code = 1
                    else: output = json.dumps({'ok': True})
                else:
                    self.assertEqual(args[:3], ['adb', '-L',
                        'localfilesystem:' + str(home / '.local/state/phone-recovery/adb.sock')])
                    if args[3] == 'connect': output = 'connected to 127.0.0.1:12345'
                    elif args[-2:] == ['id', '-u']: output = '2000\n'
                    elif args[3] == 'kill-server' and shutdown_fails: code = 1
                return subprocess.CompletedProcess(args, code, output, 'synthetic failure' if code else '')

            with patch.object(sys, 'argv', [str(SOURCE), '--once']), \
                    patch('pathlib.Path.home', return_value=home), \
                    patch('subprocess.run', fake_run), patch('time.sleep'), \
                    contextlib.redirect_stdout(io.StringIO()):
                with self.assertRaises(SystemExit) as result:
                    runpy.run_path(str(SOURCE), run_name='__main__')
            return result.exception.code, calls

    def test_success_disables_debugging_and_stops_private_adb(self):
        code, calls = self.recover()
        self.assertEqual(code, 0)
        disable = next(i for i, c in enumerate(calls) if c[-1] == 'DISABLE')
        shutdown = next(i for i, c in enumerate(calls) if c[-1] == 'kill-server')
        self.assertLess(disable, shutdown)

    def test_disable_failure_still_stops_private_adb(self):
        code, calls = self.recover(disable_fails=True)
        self.assertIsInstance(code, str)
        self.assertIn('synthetic failure', code)
        self.assertEqual(calls[-1][-1], 'kill-server')

    def test_shutdown_failure_is_not_reported_as_success(self):
        code, calls = self.recover(shutdown_fails=True)
        self.assertIsInstance(code, str)
        self.assertIn('shutdown failed', code)
        self.assertTrue(any(c[-1] == 'DISABLE' for c in calls))


if __name__ == '__main__':
    unittest.main()
