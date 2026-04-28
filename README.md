# PressureTest

과부하 시 시스템 생존을 위해 요청을 선별 폐기(triage)하는 Spring Boot 3.3 게이트웨이.

## 핵심 모델

**점수 공식**

```
score = tier_weight * tier_value
      − cost_penalty * cost_units
      − load_penalty * (in_flight / budget)
```

`tier_weight=30`, `cost_penalty=1`, `load_penalty=50`, `budget=8` (기본값, application.yml).

`tier_value`: PREMIUM=3, STANDARD=2, FREE=1.

**판정 규칙** (`TriageEngine`)

1. `in_flight < budget` → **admit** (모두 통과)
2. budget 초과:
   - `score >= admit_threshold(=30)` → **admit**
   - `score >= degrade_threshold(=20)` → **degraded** (최소 응답 반환)
   - 그 외 → **shed (503)**

## 실행

```bash
mvn test                    # 7건 테스트
mvn spring-boot:run         # 8120 포트
```

## 호출 예시

```bash
# 정상 요청
curl -X POST localhost:8120/api/work \
  -H 'content-type: application/json' \
  -d '{"userId":"u1","tier":"PREMIUM","costUnits":1,"operation":"checkout"}'
# → {"admitted":true,"score":89.0,"degraded":false,"result":"ok","operation":"checkout"}

# 부하 상태에서 FREE 고비용
curl -X POST localhost:8120/api/work \
  -H 'content-type: application/json' \
  -d '{"userId":"u9","tier":"FREE","costUnits":500,"operation":"export"}'
# → 503 {"admitted":false,"score":-520.0,"reason":"...","retryAfterMs":200}

# 부하 모니터
curl localhost:8120/api/load
```

## 테스트 (7건)

| 케이스 | 검증 |
|---|---|
| `score_premium_higher_than_free_at_same_load` | 같은 부하에서 PREMIUM > FREE |
| `score_decreases_with_load` | in_flight 증가 시 점수 감소 |
| `below_budget_always_admits` | budget 미만이면 무조건 admit |
| `overload_sheds_low_tier_high_cost_first` | 과부하 시 FREE+고비용 먼저 shed, PREMIUM 통과 |
| `overload_marks_borderline_as_degraded` | 임계값 사이는 degraded 응답 |
| `monitor_counters_track_decisions` | shed 카운터 증가 추적 |
| `release_decrements_in_flight` | release 시 in_flight 감소 |

`mvn test` → 7/7 pass.

## 의도적으로 보류한 항목

- 우선순위 큐 기반 비동기 디스패처 (현 MVP는 동기 budget gate)
- 시간 윈도우 기반 동적 임계값 학습
- 카오스 테스트, 부하 시뮬레이터
- 시각화 대시보드 (Grafana)
- 다중 노드 공유 budget
