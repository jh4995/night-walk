"""③ 좌표 사슬 왕복 검사 — **합성 데이터, 실기기 없이**.

`scripts/detect_parity.py`의 후처리 역변환(letterbox 역 → **회전 역**)이 원본 좌표로
정확히 돌아오는지를 **실기기가 오기 전에** 확인한다. 좌표계 규약은
`docs/plans/20260806_detect_parity_dump_format.md` §5이며 **어긋나면 그 문서가 맞다.**

    python scripts/detect_parity_roundtrip.py
    python scripts/detect_parity_roundtrip.py --no_outputs

## 🔴 이것이 실측이 아니다

여기서 나오는 숫자는 **합성 좌표로 태운 로직 검증**이다. 폰이 같은 답을 내는지는 이 스크립트가
말하지 못한다 — 그건 `detect_parity.py`가 실기기 덤프로 답한다. 이 검사가 통과했다고
"③ 이식이 맞다"고 쓰면 틀린다.

## 🔴 사본을 만들지 않는다

역변환은 **`detect_parity.py`가 실제로 쓰는 함수를 import 해서** 태운다
(`RotationMap.invert_box`·`letterbox`·`postprocess`). 사본을 검증하면 아무것도 검증하지 않은
것이다. 반대로 **정변환(센서 → 회전 후 → letterbox 640)은 이 파일이 따로 쓴다** — 역함수를
역함수로 검사하면 순환이라 둘 다 틀려도 통과한다.

## 🔴 허용오차를 지어내지 않는다

우리에게 고유한 좌표 허용오차가 없다. 그래서 **관측값(`max|d|`)을 먼저** 내고, 바가 필요하면
상류가 자기 대조에 쓴 것을 **빌려 쓰고 빌린 것임을 밝힌다**
(`models/det_c4b_loli0_640/metadata.json`의 `parity_check`. `detect_parity.py` §7과 같은 방식).
🔴 **빌린 바는 종료 코드를 흔들지 않는다.** 종료 코드를 흔드는 것은 구조적 불변식뿐이다:
**규약 §5-1의 프레임 전체 박스 검산** · 박스 수가 보존되는가 · 클램프가 되살아나지 않았는가 ·
역전 박스가 조용히 고쳐지지 않는가 · 모르는 회전각을 거부하는가 · §4-2 게이트가 세 경우를
가르는가.

## 🔴 왕복이 통과해도 규약은 깨질 수 있다

**왕복 오차만 보면 안 된다.** 회전 반사식을 `N − v`(규약 §5-1) 대신 `(N−1) − v`로 바꿔도
정방향·역방향이 같은 규약이면 **왕복은 그대로 성립한다** — 이 스크립트의 모든 박스가 여전히
제자리로 돌아오고 오차만 1e-5에서 1.0px로 커진다. 그래서 §5-1이 지정한 **검산**을 같은 등급의
불변식으로 함께 판정한다: 회전 후 프레임 전체 박스 `(0,0,rotated_w,rotated_h)`가 센서 프레임
전체 `(0,0,src_w,src_h)`로 **정확히** 가는가. 참/거짓이므로 **px 문턱을 만들지 않는다.**
"""

from __future__ import annotations

import json
import logging
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))
sys.path.insert(0, str(Path(__file__).resolve().parent))

from lib.run_utils import common_argparser, ensure_utf8_console, init_run  # noqa: E402
from lib.stats import percentile  # noqa: E402

import detect_parity as dp  # noqa: E402

LOG = logging.getLogger(__name__)

NOT_MEASURED_NOTE = (
    "🔴 **이것은 합성 데이터로 통과한 로직 검증이지 실측이 아니다.** 폰이 같은 답을 내는지는 "
    "여기서 말하지 못한다 — 그건 scripts/detect_parity.py가 실기기 덤프로 답한다. "
    "이 검사가 통과했다고 '③ 이식이 맞다'고 쓰면 틀린다"
)

DST = 640                 # 모델 입력 한 변. 매니페스트가 아니라 이 검사의 조건이다
CONF_THR = 0.5
IOU_THR = 0.7

# 센서 치수 후보. 🔴 두 번째는 **총 패딩이 홀수**가 되도록 고른 것이고, 실제로 홀수인지는
#   아래에서 관측으로 확인한다(의도만 적고 확인하지 않으면 그 케이스는 없는 것과 같다).
SENSOR_SIZES = (
    (1280, 720),      # 실기기와 같은 조건
    (1280, 721),      # 총 패딩 홀수 노림 — 남는 1px이 아래/오른쪽으로 가는가
    (640, 640),       # 축소도 패딩도 없는 항등 letterbox
)


