# 기준 측정값 (baselines)

`outputs/`는 git이 추적하지 않는다. 그래서 **승격하지 않은 숫자는 그 머신에만 남는다** —
노트북이 바뀌거나 팀원이 확인하려 하면 근거가 없다.

여기엔 **기준이 된 측정만** 올린다. 그러면 `baseline_diff.py`가 세션·기기를 넘어서 동작한다.

## 승격 기준 (셋 중 하나면)

- `FRAME_BUDGET.md`의 칸을 채운 측정
- 팀에 숫자로 보고한 측정
- 앞으로 비교 기준으로 쓸 측정

나머지 수십 개 런은 `outputs/`에 두고 승격하지 않는다.

## 방법

```bash
cp outputs/poc_baseline/<run_ts>/summary.json docs/baselines/<날짜>_<라벨>.json
```

`summary.json`을 **그대로** 복사한다. 수 KB이고, git commit·기기 메타·판정·경고가 이미 안에 있다.
**손으로 만들거나 편집하지 않는다** — 하네스를 거치지 않은 숫자는 스탬프가 없어 비교 불가다.

명명: `<YYYYMMDD>_<무엇을_잰_것인지>.json` — 예 `20260731_poc_empty_a34.json`

## 비교

```bash
python scripts/baseline_diff.py \
  --baseline docs/baselines/20260731_poc_empty_a34.json \
  --current  outputs/poc_baseline/<run_ts>/summary.json
```

⚠️ **조건이 다르면 비교가 아니라 착시다.** 같은 기기·같은 빌드 타입·같은 파이프라인 구성이어야
한다. `baseline_diff.py`가 `CONDITION_KEYS`로 검사해 다르면 경고를 먼저 낸다.

## 지금 여기 있는 것

없다. **실측 0건.** 첫 파일은 빈 파이프라인 PoC의 베이스라인이 될 것이다.

> 그 숫자를 팀에 공유할 때는 단서를 반드시 붙인다 — 처리가 없는 파이프라인의 프레임 간격은
> **연산 비용이 아니라 카메라 공급 속도**다. 여유의 상한이 아니라 **바닥값**이고,
> 여기서부터 ①②③④ 비용이 더해진다.
