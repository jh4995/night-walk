# `c4e_s3_11n` — INT8 양자화 산출물

> 만든 날 **2026-08-26** · 원본 `bammasil_det_c4e_s3_11n_640.onnx` (FP32 · 10.61 MB)
> 🔴 **이것은 배포 확정판이 아니다** — 캘리브 표본이 159프레임
> (한 장소·한 밤)이고 mAP·recall 정식 판정을 하지 못했다. 아래 5장.

## 1. 무엇이 들어 있나

이 디렉토리의 **모든 파일**이다. ★ 표시만 앱에 넘길 배포 후보이고 나머지는 중간물·기록이다.

| 파일 | 무엇 | 크기 |
|---|---|---|
| `bammasil_det_c4e_s3_11n_640-INT8_calib_manifest.txt` | 캘리브레이션에 **실제로 쓴** 파일·프레임 번호 159줄 (재현용) | 9.1 KB |
| `bammasil_det_c4e_s3_11n_640-INT8_fp32_realnight.json` | `eval_real_night.py` 산출 — **fp32** 판의 야간 음성·양성 장면 대조 원자료 | 3.7 KB |
| `bammasil_det_c4e_s3_11n_640-INT8_generic.onnx` | ★ **배포 후보 — `generic` 프리셋** · QDQ · per-channel · 가중치 QInt8 / 활성 QUInt8 · 대상 NNAPI · Core ML · ORT CPU EP | 3.15 MB |
| `bammasil_det_c4e_s3_11n_640-INT8_generic_realnight.json` | `eval_real_night.py` 산출 — **generic** 판의 야간 음성·양성 장면 대조 원자료 | 3.7 KB |
| `bammasil_det_c4e_s3_11n_640-INT8_metadata.json` | 기계 판독용 — 설정 · SHA256 · 그래프 입출력 · 캘리브 표본 · FP32 대비 비교 결과 | 11.2 KB |
| `bammasil_det_c4e_s3_11n_640-INT8_opset13_fp32.onnx` | 중간물 — 원본을 opset 12→13 변환 + `quant_pre_process` 한 것. **FP32 그대로**이고 양자화의 **입력**이다. 배포물이 아니며 지워도 재실행하면 다시 생긴다 | 10.59 MB |
| `bammasil_det_c4e_s3_11n_640-INT8_qnn_qdq.onnx` | ★ **배포 후보 — `qnn` 프리셋** · QDQ · per-tensor · 가중치 QUInt8 / 활성 QUInt8 · 대상 스냅드래곤 **QNN EP(HTP)** | 3.04 MB |
| `bammasil_det_c4e_s3_11n_640-INT8_qnn_qdq_realnight.json` | `eval_real_night.py` 산출 — **qnn_qdq** 판의 야간 음성·양성 장면 대조 원자료 | 3.7 KB |
| `bammasil_det_c4e_s3_11n_640-INT8_README.md` | 이 문서 | 6.1 KB |

### FP32 대비 정합 — 같은 이미지에 두 모델을 먹여 박스를 짝지은 결과

| 파일 | 프리셋 | 가중치 스케일 | 크기 | 검출수 불일치 | 좌표 최대오차(px) | conf 최대오차 |
|---|---|---|---|---|---|---|
| `bammasil_det_c4e_s3_11n_640-INT8_generic.onnx` | generic | per-channel | 3.15 MB | 1 | 44.2624 | 0.07724 |
| `bammasil_det_c4e_s3_11n_640-INT8_qnn_qdq.onnx` | qnn | per-tensor | 3.04 MB | 0 | 40.9115 | 0.06717 |

- **`generic`** — per-channel 가중치 / uint8 활성. NNAPI · Core ML · ORT CPU EP 대상
- **`qnn`** — per-tensor uint8. 스냅드래곤 **QNN EP(HTP)** 가 요구하는 형태