def make_boxes(src_w: int, src_h: int) -> list[dict]:
    """센서 좌표계(①)의 시험 박스. 🔴 **좌표는 화면 밖으로 나갈 수 있다**(규약 §5-4) —
    클램프가 없으므로 음수도 초과도 정상 입력이다."""
    w = float(src_w)
    h = float(src_h)
    return [
        {"tag": "normal_a", "x1": 100.25, "y1": 60.5, "x2": 300.75, "y2": 220.5},
        {"tag": "normal_b", "x1": w * 0.6, "y1": h * 0.1, "x2": w * 0.9, "y2": h * 0.4},
        {"tag": "normal_c", "x1": w * 0.25, "y1": h * 0.55, "x2": w * 0.45, "y2": h * 0.95},
        {"tag": "normal_d", "x1": w * 0.05, "y1": h * 0.45, "x2": w * 0.2, "y2": h * 0.6},
        # 1px · 코너
        {"tag": "corner_1px_tl", "x1": 0.0, "y1": 0.0, "x2": 1.0, "y2": 1.0},
        {"tag": "corner_1px_br", "x1": w - 1.0, "y1": h - 1.0, "x2": w, "y2": h},
        # 🔴 이슈 34가 재현됐던 입력 — letterbox **패딩 안에만** 있는 탐지
        {"tag": "pad_only_left", "x1": -120.0, "y1": 40.0, "x2": -40.0, "y2": 160.0},
        {"tag": "pad_only_top", "x1": 200.0, "y1": -90.0, "x2": 260.0, "y2": -20.0},
        # 프레임 경계를 넘는 박스 — 음수·초과가 **살아서 돌아오는가**(클램프 부활 감시)
        {"tag": "cross_tl", "x1": -30.0, "y1": -25.0, "x2": 50.0, "y2": 45.0},
        {"tag": "cross_br", "x1": w - 20.0, "y1": h - 15.0, "x2": w + 60.0, "y2": h + 40.0},
        # 🔴 역전 박스 — 왕복에서 **조용히 고쳐지면 안 된다**(규약 §5-3)
        {"tag": "inverted_x", "x1": 300.0, "y1": 100.0, "x2": 200.0, "y2": 180.0},
    ]


# ── 정변환: 이 파일이 따로 쓴다 (역함수를 역함수로 검사하지 않기 위해) ────────
# 🔴 `RotationMap.invert_box`의 역이지만 **코드를 공유하지 않는다.** 규약 §5의 사슬을 보고
#    다시 쓴 것이고, 둘이 서로의 역이 아니면 아래 왕복 오차가 그 자리에서 터진다.


def forward_rotate_box(rot: dp.RotationMap, x1, y1, x2, y2):
    """센서 좌표계(①) → 회전 후 좌표계(②). 시계 방향 `rot.degrees`.

    🔴 **연속 좌표 규약**(규약 §5-1이 정했다): 박스 좌표는 `[0, N]`을 가득 채우므로 반사식은
    `N - v`이고 `(N-1) - v`가 아니다. 축이 뒤집히는 쪽에서는 두 끝점의 **순서까지 함께**
    옮긴다 — min/max로 뭉개면 역전 박스가 조용히 고쳐진다(§5-1·§5-3).
    ⚠ 이 함수는 §5-1의 역변환표를 보고 **따로 쓴 것**이다(역함수를 역함수로 검사하지 않기
    위해서다). 여기가 틀리면 아래 왕복 오차가 그 자리에서 터진다.
    """
    f32 = dp.np.float32
    d = rot.degrees
    if d == 0:
        return x1, y1, x2, y2
    sw = f32(rot.src_w)
    sh = f32(rot.src_h)
    if d == 90:
        # rx ← src_h - sy (반전) · ry ← sx (보존)
        return sh - y2, x1, sh - y1, x2
    if d == 180:
        return sw - x2, sh - y2, sw - x1, sh - y1
    # 270: rx ← sy (보존) · ry ← src_w - sx (반전)
    return y1, sw - x2, y2, sw - x1


def forward_letterbox_box(box: dp.Letterbox, x1, y1, x2, y2):
    """회전 후 좌표계(②) → letterbox 640 좌표계(③)."""
    f32 = dp.np.float32
    s = box.scale
    px = f32(box.pad_x)
    py = f32(box.pad_y)
    return (x1 * s + px, y1 * s + py, x2 * s + px, y2 * s + py)


def build_output_tensor(lb_boxes: list[tuple]):
    """letterbox 좌표계의 박스들 → `(4+nc, A)` 출력 텐서.

    🔴 **앵커 하나에 박스 하나, 클래스도 하나씩 준다.** 그래야 NMS가 아무것도 억제하지 않고,
    이 검사가 재려는 것(좌표 왕복)만 남는다 — 억제가 섞이면 "박스가 사라졌다"가 역변환
    결함인지 NMS인지 구분되지 않는다.
    """
    np = dp.np
    f32 = np.float32
    n = len(lb_boxes)
    arr = np.zeros((dp.BOX_CHANNELS + n, n), dtype=np.float32)
    for i, (x1, y1, x2, y2) in enumerate(lb_boxes):
        arr[0, i] = (f32(x1) + f32(x2)) * f32(0.5)
        arr[1, i] = (f32(y1) + f32(y2)) * f32(0.5)
        arr[2, i] = f32(x2) - f32(x1)
        arr[3, i] = f32(y2) - f32(y1)
        arr[dp.BOX_CHANNELS + i, i] = f32(0.9)
    return arr


