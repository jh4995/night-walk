"""③ 이식 정확성 대조 — 폰 덤프 ↔ PC ORT 레퍼런스.

폰이 남긴 덤프(`parity/`)를 읽어 **E(전처리)·F(추론)·G(후처리)를 따로** PC에서 재현하고
대조한다. 포맷 규약은 `docs/plans/20260806_detect_parity_dump_format.md`이며 **어긋나면 그
문서가 맞다** — 생산자는 앱(`DetectParityDumper.kt`)이고 이 파일은 소비자다.

    python scripts/detect_parity.py --dump <pulled>/parity
    python scripts/detect_parity.py --dump <cpu>/parity --dump <nnapi>/parity --dump <xnnpack>/parity

## 🔴 이것이 답하는 질문과 답하지 못하는 질문

답한다: 폰의 E/F/G가 **같은 입력에서 PC와 같은 값을 내는가**(이식이 값을 안 바꿨는가).
답하지 못한다: **모델이 옳은가**(mAP·누락률 — 정답 라벨이 필요하다). 이 스크립트의 결과를
"모델이 잘 맞힌다"의 증거로 쓰면 틀린다. 게다가 앱은 `rotationDegrees`를 적용하지 않아
(알려진 이슈 29) 모델이 **옆으로 누운 장면**을 본다 — `rotation_applied=false`면 아래
`warnings`에 그 문장이 반드시 나간다(규약 §4).

## 🔴 판정선이 없는 스크립트다

`lib/targets.py`의 판정선(프레임당 예산·p95 관리선)은 **프레임타임의 것**이고 여기와 무관하다.
그래서 이 스크립트는 `meets_*_target`을 내지 않는다. 대신 규약 §7 그대로:

1. **관측값을 먼저 낸다** — `max|diff|` · `mean|diff|` · 초과 개수. 통과/실패보다 이게 앞이다.
2. 기준선은 **상류가 자기 대조에 쓴 바를 빌려 쓰고 빌린 것임을 밝힌다**
   (`metadata.json`의 `parity_check`. 🔴 **숫자를 여기 베껴 적지 않고 그 파일에서 읽는다** —
   두 곳에 적히면 조용히 어긋난다). 🔴 **그 바는 상류가 PC에서 PyTorch↔ONNX를 잰 것이고
   우리 대조(폰↔PC ORT)와 다른 비교다.** "넘었다/못 넘었다"까지만 말하고 **합격 판정으로
   쓰지 않는다.** 그래서 바를 넘어도 **종료 코드는 0이다** — 종료 코드가 흔들리면 그것이 곧
   합격 판정이 된다. 0이 아닌 종료 코드는 **대조 자체가 성립하지 않았을 때**만 나온다.
3. F는 **비트 일치를 기대하지 않는다**(폰 arm64 빌드 vs PC x86-64 빌드는 같은 ORT라도 커널
   구현·벡터화·연산 순서가 다르다). raw float 차이와 함께 **"살아남은 탐지 집합이 같은가"**를
   함께 낸다 — 안전 관점에서 의미 있는 것은 그쪽이다.

## ⚠ 이 저장소에서 유일하게 stdlib 밖을 쓰는 스크립트다

`numpy`·`onnxruntime`을 **이 파일 안에서 지연 import**한다. `lib/` 아래 공용 모듈에는
넣지 않는다 — 하네스의 나머지가 의존성 없이 도는 성질을 이 한 스크립트 때문에 깨지 않는다.
"""

from __future__ import annotations

import hashlib
import json
import logging
import math
import sys
from pathlib import Path
from typing import Optional

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))

from lib.run_utils import common_argparser, init_run  # noqa: E402
from lib.stats import percentile, summarize  # noqa: E402

LOG = logging.getLogger(__name__)

_PROJECT_ROOT = Path(__file__).resolve().parent.parent

# ── 포맷 규약 상수 ─────────────────────────────────────────────────────────
# 🔴 출처는 `docs/plans/20260806_detect_parity_dump_format.md` **하나**다. 앱이 같은 문서를
#    보고 같은 문자열을 만든다 — 여기서 지어내면 대조가 통째로 무의미해진다.
MANIFEST_NAME = "parity.json"
FORMAT_ID = "bammasil-detect-parity/1"        # §3. 다르면 죽는다
BYTE_ORDER_ID = "little_endian"               # §2. 다르면 죽는다(지어내지 않는다)
F32_ITEMSIZE = 4

DEFAULT_MODEL = (
    _PROJECT_ROOT / "models" / "det_c4b_loli0_640" / "bammasil_det_c4b_loli0_640.onnx"
)
# 빌린 바(`parity_check`)가 사는 곳. **모델 파일 옆의 metadata.json**이며, 모델 경로를
# 바꾸면 이 경로도 따라 움직인다(같은 디렉토리에서 찾는다).
METADATA_NAME = "metadata.json"

# 🔴 **PC 세션은 CPU EP 고정이다.** 이 스크립트가 만드는 것은 "폰과 비교할 레퍼런스"이고,
#    PC 쪽 EP까지 흔들면 F의 차이가 폰↔PC 차이인지 PC 쪽 EP 차이인지 구분되지 않는다.
PC_PROVIDER = "CPUExecutionProvider"

# 지연 import한 모듈이 앉는 자리. `_require_deps()`가 채운다.
# ⚠ 전역을 쓰는 이유는 전처리·후처리 함수 서명에 `np`를 끌고 다니지 않기 위해서다 —
#   그 함수들은 합성 덤프 생성기(스크래치패드)도 import해서 쓴다.
np = None  # type: ignore[assignment]
ort = None  # type: ignore[assignment]


class ParityError(RuntimeError):
    """대조가 성립하지 않는 상태. **조용히 그럴듯한 결과를 내지 않기 위해** 던진다."""


def _require_deps() -> None:
    """`numpy`·`onnxruntime`을 지연 import한다. 없으면 설치 방법을 알려 주고 깔끔히 죽는다."""
    global np, ort
    if np is not None and ort is not None:
        return
    try:
        import numpy as _np  # noqa: PLC0415
        import onnxruntime as _ort  # noqa: PLC0415
    except ImportError as exc:
        raise ParityError(
            f"이 스크립트만 stdlib 밖을 쓴다 — {exc.name}을 import하지 못했다. "
            "설치: python -m pip install onnxruntime numpy "
            "(이 저장소는 uv를 쓰지 않는다). "
            "⚠ lib/ 아래 공용 모듈에는 이 의존을 넣지 않았다 — 하네스의 나머지는 "
            "의존성 없이 돈다"
        ) from exc
    np = _np
    ort = _ort


# ── 백분위: lib/stats.percentile의 nearest-rank 규칙을 numpy 배열에 적용한다 ──
# 🔴 **다른 규칙을 새로 만든 것이 아니다.** 텐서 원소가 한 샘플에 120만 개라 파이썬 리스트로
#    올리면 메모리·시간이 낭비되므로 정렬된 numpy 배열에 같은 랭크 식을 쓴다. 두 구현이
#    갈리지 않는다는 것은 아래 자기검사가 import 시점에 확인한다.
def _np_percentile(sorted_arr, p: float) -> float:
    # len()으로 센다 — 아래 자기검사가 **파이썬 리스트**를 그대로 넘겨 두 구현을 대조하기
    # 때문이다(.size로 세면 자기검사가 numpy 전용이 되어 규칙 비교가 성립하지 않는다).
    n = len(sorted_arr)
    if n == 0:
        return 0.0
    rank = max(1, min(n, math.ceil(p * n)))
    return float(sorted_arr[rank - 1])


def _np_summarize(arr) -> dict:
    """numpy 배열의 분포 요약. 키는 `lib/stats.summarize`와 **같다**(소비자가 둘을 구분할
    필요가 없어야 한다). count=0이면 죽지 않고 None들을 돌려준다."""
    flat = arr.reshape(-1)
    if flat.size == 0:
        return summarize([])
    ordered = np.sort(flat)
    return {
        "count": int(ordered.size),
        "min": round(float(ordered[0]), 9),
        "max": round(float(ordered[-1]), 9),
        "mean": round(float(flat.mean(dtype="float64")), 9),
        "p50": round(_np_percentile(ordered, 0.50), 9),
        "p95": round(_np_percentile(ordered, 0.95), 9),
        "p99": round(_np_percentile(ordered, 0.99), 9),
    }


# ── 자기검사: 두 백분위 구현이 같은 답을 내는가 ───────────────────────────
# `lib/frame_log.py`의 상수 자기검사와 같은 부류다 — 데이터와 무관한 불변식이고, 깨지는
# 순간은 개발자가 한쪽을 고친 그 편집 시점이다. 여기서 죽이지 않으면 이 스크립트의 백분위만
# 조용히 다른 규칙을 쓰게 되고, 그 숫자는 다른 요약과 같은 표에 놓인다.
_ref = [1.0, 2.0, 3.0, 4.0, 5.0, 6.0, 7.0]
for _p in (0.50, 0.95, 0.99):
    if percentile(_ref, _p) != _np_percentile(_ref, _p):
        raise RuntimeError(
            "scripts/detect_parity.py 자기검사 실패 — _np_percentile이 lib/stats.percentile과 "
            f"다른 답을 낸다(p={_p}). 백분위 규칙이 둘로 갈리면 이 스크립트의 숫자만 "
            "다른 뜻이 된다"
        )
del _ref, _p


def sha256_file(path: Path) -> str:
    h = hashlib.sha256()
    with path.open("rb") as f:
        for chunk in iter(lambda: f.read(1 << 20), b""):
            h.update(chunk)
    return h.hexdigest()


