"""③ 이식 정확성 대조 — 폰 덤프 ↔ PC ORT 레퍼런스.

폰이 남긴 덤프(`parity/`)를 읽어 **E(전처리)·F(추론)·G(후처리)를 따로** PC에서 재현하고
대조한다. 포맷 규약은 `docs/plans/20260806_detect_parity_dump_format.md`이며 **어긋나면 그
문서가 맞다** — 생산자는 앱(`DetectParityDumper.kt`)이고 이 파일은 소비자다.

    python scripts/detect_parity.py --dump <pulled>/parity
    python scripts/detect_parity.py --dump <cpu>/parity --dump <nnapi>/parity --dump <xnnpack>/parity

## 🔴 이것이 답하는 질문과 답하지 못하는 질문

답한다: 폰의 E/F/G가 **같은 입력에서 PC와 같은 값을 내는가**(이식이 값을 안 바꿨는가).
답하지 못한다: **모델이 옳은가**(mAP·누락률 — 정답 라벨이 필요하다). 이 스크립트의 결과를
"모델이 잘 맞힌다"의 증거로 쓰면 틀린다.

🔴 **회전을 적용해도 그 문장은 그대로다**(규약 §4-4). v1.2부터 앱이 `rotationDegrees`를
적용하고(이슈 29를 닫는다) 모델이 바로 선 장면을 보게 되지만, **정답 라벨도 골든 샘플도
없으므로 정확도 판정은 여전히 불가능하다.** "회전을 적용했다"는 "모델이 정확하다"가 아니다 —
이 덤프가 답하는 것은 끝까지 **"폰과 PC가 같은 답을 내는가"**까지다.

## 🔴 덤프 포맷 `/1`과 `/2`를 **둘 다 읽는다** (규약 §3-3)

`/2`에서 **기존 키의 뜻이 바뀌었다** — `letterbox.*`가 센서 치수가 아니라 **회전 후 치수**에서
계산된 값이고, `boxes`는 letterbox 역변환에 **회전 역변환까지** 거친 **센서 좌표계**다.
그래서 PC는 **버전에 따라 기준을 바꾼다.** 08-06 덤프(`/1`, 회전 미적용, 24샘플)가 이 저장소의
유일한 ③ 이식 실측이라, 못 읽게 되면 그 근거가 사라진다. **아는 버전이 아니면 죽는다.**

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
# 🔴 **아는 버전이 둘이다**(규약 §3-3). `/1`은 회전 이전 포맷(letterbox가 센서 치수 기준,
#   boxes도 그 좌표계), `/2`는 회전 이후 포맷(letterbox가 **회전 후** 치수 기준, boxes는
#   **센서 좌표계**로 되돌린 값). **같은 키가 다른 것을 말하므로** 읽는 법을 버전으로 가른다.
#   목록 밖의 값이면 죽는다 — 모르는 포맷을 그럴듯하게 읽지 않는다.
FORMAT_PREFIX = "bammasil-detect-parity/"
FORMAT_IDS = (FORMAT_PREFIX + "1", FORMAT_PREFIX + "2")
BYTE_ORDER_ID = "little_endian"               # §2. 다르면 죽는다(지어내지 않는다)
F32_ITEMSIZE = 4

# ── 회전 어휘 (규약 §4-1) ─────────────────────────────────────────────────
# 🔴 여기 있는 문자열도 출처는 규약 문서 하나다. 앱이 같은 문서를 보고 같은 문자열을 낸다.
ROTATIONS_ALLOWED = (0, 90, 180, 270)
ROTATION_SITES_APPLIED = ("preprocess_sample_map", "preprocess_plane_copy")
ROTATION_SITE_NONE = "none"
# 🔴 어휘에 **없는** 값이지만 이름은 안다 — `ImageAnalysis`가 회전까지 해서 주면 변환이
#   E 구간 **밖에서** 일어나 E가 과소로 나온다(규약 §4-1). 그 값이 나타나면 죽되, 왜 죽는지를
#   이 이름으로 말한다.
ROTATION_SITE_FORBIDDEN = "camerax_output_rotation"

DEFAULT_MODEL = (
    _PROJECT_ROOT / "models" / "det_c4b_loli0_640" / "bammasil_det_c4b_loli0_640.onnx"
)
# 빌린 바(`parity_check`)가 사는 곳. **모델 파일 옆의 metadata.json**이며, 모델 경로를
# 바꾸면 이 경로도 따라 움직인다(같은 디렉토리에서 찾는다).
METADATA_NAME = "metadata.json"

# 출력 텐서 `(4+nc, A)`에서 **앞 4채널이 좌표**(cx,cy,w,h)이고 나머지가 클래스별 점수다.
# 🔴 이 4는 클래스 수가 아니라 **YOLO detect head의 구조**다(`nms:false`로 export 했으므로
#   박스 디코딩이 그래프 밖에 있다). 클래스 수는 `n_ch - 4`로 **그래프에서 유도**하며 어디에도
#   박아 넣지 않는다 — 모델이 클래스를 늘려도 여기는 안 바뀐다.
BOX_CHANNELS = 4

# 샘플이 이 시간 안에 다 몰려 있으면 **사실상 한 장면**이라고 경고한다.
# 🔴 판정선이 아니라 **읽는 이를 위한 경고선**이다(종료 코드를 흔들지 않는다).
# 근거: 실측 실행 주기가 ~300ms이므로 5초면 추론 15회쯤이 지나가는데, 그 안에서 장면이
# 바뀌었다고 보기 어렵다. 실제로 첫 실기기 대조가 0.8~1.6초에 몰렸다.
SAMPLE_SPAN_WARN_SEC = 5.0

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
    # 🔴 numpy가 붙은 **직후** 규약 §5-1의 검산을 돌린다. import 시점에 두면 numpy를 강제로
    #   끌어와 이 파일의 지연 import 격리가 깨진다(다른 스크립트는 무의존으로 남아야 한다).
    #   여기 두면 대조를 시작하는 모든 실행이 반드시 이 불변식을 통과한다.
    _selfcheck_frame_box()


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
#
# 🔴 **기준 벡터가 퇴화되면 안 된다.** 처음엔 `[1..7]` 하나였는데, n=7에서는 세 p 모두
#   `ceil(p*n) == int(p*n)+1`이라 **floor+1 변형이 그대로 통과**했다(독립 검증 지적).
#   길이를 둘 두는 이유가 그것이다 — 어느 한 길이도 모든 변형을 잡지 못한다:
#     n=20: p*n이 정수인 자리(10, 19)가 생겨 **floor+1**과 **int(p*n)**(p=0.99)을 가른다
#     n=21: p=0.5에서 10.5가 되어 **round**(→10)와 ceil(→11)을 가른다
#   값 간격을 일부러 **불균등**하게 둔 것은 선형보간 변형을 가르기 위해서다(등간격이면
#   보간값이 실측값과 같아져 그것도 통과한다).
_REF_VECTORS = [
    [float(i * i + (i % 3)) for i in range(1, 21)],   # n=20
    [float(i * i + (i % 3)) for i in range(1, 22)],   # n=21
]
for _ref in _REF_VECTORS:
    for _p in (0.50, 0.95, 0.99):
        if percentile(_ref, _p) != _np_percentile(_ref, _p):
            raise RuntimeError(
                "scripts/detect_parity.py 자기검사 실패 — _np_percentile이 "
                f"lib/stats.percentile과 다른 답을 낸다(n={len(_ref)}, p={_p}: "
                f"{percentile(_ref, _p)} vs {_np_percentile(_ref, _p)}). 백분위 규칙이 둘로 "
                "갈리면 이 스크립트의 숫자만 다른 뜻이 된다"
            )
del _REF_VECTORS, _ref, _p


def sha256_file(path: Path) -> str:
    h = hashlib.sha256()
    with path.open("rb") as f:
        for chunk in iter(lambda: f.read(1 << 20), b""):
            h.update(chunk)
    return h.hexdigest()


# ══ 회전: 좌표 사슬의 두 번째 칸 (규약 §4·§5) ═════════════════════════════
# 🔴 **PC가 회전을 자기 힘으로 재현한다**(규약 §4-5). 덤프된 `sample_NN_src.yuv`는 **센서
#    방향 원본 평면 그대로**이므로, PC가 같은 회전을 **같은 자리에서** 재현해야 E 대조가
#    `YUV→회전→letterbox→RGB→/255→NCHW` **전체**를 덮는다. 앱이 회전한 결과를 덤프했다면
#    PC는 그 회전을 검사할 수 없고 E 대조가 회전을 건너뛴다.
#
# 좌표 사슬(규약 §5):
#   ① 센서 프레임 (src.width × src.height)
#        ↓ 회전 (source.rotation_degrees, **시계 방향** — Android `rotationDegrees` 규약)
#   ② 회전 후 프레임 (source.rotated_width × rotated_height)
#        ↓ letterbox  ③ 640 좌표계  → 모델 → ④ 출력 텐서
#        ↑ letterbox 역변환         ↑ 회전 역변환 → ① 로 되돌아온다


class RotationMap:
    """센서 프레임(①) ↔ 회전 후 프레임(②) 사이의 좌표 변환.

    🔴 **`degrees`는 "실제로 적용한" 각이다.** 매니페스트의 `rotation_degrees`를 그대로
    쓰지 않는다 — `rotation_applied=false`인 짝 arm(`detect_cpu_norot`)에서는 기기가 90°를
    보고해도 앱이 **적용하지 않았으므로** PC도 적용하면 안 된다. 그 판단은
    `resolve_rotation()`이 규약 §4-2의 세 경우로 내린다.

    ⚠ **방향 가정(`ROTATION_DIRECTION_ASSUMPTION`).** Android `ImageProxy.imageInfo`의
    `rotationDegrees`는 "이 버퍼를 **시계 방향으로** 이만큼 돌리면 바로 선다"는 뜻이라고
    읽었다. 이것은 계약 문서에 적힌 값이 아니라 플랫폼 규약을 우리가 읽은 것이고, 폰이
    반대로 돌리면 E 대조가 **크게** 어긋난다(1ulp가 아니라 화면 전체가 다르다) — 조용히
    틀리지는 않는다.
    """

    __slots__ = ("degrees", "src_w", "src_h", "rot_w", "rot_h", "applied", "site",
                 "box_edge_offset")

    def __init__(self, degrees: int, src_w: int, src_h: int, applied: bool, site,
                 box_edge_offset: float = 0.0):
        if degrees not in ROTATIONS_ALLOWED:
            raise ParityError(
                f"회전각 {degrees!r}은 {list(ROTATIONS_ALLOWED)} 밖이다 — 90° 배수가 아닌 "
                f"회전은 픽셀 재배치가 아니라 보간이 되고, 그 보간 규칙은 규약에 없다. "
                f"**지어내지 않는다** (규약 §4)"
            )
        self.degrees = int(degrees)
        self.src_w = int(src_w)
        self.src_h = int(src_h)
        self.applied = bool(applied)
        self.site = site
        # 🔴 **박스 반사축의 원점 규약**(아래 `invert_box` 주석). 0.0 = 연속 좌표(`W - x`),
        #    1.0 = 픽셀 인덱스(`(W-1) - x`). 기본은 0.0이고, 1.0은 **진단용 대조**로만 쓴다 —
        #    폰이 어느 규약을 썼는지 이름 붙이기 위한 것이지 값을 맞추려는 것이 아니다.
        self.box_edge_offset = float(box_edge_offset)
        swapped = self.degrees in (90, 270)
        self.rot_w = self.src_h if swapped else self.src_w
        self.rot_h = self.src_w if swapped else self.src_h

    def as_dict(self) -> dict:
        return {
            "degrees_effective": self.degrees,
            "applied": self.applied,
            "site": self.site,
            "sensor_width": self.src_w,
            "sensor_height": self.src_h,
            "rotated_width_pc": self.rot_w,
            "rotated_height_pc": self.rot_h,
        }

    # ── ② → ① : 샘플 좌표 (전처리 샘플 맵이 쓴다) ─────────────────────────
    def to_sensor(self, rx, ry):
        """회전 후 프레임의 좌표 `(rx, ry)` → 센서 프레임의 좌표 `(sx, sy)`.

        🔴 **90° 배수라 순수 좌표 치환이다** — 보간도 반올림도 없다. numpy 브로드캐스트
        배열을 그대로 받는다(전처리가 행/열 축을 따로 넘긴다).

        🔴 **여기는 픽셀 인덱스 규약(`-1`)이다.** 샘플 좌표는 픽셀 격자 위의 위치이고
        `[0, W-1]`이 `[0, W-1]`로 뒤집혀야 한다. 🔴 **박스(`invert_box`)는 규약이 다르다** —
        거긴 letterbox 역변환이 낸 **연속 좌표**라 `[0, W]`를 채운다. 같은 함수를 두 곳에
        쓰면 한쪽이 정확히 1px 틀린다(그 갈림을 `_probe_box_convention`이 감시한다).

        ⚠ 정수를 넣으면 정수가, 연속 좌표를 넣으면 연속 좌표가 나온다 — 전처리는 **연속
        좌표**를 넣는다(`DetectPreprocessor.kt`의 `toSensorX/Y`와 같은 자리, 같은 입력).
        """
        d = self.degrees
        if d == 0:
            return rx, ry
        if d == 90:
            return ry, (self.src_h - 1) - rx
        if d == 180:
            return (self.src_w - 1) - rx, (self.src_h - 1) - ry
        return (self.src_w - 1) - ry, rx          # 270

    # ── ② → ① : 연속 좌표의 박스 (후처리 역변환이 쓴다) ───────────────────
    def invert_box(self, x1, y1, x2, y2):
        """회전 후 좌표계(②)의 박스 → 센서 좌표계(①)의 박스. **float32로 계산한다.**

        🔴 **min/max로 정규화하지 않는다.** 축 대응으로 자리를 옮길 뿐이다:
        회전이 뒤집는 축에서는 두 끝점의 **순서까지 함께** 옮긴다(`x1' = W - x2`). 그래서
        - 잘 정렬된 박스는 잘 정렬된 채로 돌아오고(90°마다 전부 역전되는 일이 없다),
        - **역전된 박스는 역전된 채로 돌아온다.** min/max를 씌우면 `x2 < x1`인 박스가 조용히
          고쳐지는데, 규약 §5-3은 역전 박스를 **거르지도 고치지도 말고 세라**고 한다 —
          조용히 지우면 결함이 숨는다.

        ⚠ **축 대응 가정(`ROTATION_BOX_AXIS_MAPPING`).** 폰도 같은 방식으로 옮긴다고 본다.
        폰이 두 꼭짓점을 각각 옮기고 재정렬하지 않으면 90°에서 **모든 박스가 y축 역전**으로
        나오므로, 그 경우 `boxes_inverted`가 박스 수와 같아져 곧바로 드러난다.

        🔴 **연속 좌표 규약(`ROTATION_BOX_EDGE_CONVENTION`) — 여기가 폰과 갈릴 자리다.**
        박스 좌표는 letterbox 역변환 `(x - pad)/scale`이 낸 **연속 좌표**이고 회전 후 프레임
        `[0, rot_w]`를 채운다(정수 인덱스 `[0, rot_w-1]`이 아니다). 그래서 반사축의 식은
        `W - x`다. 픽셀 인덱스 쪽 식 `(W-1) - i`를 **박스에 그대로 쓰면** 프레임 전체가
        원점 쪽으로 **정확히 1px 밀린다**(회전 후 오른쪽 끝 `rot_w`가 센서 `-1`로 간다).
        ⚠ 샘플 맵(`sensor_index`)은 **정수 인덱스**를 다루므로 거기서는 `-1`이 맞다 —
          같은 함수를 두 규약에 쓰면 한쪽이 1px 틀린다.
        ⚠ `box_edge_offset=1.0`으로 만들면 인덱스 규약이 된다. **진단 대조 전용**이다
          (`_probe_box_convention` — 폰이 어느 규약을 썼는지 이름 붙이기 위한 것이다).
        """
        f32 = np.float32
        d = self.degrees
        if d == 0:
            return x1, y1, x2, y2
        off = f32(self.box_edge_offset)
        w = f32(self.rot_w) - off
        h = f32(self.rot_h) - off
        if d == 90:
            # sx ← ry (순서 보존) · sy ← rot_w - rx (순서 반전)
            return y1, w - x2, y2, w - x1
        if d == 180:
            return w - x2, h - y2, w - x1, h - y1
        # 270: sx ← rot_h - ry (반전) · sy ← rx (보존)
        return h - y2, x1, h - y1, x2


def _selfcheck_frame_box() -> None:
    """🔴 규약 §5-1의 **검산** — 회전 후 프레임 전체 박스 `(0, 0, rotated_w, rotated_h)`는
    센서 프레임 전체 `(0, 0, src_w, src_h)`로 가야 한다.

    **허용오차 문제가 아니라 참/거짓이다.** 인덱스 식 `(N-1) - v`를 쓰면 한 축이 `-1`로
    나오고 프레임 전체가 원점 쪽으로 정확히 1px 밀린다 — 그 1px은 **어떤 왕복 검사로도
    안 잡힌다**(정방향·역방향이 같은 규약이면 왕복은 통과한다). §5-1이 지목한 두 감시자 중
    하나가 이것이고, 다른 하나가 폰↔PC 대조(`_probe_box_convention`)다.

    데이터와 무관한 불변식이라 `lib/frame_log.py`의 상수 자기검사와 같은 부류다 —
    깨지는 순간은 개발자가 반사식을 고친 그 편집 시점이고, 여기서 죽이지 않으면 그 뒤의
    모든 박스 좌표가 조용히 1px 밀린 채로 나온다.
    """
    f32 = np.float32
    for deg in ROTATIONS_ALLOWED:
        # 정사각형이 아닌 치수 두 벌로 본다 — 정사각형은 축 스왑이 항등이라 90/270°의
        # 축 대응 오류를 통과시킨다(퇴화된 기준 벡터를 만들지 않는다).
        for src_w, src_h in ((1280, 720), (721, 1280)):
            rot = RotationMap(deg, src_w, src_h, applied=True,
                              site=ROTATION_SITES_APPLIED[0])
            got = tuple(float(v) for v in rot.invert_box(
                f32(0), f32(0), f32(rot.rot_w), f32(rot.rot_h)))
            want = (0.0, 0.0, float(src_w), float(src_h))
            if got != want:
                raise ParityError(
                    "scripts/detect_parity.py 자기검사 실패 — 규약 §5-1의 검산이 깨졌다. "
                    f"회전 {deg}°, 센서 {src_w}×{src_h}(회전 후 {rot.rot_w}×{rot.rot_h}): "
                    f"프레임 전체 박스가 {got}로 갔다(기대 {want}). "
                    "반사식은 `N - v`여야 하고 `(N-1) - v`가 아니다 — 인덱스 식을 쓰면 한 축이 "
                    "-1로 나오고 프레임 전체가 원점 쪽으로 정확히 1px 밀린다. "
                    "⚠ 전처리 샘플 맵(`to_sensor`)은 반대로 `(N-1) - v`가 맞다. **한 함수를 "
                    "두 자리에 쓰면 한쪽이 1px 틀린다**"
                )


def count_inverted_boxes(boxes: list[dict]) -> int:
    """`x2 < x1` 또는 `y2 < y1`인 박스 수 (규약 §5-3). 🔴 **거른 개수가 아니라 센 개수다** —
    해당 박스는 리스트에 그대로 들어 있다."""
    return sum(1 for b in boxes if b["x2"] < b["x1"] or b["y2"] < b["y1"])


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
            # 🔴 `/2`가 더한 키다(규약 §3). **PC가 자기 힘으로 잡은 기준 치수**이며, 매니페스트에
            #    그 키가 있으면 대조된다(`/1` 덤프에는 없어서 그 항목만 건너뛴다).
            "src_w": self.src_w,
            "src_h": self.src_h,
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


def preprocess(raw: bytes, src: dict, box: Letterbox, pad_value_u8: int, rot: RotationMap):
    """YUV_420_888 평면 → **회전** → letterbox → BT.601 full range RGB → `/255` → NCHW float32.

    반환은 `(3, dst_h, dst_w)` float32 배열이며 C-order로 펴면 폰의 `input.f32`와 같은
    바이트 나열이 된다(`plane[rOff + dy*dstW + dx]` 배치).

    🔴 **회전이 여기 들어 있다**(규약 §4-5). 덤프된 평면은 **센서 방향 원본**이므로 PC가
    같은 회전을 **같은 자리에서** 재현해야 E 대조가 전 구간을 덮는다. letterbox 기하는
    **회전 후 프레임**(`rot.rot_w × rot.rot_h`)에서 계산된 것이고, 이 함수는 목적지 픽셀 →
    회전 후 좌표 → **회전 역함수** → 센서 픽셀을 **한 번에** 매핑한다
    (`rotation_site = "preprocess_sample_map"`. 추가 패스도 추가 버퍼도 없다).

    ⚠ 휘도는 **이중선형**, 색차는 **최근접**이다. 둘 다 계약이 아니라 가정이며
      (`RESIZE_INTERPOLATION_ASSUMPTION`) 폰이 그렇게 하므로 여기서도 그렇게 한다.
    🔴 **보간은 센서 공간에서 한다**(`ROTATION_SAMPLE_ORDER_ASSUMPTION`). 회전 역함수를
      **연속 좌표에 먼저** 적용하고, 클램프·floor·가중치를 전부 센서 공간에서 계산한다 —
      `DetectPreprocessor.kt`가 하는 그대로다. 회전 후 공간에서 가중치를 만들면 값은
      대수적으로 같지만 반사축에서 `a+(b-a)t` 대신 `b+(a-b)(1-t)`가 되어 **1ulp**가 갈리고,
      E가 비트 일치를 잃는다. 경계 클램프(`fx<0 → 0`)도 어느 공간에서 거느냐에 따라 프레임
      가장자리 반 픽셀에서 다른 이웃을 집는다.
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
    _require(
        src_w == rot.src_w and src_h == rot.src_h,
        f"src {src_w}×{src_h}와 회전 맵의 센서 치수 {rot.src_w}×{rot.src_h}가 다르다 — "
        f"회전 맵이 다른 프레임에서 만들어졌다",
    )
    # 🔴 letterbox는 **회전 후 프레임**에서 계산된 것이다(규약 §5). 샘플 좌표의 클램프도
    #    그 프레임 기준이며, 센서 인덱스로 옮기는 것은 그 다음이다.
    _require(
        box.src_w == rot.rot_w and box.src_h == rot.rot_h,
        f"letterbox가 {box.src_w}×{box.src_h}에서 계산됐는데 회전 후 프레임은 "
        f"{rot.rot_w}×{rot.rot_h}다 — 기하 기준이 두 개다 (규약 §5)",
    )
    out = np.empty((3, box.dst_h, box.dst_w), dtype=np.float32)
    # 패딩 값은 **나눗셈**이다(`DetectContract.PAD_VALUE_U8 / 255f`). 아래 RGB는 역수 곱이라
    # 둘의 반올림이 1ulp 갈릴 수 있어 그대로 옮긴다.
    out[:] = f32(pad_value_u8) / f32(255)
    if box.content_w <= 0 or box.content_h <= 0:
        return out

    inv_scale = f32(1) / box.scale

    # ③ → ② : letterbox 역변환. 픽셀 중심 정렬(cv2.resize 규약)이라 0.5를 더했다 뺀다.
    # 🔴 여기서 나오는 (fxr, fyr)은 **회전 후 프레임**의 연속 좌표다(아직 센서가 아니다).
    dy = np.arange(box.pad_y, box.pad_y + box.content_h, dtype=np.int32)
    fyr = ((dy - box.pad_y).astype(f32) + f32(0.5)) * inv_scale - f32(0.5)
    dx = np.arange(box.pad_x, box.pad_x + box.content_w, dtype=np.int32)
    fxr = ((dx - box.pad_x).astype(f32) + f32(0.5)) * inv_scale - f32(0.5)

    # ② → ① : 회전 역함수. 🔴 **여기가 `rotation_site = preprocess_sample_map`의 실체다** —
    #   목적지 좌표에서 센서 좌표를 한 번에 잡는다. `DetectPreprocessor.kt`가 `toSensorX/Y`를
    #   부르는 자리와 **같은 자리, 같은 입력(연속 좌표)**이다.
    #   🔴 클램프·floor·보간 가중치를 **센서 공간에서** 계산하는 것도 폰과 같다. 회전 후
    #   공간에서 계산하면 대수적으로는 같은 값이지만 float32 연산 순서가 갈려 E가 비트
    #   일치를 잃는다(반사축에서 `a+(b-a)t` vs `b+(a-b)(1-t)`가 된다).
    #   회전이 0°면 to_sensor가 항등이라 아래는 예전 코드와 같은 값을 낸다 — `/1` 덤프의
    #   E 대조 결과가 이 변경으로 흔들리지 않는다.
    fx, fy = rot.to_sensor(fxr[None, :], fyr[:, None])
    fx = np.maximum(fx, f32(0))
    fy = np.maximum(fy, f32(0))
    x0 = np.minimum(fx.astype(np.int32), src_w - 1)
    x1 = np.minimum(x0 + 1, src_w - 1)
    ax = fx - x0.astype(f32)                 # 🔴 클램프된 x0로 계산한다(폰과 같은 순서)
    y0 = np.minimum(fy.astype(np.int32), src_h - 1)
    y1 = np.minimum(y0 + 1, src_h - 1)
    ay = fy - y0.astype(f32)

    r0 = y0 * y_row
    r1 = y1 * y_row
    c0 = x0 * y_pix
    c1 = x1 * y_pix
    y00 = y_buf[r0 + c0].astype(f32)
    y01 = y_buf[r0 + c1].astype(f32)
    y10 = y_buf[r1 + c0].astype(f32)
    y11 = y_buf[r1 + c1].astype(f32)
    y_top = y00 + (y01 - y00) * ax
    y_bot = y10 + (y11 - y10) * ax
    yy = y_top + (y_bot - y_top) * ay

    # 색차는 최근접. 이미 2:1로 서브샘플된 평면이라 표준 관행이다.
    # 🔴 색차 평면은 **센서 배치**이므로 서브샘플 인덱스도 센서 좌표(x0·y0)에서 잡는다.
    cxi = (x0 >> 1)
    cyi = (y0 >> 1)
    uu = (u_buf[cyi * u_row + cxi * u_pix].astype(np.int32) - 128).astype(f32)
    vv = (v_buf[cyi * v_row + cxi * v_pix].astype(np.int32) - 128).astype(f32)

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
# 🔴 **순서가 규약 §5 그대로여야 한다** — 상류 README 의 네 단계다:
#      conf 필터 → cxcywh→xyxy → **클래스별 NMS를 letterbox 640 좌표계에서** →
#      살아남은 것만 역변환
#    예전 앱 코드는 NMS 앞에서 역변환을 했고 "같은 아핀이라 IoU 순서 불변"이라고
#    정당화했는데, 그때 함께 하던 **클램프가 아핀이 아니었다**(독립 검증이 잡아 고쳤다).
#    순서를 바꾸면 불일치가 이식 결함이 아니라 **PC 쪽 결함**이 된다.
#
# 🔴 **원본 경계 클램프는 없다(2026-08-07, 폰과 함께 제거).** 상류에 없는 5번째 단계였고
#    비대칭이라 역전 박스를 만들었다. ⚠ **그 이전 빌드가 만든 덤프를 이 코드로 대조하면
#    경계에 걸친 박스에서 차이가 난다** — 실제로 `20260806_230424` 덤프의 박스 하나가
#    `y2`를 720(프레임 높이)에 잘린 채로 남아 있어 `max_xy=3.874px`가 나온다.
#    그것은 **이식 결함이 아니라 코드 버전 차이**이며, 폰을 다시 돌려 새 덤프를 뜨면 사라진다.
# 🔴 **면제 대상은 그 덤프 하나가 아니다**(알려진 이슈 54). 독립 검증이 `20260806_234512`
#    **#0·#1**에서도 같은 흔적을 찾았다 — `max_xy`가 각각 **0.347px / 2.195px**이고, 좌표
#    차가 **정확히 `[0,W]`·`[0,H]` 경계에서만** 나며 `conf_diff=0.0`이다. 즉 이것은
#    **08-06 `/1` 덤프 전반의 성질이며 sweep 결함이 아니다** — `234512`를 결함으로 읽지 마라.