def run_case(src_w: int, src_h: int, degrees: int, warnings: list[str]) -> dict:
    np = dp.np
    f32 = np.float32
    rot = dp.RotationMap(degrees, src_w, src_h, applied=True,
                         site="preprocess_sample_map")
    box = dp.letterbox(rot.rot_w, rot.rot_h, DST, DST)
    boxes = make_boxes(src_w, src_h)
    class_names = {str(i): f"box_{b['tag']}" for i, b in enumerate(boxes)}

    lb_boxes = []
    traits = []
    for b in boxes:
        rx1, ry1, rx2, ry2 = forward_rotate_box(
            rot, f32(b["x1"]), f32(b["y1"]), f32(b["x2"]), f32(b["y2"]))
        lx1, ly1, lx2, ly2 = forward_letterbox_box(box, rx1, ry1, rx2, ry2)
        lb_boxes.append((lx1, ly1, lx2, ly2))
        # 🔴 케이스 성립 여부를 **의도가 아니라 관측으로** 판정한다.
        lo_x, hi_x = min(lx1, lx2), max(lx1, lx2)
        lo_y, hi_y = min(ly1, ly2), max(ly1, ly2)
        in_pad_only = bool(
            hi_x <= box.pad_x or lo_x >= box.pad_x + box.content_w
            or hi_y <= box.pad_y or lo_y >= box.pad_y + box.content_h
        )
        out_of_frame = bool(
            b["x1"] < 0 or b["y1"] < 0 or b["x2"] > src_w or b["y2"] > src_h
        )
        traits.append({
            "letterbox_xyxy": [float(lx1), float(ly1), float(lx2), float(ly2)],
            "inside_padding_only": in_pad_only,
            "out_of_frame": out_of_frame,
            "inverted_input": bool(b["x2"] < b["x1"] or b["y2"] < b["y1"]),
        })

    raw2d = build_output_tensor(lb_boxes)
    # 🔴 **실제로 쓰는 함수를 태운다.** NMS·letterbox 역변환·회전 역변환이 전부 이 안에 있다.
    got, pre_nms, _ = dp.postprocess(raw2d, box, CONF_THR, IOU_THR, class_names, rot)
    by_name = {g["cls_name"]: g for g in got}

    rows = []
    max_d = 0.0
    missing = 0
    clamp_hits = []
    inversion_lost = []
    for i, (b, tr) in enumerate(zip(boxes, traits)):
        name = class_names[str(i)]
        g = by_name.get(name)
        if g is None:
            missing += 1
            rows.append({"tag": b["tag"], "returned": False, **tr})
            continue
        d = [abs(g["x1"] - b["x1"]), abs(g["y1"] - b["y1"]),
             abs(g["x2"] - b["x2"]), abs(g["y2"] - b["y2"])]
        max_d = max(max_d, max(d))
        # 🔴 **클램프 부활 감시.** 음수/초과가 그대로 살아 돌아와야 한다 — 규약 §5-4가
        #    없앤 그 클램프가 어느 쪽에서든 되살아나면 여기서 부호가 바뀐다.
        clamped = (
            (b["x1"] < 0 and g["x1"] >= 0) or (b["y1"] < 0 and g["y1"] >= 0)
            or (b["x2"] > src_w and g["x2"] <= src_w)
            or (b["y2"] > src_h and g["y2"] <= src_h)
            or (b["x2"] < 0 and g["x2"] >= 0) or (b["y2"] < 0 and g["y2"] >= 0)
        )
        if clamped:
            clamp_hits.append(b["tag"])
        inv_out = bool(g["x2"] < g["x1"] or g["y2"] < g["y1"])
        if tr["inverted_input"] and not inv_out:
            inversion_lost.append(b["tag"])
        rows.append({
            "tag": b["tag"], "returned": True,
            "sensor_in": [b["x1"], b["y1"], b["x2"], b["y2"]],
            "sensor_out": [g["x1"], g["y1"], g["x2"], g["y2"]],
            "max_abs_diff_px": round(max(d), 12),
            "clamp_suspected": clamped,
            "inverted_output": inv_out,
            **tr,
        })

    # ── 🔴 규약 §5-1의 검산 — 왕복만으로는 절대 안 잡히는 자리 ──────────────
    # 회전 후 프레임 전체 박스 `(0, 0, rotated_w, rotated_h)`는 센서 프레임 전체
    # `(0, 0, src_w, src_h)`로 **정확히** 가야 한다. 인덱스 식 `(N-1) - v`를 쓰면 한 축이
    # `-1`로 나온다. 🔴 **허용오차 문제가 아니라 참/거짓이다** — 그래서 문턱을 만들지 않고
    # 등식으로 판정하며, 아래 verdicts에서 `all_boxes_returned`와 **같은 등급**으로 다룬다.
    # ⚠ 이 검사가 없으면 규약이 깨져도 왕복 오차만 1e-5 → 1.0으로 커질 뿐 **전부 초록불**이다
    #   (정방향·역방향이 같은 규약이면 왕복은 통과한다 — §5-1이 같은 말을 한다).
    frame_got = tuple(float(v) for v in rot.invert_box(
        f32(0), f32(0), f32(rot.rot_w), f32(rot.rot_h)))
    frame_want = (0.0, 0.0, float(src_w), float(src_h))
    frame_exact = frame_got == frame_want
    if not frame_exact:
        warnings.append(
            f"{src_w}×{src_h} rot={degrees}: 🔴 **규약 §5-1의 검산이 깨졌다** — 회전 후 프레임 "
            f"전체 박스가 {frame_got}로 갔다(기대 {frame_want}). 반사식은 `N - v`여야 하고 "
            f"`(N-1) - v`가 아니다. 한 축이 -1이면 **프레임 전체가 원점 쪽으로 정확히 1px 밀린 "
            f"것**이고, 그 1px은 왕복 검사로는 잡히지 않는다(정·역이 같은 규약이면 왕복은 "
            f"통과한다). ⚠ 전처리 샘플 맵은 반대로 `(N-1) - v`가 맞다 — 한 함수를 두 자리에 "
            f"쓰면 한쪽이 틀린다"
        )

    total_pad_x = DST - box.content_w
    total_pad_y = DST - box.content_h
    if missing:
        warnings.append(
            f"{src_w}×{src_h} rot={degrees}: 🔴 **박스 {missing}개가 돌아오지 않았다** — "
            f"앵커마다 클래스를 갈랐으므로 NMS 억제가 아니다. 후처리 경로에서 사라진 것이다"
        )
    if clamp_hits:
        warnings.append(
            f"{src_w}×{src_h} rot={degrees}: 🔴 **클램프가 되살아난 것으로 보인다** — "
            f"{clamp_hits}. 프레임 밖 좌표가 경계로 잘렸다. 규약 §5-4는 클램프를 "
            f"**제거**했고(비대칭이라 역전 박스를 만들었다) 폰과 PC가 함께 없앴다"
        )
    if inversion_lost:
        warnings.append(
            f"{src_w}×{src_h} rot={degrees}: 🔴 **역전 박스가 조용히 고쳐졌다** — "
            f"{inversion_lost}. 규약 §5-3은 거르지도 고치지도 말고 **세라**고 한다. "
            f"min/max 정규화가 어딘가에 들어갔는지 볼 것"
        )
    return {
        "sensor": [src_w, src_h],
        "rotation_degrees": degrees,
        "rotated": [rot.rot_w, rot.rot_h],
        "letterbox": box.as_dict(),
        "total_pad_x": total_pad_x,
        "total_pad_y": total_pad_y,
        # 🔴 **관측으로 확인한다** — "홀수 패딩을 시험했다"는 의도만으로는 시험한 것이 아니다.
        "odd_total_padding": bool(total_pad_x % 2 or total_pad_y % 2),
        # 🔴 규약 §5-1의 검산. 참/거짓이지 오차가 아니다.
        "frame_box_sensor": list(frame_got),
        "frame_box_expected": list(frame_want),
        "frame_box_exact": frame_exact,
        "boxes_in": len(boxes),
        "boxes_out": len(got),
        "boxes_pre_nms": pre_nms,
        "boxes_missing": missing,
        "max_abs_diff_px": round(max_d, 12),
        "clamp_suspected": bool(clamp_hits),
        "inversion_lost": bool(inversion_lost),
        "inverted_out_count": dp.count_inverted_boxes(got),
        "padding_only_boxes": sum(1 for t in traits if t["inside_padding_only"]),
        "out_of_frame_boxes": sum(1 for t in traits if t["out_of_frame"]),
        "rows": rows,
    }


