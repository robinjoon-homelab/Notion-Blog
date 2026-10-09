# Kotlin Spring Boot 템플릿 적용 기준

개발 구조는 2026-10-08에 다시 확인한 [템플릿 원격 master](https://github.com/robinjoon-homelab/kotlin-springboot-exposed-template/tree/ddfbfef26e7af1ab3ee3fc4f00bc137fdb0a4a87)를 기준으로 한다. 기준 SHA는 `ddfbfef26e7af1ab3ee3fc4f00bc137fdb0a4a87`이며, 배포 자동화는 이후 같은 날의 공통 템플릿 로컬 변경본을 반영한다. 이전 `1826e36` 비교의 서비스 트랜잭션·저장소별 트랜잭션 설명은 현재 기준으로 사용하지 않는다.

아키텍처 검사와 런타임 JVM 정책은 이후 2026-10-09의 공통 템플릿 로컬 변경본을 반영한다. 공통 패키지 파서·규칙·정상/위반 fixture를 공유하고 앱별 엔진 분기를 두지 않는다. 기존 블로그의 기술·업무 검사는 공통 파서를 사용하는 추가 테스트로 유지한다.

최신 템플릿은 application service의 `@Transactional`을 허용한다. 따라서 이 프로젝트의 업무 트랜잭션과 RSS `REPEATABLE_READ`를 유지하면서 공통 구조를 적용할 수 있다. 상세 규약은 [개발 가이드](development.md), [코드 품질 규칙](code-quality.md), [아키텍처](kotlin-spring-architecture.md)가 관리한다.

## 적용 구조

- `adapter.inbound/outbound` 이름을 사용하고 HTTP·scheduler는 명시적인 입력 use case port를 호출한다. 서비스의 `@Service`·`@Component`를 제거하고 역할별 `*Config`의 `@Bean`으로 조립한다.
- Exposed 1.5.0 Spring Boot 4 starter의 단일 transaction manager를 사용한다. DB 읽기·쓰기 서비스가 업무 단위 트랜잭션을 소유하고 repository DSL은 현재 경계에 참여한다. 외부 Notion HTTP orchestration은 트랜잭션 밖에 둔다.
- Gradle 9.3.0과 공식 배포 체크섬, native BOM, ArchUnit 1.5.1, 타입 분석 detekt 2.0.0-alpha.6, 빌드 전용 `quality-rules`를 적용한다. baseline·패키지 제외로 위반을 숨기지 않는다.
- 운영 함수 30줄, 테스트·승인된 개별 DSL 80줄, 일반 인자 4개·생성자 6개, 제어 중첩 2단계·scope 중첩 1단계, 인지 복잡도 10을 검사한다. 생성자 예외는 실제 응답·설정·뷰 계약에 한해 개별 사유를 둔다.
- 긴 본문·RSS·스냅샷·Notion 매핑을 역할별 구체 클래스로 분리한다. 복구 가능한 스냅샷 오류는 동일 예외를 진단 출력 포트로 전달하고 로그에 메시지·스택·본문을 남기지 않는다. 기존 snapshot schema-v1과 추가 전용 Flyway, 미게시·공개 범위·URL 안전 규칙을 보존한다. 사용하지 않는 Notion DTO 선언은 제거한다.
- REST Docs가 기존 HTML·RSS 경로의 성공·오류·조건부 응답 계약을 검증한다. Asciidoctor 문서와 로컬 CSS를 실행 JAR에 포함하고 기존 CSP를 유지한다.
- 가상 스레드와 keep-alive를 활성화한다. readiness는 `readinessState,db`, liveness는 `livenessState`로 구성하고 Notion 장애는 readiness에 연결하지 않는다.
- CI는 포맷 diff를 실패로 처리하고 테스트한 `application.jar`를 이미지 job에 전달한다. 보고서는 7일 보관한다. 배포 helper는 하네스 main에서 요청한 repository와 이미지 태그를 확인한다.

## 실제 프로젝트 때문에 유지하는 차이

**PostgreSQL.** JSONB, 복합 외래 키, 부분 인덱스와 행 잠금을 사용하므로 H2 샘플 구성을 도입하지 않는다. 실제 PostgreSQL Testcontainers로 Flyway·제약·rollback·경쟁 읽기를 검증한다. 이는 템플릿의 PostgreSQL 고유 기능 검증 원칙과도 일치한다.

**도메인 값과 실제 어댑터.** URI는 링크·미디어의 순수 값 계약이므로 정확히 `java.net.URI`를 허용한다. URL·Socket 등 실제 I/O 의존성은 금지한다. Notion·표현 자산·스냅샷 실패 진단·scheduler와 application projection은 실제 역할에 맞춘 ArchUnit 경계와 양성·음성 fixture로 검증한다.

**구현 위치의 일반화.** 이전의 `source → notion`, 포트 이름과 같은 출력 그룹, `Controller → inbound.web` 고정 대응은 제거했다. 현재 Notion·표현 자산·진단 구현은 그대로 사용하면서, 다른 그룹에서도 유효한 전체 역할 경로를 구현할 수 있게 한다. 현재 배치는 강제 불변조건이 아니며 소스 중립성, Notion 외부 타입, 정확한 공개 경로, 트랜잭션·직렬화 규칙은 별도로 유지한다.

**스케줄러 실패 격리.** due 조회와 개별 대상 실행 두 메서드만 runtime 예외를 처리한다. 한 대상 실패가 나머지를 중단하지 않게 하되 인터럽트는 복구·전파한다. 일반 서비스·저장소에는 이 예외를 적용하지 않는다.

**배포 자동화의 후속 정렬.** 2026-10-08 공통 템플릿의 로컬 변경본으로 명시적 `APP_NAME`·레지스트리 입력, HTTP 404 최초 생성과 하네스 main의 목표 이미지 확인을 적용했다. 기존 `notion-blog`는 DB·도메인·환경변수·ServiceAccount를 보존하고 태그만 갱신한다. 부재 시 `.github/deployment.json`의 `notion_blog`·`blog.homelab.robinjoon.xyz`와 런타임 Secret 참조로 생성한다. Actions 실행·조회와 Contents 조회가 가능해야 하며, 성공은 Pod·DNS·TLS 상태 검증을 포함하지 않는다. 자세한 사전조건은 [배포 가이드](deployment.md)를 따른다.

**JVM 기본값.** 2026-10-09 공통 정책에 따라 런타임의 명시적인 힙·메모리 비율·OOM 종료 옵션을 제거하고 JRE 기본값을 사용한다. Gradle 빌드 JVM, JDK 25 toolchain과 Spring 가상 스레드 설정은 유지하며 하네스 제한은 변경하지 않는다.

## 별도 후속 설계

[Notion 응답 모델·디코더 분리](notion-adapter-structure.md)는 기존의 별도 후속 설계다. 이번 역할 분리와 미사용 DTO 제거가 전체 JSON 해석 경로의 전환을 완료했다는 의미는 아니다. 입력 허용 정책 변경이나 동기화 전면 재설계를 템플릿 예외 또는 이번 구현 범위에 포함하지 않는다.

검증 명령은 `./gradlew test`, `./gradlew build`이며 두 명령은 ktlint·detekt·품질 규칙 테스트를 포함한다. 배포 helper는 `python3 -m unittest discover -s scripts/tests -v`로 검증한다. Docker와 실행 JAR·문서 확인 절차는 [배포 가이드](deployment.md)를 따른다.
