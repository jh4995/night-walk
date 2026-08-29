"""③ 두 모델 대조 — **같은 입력 텐서**에 모델 여럿을 먹여 conf·박스 수를 비교한다.

폰을 다시 돌리지 않는다. `parity/` 덤프에 폰이 ORT에 넘긴 `sample_NN_input.f32`가 그대로
남아 있으므로, **그 동일한 텐서**를 모델 A·B에 각각 먹여 "모델이 바뀌면 무엇이 달라지는가"를
낸다. 장면·조명·초점·발열이 전부 같은 값이라 **모델 말고는 변수가 없다.**

    python scripts/model_conf_compare.py --dump <pulled>/parity \\
        --model models/0824/bammasil_det_c4e_s3_11n_640/bammasil_det_c4e_s3_11n_640.onnx \\
        --model models/0826/.../bammasil_det_c4e_s3_11n_640-INT8_generic.onnx

## 🔴 `detect_parity.py`와 무엇이 다른가 — 방어선의 방향이 반대다

`detect_parity.py`는 **폰↔PC 이식**을 묻는다. 그래서 폰이 연 모델과 PC가 여는 모델이
**다르면 죽는다**(`_check_model`) — 다른 모델 둘을 대조하면 "이식 결함"과 "모델 차이"가
섞이기 때문이다.

이 스크립트는 **모델↔모델**을 묻는다. 그래서 모델이 다른 것이 **전제**다. 대신 반대편을
막는다: **같은 파일을 두 번 주면 경고한다**(비교할 것이 없다). 두 스크립트를 한 파일에
합치지 않은 이유가 이것이다 — 한쪽의 방어선이 다른 쪽에서는 결함이 된다.

## 🔴 이것이 답하는 질문과 답하지 못하는 질문

답한다: **같은 입력에서 모델 둘의 `max_conf`·`boxes_out`·`boxes_pre_nms`가 얼마나 다른가.**
양자화가 신뢰도를 깎았는지를 **장면 변수 없이** 본다.

답하지 못한다:

- **어느 모델이 옳은가.** 정답 라벨이 없다. 박스가 늘었다고 좋아진 것도, 줄었다고 나빠진
  것도 아니다. mAP·recall 판정에는 라벨이 필요하다(모델링 담당 몫).
- **탐지가 왜 적은가.** 이 덤프가 한 장면이면 그 장면의 답만 나온다. "실내라서"인지
  "모델이라서"인지는 **장면이 다른 덤프가 둘 이상 있어야** 갈린다.
- **폰에서의 속도.** 여기 지연은 PC x86 CPU EP의 것이고 폰 ARM의 배율과 다르다.
  🔴 **속도는 실기기로만 말한다** — 이 스크립트는 ms를 내지 않는다.
- **폰이 같은 답을 내는가.** 그건 `detect_parity.py`다. 여기 값은 전부 **PC ORT**의 것이다.

## 🔴 판정선이 없는 스크립트다

`lib/targets.py`의 판정선은 **프레임타임의 것**이고 여기와 무관하다. `meets_*_target`을
내지 않고 **종료 코드는 어떤 차이에서도 0**이다 — 종료 코드가 흔들리면 그것이 곧
"이 모델이 합격"이라는 판정이 된다. 0이 아닌 코드는 **대조 자체가 성립하지 않을 때**만 나온다.
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

STAGE = "model_conf_compare"


def _model_io(sess) -> dict:
    return {
        "input_name": sess.get_inputs()[0].name,
        "input_shape": list(sess.get_inputs()[0].shape),
        "output_name": sess.get_outputs()[0].name,
        "output_shape": list(sess.get_outputs()[0].shape),
    }


def open_models(paths_in: list[Path], warnings: list[str]) -> list[dict]:
    """모델마다 세션을 연다. 🔴 **sha256을 기록한다** — 어느 파일로 잰 숫자인지가 결과의 일부다."""
    ort = dp.ort
    opened = []
    for p in paths_in:
        dp._require(p.is_file(), f"모델 파일이 없다: {p}")
        sess = ort.InferenceSession(str(p), providers=[dp.PC_PROVIDER])
        resolved = list(sess.get_providers())
        if dp.PC_PROVIDER not in resolved:
            warnings.append(
                f"{p.name}: PC 세션이 {dp.PC_PROVIDER}로 열리지 않았다(실제 {resolved}) — "
                f"모델 간 차이가 EP 차이와 섞인다"
            )
        opened.append({
            "path": str(p.resolve()),
            "name": p.name,
            "sha256": dp.sha256_file(p),
            "io": _model_io(sess),
            "_sess": sess,
        })

    # 🔴 **같은 파일을 두 번 주면 비교가 아니다.** detect_parity의 방어선과 반대 방향이다.
    by_sha: dict[str, list[str]] = {}
    for m in opened:
        by_sha.setdefault(m["sha256"], []).append(m["name"])
    for sha, names in by_sha.items():
        if len(names) > 1:
            warnings.append(
                f"🔴 **같은 모델을 {len(names)}번 줬다**({', '.join(names)}, sha={sha[:12]}) — "
                f"아래 차이는 전부 0이 나온다. 그것은 '두 모델이 같다'가 아니라 "
                f"**한 모델을 자기와 비교했다**는 뜻이다"
            )

    # 🔴 클래스 수(출력 채널)가 다르면 대조가 성립하지 않는다 — max_conf의 정의가 달라진다.
    ch = {m["name"]: m["io"]["output_shape"][1] for m in opened
          if len(m["io"]["output_shape"]) >= 2}
    concrete = {n: c for n, c in ch.items() if isinstance(c, int)}
    if len(set(concrete.values())) > 1:
        raise dp.ParityError(
            f"모델들의 출력 채널이 다르다({concrete}) — 채널은 4+nc이므로 클래스 수가 "
            f"다르다는 뜻이고, 그러면 `max_conf`가 서로 다른 것을 재는 값이 된다. "
            f"대조하지 않는다"
        )
    return opened


def run_sample(sess, io: dict, phone_in, box, conf_thr: float, iou_thr: float,
               classes: dict, rot) -> dict:
    """입력 텐서 하나를 한 모델에 먹이고 후처리까지 간다. **후처리는 `detect_parity`의 것을 쓴다** —
    두 번 구현하면 그 둘이 갈리는 날 어느 쪽이 맞는지 말할 수 없다."""
    out = sess.run([io["output_name"]], {io["input_name"]: phone_in})[0]
    out2d = dp._decode_output(dp.np.asarray(out))
    boxes, pre_nms, max_conf = dp.postprocess(out2d, box, conf_thr, iou_thr, classes, rot)
    per_class: dict[str, int] = {}
    for b in boxes:
        key = str(b.get("cls_name") or b.get("cls") or "?")
        per_class[key] = per_class.get(key, 0) + 1
    return {
        "boxes_out": len(boxes),
        "boxes_pre_nms": int(pre_nms),
        "max_conf": float(max_conf),
        "by_class": per_class,
    }


def compare_dump(dump: dp.Dump, models: list[dict], warnings: list[str]) -> dict:
    m = dump.manifest
    class_block = m.get("classes") or {}
    classes = class_block.get("names") or {}
    if not classes:
        warnings.append(
            f"{dump.label}: 매니페스트 classes.names가 비어 있다 — 박스가 "
            f"`class_<인덱스>`로 떨어진다. 개수 비교는 성립하지만 클래스 구성은 못 읽는다"
        )

    # 🔴 **매니페스트의 클래스는 폰이 연 모델의 것이다** — 지금 대조하는 모델의 것이 아니다.
    #    옛 덤프(2클래스 시절)에 3클래스 모델을 먹이면 인덱스 2가 조용히 `class_2`로 떨어져
    #    "이름 없는 클래스가 나왔다"처럼 보인다. 그것은 모델 결함이 아니라 이름표 부재다.
    #    ⚠ 개수 비교(`boxes_out`·`max_conf`)는 이름과 무관하므로 **죽이지 않고 경고한다.**
    if classes:
        model_ncs = {m["name"]: m["io"]["output_shape"][1] - dp.BOX_CHANNELS
                     for m in models
                     if len(m["io"]["output_shape"]) >= 2
                     and isinstance(m["io"]["output_shape"][1], int)}
        odd = {n: nc for n, nc in model_ncs.items() if nc != len(classes)}
        if odd:
            warnings.append(
                f"{dump.label}: 🔴 **매니페스트의 클래스 수({len(classes)})와 모델의 클래스 "
                f"수가 다르다**({odd}). 매니페스트는 **폰이 그때 연 모델**의 이름표라 지금 "
                f"대조하는 모델의 것이 아니다 — 이름 없는 인덱스는 `class_<n>`으로 떨어진다. "
                f"⚠ `boxes_out`·`max_conf` 비교는 이름과 무관하므로 **그대로 유효하다**; "
                f"`by_class`만 믿지 말 것"
            )

    thresholds = m.get("thresholds") or {}
    conf_thr = thresholds.get("conf")
    iou_thr = thresholds.get("iou")
    dp._require(
        isinstance(conf_thr, (int, float)) and isinstance(iou_thr, (int, float)),
        f"{dump.root}: thresholds.conf/iou를 숫자로 읽지 못했다 "
        f"(conf={conf_thr!r} iou={iou_thr!r}). 🔴 0으로 뭉개면 전 앵커가 통과해 "
        f"두 모델이 나란히 다른 것을 재게 된다 — 시작하지 않는다",
    )

    # 🔴 **폰이 실제로 쓴 임계로 잰다.** 여기서 다른 값을 쓰면 `boxes_out`이 앱의 것과
    #    비교 불가능해지고, 이슈 61이 묻는 "앱에서 안 잡혔다"와 연결이 끊긴다.
    rot = dp.resolve_rotation(dump, warnings)

    ts = [s.get("t_recv_ns") for s in (m.get("samples") or []) if s.get("t_recv_ns") is not None]
    span = (max(ts) - min(ts)) / 1e9 if len(ts) >= 2 else 0.0
    if len(ts) >= 2 and span < dp.SAMPLE_SPAN_WARN_SEC:
        warnings.append(
            f"{dump.label}: 🔴 **샘플 {len(ts)}개가 {span:.3f}초 안에 몰려 있다** — 사실상 "
            f"한 장면이다. 모델 간 차이는 이 장면 하나에서의 차이이고, "
            f"**'모든 장면에서 그렇다'로 읽으면 안 된다**"
        )

    rows = []
    for s in m["samples"]:
        idx = s.get("index")
        where = f"{dump.label} sample {idx}"
        inp = s["input"]
        dp._require(
            inp.get("layout") == "NCHW" and inp.get("dtype") == "float32",
            f"{where}: input.layout/dtype이 NCHW/float32가 아니다 "
            f"({inp.get('layout')!r}/{inp.get('dtype')!r}) — 레이아웃을 지어내면 "
            f"채널이 섞인 채로 그럴듯한 차이가 나온다 (규약 §2·§3)",
        )
        phone_in = dp._read_f32(dump.root / inp["file"], inp["shape"], where)

        # 🔴 모델 그래프와 입력 shape을 대조한다. 원소 수가 같아도 축 순서가 다르면 다른 텐서다.
        for mo in models:
            if not dp._shape_matches(mo["io"]["input_shape"], list(phone_in.shape)):
                raise dp.ParityError(
                    f"{where}: 덤프 입력 shape {list(phone_in.shape)}이 "
                    f"{mo['name']}의 그래프 입력 {mo['io']['input_shape']}과 다르다 — "
                    f"그대로 먹이면 그럴듯한 쓰레기가 나온다"
                )

        box = dp.letterbox(rot.rot_w, rot.rot_h,
                           int(phone_in.shape[-1]), int(phone_in.shape[-2]))

        per_model = {}
        for mo in models:
            per_model[mo["name"]] = run_sample(
                mo["_sess"], mo["io"], phone_in, box,
                float(conf_thr), float(iou_thr), classes, rot,
            )
        rows.append({"index": idx, "t_recv_ns": s.get("t_recv_ns"), "models": per_model})

    return {
        "dump_label": dump.label,
        "dump_root": str(dump.root),
        "format": dump.format_id,
        "samples": len(rows),
        "span_sec": round(span, 3),
        "thresholds": {"conf": float(conf_thr), "iou": float(iou_thr)},
        "rotation_degrees_effective": rot.as_dict().get("degrees_effective"),
        "rows": rows,
        "per_model": _summarize(rows, models),
        "pairwise": _pairwise(rows, models),
    }


def _summarize(rows: list[dict], models: list[dict]) -> dict:
    np = dp.np
    out = {}
    for mo in models:
        name = mo["name"]
        confs = np.asarray([r["models"][name]["max_conf"] for r in rows], dtype=np.float64)
        outs = np.asarray([r["models"][name]["boxes_out"] for r in rows], dtype=np.float64)
        pres = np.asarray([r["models"][name]["boxes_pre_nms"] for r in rows], dtype=np.float64)
        out[name] = {
            "sha256": mo["sha256"],
            "max_conf": dp._np_summarize(confs),
            "boxes_out": dp._np_summarize(outs),
            "boxes_pre_nms": dp._np_summarize(pres),
            # 🔴 앱이 보는 것과 같은 뜻의 값 — "박스가 하나라도 나온 샘플의 비율".
            "samples_with_boxes": int((outs > 0).sum()),
            "samples": len(rows),
        }
    return out


def _pairwise(rows: list[dict], models: list[dict]) -> list[dict]:
    """🔴 **같은 샘플 안에서만 뺀다.** 모델별 분포끼리 빼면 장면 구성이 섞인다."""
    np = dp.np
    pairs = []
    for i, a in enumerate(models):
        for b in models[i + 1:]:
            an, bn = a["name"], b["name"]
            d_conf = np.asarray(
                [r["models"][bn]["max_conf"] - r["models"][an]["max_conf"] for r in rows],
                dtype=np.float64,
            )
            d_out = np.asarray(
                [r["models"][bn]["boxes_out"] - r["models"][an]["boxes_out"] for r in rows],
                dtype=np.float64,
            )
            pairs.append({
                "base": an,
                "other": bn,
                "note": f"값은 **{bn} − {an}**이다. 음수면 {bn}이 낮다",
                "d_max_conf": dp._np_summarize(d_conf),
                "d_boxes_out": dp._np_summarize(d_out),
                "abs_max_conf_max": float(np.abs(d_conf).max()) if len(d_conf) else 0.0,
                "samples_boxes_differ": int((d_out != 0).sum()),
                "samples": len(rows),
            })
    return pairs


def render(results: list[dict], models: list[dict], warnings: list[str]) -> str:
    lines = ["", "=== ③ 두 모델 대조 (같은 입력 텐서 · PC CPU EP) ==="]
    for mo in models:
        lines.append(f"  모델 {mo['name']}  sha={mo['sha256'][:12]}")
    for res in results:
        lines.append("")
        lines.append(f"[{res['dump_label']}] 샘플 {res['samples']}개 · "
                     f"걸친 시간 {res['span_sec']}초 · conf={res['thresholds']['conf']} "
                     f"iou={res['thresholds']['iou']}")
        for name, s in res["per_model"].items():
            lines.append(
                f"  {name:<52} max_conf p50={s['max_conf']['p50']:.4f} "
                f"max={s['max_conf']['max']:.4f} | boxes_out p50={s['boxes_out']['p50']:.1f} "
                f"| 박스 나온 샘플 {s['samples_with_boxes']}/{s['samples']}"
            )
        for p in res["pairwise"]:
            lines.append(
                f"  Δ({p['other']} − {p['base']})  max_conf p50={p['d_max_conf']['p50']:+.4f} "
                f"| |Δ|max={p['abs_max_conf_max']:.4f} "
                f"| boxes_out이 다른 샘플 {p['samples_boxes_differ']}/{p['samples']}"
            )
    if warnings:
        lines.append("")
        lines.append("경고:")
        for w in warnings:
            lines.append(f"  · {w}")
    lines.append("")
    lines.append("🔴 정답 라벨이 없다 — 어느 모델이 옳은지는 여기서 나오지 않는다.")
    lines.append("🔴 ms를 내지 않는다 — 속도는 실기기로만 말한다.")
    return "\n".join(lines)


def main() -> int:
    parser = common_argparser()
    parser.add_argument("--dump", action="append", default=[], required=True,
                        help="폰 덤프의 parity/ 디렉토리 (여러 번 줄 수 있다)")
    parser.add_argument("--model", action="append", default=[], required=True,
                        help="비교할 .onnx (🔴 **2개 이상**. 순서가 base→other다)")
    parser.add_argument("--label", default="", help="이 대조에 붙일 메모")
    args = parser.parse_args()

    paths = init_run(STAGE, __file__, args)
    logging.basicConfig(level=logging.INFO, format="%(message)s")

    if len(args.model) < 2:
        raise dp.ParityError(
            f"--model이 {len(args.model)}개다 — 이 스크립트는 **모델을 서로** 비교한다. "
            f"둘 이상 줄 것 (한 모델의 값만 보려면 scripts/detect_parity.py가 그 일을 한다)"
        )

    dp._require_deps()

    warnings: list[str] = []
    models = open_models([Path(p) for p in args.model], warnings)
    results = [compare_dump(dp.load_dump(Path(d)), models, warnings) for d in args.dump]

    for line in render(results, models, warnings).splitlines():
        LOG.info(line)

    if paths.outputs_enabled:
        out = paths.out_dir / "model_conf_compare.json"
        payload = {
            "stage": STAGE,
            "label": args.label,
            "models": [{k: v for k, v in m.items() if not k.startswith("_")} for m in models],
            "pc_provider": dp.PC_PROVIDER,
            "onnxruntime": dp.ort.__version__,
            "dumps": results,
            "warnings": warnings,
            "verdict": None,
            "notes": {
                "no_ground_truth": (
                    "🔴 **정답 라벨이 없다.** `max_conf`가 낮다는 것은 '이 모델이 이 입력에서 "
                    "확신하지 못했다'이지 '틀렸다'가 아니다. 어느 모델이 옳은지는 mAP·recall이 "
                    "답하고 그것은 라벨이 있어야 한다."
                ),
                "same_input_is_the_point": (
                    "🟢 두 모델이 **같은 텐서**를 받았다 — 장면·조명·초점·발열이 변수가 아니다. "
                    "그래서 Δ는 **모델 차이**로 읽어도 된다. 다만 그 텐서들이 한 장면이면 "
                    "**그 장면에서의 모델 차이**다."
                ),
                "pc_not_phone": (
                    "⚠ 전부 **PC ORT(x86 CPU EP)** 값이다. 폰이 같은 답을 내는지는 "
                    "scripts/detect_parity.py가 답한다."
                ),
                "no_ms": (
                    "🔴 **지연을 내지 않는다.** PC x86의 INT8 배율은 폰 ARM의 배율과 다르고, "
                    "여기 숫자로 폰을 판정하면 틀린다."
                ),
                "thresholds_from_manifest": (
                    "임계는 **폰이 실제로 쓴 값**을 매니페스트에서 읽었다 — 여기서 다른 값을 "
                    "쓰면 boxes_out이 앱의 것과 비교 불가능해진다."
                ),
                "no_threshold_line": (
                    "⚠ **판정선이 아니다.** 'Δmax_conf 얼마까지 허용'은 이 저장소에 없다."
                ),
            },
        }
        out.write_text(json.dumps(payload, ensure_ascii=False, indent=2), encoding="utf-8")
        LOG.info(f"대조 저장: {out}")

    # 🔴 차이가 얼마든 0. 종료 코드로 합격을 말하지 않는다.
    return 0


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except dp.ParityError as exc:
        print(f"모델 대조 실패: {exc}", file=sys.stderr)
        raise SystemExit(2) from exc