def postprocess(raw2d, box: Letterbox, conf_thr: float, iou_thr: float,
                class_names: dict, rot: RotationMap) -> tuple[list[dict], int, float]:
    """`DetectPostprocessor.run`의 이식.

    :param raw2d: `(4+nc, A)` float32. 출력 텐서를 **채널 우선**으로 읽은 것.
    :param rot: 회전 맵. 역변환은 letterbox 역변환 **뒤**, 즉 마지막이다(규약 §5-1).
    :returns: (박스 리스트(**센서 좌표계** = 규약 §5의 ①), boxes_pre_nms, max_conf)
    """
    f32 = np.float32
    n_ch, n_anchors = raw2d.shape
    class_count = n_ch - BOX_CHANNELS
    if class_count < 1:
        raise ParityError(f"출력 채널이 {n_ch}개다 — {BOX_CHANNELS}+nc 구조가 아니다")

    cls_scores = raw2d[BOX_CHANNELS:, :]
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

    # 4) 역변환. **살아남은 것만** 한다. 상류 README 후처리 절의 **네 단계 그대로**다.
    #
    # 🔴 **원본 경계 클램프를 없앴다(2026-08-07). 폰과 함께 고쳤다.**
    #    상류 명세에 없는 5번째 단계였고 **비대칭**이라(x1/y1은 아래만, x2/y2는 위만) 탐지가
    #    letterbox 패딩 안에만 있으면 `x2 < x1`인 역전 박스가 나왔다.
    #    🔴 **부등호를 보장하는 것은 `scale`이 아니라 모델이다** — `x2 - x1 = w · invScale`이라
    #    부호는 `w`가 정한다("scale > 0이라 항상 성립"이라고 적었다가 독립 검증에 잡혔다).
    #    이 모델은 실측(앵커 201,600개, `min(w)=4.599`)과 구조(DFL: `w=(lt+rb)·stride ≥ 0`)
    #    양쪽으로 `w > 0`이지만 **산술적 보장은 아니다.** 자세한 논거는
    #    `DetectPostprocessor.kt`의 같은 자리에 있다.
    #    ⚠ **한쪽만 고치면 이식 대조가 그 자리에서 어긋난다** — 두 파일은 함께 움직인다.
    #
    # 🔴 **5) 회전 역변환(v1.2). letterbox 역변환 뒤이고 NMS 뒤다**(규약 §5-1).
    #    회전 역변환은 아핀이고 90° 배수라 순수 좌표 치환이지만, **그래도 NMS 앞으로 옮기지
    #    않는다** — "아핀이니 앞에 둬도 된다"는 논거가 바로 그 클램프 사고를 낳았다. 순서를
    #    상류와 같게 두는 것이 논거 없이도 성립하는 유일한 방식이다.
    #    회전이 0°면 `invert_box`는 항등이라 값이 그대로 지나간다.
    inv_scale = f32(1) / box.scale
    out: list[dict] = []
    for idx in kept:
        # ② 회전 후 좌표계
        x1 = (bx1[idx] - box.pad_x) * inv_scale
        y1 = (by1[idx] - box.pad_y) * inv_scale
        x2 = (bx2[idx] - box.pad_x) * inv_scale
        y2 = (by2[idx] - box.pad_y) * inv_scale
        # ① 센서 좌표계 — 여기가 boxes의 좌표계다(규약 §5-2)
        x1, y1, x2, y2 = rot.invert_box(x1, y1, x2, y2)
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

    def __init__(self, root: Path, manifest: dict, format_version: int):
        self.root = root
        self.manifest = manifest
        self.label = _dump_label(root)
        # 🔴 **읽는 법을 가르는 값이다**(규약 §3-3). `/1`은 letterbox가 센서 치수 기준이고
        #    `/2`는 회전 후 치수 기준이다 — 같은 키가 다른 것을 말한다.
        self.format_version = int(format_version)
        self.format_id = str(manifest.get("format"))


