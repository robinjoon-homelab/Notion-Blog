# AGENTS.md

## Mission

이 저장소는 Notion을 편집 도구로 사용하는 셀프 호스팅 블로그다. 현재 작업의 기준 설계는 `docs/kotlin-spring-architecture.md`다. 모든 에이전트는 작업 전에 해당 문서를 읽고, 설계와 충돌하는 구현을 임의로 추가하지 않는다.

공통 개발 기준은 `kotlin-springboot-exposed-template` 원격 master를 2026-10-08에 재확인한 `ddfbfef26e7af1ab3ee3fc4f00bc137fdb0a4a87`이다. 개발·품질 규칙은 `docs/development.md`와 `docs/code-quality.md`를 함께 읽는다. 과거 1826e36의 트랜잭션 정책을 적용하지 않는다.

배포 자동화는 이후 2026-10-08의 공통 템플릿 로컬 변경본을 반영한다. 명시적 배포 이름·HTTP 404 최초 생성·기존 태그만 갱신·하네스 main 목표 태그 확인 계약은 `docs/deployment.md`를 따른다.

아키텍처 검사는 2026-10-09 공통 템플릿의 패키지 파서와 규칙을 사용한다. 계층 우선·기능 우선 구조를 함께 지원하며 공통 코드에 앱별 역할 분기를 추가하지 않는다. 블로그의 기술·업무 추가 검사는 별도 테스트로 유지한다.

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
- domain과 입력·출력 port는 Spring에 의존하지 않는다.
- application/service의 Spring 의존성은 `Transactional`, `Isolation`, `Propagation`만 허용한다. `@Service`·`@Component` 대신 config의 `@Bean`으로 조립한다.
- 외부 진입점은 입력 use case port를 호출하고, 외부 시스템은 출력 port로 접근한다.
- Exposed `Table`, `ResultRow`, SQL expression은 persistence adapter 밖으로 노출하지 않는다.
- Notion API DTO는 Notion adapter 밖으로 노출하지 않는다.
- application service가 transaction 경계를 소유한다.
- 외부 HTTP 호출 중 DB transaction을 유지하지 않는다.
- controller와 scheduler는 orchestration을 application service에 위임한다.
- 입력 use case와 외부 출력 경계에 계약을 두며 불필요한 내부 인터페이스는 만들지 않는다.
- 포트의 전체 역할 경로는 구현 패키지에 연속된 완전한 세그먼트로 나타나야 한다. 루트 공용 포트 외에는 기능 소유권도 일치해야 한다.
- 어댑터 격리는 기능 경로·입력/출력 방향·첫 그룹 이름으로 판단한다. 같은 그룹의 하위 클라이언트·매퍼·코덱은 함께 사용할 수 있다.

## Kotlin conventions

- package base는 `xyz.robinjoon.notionblog`로 통일한다.
- constructor injection만 사용한다.
- 운영 코드에서 field injection과 `lateinit`을 사용하지 않는다. 테스트의 `@Autowired`, `@Inject`, `@Resource` 프레임워크 주입 필드는 템플릿과 동일하게 허용한다.
- 불변 `data class`와 `val`을 기본으로 한다.
- nullable 값은 의미가 있을 때만 사용하며 `!!`을 사용하지 않는다.
- 시간은 `Instant`와 주입된 `Clock`으로 다룬다.
- money가 없으므로 범용 value-object 프레임워크를 만들지 않는다.
- 예외는 경계별 의미가 있는 소수의 sealed/domain exception으로 제한한다.
- wildcard import를 사용하지 않는다.
- 한 파일에 관련 없는 top-level 선언을 모으지 않는다.
- 운영 함수 30줄, 테스트·승인된 개별 DSL 함수 80줄, 일반 인자 4개·생성자 6개, 제어 흐름 중첩 2단계·scope 함수 1단계, 인지 복잡도 10을 검사한다. 정확한 계산과 개별 예외는 `docs/code-quality.md`를 따른다.
- baseline, 검사 비활성화, 파일·패키지 제외로 위반을 숨기지 않는다. 개별 예외에는 바로 앞 `quality-exception` 사유를 적고 검토한다.