⚠️ **CPU 판·GPU 판이 아니다.** QDQ ONNX 는 런타임 중립이라 같은 파일을 여러 EP 가 읽는다.
두 판의 차이는 EP 계열이 요구하는 **양자화 형식**이다. GPU 는 INT8 의 짝이 아니며
(폰에 TensorRT 없음 → hardware_inference.md 1장), GPU delegate 를 쓸 거면 FP16 이다.

## 2. 입출력 계약 — 원본과 같다

```
images  : float32 [1, 3, 640, 640]   (NCHW · RGB · /255)
output0 : float32 [1, 7, 8400]
```

전처리·후처리·NMS·좌표 역변환은 **FP32 판과 완전히 동일**하다
(→ `outputs/export/c4e_s3_11n/README.md`). 앱이 고칠 것은 없다.

## 3. 어떻게 만들었나

```
opset 12 → 13 (version_converter)   per-channel QDQ 는 QuantizeLinear 의 axis 가 필요
  → quant_pre_process               shape inference + 그래프 정리
    → quantize_static (QDQ)         캘리브 159프레임 · **Conv 만**
      → metadata_props 복사          names·stride·task 유지
```

🔴 **`Conv` 만 양자화한 것은 취향이 아니라 필수다.** 전부 양자화하면 **검출이 0 이 된다** —
머리의 마지막 `Concat`(`/model.23/Concat_3`)이 박스 좌표(0~640)와 클래스 점수(0~1)를 한
텐서로 합치는데, 이걸 per-tensor uint8 로 재면 스케일이 640/255 ≈ 2.5 라 **점수가 전부
0 으로 반올림**된다. 7장 전부에서 박스가 사라지는 것을 실측했다. Conv 만 두면 연산량의
대부분은 INT8 로 가면서 머리의 산술은 float 로 남아 이 붕괴가 없다.

캘리브 소재는 **야간 실촬영** `data/test_real_data` 이고 전처리는 배포와 같은 자
(**square letterbox 640** · RGB · /255 · NCHW → STATUS 3장 함정 18)다.
실제로 쓴 파일·프레임 목록은 `bammasil_det_c4e_s3_11n_640-INT8_calib_manifest.txt` 에 있다.

재현:

```powershell
uv run python scripts/quantize_onnx.py --calibrate-method minmax
```

## 4. CPU EP 지연 — ⚠️ 참고치일 뿐이다

| 파일 | 1회 추론(ms) |
|---|---|
| `bammasil_det_c4e_s3_11n_640.onnx` (FP32) | 40.43 |
| `bammasil_det_c4e_s3_11n_640-INT8_generic.onnx` | 46.43 |
| `bammasil_det_c4e_s3_11n_640-INT8_qnn_qdq.onnx` | 61.07 |

🔴 **이 PC 는 15~25ms 를 판정할 수 없다** (프로세스 간 10~20% 변동 → STATUS 3장 함정 3).
게다가 데스크톱 x86 CPU EP 의 INT8 배율은 **폰 NPU 의 배율과 다르다.**
속도 판정은 `C11` 실기기 계측이다.

## 5. 🔴 아직 검증되지 않은 것

- **mAP·recall 을 재지 못했다** — held-out(NightOwls rec34)·`detect_v3` val 이 이 PC 에 없다.
  학습 PC 또는 `C5` 하네스(`scripts/eval_own_night.py`)로 판정할 것.
- **캘리브 표본이 얇다** — 159프레임이고 전부 **한 장소·한 밤·세로
  촬영**이다. square letterbox 의 회색 패딩이 좌우에만 생기는 쪽으로 치우쳐 있다.
  정식 캘리브는 `C2` 촬영분이나 학습 PC 의 `detect_v3` 로 다시 구울 것.
- **QNN 판은 실기기에서 그래프가 통째로 올라가는지 확인되지 않았다** — op 하나가 안 올라가면
  CPU fallback 이라 이득이 사라진다. `C10` 에서 확인할 것.