# ══ E: 전처리 재구현 ══════════════════════════════════════════════════════
# 🔴 **`DetectPreprocessor.kt`·`DetectContract.kt`를 읽고 그대로 옮긴 것이다.** 더 나은
#    방법을 알아도 여기서 고치지 않는다 — 다르게 짜면 대조 결과의 불일치가 "이식 결함"이
#    아니라 "PC 쪽이 다른 알고리즘을 썼다"가 되어 질문에 답할 수 없다.
#    ⚠ 폰은 Kotlin `Float`(32비트)로 계산한다. 그래서 여기도 **전부 float32**이고 연산
#      순서까지 같게 맞춘다(`114/255f`는 나눗셈이고 `r*INV_255`는 곱셈이다 — 1ulp가 갈린다).


class Letterbox:
    """`DetectContract.Letterbox`와 같은 값. **전처리와 후처리가 같은 객체를 쓴다.**"""

    __slots__ = ("src_w", "src_h", "dst_w", "dst_h", "scale", "content_w", "content_h",
                 "pad_x", "pad_y")

    def __init__(self, src_w, src_h, dst_w, dst_h, scale, content_w, content_h, pad_x, pad_y):
        self.src_w = src_w
        self.src_h = src_h
        self.dst_w = dst_w
        self.dst_h = dst_h
        self.scale = scale
        self.content_w = content_w
        self.content_h = content_h
        self.pad_x = pad_x
        self.pad_y = pad_y

    def as_dict(self) -> dict:
        return {
            "scale": float(self.scale),
            "pad_x": self.pad_x,
            "pad_y": self.pad_y,
            "content_w": self.content_w,
            "content_h": self.content_h,
            "align": "center",
        }


def letterbox(src_w: int, src_h: int, dst_w: int, dst_h: int) -> Letterbox:
    """`DetectContract.letterbox`의 이식. align=center이며 **총 패딩이 홀수면 남는 1px은
    오른쪽/아래로** 간다(정수 나눗셈). 🔴 그 정렬은 계약이 아니라 가정이다
    (`LETTERBOX_ALIGN_ASSUMPTION` — 틀리면 박스가 통째로 세로로 밀린다)."""
    f32 = np.float32
    scale = min(f32(dst_w) / f32(src_w), f32(dst_h) / f32(src_h))
    # Kotlin `Math.round(Float)` = (int)floor(a + 0.5f). float32 덧셈까지 같게 맞춘다.
    content_w = min(max(int(np.floor(f32(src_w) * scale + f32(0.5))), 1), dst_w)
    content_h = min(max(int(np.floor(f32(src_h) * scale + f32(0.5))), 1), dst_h)
    return Letterbox(
        src_w=src_w, src_h=src_h, dst_w=dst_w, dst_h=dst_h, scale=scale,
        content_w=content_w, content_h=content_h,
        pad_x=(dst_w - content_w) // 2, pad_y=(dst_h - content_h) // 2,
    )


def preprocess(raw: bytes, src: dict, box: Letterbox, pad_value_u8: int):
    """YUV_420_888 평면 → letterbox → BT.601 full range RGB → `/255` → NCHW float32.

    반환은 `(3, dst_h, dst_w)` float32 배열이며 C-order로 펴면 폰의 `input.f32`와 같은
    바이트 나열이 된다(`plane[rOff + dy*dstW + dx]` 배치).

    ⚠ 휘도는 **이중선형**, 색차는 **최근접**이다. 둘 다 계약이 아니라 가정이며
      (`RESIZE_INTERPOLATION_ASSUMPTION`) 폰이 그렇게 하므로 여기서도 그렇게 한다.
    """
    f32 = np.float32
    planes = {}
    for p in src.get("planes") or []:
        planes[str(p.get("name"))] = p
    for name in ("y", "u", "v"):
        if name not in planes:
            raise ParityError(f"src.planes에 '{name}' 평면이 없다 — 포맷 규약 §3")

    buf = np.frombuffer(raw, dtype=np.uint8)

    def plane_view(name: str):
        p = planes[name]
        off = int(p["offset"])
        ln = int(p["length"])
        if off < 0 or ln < 0 or off + ln > buf.size:
            raise ParityError(
                f"src 평면 '{name}'이 파일 밖을 가리킨다: offset={off} length={ln} "
                f"파일크기={buf.size}B — adb pull이 잘렸거나 매니페스트가 어긋났다"
            )
        return buf[off:off + ln], int(p["row_stride"]), int(p["pixel_stride"])

    y_buf, y_row, y_pix = plane_view("y")
    u_buf, u_row, u_pix = plane_view("u")
    v_buf, v_row, v_pix = plane_view("v")

    src_w = int(src["width"])
    src_h = int(src["height"])
    out = np.empty((3, box.dst_h, box.dst_w), dtype=np.float32)
    # 패딩 값은 **나눗셈**이다(`DetectContract.PAD_VALUE_U8 / 255f`). 아래 RGB는 역수 곱이라
    # 둘의 반올림이 1ulp 갈릴 수 있어 그대로 옮긴다.
    out[:] = f32(pad_value_u8) / f32(255)
    if box.content_w <= 0 or box.content_h <= 0:
        return out

    inv_scale = f32(1) / box.scale

    # 픽셀 중심 정렬(cv2.resize 규약). 0.5를 빼고 더하지 않으면 축소 시 반 픽셀이 밀린다.
    dy = np.arange(box.pad_y, box.pad_y + box.content_h, dtype=np.int32)
    fy = ((dy - box.pad_y).astype(f32) + f32(0.5)) * inv_scale - f32(0.5)
    fy = np.maximum(fy, f32(0))
    y0 = np.minimum(fy.astype(np.int32), src_h - 1)
    y1 = np.minimum(y0 + 1, src_h - 1)
    ay = (fy - y0.astype(f32))[:, None]      # 🔴 클램프된 y0로 계산한다(폰과 같은 순서)

    dx = np.arange(box.pad_x, box.pad_x + box.content_w, dtype=np.int32)
    fx = ((dx - box.pad_x).astype(f32) + f32(0.5)) * inv_scale - f32(0.5)
    fx = np.maximum(fx, f32(0))
    x0 = np.minimum(fx.astype(np.int32), src_w - 1)
    x1 = np.minimum(x0 + 1, src_w - 1)
    ax = (fx - x0.astype(f32))[None, :]

    r0 = (y0 * y_row)[:, None]
    r1 = (y1 * y_row)[:, None]
    c0 = (x0 * y_pix)[None, :]
    c1 = (x1 * y_pix)[None, :]
    y00 = y_buf[r0 + c0].astype(f32)
    y01 = y_buf[r0 + c1].astype(f32)
    y10 = y_buf[r1 + c0].astype(f32)
    y11 = y_buf[r1 + c1].astype(f32)
    y_top = y00 + (y01 - y00) * ax
    y_bot = y10 + (y11 - y10) * ax
    yy = y_top + (y_bot - y_top) * ay

    # 색차는 최근접. 이미 2:1로 서브샘플된 평면이라 표준 관행이다.
    cxi = (x0 >> 1)
    cr_u = ((y0 >> 1) * u_row)[:, None]
    cr_v = ((y0 >> 1) * v_row)[:, None]
    uu = (u_buf[cr_u + (cxi * u_pix)[None, :]].astype(np.int32) - 128).astype(f32)
    vv = (v_buf[cr_v + (cxi * v_pix)[None, :]].astype(np.int32) - 128).astype(f32)

    # BT.601 full range (YUV_TO_RGB_ASSUMPTION). 🔴 계약에 없는 가정이다.
    r = yy + f32(1.402) * vv
    g = yy - f32(0.344136) * uu - f32(0.714136) * vv
    b = yy + f32(1.772) * uu
    for ch in (r, g, b):
        np.clip(ch, f32(0), f32(255), out=ch)

    inv255 = f32(1) / f32(255)
    ys, ye = box.pad_y, box.pad_y + box.content_h
    xs, xe = box.pad_x, box.pad_x + box.content_w
    out[0, ys:ye, xs:xe] = r * inv255
    out[1, ys:ye, xs:xe] = g * inv255
    out[2, ys:ye, xs:xe] = b * inv255
    return out


# ══ G: 후처리 재구현 ══════════════════════════════════════════════════════
# 🔴 **순서가 규약 §5 그대로여야 한다:**
#      conf 필터 → cxcywh→xyxy → **클래스별 NMS를 letterbox 640 좌표계에서** →
#      살아남은 것만 역변환 + 원본 경계 클램프
#    예전 앱 코드는 NMS 앞에서 역변환+클램프를 했고 "같은 아핀이라 IoU 순서 불변"이라고
#    정당화했는데 **클램프는 아핀이 아니다**(독립 검증이 잡아 고쳤다). 순서를 바꾸면
#    불일치가 이식 결함이 아니라 **PC 쪽 결함**이 된다.