def check_rotation_rejection() -> dict:
    """🔴 {0,90,180,270} 밖의 회전각을 **거부하는가.** 조용히 받아들이면 90° 배수가 아닌
    회전이 픽셀 보간으로 흘러들어가고, 그 보간 규칙은 규약 어디에도 없다.

    ⚠ **`90.0`(float)은 여기서 거부 대상이 아니다.** 수치로 90°와 같은 각이고
    `RotationMap`은 각의 **값**을 검사하는 자리다. 매니페스트가 실어 온 값의 **타입**을
    막는 자리는 `resolve_rotation`이며(규약 §3의 `rotation_degrees`는 정수다), 그쪽은
    아래 `check_manifest_rotation_gate`가 따로 태운다.
    🔴 처음엔 `90.0`을 거부 목록에 넣었다가 이 검사가 실패했다. **코드가 아니라 이 검사의
    기대가 틀렸던 것**이고, 그 판단을 여기 적어 둔다 — 통과시키려고 목록에서 뺀 것이 아니라
    타입 게이트의 자리를 옮겨 **경계에서 실제로 막히는지 확인**하는 쪽으로 바꿨다.
    """
    out = []
    ok = True
    for bad in (45, -90, 360, 91, 1, "90", None):
        try:
            dp.RotationMap(bad, 1280, 720, applied=True, site="preprocess_sample_map")
        except dp.ParityError as exc:
            out.append({"value": repr(bad), "rejected": True, "message": str(exc)[:80]})
            continue
        except Exception as exc:                      # noqa: BLE001
            out.append({"value": repr(bad), "rejected": True,
                        "message": f"{type(exc).__name__}: {exc}"[:80]})
            continue
        out.append({"value": repr(bad), "rejected": False, "message": None})
        ok = False
    # 아는 각은 **받아들여야** 한다. 전부 거부하면 위 검사는 공짜로 통과한다.
    accepted = []
    for good in dp.ROTATIONS_ALLOWED:
        try:
            dp.RotationMap(good, 1280, 720, applied=True, site="preprocess_sample_map")
            accepted.append(good)
        except dp.ParityError:
            ok = False
    return {"rejected_cases": out, "accepted": accepted,
            "accepts_all_known": list(accepted) == list(dp.ROTATIONS_ALLOWED),
            "ok": ok and list(accepted) == list(dp.ROTATIONS_ALLOWED)}