def _format_version(fmt, where: str) -> int:
    """`format` 문자열 → 버전 정수. **아는 버전이 아니면 죽는다**(규약 §3-3).

    모르는 포맷을 그럴듯하게 읽으면 결과가 '이식 결함'인지 '포맷 어긋남'인지 구분되지 않는다.
    """
    _require(
        isinstance(fmt, str) and fmt in FORMAT_IDS,
        f"{where}: format={fmt!r}이 아는 버전이 아니다(아는 것: {list(FORMAT_IDS)}). "
        f"포맷이 갈린 채로 대조하면 결과가 '이식 결함'인지 '포맷 어긋남'인지 구분되지 "
        f"않는다 — 읽지 않는다 (docs/plans/20260806_detect_parity_dump_format.md §3-3)",
    )
    return int(str(fmt)[len(FORMAT_PREFIX):])


def load_dump(root: Path) -> Dump:
    """매니페스트를 읽고 **방어선을 전부 통과시킨다.** 하나라도 어긋나면 던진다."""
    _require(root.is_dir(), f"덤프 디렉토리가 없다: {root}")
    mpath = root / MANIFEST_NAME
    _require(mpath.is_file(), f"매니페스트가 없다: {mpath} (규약 §2)")
    try:
        manifest = json.loads(mpath.read_text(encoding="utf-8"))
    except (json.JSONDecodeError, UnicodeDecodeError) as exc:
        raise ParityError(f"{mpath}를 JSON으로 읽지 못했다: {exc}") from exc

    version = _format_version(manifest.get("format"), str(mpath))
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
    return Dump(root, manifest, version)