def postprocess(raw2d, box: Letterbox, conf_thr: float, iou_thr: float,
                class_names: dict) -> tuple[list[dict], int, float]:
    """`DetectPostprocessor.run`의 이식.

    :param raw2d: `(4+nc, A)` float32. 출력 텐서를 **채널 우선**으로 읽은 것.
    :returns: (박스 리스트(원본 좌표계), boxes_pre_nms, max_conf)
    """
    f32 = np.float32
    n_ch, n_anchors = raw2d.shape
    class_count = n_ch - 4
    if class_count < 1:
        raise ParityError(f"출력 채널이 {n_ch}개다 — 4+nc 구조가 아니다")

    cls_scores = raw2d[4:, :]
    # `np.argmax`는 동점에서 **첫 인덱스**를 고른다 — Kotlin의 엄격 `>` 비교와 같다.
    best_cls = np.argmax(cls_scores, axis=0).astype(np.int32)
    best = cls_scores[best_cls, np.arange(n_anchors)]
    # Kotlin은 maxConf를 0f에서 시작해 갱신한다 → max(0, 최대 점수).
    max_conf = float(max(f32(0), best.max())) if n_anchors else 0.0

    keep0 = np.nonzero(best >= f32(conf_thr))[0]   # 앵커 오름차순 — 폰의 삽입 순서와 같다
    pre_nms = int(keep0.size)
    if pre_nms == 0:
        return [], 0, max_conf

    cx = raw2d[0, keep0]
    cy = raw2d[1, keep0]
    hw = raw2d[2, keep0] * f32(0.5)
    hh = raw2d[3, keep0] * f32(0.5)
    # 🔴 여기까지 letterbox 640 좌표계 그대로다. 역변환은 NMS **뒤**다.
    bx1 = cx - hw
    by1 = cy - hh
    bx2 = cx + hw
    by2 = cy + hh
    score = best[keep0]
    cls = best_cls[keep0]

    # 점수 내림차순, **안정 정렬**(폰의 삽입 정렬이 동점 순서를 보존한다).
    order = np.argsort(-score, kind="stable")
    suppressed = np.zeros(pre_nms, dtype=bool)
    area = (bx2 - bx1) * (by2 - by1)
    kept: list[int] = []
    for oi in range(pre_nms):
        idx = int(order[oi])
        if suppressed[idx]:
            continue
        kept.append(idx)
        rest = order[oi + 1:]
        if rest.size == 0:
            continue
        cand = rest[(~suppressed[rest]) & (cls[rest] == cls[idx])]
        if cand.size == 0:
            continue
        ix1 = np.maximum(bx1[idx], bx1[cand])
        iy1 = np.maximum(by1[idx], by1[cand])
        ix2 = np.minimum(bx2[idx], bx2[cand])
        iy2 = np.minimum(by2[idx], by2[cand])
        iw = ix2 - ix1
        ih = iy2 - iy1
        inter = iw * ih
        union = area[idx] + area[cand] - inter
        iou = np.where(
            (iw <= f32(0)) | (ih <= f32(0)) | (union <= f32(0)),
            f32(0),
            inter / np.where(union == f32(0), f32(1), union),
        )
        suppressed[cand[iou > f32(iou_thr)]] = True

    # 4) 역변환 + 원본 경계 클램프. **살아남은 것만** 한다.
    #    ⚠ 클램프가 비대칭이다(x1/y1은 아래만, x2/y2는 위만) — 폰과 글자 그대로 같다.
    inv_scale = f32(1) / box.scale
    src_wf = f32(box.src_w)
    src_hf = f32(box.src_h)
    out: list[dict] = []
    for idx in kept:
        x1 = (bx1[idx] - box.pad_x) * inv_scale
        y1 = (by1[idx] - box.pad_y) * inv_scale
        x2 = (bx2[idx] - box.pad_x) * inv_scale
        y2 = (by2[idx] - box.pad_y) * inv_scale
        if x1 < f32(0):
            x1 = f32(0)
        if y1 < f32(0):
            y1 = f32(0)
        if x2 > src_wf:
            x2 = src_wf
        if y2 > src_hf:
            y2 = src_hf
        ci = int(cls[idx])
        out.append({
            "cls": ci,
            # 🔴 클래스는 **이름으로** 다룬다. 인덱스를 하드코딩하지 않는다 —
            #    INTERFACES.md 계약 A-4와 모델의 클래스 순서가 반대다(보고 대상이지
            #    고칠 대상이 아니다).
            "cls_name": class_names.get(str(ci), f"class_{ci}"),
            "conf": float(score[idx]),
            "x1": float(x1), "y1": float(y1), "x2": float(x2), "y2": float(y2),
        })
    return out, pre_nms, max_conf


# ══ 덤프 읽기 — 방어선이 여기 전부 있다 ═══════════════════════════════════


def _require(cond: bool, msg: str) -> None:
    if not cond:
        raise ParityError(msg)


def _read_f32(path: Path, shape: list[int], where: str):
    """헤더 없는 생 float32 나열을 읽는다. **파일 크기가 shape 곱과 다르면 죽는다**(규약 §2)."""
    want = 1
    for d in shape:
        want *= int(d)
    size = path.stat().st_size
    _require(
        size == want * F32_ITEMSIZE,
        f"{where}: {path.name}의 크기가 shape와 어긋난다 — 파일 {size}B, "
        f"shape {shape} → {want * F32_ITEMSIZE}B({want}개 float32). "
        f"adb pull이 잘렸거나 매니페스트의 shape이 틀렸다 (규약 §2)",
    )
    # little-endian 고정. 아래 load_dump가 byte_order를 이미 확인했다.
    return np.fromfile(path, dtype="<f4").reshape([int(d) for d in shape])


def _dump_label(root: Path) -> str:
    """리포트에 쓰는 짧은 이름. 규약 §2대로 덤프 디렉토리 이름은 **전부 `parity`**라
    마지막 조각만 쓰면 EP 셋이 전부 같은 이름으로 찍힌다 — 상위 런 디렉토리를 함께 낸다."""
    root = Path(root)
    return f"{root.parent.name}/{root.name}" if root.parent.name else root.name


class Dump:
    """덤프 하나(= EP 하나의 `parity/` 디렉토리)."""

    def __init__(self, root: Path, manifest: dict):
        self.root = root
        self.manifest = manifest
        self.label = _dump_label(root)


def load_dump(root: Path) -> Dump:
    """매니페스트를 읽고 **방어선을 전부 통과시킨다.** 하나라도 어긋나면 던진다."""
    _require(root.is_dir(), f"덤프 디렉토리가 없다: {root}")
    mpath = root / MANIFEST_NAME
    _require(mpath.is_file(), f"매니페스트가 없다: {mpath} (규약 §2)")
    try:
        manifest = json.loads(mpath.read_text(encoding="utf-8"))
    except (json.JSONDecodeError, UnicodeDecodeError) as exc:
        raise ParityError(f"{mpath}를 JSON으로 읽지 못했다: {exc}") from exc

    fmt = manifest.get("format")
    _require(
        fmt == FORMAT_ID,
        f"{mpath}: format={fmt!r}이 규약과 다르다(기대 {FORMAT_ID!r}). "
        f"포맷이 갈린 채로 대조하면 결과가 '이식 결함'인지 '포맷 어긋남'인지 구분되지 "
        f"않는다 — 읽지 않는다 (docs/plans/20260806_detect_parity_dump_format.md §3)",
    )
    bo = manifest.get("byte_order")
    _require(
        bo == BYTE_ORDER_ID,
        f"{mpath}: byte_order={bo!r}이다. 이 스크립트는 {BYTE_ORDER_ID!r}만 읽는다 — "
        f"바이트 순서를 **지어내지 않는다** (규약 §2)",
    )
    samples = manifest.get("samples")
    _require(
        isinstance(samples, list) and samples,
        f"{mpath}: samples가 비어 있다 — 빈 입력으로 통과시키지 않는다",
    )

    # 파일별 sha256 대조. 🔴 **adb pull이 깨진 것을 이식 결함으로 오독하지 않기 위해서다.**
    for s in samples:
        idx = s.get("index")
        for key in ("input", "output"):
            blk = s.get(key)
            _require(isinstance(blk, dict), f"{mpath}: sample {idx}에 '{key}' 블록이 없다")
            fpath = root / str(blk["file"])
            _require(fpath.is_file(), f"{mpath}: sample {idx}의 {key} 파일이 없다: {fpath}")
            want = blk.get("sha256")
            _require(
                isinstance(want, str) and want,
                f"{mpath}: sample {idx}의 {key}에 sha256이 없다. 규약 §3은 이 키를 요구한다 "
                f"— 해시 없이 대조하면 adb pull이 깨진 것을 이식 결함으로 오독할 수 있다",
            )
            got = sha256_file(fpath)
            _require(
                got.lower() == want.lower(),
                f"{mpath}: sample {idx}의 {key}({fpath.name}) 해시가 매니페스트와 다르다 — "
                f"매니페스트 {want}, 실제 {got}. **파일이 손상됐다**(adb pull 실패 등). "
                f"이 상태의 차이를 이식 결함으로 읽으면 엉뚱한 곳을 뒤진다",
            )
        # src에는 규약 §3의 예시에 sha256 키가 없다 — 있으면 대조하고 없으면 넘어간다.
        srcb = s.get("src")
        _require(isinstance(srcb, dict), f"{mpath}: sample {idx}에 'src' 블록이 없다")
        spath = root / str(srcb["file"])
        _require(spath.is_file(), f"{mpath}: sample {idx}의 src 파일이 없다: {spath}")
        swant = srcb.get("sha256")
        if isinstance(swant, str) and swant:
            sgot = sha256_file(spath)
            _require(
                sgot.lower() == swant.lower(),
                f"{mpath}: sample {idx}의 src({spath.name}) 해시가 매니페스트와 다르다 — "
                f"매니페스트 {swant}, 실제 {sgot}",
            )
    return Dump(root, manifest)


# ══ 대조 ══════════════════════════════════════════════════════════════════


def _tensor_diff(a, b, name: str) -> dict:
    """두 텐서의 **관측값**. 판정이 아니라 관측이다 — 통과/실패보다 이게 먼저다."""
    if a.shape != b.shape:
        return {
            "comparable": False,
            "reason": f"shape이 다르다: {list(a.shape)} vs {list(b.shape)}",
            "shape_a": list(a.shape),
            "shape_b": list(b.shape),
        }
    d = np.abs(a.astype("float64") - b.astype("float64"))
    nonzero = int(np.count_nonzero(d))
    return {
        "comparable": True,
        "name": name,
        "elements": int(d.size),
        "max_abs_diff": float(d.max()) if d.size else 0.0,
        "mean_abs_diff": float(d.mean()) if d.size else 0.0,
        "nonzero_count": nonzero,
        "nonzero_fraction": round(nonzero / d.size, 9) if d.size else 0.0,
        "bitwise_identical": nonzero == 0,
        # 🔴 이 분포는 **원소별 |diff|**의 분포다. 판정선이 아니라 관측이다.
        "abs_diff_dist": _np_summarize(d),
        "dist_note": (
            "원소별 |diff|의 분포다(nearest-rank, lib/stats.percentile과 같은 규칙). "
            "🔴 판정선이 아니다 — 이 대조에는 우리 고유의 허용오차가 없다"
        ),
    }


