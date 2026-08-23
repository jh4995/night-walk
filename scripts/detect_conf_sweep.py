"""③ conf 임계 sweep — **이미 뜬 덤프**의 출력 텐서에 후처리만 다시 돌린다.

폰을 다시 돌리지 않는다. `parity/` 덤프에 ORT가 낸 `sample_NN_output.f32`가 그대로
남아 있으므로, 같은 텐서에 **G(후처리)만 여러 conf로** 재실행해 "임계를 바꾸면 무엇이
달라지는가"를 낸다. 포맷 규약은 `docs/plans/20260806_detect_parity_dump_format.md`이고
읽기 코드는 `scripts/detect_parity.py`를 그대로 재사용한다 — 후처리를 두 번 구현하면
그 둘이 갈리는 순간 어느 쪽이 맞는지 말할 수 없다.

    python scripts/detect_conf_sweep.py --dump <pulled>/parity --conf 0.35,0.25,0.10

## 🔴 이것이 답하는 질문과 답하지 못하는 질문

답한다: **같은 프레임에서 임계를 바꾸면 박스 수·클래스 구성·`pre_nms`가 어떻게 변하는가.**

답하지 못한다:

- **어느 임계가 옳은가.** `INTERFACES.md` 미확정 항목이고 U-10으로 팀에 반환돼 있다.
  이 스크립트는 **어느 값도 기본값·권장값으로 표시하지 않는다.**
- **G·H·I의 실제 비용.** 박스 수가 비용의 설명 변수인 것은 맞지만 비용은 실기기 시계로만
  말한다. 여기 박스 수로 ms를 **외삽하지 않는다**(추정을 실측처럼 내지 않는다).
- **탐지가 옳은가.** 정답 라벨이 없다. 박스가 늘었다고 좋아진 것도, 줄었다고 나빠진 것도
  아니다. 미탐·오탐 판정에는 `C2` 야간 클립과 라벨이 필요하다.

## 🔴 판정선이 없는 스크립트다

`lib/targets.py`의 판정선은 **프레임타임의 것**이고 여기와 무관하다. `meets_*_target`을
내지 않고 **종료 코드는 어떤 임계에서도 0**이다 — 종료 코드가 흔들리면 그것이 곧
"이 임계가 합격"이라는 판정이 된다. 0이 아닌 코드는 **sweep 자체가 성립하지 않을 때**만
나온다.

## 왜 오프라인인가

한 번 뜬 덤프로 **임계 전부**를 볼 수 있다. 임계마다 폰을 다시 돌리면 장면·발열·조명이
같지 않아 **임계 때문인지 조건 때문인지 갈리지 않는다.** 같은 텐서를 쓰는 것이 통제다.
"""

from __future__ import annotations

import json
import logging
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

from lib.run_utils import common_argparser, init_run  # noqa: E402

import scripts.detect_parity as dp  # noqa: E402

LOG = logging.getLogger(__name__)


def bind_numpy():
    """`detect_parity`는 numpy·onnxruntime을 **함께** 지연 import한다.

    🔴 이 스크립트는 **추론을 하지 않으므로 onnxruntime이 필요 없다.** 그래서
    `dp._require_deps()`를 부르지 않고 numpy만 붙인다 — 부르면 ORT가 없는 머신에서
    sweep과 무관한 이유로 죽는다.

    ⚠ 대신 그 함수가 하던 **프레임 박스 불변식 자기검사를 여기서 직접 부른다.**
    안 부르면 검사되지 않은 채 후처리가 돈다.
    """
    if dp.np is not None:
        return dp.np
    try:
        import numpy as _np  # noqa: PLC0415
    except ImportError as exc:  # pragma: no cover
        raise dp.ParityError(
            f"{exc.name}을 import하지 못했다. 설치: python -m pip install numpy "
            "(이 저장소는 uv를 쓰지 않는다)"
        ) from exc
    dp.np = _np
    dp._selfcheck_frame_box()
    return _np


def parse_conf_list(raw: str) -> list[float]:
    """쉼표로 나눈 conf 목록. **기본값을 코드에 박지 않는다** — 호출자가 준다."""
    out: list[float] = []
    for tok in str(raw).split(","):
        tok = tok.strip()
        if not tok:
            continue
        try:
            value = float(tok)
        except ValueError as exc:
            raise dp.ParityError(f"--conf 값을 숫자로 읽지 못했다: {tok!r}") from exc
        # 0.0을 허용하면 전 앵커가 통과해 NMS가 다른 것을 재게 된다(앱과 같은 방어선).
        if not 0.0 < value < 1.0:
            raise dp.ParityError(f"--conf는 0<v<1이어야 한다: {value}")
        out.append(value)
    if not out:
        raise dp.ParityError("--conf가 비었다")
    return sorted(set(out), reverse=True)