def resolve_rotation(dump: Dump, warnings: list[str]) -> RotationMap:
    """매니페스트의 `source` 블록 → **PC가 실제로 적용할** 회전 (규약 §4-2).

    🔴 **`rotation_applied=false`의 뜻이 둘이다.** 하나로 뭉치면 의도된 대조군을 결함으로
    읽거나(이슈 29 경고를 잘못 낸다) 진짜 모순을 놓친다:

    | `rotation_applied` | `rotation_site` | 어떻게 읽는가 |
    |---|---|---|
    | `true`  | `preprocess_*` | 정상. PC도 같은 각을 적용한다 |
    | `false` | `none`         | 🟢 **의도된 대조군**(`detect_cpu_norot`). PC도 적용하지 않는다. **경고를 내지 않는다** |
    | `false` | `preprocess_*` | 🔴 모순 — **죽는다** |

    `/1`(회전 이전 포맷)에는 `rotation_site`가 없다. 그건 이슈 29 그대로이므로 **예전 경고를
    그대로** 낸다 — 그 문장이 사라지면 08-06 덤프를 지금 다시 읽는 사람이 "회전이 적용된
    결과"로 오독한다.
    """
    src_info = dump.manifest.get("source") or {}
    label = dump.label
    deg = src_info.get("rotation_degrees")
    _require(
        isinstance(deg, int) and not isinstance(deg, bool),
        f"{label}: source.rotation_degrees를 정수로 읽지 못했다({deg!r}) — 회전각을 "
        f"**지어내지 않는다** (규약 §3·§4)",
    )
    applied = src_info.get("rotation_applied")
    _require(
        isinstance(applied, bool),
        f"{label}: source.rotation_applied가 불리언이 아니다({applied!r}) — 회전을 적용했는지 "
        f"모르는 채로 재현하면 E가 다른 것을 재게 된다 (규약 §4-2)",
    )
    site = src_info.get("rotation_site")

    if dump.format_version < 2:
        # `/1`: 회전이라는 개념 자체가 없던 포맷. `rotation_applied`는 항상 false였다.
        _require(
            not applied,
            f"{label}: format={dump.format_id}인데 rotation_applied=true다 — `/1`은 회전을 "
            f"적용하지 않던 포맷이고 letterbox·boxes가 센서 치수 기준이라, 회전을 적용한 런을 "
            f"`/1`로 적으면 좌표계가 통째로 어긋난다. 덤프를 `/2`로 다시 뜰 것 (규약 §3-3)",
        )
        if site is not None:
            warnings.append(
                f"{label}: format={dump.format_id}인데 rotation_site={site!r}가 실려 있다 — "
                f"`/1`에는 없던 키다. 회전은 적용되지 않은 것으로 읽었다"
            )
        warnings.append(
            f"{label}: 🔴 **rotation_applied=false** (format={dump.format_id}) — 앱이 "
            f"ImageProxy.imageInfo.rotationDegrees({deg}°)를 적용하지 않았다(알려진 이슈 29). "
            f"모델은 **옆으로 누운 장면**을 본다. 이식 정확성 질문은 회전과 직교하므로 이 "
            f"대조는 성립하지만, 🔴 **이 덤프로 '모델이 잘 맞힌다/못 맞힌다'를 말하면 안 된다** "
            f"— 입력이 누워 있어 그 판단은 애초에 불가능하다 (규약 §4)"
        )
        src_w, src_h = _src_dims(dump)
        # 적용각 0 = 항등. `/1` 덤프의 letterbox 기준이 센서 치수 그대로라는 뜻이기도 하다.
        return RotationMap(0, src_w, src_h, applied=False, site=site)

    # ── `/2` 이상: 규약 §4-2의 세 경우 ────────────────────────────────────
    _require(
        isinstance(site, str) and site,
        f"{label}: format={dump.format_id}인데 source.rotation_site가 없다({site!r}) — "
        f"`/2`는 **어디서 돌렸는가**를 요구한다(규약 §4-1). 그 값이 없으면 "
        f"rotation_applied=false가 '의도된 대조군'인지 '구현 안 됨'인지 구분되지 않는다",
    )
    if site == ROTATION_SITE_FORBIDDEN:
        raise ParityError(
            f"{label}: rotation_site={site!r}는 규약 §4-1의 어휘에 **없다** — `ImageAnalysis`가 "
            f"회전까지 해서 주면 변환이 **E 구간 밖에서** 일어나 E가 과소로 나오고, 덤프된 "
            f"평면이 센서 방향이라는 §4-5 전제도 깨진다. 🔴 **그 런의 E는 인용할 수 없다.** "
            f"회전을 전처리 안으로 옮겨 다시 뜰 것"
        )
    known_site = site in ROTATION_SITES_APPLIED or site == ROTATION_SITE_NONE
    _require(
        known_site,
        f"{label}: rotation_site={site!r}는 아는 어휘가 아니다"
        f"(아는 것: {list(ROTATION_SITES_APPLIED)} + {ROTATION_SITE_NONE!r}). "
        f"어디서 돌렸는지 모르면 PC가 **같은 자리에서** 재현할 수 없다 (규약 §4-1)",
    )
    if applied and site == ROTATION_SITE_NONE:
        raise ParityError(
            f"{label}: 🔴 **모순이다** — rotation_applied=true인데 rotation_site='none'이다. "
            f"적용했다면 어디서 적용했는지가 있어야 한다 (규약 §4-1·§4-2)"
        )
    if not applied and site in ROTATION_SITES_APPLIED:
        raise ParityError(
            f"{label}: 🔴 **모순이다** — rotation_applied=false인데 rotation_site={site!r}다. "
            f"규약 §4-2의 표에 없는 조합이라 PC가 회전을 재현해야 하는지 알 수 없다. "
            f"의도된 대조군이면 site='none'이어야 하고, 적용했다면 applied=true여야 한다"
        )

    effective = deg if applied else 0
    if not applied:
        # 🟢 **의도된 대조군이다. 이슈 29 경고를 내지 않는다** — 여기서 경고를 내면 짝 arm을
        #    돌릴 때마다 결함처럼 보이고, 그러면 진짜 경고도 같이 읽히지 않게 된다.
        #    다만 "회전 전 기준선"이라는 사실은 요약에 남는다(dump.rotation 블록).
        if deg == 0:
            warnings.append(
                f"{label}: rotation_site='none'인데 기기가 보고한 rotation_degrees도 0°다 — "
                f"이 런은 회전 유무의 **대조가 되지 않는다**(적용해도 항등이다). 짝 arm의 "
                f"기준선으로 쓰려면 0°가 아닌 방향으로 다시 찍을 것"
            )
    elif deg == 0:
        # ⚠ 규약 §4-2: `rotation_degrees: 0`과 `rotation_applied: false`는 다르다.
        warnings.append(
            f"{label}: rotation_applied=true인데 rotation_degrees=0°다 — 회전은 **적용됐고 "
            f"항등**이다(규약 §4-2). 미적용과 혼동하지 말 것. 다만 이 런의 E는 회전 비용을 "
            f"보여 주지 못한다(옮길 픽셀이 제자리다)"
        )

    if not src_info.get("rotation_locked", False):
        warnings.append(
            f"{label}: source.rotation_locked가 참이 아니다({src_info.get('rotation_locked')!r}) "
            f"— 회전각을 첫 프레임에서 잠갔다는 보장이 없다. 런 도중 각이 바뀌면 letterbox "
            f"기하가 갈려 **E와 박스 좌표가 한 런 안에서 두 뜻을 갖는다** (규약 §4-3)"
        )
    changed = src_info.get("rotation_changed_frames")
    if isinstance(changed, int) and changed != 0:
        warnings.append(
            f"{label}: 🔴 **rotation_changed_frames={changed}** — 잠근 뒤에도 다른 "
            f"rotationDegrees가 {changed}프레임에서 왔다. 앱은 잠근 값을 계속 썼으므로 이 "
            f"대조 자체는 성립하지만, **이 런은 승격 대상에서 뺀다**(규약 §4-3). 기기를 "
            f"고정하고 다시 잴 것"
        )

    src_w, src_h = _src_dims(dump)
    rot = RotationMap(effective, src_w, src_h, applied=applied, site=site)

    # 🔴 `rotated_*`도 **PC가 자기 힘으로 유도하고 대조만 한다**(letterbox와 같은 원칙).
    #    매니페스트 값을 그대로 쓰면 회전 기하 이식이 검사 대상에서 빠진다.
    dec_w = src_info.get("rotated_width")
    dec_h = src_info.get("rotated_height")
    if dec_w is None or dec_h is None:
        warnings.append(
            f"{label}: source.rotated_width/height가 없다(규약 §3) — PC가 유도한 "
            f"{rot.rot_w}×{rot.rot_h}와 **대조하지 못했다**"
        )
    elif int(dec_w) != rot.rot_w or int(dec_h) != rot.rot_h:
        would_be = (src_h, src_w) if deg in (90, 270) else (src_w, src_h)
        extra = ""
        if not applied and (int(dec_w), int(dec_h)) == would_be:
            extra = (
                " ⚠ 매니페스트 값은 **적용했다면 됐을** 치수와 같다 — 대조군 arm에서 "
                "'회전 후 치수'를 적용 여부와 무관하게 적어 낸 것으로 보인다. 그렇다면 "
                "letterbox 기준도 어느 쪽인지 확인할 것"
            )
        warnings.append(
            f"{label}: 🔴 **회전 후 치수가 어긋난다** — 매니페스트 {dec_w}×{dec_h} vs PC 유도 "
            f"{rot.rot_w}×{rot.rot_h}(센서 {src_w}×{src_h}, 적용각 {effective}°)."
            f"{extra} 아래 letterbox·E의 차이는 전처리 이식이 아니라 **기준 프레임이 다른 "
            f"것**일 수 있다 (규약 §5)"
        )
    return rot


