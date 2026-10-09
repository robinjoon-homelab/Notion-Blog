# 개발 기준

2026-10-08 원격 기본 브랜치에서 확인한 공통 템플릿 기준은 `ddfbfef26e7af1ab3ee3fc4f00bc137fdb0a4a87`이다. 이전 1826e36 비교는 현재 트랜잭션 설계의 기준이 아니다. 최신 템플릿의 실제 코드와 품질 설정을 적용하며, 이 문서는 블로그의 확인된 경계와 검증 근거를 함께 기록한다.

## 의존성과 조립

입력은 `adapter.inbound`에서 `application.port.input`의 use case를 호출한다. 외부 시스템은 `application.port.output`으로 분리하며 `adapter.outbound`가 구현한다. domain과 port는 Spring·Exposed에 의존하지 않는다. service의 Spring 의존성은 `Transactional`, `Isolation`, `Propagation`만 허용하고 빈은 config의 `@Bean`으로 조립한다.

2026-10-09 공통 `ArchitecturePackages`와 `ArchitectureRules`는 계층 우선과 기능 우선 구조를 같은 방식으로 검사한다. 현재 블로그의 계층 구조는 유지하고 Notion 출력 포트 구현 두 개만 `adapter.outbound.notion.source`에 둔다. 전체 역할 경로가 연속된 완전한 세그먼트로 일치해야 하며, 입력 포트를 구현하지 않는 협력 서비스에 인터페이스를 추가하지 않는다.

기능 경로·입력/출력 방향·첫 그룹이 같으면 같은 어댑터다. 따라서 `notion.source`는 기존 `notion.client`·`notion.mapping`과 함께 동작하고 영속성의 `exposed`·`snapshot`도 같은 `persistence` 그룹을 유지한다. 다른 기능의 구현이나 다른 어댑터 그룹은 직접 참조하지 않는다. 루트 공용 포트와 기능 간 공개 계약·값, 조립용 `config`의 허용 범위는 [패키지 규약](code-quality.md#하위-패키지와-기능-경계)을 따른다.

읽기 DB 유스케이스는 `@Transactional(readOnly = true)`, 쓰기 유스케이스는 메서드의 `@Transactional`로 선언한다. Exposed Spring Boot 4 starter의 단일 transaction manager를 사용하며 repository는 같은 경계에 참여하는 DSL 쿼리만 실행한다. 여러 repository를 수정하는 업무와 RSS의 일관된 읽기는 실제 PostgreSQL에서 rollback과 경쟁 변경으로 검증한다. adapter에 transaction annotation이나 `transaction {}`를 추가하지 않는다.

Notion HTTP를 호출하는 동기화 orchestration은 트랜잭션 밖에서 실행한다. 관리되는 조회 service와 적용 service를 호출해 네트워크 대기 중 DB 연결을 보유하지 않는다. 같은 인스턴스 내부 호출을 새 트랜잭션 경계로 가정하지 않는다.

조회 불가 또는 재수집으로 복구하는 스냅샷 오류는 `SnapshotFailureReporter` 출력 포트에 동일 예외를 전달한다. 로깅 어댑터가 작업 종류·내부 ID·예외와 원인 타입만 기록하며 메시지·본문·URL·스택은 남기지 않는다. 기존 503 응답, 손상된 헤더·푸터 생략과 정상 수집에 의한 복구 동작을 유지하면서 원인을 진단할 수 있게 한다. 프레임워크 로거를 application에 넣거나 검사를 피하려고 예외를 무시하는 표현식으로 바꾸지 않는다.

## 품질과 검증

```sh
./gradlew detektMain detektTest
./gradlew :quality-rules:test
./gradlew test
./gradlew build
```

ktlint 자동 수정 후 타입 분석 detekt, ArchUnit, 테스트와 문서 생성을 실행한다. 검증이 바꾼 포맷은 확인하여 반영하고 CI에서는 미반영 포맷을 실패시킨다. 세부 제한과 선언별 예외는 [코드 품질 규칙](code-quality.md)을 따른다. 원격 배포는 로컬 검증에 포함하지 않는다.

## 실제 프로젝트 경계

- PostgreSQL은 JSONB, 부분 인덱스, 복합 외래 키와 행 잠금을 실제로 사용한다. 기본 DB를 H2로 바꾸지 않으며 PostgreSQL Testcontainers의 스키마·snapshot·동시성·rollback 테스트로 검증한다. 템플릿도 PostgreSQL 고유 기능은 해당 DB에서 검증하도록 요구한다.
- `java.net.URI`는 링크·미디어의 순수 값이다. 해당 타입만 I/O 금지 규칙에서 허용하고 URL·Socket 등의 실제 I/O 타입은 금지한다. ArchUnit 양성·음성 fixture로 범위를 검증한다.
- Notion, 배포된 표현 자산, 스냅샷 실패 진단, scheduler는 실제 외부 경계이므로 `outbound.notion`, `outbound.presentation`, `outbound.diagnostics`, `inbound.scheduling`을 별도 검사한다. `application.model`은 use case 결과와 저장 projection을 담으며 framework 의존성을 허용하지 않는다.
- scheduler의 두 작업 실행 경계는 한 대상의 예기치 않은 실패가 다른 대상을 중단시키지 않아야 한다. 정확한 해당 메서드만 넓은 runtime 예외 처리 사유를 남길 수 있으며 일반 service·repository로 확대하지 않는다. 실패 후 후속 작업과 인터럽트 처리를 테스트한다.
- 배포는 명시적 `notion-blog`와 기존 이미지 repository를 사용한다. 기존 워크로드의 DB·공개 주소·환경변수는 보존하며 테스트한 동일 JAR의 이미지 태그만 갱신한다. HTTP 404로 워크로드 부재를 확인한 경우에만 `.github/deployment.json`으로 `notion_blog` DB와 `blog.homelab.robinjoon.xyz`를 포함한 최초 생성을 요청한다. 성공 조건은 하네스 main의 목표 이미지 확인이며 [배포 계약](deployment.md)을 따른다.
- 런타임은 명시적인 JVM·메모리 옵션 없이 JRE 기본값을 사용한다. 힙 크기·메모리 비율·OOM 종료 옵션을 앱 이미지나 배포 설정에 추가하지 않는다. Gradle 빌드 JVM 설정, JDK 25 toolchain과 Spring 가상 스레드는 유지하고 하네스 리소스 제한은 변경하지 않는다.

의도적인 구조 차이, 기존 사용자 제약, 미완료와 환경 때문에 실행하지 못한 검증은 구분한다. 검사 실패나 기존 관행이라는 이유만으로 예외를 승인하지 않는다. 배포 자격 증명이나 외부 권한이 추가로 필요하면 기존 운영 요구와의 차이를 확인하고 검증하지 못한 사실을 남긴다.
