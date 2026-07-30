"""실기기 메타 수집 (adb).

android-runtime 스킬 §3: "기기·설정 메타를 함께 남긴다. run_meta.json과 같은 역할이다."
기기가 다르면 숫자를 비교할 수 없으므로, 어느 기기에서 잰 값인지가 숫자의 일부다.

adb가 없거나 기기가 안 붙어 있어도 **죽지 않는다.** 대신 available=False로 남긴다 —
측정이 불가능했다는 사실 자체가 기록돼야 한다.
"""

from __future__ import annotations

import os
import shutil
import subprocess
from pathlib import Path
from typing import Optional

# 이 목록이 곧 "기기가 다르면 숫자가 달라지는 항목"이다.
PROPS = {
    "manufacturer": "ro.product.manufacturer",
    "model": "ro.product.model",
    "device": "ro.product.device",
    "android_release": "ro.build.version.release",
    "android_sdk": "ro.build.version.sdk",
    "soc_manufacturer": "ro.soc.manufacturer",
    "soc_model": "ro.soc.model",
    "cpu_abi": "ro.product.cpu.abi",
    "build_fingerprint": "ro.build.fingerprint",
}

_ADB_TIMEOUT = 15


def find_adb(explicit: str = "") -> Optional[str]:
    """adb 경로를 찾는다. 우선순위: 인자 > PATH > ANDROID_HOME > 윈도우 기본 경로."""
    if explicit:
        return explicit if Path(explicit).exists() else None

    found = shutil.which("adb")
    if found:
        return found

    for env_name in ("ANDROID_HOME", "ANDROID_SDK_ROOT"):
        root = os.environ.get(env_name, "")
        if root:
            for name in ("adb.exe", "adb"):
                cand = Path(root) / "platform-tools" / name
                if cand.exists():
                    return str(cand)

    local = os.environ.get("LOCALAPPDATA", "")
    if local:
        cand = Path(local) / "Android" / "Sdk" / "platform-tools" / "adb.exe"
        if cand.exists():
            return str(cand)
    return None


def _adb(adb_path: str, args: list[str], serial: str = "") -> Optional[str]:
    cmd = [adb_path]
    if serial:
        cmd += ["-s", serial]
    cmd += args
    try:
        proc = subprocess.run(
            cmd,
            check=True,
            stdout=subprocess.PIPE,
            stderr=subprocess.DEVNULL,
            text=True,
            timeout=_ADB_TIMEOUT,
        )
        return proc.stdout.strip()
    except (FileNotFoundError, subprocess.CalledProcessError, subprocess.TimeoutExpired):
        return None


def list_devices(adb_path: str) -> list[str]:
    """`device` 상태인 시리얼만 돌려준다. unauthorized/offline은 제외."""
    out = _adb(adb_path, ["devices"])
    if not out:
        return []
    serials = []
    for line in out.splitlines()[1:]:
        parts = line.split()
        if len(parts) >= 2 and parts[1] == "device":
            serials.append(parts[0])
    return serials


def collect(adb_path_hint: str = "", serial: str = "") -> dict:
    """기기 메타를 dict로. 실패해도 예외를 던지지 않는다."""
    meta: dict = {"available": False, "reason": None, "serial": None, "props": {}}

    adb_path = find_adb(adb_path_hint)
    if not adb_path:
        meta["reason"] = "adb를 찾지 못함 (ANDROID_HOME 미설정 또는 platform-tools 미설치)"
        return meta
    meta["adb_path"] = adb_path

    serials = list_devices(adb_path)
    if not serials:
        meta["reason"] = "device 상태인 기기 없음 (미연결 / USB 디버깅 미허용 / unauthorized)"
        return meta

    target = serial or serials[0]
    if serial and serial not in serials:
        meta["reason"] = f"지정한 serial({serial})이 device 상태가 아님. 연결된 것: {serials}"
        return meta
    if len(serials) > 1 and not serial:
        meta["multiple_devices"] = serials

    for key, prop in PROPS.items():
        val = _adb(adb_path, ["shell", "getprop", prop], serial=target)
        meta["props"][key] = val if val else None

    meta["available"] = True
    meta["serial"] = target
    return meta


def describe(meta: dict) -> str:
    """로그 한 줄용 요약."""
    if not meta.get("available"):
        return f"기기 메타 없음 — {meta.get('reason')}"
    p = meta.get("props", {})
    return (
        f"{p.get('manufacturer')} {p.get('model')} / "
        f"SoC {p.get('soc_manufacturer')} {p.get('soc_model')} / "
        f"Android {p.get('android_release')} (API {p.get('android_sdk')}) / "
        f"{p.get('cpu_abi')}"
    )
