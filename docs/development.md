# 개발 작업 지침

## 실행 환경

- `JAVA_HOME`을 JDK 25로 설정하고 저장소의 `./gradlew`를 사용한다. 전역 Gradle을 사용하지 않는다.
- 의존성 변경 전 `build.gradle.kts`와 `gradle/wrapper/gradle-wrapper.properties`의 버전을 확인한다. 동적 버전과 snapshot 의존성을 쓰지 않는다.
- 로컬 앱은 PostgreSQL을 사용한다. H2로 대체하지 않는다. `.env.example`을 `local.env`로 복사해 값을 채우고 `set -a; source local.env; set +a`로 불러온다. `local.env`는 커밋하지 않는다.
- 필수 환경변수는 `SPRING_DATASOURCE_URL`, `SPRING_DATASOURCE_USERNAME`, `SPRING_DATASOURCE_PASSWORD`, `NOTION_TOKEN`, `NOTION_SETTINGS_DATA_SOURCE_ID`다. 선택 값과 기본값은 `src/main/resources/application.yml`의 `notion.*`, `blog.*` 설정을 따른다.
- `NOTION_API_VERSION`은 `2026-03-11`만 허용한다. `BLOG_PUBLIC_BASE_URL`은 하위 경로·사용자 정보·query·fragment 없는 HTTP(S) 원점이며, 빈 값이면 RSS가 503을 반환한다.
- 글 데이터베이스는 `NOTION_POSTS_DATA_SOURCE_ID`와 속성 ID(`NOTION_POSTS_SUMMARY_PROPERTY_ID`, `NOTION_POSTS_TAGS_PROPERTY_ID`)로 지정한다. 모두 선택이며 비우면 카탈로그 스캔이 설정 오류로 실패하고 기존 설정 데이터 소스 동작은 유지된다. 설정 데이터 소스의 `rootPage`도 선택이다.
- 관리 화면은 OIDC로만 열린다. `blog.admin.oidc.{issuer-uri,client-id,client-secret,allowed-subject}`(`BLOG_ADMIN_OIDC_ISSUER_URI`, `BLOG_ADMIN_OIDC_CLIENT_ID`, `BLOG_ADMIN_OIDC_CLIENT_SECRET`, `BLOG_ADMIN_OIDC_ALLOWED_SUBJECT`)가 하나라도 비면 `/admin/**`은 `403`으로 닫히고 공개 읽기만 동작한다. 허용하는 운영자는 `allowed-subject` 하나다. 클라이언트 시크릿은 환경 주입만 쓴다.
- 업로드 파일 한도는 `BLOG_MEDIA_MAX_BYTES`(파일당 기본 25MiB), `BLOG_MEDIA_MAX_IMPORT_BYTES`(글 하나 기본 100MiB), `BLOG_MEDIA_REQUEST_TIMEOUT`(기본 10s), `BLOG_MEDIA_MAX_REDIRECTS`(기본 3)다. 글 카탈로그 스캔 간격은 `BLOG_SYNCHRONIZATION_CATALOG_INTERVAL_MS`(기본 5분)다.
- 로컬 API는 `http://localhost:8080`으로 호출한다. 빈 DB에서는 Flyway seed의 설정 동기화 대상부터 처리하며, 사이트 설정과 표현 프로필이 준비되기 전 `/`와 게시글 경로는 503이다.
- 운영 PostgreSQL은 [배포 계약](deployment.md#컨테이너와-db-계약)을 따른다. DB 파일·자격 증명을 커밋하지 않는다.

## 기능 변경

1. 명령·조회·성공·실패·동시 실행 조건을 확인하고 [경계 선정 절차](ddd-architecture.md#새-기능을-분류하는-절차)로 소유 BC·루트를 정한다.
2. 도메인 규칙과 [기본 범위](#테스트-범위)의 테스트를 작성한다. 새 BC·루트는 `HexagonalArchitectureTest.contextAggregates`와 아키텍처 문서의 목록에도 등록하고, 새 HTTP 경로는 `TargetArchitectureBoundaryTest`의 허용 목록에도 `HTTP 메서드 경로` 형식으로 추가한다.
3. 필요한 입력·출력 포트와 유스케이스를 변경하고 [트랜잭션 계약](ddd-architecture.md#트랜잭션)을 적용한다.
4. 어댑터와 `config`의 빈 조립을 변경한다. HTTP·DB를 바꾸면 아래 해당 절차도 수행한다.
5. [변경 설명](ddd-architecture.md#변경-설명에-남길-최소-기록)을 남기고 [변경별 검증과 전체 빌드](#실행과-검증)를 실행한다.

## 테스트 범위

테스트 대상과 수준은 이 절을 원본으로 한다.

- 테스트 대상 함수가 본문에서 서로 다른 함수를 2개 이상 호출할 때만 그 함수의 테스트를 작성한다. 다른 함수 하나에 처리를 넘기는 단순 위임은 작성 대상에서 제외한다.
- 호출 수는 테스트 코드가 아니라 대상 함수 본문에서 센다. 같은 함수의 반복 호출은 하나로 보고, 테스트 준비·단언에서 부르는 함수는 세지 않는다.
- 생성자·`init` 블록에도 같은 기준을 적용한다. 내장·표준·라이브러리 함수와 생성자 호출도 호출로 센다.
- 트랜잭션 동작은 프레임워크를 신뢰하고 테스트 작성 대상에서 제외한다.

작성 대상은 다음 방식으로 검증한다.

- 사람이 특정 대상의 내부 호출 검증을 명확히 지시한 경우에만 그 검증을 허용한다. 그 외의 테스트는 반환값·예외·공개 상태 변화·저장 결과·HTTP 요청/응답 계약처럼 외부에서 관찰 가능한 결과를 검증한다.
- 내부의 특정 함수·의존성을 호출했는지, 호출하지 않았는지, 몇 번·어떤 순서·인자로 호출했는지는 검증하지 않는다. MockK의 `verify`·`verifySequence`·`confirmVerified`뿐 아니라 spy·호출 기록·카운터·인자 캡처로 같은 검증을 우회하지 않는다. MockK는 의존성의 입력과 응답을 준비하는 데 사용한다.
- 기존 테스트에 결과 단언이 있으면 호출 검증만 제거한다. 호출 검증만 있는 테스트는 같은 단위의 결과 검증으로 바꾸고, 변경이 어려우면 삭제한다. 테스트를 유지하려고 운영 계약을 바꾸거나 통합 테스트·가짜 저장소를 추가하지 않는다.


| 대상 | 방식 |
| --- | --- |
| 도메인 모델·값 | 프레임워크와 모킹 없이 순수 단위 테스트로 검증한다. |
| 순수 Kotlin 객체가 아닌 의존이 필요한 단위(유스케이스 등) | 출력 포트와 외부 효과를 MockK로 대체한다. 도메인 객체·값·`Clock.fixed`는 실제 값을 쓴다. |
| RDB 출력 어댑터 | 배포된 Flyway 마이그레이션은 PostgreSQL 전용 DDL이므로 Exposed 테이블 객체로 만든 H2 테스트 스키마에 어댑터 하나를 연결해 저장·조회·매핑·제약·어댑터 예외 변환을 검증한다. |
| HTTP 입력 어댑터·외부 API 어댑터 | 입력 포트와 외부 의존을 MockK로 대체하고 MockMvc·MockWebServer 등 전용 기술로 어댑터 하나를 검증한다. 실제 Notion API를 호출하지 않는다. |

- 그 밖의 통합 시나리오·동시성 테스트는 사람이 명확히 지시할 때만 작성한다.
- ArchUnit·detekt 규칙과 fixture 테스트는 구조·규칙 단위 검사로 기본 빌드에 유지한다. 범위 밖 테스트를 `@Disabled`·태그 제외로 남기지 않는다.

## API 변경

- `BlogHttpDocumentationTest`에서 입력 포트를 MockK로 대체하고 MockMvc로 실제 컨트롤러·예외 변환·보안 헤더 필터·Thymeleaf·RSS 렌더링의 요청·응답·상태 코드와 404·503·조건부 요청 응답을 검증한다. 관리 화면·관리 명령·공개/관리 파일 경로는 `AdminHttpDocumentationTest`에서 입력 포트를 MockK로 대체하고 MockMvc로 실제 컨트롤러·예외 변환·보안 헤더 필터·Thymeleaf 렌더링을 검증한다. 두 테스트 모두 DB와 서비스 구현을 연결하지 않는다.
- 관리 경로의 인증·권한·CSRF·로그아웃은 보안 설정과 탐침 컨트롤러만 연결한 `AdminSecurityFilterChainTest`·`AdminSecurityClosedChainTest`에서 검증한다.
- API 변경과 함께 REST Docs 설명을 수정하고 `src/docs/asciidoc/index.adoc`에 생성 스니펫을 연결한다. 문서는 같은 출처 스타일만 쓰므로 외부 글꼴·스크립트를 불러오는 Asciidoctor 속성을 켜지 않는다.
- HTTP 상세를 README·개발 가이드에 재작성하지 않는다. 업무 규칙은 [아키텍처 문서](ddd-architecture.md#현재-업무-규칙)에서 관리한다.
- `build/generated-snippets`와 `build/docs/asciidoc/index.html`을 직접 편집하지 않는다.
- 생성 문서는 전체 빌드가 만든 `build/docs/asciidoc/index.html`과 `application.jar`의 `BOOT-INF/classes/static/docs/index.html`로 확인한다. 화면으로 볼 때는 `java -jar build/libs/application.jar`로 실행해 `http://localhost:8080/docs/index.html`만 열고 `bootRun`을 사용하지 않는다. 이 확인은 HTTP 업무 시나리오 검증이 아니다.

## 스키마 변경

- `src/main/resources/db/migration`의 마지막 버전 다음 번호로 `V<번호>__describe_change.sql`을 추가한다. 배포된 마이그레이션은 수정하지 않는다. 기존 테이블·행·UUID·이력을 삭제하거나 덮어쓰지 않는 추가형으로 쓰고, 기존 데이터 이전은 앱의 멱등 시작 작업([레거시 글 이전](ddd-architecture.md#레거시-글-이전))에서 한다.
- Flyway로만 DDL을 적용하고 `spring.exposed.generate-ddl=false`를 유지한다.
- 기존 데이터 변환·새 제약의 테스트 작성 여부와 방식은 [테스트 범위](#테스트-범위)를 따른다. 여러 저장 작업에는 [트랜잭션 계약](ddd-architecture.md#트랜잭션)을 적용한다.
- 스냅샷 JSON 형식을 바꾸면 `schemaVersion`을 관리하고 `src/test/resources/snapshot/schema-v1`의 기존 스냅샷을 계속 읽는지 검증한다.

## 실행과 검증

로컬 앱이 필요할 때 실행한다. 앱 실행은 테스트 검증 절차에 포함하지 않는다.

```sh
java -version
set -a; source local.env; set +a
./gradlew bootRun
```

변경에 해당하는 검증을 먼저 실행한다. 애플리케이션 테스트를 선별할 때는 `:test` 태스크를 지정한다.

- 도메인·유스케이스: `./gradlew :test --tests '*SynchronizePostServiceTest'`처럼 대상 테스트를 지정한다.
- RDB 어댑터: `./gradlew :test --tests '*ExposedPostRepositoryTest'`처럼 대상 어댑터 테스트를 지정한다.
- HTTP·문서: `./gradlew :test --tests '*HttpDocumentationTest'`(공개 `BlogHttpDocumentationTest`, 관리 `AdminHttpDocumentationTest`).
- 형식: 수정은 `./gradlew ktlintFormat`, 확인은 `./gradlew ktlintCheck`.
- 검사 규칙: `./gradlew detektMain detektTest`, `./gradlew :quality-rules:test`. [검사기 지침](quality-maintenance.md)의 회귀 범위도 확인한다.
- 배포 스크립트: [배포 없이 검증](deployment.md#배포-없이-검증)의 Python 테스트도 실행한다.

마지막에 전체 빌드와 diff를 확인한다.

```sh
./gradlew build
git diff --check
git diff
```

- 전체 빌드의 규칙 테스트·REST Docs 생성을 생략하지 않는다.
- 검증 후 ktlint 자동 수정도 검토한다. 커밋할 때 자동 수정 파일을 빠뜨리지 않는다.
- 실패 시 `build/reports/tests/test/`, `build/reports/detekt/`, `quality-rules/build/reports/tests/test/`를 확인한다.

## 템플릿 적용

1. [Use this template](https://github.com/robinjoon-homelab/kotlin-springboot-exposed-template/generate)로 서비스 저장소를 만들고 복제한다. 첫 `main`·`master` 푸시 전에 [배포 전 설정](deployment.md#배포-전-설정)을 끝낸다.
2. 프로젝트명·패키지를 바꾸면 `settings.gradle.kts`, 소스·테스트의 선언·경로, `HexagonalArchitectureTest`의 `ROOT`와 시작 클래스 단언, 애플리케이션 이름을 함께 변경한다. 시작 클래스명을 바꾸면 `ArchitectureRules`의 `applicationClass` 인자도 맞춘다.
3. 공용 배포 파일과 `application.jar` 이름을 유지하고 앱 이름·이미지 경로는 [배포 변수](deployment.md#배포-이름과-이미지-설정)로 지정한다.
4. `./gradlew build`로 확인한다.