def _iou_py(a: dict, b: dict) -> float:
    ix1 = max(a["x1"], b["x1"])
    iy1 = max(a["y1"], b["y1"])
    ix2 = min(a["x2"], b["x2"])
    iy2 = min(a["y2"], b["y2"])
    iw = ix2 - ix1
    ih = iy2 - iy1
    if iw <= 0 or ih <= 0:
        return 0.0
    inter = iw * ih
    ua = (a["x2"] - a["x1"]) * (a["y2"] - a["y1"])
    ub = (b["x2"] - b["x1"]) * (b["y2"] - b["y1"])
    union = ua + ub - inter
    return 0.0 if union <= 0 else inter / union


def _compare_boxes(lhs: list[dict], rhs: list[dict], bar: Optional[dict]) -> dict:
    """살아남은 탐지 **집합**의 비교. 🔴 안전 관점에서 의미 있는 것은 raw float 차이가
    아니라 이쪽이다 — 개수·클래스가 같은가, 같은 물체를 가리키는가(IoU).

    매칭은 **클래스가 같은 것끼리 IoU 최대**로 greedy. 순서에 의존하지 않는다(양쪽의
    NMS 출력 순서가 같다고 가정하면, 그 가정이 틀렸을 때 좌표차가 폭발해서 원인을 못 찾는다).
    """
    used = [False] * len(rhs)
    pairs = []
    for i, a in enumerate(lhs):
        best_j, best_iou = -1, -1.0
        for j, b in enumerate(rhs):
            if used[j] or b["cls_name"] != a["cls_name"]:
                continue
            v = _iou_py(a, b)
            if v > best_iou:
                best_iou, best_j = v, j
        if best_j >= 0:
            used[best_j] = True
            pairs.append((i, best_j, best_iou))

    matched = []
    max_xy = 0.0
    max_conf = 0.0
    min_iou = None
    for i, j, v in pairs:
        a, b = lhs[i], rhs[j]
        xy = max(abs(a["x1"] - b["x1"]), abs(a["y1"] - b["y1"]),
                 abs(a["x2"] - b["x2"]), abs(a["y2"] - b["y2"]))
        cd = abs(a["conf"] - b["conf"])
        max_xy = max(max_xy, xy)
        max_conf = max(max_conf, cd)
        min_iou = v if min_iou is None else min(min_iou, v)
        matched.append({
            "lhs_index": i, "rhs_index": j, "cls_name": a["cls_name"],
            "iou": round(v, 9), "max_xy_diff_px": round(xy, 9),
            "conf_diff": round(cd, 9),
        })

    def _multiset(bs):
        out: dict[str, int] = {}
        for b in bs:
            out[b["cls_name"]] = out.get(b["cls_name"], 0) + 1
        return out

    lhs_cls = _multiset(lhs)
    rhs_cls = _multiset(rhs)
    # 🔴 **양쪽 다 박스 0개면 "일치했다"고 말하지 않는다.** 0 == 0 은 True 이고 {} == {} 도
    #    True 라, 그대로 두면 count_equal · class_counts_equal · all_matched 가 전부 True 가
    #    되어 요약만 읽는 사람에게 **완전 합격**으로 보인다 — 실제로는 **비교된 박스가 한 개도
    #    없다.** 이 라운드가 답하려는 질문이 "탐지 집합이 같은가"인데 두 집합이 다 비었으면
    #    그 질문에 답한 것이 아니다. 그래서 불리언을 **None(= 비교할 것이 없었다)** 으로 낸다.
    #    (독립 검증 지적. 합성이 아니라 실제 모델 출력에서 conf 최대 0.0013 인 덤프로 재현됐다.)
    nothing_to_compare = not lhs and not rhs
    block = {
        # ── 관측값이 먼저다 ───────────────────────────────────────────────
        "count_lhs": len(lhs),
        "count_rhs": len(rhs),
        "count_mismatch": abs(len(lhs) - len(rhs)),
        "class_counts_lhs": lhs_cls,
        "class_counts_rhs": rhs_cls,
        "matched": len(pairs),
        "unmatched_lhs": len(lhs) - len(pairs),
        "unmatched_rhs": len(rhs) - len(pairs),
        "max_xy_diff_px": round(max_xy, 9) if pairs else None,
        "max_conf_diff": round(max_conf, 9) if pairs else None,
        "min_matched_iou": round(min_iou, 9) if min_iou is not None else None,
        "pairs": matched,
        # 🔴 **비교된 박스가 몇 개였는가.** 아래 불리언을 읽기 전에 이 수를 먼저 본다.
        "boxes_total": len(lhs) + len(rhs),
        "nothing_to_compare": nothing_to_compare,
        # ── 집합이 같은가 (3값). 🔴 IoU에는 우리 기준선이 없어 관측값만 낸다 ──
        "count_equal": None if nothing_to_compare else len(lhs) == len(rhs),
        "class_counts_equal": None if nothing_to_compare else lhs_cls == rhs_cls,
        "all_matched": None if nothing_to_compare else len(pairs) == len(lhs) == len(rhs),
        "set_note": (
            "count_equal·class_counts_equal·all_matched까지가 '집합이 같은가'의 불리언이고, "
            "IoU는 **관측값만** 낸다 — 'IoU 몇 이상이면 같은 박스'라는 바를 우리는 갖고 있지 "
            "않고 지어내지 않는다. "
            "🔴 **셋은 3값이다: true / false / null.** null은 '일치'가 아니라 "
            "**양쪽 다 박스가 0개여서 비교할 것이 없었다**는 뜻이다(boxes_total로 확인)"
        ),
    }
    block.update(_bar_verdict(max_xy if pairs else None,
                              max_conf if pairs else None,
                              abs(len(lhs) - len(rhs)), bar,
                              boxes_total=len(lhs) + len(rhs), pairs_count=len(pairs)))
    return block


def _bar_verdict(max_xy, max_conf, count_mismatch, bar: Optional[dict],
                 *, boxes_total: int, pairs_count: int) -> dict:
    """빌린 바와의 대조. 🔴 **합격 판정이 아니다.**

    🔴 `over_borrowed_bar`도 **3값**이다. 매칭된 쌍이 0개면 좌표차·신뢰도차가 `None`이라
    그 두 항목을 건너뛰게 되는데, 그때 `False`("바 안이다")를 내면 **잰 적 없는 것을 통과로
    말하는 것**이다. 실제로 그 구멍이 있었다: 양쪽에 박스가 있는데 클래스 이름이 안 맞아
    한 쌍도 매칭되지 않은 덤프에서 `over_borrowed_bar: false`가 나왔다(독립 검증 지적).

    다만 `count_mismatch`는 쌍이 없어도 **잴 수 있다** — 그것이 이미 바를 넘었으면 `True`가
    맞다. 그래서 "넘은 것이 하나라도 있으면 True, 아니면 잴 수 있었을 때만 False"로 낸다.
    """
    if not bar:
        return {
            "borrowed_bar": None,
            "borrowed_bar_note": (
                "빌릴 바를 읽지 못했다(metadata.json의 parity_check). 관측값만 낸다"
            ),
        }
    over = []
    if bar.get("max_xy_diff_px") is not None and max_xy is not None:
        if max_xy > float(bar["max_xy_diff_px"]):
            over.append("max_xy_diff_px")
    if bar.get("max_conf_diff") is not None and max_conf is not None:
        if max_conf > float(bar["max_conf_diff"]):
            over.append("max_conf_diff")
    if bar.get("count_mismatch") is not None:
        if count_mismatch > int(bar["count_mismatch"]):
            over.append("count_mismatch")
    if over:
        verdict = True
        undecidable = None
    elif boxes_total == 0:
        verdict = None
        undecidable = "양쪽 다 박스 0개다 — 바와 견줄 것이 없었다"
    elif pairs_count == 0:
        verdict = None
        undecidable = (
            "매칭된 쌍이 0개라 좌표차·신뢰도차를 재지 못했다 — count_mismatch만으로 "
            "'바 안'이라고 말하지 않는다"
        )
    else:
        verdict = False
        undecidable = None
    return {
        "borrowed_bar": dict(bar),
        "over_borrowed_bar": verdict,
        "over_borrowed_bar_items": over,
        "over_borrowed_bar_undecidable": undecidable,
        "borrowed_bar_note": (
            "🔴 **이 바는 빌린 것이고 합격 판정이 아니다.** 출처는 상류의 "
            "models/det_c4b_loli0_640/metadata.json `parity_check`이며, 상류가 **PC에서 "
            "PyTorch↔ONNX**를 잰 값이다. 우리 대조는 **폰 ORT↔PC ORT**로 비교 자체가 다르다 "
            "— '넘었다/못 넘었다'까지만 말한다. 종료 코드도 흔들지 않는다"
        ),
    }


