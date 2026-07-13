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

## 테스트 (24건)

`TriageTests` (8건):
- PREMIUM > FREE 점수, 부하 증가 시 점수 감소, budget 미만 무조건 admit, 과부하 시 FREE+고비용 우선 shed, 경계 점수 degraded, 카운터 추적, 동시성 시 in_flight ≤ admitted, release 시 in_flight 감소

`HttpFlowTests` (4건):
- 빈 userId 400, 음수 cost 400, 부정 tier 400, admitted 응답에 op digest 포함

`DynamicThresholdTests` (8건):
- base 시작값, 잘못된 bound 거부, 지속적 shed → admit 임계값 상승, allow 회복 시 하락, degrade < admit 항상 보장, override 우선, reset, extreme input clamp

`AsyncDispatcherTests` (4건):
- 단일 요청 dispatch + release, 과부하시 shed 결정은 enqueue되지 않음, 다수 동시 제출, queue/stat 노출

`mvn test` → 24/24 pass.

## 의도적으로 보류한 항목

- 카오스 테스트, 부하 시뮬레이터
- 시각화 대시보드 (Grafana)
- 다중 노드 공유 budget
- Spring WebFlux 기반 non-blocking 처리 — 초기 명세(`PressureTest.md`)는 WebFlux를 요구했으나, 실제 구현은 `spring-boot-starter-web` 기반 blocking Servlet MVC다.

## 비동기 디스패처

`AsyncDispatcher`는 동기 triage 결정을 그대로 활용하면서, 실제 work step을 worker pool로 위임한다. 큐는 실행 시 FIFO `LinkedBlockingQueue` 기반이며 우선순위 없이 도착 순서대로 처리된다 (capacity=`pressure.async.queue-capacity`; 코드에는 미사용 `PriorityBlockingQueue` 준비 로직이 남아있지만 실제 `ThreadPoolExecutor`는 이를 쓰지 않는다). 풀은 fixed-size (`pressure.async.workers`). 큐가 꽉 차면 reservation을 release하고 즉시 shed로 응답. `/admin/async/work`로 트리거, `/admin/async`로 상태 조회.

## 동적 임계값

`DynamicThresholdProvider`는 shed 비율의 EMA를 추적하여 admit/degrade 임계값을 자동 조정한다. shed가 늘면 임계값을 위로(`admit_max`까지) 밀어 borderline 트래픽을 더 적극적으로 차단. 임계값은 `[admitMin, admitMax]` / `[degradeMin, degradeMax]`로 clamp되며 항상 `degrade < admit`. `/admin/threshold/override?admit=...&degrade=...`로 수동 잠금 가능.
