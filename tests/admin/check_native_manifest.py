#!/usr/bin/env python3
"""Check the generated or merged native XML manifest without building or installing it."""

import argparse
from pathlib import Path
import xml.etree.ElementTree as ET


def check(path: Path) -> None:
    root = ET.parse(path).getroot()
    android = "{http://schemas.android.com/apk/res/android}"
    application = root.find("application")
    assert application is not None, "No application element"
    expected = {
        "com.termux.admin.HostReceiver": "android.permission.DUMP",
        "com.termux.admin.AdminReceiver": "android.permission.BIND_DEVICE_ADMIN",
    }
    for name, permission in expected.items():
        matches = [
            receiver for receiver in application.findall("receiver")
            if receiver.get(android + "name") == name
        ]
        assert len(matches) == 1, f"Expected one declaration for {name}, found {len(matches)}"
        receiver = matches[0]
        assert receiver.get(android + "exported") == "true", f"{name} must be explicitly exported"
        actual = receiver.get(android + "permission")
        assert actual == permission, f"{name} requires {permission}, got {actual!r}"


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument(
        "manifest", nargs="?", type=Path,
        default=Path(__file__).resolve().parents[2]
        / "app/src/main/AndroidManifest.xml",
    )
    args = parser.parse_args()
    check(args.manifest)
    print("Native receiver permission checks passed")