def check_manifest_rotation_gate() -> dict:
    """🔴 규약 §4-2의 **세 경우**를 매니페스트 경계에서 태운다 —
    `detect_parity.resolve_rotation`을 **직접** 부른다(사본이 아니다).

    파일이 아니라 메모리 매니페스트로 태우는 이유: 이 표는 **덤프가 없어도 성립하는 규칙**이고,
    실기기 덤프를 기다리면 그때까지 이 분기가 한 번도 안 돌아 본 채로 남는다.

    | `rotation_applied` | `rotation_site` | 기대 |
    |---|---|---|
    | `true`  | `preprocess_*` | 정상, PC 적용각 = 선언각 |
    | `false` | `none`         | 🟢 의도된 대조군. PC 적용각 0°, **이슈 29 경고를 내면 안 된다** |
    | `false` | `preprocess_*` | 🔴 죽는다 |
    """
    def manifest(fmt: str, **source) -> dict:
        return {
            "format": fmt,
            "samples": [{"src": {"width": 1280, "height": 720}}],
            "source": {"format": "YUV_420_888", **source},
        }

    v1 = "bammasil-detect-parity/1"
    v2 = "bammasil-detect-parity/2"
    applied2 = {"rotation_locked": True, "rotation_changed_frames": 0,
                "rotated_width": 720, "rotated_height": 1280}
    norot2 = {"rotation_locked": True, "rotation_changed_frames": 0,
              "rotated_width": 1280, "rotated_height": 720}
    # (이름, 매니페스트, 죽어야 하나, 기대 적용각, 이슈29 경고가 나와야 하나, 경고가 있어야 하나)
    cases = [
        ("v1_not_applied",
         manifest(v1, rotation_degrees=90, rotation_applied=False),
         False, 0, True, None),
        ("v1_applied_true_contradiction",
         manifest(v1, rotation_degrees=90, rotation_applied=True),
         True, None, False, None),
        ("v2_applied_sample_map",
         manifest(v2, rotation_degrees=90, rotation_applied=True,
                  rotation_site="preprocess_sample_map", **applied2),
         False, 90, False, False),
        ("v2_applied_plane_copy",
         manifest(v2, rotation_degrees=270, rotation_applied=True,
                  rotation_site="preprocess_plane_copy",
                  rotation_locked=True, rotation_changed_frames=0,
                  rotated_width=720, rotated_height=1280),
         False, 270, False, False),
        ("v2_norot_control_arm",
         manifest(v2, rotation_degrees=90, rotation_applied=False,
                  rotation_site="none", **norot2),
         False, 0, False, False),
        ("v2_false_with_preprocess_site",
         manifest(v2, rotation_degrees=90, rotation_applied=False,
                  rotation_site="preprocess_sample_map", **applied2),
         True, None, False, None),
        ("v2_true_with_none_site",
         manifest(v2, rotation_degrees=90, rotation_applied=True,
                  rotation_site="none", **applied2),
         True, None, False, None),
        ("v2_missing_site",
         manifest(v2, rotation_degrees=90, rotation_applied=True, **applied2),
         True, None, False, None),
        ("v2_camerax_site",
         manifest(v2, rotation_degrees=90, rotation_applied=True,
                  rotation_site="camerax_output_rotation", **applied2),
         True, None, False, None),
        ("v2_unknown_site",
         manifest(v2, rotation_degrees=90, rotation_applied=True,
                  rotation_site="somewhere_else", **applied2),
         True, None, False, None),
        ("v2_degrees_45",
         manifest(v2, rotation_degrees=45, rotation_applied=True,
                  rotation_site="preprocess_sample_map", **applied2),
         True, None, False, None),
        # 🔴 타입 게이트가 사는 자리 — 매니페스트의 rotation_degrees는 **정수**다(규약 §3).
        ("v2_degrees_float",
         manifest(v2, rotation_degrees=90.0, rotation_applied=True,
                  rotation_site="preprocess_sample_map", **applied2),
         True, None, False, None),
        ("v2_applied_not_bool",
         manifest(v2, rotation_degrees=90, rotation_applied="true",
                  rotation_site="preprocess_sample_map", **applied2),
         True, None, False, None),
        # 경고는 나오되 죽지는 않아야 하는 것들 (규약 §4-3·§5)
        ("v2_rotation_changed_frames",
         manifest(v2, rotation_degrees=90, rotation_applied=True,
                  rotation_site="preprocess_sample_map", rotation_locked=True,
                  rotation_changed_frames=3, rotated_width=720, rotated_height=1280),
         False, 90, False, True),
        ("v2_rotated_dims_mismatch",
         manifest(v2, rotation_degrees=90, rotation_applied=True,
                  rotation_site="preprocess_sample_map", rotation_locked=True,
                  rotation_changed_frames=0, rotated_width=1280, rotated_height=720),
         False, 90, False, True),
    ]

    rows = []
    ok = True
    for name, man, want_die, want_deg, want_issue29, want_warn in cases:
        warns: list[str] = []
        dump = dp.Dump(Path(name), man, int(str(man["format"])[-1]))
        died = False
        deg = None
        err = None
        try:
            rot = dp.resolve_rotation(dump, warns)
            deg = rot.degrees
        except dp.ParityError as exc:
            died = True
            err = str(exc)[:120]
        issue29 = any("이슈 29" in w for w in warns)
        good = (died == want_die)
        if not died:
            good = good and deg == want_deg and issue29 == want_issue29
            if want_warn is not None:
                good = good and bool(warns) == want_warn
        ok = ok and good
        rows.append({
            "case": name, "died": died, "expected_die": want_die,
            "effective_degrees": deg, "expected_degrees": want_deg,
            "issue29_warning": issue29, "expected_issue29": want_issue29,
            "warnings": warns, "error": err, "ok": good,
        })
    return {"rows": rows, "ok": ok}