def _check_ort_versions(dumps, pc_version: str, warnings: list[str]) -> list[dict]:
    """폰이 기록한 ORT 버전과 PC의 것을 대조한다. 🔴 **다르다고 죽이지 않는다.**

    버전이 달라도 대조는 성립한다 — 달라지는 것은 **결과를 읽는 법**이다. 같은 버전에서
    값이 다르면 빌드/플랫폼 차이(arm64 vs x86-64: 커널 구현·벡터화·연산 순서)로 좁혀지지만,
    버전이 다르면 그 좁히기가 불가능하다. 그 사실을 경고로 남기고 요약에도 싣는다.

    🔴 폰 쪽 값은 **런타임이 말한 것**(`ort_version_runtime`)을 쓴다. gradle에 선언한
    `ort_version_declared`는 우리가 적은 문자열이라 근거가 못 된다 — 모델 해시를
    metadata.json 선언값 대신 로드 바이트에서 계산하는 것과 같은 논거다.
    """
    out = []
    for d in dumps:
        rt = (d.manifest.get("runtime") or {})
        runtime_v = rt.get("ort_version_runtime")
        declared_v = rt.get("ort_version_declared")
        row = {
            "dump": d.label,
            "phone_ort_version_runtime": runtime_v,
            "phone_ort_version_declared": declared_v,
            "pc_ort_version": pc_version,
            "same_version": (runtime_v == pc_version) if runtime_v else None,
        }
        if rt.get("ort_version_mismatch"):
            warnings.append(f"{d.label}: {rt['ort_version_mismatch']}")
        if not rt:
            warnings.append(
                f"{d.label}: 매니페스트에 `runtime` 블록이 없다(규약 §3) — **폰 ORT 버전을 "
                f"알 수 없다.** PC는 {pc_version}이다. 같은 버전인지 확인하지 못했으므로 "
                f"'값 차이는 빌드/플랫폼 차이'라는 좁히기를 이 덤프에는 쓸 수 없다"
            )
        elif runtime_v is None:
            warnings.append(
                f"{d.label}: `runtime.ort_version_runtime`이 null이다 — 앱이 "
                f"OrtEnvironment.getVersion()을 읽지 못했다. 선언값은 {declared_v!r}이지만 "
                f"**그것은 build.gradle.kts에 적은 문자열이지 로드된 .so가 말한 값이 아니다.** "
                f"버전 일치는 **미검증**이다"
            )
        elif runtime_v != pc_version:
            warnings.append(
                f"{d.label}: 🔴 **폰 ORT {runtime_v} ≠ PC ORT {pc_version}** — 아래 F의 "
                f"차이를 '빌드/플랫폼 차이'로 좁힐 수 없다. 버전 차이일 수도 있다. "
                f"같은 버전으로 맞춰 다시 잴 것"
            )
        out.append(row)
    return out


def _set_verdict(block: dict) -> Optional[bool]:
    """한 샘플의 '탐지 집합이 같은가'. **비교할 것이 없었으면 None**이지 True가 아니다."""
    matched, counts = block.get("all_matched"), block.get("class_counts_equal")
    if matched is None or counts is None:
        return None
    return bool(matched and counts)


def _tri_all(values) -> Optional[bool]:
    """3값 all(). 하나라도 False면 False, 하나도 판정 못 했으면 None, 그 밖은 True.

    🔴 `all()`을 그대로 쓰면 **None이 falsy라 False로 접힌다** — "판정하지 못했다"가
    "불일치했다"로 둔갑한다. 반대로 None을 빼고 세면 전부 None일 때 빈 all()이 True를 내서
    이번엔 "일치했다"가 된다. 둘 다 없는 근거를 만드는 것이라 3값으로 남긴다.
    """
    seen = [v for v in values if v is not None]
    if any(v is False for v in seen):
        return False
    return True if seen else None


def _tri_any(values) -> Optional[bool]:
    """3값 any(). 하나라도 True면 True, 하나도 판정 못 했으면 None, 그 밖은 False."""
    seen = [v for v in values if v is not None]
    if any(v is True for v in seen):
        return True
    return False if seen else None


def _read_bar(model_path: Path) -> tuple[Optional[dict], Optional[str]]:
    """빌릴 바를 모델 옆 `metadata.json`에서 읽는다. 🔴 **숫자를 이 스크립트에 적지 않는다.**"""
    meta_path = model_path.parent / METADATA_NAME
    if not meta_path.is_file():
        return None, f"{meta_path}가 없어 빌릴 바가 없다 — 관측값만 낸다"
    try:
        meta = json.loads(meta_path.read_text(encoding="utf-8"))
    except (json.JSONDecodeError, UnicodeDecodeError) as exc:
        return None, f"{meta_path}를 읽지 못했다({exc}) — 관측값만 낸다"
    pc = meta.get("parity_check")
    if not isinstance(pc, dict):
        return None, f"{meta_path}에 parity_check 블록이 없다 — 관측값만 낸다"
    bar = {
        "source": str(meta_path),
        "images": pc.get("images"),
        "max_xy_diff_px": pc.get("max_xy_diff_px"),
        "max_conf_diff": pc.get("max_conf_diff"),
        "count_mismatch": pc.get("count_mismatch"),
    }
    return bar, None


# ══ 본체 ══════════════════════════════════════════════════════════════════


def _check_model(dumps: list[Dump], model_path: Path) -> dict:
    """`model.sha256` ↔ PC가 여는 `.onnx`의 해시. **다르면 죽는다** — 다른 모델 둘의
    비교는 무의미하다."""
    _require(model_path.is_file(), f"모델 파일이 없다: {model_path}")
    pc_sha = sha256_file(model_path)
    for d in dumps:
        declared = ((d.manifest.get("model") or {}).get("sha256") or "").lower()
        _require(
            declared == pc_sha.lower(),
            f"{d.root}: 폰이 로드한 모델의 sha256({declared or '없음'})과 PC가 여는 "
            f"{model_path.name}({pc_sha})이 다르다. **다른 모델 둘을 비교하는 것은 "
            f"무의미하다** — 같은 파일을 --model로 주거나 덤프를 다시 뜰 것 (규약 §3)",
        )
    return {"path": str(model_path.resolve()), "sha256": pc_sha}


def _decode_output(arr):
    """`[1, C, A]` → `(C, A)`. 배치 축이 1이 아니면 죽는다."""
    if arr.ndim == 3:
        _require(arr.shape[0] == 1, f"출력 배치 축이 1이 아니다: {list(arr.shape)}")
        return arr[0]
    if arr.ndim == 2:
        return arr
    raise ParityError(f"출력 텐서 차원이 2도 3도 아니다: {list(arr.shape)}")