def _src_dims(dump: Dump) -> tuple[int, int]:
    """샘플들의 `src.width/height`. **샘플마다 다르면 죽는다** — 한 런 안에서 센서 치수가
    바뀌면 letterbox 기하도 회전 후 치수도 두 뜻을 갖는다."""
    dims = {(int(s["src"]["width"]), int(s["src"]["height"]))
            for s in dump.manifest.get("samples") or []}
    _require(
        len(dims) == 1,
        f"{dump.label}: 샘플들의 src 치수가 하나가 아니다({sorted(dims)}) — 한 런 안에서 "
        f"센서 해상도가 바뀌면 letterbox 기하와 회전 후 치수가 두 뜻을 갖는다",
    )
    return next(iter(dims))


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


def _set_label(verdict: Optional[bool]) -> str:
    """3값을 사람이 읽는 말로. **None을 '불일치'라고 쓰지 않는다.**"""
    if verdict is None:
        return "판정 불가(양쪽 다 박스 0개 — 비교할 것이 없었다)"
    return "일치" if verdict else "🔴 불일치"


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


def _output_diff_by_channel(pc2d, phone2d, conf_thr: float) -> dict:
    """출력 텐서의 차이를 **좌표와 점수로 갈라서** 낸다. 🔴 안전 질문은 점수 쪽이다.

    출력은 `(4+nc, A)`이고 채널 0~3은 `cx,cy,w,h`(letterbox 640 픽셀 스케일), 4.. 는
    클래스별 점수(0~1, 시그모이드 적용됨)다. 둘이 **단위가 다른데** 한 축에 섞여 있어서
    전체 `max|diff|` 하나로는 "0.006이 픽셀인가 점수인가"를 말할 수 없다.

    🔴 그리고 **진짜 물어야 할 것은 float 차이가 아니라 판정이 뒤집혔는가**다.
    폰에서는 conf 임계를 넘고 PC에서는 못 넘는 앵커가 있으면, 그건 **박스 하나가 이식
    때문에 생기거나 사라진 것**이고 그게 안전 문제다. 차이가 아무리 작아도 임계 바로
    위아래에 있으면 뒤집힌다 — 그래서 개수를 센다.
    """
    n_box = BOX_CHANNELS
    pc_box, ph_box = pc2d[:n_box], phone2d[:n_box]
    pc_sc, ph_sc = pc2d[n_box:], phone2d[n_box:]

    def _stat(a, b) -> dict:
        d = np.abs(np.asarray(a, dtype=np.float64) - np.asarray(b, dtype=np.float64))
        return {
            "max_abs_diff": float(d.max()) if d.size else 0.0,
            "mean_abs_diff": float(d.mean()) if d.size else 0.0,
            "elements": int(d.size),
        }

    # 앵커별 최대 점수로 임계 판정을 만든다(후처리가 클래스별 최대를 쓰는 것과 같은 기준).
    pc_pass = pc_sc.max(axis=0) >= conf_thr if pc_sc.size else np.zeros(0, dtype=bool)
    ph_pass = ph_sc.max(axis=0) >= conf_thr if ph_sc.size else np.zeros(0, dtype=bool)
    flipped = int(np.count_nonzero(pc_pass != ph_pass))
    anchors = int(pc_pass.size)
    return {
        "box_channels": {"channels": f"0~{n_box - 1} (cx,cy,w,h)",
                         "unit": "letterbox 640 픽셀", **_stat(pc_box, ph_box)},
        "score_channels": {"channels": f"{n_box}.. (클래스별 점수)",
                           "unit": "0~1 (시그모이드 적용됨)", **_stat(pc_sc, ph_sc)},
        "threshold_flips": {
            "conf": conf_thr,
            "anchors_total": anchors,
            "pc_pass": int(np.count_nonzero(pc_pass)),
            "phone_pass": int(np.count_nonzero(ph_pass)),
            "flipped": flipped,
            "flipped_fraction": round(flipped / anchors, 9) if anchors else None,
            "note": (
                "🔴 **이 수가 안전 질문의 답이다.** conf 임계를 폰과 PC가 다르게 판정한 앵커 "
                "수다 — 0이 아니면 박스 하나가 이식 때문에 생기거나 사라질 수 있다는 뜻이고, "
                "float 차이가 아무리 작아도 임계 바로 위아래에 있으면 뒤집힌다. "
                "⚠ 뒤집힌 앵커가 NMS에서 어차피 억제될 수도 있으므로 이 수가 곧 박스 수는 "
                "아니다 — 최종 결과는 G_postprocess가 말한다"
            ),
        },
    }