def _dist(values: list[float]) -> dict:
    """분포. `lib/stats.percentile`의 nearest-rank 규칙을 그대로 쓰되 **반올림하지 않는다** —
    왕복 오차는 1e-5 단위라 `lib/stats.summarize`의 소수 3자리 반올림이 전부 0으로 만든다."""
    if not values:
        return {"count": 0, "min": None, "max": None, "mean": None,
                "p50": None, "p95": None, "p99": None}
    ordered = sorted(float(v) for v in values)
    return {
        "count": len(ordered),
        "min": ordered[0],
        "max": ordered[-1],
        "mean": sum(ordered) / len(ordered),
        "p50": percentile(ordered, 0.50),
        "p95": percentile(ordered, 0.95),
        "p99": percentile(ordered, 0.99),
    }


def main() -> int:
    ensure_utf8_console()          # init_run도 부르지만, 그 앞에서 죽을 때를 위해 먼저 부른다
    parser = common_argparser()
    parser.add_argument("--label", default="", help="이 검사에 붙일 메모")
    parser.add_argument(
        "--model", default=str(dp.DEFAULT_MODEL),
        help=("빌릴 바(parity_check)를 읽을 모델 경로. 🔴 이 검사는 모델을 열지 않는다 — "
              "옆의 metadata.json만 본다"),
    )
    args = parser.parse_args()
    paths = init_run(stage="detect_parity_roundtrip", script_file=__file__, args=args)

    warnings: list[str] = []
    try:
        dp._require_deps()
    except dp.ParityError as exc:
        LOG.error("%s", exc)
        return 2

    bar, bar_note = dp._read_bar(Path(args.model))
    if bar_note:
        warnings.append(bar_note)

    cases = []
    for (sw, sh) in SENSOR_SIZES:
        for deg in dp.ROTATIONS_ALLOWED:
            cases.append(run_case(sw, sh, deg, warnings))
    gate = check_manifest_rotation_gate()
    if not gate["ok"]:
        bad = [r["case"] for r in gate["rows"] if not r["ok"]]
        warnings.append(
            f"🔴 **매니페스트 회전 게이트가 규약 §4-2와 다르게 답했다** — {bad}. "
            f"세 경우(정상 / 🟢 의도된 대조군 / 🔴 모순)를 가르지 못하면 대조군 arm이 "
            f"결함처럼 보이거나 진짜 모순이 조용히 지나간다"
        )
    rejection = check_rotation_rejection()
    if not rejection["ok"]:
        warnings.append(
            "🔴 **모르는 회전각을 거부하지 못했다** — "
            f"{[c for c in rejection['rejected_cases'] if not c['rejected']]}. "
            "90° 배수가 아닌 회전은 픽셀 재배치가 아니라 보간이고 그 규칙은 규약에 없다"
        )

    all_diffs = [r["max_abs_diff_px"] for c in cases for r in c["rows"] if r["returned"]]
    observed_max = max(all_diffs) if all_diffs else None
    padding_only = sum(c["padding_only_boxes"] for c in cases)
    out_of_frame = sum(c["out_of_frame_boxes"] for c in cases)
    odd_cases = [c for c in cases if c["odd_total_padding"]]
    if not odd_cases:
        warnings.append(
            "🔴 **총 패딩이 홀수인 조합이 하나도 없었다** — 남는 1px이 오른쪽/아래로 가는 "
            "규약(detect_parity.py의 letterbox)이 왕복에서 살아남는지 **시험되지 않았다.** "
            "SENSOR_SIZES를 고칠 것"
        )
    if not padding_only:
        warnings.append(
            "🔴 **letterbox 패딩 안에만 있는 박스가 하나도 없었다** — 이슈 34가 재현됐던 "
            "입력이 이 실행에서는 만들어지지 않았다. 그 경로는 **시험되지 않았다**"
        )
    if not out_of_frame:
        warnings.append(
            "🔴 **프레임 밖 좌표를 가진 박스가 하나도 없었다** — 클램프 부활 감시가 "
            "**시험되지 않았다**"
        )

    verdicts = {
        # 🔴 우리가 스스로 주장한 구조적 불변식. 이것만 종료 코드를 흔든다.
        # 🔴 규약 §5-1의 검산. **왕복 오차와 별개의 질문이다** — 인덱스 식을 쓰면 왕복은
        #    그대로 통과하면서 이것만 깨진다(정·역이 같은 규약이면 왕복은 성립한다).
        #    허용오차가 아니라 등식이므로 문턱을 지어낼 필요가 없다.
        "frame_box_maps_to_frame": all(c["frame_box_exact"] for c in cases),
        "all_boxes_returned": all(c["boxes_missing"] == 0 for c in cases),
        "no_clamping_observed": not any(c["clamp_suspected"] for c in cases),
        "inversion_preserved": not any(c["inversion_lost"] for c in cases),
        "rejects_unknown_rotation": rejection["ok"],
        "manifest_rotation_gate_ok": gate["ok"],
        # 케이스가 실제로 만들어졌는가 (의도가 아니라 관측)
        "odd_padding_case_covered": bool(odd_cases),
        "padding_only_case_covered": padding_only > 0,
        "out_of_frame_case_covered": out_of_frame > 0,
    }
    structural_ok = all(verdicts.values())

    over_bar = None
    if bar and bar.get("max_xy_diff_px") is not None and observed_max is not None:
        over_bar = observed_max > float(bar["max_xy_diff_px"])

    summary = {
        "run_ts": paths.run_ts,
        "label": args.label,
        "synthetic": True,
        "not_measured_note": NOT_MEASURED_NOTE,
        "spec": "docs/plans/20260806_detect_parity_dump_format.md §5",
        "under_test": (
            "scripts/detect_parity.py의 letterbox() · RotationMap.invert_box() · "
            "postprocess() — **사본이 아니라 그 함수들을 직접 태웠다.** 정변환(센서 → 회전 후 "
            "→ letterbox)만 이 파일이 따로 쓴다(역함수를 역함수로 검사하면 순환이다)"
        ),
        "conditions": {
            "dst": DST, "conf": CONF_THR, "iou": IOU_THR,
            "sensor_sizes": [list(s) for s in SENSOR_SIZES],
            "rotations": list(dp.ROTATIONS_ALLOWED),
            "cases": len(cases),
            "boxes_per_case": cases[0]["boxes_in"] if cases else 0,
        },
        "observed": {
            "roundtrip_abs_diff_px": _dist(all_diffs),
            "max_abs_diff_px": observed_max,
            "boxes_compared": len(all_diffs),
            "boxes_missing_total": sum(c["boxes_missing"] for c in cases),
            "padding_only_boxes_total": padding_only,
            "out_of_frame_boxes_total": out_of_frame,
            "odd_padding_cases": [
                {"sensor": c["sensor"], "rotation_degrees": c["rotation_degrees"],
                 "total_pad_x": c["total_pad_x"], "total_pad_y": c["total_pad_y"]}
                for c in odd_cases
            ],
            "inverted_out_total": sum(c["inverted_out_count"] for c in cases),
            "dist_note": (
                "왕복 |diff|의 분포다(nearest-rank, lib/stats.percentile과 같은 규칙). "
                "🔴 반올림하지 않았다 — 1e-5 단위라 소수 3자리로 접으면 전부 0이 된다. "
                "🔴 판정선이 아니다: 우리 고유의 좌표 허용오차는 없다"
            ),
        },
        "verdicts": verdicts,
        "structural_ok": structural_ok,
        "verdict_note": (
            "🔴 여기 불리언은 **구조적 불변식**이다(규약 §5-1 프레임 전체 박스 검산 · 박스 "
            "보존 · 클램프 부활 없음 · 역전 보존 · 모르는 각 거부 · §4-2 게이트 · 각 케이스가 "
            "실제로 만들어졌는가). 이것만 종료 코드를 흔든다. 좌표 오차의 합격선은 여기 없다. "
            "🔴 **frame_box_maps_to_frame이 그중 유일하게 회전 반사식을 판정한다** — 왕복 "
            "오차는 인덱스 식으로 바꿔도 '통과'하므로(정·역이 같은 규약이면 왕복은 성립한다) "
            "그 값만 보면 규약 위반이 초록불로 지나간다"
        ),
        "borrowed_bar": bar,
        "over_borrowed_bar": over_bar,
        "borrowed_bar_note": (
            "🔴 **빌린 바이고 합격 판정이 아니다.** 출처는 상류의 "
            "models/det_c4b_loli0_640/metadata.json `parity_check`이며 상류가 **PC에서 "
            "PyTorch↔ONNX**를 잰 값이다. 이 검사는 **PC 안의 좌표 왕복**이라 또 다른 비교다 — "
            "'넘었다/못 넘었다'까지만 말하고 종료 코드를 흔들지 않는다"
        ),
        "rotation_rejection": rejection,
        "manifest_rotation_gate": gate,
        "manifest_rotation_gate_note": (
            "🔴 규약 §4-2의 세 경우를 **메모리 매니페스트로** 태운 것이다 — "
            "detect_parity.resolve_rotation을 직접 부른다. 실기기 `/2` 덤프가 아직 없어서 "
            "파일로는 못 태우는 분기를, 그 분기가 한 번도 안 돌아 본 채로 남지 않게 한다. "
            "⚠ 이것도 합성이다 — 앱이 실제로 그 값을 싣는지는 여기서 말하지 못한다"
        ),
        "cases": cases,
        "warnings": warnings,
    }

    if paths.outputs_enabled:
        out_path = paths.out_dir / "summary.json"
        with out_path.open("w", encoding="utf-8") as f:
            json.dump(summary, f, ensure_ascii=False, indent=2)
            f.write("\n")
        LOG.info("요약 저장: %s", out_path)
    else:
        LOG.info("출력 비활성화 — 요약을 파일로 남기지 않는다(--no_outputs)")

    _print_report(summary)
    return 0 if structural_ok else 1


