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
mvn test                    # 12건 테스트
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

## 테스트 (12건)

`TriageTests` (8건):
- PREMIUM > FREE 점수, 부하 증가 시 점수 감소, budget 미만 무조건 admit, 과부하 시 FREE+고비용 우선 shed, 경계 점수 degraded, 카운터 추적, 동시성 시 in_flight ≤ admitted, release 시 in_flight 감소

`HttpFlowTests` (4건):
- 빈 userId 400, 음수 cost 400, 부정 tier 400, admitted 응답에 op digest 포함

`mvn test` → 12/12 pass.

## 의도적으로 보류한 항목

- 우선순위 큐 기반 비동기 디스패처 (현 MVP는 동기 budget gate)
- 시간 윈도우 기반 동적 임계값 학습
- 카오스 테스트, 부하 시뮬레이터
- 시각화 대시보드 (Grafana)
- 다중 노드 공유 budget