def _run_dump(dump: Dump, sess, model_io: dict, bar: Optional[dict],
              warnings: list[str]) -> dict:
    m = dump.manifest
    class_block = m.get("classes") or {}
    classes = class_block.get("names") or {}
    # 🔴 **이름표가 비면 PC 쪽 박스가 전부 `class_<인덱스>`로 떨어진다.** 폰 쪽은 박스에
    #   `cls_name`을 실어 오므로 **한 쌍도 매칭되지 않는데 경고가 하나도 없었다** — 사람은
    #   "G 탐지집합 불일치"만 보고 **후처리 이식을 뒤지게 된다.** 원인은 매니페스트다.
    #   (독립 검증 지적. `classes.names={}`인 덤프로 재현됐다.)
    if not classes:
        declared = class_block.get("count")
        warnings.append(
            f"{dump.label}: 🔴 **매니페스트 classes.names가 비어 있다**"
            + (f" (count={declared}, raw={class_block.get('raw')!r}로는 클래스가 있다고 "
               f"말한다 — 자기모순이다)" if declared or class_block.get("raw") else "")
            + ". PC 쪽 박스는 이름 대신 `class_<인덱스>`로 떨어지므로, 폰 박스가 진짜 이름을 "
            "갖고 있으면 **한 쌍도 매칭되지 않는다.** 그때 나오는 '탐지집합 불일치'는 "
            "**후처리 이식 결함이 아니라 매니페스트 결함**이다 — 여기부터 볼 것"
        )
    thresholds = m.get("thresholds") or {}
    conf_thr = thresholds.get("conf")
    iou_thr = thresholds.get("iou")
    _require(
        isinstance(conf_thr, (int, float)) and isinstance(iou_thr, (int, float)),
        f"{dump.root}: thresholds.conf/iou를 숫자로 읽지 못했다 "
        f"(conf={conf_thr!r} iou={iou_thr!r}). 🔴 0으로 뭉개면 전 앵커가 통과해 "
        f"G가 다른 것을 재게 된다 — 후처리를 시작하지 않는다",
    )

    src_info = m.get("source") or {}
    if not src_info.get("rotation_applied", False):
        # 규약 §4. **반드시 낸다.**
        warnings.append(
            f"{dump.label}: 🔴 **rotation_applied=false** — 앱이 "
            f"ImageProxy.imageInfo.rotationDegrees"
            f"({src_info.get('rotation_degrees')}°)를 적용하지 않았다(알려진 이슈 29). "
            f"모델은 **옆으로 누운 장면**을 본다. 이식 정확성 질문은 회전과 직교하므로 이 "
            f"대조는 성립하지만, 🔴 **이 덤프로 '모델이 잘 맞힌다/못 맞힌다'를 말하면 "
            f"안 된다** — 입력이 누워 있어 그 판단은 애초에 불가능하다 (규약 §4)"
        )

    in_name = model_io["input_name"]
    out_name = model_io["output_name"]
    results = []
    for s in m["samples"]:
        idx = s.get("index")
        where = f"{dump.label} sample {idx}"
        src = s["src"]
        inp = s["input"]
        outp = s["output"]

        # 🔴 레이아웃을 **추측하지 않는다.** 아래 전부가 NCHW/NCA 전제로 축을 잡는다 —
        #    다른 레이아웃을 조용히 그렇게 읽으면 채널이 섞인 채로 그럴듯한 숫자가 나온다.
        for blk, want, key in ((inp, "NCHW", "input"), (outp, "NCA", "output")):
            got = blk.get("layout")
            _require(
                got == want,
                f"{where}: {key}.layout={got!r}인데 이 스크립트는 {want!r}만 읽는다 "
                f"(규약 §3). 레이아웃을 지어내면 축이 섞인 채로 그럴듯한 차이가 나온다",
            )
        for blk, dtype, key in ((inp, "float32", "input"), (outp, "float32", "output")):
            got = blk.get("dtype")
            _require(
                got == dtype,
                f"{where}: {key}.dtype={got!r}인데 이 스크립트는 {dtype!r}만 읽는다 (규약 §2)",
            )

        # 🔴 **샘플의 shape을 그래프와 대조한다.** `_read_f32`의 크기 검사는 원소 **곱**만
        #   보므로 곱이 같은 잘못된 축 순서가 그대로 통과한다. 실제로 output.shape을
        #   [1,6,8400] → [1,8400,6]으로 바꾼 덤프가 exit 0으로 완주하면서 `class_4171` 같은
        #   쓰레기 클래스를 냈고, 원인을 말하는 문장은 어디에도 없었다(독립 검증 지적).
        #   layout 문자열 검사가 막으려던 것이 바로 그건데, **축 순서를 실제로 나르는 것은
        #   layout 문자열이 아니라 shape이다.** 여기서 죽인다.
        for key, blk, want in (("input", inp, model_io["input_shape"]),
                               ("output", outp, model_io["output_shape"])):
            _require(
                _shape_matches(list(blk["shape"]), want),
                f"{where}: {key}.shape={list(blk['shape'])}이 PC 그래프의 {want}과 다르다 — "
                f"원소 수가 같아도 **축 순서가 다르면 다른 텐서**이고, 그대로 읽으면 그럴듯한 "
                f"쓰레기가 나온다 (규약 §3)",
            )

        phone_in = _read_f32(dump.root / str(inp["file"]), list(inp["shape"]), where)
        phone_out = _read_f32(dump.root / str(outp["file"]), list(outp["shape"]), where)
        _require(
            phone_in.ndim == 4 and phone_in.shape[0] == 1 and phone_in.shape[1] == 3,
            f"{where}: input.shape={list(inp['shape'])}이 [1,3,H,W]가 아니다 — "
            f"NCHW 전제로 축을 잡을 수 없다",
        )

        # PC가 **자기 힘으로** 계산한 letterbox. 매니페스트 값을 그대로 쓰면 기하 이식
        # 결함이 숨는다 — 대신 둘이 같은지를 관측으로 낸다.
        box = letterbox(int(src["width"]), int(src["height"]),
                        int(phone_in.shape[-1]), int(phone_in.shape[-2]))
        declared_lb = s.get("letterbox") or {}
        lb_agrees = all(
            declared_lb.get(k) is None or _num_eq(declared_lb.get(k), v)
            for k, v in box.as_dict().items()
        )
        if not lb_agrees:
            # 🔴 조용히 넘기면 E의 차이가 "letterbox 기하가 다르다"에서 온 것인데도
            #    "전처리 이식 결함"으로 읽힌다. 원인을 먼저 말한다.
            warnings.append(
                f"{where}: 🔴 **letterbox 기하가 어긋난다** — 매니페스트 {declared_lb} vs "
                f"PC 계산 {box.as_dict()}. 아래 E의 차이는 전처리 이식이 아니라 **기하가 "
                f"다른 것**일 수 있다(정렬 가정 center가 그중 하나다 — "
                f"LETTERBOX_ALIGN_ASSUMPTION)"
            )
        pad_u8 = declared_lb.get("pad_value_u8")
        if not isinstance(pad_u8, int):
            raise ParityError(
                f"{where}: letterbox.pad_value_u8을 정수로 읽지 못했다({pad_u8!r}) — "
                f"패딩 값을 지어내지 않는다 (규약 §3)"
            )

        # ── E: 원본 평면 → PC 전처리 vs 폰 input.f32 ──────────────────────
        raw_src = (dump.root / str(src["file"])).read_bytes()
        pc_in = preprocess(raw_src, src, box, pad_u8)
        e_diff = _tensor_diff(pc_in.reshape(-1), phone_in.reshape(-1), "input_tensor")

        # ── F(본진): 폰 input.f32 → PC ORT vs 폰 output.f32 ───────────────
        feed = {in_name: phone_in.reshape([int(d) for d in inp["shape"]]).astype("float32")}
        pc_out = _decode_output(np.asarray(sess.run([out_name], feed)[0]))
        phone_out2d = _decode_output(phone_out)
        f_diff = _tensor_diff(pc_out, phone_out2d, "output_tensor")
        # 🔴 raw float 차이만으로는 안전을 말할 수 없다 — **같은 후처리로 디코딩한 탐지
        #    집합**이 같은지를 함께 낸다.
        f_boxes_pc, _, _ = postprocess(pc_out, box, conf_thr, iou_thr, classes)
        f_boxes_phone_tensor, _, _ = postprocess(phone_out2d, box, conf_thr, iou_thr, classes)
        f_set = _compare_boxes(f_boxes_phone_tensor, f_boxes_pc, bar)

        # ── G: 폰 output.f32 → PC 후처리 vs 폰 boxes ──────────────────────
        g_boxes, g_pre, g_maxconf = postprocess(phone_out2d, box, conf_thr, iou_thr, classes)
        phone_boxes = _normalize_phone_boxes(s.get("boxes") or [], classes, where, warnings)
        g_cmp = _compare_boxes(phone_boxes, g_boxes, bar)
        pre_declared = s.get("boxes_pre_nms")
        if pre_declared is not None and int(pre_declared) != int(g_pre):
            # conf 필터까지는 같아야 한다(같은 텐서·같은 임계). 여기서 갈리면 NMS보다 **앞**의
            # 문제이므로, 최종 박스 수만 보고 NMS를 뒤지지 않도록 따로 말한다.
            warnings.append(
                f"{where}: 🔴 **boxes_pre_nms가 다르다** — 폰 {pre_declared} vs PC {g_pre}. "
                f"같은 출력 텐서에 같은 conf 임계를 걸었는데 통과 개수가 다르다는 뜻이라 "
                f"**NMS보다 앞**(conf 필터·클래스 최대값)에서 갈렸다"
            )

        # ── E2E(참고): 원본 평면 → PC 전 구간 vs 폰 boxes ─────────────────
        e2e_out = _decode_output(np.asarray(
            sess.run([out_name], {in_name: pc_in.reshape(1, *pc_in.shape).astype("float32")})[0]
        ))
        e2e_boxes, _, _ = postprocess(e2e_out, box, conf_thr, iou_thr, classes)
        e2e_cmp = _compare_boxes(phone_boxes, e2e_boxes, bar)

        results.append({
            "index": idx,
            "t_recv_ns": s.get("t_recv_ns"),
            "src": {"width": src.get("width"), "height": src.get("height"),
                    "file": src.get("file")},
            "letterbox_pc": box.as_dict(),
            "letterbox_declared": declared_lb,
            "letterbox_agrees": lb_agrees,
            "letterbox_note": (
                "PC는 letterbox를 **자기 힘으로 계산**하고 매니페스트 값과 대조만 한다 — "
                "선언값을 그대로 쓰면 기하 이식 결함이 숨는다"
            ),
            "E_preprocess": e_diff,
            "F_inference": f_diff,
            "F_detection_set": f_set,
            "G_postprocess": g_cmp,
            "G_boxes_pre_nms_pc": g_pre,
            "G_boxes_pre_nms_declared": pre_declared,
            "G_boxes_pre_nms_equal": (
                None if pre_declared is None else int(pre_declared) == int(g_pre)
            ),
            "G_max_conf_pc": round(g_maxconf, 9),
            "E2E": e2e_cmp,
        })

    return {
        "dump_dir": str(dump.root.resolve()),
        "dump_label": dump.label,
        "ep": m.get("ep"),
        "classes": m.get("classes"),
        "thresholds": {"conf": conf_thr, "iou": iou_thr},
        "source": src_info,
        "rotation_applied": bool(src_info.get("rotation_applied", False)),
        "samples": results,
        "sample_count": len(results),
        "sample_count_note": (
            f"K={len(results)}은 **통계적 표본이 아니다**(규약 §9). "
            f"'{len(results)}장에서 일치했다'를 '항상 일치한다'로 쓰지 않는다"
        ),
    }


def _num_eq(a, b) -> bool:
    if isinstance(a, str) or isinstance(b, str):
        return str(a) == str(b)
    try:
        # float32 왕복에서 생기는 표기 차이만 흡수한다. 값 자체가 다르면 False다.
        return abs(float(a) - float(b)) <= 1e-6 * max(1.0, abs(float(b)))
    except (TypeError, ValueError):
        return False


def _shape_matches(declared, actual) -> bool:
    """선언 shape이 그래프 shape과 같은가. 그래프 쪽 동적 축(문자열/None)은 대조하지 않는다.

    ⚠ 동적 축을 "무엇이든 맞다"로 넘기는 것은 **모르는 것을 맞다고 하지 않기 위해서**다 —
    이 모델은 `dynamic: false`로 export 됐으므로(metadata.json) 실무에서는 전부 정수다.
    """
    if not isinstance(declared, (list, tuple)) or len(declared) != len(actual):
        return False
    for want, got in zip(declared, actual):
        if not isinstance(got, int):   # 동적 축 — 대조 불가
            continue
        try:
            if int(want) != got:
                return False
        except (TypeError, ValueError):
            return False
    return True