def _print_report(summary: dict) -> None:
    obs = summary["observed"]
    LOG.info("=" * 62)
    LOG.info("③ 좌표 사슬 왕복 검사(합성) — run_ts=%s", summary["run_ts"])
    LOG.info("%s", summary["not_measured_note"])
    LOG.info("-" * 62)
    LOG.info("관측값 (통과/실패보다 이게 먼저다)")
    for c in summary["cases"]:
        LOG.info(
            "  센서 %s×%s rot=%s° → 회전 후 %s×%s · pad(%s,%s) 총(%s,%s)%s · "
            "박스 %s→%s · max|d|=%.6g px",
            c["sensor"][0], c["sensor"][1], c["rotation_degrees"],
            c["rotated"][0], c["rotated"][1],
            c["letterbox"]["pad_x"], c["letterbox"]["pad_y"],
            c["total_pad_x"], c["total_pad_y"],
            " **홀수**" if c["odd_total_padding"] else "",
            c["boxes_in"], c["boxes_out"], c["max_abs_diff_px"],
        )
    LOG.info("-" * 62)
    d = obs["roundtrip_abs_diff_px"]
    LOG.info(
        "왕복 |diff| px: max=%s p99=%s p95=%s p50=%s min=%s (박스 %s개)",
        d["max"], d["p99"], d["p95"], d["p50"], d["min"], d["count"],
    )
    LOG.info(
        "케이스 커버리지: 패딩 안에만 있는 박스 %s개 · 프레임 밖 좌표 박스 %s개 · "
        "홀수 패딩 케이스 %s개 · 역전 박스 반환 %s개",
        obs["padding_only_boxes_total"], obs["out_of_frame_boxes_total"],
        len(obs["odd_padding_cases"]), obs["inverted_out_total"],
    )
    LOG.info(
        "🔴 규약 §5-1 검산(프레임 전체 박스 → 센서 프레임 전체): %s/%s 케이스 정확 일치 "
        "— 왕복 오차로는 잡히지 않는 자리다",
        sum(1 for c in summary["cases"] if c["frame_box_exact"]), len(summary["cases"]),
    )
    for c in summary["cases"]:
        if not c["frame_box_exact"]:
            LOG.error(
                "  🔴 센서 %s×%s rot=%s°: 프레임 전체 박스가 %s → 기대 %s",
                c["sensor"][0], c["sensor"][1], c["rotation_degrees"],
                c["frame_box_sensor"], c["frame_box_expected"],
            )
    bar = summary.get("borrowed_bar")
    if bar:
        LOG.info(
            "빌린 바 (상류 parity_check) max_xy_diff_px=%s → 넘었나: %s "
            "🔴 합격 판정이 아니다(다른 비교다)",
            bar.get("max_xy_diff_px"), summary["over_borrowed_bar"],
        )
    else:
        LOG.info("빌린 바 없음 — 관측값만 낸다")
    LOG.info("-" * 62)
    LOG.info("매니페스트 회전 게이트 (규약 §4-2 세 경우) — %s건",
             len(summary["manifest_rotation_gate"]["rows"]))
    for r in summary["manifest_rotation_gate"]["rows"]:
        LOG.info(
            "  %-32s 죽었나=%-5s(기대 %-5s) 적용각=%-5s 이슈29경고=%-5s → %s",
            r["case"], r["died"], r["expected_die"], r["effective_degrees"],
            r["issue29_warning"], "OK" if r["ok"] else "🔴 FAIL",
        )
    LOG.info("-" * 62)
    for k, v in summary["verdicts"].items():
        LOG.info("  %-28s %s", k, v)
    LOG.info("구조적 불변식 전체: %s", summary["structural_ok"])
    for w in summary["warnings"]:
        LOG.warning("⚠ %s", w)
    LOG.info("%s", summary["not_measured_note"])
    LOG.info("=" * 62)


if __name__ == "__main__":
    raise SystemExit(main())