def _tensor(sample: dict, key: str, root: Path, where: str):
    """샘플의 텐서 서술자(`{file, shape, sha256, ...}`)를 읽어 배열로 돌려준다.

    규약 §3의 구조를 `detect_parity`와 **같은 방식으로** 읽는다 — 모양을 추측하지 않는다.
    채널 축과 앵커 축이 뒤바뀐 채 디코딩되면 후처리가 조용히 다른 것을 센다.

    ⚠ 서술자의 `sha256`도 대조한다. 이 스크립트는 **오래 전에 뜬 덤프**를 다시 읽는 것이
    용도라, 파일이 그 사이 바뀌었으면 sweep 결과가 그 덤프의 것이 아니게 된다.
    """
    blk = sample.get(key)
    dp._require(
        isinstance(blk, dict) and blk.get("file") and isinstance(blk.get("shape"), list),
        f"{where}: {key} 서술자를 읽지 못했다({blk!r}) — 규약 §3의 "
        f"{{file, shape, sha256}} 구조가 아니다",
    )
    path = root / str(blk["file"])
    dp._require(path.is_file(), f"{where}: {key} 텐서가 없다: {path}")
    want_sha = blk.get("sha256")
    if isinstance(want_sha, str) and want_sha:
        got = dp.sha256_file(path)
        dp._require(
            got == want_sha,
            f"{where}: {key} 텐서의 sha256이 서술자와 다르다 — 덤프가 뜬 뒤 파일이 "
            f"바뀌었다. 이 sweep은 그 덤프의 결과가 아니다 (서술자 {want_sha[:12]}… / "
            f"실제 {got[:12]}…)",
        )
    shape = [int(d) for d in blk["shape"]]
    return dp._read_f32(path, shape, where), shape


def sweep_dump(dump: dp.Dump, conf_list: list[float], warnings: list[str]) -> dict:
    """덤프 하나를 여러 conf로 후처리한다. iou는 고정 변수다."""
    manifest = dump.manifest
    classes = (manifest.get("classes") or {}).get("names") or {}
    thresholds = manifest.get("thresholds") or {}
    declared_conf = thresholds.get("conf")
    iou_thr = thresholds.get("iou")
    dp._require(
        isinstance(iou_thr, (int, float)),
        f"{dump.root}: thresholds.iou를 숫자로 읽지 못했다({iou_thr!r}) — "
        f"iou는 이 sweep의 고정 변수다. 지어내지 않는다",
    )
    if not classes:
        warnings.append(
            f"{dump.label}: 🔴 매니페스트 classes.names가 비었다 — 클래스별 집계가 "
            f"`class_<인덱스>`로 떨어진다. 이름 대조가 필요한 판단에 쓰지 말 것"
        )

    rot = dp.resolve_rotation(dump, warnings)
    samples = manifest.get("samples") or []
    dp._require(bool(samples), f"{dump.root}: 매니페스트에 samples가 없다")

    per_conf: dict[str, dict] = {
        f"{c:g}": {"conf": c, "samples": [], "boxes_total": 0,
                   "pre_nms_total": 0, "by_class": {}}
        for c in conf_list
    }

    for i, sample in enumerate(samples):
        where = f"{dump.label}#{i:02d}"
        phone_out, _ = _tensor(sample, "output", dump.root, where)
        raw2d = dp._decode_output(phone_out)

        # letterbox 기하는 conf와 무관하다 — 샘플마다 한 번만 유도한다.
        _, in_shape = _tensor(sample, "input", dump.root, where)
        box = dp.letterbox(rot.rot_w, rot.rot_h, int(in_shape[-1]), int(in_shape[-2]))

        for conf in conf_list:
            boxes, pre_nms, max_conf = dp.postprocess(
                raw2d, box, float(conf), float(iou_thr), classes, rot
            )
            slot = per_conf[f"{conf:g}"]
            by_class: dict[str, int] = {}
            for b in boxes:
                by_class[b["cls_name"]] = by_class.get(b["cls_name"], 0) + 1
            slot["samples"].append({
                "index": i,
                "boxes_out": len(boxes),
                "boxes_pre_nms": int(pre_nms),
                "max_conf": round(float(max_conf), 6),
                "by_class": by_class,
            })
            slot["boxes_total"] += len(boxes)
            slot["pre_nms_total"] += int(pre_nms)
            for name, count in by_class.items():
                slot["by_class"][name] = slot["by_class"].get(name, 0) + count

    return {
        "dump": str(dump.root),
        "label": dump.label,
        "format": dump.format_id,
        "sample_count": len(samples),
        # 🔴 매니페스트가 **선언한** 값이다. 권장값이 아니다 — 출처는 상류 export
        #    스크립트의 argparse 기본값이고 상류의 어떤 판정에도 없다
        #    (docs/research/RESEARCH_20260823_UPSTREAM.md §4).
        "declared_conf": declared_conf,
        "iou": iou_thr,
        "by_conf": per_conf,
    }