def _normalize_phone_boxes(boxes, classes: dict, where: str, warnings: list[str]) -> list[dict]:
    """폰이 낸 박스를 비교 가능한 형태로. 🔴 **클래스는 이름으로 다룬다.**"""
    out = []
    # 🔴 **이름을 못 붙인 박스가 있으면 말한다.** `classes.names`가 비어 있으면 PC 쪽은
    #   `class_0`/`class_1`로 떨어지고 폰 쪽은 `person`/`stairs`라 **한 쌍도 매칭되지 않는데
    #   경고가 하나도 없었다** — 사람은 "G 탐지집합 불일치"만 보고 후처리 이식을 뒤지게 된다.
    #   원인이 매니페스트라는 것을 여기서 말한다(독립 검증 지적).
    unnamed = 0
    for b in boxes:
        ci = b.get("cls")
        name = b.get("cls_name")
        mapped = classes.get(str(ci)) if ci is not None else None
        if name and mapped and name != mapped:
            warnings.append(
                f"{where}: 박스의 cls_name={name!r}과 매니페스트 classes[{ci}]={mapped!r}이 "
                f"다르다 — 매니페스트가 자기모순이다. 비교에는 cls_name을 썼다"
            )
        if not name and not mapped:
            unnamed += 1
        out.append({
            "cls": ci,
            "cls_name": name or mapped or f"class_{ci}",
            "conf": float(b.get("conf", 0.0)),
            "x1": float(b["x1"]), "y1": float(b["y1"]),
            "x2": float(b["x2"]), "y2": float(b["y2"]),
        })
    if unnamed:
        warnings.append(
            f"{where}: 박스 {unnamed}개에 클래스 **이름이 없다** — `cls_name`도 없고 "
            f"매니페스트 `classes.names`에도 그 인덱스가 없다(names 항목 "
            f"{len(classes)}개). `class_<인덱스>`로 임시 이름을 붙여 비교했으므로, 반대편이 "
            f"진짜 이름을 갖고 있으면 **한 쌍도 매칭되지 않는다.** 그때 나오는 '탐지집합 "
            f"불일치'는 후처리 이식 결함이 아니라 **매니페스트 결함**이다"
        )
    return out


def _cross_ep(dumps: list[Dump]) -> list[dict]:
    """🔴 **폰 EP끼리도 서로 대조한다.** NNAPI가 GPU로 내려가는 것을 이미 실측했고, GPU
    경로는 fp16으로 떨어질 수 있다 — 같은 모델·같은 입력에 답이 달라지는 것은 성능 문제가
    아니라 **안전 문제**다(규약 §8)."""
    out = []
    for i in range(len(dumps)):
        for j in range(i + 1, len(dumps)):
            a, b = dumps[i], dumps[j]
            a_by_idx = {s.get("index"): s for s in a.manifest["samples"]}
            b_by_idx = {s.get("index"): s for s in b.manifest["samples"]}
            shared = sorted(set(a_by_idx) & set(b_by_idx), key=lambda v: (v is None, v))
            pair = {
                "lhs": {"dir": a.label, "ep": a.manifest.get("ep")},
                "rhs": {"dir": b.label, "ep": b.manifest.get("ep")},
                "shared_sample_indices": shared,
                "samples": [],
                "note": (
                    "같은 샘플 인덱스의 output.f32끼리 비교한다. ⚠ **입력 텐서가 같은지도 "
                    "함께 본다** — 다른 프레임을 찍은 덤프면 출력이 다른 것이 당연하다"
                ),
            }
            for idx in shared:
                sa, sb = a_by_idx[idx], b_by_idx[idx]
                ta = _read_f32(a.root / sa["output"]["file"], list(sa["output"]["shape"]),
                               f"{a.label} sample {idx}")
                tb = _read_f32(b.root / sb["output"]["file"], list(sb["output"]["shape"]),
                               f"{b.label} sample {idx}")
                ia = _read_f32(a.root / sa["input"]["file"], list(sa["input"]["shape"]),
                               f"{a.label} sample {idx}")
                ib = _read_f32(b.root / sb["input"]["file"], list(sb["input"]["shape"]),
                               f"{b.label} sample {idx}")
                in_same = _tensor_diff(ia.reshape(-1), ib.reshape(-1), "input_tensor")
                pair["samples"].append({
                    "index": idx,
                    "input_tensor": in_same,
                    "same_input": bool(in_same.get("bitwise_identical")),
                    "output_tensor": _tensor_diff(_decode_output(ta), _decode_output(tb),
                                                  "output_tensor"),
                })
            out.append(pair)
    return out