def _probe_box_convention(phone_out2d, box: Letterbox, conf_thr: float, iou_thr: float,
                          classes: dict, rot: RotationMap, phone_boxes: list[dict],
                          bar) -> Optional[dict]:
    """🔴 **폰이 박스 회전 역변환에 어느 원점 규약을 썼는지 이름 붙인다.**

    두 규약이 있고 **정확히 1.0px** 차이가 난다(`RotationMap.invert_box` 주석):

    | 규약 | 반사축 식 | 회전 후 프레임 `[0, W]`가 가는 곳 |
    |---|---|---|
    | 연속(이 스크립트의 기본) | `W - x` | `[0, W]` |
    | 픽셀 인덱스 | `(W-1) - x` | `[-1, W-1]` — 원점 쪽으로 1px 밀린다 |

    🔴 **규약 §5-1이 `N − v`(연속)로 정했다.** 그러므로 `phone_matches == "pixel_index"`는
    "팀이 정할 문제"가 아니라 **폰 쪽 규약 위반**이다 — 이 함수는 그 회귀의 **유일한 자동
    감시자**다. (v1.2 초판에는 이 한 줄이 없었고, 그때 이 probe는 "규약 미정 진단"이었다.
    바뀐 것은 문서이고 판정 성격이 승격됐다.)

    둘 다 **왕복은 정확히 성립**하므로 어느 쪽 자기검사로도 잡히지 않는다(§5-1이 같은 말을
    한다). 잡히는 자리는 (a) 프레임 전체 박스 검산 (b) 폰↔PC 대조 둘뿐이고, 이것이 (b)다.
    (a)는 `_selfcheck_frame_box()`가 PC 쪽에서, `scripts/detect_parity_roundtrip.py`가
    구조적 불변식으로 지킨다.

    🔴 허용오차를 지어내지 않는다 — 두 후보의 `max_xy`를 **그대로 견주기만** 한다. 규약
    위반이면 반사축에서 정확히 1.0px이 나오지만, 그 1.0을 문턱으로 쓰지 않는다(어느 쪽이
    폰과 **더 맞는가**만 본다).

    ⚠ **실데이터 커버리지 0이다.** 회전이 항등(0°)이면 두 규약이 같은 값을 내므로 None을
    내는데, 이 저장소의 실기기 덤프 7개는 **전부 `/1`(적용각 0°)**이라 이 probe는 **실기기
    덤프에서 한 번도 돈 적이 없다.** 지금까지 태운 것은 합성 `/2` 덤프뿐이다.
    """
    if rot.degrees == 0 or not phone_boxes:
        return None
    alt = RotationMap(rot.degrees, rot.src_w, rot.src_h, applied=rot.applied,
                      site=rot.site, box_edge_offset=1.0)
    alt_boxes, _, _ = postprocess(phone_out2d, box, conf_thr, iou_thr, classes, alt)
    cont = _compare_boxes(phone_boxes, postprocess(
        phone_out2d, box, conf_thr, iou_thr, classes, rot)[0], bar)
    idx = _compare_boxes(phone_boxes, alt_boxes, bar)
    cont_xy = cont.get("max_xy_diff_px")
    idx_xy = idx.get("max_xy_diff_px")
    if cont_xy is None or idx_xy is None:
        matches = None
    elif idx_xy < cont_xy:
        matches = "pixel_index"
    elif cont_xy < idx_xy:
        matches = "continuous"
    else:
        matches = "tie"
    return {
        "continuous_max_xy_diff_px": cont_xy,
        "pixel_index_max_xy_diff_px": idx_xy,
        "phone_matches": matches,
        "pc_default": "continuous",
        "spec_compliant_convention": "continuous",
        # 🔴 불리언으로 명시한다 — 사람이 두 숫자를 보고 판단하게 두지 않는다.
        "phone_violates_spec_5_1": (None if matches is None else matches == "pixel_index"),
        "note": (
            "🔴 두 규약은 반사축에서 **정확히 1.0px** 차이가 난다. **규약 §5-1이 `N − v`"
            "(연속 좌표)로 정했다** — 박스 좌표는 letterbox 역변환이 낸 연속 좌표라 `[0, N]`을 "
            "가득 채우기 때문이다. 그러므로 phone_matches가 'pixel_index'면 폰이 `(N-1) - v`를 "
            "쓴 것이고 그것은 **규약 위반**이다(전처리 샘플 맵은 반대로 `(N-1) - v`가 맞다 — "
            "한 함수를 두 자리에 쓰면 한쪽이 정확히 1px 틀린다). "
            "🔴 **'아직 안 정해진 문제'가 아니다** — 고칠 곳은 폰이다. "
            "⚠ 이 probe는 적용각 0°면 None을 내므로, `/1` 덤프뿐인 지금까지 **실기기에서 한 번도 "
            "돈 적이 없다**(합성 `/2` 덤프에서만 확인됐다)"
        ),
    }


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

    # 🔴 **샘플이 언제 잡혔는지를 본다.** t_recv_ns 로 직접 계산한다 — 매니페스트의
    #   sampling.span_sec 은 앱의 자진 신고이고, 여기서 대조해 어긋나면 그것도 말한다.
    #   (분석 창을 p50×표본수로 유도했다가 잡힌 전례가 있어 span 은 늘 실제 값에서 뽑는다.)
    ts = [s.get("t_recv_ns") for s in (m.get("samples") or []) if s.get("t_recv_ns") is not None]
    span = (max(ts) - min(ts)) / 1e9 if len(ts) >= 2 else 0.0
    sampling = m.get("sampling") or {}
    if not sampling:
        warnings.append(
            f"{dump.label}: 매니페스트에 `sampling` 블록이 없다(구 덤프) — 샘플이 런 전체에 "
            f"퍼졌는지 **선언으로는 알 수 없다.** t_recv_ns 로 직접 계산한 실제 걸친 시간은 "
            f"{span:.3f}초다"
        )
    declared = sampling.get("span_sec")
    if declared is not None and abs(float(declared) - span) > 0.01:
        warnings.append(
            f"{dump.label}: 매니페스트의 sampling.span_sec={declared}와 t_recv_ns 로 계산한 "
            f"{span:.3f}초가 다르다 — 앱의 자진 신고와 실제가 어긋난다"
        )
    if len(ts) >= 2 and span < SAMPLE_SPAN_WARN_SEC:
        warnings.append(
            f"{dump.label}: 🔴 **샘플 {len(ts)}개가 {span:.3f}초 안에 몰려 있다** — 사실상 "
            f"한 장면이다. 표본 수만 보면 넉넉해 보이지만 **장면 다양성은 사실상 1**이고, "
            f"이 덤프로 '여러 장면에서 일치했다'고 쓰면 안 된다. 앱이 최소 간격을 두고 "
            f"샘플을 잡는지 확인할 것(sampling.min_gap_ms)"
        )

    src_info = m.get("source") or {}
    # 🔴 회전은 여기서 한 번 정한다(규약 §4-2의 세 경우). 모순이면 이 안에서 죽는다.
    rot = resolve_rotation(dump, warnings)

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
        # 🔴 **기준 프레임이 버전에 따라 다르다**(규약 §3-3): `/1`은 센서 치수,
        #    `/2`는 **회전 후** 치수. 그 치수 자체도 PC가 유도한 것이지 매니페스트의
        #    `rotated_*`를 받아 쓴 것이 아니다(resolve_rotation이 대조만 한다).
        box = letterbox(rot.rot_w, rot.rot_h,
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
        pc_in = preprocess(raw_src, src, box, pad_u8, rot)
        e_diff = _tensor_diff(pc_in.reshape(-1), phone_in.reshape(-1), "input_tensor")

        # ── F(본진): 폰 input.f32 → PC ORT vs 폰 output.f32 ───────────────
        feed = {in_name: phone_in.reshape([int(d) for d in inp["shape"]]).astype("float32")}
        pc_out = _decode_output(np.asarray(sess.run([out_name], feed)[0]))
        phone_out2d = _decode_output(phone_out)
        f_diff = _tensor_diff(pc_out, phone_out2d, "output_tensor")
        # 🔴 **전체 max|diff| 하나로는 안전 질문에 답할 수 없다.** 출력 텐서는 축 하나에
        #   좌표(letterbox 640 픽셀 스케일)와 클래스 점수(0~1)가 **섞여** 있어서, "최대 0.006"이
        #   1/1000 픽셀인지 점수 0.006인지 구분되지 않는다. 갈라서 낸다.
        f_diff["by_channel"] = _output_diff_by_channel(pc_out, phone_out2d, conf_thr)
        # 🔴 raw float 차이만으로는 안전을 말할 수 없다 — **같은 후처리로 디코딩한 탐지
        #    집합**이 같은지를 함께 낸다.
        f_boxes_pc, _, _ = postprocess(pc_out, box, conf_thr, iou_thr, classes, rot)
        f_boxes_phone_tensor, _, _ = postprocess(phone_out2d, box, conf_thr, iou_thr,
                                                 classes, rot)
        f_set = _compare_boxes(f_boxes_phone_tensor, f_boxes_pc, bar)

        # ── G: 폰 output.f32 → PC 후처리 vs 폰 boxes ──────────────────────
        g_boxes, g_pre, g_maxconf = postprocess(phone_out2d, box, conf_thr, iou_thr,
                                                classes, rot)
        phone_boxes = _normalize_phone_boxes(s.get("boxes") or [], classes, where, warnings)
        g_cmp = _compare_boxes(phone_boxes, g_boxes, bar)
        # 🔴 폰이 박스 회전 역변환에 어느 원점 규약을 썼는가(정확히 1.0px 차이). 규약 §5-1이
        #    아직 정하지 않은 한 줄이라, 값을 맞추지 않고 **이름을 붙여 관측으로 낸다.**
        conv = _probe_box_convention(phone_out2d, box, conf_thr, iou_thr, classes, rot,
                                     phone_boxes, bar)
        if conv and conv.get("phone_matches") == "pixel_index":
            warnings.append(
                f"{where}: 🔴 **폰이 규약 §5-1의 회전 역변환 식을 어겼다** — 폰은 픽셀 인덱스 "
                f"식 `(N-1)-v`와 맞고(max_xy={conv['pixel_index_max_xy_diff_px']}px), 규약이 "
                f"정한 연속 좌표 식 `N-v`와는 "
                f"max_xy={conv['continuous_max_xy_diff_px']}px만큼 어긋난다. "
                f"🔴 **이것은 '아직 안 정해진 문제'가 아니다** — §5-1이 `N-v`로 정했고 "
                f"검산도 함께 정했다(회전 후 프레임 전체 박스가 센서 프레임 전체로 가야 한다. "
                f"인덱스 식이면 한 축이 -1로 나온다). **고칠 곳은 폰이고 PC가 아니다.** "
                f"반사축에서 정확히 1.0px씩 밀리며 **양쪽 다 왕복은 성립**해 폰의 자기검사로는 "
                f"안 잡힌다 — 잡히는 자리가 여기다. ④ 강조가 이 좌표를 그대로 받는다"
            )
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
        e2e_boxes, _, _ = postprocess(e2e_out, box, conf_thr, iou_thr, classes, rot)
        e2e_cmp = _compare_boxes(phone_boxes, e2e_boxes, bar)

        # ── 역전 박스: **세되 거르지 않는다**(규약 §5-3) ───────────────────
        # 🔴 대조 자체는 성립한다 — 폰과 PC가 **같은 역전 박스**를 내면 이식은 맞다는 뜻이고,
        #    그때 문제는 상류 로직이나 모델 쪽이다. 그래서 경고이지 실패가 아니다.
        inv_pc = count_inverted_boxes(g_boxes)
        inv_phone_counted = count_inverted_boxes(phone_boxes)
        inv_declared = s.get("boxes_inverted")
        if inv_declared is not None and int(inv_declared) != 0:
            warnings.append(
                f"{where}: 🔴 **boxes_inverted={inv_declared}** — 폰이 x2<x1 또는 y2<y1인 "
                f"박스를 {inv_declared}개 냈다(거른 개수가 아니라 **센 개수**이며 boxes 배열에 "
                f"그대로 있다). PC 재구현도 같은 텐서에서 {inv_pc}개를 냈다. "
                f"⚠ **대조 자체는 성립한다** — 양쪽이 같은 역전 박스를 내면 이식은 맞고 "
                f"원인은 상류 로직이나 모델 쪽(w·h의 부호)이다. ④ 강조가 이 박스를 그대로 "
                f"받으면 화면 가장자리에 면적 0의 선이 남는다 (규약 §5-3)"
            )
        elif inv_pc or inv_phone_counted:
            warnings.append(
                f"{where}: 🔴 **역전 박스가 있는데 매니페스트의 boxes_inverted는 "
                f"{inv_declared!r}이다** — 폰 박스에서 센 것 {inv_phone_counted}개, PC 재구현 "
                f"{inv_pc}개. 세는 쪽이 어긋났거나 앱이 역전 박스를 **거르고 있다**(규약 §5-3은 "
                f"거르지 말고 세라고 한다 — 조용히 지우면 결함이 숨는다)"
            )

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
                "선언값을 그대로 쓰면 기하 이식 결함이 숨는다. "
                f"🔴 기준 프레임은 포맷 버전이 정한다(규약 §3-3): `/1`은 센서 치수, "
                f"`/2`는 **회전 후** 치수 — 이 덤프는 /{dump.format_version}이라 "
                f"{box.src_w}×{box.src_h}에서 계산했다"
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
            # 🔴 규약 §5-3. **거른 개수가 아니라 센 개수다.**
            "G_boxes_inverted_pc": inv_pc,
            "G_boxes_inverted_declared": inv_declared,
            "G_boxes_inverted_phone_counted": inv_phone_counted,
            "G_box_convention_probe": conv,
            "G_boxes_inverted_equal": (
                None if inv_declared is None else int(inv_declared) == inv_pc
            ),
            "E2E": e2e_cmp,
        })

    return {
        "dump_dir": str(dump.root.resolve()),
        "dump_label": dump.label,
        "format": dump.format_id,
        "format_version": dump.format_version,
        "ep": m.get("ep"),
        "classes": m.get("classes"),
        "thresholds": {"conf": conf_thr, "iou": iou_thr},
        "source": src_info,
        "rotation_applied": bool(src_info.get("rotation_applied", False)),
        # 🔴 **PC가 실제로 적용한 회전**. 매니페스트의 선언값과 다를 수 있다 —
        #    대조군 arm(applied=false)에서는 기기가 90°를 보고해도 PC는 0°를 적용한다.
        "rotation_pc": rot.as_dict(),
        "rotation_note": (
            "🔴 **회전을 적용했다는 것이 모델이 정확하다는 뜻이 아니다**(규약 §4-4). 입력이 "
            "바로 서면 boxes·max_conf를 **읽을 수는 있게** 되지만, 정답 라벨도 골든 샘플도 "
            "없어 mAP·누락률은 여전히 말할 수 없다. 이 덤프가 답하는 것은 끝까지 "
            "'폰과 PC가 같은 답을 내는가'까지다. "
            "⚠ rotation_site가 E의 **정의**를 바꾸지는 않는다(E는 DetectPipeline이 t를 찍는 "
            "위치 그대로다) — 바뀌는 것은 E의 **값**이고, 그것은 회귀가 아니라 조건 변경이다"
        ),
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
        # 🔴 EP끼리 대조하려면 **회전 조건이 같아야** 한다. 회전이 다른 덤프끼리 비교하면
        #    출력이 다른 것이 당연하고, 그 차이를 EP 차이(fp16 강등 등)로 오독하게 된다.
        rots = {d["dump_label"]: (d["rotation_pc"] or {}).get("degrees_effective")
                for d in per_dump}
        if len(dumps) > 1 and len(set(rots.values())) > 1:
            warnings.append(
                f"🔴 **덤프들의 적용 회전각이 서로 다르다**({rots}) — EP 간 대조는 같은 입력을 "
                f"전제로 하는데 회전이 다르면 입력부터 다르다. 아래 cross_ep의 차이를 "
                f"EP 차이(fp16 강등 등)로 읽으면 틀린다(각 항목의 same_input을 볼 것). "
                f"EP 비교는 **같은 회전 조건**으로 다시 찍어서 할 것"
            )
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
    inverted_pc_total = sum(
        (s.get("G_boxes_inverted_pc") or 0) for d in per_dump for s in d["samples"]
    )
    inverted_declared_total = sum(
        (s.get("G_boxes_inverted_declared") or 0) for d in per_dump for s in d["samples"]
    )

    summary = {
        "run_ts": paths.run_ts,
        "label": args.label,
        # 🔴 덤프마다 포맷 버전이 다를 수 있다(`/1`과 `/2`를 둘 다 읽는다). 하나로 뭉치면
        #    "이 요약이 어느 규격으로 읽힌 것인가"가 사라진다.
        "formats_read": {d["dump_label"]: d["format"] for d in per_dump},
        "formats_known": list(FORMAT_IDS),
        "format_spec": "docs/plans/20260806_detect_parity_dump_format.md",
        "scope_note": (
            "🔴 **이것은 '모델이 옳다'의 증거가 아니다.** 이식이 값을 안 바꿨다는 증거일 "
            "뿐이다. mAP·누락률은 정답 라벨이 필요하고 여기에 없다. "
            "🔴 **회전을 적용해도 이 문장은 그대로다**(규약 §4-4) — 입력이 바로 서면 박스를 "
            "읽을 수 있게 될 뿐, 정답 라벨이 없는 것은 그대로다"
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
            # 🔴 **F 의 안전 지표.** float 차이가 아니라 "판정이 뒤집혔는가"다.
            "F_threshold_flips_total": sum(
                ((s["F_inference"].get("by_channel") or {}).get("threshold_flips") or {})
                .get("flipped", 0)
                for d in per_dump for s in d["samples"]
            ),
            "F_box_channel_max_px": max(
                [((s["F_inference"].get("by_channel") or {}).get("box_channels") or {})
                 .get("max_abs_diff", 0.0) for d in per_dump for s in d["samples"]] or [0.0]
            ),
            "F_score_channel_max": max(
                [((s["F_inference"].get("by_channel") or {}).get("score_channels") or {})
                 .get("max_abs_diff", 0.0) for d in per_dump for s in d["samples"]] or [0.0]
            ),
            "F_channel_note": (
                "🔴 **전체 max|diff| 를 안전 근거로 쓰지 말 것** — 출력 텐서는 한 축에 "
                "좌표(letterbox 640 픽셀)와 클래스 점수(0~1)가 섞여 있어 단위가 다르다. "
                "안전 질문의 답은 F_threshold_flips_total 이다: conf 임계를 폰과 PC가 다르게 "
                "판정한 앵커 수이며, 0이 아니면 박스가 이식 때문에 생기거나 사라질 수 있다"
            ),
            # 🔴 **불리언보다 이 수를 먼저 본다.** 0이면 위 두 값은 null이고, 그건 일치가
            #    아니라 **비교된 박스가 한 개도 없었다**는 뜻이다.
            "boxes_compared_total": boxes_total,
            "boxes_note": (
                "🔴 **boxes_compared_total이 0이면 위 F/G 탐지집합 값은 null이다** — "
                "'일치했다'가 아니라 **비교할 것이 없었다**는 뜻이다. 탐지가 0개인 장면을 "
                "찍었거나 conf 임계 아래였다는 것이고, 그 덤프로는 후처리 이식을 검증하지 "
                "못한다. 박스가 나오는 장면으로 다시 찍을 것"
            ),
            # 🔴 규약 §5-3. **거른 개수가 아니라 센 개수다** — 0이 아니어도 대조는 성립한다
            #    (폰과 PC가 같은 역전 박스를 내면 이식은 맞다는 뜻이다).
            "boxes_inverted_pc_total": inverted_pc_total,
            "boxes_inverted_declared_total": inverted_declared_total,
            "boxes_inverted_note": (
                "x2<x1 또는 y2<y1인 박스 수다. **거르지 않는다** — 걸러 내면 면적 0의 선이 "
                "화면 가장자리에 남는 결함이 조용히 숨는다. 0이 아니면 경고를 내되 **대조 "
                "자체는 성립한다**: 폰과 PC가 같은 역전 박스를 내면 이식은 맞고 원인은 상류 "
                "로직이나 모델 쪽(w·h의 부호)이다"
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
            "  [%s] ep %s→%s · 샘플 %s개 · conf=%s iou=%s · format=%s",
            d["dump_label"], ep.get("requested"), ep.get("resolved"),
            d["sample_count"], d["thresholds"]["conf"], d["thresholds"]["iou"],
            d.get("format"),
        )
        rp = d.get("rotation_pc") or {}
        src_rot = (d.get("source") or {}).get("rotation_degrees")
        LOG.info(
            "      회전: 기기 보고 %s° · 적용 %s(site=%s) · PC 적용각 %s° · "
            "센서 %s×%s → 회전 후 %s×%s",
            src_rot, rp.get("applied"), rp.get("site"), rp.get("degrees_effective"),
            rp.get("sensor_width"), rp.get("sensor_height"),
            rp.get("rotated_width_pc"), rp.get("rotated_height_pc"),
        )
        if rp.get("applied"):
            LOG.info(
                "      🔴 회전을 적용했다는 것은 **모델이 정확하다는 뜻이 아니다**(규약 §4-4) "
                "— 정답 라벨이 없어 mAP·누락률은 여전히 말할 수 없다"
            )
        elif rp.get("site") == ROTATION_SITE_NONE:
            LOG.info(
                "      🟢 의도된 대조군이다(rotation_site='none') — 회전 전 기준선. "
                "이슈 29(미구현)와 **다른 상태**다"
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
                # 🔴 **3값이다.** None을 falsy로 접으면 "비교할 것이 없었다"가 화면에서
                #    "불일치했다"로 둔갑한다 — 요약(_tri_all)은 옳게 내는데 이 줄만 어긋나
                #    같은 보고서 안에서 두 문장이 서로 모순됐다. 실기기 첫 대조에서 잡혔다.
                _set_label(_set_verdict(fs)),
                fs["count_lhs"], fs["count_rhs"],
            )
            bc = (f.get("by_channel") or {})
            if bc:
                tf = bc["threshold_flips"]
                LOG.info(
                    "          ↳ 좌표채널 max|d|=%.6g px · 점수채널 max|d|=%.6g · "
                    "🔴 임계 판정 뒤집힌 앵커 %s/%s (폰 통과 %s ↔ PC 통과 %s)",
                    bc["box_channels"]["max_abs_diff"], bc["score_channels"]["max_abs_diff"],
                    tf["flipped"], tf["anchors_total"], tf["phone_pass"], tf["pc_pass"],
                )
            LOG.info(
                "        G 박스 폰 %s ↔ PC %s · max_xy=%s px · max_conf_diff=%s · minIoU=%s "
                "· 역전(폰 선언 %s / PC %s)",
                g["count_lhs"], g["count_rhs"], g["max_xy_diff_px"],
                g["max_conf_diff"], g["min_matched_iou"],
                s.get("G_boxes_inverted_declared"), s.get("G_boxes_inverted_pc"),
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
    # 🔴 **불리언보다 이 수가 먼저다.** 0이면 아래 두 값은 null이고, 그건 "일치"가 아니라
    #    "비교된 박스가 한 개도 없었다"다.
    LOG.info(
        "F 채널별: 좌표 max|d|=%.6g px · 점수 max|d|=%.6g · "
        "🔴 임계 판정 뒤집힌 앵커 **총 %s개**",
        obs.get("F_box_channel_max_px", 0.0), obs.get("F_score_channel_max", 0.0),
        obs.get("F_threshold_flips_total"),
    )
    LOG.info("비교된 박스 총수: %s", obs.get("boxes_compared_total"))
    LOG.info(
        "역전 박스(x2<x1 또는 y2<y1) 총계: 폰 선언 %s · PC 재구현 %s "
        "— 🔴 **거른 개수가 아니라 센 개수다**(규약 §5-3)",
        obs.get("boxes_inverted_declared_total"), obs.get("boxes_inverted_pc_total"),
    )
    LOG.info("F 탐지 집합: %s", _set_label(obs["F_detection_set_agrees"]))
    LOG.info("G 탐지 집합: %s", _set_label(obs["G_detection_set_agrees"]))
    if obs.get("boxes_compared_total") == 0:
        LOG.warning(
            "⚠ **박스가 한 개도 없었다** — 이 덤프로는 G(후처리) 이식을 검증하지 못했다. "
            "E와 F는 박스와 무관하게 대조됐다. 박스가 나오는 장면으로 다시 찍을 것"
        )
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