def render(results: list[dict], conf_list: list[float], warnings: list[str]) -> str:
    lines: list[str] = []
    add = lines.append
    add("=" * 78)
    add("③ conf 임계 sweep — 같은 출력 텐서에 후처리만 다시 돌린 것")
    add("=" * 78)
    add("🔴 어느 임계도 권장값이 아니다. 계약값은 미확정이고 U-10으로 팀에 반환돼 있다.")
    add("🔴 박스 수로 G·H·I의 ms를 외삽하지 마라 — 비용은 실기기 시계로만 말한다.")
    add("")
    for r in results:
        add(f"── {r['label']}  (샘플 {r['sample_count']}개 · format {r['format']} · "
            f"iou {r['iou']} · 매니페스트 선언 conf {r['declared_conf']})")
        add(f"   {'conf':>6}  {'박스합':>7}  {'pre_nms':>8}   클래스별")
        for conf in conf_list:
            slot = r["by_conf"][f"{conf:g}"]
            by_class = slot["by_class"]
            shown = ", ".join(f"{k}={v}" for k, v in sorted(by_class.items())) or "—"
            declared = r["declared_conf"]
            mark = ""
            if isinstance(declared, (int, float)) and abs(float(declared) - conf) < 1e-9:
                mark = "  ← 매니페스트 선언값"
            add(f"   {conf:>6g}  {slot['boxes_total']:>7}  {slot['pre_nms_total']:>8}   "
                f"{shown}{mark}")
        # max_conf는 임계와 무관하다 — 한 번만 낸다. 어느 임계가 그 프레임을 가르는지
        # 사람이 바로 보게 하는 것이 이 줄의 목적이다.
        first = r["by_conf"][f"{conf_list[0]:g}"]["samples"]
        add("   샘플별 max_conf: "
            + ", ".join(f"#{s['index']:02d}={s['max_conf']:.3f}" for s in first))
        add("")
    if warnings:
        add("⚠ 경고")
        for w in warnings:
            add(f"  - {w}")
        add("")
    add("말하지 않는 것: 어느 임계가 옳은가 · G/H/I의 비용 · 탐지가 옳은가.")
    return "\n".join(lines)


def main() -> int:
    parser = common_argparser()
    parser.add_argument("--dump", action="append", required=True,
                        help="parity 덤프 디렉토리. 여러 번 줄 수 있다")
    parser.add_argument("--conf", required=True,
                        help="쉼표로 나눈 conf 목록 (예: 0.35,0.25,0.10). "
                             "기본값을 두지 않는다: 무엇을 볼지 호출자가 정한다 "
                             "(어느 값도 권장값이 아니다)")
    args = parser.parse_args()

    paths = init_run(stage="detect_conf_sweep", script_file=__file__, args=args)

    bind_numpy()
    conf_list = parse_conf_list(args.conf)
    warnings: list[str] = []
    results = [sweep_dump(dp.load_dump(Path(d)), conf_list, warnings)
               for d in args.dump]

    for line in render(results, conf_list, warnings).splitlines():
        LOG.info(line)

    if paths.outputs_enabled:
        out = paths.out_dir / "conf_sweep.json"
        out.write_text(
            json.dumps({
                "conf_list": conf_list,
                "dumps": results,
                "warnings": warnings,
                "note": "어느 임계도 권장값이 아니다. 박스 수로 ms를 외삽하지 마라.",
            }, ensure_ascii=False, indent=2),
            encoding="utf-8",
        )
        LOG.info(f"sweep 저장: {out}")

    # 🔴 임계가 무엇이든 0. 종료 코드로 합격을 말하지 않는다.
    return 0


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except dp.ParityError as exc:
        print(f"conf sweep 실패: {exc}", file=sys.stderr)
        raise SystemExit(2) from exc
