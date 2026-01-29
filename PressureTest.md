# PressureTest
### 대규모 트래픽 하에서 “버릴 요청을 고르는 서버”

---

## 1. 프로젝트 개요

PressureTest는 대규모 트래픽 상황에서  
**모든 요청을 처리하려 하지 않고,  
시스템을 살리기 위해 요청을 선별적으로 폐기하는 서버**를 구현하는 프로젝트이다.

본 과제의 핵심은 TPS가 아니라  
**“어떤 요청을 먼저 포기해야 하는가”** 이다.

---

## 2. 문제 정의

전통적인 트래픽 대응:
- 캐시 추가
- 서버 증설
- 오토스케일링

하지만 실제 장애 상황에서는:
- 리소스는 이미 고갈
- 증설은 늦음
- 모든 요청을 처리하면 전체 장애 발생

PressureTest는 다음을 목표로 한다:

> “부분 실패로 전체 실패를 막는다.”

---

## 3. 핵심 개념

### Request Triage Engine

각 요청은 도착 시점에 다음 기준으로 점수화된다:

- 사용자 중요도
- 요청 비용 (CPU / DB / IO)
- 현재 시스템 부하
- 과거 처리 성공률

점수에 따라 요청은 다음 중 하나로 분류된다:
- 즉시 처리
- 지연 처리
- Degraded Response
- Fail Fast

---

## 4. 시스템 구조

1. Global Request Queue
2. Load Monitor
3. Score Calculator
4. Triage Dispatcher
5. Response Policy 적용

---

## 5. 필수 구현 요구사항

### 서버
- Spring WebFlux 기반
- Non-blocking 처리
- 요청 단위 Queue 관리

### Load 측정
- CPU 사용률
- Queue Depth
- 평균 응답 시간

### Rate Limiting
- 고정값 금지
- 시스템 상태 기반 가변 조절

---

## 6. Degraded Response

정상 응답이 불가능한 경우:
- 빈 데이터
- 캐시 데이터
- 요약 정보

단, **HTTP 200을 유지할 것** (명시적 실패 제외)

---

## 7. 제한 사항

- 단순 TPS 증가 목적 ❌
- 무조건적인 429 응답 ❌
- 외부 Load Balancer 의존 ❌

---

## 8. 테스트 시나리오

- 점진적 트래픽 증가
- Burst Traffic
- 특정 엔드포인트 집중 공격
- 정상 사용자 보호 여부 확인

---

## 9. 평가 기준

- 요청 선별 기준의 합리성
- 장애 상황 가정의 현실성
- Degraded 전략의 설계 완성도
- 코드의 관측 가능성 (Metrics)

---

## 10. 보너스 과제

- Chaos Test 자동화
- 실시간 Triage 시각화
- 요청 생존율 통계

---

## 11. 결과물

- 소스 코드
- README
- 부하 테스트 스크립트
- 트래픽 분석 리포트
