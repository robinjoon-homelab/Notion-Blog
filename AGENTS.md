# AGENTS.md

## Mission

이 저장소는 Notion을 편집 도구로 사용하는 셀프 호스팅 블로그다. 현재 작업의 기준 설계는 `docs/kotlin-spring-architecture.md`다. 모든 에이전트는 작업 전에 해당 문서를 읽고, 설계와 충돌하는 구현을 임의로 추가하지 않는다.

## Required workflow

1. 변경할 동작과 성공 조건을 먼저 한 문장으로 적는다.
2. 해당 동작을 검증하는 실패 테스트를 먼저 작성한다.
3. 가능하면 실패를 실제로 실행해 확인한다.
4. 테스트를 통과시키는 최소 구현을 작성한다.
5. 관련 테스트와 전체 테스트를 실행한다.
6. 구조적 결정이 바뀌면 코드보다 `docs/kotlin-spring-architecture.md`를 먼저 갱신한다.

테스트 없이 production code부터 작성하지 않는다. 예외는 테스트 실행을 가능하게 하는 순수 build/config scaffold뿐이며, scaffold 자체는 context-load 또는 build smoke test로 즉시 검증한다.

## Technology constraints

- JDK 25 toolchain
- Kotlin + Spring Boot
- Spring MVC, not WebFlux
- Exposed JDBC DSL, not Exposed DAO or R2DBC
- PostgreSQL and Flyway
- Thymeleaf server-side rendering
- Gradle Kotlin DSL and Gradle Wrapper
- JUnit 5, AssertJ, MockK, Testcontainers PostgreSQL, MockWebServer
- single deployable Spring Boot application

정확한 라이브러리 버전은 호환성 테스트를 통과한 뒤 고정한다. 동적 버전과 snapshot 의존성을 사용하지 않는다.

## Architecture boundaries

- dependency direction: `adapter -> application -> domain`
- `domain`은 Kotlin/JDK 외 라이브러리에 의존하지 않는다.
- Spring annotation은 domain에 두지 않는다.
- Exposed `Table`, `ResultRow`, SQL expression은 persistence adapter 밖으로 노출하지 않는다.
- Notion API DTO는 Notion adapter 밖으로 노출하지 않는다.
- application service가 transaction 경계를 소유한다.
- 외부 HTTP 호출 중 DB transaction을 유지하지 않는다.
- controller와 scheduler는 orchestration을 application service에 위임한다.
- 실제 외부 경계가 아닌 한 인터페이스를 만들지 않는다.

## Kotlin conventions

- package base는 `xyz.robinjoon.notionblog`로 통일한다.
- constructor injection만 사용한다.
- field injection과 `lateinit` bean injection을 사용하지 않는다.
- 불변 `data class`와 `val`을 기본으로 한다.
- nullable 값은 의미가 있을 때만 사용하며 `!!`을 사용하지 않는다.
- 시간은 `Instant`와 주입된 `Clock`으로 다룬다.
- money가 없으므로 범용 value-object 프레임워크를 만들지 않는다.
- 예외는 경계별 의미가 있는 소수의 sealed/domain exception으로 제한한다.
- wildcard import를 사용하지 않는다.
- 한 파일에 관련 없는 top-level 선언을 모으지 않는다.

## Spring conventions

- 설정은 `@ConfigurationProperties`로 타입 안전하게 바인딩한다.
- REST/HTML controller에 비즈니스 규칙을 넣지 않는다.
- `@Transactional`은 application service의 public method에 둔다.
- 읽기 전용 유스케이스는 `@Transactional(readOnly = true)`를 사용한다.
- scheduled method는 due 대상 조회와 service 호출만 한다.
- Notion 장애를 Actuator readiness 실패로 연결하지 않는다.

## Exposed and database conventions

- table 이름과 column 이름은 `snake_case`다.
- enum은 의미가 명확한 대문자 문자열로 저장한다.
- production schema는 Flyway만 변경한다.
- 모든 migration은 append-only다. 이미 적용된 migration을 수정하지 않는다.
- repository는 domain model 또는 명시적인 projection을 반환한다.
- PostgreSQL 동작 테스트에 H2를 사용하지 않는다.
- 소스 바인딩·공개 범위의 DB 제약과 원자적 상태 전이는 Testcontainers로 검증한다.
- JSONB에는 Notion 원본 응답이 아니라 정규화한 snapshot을 저장한다.

## Testing conventions

테스트는 핵심과 경계를 우선한다.

### Must test

- domain invariant와 상태 전이
- `/`와 `/posts/{postId}` 경로, 내부 ID 파싱과 공개 범위 규칙
- 공개 상태 취소
- Notion pagination, error classification, mapping
- Flyway와 Exposed mapping
- transaction rollback과 DB constraints
- HTTP status, visibility, 내부 링크 해석
- renderer의 지원 블록과 안전한 fallback

### Avoid

- 단순 getter/setter 테스트
- 프레임워크 자체 동작 재검증
- private method 직접 테스트
- interaction-only mock 테스트 남발
- implementation line과 1:1로 결합된 brittle test

테스트 이름은 동작과 결과를 표현한다. Arrange/Act/Assert가 길어지면 fixture builder를 사용하되 범용 테스트 프레임워크는 만들지 않는다.

## Commands

기본 검증 명령은 다음을 사용한다.

```bash
./gradlew test
./gradlew build
```

DB 통합 테스트는 Docker가 필요할 수 있다. Docker를 사용할 수 없는 환경에서는 실패 원인을 숨기지 말고 단위/경계 테스트 결과와 분리해 보고한다.

## Repository and runtime artifact boundaries

이 저장소가 소유하는 산출물은 다음으로 한정한다.

1. 애플리케이션 소스와 테스트
2. Gradle/Flyway/런타임 설정
3. Dockerfile
4. GitHub Actions CI

- Helm chart, Kubernetes manifest, GitOps 및 배포 하네스 설정을 이 저장소에 추가하지 않는다.
- runtime artifact는 단일 Spring Boot container image다.
- 별도 worker image와 migration image를 추가하지 않는다.
- 외부 하네스가 사용할 수 있도록 liveness/readiness Actuator endpoint를 유지한다.
- secret 값을 repository, image, log, test fixture에 넣지 않는다.
- replica, Service/Ingress, probe 연결, Secret/ConfigMap 주입과 보안 정책은 별도 하네스 저장소가 소유한다.

## Scope control

- 현재 기능에 필요하지 않은 범용 프레임워크나 확장 지점을 만들지 않는다.
- 인접한 기존 코드의 스타일 정리만을 위한 변경을 하지 않는다.
- 교체가 끝난 구 스택 파일은 신규 테스트가 대체 동작을 검증한 뒤 제거한다.
- 사용자 변경이나 무관한 dirty worktree 파일을 덮어쓰지 않는다.

## Agent collaboration

- 시작할 때 담당 파일과 경계를 명시한다.
- 다른 에이전트가 담당하는 파일을 수정하지 않는다.
- 공용 build 파일 변경이 필요하면 주 에이전트에게 요청한다.
- 완료 보고에는 작성한 테스트, 실행 명령, 결과, 남은 위험을 포함한다.
- 범위를 벗어난 문제는 고치지 말고 관찰 사실만 전달한다.