## Spring conventions

- 설정은 `@ConfigurationProperties`로 타입 안전하게 바인딩한다.
- REST/HTML controller에 비즈니스 규칙을 넣지 않는다.
- `@Transactional`은 application service의 public method에 둔다.
- 읽기 전용 유스케이스는 `@Transactional(readOnly = true)`를 사용한다.
- Exposed Spring Boot 4 starter의 단일 transaction manager를 사용한다. repository의 `transaction {}`나 adapter의 트랜잭션 선언을 추가하지 않는다.
- 외부 HTTP orchestration에는 트랜잭션을 열지 않고 관리되는 읽기·쓰기 service 호출로 DB 경계를 분리한다.
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
./gradlew detektMain detektTest
./gradlew :quality-rules:test
./gradlew test
./gradlew build
```

`test`와 `build`는 ktlint 자동 수정·검사와 타입 분석 detekt를 실행한다. CI에서는 자동 수정이 남으면 실패시킨다. REST Docs 생성물은 직접 수정하지 않는다.

## Project-specific boundaries

- PostgreSQL JSONB·복합 외래 키·부분 인덱스·row lock을 사용하므로 DB 검증은 실제 PostgreSQL Testcontainers로 수행한다. H2 통과로 대체하지 않는다.
- `java.net.URI`는 링크·미디어의 순수 값 계약으로 허용한다. URL·Socket·파일·JDBC 등 domain/application의 실제 I/O 의존성은 계속 금지한다.
- Notion HTTP, 정적 표현 자산, scheduler와 스냅샷 실패 진단은 실제 외부 경계이므로 outbound notion/presentation/diagnostics와 inbound scheduling을 명시적으로 검사한다. application model은 use case 결과와 저장 projection을 담으며 framework 의존성은 허용하지 않는다.
- 복구 가능한 스냅샷 실패도 동일 예외를 진단 출력 포트에 전달한다. 로깅 어댑터는 작업 종류·내부 ID·예외 타입만 기록하고 메시지·원문·URL·스택을 남기지 않는다.
- 공개 범위·게시 상태·스냅샷 변경의 원자성과 RSS의 일관된 읽기를 PostgreSQL rollback·동시성 테스트로 검증한다. 격리 수준과 업무 단위는 이름이나 계층 이동 때문에 약화하지 않는다.
- scheduler의 due 조회·대상 실행 두 경계만 runtime 실패 격리를 위한 개별 catch 예외를 둔다. 인터럽트는 복구·전파하고 뒤의 대상 실행 여부와 함께 테스트한다. 일반 service·repository로 이 예외를 확대하지 않는다.
- 운영 앱·DB·도메인은 신규 샘플이 아니므로 `notion-blog`와 `blog.homelab.robinjoon.xyz`를 유지한다. 세부 차이, 근거와 대체 검증은 개발 문서에서 관리한다.
- 런타임 JVM·메모리는 JRE 기본값을 사용한다. 이미지나 앱 배포 설정에 힙 크기·메모리 비율·OOM 종료 옵션을 추가하지 않는다. Gradle 빌드 JVM 설정과 JDK 25 toolchain, Spring 가상 스레드는 유지하며 하네스 제한은 변경하지 않는다.

DB 통합 테스트는 Docker가 필요할 수 있다. Docker를 사용할 수 없는 환경에서는 실패 원인을 숨기지 말고 단위/경계 테스트 결과와 분리해 보고한다.

## Repository and runtime artifact boundaries

이 저장소가 소유하는 산출물은 다음으로 한정한다.

1. 애플리케이션 소스와 테스트
2. Gradle/Flyway/런타임 설정
3. Dockerfile
4. GitHub Actions CI

- Helm chart, Kubernetes manifest, GitOps 및 배포 하네스 설정을 이 저장소에 추가하지 않는다.
- `.github/deployment.json`은 공통 배포 API의 최초 생성 요청에 필요한 앱별 DB명·도메인·환경변수·Secret 참조만 선언한다. 기존 워크로드에는 재적용하지 않는다. 실제 Secret 값과 하네스 manifest는 저장하지 않는다.
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