def main() -> int:
    parser = common_argparser()
    parser.add_argument(
        "--dump", action="append", default=[], required=True,
        help=(
            "폰에서 회수한 parity 덤프 디렉토리(안에 parity.json이 있다). "
            "**여러 번 줄 수 있다** — 그러면 EP끼리도 서로 대조한다"
        ),
    )
    parser.add_argument(
        "--model", default=str(DEFAULT_MODEL),
        help=(
            f"PC ORT가 열 .onnx (기본 {DEFAULT_MODEL.name}). "
            f"매니페스트의 model.sha256과 다르면 죽는다"
        ),
    )
    parser.add_argument("--label", default="", help="이 대조에 붙일 메모")
    args = parser.parse_args()

    paths = init_run(stage="detect_parity", script_file=__file__, args=args)

    warnings: list[str] = []
    try:
        _require_deps()
        model_path = Path(args.model)
        bar, bar_note = _read_bar(model_path)
        if bar_note:
            warnings.append(bar_note)

        dumps = [load_dump(Path(d)) for d in args.dump]
        model_block = _check_model(dumps, model_path)

        sess = ort.InferenceSession(str(model_path), providers=[PC_PROVIDER])
        resolved = list(sess.get_providers())
        if PC_PROVIDER not in resolved:
            warnings.append(
                f"PC 세션이 {PC_PROVIDER}로 열리지 않았다(실제 {resolved}) — F의 차이가 "
                f"폰↔PC가 아니라 PC 쪽 EP 차이일 수 있다"
            )
        model_io = {
            "input_name": sess.get_inputs()[0].name,
            "input_shape": list(sess.get_inputs()[0].shape),
            "output_name": sess.get_outputs()[0].name,
            "output_shape": list(sess.get_outputs()[0].shape),
        }
        for d in dumps:
            dm = d.manifest.get("model") or {}
            for key, got in (("input_name", model_io["input_name"]),
                             ("output_name", model_io["output_name"])):
                if dm.get(key) and dm.get(key) != got:
                    warnings.append(
                        f"{d.label}: 매니페스트 model.{key}={dm.get(key)!r}이 PC 그래프의 "
                        f"{got!r}과 다르다 — 같은 파일인데 이름이 다를 수 없다"
                    )
            # 🔴 **shape을 그래프와 대조한다.** `_read_f32`의 크기 검사는 원소 **곱**만 보므로
            #   곱이 같은 잘못된 축 순서가 전부 통과한다. 실제로 output.shape을
            #   [1,6,8400] → [1,8400,6]으로 바꿔 넣었더니 exit 0으로 완주하면서 `class_4171`
            #   같은 쓰레기 클래스를 냈고, 근본 원인 문장은 어디에도 안 나왔다(독립 검증).
            #   layout 문자열 검사가 막으려던 것이 바로 그건데, **축 순서를 실제로 나르는 것은
            #   layout 문자열이 아니라 shape이다.** 여기서 죽인다 — 지어내서 맞추지 않는다.
            for key, got in (("input_shape", model_io["input_shape"]),
                             ("output_shape", model_io["output_shape"])):
                declared = dm.get(key)
                if declared is None:
                    continue
                if not _shape_matches(declared, got):
                    raise ParityError(
                        f"{d.label}: 매니페스트 model.{key}={declared}이 PC 그래프의 {got}과 "
                        f"다르다 — 원소 수가 같아도 **축 순서가 다르면 다른 텐서**이고, 그대로 "
                        f"읽으면 그럴듯한 쓰레기가 나온다. 폰과 PC가 같은 모델을 열지 않았거나 "
                        f"매니페스트가 틀렸다"
                    )

        # 🔴 **폰 ORT 버전과 PC ORT 버전을 대조한다.** 이 라운드의 핵심 주장이 "둘이 같은
        #   버전이니 값이 다르면 버전 차이가 아니라 빌드/플랫폼 차이"인데, 그 전제를 확인하지
        #   않으면 **주장이 아니라 가정**이다. 규약 §3의 `runtime` 블록이 그래서 있다.
        #   ⚠ 다르다고 **죽이지 않는다** — 버전이 달라도 대조 자체는 성립하고, 그 사실이
        #   결과를 읽는 법을 바꿀 뿐이다. 그래서 경고로 낸다.
        phone_runtimes = _check_ort_versions(dumps, ort.__version__, warnings)

        per_dump = [_run_dump(d, sess, model_io, bar, warnings) for d in dumps]
        cross = _cross_ep(dumps) if len(dumps) > 1 else []
        if len(dumps) == 1:
            warnings.append(
                "덤프가 하나뿐이라 **EP 간 대조를 하지 못했다**(규약 §8). NNAPI가 GPU로 "
                "내려가는 것을 이미 실측했고 fp16 강등 가능성은 안전 문제다 — EP별 덤프를 "
                "모아 다시 돌릴 것"
            )
    except ParityError as exc:
        LOG.error("대조가 성립하지 않는다: %s", exc)
        return 2

    # ── 요약: 관측값이 먼저, 빌린 바는 그 다음 ────────────────────────────
    e_max = [s["E_preprocess"].get("max_abs_diff") for d in per_dump for s in d["samples"]]
    f_max = [s["F_inference"].get("max_abs_diff") for d in per_dump for s in d["samples"]]
    e_max = [v for v in e_max if v is not None]
    f_max = [v for v in f_max if v is not None]
    g_over = [
        s["G_postprocess"].get("over_borrowed_bar") for d in per_dump for s in d["samples"]
    ]
    e2e_over = [s["E2E"].get("over_borrowed_bar") for d in per_dump for s in d["samples"]]
    g_set_ok = _tri_all(_set_verdict(s["G_postprocess"])
                        for d in per_dump for s in d["samples"])
    f_set_ok = _tri_all(_set_verdict(s["F_detection_set"])
                        for d in per_dump for s in d["samples"])
    # 🔴 **비교된 박스 총수.** 이 값이 0이면 위 두 불리언은 null이고, 그건 '일치'가 아니라
    #    '비교할 것이 없었다'다. 요약에 이 수가 없어서 빈 대조가 합격처럼 읽혔다(독립 검증).
    boxes_total = sum(
        (s["G_postprocess"].get("boxes_total") or 0) for d in per_dump for s in d["samples"]
    )
    n_samples = sum(d["sample_count"] for d in per_dump)

    summary = {
        "run_ts": paths.run_ts,
        "label": args.label,
        "format": FORMAT_ID,
        "format_spec": "docs/plans/20260806_detect_parity_dump_format.md",
        "scope_note": (
            "🔴 **이것은 '모델이 옳다'의 증거가 아니다.** 이식이 값을 안 바꿨다는 증거일 "
            "뿐이다. mAP·누락률은 정답 라벨이 필요하고 여기에 없다"
        ),
        "model": model_block,
        "pc_runtime": {
            "onnxruntime": ort.__version__,
            "numpy": np.__version__,
            "requested_provider": PC_PROVIDER,
            "resolved_providers": resolved,
            "graph": model_io,
            "note": (
                "🔴 폰과 PC의 ORT 버전이 **같은지 이 스크립트가 대조한다**(아래 "
                "`phone_runtime`). 같은 버전인데 값이 다르면 그것은 버전 차이가 아니라 "
                "**빌드/플랫폼 차이**다(폰 arm64 vs PC x86-64: 커널 구현·벡터화·연산 순서가 "
                "다르다). ⚠ 예전에는 이 note가 '폰 쪽 버전은 session.json에 있다'고만 적고 "
                "대조를 사람에게 미뤘는데, 그러면 이 라운드의 핵심 전제가 **확인된 적 없는 "
                "가정**으로 남는다"
            ),
        },
        # 🔴 폰 쪽 좌표. `same_version`이 true가 아니면 위 좁히기를 쓸 수 없다.
        "phone_runtime": phone_runtimes,
        "borrowed_bar": bar,
        "borrowed_bar_note": (
            "🔴 **합격 판정으로 쓰지 않는다.** 상류가 **PC에서 PyTorch↔ONNX**를 잰 바이고 "
            "우리 대조는 **폰 ORT↔PC ORT**다 — 다른 비교다. 그래서 바를 넘어도 종료 코드는 "
            "0이다(종료 코드가 흔들리면 그게 곧 합격 판정이 된다)"
        ),
        # ── 관측 요약. 🔴 통과/실패보다 이게 앞이다 ───────────────────────
        "observed": {
            "samples_total": n_samples,
            "E_max_abs_diff": summarize(e_max),
            "F_max_abs_diff": summarize(f_max),
            "E_all_bitwise_identical": all(
                s["E_preprocess"].get("bitwise_identical") for d in per_dump
                for s in d["samples"]
            ),
            "F_all_bitwise_identical": all(
                s["F_inference"].get("bitwise_identical") for d in per_dump
                for s in d["samples"]
            ),
            "F_bitwise_note": (
                "🔴 **F의 비트 일치를 기대하지 않는다.** 폰 arm64 빌드와 PC x86-64 빌드는 "
                "같은 ORT라도 커널 구현·벡터화·연산 순서가 다르다. 아래 detection_set이 "
                "안전 관점의 실제 질문이다"
            ),
            "F_detection_set_agrees": f_set_ok,
            "G_detection_set_agrees": g_set_ok,
            # 🔴 **불리언보다 이 수를 먼저 본다.** 0이면 위 두 값은 null이고, 그건 일치가
            #    아니라 **비교된 박스가 한 개도 없었다**는 뜻이다.
            "boxes_compared_total": boxes_total,
            "boxes_note": (
                "🔴 **boxes_compared_total이 0이면 위 F/G 탐지집합 값은 null이다** — "
                "'일치했다'가 아니라 **비교할 것이 없었다**는 뜻이다. 탐지가 0개인 장면을 "
                "찍었거나 conf 임계 아래였다는 것이고, 그 덤프로는 후처리 이식을 검증하지 "
                "못한다. 박스가 나오는 장면으로 다시 찍을 것"
            ),
            "G_over_borrowed_bar_any": _tri_any(g_over),
            "E2E_over_borrowed_bar_any": _tri_any(e2e_over),
            "dist_note": (
                f"E/F의 max|diff| 분포는 **샘플 {n_samples}개**의 분포다 — "
                f"규약 §9대로 통계적 표본이 아니다"
            ),
        },
        "dumps": per_dump,
        "cross_ep": cross,
        "cross_ep_note": (
            "🔴 폰 EP끼리의 대조다(규약 §8). NNAPI가 GPU로 내려가는 것을 이미 실측했고 GPU "
            "경로는 fp16으로 떨어질 수 있다 — 같은 입력에 답이 달라지는 것은 성능 문제가 "
            "아니라 안전 문제다"
        ),
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
    return 0


def _print_report(summary: dict) -> None:
    obs = summary["observed"]
    LOG.info("=" * 62)
    LOG.info("③ 이식 정확성 대조 — run_ts=%s", summary["run_ts"])
    LOG.info("모델 %s", summary["model"]["sha256"])
    LOG.info(
        "PC ORT %s / numpy %s / provider %s",
        summary["pc_runtime"]["onnxruntime"], summary["pc_runtime"]["numpy"],
        ",".join(summary["pc_runtime"]["resolved_providers"]),
    )
    LOG.info("-" * 62)
    LOG.info("관측값 (통과/실패보다 이게 먼저다)")
    for d in summary["dumps"]:
        ep = d.get("ep") or {}
        LOG.info(
            "  [%s] ep %s→%s · 샘플 %s개 · conf=%s iou=%s",
            d["dump_label"], ep.get("requested"), ep.get("resolved"),
            d["sample_count"], d["thresholds"]["conf"], d["thresholds"]["iou"],
        )
        for s in d["samples"]:
            e = s["E_preprocess"]
            f = s["F_inference"]
            g = s["G_postprocess"]
            fs = s["F_detection_set"]
            LOG.info(
                "    #%s E max|d|=%.6g mean=%.6g 불일치 %s/%s",
                s["index"], e.get("max_abs_diff", float("nan")),
                e.get("mean_abs_diff", float("nan")),
                e.get("nonzero_count"), e.get("elements"),
            )
            LOG.info(
                "        F max|d|=%.6g mean=%.6g 불일치 %s/%s · 탐지집합 %s "
                "(폰텐서 %s개 ↔ PC %s개)",
                f.get("max_abs_diff", float("nan")), f.get("mean_abs_diff", float("nan")),
                f.get("nonzero_count"), f.get("elements"),
                "일치" if fs["all_matched"] and fs["class_counts_equal"] else "🔴 불일치",
                fs["count_lhs"], fs["count_rhs"],
            )
            LOG.info(
                "        G 박스 폰 %s ↔ PC %s · max_xy=%s px · max_conf_diff=%s · minIoU=%s",
                g["count_lhs"], g["count_rhs"], g["max_xy_diff_px"],
                g["max_conf_diff"], g["min_matched_iou"],
            )
            LOG.info(
                "        E2E 박스 폰 %s ↔ PC %s · max_xy=%s px",
                s["E2E"]["count_lhs"], s["E2E"]["count_rhs"], s["E2E"]["max_xy_diff_px"],
            )
    LOG.info("-" * 62)
    bar = summary.get("borrowed_bar")
    if bar:
        LOG.info(
            "빌린 바 (상류 parity_check): max_xy_diff_px=%s · max_conf_diff=%s · "
            "count_mismatch=%s",
            bar.get("max_xy_diff_px"), bar.get("max_conf_diff"), bar.get("count_mismatch"),
        )
        LOG.info("  ↳ G가 바를 넘었나: %s · E2E: %s",
                 obs["G_over_borrowed_bar_any"], obs["E2E_over_borrowed_bar_any"])
        LOG.info(
            "  🔴 이 바는 상류가 PC에서 PyTorch↔ONNX를 잰 것이다. 우리 대조(폰↔PC ORT)와 "
            "다른 비교이므로 **합격 판정으로 쓰지 않는다**"
        )
    else:
        LOG.info("빌린 바 없음 — 관측값만 낸다")
    LOG.info("-" * 62)
    LOG.info("E 전부 비트 일치: %s", obs["E_all_bitwise_identical"])
    LOG.info("F 전부 비트 일치: %s (🔴 기대하지 않는다 — 다른 아키텍처 빌드다)",
             obs["F_all_bitwise_identical"])
    LOG.info("F 탐지 집합 일치: %s · G 탐지 집합 일치: %s",
             obs["F_detection_set_agrees"], obs["G_detection_set_agrees"])
    for pair in summary.get("cross_ep") or []:
        for s in pair["samples"]:
            LOG.info(
                "EP 대조 %s ↔ %s #%s: 입력 동일=%s · 출력 max|d|=%.6g 불일치 %s/%s",
                pair["lhs"]["dir"], pair["rhs"]["dir"], s["index"], s["same_input"],
                s["output_tensor"].get("max_abs_diff", float("nan")),
                s["output_tensor"].get("nonzero_count"),
                s["output_tensor"].get("elements"),
            )
    LOG.info("-" * 62)
    LOG.info("%s", summary["scope_note"])
    for w in summary["warnings"]:
        LOG.warning("⚠ %s", w)
    LOG.info("=" * 62)


if __name__ == "__main__":
    raise SystemExit(main())
