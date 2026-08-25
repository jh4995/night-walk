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
            # ⚠ **stdin을 물려주지 않는다.** `adb shell`은 stdin을 읽어 기기로 흘려보내므로,
            #   호출자가 파이프로 사람의 응답을 받는 상황이면 그 입력을 통째로 삼킨다.
            #   실제로 `run_session.read_temperature()`(= 이 함수를 부른다) 직후 첫 프롬프트가
            #   즉시 EOF가 되어 세션이 시작하자마자 중단됐다.
            #   `run_session.invoke_child`가 같은 이유로 이미 DEVNULL을 쓴다 — 그 가드가
            #   adb를 직접 부르는 이 경로에만 빠져 있었다.
            stdin=subprocess.DEVNULL,
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


# ── 기기 온도 (`adb shell dumpsys battery`) ─────────────────────────────────
# **왜 필요한가:** 알려진 이슈 23이 "런 안에서 GPU 시간이 +13% 드리프트했는데 발열/DVFS인지
# **온도를 기록하지 않아 판별하지 못했다**"이고, 그 뒤로 두 라운드 연속 누락했다. 계획 파일이
# 사람에게 손으로 적으라고 지시하는 상태였고(s11의 35.1→40.5℃가 그렇게 나왔다) 손 작업은
# 잊힌다 — 그래서 코드로 옮긴다.
#
# 🔴 **배터리 온도이지 SoC/GPU 다이 온도가 아니다.** 열원과 떨어져 있어 늦게 따라오고 폭도
#   작다. 그러므로 이 값으로 "스로틀링이 있었다/없었다"를 단정하지 않는다 — 두 시점의 차가
#   **발열 방향의 증거**일 뿐이다. 다이 온도는 기기·커널마다 경로가 달라
#   (`/sys/class/thermal/thermal_zone*`) 어느 zone이 무엇인지 신고해 주는 표준이 없다.
#   그래서 "어느 기기에서나 같은 뜻으로 읽히는 값"을 택했고, 그 한계를 이름과 문장에 남긴다.
# 🔴 **못 읽으면 None이다. 지어내지 않는다.**
BATTERY_TEMP_COMMAND = ("shell", "dumpsys", "battery")
BATTERY_TEMP_KEY = "temperature"
BATTERY_TEMP_DIVISOR = 10.0  # dumpsys battery는 0.1℃ 단위 정수로 낸다
BATTERY_TEMP_SOURCE = "adb shell dumpsys battery: temperature ÷ 10 = ℃ (**배터리** 온도)"


def battery_temperature_c(adb_path_hint: str = "", serial: str = "") -> dict:
    """기기 배터리 온도(℃). 실패해도 예외를 던지지 않고 `celsius=None`으로 돌려준다.

    반환 dict:
      celsius : float | **None**(못 읽었다 — 지어내지 않는다)
      raw     : `dumpsys`가 준 원문 값 (없으면 None)
      source  : 어떻게 얻은 값인지 (사람이 되물을 수 있게)
      reason  : celsius가 None인 사유 (있으면)
      serial  : 실제로 물어본 기기
    """
    out = {
        "celsius": None,
        "raw": None,
        "source": BATTERY_TEMP_SOURCE,
        "reason": None,
        "serial": serial or None,
        "limits": (
            "배터리 온도이지 SoC/GPU 다이 온도가 아니다 — 열원과 떨어져 있어 늦게 따라오고 "
            "폭도 작다. 두 시점의 차는 **발열 방향의 증거**이며 스로틀링 여부의 판정이 아니다"
        ),
    }
    adb_path = find_adb(adb_path_hint)
    if not adb_path:
        out["reason"] = "adb를 찾지 못함 (ANDROID_HOME 미설정 또는 platform-tools 미설치)"
        return out
    target = serial
    if not target:
        serials = list_devices(adb_path)
        if not serials:
            out["reason"] = "device 상태인 기기 없음 (미연결 / USB 디버깅 미허용)"
            return out
        target = serials[0]
        if len(serials) > 1:
            out["multiple_devices"] = serials
    out["serial"] = target
    text = _adb(adb_path, list(BATTERY_TEMP_COMMAND), serial=target)
    if not text:
        out["reason"] = "dumpsys battery가 출력을 주지 않았다 (권한/기기 상태 확인)"
        return out
    for line in text.splitlines():
        key, _, val = line.partition(":")
        if key.strip() != BATTERY_TEMP_KEY:
            continue
        out["raw"] = val.strip()
        try:
            out["celsius"] = round(int(val.strip()) / BATTERY_TEMP_DIVISOR, 1)
        except ValueError:
            # 숫자가 아니면 **그대로 두고 판단하지 않는다.** raw는 남겼으므로 되물을 수 있다.
            out["reason"] = f"temperature 값을 숫자로 읽을 수 없다: {val.strip()!r}"
        return out
    out["reason"] = (
        f"dumpsys battery 출력에 '{BATTERY_TEMP_KEY}' 줄이 없다 — 이 기기/OS는 그 필드를 "
        f"내지 않는다"
    )
    return out


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
