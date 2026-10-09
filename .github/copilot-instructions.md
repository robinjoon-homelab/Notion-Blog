# 저장소 개발 지침

[AGENTS.md](../AGENTS.md)와 [Kotlin/Spring 아키텍처](../docs/kotlin-spring-architecture.md)를 먼저 읽는다. 품질 기준과 예외는 [코드 품질 규칙](../docs/code-quality.md)을 따른다.

- 동작과 성공 조건을 명시하고 실패 테스트부터 작성한다.
- JDK 25와 `./gradlew`를 사용한다. PostgreSQL 통합 테스트에는 Docker가 필요하다. Python은 CI 배포 보조 스크립트의 단위 테스트에만 사용한다.
- `adapter → application → domain` 의존 방향과 소스 중립적인 도메인을 유지한다. Notion DTO와 Exposed 타입은 각 출력 어댑터 밖으로 노출하지 않는다.
- 빈은 `config`에서 조립하며 application service가 Spring `@Transactional`로 트랜잭션을 소유한다. 외부 HTTP 호출 중 DB 트랜잭션을 유지하지 않는다.
- 스키마는 Flyway의 새 migration으로만 변경한다. PostgreSQL 검증을 H2로 대체하지 않는다.
- `./gradlew build`로 ktlint, 타입 분석이 있는 detekt, 사용자 정의 규칙·아키텍처·애플리케이션 테스트와 문서 생성을 확인한다. 자동 포맷 변경을 검토한다.
- baseline, 사유 없는 `@Suppress`, 경로 제외, 검사 비활성화로 실패를 숨기지 않는다. 허용한 예외는 가장 작은 선언 바로 앞에 `quality-exception` 사유를 남긴다.
- 배포 스크립트 변경은 `python3 -m unittest discover -s scripts/tests -v`로 검증한다. 로컬 검증에서 `scripts/deploy.py apply`를 실행하지 않는다.
- 기존 앱·도메인·DB·레지스트리 계약은 [배포 가이드](../docs/deployment.md)를 따른다. 사용자 변경을 보존하며 요청 범위 밖의 원격 작업·배포는 실행하지 않는다.
- 결과와 검증 상태는 한국어로 간결하게 보고한다.
