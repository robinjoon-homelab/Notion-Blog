# Notion Blog Kotlin/Spring 목표 아키텍처

## 1. 문서 상태

- 상태: 현재 구현 및 유지보수 기준선
- 기준일: 2026-10-05
- 런타임 기준: JDK 25
- 적용 범위: 단일 사이트, 단일 Spring Boot 애플리케이션

이 문서는 기존 Kotlin/Spring MVP PoC의 Notion 중심 구조를 대체한 구현의 설계 기준이다. 현재 코드는 소스 중립적인 `Post`·`BlockTree`, 공개 범위와 게시 상태의 분리, 목적별 저장소, `/posts/{postId}` 경로와 Thymeleaf 렌더링을 적용했다. 이후 변경도 이 문서의 경계와 계약을 기준으로 진행한다.

기본 구조 전환과 전체 완료 조건은 구분한다. 공개 범위에서 제외된 글의 동기화 예약은 범위 활성화와 함께 삭제하고, 이전 실행에서 남은 예약도 대상 확인 시 정리한다. 데스크톱·모바일 시각 회귀, 최초 수집의 실행 중단 후 복구와 동기화 전체 재설계, 13.6절의 타입별 DTO 디코딩 원칙과 실제 JSON 파싱 경로의 정합성은 후속 검토·검증 과제로 남아 있다. Notion 어댑터는 13.8절의 구조 설계를 먼저 정리하며, 수집량·깊이·시간 제한과 현재 수집 동작은 이번 변경에서 유지한다.

구조적 결정이 바뀌면 프로덕션 코드보다 이 문서를 먼저 갱신한다. PoC 전환에서는 기존 DB와의 하위 호환성을 제공하지 않고 빈 DB에 목표 스키마만 생성하는 새 Flyway 기준선을 적용했다. 현재 기준선과 이후 마이그레이션은 수정하지 않고 변경을 추가 전용으로 관리한다.

## 2. 제품 정의

이 시스템은 외부 콘텐츠 소스를 편집 도구로 사용하는 셀프 호스팅 블로그다.

핵심 개념은 다음과 같다.

- `Post`는 제목과 `BlockTree`로 구성된 게시글이다.
- `BlockTree`는 텍스트, 미디어, 표, 링크 등 다양한 블록의 재귀 구조다.
- `BlogPublication`은 블로그가 제공할 게시글의 범위를 정의한다.
- 공개 범위는 설정된 루트 문서와 그 구조적 자식 및 후손 문서다.
- 범위 안에 있더라도 외부 소스에서 게시되지 않은 문서는 블로그에서 `404 Not Found`로 처리한다.
- 본문의 링크는 콘텐츠 데이터다. 링크 자체가 공개 범위를 넓히거나 게시글 관계 애그리거트를 만들지 않는다.
- Notion은 `PostSource`와 `SiteConfigurationSource`의 구현체다.
- PostgreSQL과 Exposed는 게시글, 공개 범위, 설정, 동기화 상태를 저장하는 영속성 어댑터다.

## 3. 용어

| 용어 | 의미 |
|---|---|
| 게시글, `Post` | 블로그가 저장하고 렌더링하는 콘텐츠 단위 |
| 블록 트리, `BlockTree` | 게시글 본문을 이루는 재귀 문서 구조 |
| 구조적 자식 | 소스의 부모 정보로 소속을 확인한 문서. Notion에서는 `child_page`의 페이지 부모와 현재 문서에 실제로 속한 DB·데이터 소스의 행 페이지 부모를 확인한다. DB 자체는 게시글이 아니므로 그 행은 포함 게시글의 자식으로 정규화한다. |
| 본문 링크 | 리치 텍스트 링크, 멘션, 북마크, `link_to_page` 등 다른 문서를 가리키는 콘텐츠 |
| 외부 소스 | 게시글과 설정을 가져오는 시스템. 현재 구현체는 Notion이다. |
| 소스 참조, `SourceDocumentRef` | 외부 소스 인스턴스와 외부 문서 ID의 조합 |
| 공개 범위, `publication scope` | 루트부터 구조적 자식 관계로 도달 가능한 게시글 집합 |
| 게시 상태, `PostAvailability` | 외부 소스에서 마지막으로 확인한 `PUBLISHED` 또는 `UNPUBLISHED` 상태 |
| 공개 범위 버전, `PublicationRevision` | 특정 시점의 루트 및 후손 구성원 집합을 나타내는 불변 버전 |
| 스냅샷, `snapshot` | 정규화된 게시글 콘텐츠를 저장한 버전 지정 JSONB |

`Page`, `NotionPage`, `Slug`, canonical, alias는 목표 도메인 용어가 아니다. 웹 경로는 내부 `PostId`로 결정한다.

## 4. 핵심 아키텍처 결정

### ADR-001: 단일 배포형 모놀리스

웹 요청과 동기화 스케줄러는 하나의 Spring Boot 애플리케이션에서 실행한다. 별도 워커나 마이그레이션 이미지를 만들지 않는다.

### ADR-002: 동기식 실행 모델

Spring MVC, Spring `RestClient`, Exposed JDBC DSL을 사용한다. WebFlux, R2DBC, 코루틴 기반 DB 접근을 섞지 않는다.

### ADR-003: 소스 중립적인 블로그 도메인

`domain`과 일반 `application` 코드에는 `NotionPageId`, Notion API DTO, Notion 설정 행이 나타나지 않는다. Notion ID 형식, URL 파싱, API 버전, 페이지네이션과 오류 응답은 Notion 어댑터가 소유한다.

### ADR-004: 게시글은 `Post + BlockTree`다

게시글은 다른 게시글 애그리거트를 포함하지 않는다. 다른 문서는 블록 안의 `LinkTarget` 또는 구조적 자식 참조로만 가리킨다.

### ADR-005: 공개 범위와 게시 상태를 분리한다

공개 범위 구성원 여부는 루트의 구조적 자식 또는 후손인지 나타낸다. 게시 상태는 외부 소스가 해당 문서를 게시했는지 나타낸다. 블로그가 게시글을 제공하려면 다음 조건을 모두 만족해야 한다.

```text
active publication member
AND PostAvailability == PUBLISHED
AND renderable snapshot exists
```

### ADR-006: 사람에게 읽기 쉬운 URL을 만들지 않는다

웹 경로는 다음 두 가지로 고정한다.

```text
GET /                 -> 활성 BlogPublication의 루트 Post
GET /posts/{postId}   -> 내부 PostId로 조회
```

`Slug`, canonical, alias, 제목 기반 경로, `page_route`는 목표 설계에 존재하지 않는다.

### ADR-007: 본문 링크는 게시글 관계가 아니다

본문 링크는 `BlockTree` 내부 데이터다. `PostRelation` 애그리거트나 `post_link` 테이블을 만들지 않는다. 백링크, 관련 글, 링크 그래프 검색이 실제 요구사항이 될 때 별도 조회 모델을 검토한다.

### ADR-008: 스냅샷에 구현 클래스 정보를 저장하지 않는다

스냅샷에는 논리적 `kind`와 명시적 `schemaVersion`만 저장한다. 다음 항목은 금지한다.

- Kotlin 또는 Java FQCN
- Jackson default typing
- `@class` 또는 `type`에 구현 클래스 이름을 기록하는 방식
- Notion 원본 API 응답 전체

### ADR-009: 스타일은 의미 정보와 표현 자산을 분리한다

블록에는 색상, 배경, 정렬, 폭 같은 의미적 `BlockStyle`만 저장한다. CSS와 JavaScript는 콘텐츠에 넣지 않는다. 사이트 표현은 `PresentationProfile`과 신뢰된 정적 자산 참조로 관리한다.

### ADR-010: 외부 장애는 미게시 상태가 아니다

Notion이 명시적으로 미게시 상태를 반환했을 때만 `UNPUBLISHED`로 전환한다. 타임아웃, `429`, `5xx`, 인증 실패, 매핑 실패는 동기화 실패로 기록하며 마지막으로 확인한 게시 상태와 스냅샷을 변경하지 않는다.

## 5. 기술 기준

| 영역 | 선택 |
|---|---|
| JDK | 25 toolchain |
| 언어 | Kotlin |
| 애플리케이션 | Spring Boot 단일 모듈 |
| 웹 | Spring MVC, Thymeleaf |
| 외부 HTTP | Spring `RestClient` |
| 데이터베이스 | PostgreSQL |
| 데이터 접근 | Exposed JDBC DSL |
| 스키마 관리 | Flyway |
| JSON | Jackson Kotlin, PostgreSQL JSONB |
| 운영 | Actuator, Micrometer |
| 테스트 | JUnit 5, AssertJ, MockK, Testcontainers PostgreSQL, MockWebServer |
| 빌드 | Gradle Kotlin DSL, Gradle Wrapper |
| 배포 산출물 | 단일 OCI 이미지 |

정확한 라이브러리 버전은 호환성 테스트를 통과한 고정 버전만 사용한다. 동적 버전과 스냅샷 의존성을 사용하지 않는다.

Spring 구성은 생성자 주입과 타입 안전한 `@ConfigurationProperties`만 사용한다. 필드 주입, `lateinit` 빈 주입, 전역 서비스 로케이터를 사용하지 않는다.

## 6. 시스템 경계

```text
Web controller -----+
                    |
Scheduler ----------+--> Application services --> PostSource / SiteConfigurationSource
                              |                              |
                              |                              v
                              |                       Notion adapters --> Notion API
                              |
                              +--> Repository ports --> Exposed adapters --> PostgreSQL
                              |
                              +--> PresentationAssetCatalog --> Classpath asset adapter
                              |
                              v
                         Pure domain models
```

위 화살표는 런타임 호출 방향이다. 소스 코드의 의존 방향에서는 출력 어댑터가 애플리케이션 포트를 구현하므로 여전히 `adapter -> application -> domain`을 지킨다.

허용되는 의존 방향은 다음과 같다.

```text
adapter -> application -> domain
config  -> adapter/application
domain  -> Kotlin/JDK only
```

렌더링은 외부 시스템으로 데이터를 내보내는 별도 출력 도메인이 아니라 HTTP 표현 계층의 일부다. 따라서 컨트롤러, 뷰 조립기, Thymeleaf 뷰 모델은 `adapter.input.web`에 둔다.

## 7. 전체 패키지 구조

기준 패키지는 `xyz.robinjoon.notionblog`로 유지한다.

```text
src/main/kotlin/xyz/robinjoon/notionblog/
├── BlogApplication.kt
│
├── domain/
│   ├── post/
│   │   ├── Post.kt
│   │   ├── PostId.kt
│   │   └── block/
│   │       ├── BlockTree.kt
│   │       ├── BlockNode.kt
│   │       ├── BlockId.kt
│   │       ├── content/
│   │       │   ├── BlockContent.kt
│   │       │   ├── TextBlockContent.kt
│   │       │   ├── ListBlockContent.kt
│   │       │   ├── LayoutBlockContent.kt
│   │       │   ├── MediaBlockContent.kt
│   │       │   ├── ReferenceBlockContent.kt
│   │       │   └── UnsupportedBlockContent.kt
│   │       ├── inline/
│   │       │   ├── InlineContent.kt
│   │       │   ├── TextAnnotations.kt
│   │       │   └── LinkTarget.kt
│   │       ├── media/
│   │       │   └── MediaSource.kt
│   │       └── style/
│   │           └── BlockStyle.kt
│   ├── publication/
│   │   ├── BlogPublication.kt
│   │   ├── PublicationId.kt
│   │   ├── PublicationRevision.kt
│   │   ├── PublicationMember.kt
│   │   ├── PostAvailability.kt
│   │   └── PublicationPolicy.kt
│   ├── source/
│   │   ├── SourceId.kt
│   │   ├── SourceDocumentRef.kt
│   │   ├── SourceRevision.kt
│   │   └── PostSourceBinding.kt
│   ├── site/
│   │   ├── SiteConfiguration.kt
│   │   ├── SiteMetadata.kt
│   │   ├── PresentationProfile.kt
│   │   ├── PresentationTokens.kt
│   │   └── PresentationAssetRef.kt
│   └── sync/
│       ├── SyncTarget.kt
│       ├── SyncState.kt
│       ├── SyncFailureKind.kt
│       └── RefreshPolicy.kt
│
├── application/
│   ├── model/
│   │   ├── ImportedPost.kt
│   │   ├── ImportedSiteConfiguration.kt
│   │   ├── AppliedSiteConfiguration.kt
│   │   ├── StoredPost.kt
│   │   ├── PostLookupResult.kt
│   │   ├── LinkResolution.kt
│   │   ├── BlogPage.kt
│   │   ├── SynchronizationContext.kt
│   │   └── PresentationAssetDescriptor.kt
│   ├── port/
│   │   └── output/
│   │       ├── source/
│   │       │   ├── PostSource.kt
│   │       │   ├── SiteConfigurationSource.kt
│   │       │   └── SourceExceptions.kt
│   │       ├── presentation/
│   │       │   └── PresentationAssetCatalog.kt
│   │       └── persistence/
│   │           ├── PostRepository.kt
│   │           ├── PublicationRepository.kt
│   │           ├── SiteConfigurationRepository.kt
│   │           └── SyncStateRepository.kt
│   └── service/
│       ├── GetBlogPageService.kt
│       ├── GetPublishedPostService.kt
│       ├── ResolvePostLinksService.kt
│       ├── SynchronizationQueryService.kt
│       ├── SynchronizePublicationService.kt
│       ├── StagePublicationMemberService.kt
│       ├── ActivatePublicationService.kt
│       ├── SynchronizePostService.kt
│       ├── ApplyImportedPostService.kt
│       ├── ApplyImportedSiteConfigurationService.kt
│       └── SynchronizeSiteConfigurationService.kt
│
├── adapter/
│   ├── input/
│   │   ├── web/
│   │   │   ├── BlogController.kt
│   │   │   ├── PostPageViewAssembler.kt
│   │   │   ├── WebExceptionHandler.kt
│   │   │   └── view/
│   │   │       ├── PostPageView.kt
│   │   │       ├── BlockView.kt
│   │   │       └── LinkView.kt
│   │   └── scheduling/
│   │       └── SynchronizationScheduler.kt
│   └── output/
│       ├── notion/
│       │   ├── NotionPostSource.kt
│       │   ├── NotionSiteConfigurationSource.kt
│       │   ├── NotionFailureTranslator.kt
│       │   ├── client/
│       │   │   └── NotionApiClient.kt
│       │   ├── dto/
│       │   │   ├── NotionPageResponse.kt
│       │   │   ├── NotionBlockEnvelope.kt
│       │   │   ├── NotionSettingsRowResponse.kt
│       │   │   ├── NotionPaginationResponse.kt
│       │   │   ├── block/
│       │   │   │   ├── NotionTextBlockData.kt
│       │   │   │   ├── NotionLayoutBlockData.kt
│       │   │   │   ├── NotionMediaBlockData.kt
│       │   │   │   ├── NotionReferenceBlockData.kt
│       │   │   │   └── NotionMeetingNotesData.kt
│       │   │   └── richtext/
│       │   │       ├── NotionRichTextEnvelope.kt
│       │   │       └── NotionAnnotationsResponse.kt
│       │   └── mapping/
│       │       ├── NotionPageMapper.kt
│       │       ├── NotionBlockMapper.kt
│       │       ├── NotionSettingsMapper.kt
│       │       └── NotionReferenceParser.kt
│       ├── presentation/
│       │   └── ClasspathPresentationAssetCatalog.kt
│       └── persistence/
│           ├── exposed/
│           │   ├── ExposedPostRepository.kt
│           │   ├── ExposedPublicationRepository.kt
│           │   ├── ExposedSiteConfigurationRepository.kt
│           │   ├── ExposedSyncStateRepository.kt
│           │   └── table/
│           │       ├── PostTable.kt
│           │       ├── PostSourceBindingTable.kt
│           │       ├── PostSnapshotTable.kt
│           │       ├── PostAvailabilityTable.kt
│           │       ├── PublicationTable.kt
│           │       ├── PublicationRevisionTable.kt
│           │       ├── PublicationMemberTable.kt
│           │       ├── SiteConfigurationTable.kt
│           │       ├── PresentationProfileTable.kt
│           │       ├── PresentationProfileAssetTable.kt
│           │       └── SyncStateTable.kt
│           └── snapshot/
│               ├── JsonBlockTreeSnapshotCodec.kt
│               ├── BlockTreeSnapshotMapper.kt
│               └── dto/
│                   ├── BlockTreeSnapshotDocument.kt
│                   └── BlockSnapshotDocument.kt
│
└── config/
    ├── ApplicationConfiguration.kt
    ├── BlogProperties.kt
    ├── NotionProperties.kt
    └── SchedulingConfiguration.kt
```

리소스와 테스트 구조는 다음 경계를 따른다.

```text
src/main/resources/
├── application.yml
├── db/migration/                 # 추가 전용 Flyway SQL
├── templates/blog/
│   ├── post.html
│   └── fragments/                # 텍스트, 목록, 미디어, 표, 레이아웃 프래그먼트
└── static/presentation/          # 레지스트리에 등록한 버전 지정 CSS/JS 자산

src/test/kotlin/xyz/robinjoon/notionblog/
├── domain/                       # 순수 불변식과 상태 전이
├── application/                  # 유스케이스와 트랜잭션 경계
├── adapter/input/web/            # MockMvc, HTML, 공개 여부
├── adapter/output/notion/        # MockWebServer, DTO, 페이지네이션, 매핑
├── adapter/output/persistence/   # Testcontainers PostgreSQL, Flyway, Exposed
└── architecture/                 # 의존 방향과 금지 타입 검사
```

`application.port.input`은 기본 패키지로 미리 만들지 않는다. 웹과 스케줄러가 동일한 유스케이스를 실제로 공유하고 교체 가능한 진입 계약이 필요할 때만 좁은 입력 포트를 추가한다.

식별자와 작은 값 타입은 가장 가까운 관련 파일에 둘 수 있다. 파일을 나누는 기준은 선언 수가 아니라 응집도다. 반대로 위 트리의 서로 다른 하위 도메인을 한 파일의 무관한 최상위 선언으로 합치지 않는다.

## 8. 도메인 모델

### 8.1 `Post`

```kotlin
@JvmInline
value class PostId(val value: UUID)

data class Post(
    val id: PostId,
    val title: String,
    val content: BlockTree,
)
```

`PostId`는 블로그 내부의 안정적인 식별자다. Notion page ID를 `PostId`로 사용하지 않는다. 외부 ID와의 연결은 `PostSourceBinding`이 담당한다.

`Post`는 공개 범위 구성원 여부, 동기화 실패 횟수, HTTP 경로를 소유하지 않는다.

- `PostId`는 내부에서 생성하며 한 번 연결한 소스 문서에 대해 바뀌지 않는다.
- 제목은 정규화한 뒤 비어 있지 않아야 한다. 소스에 제목이 없으면 소스 어댑터가 명시적인 기본 제목을 만든다.
- `Post`는 항상 완전한 `BlockTree`를 가진다. 본문이 없는 글은 빈 루트 목록으로 표현한다.

### 8.2 `BlockTree`와 `BlockNode`

```kotlin
data class BlockTree(
    val roots: List<BlockNode>,
)

data class BlockNode(
    val id: BlockId,
    val content: BlockContent,
    val style: BlockStyle = BlockStyle.DEFAULT,
    val children: List<BlockNode> = emptyList(),
)
```

블록의 ID, 스타일, 자식 구조는 공통 래퍼에 두고, 렌더링 의미가 다른 데이터만 `sealed` 타입인 `BlockContent`의 하위 타입으로 분리한다. 모든 하위 타입에 `id`와 `children`을 반복하지 않는다.

`BlockContent`는 다음 계열을 표현한다.

- 텍스트: 문단, 제목 1~4, 글머리 기호·번호 목록, 할 일, 토글, 인용, 콜아웃
- 코드와 수식: 코드 블록, 블록 수식
- 레이아웃: 구분선, 열 목록, 열, 탭, 표, 표 행
- 탐색: 목차, 이동 경로
- 문서 참조: 자식 게시글, 페이지 링크, 데이터베이스 링크
- 미디어: 이미지, 영상, 오디오, 파일, PDF
- 외부 콘텐츠: 북마크, 링크 미리 보기, 임베드
- 재사용 콘텐츠: 동기화 블록, 템플릿
- 특수 콘텐츠: 회의록과 안전한 미지원 폴백

새 Notion 블록 타입을 곧바로 범용 속성 맵으로 저장하지 않는다. 데이터와 렌더링 의미가 확인된 타입은 명시적 하위 타입으로 추가한다.

한 게시글 안에서 `BlockId`는 유일해야 한다. 표 행의 셀 수는 표 너비와 일치해야 하며, 열 목록은 열만 직접 자식으로 가진다. 탭은 정규화 이후 `TabItem`만 직접 자식으로 가진다. 외부 입력의 최대 깊이와 최대 블록 수는 도메인 객체 생성 전에 애플리케이션 경계에서 검증한다.

### 8.3 Notion 블록 지원 계약

목표 Notion API 버전은 `2026-03-11`로 고정한다. `NotionProperties.apiVersion`이 애플리케이션이 지원하는 버전과 다르면 시작 단계에서 실패시킨다. 버전을 올릴 때는 공식 응답 유니언 고정 데이터와 매퍼, 스냅샷, 렌더러 테스트를 먼저 갱신한다. 최신 버전 문자열을 자동으로 따라가지 않는다.

Notion의 해당 버전에서 블록 자식 조회 응답은 36개 대안을 가진다. 목표는 모든 대안을 적어도 안전한 폴백으로 인식하고, 공개 블로그에 의미가 있는 타입은 완전 지원하는 것이다. 기준은 [Notion Block object](https://developers.notion.com/reference/block), [Retrieve block children](https://developers.notion.com/reference/get-block-children), [API versioning](https://developers.notion.com/reference/versioning)이다.

지원 등급은 다음과 같다.

| 등급 | 계약 |
|---|---|
| `FULL` | API 의미 필드, 중첩 자식, 스냅샷 왕복, 의미론적 HTML, CSS, 보안·접근성 테스트를 제공한다. |
| `DEGRADED` | 데이터를 잃지 않고 정규화하되, 원래 상호작용 대신 안전한 링크, 카드 또는 텍스트로 표현한다. |
| `FALLBACK` | 원본 타입 표식과 가능한 자식을 보존하고 안전한 안내를 표시한다. 조용히 누락하지 않는다. |

| Notion 응답 타입 | 목표 도메인 표현 | 목표 등급과 정책 |
|---|---|---|
| `paragraph`, `heading_1`~`heading_4`, `bulleted_list_item`, `numbered_list_item`, `quote`, `to_do`, `toggle`, `callout` | 명시적인 텍스트·목록·컨테이너 콘텐츠 | `FULL` |
| `code`, `equation`, `divider` | 코드, 수식, 구분선 콘텐츠 | `FULL`; 수식 렌더링 실패 시 원문 표현식을 안전한 코드로 표시 |
| `column_list`, `column`, `tab`, `table`, `table_row` | 명시적인 레이아웃·탭·표 콘텐츠 | `FULL`; 탭의 직접 자식 문단과 열 비율, 표 헤더 의미를 보존 |
| `breadcrumb`, `table_of_contents` | 이동 경로와 제목 트리에서 파생한 목차 | `FULL` |
| `child_page`, `link_to_page(page_id)` | `SourceDocumentRef`를 가진 문서 참조 콘텐츠 | `FULL`; `child_page`만 공개 범위 후보이고 일반 페이지 링크는 범위를 넓히지 않음 |
| `child_database` | 데이터베이스 참조와 읽기 전용 데이터 뷰 | `table`, `list`, `gallery`만 표시하며, 지원 뷰가 없으면 DB 표시 영역은 제외. 표시와 별도로 실제 소유 DB·데이터 소스의 행 페이지는 구조적 자식으로 발견해 별도 게시글로 수집한다. 연결된 외부 소스는 범위를 넓히지 않음 |
| `link_to_page(database_id)` | 외부 문서 참조 카드 | `DEGRADED`; 링크만으로 데이터베이스나 그 행을 수집하지 않음 |
| `synced_block`, `template` | 동기화 콘텐츠와 읽기 전용 템플릿 컨테이너 | 읽을 수 있으면 `FULL`; 순환, 권한 부족, 깨진 원본은 `FALLBACK` |
| `bookmark`, `image`, `video`, `audio`, `pdf`, `file` | 북마크와 타입이 보존된 `MediaSource` | `FULL`; URL 만료와 허용 스킴 정책 적용 |
| `embed`, `link_preview` | 임베드 또는 안전한 링크 카드 | 허용된 제공자는 `FULL`, 그 외에는 `DEGRADED` |
| `meeting_notes` | 제목, 상태, 공개 가능한 요약·노트 참조 | 기본 `DEGRADED`; 참석자 ID, 녹화 메타데이터, 전체 녹취를 자동 공개하지 않음 |
| `unsupported` | 열린 문자열 타입을 가진 `UnsupportedContent` | `FALLBACK`; `block_type`을 닫힌 enum으로 만들지 않음 |

`FULL` 타입은 타입명만 보존한다는 뜻이 아니다. 다음 시각·동작 필드를 명시적으로 정규화한다.

- 제목의 단계, 색상, `is_toggleable`
- 번호 목록의 시작 번호와 표시 형식
- 할 일의 체크 상태
- 콜아웃과 탭 항목의 아이콘 종류 및 안전한 표시 값
- 코드 언어와 캡션
- 열의 폭 비율
- 표 너비, 열 머리글, 행 머리글, 셀별 리치 텍스트
- 미디어의 소스 종류, URL, 만료 시각, 파일명, 캡션
- 동기화 블록의 원본 참조와 순환 방지 정보
- 모든 블록의 순서와 정규화된 자식

작성자 ID, 최종 편집자 ID처럼 공개 렌더링과 동기화 판단에 쓰지 않는 Notion 공통 메타데이터는 `BlockTree`에 넣지 않는다. 페이지의 게시 여부와 휴지통 상태는 `PostAvailability`로, 페이지 최종 편집 시각은 `SourceRevision`으로 정규화한다. 휴지통에 있는 개별 블록은 어댑터가 본문에서 제외한다.

현재 Notion 어댑터는 `2026-03-11`의 36개 블록 응답 대안을 고정 데이터와 매퍼 테스트로 검증한다. 위 표의 지원 등급은 매핑뿐 아니라 스냅샷·렌더링·보안·접근성을 포함하는 완료 계약이며, 브라우저 시각 회귀 검증은 후속 과제로 남아 있다.

일반 콘텐츠 블록은 `has_children == true`이면 알려진 타입 목록과 무관하게 자식 조회를 수행한다. `child_page`와 실제 소유 데이터 소스의 행 페이지는 게시글 경계이므로 본문을 부모의 `BlockTree`에 붙이지 않고 `containedChildren`으로 넘겨 별도 `Post`로 수집한다. 깊이, 전체 블록 수, 방문한 블록 ID를 제한해 미래 타입, 순환 동기화 블록, 비정상 응답에 대비한다.

### 8.4 리치 텍스트와 링크

리치 텍스트는 HTML 문자열이 아니라 구조화된 인라인 모델로 보존한다. Notion이 제공하는 최상위 타입인 `text`, `equation`, `mention`을 모두 구분하며, 모든 타입의 애너테이션과 안전한 링크 목적지를 유지한다. 세부 계약은 [Notion Rich text](https://developers.notion.com/reference/rich-text)를 기준으로 한다.

```kotlin
sealed interface InlineContent {
    val annotations: TextAnnotations

    data class Text(
        val text: String,
        override val annotations: TextAnnotations,
        val link: LinkTarget?,
    ) : InlineContent

    data class Equation(
        val expression: String,
        override val annotations: TextAnnotations,
    ) : InlineContent

    data class Mention(
        val label: String,
        val kind: MentionKind,
        override val annotations: TextAnnotations,
        val target: LinkTarget?,
    ) : InlineContent
}

sealed interface LinkTarget {
    data class ExternalUrl(val url: URI) : LinkTarget

    data class SourceDocument(
        val reference: SourceDocumentRef,
        val originalUrl: URI?,
    ) : LinkTarget
}

data class TextAnnotations(
    val bold: Boolean,
    val italic: Boolean,
    val strikethrough: Boolean,
    val underline: Boolean,
    val code: Boolean,
    val foreground: ColorToken?,
    val background: ColorToken?,
)
```

`SourceDocument`는 게시글 관계가 아니라 링크 목적지 정보다. 게시글 A가 B를 링크해도 A가 B를 애그리거트로 소유하지 않으며, B가 공개 범위에 추가되지도 않는다.

페이지 멘션은 `SourceDocument`로 정규화할 수 있다. 사용자, 날짜, 템플릿, 데이터베이스, 링크 미리 보기 멘션은 `MentionKind`와 안전한 표시 문자열을 보존하되 게시글 바인딩으로 만들지 않는다.

Notion 애너테이션의 `red`와 `red_background`처럼 하나의 입력 enum에 섞인 값은 매퍼에서 전경색과 배경색으로 분리한다. 두 값을 하나의 색 필드로 합쳐 배경 여부를 잃지 않는다.

### 8.5 `BlockStyle`

```kotlin
data class BlockStyle(
    val foreground: ColorToken?,
    val background: ColorToken?,
    val alignment: Alignment?,
    val width: WidthToken?,
    val variant: StyleVariant?,
)
```

전경색과 배경색을 분리한다. CSS 클래스 이름, CSS 선언, HTML, JavaScript를 넣지 않는다. 웹 렌더러가 의미 토큰을 안정적인 CSS 클래스 또는 CSS 변수로 변환한다.

### 8.6 미디어 소스

```kotlin
sealed interface MediaSource {
    data class External(val url: URI) : MediaSource

    data class SourceHosted(
        val url: URI,
        val expiresAt: Instant?,
    ) : MediaSource
}
```

Notion이 호스팅하는 파일 URL은 만료될 수 있으므로 외부 영구 URL과 구분한다. 만료된 URL은 렌더링하지 않고 안전한 폴백을 표시하며, 다음 동기화에서 갱신한다. 오래된 게시글에서도 미디어를 항상 제공해야 한다는 요구가 생기면 별도 ADR을 거쳐 바이너리 캐시 포트를 추가한다. 현재 설계에서 원본 파일을 PostgreSQL이나 게시글 스냅샷에 넣지 않는다.

### 8.7 외부 소스 식별자

```kotlin
@JvmInline
value class SourceId(val value: String)

data class SourceDocumentRef(
    val sourceId: SourceId,
    val externalId: String,
)

data class PostSourceBinding(
    val postId: PostId,
    val sourceDocument: SourceDocumentRef,
)
```

Notion URL과 UUID 표현의 검증 및 정규화는 Notion 어댑터가 담당한다. 도메인은 검증이 끝난 불투명한 `externalId`만 받는다.

`SourceId`는 구현 클래스 이름이나 FQCN이 아니라 설정된 소스 인스턴스의 안정적인 논리 ID다. 예를 들어 단일 Notion 연결은 `notion-main`처럼 식별하며, 토큰이나 사용자 입력 URL을 ID로 사용하지 않는다.

`SourceRevision`도 소스가 발급한 불투명한 동등성 토큰이다. 도메인은 문자열의 시간 형식을 파싱하거나 대소 관계를 추론하지 않고, 같은 버전인지 여부만 비교한다.

### 8.8 공개 범위

```kotlin
data class BlogPublication(
    val id: PublicationId,
    val rootPostId: PostId?,
    val activeRevisionId: PublicationRevisionId?,
)

data class PublicationRevision(
    val id: PublicationRevisionId,
    val publicationId: PublicationId,
    val state: PublicationRevisionState,
)

enum class PublicationRevisionState {
    STAGING,
    ACTIVE,
    SUPERSEDED,
    ABANDONED,
}

data class PublicationMember(
    val revisionId: PublicationRevisionId,
    val postId: PostId,
    val parentPostId: PostId?,
    val depth: Int,
)
```

`BlogPublication`은 모든 구성원을 메모리에 가진 거대한 애그리거트가 아니다. 현재 루트와 활성 버전 포인터만 소유한다. 첫 활성화 전에는 둘 다 `null`이고, 활성화 후에는 둘 다 존재해야 한다. `PublicationMember`는 공개 범위 버전별 독립 영속 레코드다.

`PublicationRevision`은 계층의 구성원 집합만 버전 관리한다. 게시 상태와 본문 스냅샷은 게시글별로 갱신할 수 있다. 이 분리로 구조 동기화의 원자성과 미게시 취소의 즉시성을 함께 얻는다.

### 8.9 게시 상태

```kotlin
data class PostAvailability(
    val postId: PostId,
    val status: PostAvailabilityStatus,
    val confirmedAt: Instant,
)

enum class PostAvailabilityStatus {
    PUBLISHED,
    UNPUBLISHED,
}
```

초기 상태를 나타내기 위한 `UNKNOWN` enum을 만들지 않는다. 아직 확인하지 않은 게시글에는 `PostAvailability`가 없다. 공개 범위 버전을 활성화하기 전에는 모든 구성원의 게시 상태가 확인되어야 한다.

- `PUBLISHED`로 전환할 때는 렌더링 가능한 스냅샷 저장과 같은 트랜잭션에서 처리한다.
- `UNPUBLISHED` 확인은 즉시 반영하며 기존 스냅샷이 있어도 조회하지 않는다.
- 외부 소스 오류는 상태를 바꾸지 않는다.

### 8.10 사이트 설정과 표현

```kotlin
data class SiteConfiguration(
    val publicationId: PublicationId,
    val rootDocument: SourceDocumentRef,
    val headerDocument: SourceDocumentRef?,
    val footerDocument: SourceDocumentRef?,
    val metadata: SiteMetadata,
    val presentationProfile: PresentationProfileRef,
)

data class PresentationProfileRef(
    val id: PresentationProfileId,
    val version: Long,
)

data class PresentationProfile(
    val id: PresentationProfileId,
    val key: PresentationProfileKey,
    val version: Long,
    val tokens: PresentationTokens,
    val styleSheets: List<PresentationAssetRef>,
    val scripts: List<PresentationAssetRef>,
)

data class SiteMetadata(
    val siteName: String,
    val defaultDescription: String?,
    val languageTag: String,
    val favicon: PresentationAssetRef?,
)
```

환경 변수의 DB 자격 증명, Notion 토큰, API 버전은 `SiteConfiguration`이 아니다. 이 값들은 `@ConfigurationProperties`로 바인딩되는 런타임 설정이다.

`publicationId`는 외부 설정에서 가져오는 값이 아니다. 첫 사이트 설정을 적용할 때 애플리케이션이 하나의 `BlogPublication`을 생성하고 그 내부 ID를 사이트 설정과 함께 저장한다. 이후 루트 문서가 바뀌더라도 동일한 publication을 유지하고 새 공개 범위 revision을 활성화한다.

`PresentationAssetRef`는 관리자 또는 배포 산출물이 등록한 신뢰된 자산만 가리킨다. Notion 설정이나 게시글이 임의의 CSS, JavaScript, `<style>`, `<script>`를 제공할 수 없다.

초기 `PresentationTokens`는 임의 문자열 맵이 아니라 고정된 의미 선택지만 제공한다.

```kotlin
data class PresentationTokens(
    val colorMode: PresentationColorMode = PresentationColorMode.SYSTEM,
    val contentWidth: PresentationContentWidth = PresentationContentWidth.STANDARD,
    val density: PresentationDensity = PresentationDensity.COMFORTABLE,
)
```

각 값은 웹 어댑터가 배포된 CSS의 고정 클래스로 해석한다. CSS 속성명, CSS 값, 클래스 이름, URL을 토큰 값으로 받지 않는다. 실제 필요가 확인된 의미 선택지만 enum 필드로 추가한다.

`SiteMetadata`는 escape 가능한 텍스트와 표준 언어 태그, 신뢰된 파비콘 참조만 가진다. 임의의 `<head>` 조각이나 메타 태그 맵은 두지 않는다. 새 메타데이터가 필요하면 필드를 명시적으로 추가한다.

### 8.11 동기화 도메인

외부 시스템에서 데이터를 가져와 블로그 상태를 갱신하는 개념은 이 블로그의 동기화 도메인이다. 다만 HTTP 호출과 저장 순서는 애플리케이션 서비스가 조율하고, 도메인은 갱신 대상과 다음 시도 정책만 표현한다.

```kotlin
sealed interface SyncTarget {
    data object SiteConfiguration : SyncTarget
    data class Publication(val publicationId: PublicationId) : SyncTarget
    data class Post(val postId: PostId) : SyncTarget
}

data class SyncState(
    val target: SyncTarget,
    val lastSuccessAt: Instant?,
    val refreshAfter: Instant,
    val failureCount: Int,
    val lastErrorKind: SyncFailureKind?,
)

enum class SyncFailureKind {
    RETRYABLE_SOURCE,
    AUTHENTICATION,
    ACCESS,
    CONFIGURATION,
    MAPPING,
}
```

`RefreshPolicy`는 성공 주기와 실패 횟수에 따른 백오프를 계산한다. Notion HTTP 상태 코드, 커서, 토큰은 이 모델에 들어가지 않는다. `Clock`에서 현재 시각을 얻어 정책에 전달하는 책임은 애플리케이션 서비스에 있다.

## 9. 도메인 모델 관계

```text
SiteConfiguration
    ├── publicationId: PublicationId
    ├── rootDocument: SourceDocumentRef
    └── presentationProfile: PresentationProfileRef
                                  │
                                  v
                          PresentationProfile
                                  └── PresentationAssetRef*

SourceDocumentRef ----> PostSourceBinding ----> PostId

BlogPublication -----> root Post
    │                      │
    │ active revision      └── BlockTree
    v                              └── BlockNode*
PublicationRevision                       ├── BlockContent
    └── PublicationMember*                ├── BlockStyle
            ├── parent PostId             ├── children*
            └── PostId                    └── LinkTarget?

PostId ----> PostAvailability
PostId ----> latest BlockTree snapshot

SyncTarget ----> SyncState ----> RefreshPolicy
```

중요한 구분은 다음과 같다.

| 관계 | 공개 범위를 넓히는가 | 별도 관계 엔티티인가 |
|---|---:|---:|
| 구조적 자식 게시글 | 예 | `PublicationMember.parentPostId`로 표현 |
| 리치 텍스트 내부 링크 | 아니요 | 아니요 |
| 멘션 또는 `link_to_page` | 아니요 | 아니요 |
| 외부 URL | 아니요 | 아니요 |

## 10. 공개 규칙과 불변식

### 10.1 공개 결과

| 활성 공개 범위 구성원 | 게시 상태 | 스냅샷 | 결과 |
|---:|---|---:|---|
| 예 | `PUBLISHED` | 있음 | 블로그에서 `200 OK` |
| 예 | `PUBLISHED` | 없음 | 불변식 위반, `503 Service Unavailable` |
| 예 | `UNPUBLISHED` | 무관 | `404 Not Found` |
| 아니요 | `PUBLISHED` | 무관 | 블로그에서 제공하지 않음 |
| 아니요 | `UNPUBLISHED` | 무관 | 블로그에서 제공하지 않음 |

### 10.2 공개 범위 버전 불변식

- 공개 범위 버전마다 루트 구성원은 정확히 하나다.
- 루트 구성원의 `parentPostId`는 `null`이고 `depth`는 `0`이다.
- 루트가 아닌 구성원의 부모는 같은 공개 범위 버전의 구성원이다.
- 모든 부모 사슬은 같은 공개 범위 버전의 루트에서 끝난다.
- 순환과 고립된 구성원을 허용하지 않는다.
- `BlogPublication`마다 활성 버전은 최대 하나다.
- `BlogPublication.rootPostId`와 `activeRevisionId`는 함께 비어 있거나 함께 존재한다.
- 모든 구성원의 게시 상태가 확인된 스테이징 버전만 활성화할 수 있다.
- `PUBLISHED` 구성원은 렌더링 가능한 스냅샷을 가져야 한다.
- 본문 링크는 공개 범위 구성원을 생성하거나 제거하지 않는다.
- 현재 게시글에 실제로 속한 DB의 소유 데이터 소스 행은 공개 범위에 포함한다. 일반 DB 링크, 연결된 외부 데이터 소스와 외부 동기화 블록 원본의 DB는 범위를 넓히지 않는다. 인라인 표·목록·갤러리에 표시하는 행은 원본 뷰의 필터·정렬 및 게시 상태를 따르며, 표시 여부로 구조적 소속을 판단하지 않는다.

미게시 부모의 구조적 자식 탐색은 중단하지 않는다. 미게시 부모 아래의 후손도 루트의 후손이며, 그 후손이 `PUBLISHED`라면 `/posts/{postId}`로 제공한다.

소유 DB의 행도 미게시라면 구성원으로 유지하고 조회 시 `404`를 반환한다. 정상적으로 휴지통에 있는 DB·데이터 소스·행은 구조에서 제외한다. DB 행 아래의 구조적 자식 페이지도 기존 순회로 수집한다. DB 자체를 별도 `Post`나 URL로 만들지 않으며, 행의 부모·깊이는 포함 게시글의 자식으로 정규화한다.

`headerDocument`와 `footerDocument`는 공개 범위를 넓히는 별도 루트가 아니다. 활성 공개 범위 안에서 `PUBLISHED`인 게시글로 해석될 때만 레이아웃 조각으로 사용하고, 범위 밖이거나 미게시 상태면 해당 조각을 생략하고 진단 정보만 남긴다.

### 10.3 링크 해석

`ResolvePostLinksService`는 한 게시글에 포함된 `SourceDocumentRef`를 일괄 조회해 다음처럼 변환한다.

```text
활성 공개 범위 안의 PUBLISHED 대상
  -> /posts/{postId}
  -> 요청 결과 200

활성 공개 범위 안의 UNPUBLISHED 대상
  -> /posts/{postId}
  -> 요청 결과 404

활성 공개 범위 밖의 대상
  -> 안전한 originalUrl 유지

대상을 안전하게 해석할 수 없음
  -> 링크를 만들지 않는 폴백
```

링크 대상을 미리 `post_link` 테이블에 영속화하지 않는다.

인라인 DB의 행 링크와 제목 셀 링크도 페이지 ID를 가진 `SourceDocument`로 정규화한다. 활성 공개 범위에 속한 행은 내부 주소로, 외부 연결 소스 등 범위 밖의 행은 안전한 원래 URL로 연결한다. 렌더링된 인라인 DB 제목은 링크 없는 제목으로 표시하고, 표시 본문을 제공할 수 없는 DB 참조의 원본 링크는 유지한다.

```kotlin
sealed interface LinkResolution {
    data class Internal(val postId: PostId) : LinkResolution
    data class External(val url: URI) : LinkResolution
    data object Unlinked : LinkResolution
}
```

`Internal`은 대상이 활성 공개 범위 안에 있다는 뜻일 뿐, 현재 `200`으로 조회된다는 뜻이 아니다. 게시 여부는 링크 생성 시 우회하지 않고 `GetPublishedPostService`가 요청 시점에 판단한다. 따라서 범위 안의 `UNPUBLISHED` 대상도 내부 링크이며 클릭 결과는 `404`다.

## 11. 애플리케이션 모델과 포트

### 11.1 외부 소스 결과

```kotlin
data class ImportedPost(
    val sourceDocument: SourceDocumentRef,
    val title: String,
    val publicationStatus: ImportedPublicationStatus,
    val sourceRevision: SourceRevision,
    val content: BlockTree,
    val containedChildren: List<SourceDocumentRef>,
)

@JvmInline
value class SourceRevision(val value: String)

enum class ImportedPublicationStatus {
    PUBLISHED,
    UNPUBLISHED,
}

data class ImportedSiteConfiguration(
    val rootDocument: SourceDocumentRef,
    val headerDocument: SourceDocumentRef?,
    val footerDocument: SourceDocumentRef?,
    val metadata: ImportedSiteMetadata,
    val presentationProfileKey: PresentationProfileKey?,
)

data class ImportedSiteMetadata(
    val siteName: String,
    val defaultDescription: String?,
    val languageTag: String,
    val faviconAssetKey: String?,
)
```

`containedChildren`에는 소스의 부모 정보로 확인한 직접 구조적 자식만 들어간다. 리치 텍스트 링크, 멘션, 북마크, `link_to_page`는 이 목록에 들어가지 않고 `BlockTree`의 `LinkTarget`으로만 보존한다.

### 11.2 소스 포트

```kotlin
interface PostSource {
    fun fetch(reference: SourceDocumentRef): ImportedPost
}

interface SiteConfigurationSource {
    fun fetch(): ImportedSiteConfiguration
}
```

현재 구현체는 각각 `NotionPostSource`, `NotionSiteConfigurationSource`다. 소스 포트는 Notion 엔드포인트, 데이터 소스 ID, 커서, Notion DTO를 노출하지 않는다.

현재는 소스 구현이 하나이므로 범용 플러그인 레지스트리를 만들지 않는다. 두 번째 실제 소스가 추가될 때 `SourceId`를 기준으로 명시적으로 위임하는 작은 디스패처를 추가한다. `NotionPostSource`는 자신에게 설정된 `SourceId`가 아닌 참조를 조용히 처리하지 않고 경계 오류로 거부한다.

### 11.3 영속성 및 표현 자산 포트

```text
PostRepository
  - `PostId`와 `SourceDocumentRef` 바인딩 단건·일괄 조회
  - 제목과 바인딩을 가진 게시글 identity 저장
  - `Post`, `SourceRevision`, 포착 시각을 가진 최신 스냅샷 조회와 저장
  - 게시 상태 단건·일괄 조회와 변경
  - 여러 게시글의 렌더링 가능한 스냅샷 존재 여부 조회

PublicationRepository
  - `BlogPublication`과 활성 버전 조회
  - publication 생성과 루트/활성 포인터 저장
  - revision 생성·상태 전이와 구성원 저장·조회
  - 그래프 검증에 필요한 프로젝션 조회
  - 링크 대상의 활성 구성원 여부와 구조적 직접 자식 일괄 조회

SiteConfigurationRepository
  - 현재 정규화된 사이트 설정 조회와 저장
  - ID·version으로 표현 프로필과 정렬된 자산 참조 조회와 저장
  - 등록된 표현 프로필 버전을 key의 현재 버전으로 명시적으로 전환
  - 외부 설정의 표현 프로필 key를 등록된 `is_current` 버전으로 해석

SyncStateRepository
  - 갱신 시각이 된 대상 조회
  - 대상별 현재 동기화 상태 조회
  - 성공 시각, 다음 시도 시각, 실패 횟수, 안전한 오류 분류 저장
  - 제외된 대상의 동기화 예약 삭제

PresentationAssetCatalog
  - 자산 key와 version을 신뢰된 PresentationAssetDescriptor로 해석
  - 외부 설정이 제공한 자산 key를 배포 레지스트리가 지정한 현재 `PresentationAssetRef`와 descriptor로 해석
  - public path, media type, integrity 제공
```

포트 메서드에는 `String snapshotJson`, Exposed `Table`, `ResultRow`, SQL 표현식, Notion DTO를 사용하지 않는다. 인터페이스에 `false`, `emptyList()`, `Unit` 같은 무음 기본 구현을 두지 않는다.

스냅샷 코덱은 애플리케이션 포트가 아니다. 영속성 어댑터의 내부 구현이다. `post.title`은 정규화한 제목의 저장 위치이고, `post_snapshot.snapshot_json`은 `BlockTree`만 저장해 같은 값을 중복 보관하지 않는다.

`PresentationAssetCatalog`는 배포된 정적 자산이라는 실제 외부 경계를 감싼다. 애플리케이션은 클래스패스 경로나 Spring `Resource`를 알지 않고, 등록 여부와 공개 가능한 `PresentationAssetDescriptor`만 사용한다.

`ImportedSiteMetadata.faviconAssetKey`처럼 외부 설정이 key만 제공하는 경우 `resolveCurrent(key)`를 사용한다. 현재 버전은 숫자가 가장 큰 항목을 추측해서 고르지 않고 `BlogProperties`로 주입된 배포 레지스트리의 명시적 current 참조를 따른다. exact `PresentationAssetRef`를 가진 표현 프로필을 읽을 때는 `resolve(reference)`를 사용한다.

애플리케이션 서비스가 사용하는 명시적 저장 projection은 다음과 같다.

```kotlin
data class StoredPost(
    val post: Post,
    val sourceRevision: SourceRevision,
    val capturedAt: Instant,
)
```

`PostRepository`는 snapshot이 없거나 디코딩할 수 없으면 정상 `Post`로 위장하지 않는다. snapshot 없음은 `null`, 손상은 소스 중립적인 `SnapshotContentException`으로 구분하고 `GetPublishedPostService`가 둘 다 `ContentUnavailable`로 변환한다. 게시글 identity와 source binding은 `UNPUBLISHED` 구성원에도 존재할 수 있으므로 snapshot 저장과 별도 메서드로 둔다.

`ActivatePublicationService`는 하나의 트랜잭션 안에서 저장소의 작은 상태 변경 메서드를 조합한다. repository에 도메인 검증을 숨긴 `activateEverything()` 같은 메서드를 두지 않는다. 구 활성 revision을 먼저 `SUPERSEDED`로 저장하고, 새 revision을 `ACTIVE`로 저장한 뒤 publication 포인터를 교체한다.

## 12. 애플리케이션 서비스

### 12.1 `GetPublishedPostService`

조회 조건을 한 곳에서 적용한다.

```text
PostId 또는 root 조회
  -> 활성 BlogPublication 확인
  -> 활성 구성원 여부 확인
  -> PostAvailability 확인
  -> 스냅샷 확인
  -> Found, NotFound 또는 ContentUnavailable
```

`NotFound`와 `ContentUnavailable`은 예상 가능한 애플리케이션 결과이며 예외가 아니다. 웹 어댑터가 각각 `404`와 `503`으로 변환한다. 활성 publication 자체가 없거나 `PUBLISHED` 게시글의 스냅샷이 없거나 손상된 경우가 `ContentUnavailable`이다.

동기화 오케스트레이터는 외부 소스를 호출하는 동안 DB 트랜잭션을 유지하지 않는다. 다만 Exposed 저장소 조회 자체에는 활성 트랜잭션이 필요하므로 `SynchronizationQueryService`가 다음 입력만 짧은 읽기 전용 트랜잭션으로 가져온다.

- 현재 사이트 설정의 publication ID와 루트 소스 참조
- 활성 공개 범위에 속한 개별 게시글의 소스 바인딩
- 해당 게시글의 현재 구조적 직접 자식 소스 참조
- 갱신 시각이 지난 동기화 대상

이 서비스는 외부 HTTP를 호출하거나 쓰기 상태를 바꾸지 않는다. `SynchronizePublicationService`, `SynchronizePostService`, 스케줄러는 조회가 끝난 뒤 트랜잭션 밖에서 외부 소스를 호출하고, 결과 저장은 기존의 짧은 쓰기 서비스에 위임한다. 저장소 내부에 임의의 `transaction {}`을 추가하거나 동기화 서비스 전체에 `@Transactional`을 붙이지 않는다.

### 12.2 `SynchronizeSiteConfigurationService`

1. 트랜잭션 밖에서 `SiteConfigurationSource.fetch()`를 호출한다.
2. 정규화된 설정, 언어 태그, 표현 프로필 키, 파비콘 자산 키를 검증하고 등록된 도메인 값으로 해석한다. 표현 프로필 키가 없으면 생성자에 주입된 신뢰된 기본 키를 사용한다.
3. `ApplyImportedSiteConfigurationService`의 짧은 트랜잭션에서 설정을 저장한다.
4. 루트 소스 참조가 바뀌면 새 공개 범위 동기화를 요청한다.
5. 실패하면 기존 설정을 유지하고 백오프만 기록한다.

현재 단일 애플리케이션에서는 별도 이벤트 포트나 범용 작업 큐를 만들지 않는다. `ApplyImportedSiteConfigurationService`가 루트 변경 여부를 반환하면 트랜잭션이 끝난 뒤 `SynchronizeSiteConfigurationService`가 구체 `SynchronizePublicationService`를 직접 호출한다. 호출 실패는 publication 동기화 실패 상태로 기록되고 기존 활성 revision을 유지한다.

```kotlin
data class AppliedSiteConfiguration(
    val configuration: SiteConfiguration,
    val rootChanged: Boolean,
)
```

첫 설정 저장도 `rootChanged = true`다. 이 명시적 결과를 사용하므로 외부 fetch가 끝난 뒤 저장된 설정을 다시 읽거나 트랜잭션 밖에서 이전 값과 경쟁적으로 비교하지 않는다.

### 12.3 `SynchronizePublicationService`

이 서비스는 본문 링크가 아니라 구조적 자식 관계를 따라 루트 하위 트리를 수집한다.

```text
1. `StagePublicationMemberService`로 새 `STAGING` 공개 범위 버전 생성
2. 루트 `SourceDocumentRef`를 대기열에 추가
3. 대기열에서 소스 문서를 꺼내 `PostSource.fetch` 호출
4. `PostId`와 소스 바인딩 확보
5. `ApplyImportedPostService`로 `Post`, 스냅샷, `PostAvailability`를 짧은 트랜잭션에 반영
6. `StagePublicationMemberService`로 부모-자식 구성원을 `STAGING` 버전에 저장
7. `containedChildren`만 대기열에 추가
8. 모든 후손 수집 후 그래프와 게시 가능 조건 검증
9. 짧은 트랜잭션에서 루트 게시글과 활성 버전 포인터 교체
10. 이전 활성 버전을 `SUPERSEDED`로 전환
```

Notion HTTP 호출 동안 DB 트랜잭션을 유지하지 않는다. 중간에 실패하면 `StagePublicationMemberService`의 짧은 트랜잭션으로 스테이징 버전을 `ABANDONED`로 만들고 기존 활성 버전을 계속 사용한다.

구조 동기화 중 확인한 명시적 `UNPUBLISHED` 상태는 정상 결과다. 반면 외부 장애는 `UNPUBLISHED`로 변환하지 않는다.

활성화에 모든 구성원이 `PUBLISHED`일 필요는 없다. 모든 구성원에게 확인된 `PostAvailability`가 있어야 하고, 그중 `PUBLISHED`인 구성원만 렌더링 가능한 스냅샷을 가져야 한다. `UNPUBLISHED` 구성원도 활성 revision에 남아 내부 링크가 블로그 URL로 해석되고 실제 조회는 `404`가 되게 한다.

공개 범위 활성화 시 이전 구성원 중 새 범위에 없는 게시글의 `SyncTarget.Post` 예약을 같은 트랜잭션에서 삭제한다. 게시 상태만 `UNPUBLISHED`로 바뀌거나 범위 안에서 부모만 바뀐 글은 계속 구성원이므로 예약을 유지한다. 게시글 identity, 소스 바인딩과 이전 스냅샷은 삭제하지 않는다. 활성화가 실패하면 범위 변경과 예약 삭제가 함께 롤백된다.

### 12.4 `SynchronizePostService`

활성 공개 범위의 개별 게시글을 주기적으로 갱신한다.

- 소스 버전이 같으면 게시 상태와 다음 갱신 시각만 확인한다.
- `PUBLISHED` 전환 또는 콘텐츠 변경은 `ApplyImportedPostService`에서 제목, 블록 트리 스냅샷, 게시 상태를 같은 트랜잭션에 저장한다.
- `UNPUBLISHED` 확인은 즉시 게시 상태에 반영한다.
- 동기화 실패는 기존 상태와 스냅샷을 보존한다.
- 구조적 자식 집합이 바뀌면 공개 범위 동기화를 요청한다.

구조적 자식 집합이 바뀐 경우에도 트랜잭션이 끝난 뒤 구체 `SynchronizePublicationService`를 직접 호출한다. 본문 링크 변경은 이 호출을 유발하지 않는다. 두 번째 실제 실행 방식이 필요해질 때만 별도 작업 요청 포트를 도입한다.

이전 실행에서 이미 범위 밖으로 빠진 글의 예약이 남아 있을 수 있다. 개별 동기화 입력을 찾지 못하면 `CancelInactivePostSynchronizationService`의 짧은 쓰기 트랜잭션에서 현재 활성 범위를 다시 확인하고, 비구성원으로 확인된 글의 예약만 삭제한다. publication이나 활성 버전이 아직 없거나, 범위 안의 글에 소스 바인딩이 누락된 경우를 범위 이탈로 간주하지 않는다. 이 정리는 다음 선택부터 오래된 예약이 자리를 차지하지 않게 하며, 같은 실행에서 배치를 추가로 채우거나 새로운 실행 방식을 도입하지 않는다.

### 12.5 `ResolvePostLinksService`

한 `BlockTree`의 소스 문서 링크 대상을 모아 활성 구성원 여부와 바인딩을 일괄 조회한다. HTML을 만들지 않고 `LinkResolution`만 반환한다. 링크를 영속 관계로 저장하지 않는다.

### 12.6 `GetBlogPageService`

웹 화면에 필요한 읽기를 하나의 읽기 전용 유스케이스로 묶는다.

```kotlin
data class BlogPage(
    val site: SiteConfiguration,
    val presentation: PresentationProfile,
    val presentationAssets: Map<PresentationAssetRef, PresentationAssetDescriptor>,
    val post: Post,
    val header: Post?,
    val footer: Post?,
    val links: Map<LinkTarget.SourceDocument, LinkResolution>,
)

sealed interface BlogPageLookupResult {
    data class Found(val page: BlogPage) : BlogPageLookupResult
    data object NotFound : BlogPageLookupResult
    data object ContentUnavailable : BlogPageLookupResult
}
```

1. 요청한 본문을 `GetPublishedPostService`로 조회한다.
2. 현재 `SiteConfiguration`을 읽는다.
3. 참조된 `PresentationProfile`과 신뢰된 자산 descriptor를 해석한다.
4. header/footer 참조가 있으면 같은 공개 규칙으로 조회하고, 제공할 수 없으면 조각만 생략한다.
5. 본문과 제공 가능한 레이아웃 조각의 링크를 `ResolvePostLinksService`로 일괄 해석한다.
6. `BlogPage` 애플리케이션 모델을 반환한다.

`GetBlogPageService.getRoot()`와 `get(postId)`는 `BlogPageLookupResult`를 반환한다. 본문 조회의 `NotFound`와 `ContentUnavailable`을 그대로 보존하되 header/footer 하나를 제공할 수 없는 경우에는 전체 페이지를 실패시키지 않고 해당 조각만 생략한다.

컨트롤러와 뷰 조립기는 저장소를 직접 호출하지 않는다. `PostPageViewAssembler`는 완성된 `BlogPage`를 Thymeleaf 뷰 모델로 변환하기만 한다.

### 12.7 트랜잭션 역할 분리

외부 소스를 호출하는 `Synchronize*Service`는 트랜잭션을 시작하지 않는 오케스트레이터다. 다음 쓰기 서비스의 공개 메서드가 짧은 트랜잭션을 소유한다.

- `ApplyImportedPostService`: 게시글, 블록 트리 스냅샷, 게시 상태, 동기화 성공 상태 반영
- `ApplyImportedSiteConfigurationService`: 사이트 설정, 표현 프로필 참조, 동기화 성공 상태 반영
- `StagePublicationMemberService`: 스테이징 버전 생성과 폐기, 구성원과 부모 관계 반영
- `ActivatePublicationService`: 그래프 재검증, 활성 버전과 루트 포인터 교체
- `CancelInactivePostSynchronizationService`: 현재 활성 범위 밖으로 확인된 게시글의 잔여 예약 삭제

각 `Synchronize*Service`는 외부 실패를 해당 쓰기 서비스의 짧은 `recordFailure`/`abandon` 메서드로 기록한 뒤 호출자에게 다시 전달한다. scheduler와 수동 실행 진입점이 이를 경계에서 로깅하며, 오류를 게시 상태로 바꾸거나 readiness 실패로 연결하지 않는다.

같은 클래스의 내부 호출로 `@Transactional` 프록시를 우회하지 않는다. 읽기 전용 트랜잭션의 최상위 경계는 `GetBlogPageService`가 소유한다. 하위 읽기 서비스는 이 트랜잭션에 참여하며 별도 트랜잭션을 시작하지 않는다.

## 13. Notion 어댑터 설계

### 13.1 내부 관계

```text
NotionPostSource --------------------+
    |                                |
    +--> NotionApiClient             |
    +--> NotionPageMapper            |
    +--> NotionBlockMapper           |
    +--> NotionReferenceParser       |
                                     +--> source-neutral application model
NotionSiteConfigurationSource -------+
    |
    +--> NotionApiClient
    +--> NotionSettingsMapper

NotionApiClient
    +--> RestClient
    +--> adapter-internal DTO
    +--> NotionFailureTranslator
```

내부 협력 객체가 실제 교체 경계가 아니면 인터페이스를 만들지 않는다. `NotionApiClient`, 매퍼, 파서는 기본적으로 구체 내부 클래스로 둔다.

### 13.2 `NotionApiClient`

책임은 HTTP 프로토콜에 한정한다.

- Authorization과 `Notion-Version: 2026-03-11` 헤더 설정
- 페이지 메타데이터 조회
- 블록 자식 조회
- 설정 데이터 소스 조회
- 모든 커서 페이지네이션 수집
- 요청 타임아웃과 전체 수집 제한 시간 적용
- HTTP 상태와 I/O 오류를 안전한 소스 예외로 분류
- 응답을 어댑터 내부 DTO로 역직렬화

Notion DTO, `JsonNode`, `RestClient`는 이 어댑터 밖으로 나가지 않는다.

Notion 페이지·블록 ID는 URL, 설정 행, API 응답에서 하이픈 유무가 다를 수 있다. Notion 어댑터는 페이지를 가리키는 모든 `SourceDocumentRef.externalId`를 소문자 32자리 hexadecimal 문자열로 정규화한다. 설정 루트, `child_page`, `link_to_page`, page mention, 페이지 응답 ID와 부모 `page_id`에 같은 정규화를 적용하며, 형식이 잘못된 값은 매핑 또는 설정 오류로 거부한다. 도메인과 애플리케이션은 이 Notion 전용 규칙을 알지 않는다.

### 13.3 `NotionPostSource`

`PostSource`를 구현하며 다음을 담당한다.

1. Notion 페이지 메타데이터를 조회한다.
2. 정상 메타데이터 응답에서 `in_trash == true` 또는 `public_url`이 명시적인 JSON `null`이면 `UNPUBLISHED`로 정규화한다. 휴지통에 없고 `public_url`이 비어 있지 않은 문자열이면 `PUBLISHED`다. `public_url` 필드 누락·비문자열·빈 문자열(공백만 있는 문자열 포함)은 잘못된 응답으로 `SourceConfigurationException`을 발생시키고 `CONFIGURATION` 동기화 실패로 기록하며 마지막 정상 게시 상태와 스냅샷을 유지한다.
3. 일반 콘텐츠 블록은 `has_children`이 참이면 자식을 페이지네이션 끝까지 재귀 수집한다.
4. Notion 블록 DTO를 `BlockTree`로 변환한다.
5. `child_page`는 부모 본문에 인라인하지 않고 문서 참조 블록으로 남긴다. 대상 페이지의 `parent`가 현재 페이지임을 확인한 경우에만 `containedChildren`에도 넣는다.
6. 리치 텍스트 링크, 멘션, 북마크, `link_to_page`는 `LinkTarget.SourceDocument` 또는 `ExternalUrl`로 변환한다.
7. 동기화 블록 원본을 따라갈 때 방문 ID, 최대 깊이, 전체 블록 수를 검사한다.
8. Notion 수정 시각을 소스 버전으로 반환한다.

구조적 자식과 본문 링크는 같은 수집 목록에 넣지 않는다.

동기화 블록이나 템플릿 안에서 보이는 `child_page`라도 대상 페이지의 실제 부모가 현재 페이지가 아니면 구조적 자식이 아니다. 정상 응답에서 부모가 다르면 링크 콘텐츠로만 보존한다. 부모 확인 요청 자체가 실패하면 스테이징 동기화를 실패시켜 기존 활성 공개 범위를 유지하며, 공개 범위를 추측해 넓히거나 줄이지 않는다.

Notion 호스팅 미디어는 URL과 만료 시각을 함께 `MediaSource.SourceHosted`로 변환한다. 외부 URL은 `MediaSource.External`로 변환한다. 서명 URL을 영구 URL처럼 취급하거나 만료 시각을 버리지 않는다.

### 13.4 `NotionSiteConfigurationSource`

`SiteConfigurationSource`를 구현하며 다음을 담당한다.

- 설정 데이터 소스의 모든 커서 조회
- `rootPage`, `header`, `footer`, `head` 행 해석
- Notion URL과 원시 페이지 ID 파싱
- 필수 루트 검증
- 허용된 사이트 메타데이터와 표현 키 검증
- `ImportedSiteConfiguration` 반환

Notion 행 키와 속성 이름은 이 어댑터 내부 계약이다. `settingsDataSourceId`는 `2025-09-03` 이후의 데이터 소스 ID이며 `/v1/data_sources` 조회 경로를 사용한다. `head` 행은 허용된 `SiteMetadata`와 표현 프로필 키만 만들 수 있다. `SynchronizeSiteConfigurationService`가 이 세부 사항을 알아서는 안 된다.

### 13.5 오류 변환

Notion 어댑터는 다음 소스 중립 오류로 변환한다.

| Notion 결과 | 애플리케이션 오류 | 상태 변경 |
|---|---|---|
| `429`, 타임아웃, `5xx` | 재시도 가능한 소스 실패 | 없음 |
| `401`, `403` | 소스 인증 실패 | 없음 |
| `404 object_not_found` | 삭제 또는 권한 회수를 구분할 수 없는 소스 접근 실패 | 없음 |
| 잘못된 설정 또는 응답 (`public_url` 필드 누락·비문자열·빈 문자열 포함) | `SourceConfigurationException` / `CONFIGURATION` 실패 | 없음: 마지막 정상 게시 상태와 스냅샷 유지 |
| 정상 응답의 `in_trash == true` 또는 명시적인 JSON `public_url: null` | 정상 `UNPUBLISHED` 결과 | 게시 상태 변경 |

오류 메시지, 토큰, 원본 응답, 전체 URL을 로그나 `last_error`에 저장하지 않는다.

### 13.6 어댑터 호환성 원칙

아래는 목표 해석 구조의 계약이다. 현재 구현에는 `JsonNode`를 직접 읽는 매퍼와 사용되지 않는 타입별 DTO가 함께 남아 있으며, 13.8절의 설계를 기준으로 후속 전환한다.

- 공식 블록 응답 유니언과 리치 텍스트 유니언을 JSON 고정 데이터로 보관한다.
- `NotionBlockEnvelope`은 공통 필드, 열린 `type`, 타입별 JSON payload만 받는다. 어댑터 내부 디코더가 `type`에 맞는 DTO로 명시적으로 해석하고 `NotionBlockMapper`가 블로그 모델로 변환한다. 36개 타입의 필드를 한 DTO의 nullable 속성 가방으로 모으지 않는다.
- `JsonNode`는 이 envelope과 타입별 DTO 디코딩 사이에서만 사용하며 Notion 어댑터 밖으로 내보내지 않는다.
- 알 수 없는 `type`은 `UnsupportedContent`로 보존한다. 반면 알려진 타입의 필수 필드가 깨졌다면 안전한 폴백으로 위장하지 않고 매핑 실패로 처리해 마지막 정상 스냅샷을 유지한다.
- `unsupported.block_type`은 열린 문자열로 보존한다.
- API 버전 변경은 설정값만 바꾸는 운영 작업이 아니라 DTO, 매퍼, 스냅샷, 렌더링 계약 변경이다.
- `transcription` 같은 과거 버전 이름은 새 스냅샷에 기록하지 않는다. API 버전 업그레이드 중 필요한 입력 별칭은 Notion 어댑터에서만 일시적으로 처리한다.

### 13.7 인라인 데이터베이스

`child_database`는 일반 블록 자식 조회가 아니라 데이터베이스 API와 Views API로 수집한다. 구조적 자식 발견은 데이터 소스 query API를 사용하며 표시용 뷰와 분리한다. `link_to_page(database_id)`는 수집을 유발하지 않는 참조 카드로 유지한다. 표시 레이아웃은 제품 정책상 `table`, `list`, `gallery`만 지원한다. 나머지 뷰는 표시용 행 쿼리나 스키마 조회 전에 제외하고 안내 탭도 만들지 않는다. 모든 뷰가 제외되면 DB 표시 영역을 만들지 않지만 소유 행의 구조적 자식 발견은 계속한다.

소유 관계와 실패 기준은 다음과 같다.

- DB의 `parent.page_id`가 현재 소스 문서와 같아야 한다. 부모가 `block_id`이면 해당 블록의 실제 부모를 API로 따라가 현재 페이지에 도달하는지 확인한다. 외부 동기화 원본도 포함할 수 있는 단순 방문 ID 집합을 소유의 근거로 사용하지 않는다. 부모 순환·깊이 초과·잘못된 응답·조회 실패는 수집 실패이며, 다른 페이지나 workspace에 속한 정상 DB는 구조적 범위에 넣지 않는다. 지원하지 않는 부모 형태를 확인 실패 없이 빈 범위로 추측하지 않는다.
- DB의 `data_sources`에 있는 소스를 모두 확인하되, 소스의 실제 부모가 그 DB인 경우만 구조적으로 소유한 것으로 본다. 외부 DB 또는 다른 데이터 소스가 부모인 연결 소스는 표시용으로만 취급한다. 응답 ID와 부모 필수 값이 잘못되면 실패한다.
- 소유 소스는 `POST /data_sources/{id}/query`에 `result_type: page`, `page_size: 100`과 후속 `start_cursor`만 전달해 끝까지 열거한다. 표시 뷰의 필터·정렬이나 지원 레이아웃 여부를 전달하지 않는다. 각 행의 부모 데이터 소스가 조회 대상과 같은지 확인하고, 미게시 행은 포함하며 휴지통 행만 제외한다.
- 반복 커서·중복 행·불완전한 query 응답은 실패로 처리한다. 기존 수집 시간·작업량 제한 안에서 요청하고 새 무제한 수집 경로를 만들지 않는다. 여러 표시 뷰에 같은 행이 나오는 것은 정상이며 구조적 열거를 반복하는 이유가 되지 않는다.
- DB 메타데이터와 소유 소스 열거는 게시 범위를 결정하므로 접근 실패도 전체 fetch 실패로 전달한다. 빈 자식 목록이나 표시용 fallback으로 바꾸어 기존 활성 범위를 축소하지 않는다. 소속 확인이 끝난 뒤 뷰·커버 등 표시 정보의 접근 실패는 기존 표시용 fallback을 유지한다.

표시용 조회는 다음 기존 계약을 유지한다.

- 데이터베이스 메타데이터와 뷰 목록을 읽고 각 뷰가 해당 표시 데이터베이스에 속하는지 확인한다. 뷰가 지정한 데이터 소스의 스키마를 조회한다. 연결된 데이터 소스도 이 명시적 참조와 접근 가능 여부로 확인하며 표시 데이터베이스의 소유 소스라고 가정하지 않는다. 지원 뷰의 `configuration` 생략/null은 사용자 지정 설정 없음으로 해석하고 기존 타입별 기본 옵션을 적용한다. `configuration.properties` 전체가 생략/null이면 세 레이아웃 모두 스키마의 유일한 제목 속성만 표시하는 보수적인 기본값을 사용한다. 이는 Notion 화면의 모든 기본 속성을 추측하는 정책이 아니며 표의 나머지 열도 자동 공개하지 않는다. 명시적인 속성 배열이 있으면 `visible: true`인 열의 순서·폭·줄바꿈만 적용하며 빈 배열, 전부 숨김, `visible` 생략을 기본 제목/전체 스키마로 대체하지 않는다. 제목을 유일하게 확인할 수 없는 기본 뷰의 스키마는 매핑 실패로 처리한다. 이 기본값 해석은 Notion 어댑터에만 두고 도메인과 스냅샷의 세 뷰 모델은 유지한다.
- 뷰 쿼리를 생성하고 커서를 끝까지 읽어 저장된 필터·정렬을 적용한 행 ID를 얻는다. 각 행은 표시 속성 ID와 갤러리의 명시적 커버 속성 ID만 요청하고, `public_url`이 명시적인 JSON `null`이거나 `in_trash == true`인 정상 행은 표시 스냅샷에서 제외한다. 잘못된 `public_url`은 기존 `SourceConfigurationException` / `CONFIGURATION` 실패로 처리한다. 커버용 숨긴 속성은 셀에 넣지 않는다. 표시용 뷰 쿼리 결과나 관계 속성으로 `containedChildren`을 추가하지 않으며, 앞의 소유 관계 확인과 독립 열거 결과만 사용한다.
- 여러 지원 뷰는 기존 탭 모델로 표현한다. 지원 뷰가 하나뿐이면 전환할 대상이 없으므로 탭 목록과 패널 테두리를 만들지 않고 본문을 직접 표시한다. 여러 뷰일 때만 이름을 가진 탭 전환 UI를 제공하고, 필터링 후 API 반환 순서의 첫 지원 탭을 초기 표시한다. 이를 Notion 사용자의 현재 선택/기본 뷰라고 간주하지 않는다. 표는 열별 폭·줄바꿈, 전체 줄바꿈, 고정 열 수, 세로선 설정을 반영한다. 그룹 배치·하위 항목은 평면 행으로 표시한다. 리스트는 제목과 속성 목록, 갤러리는 커버와 속성을 가진 카드 그리드로 표시한다. 셀 수정·행 추가·드래그 이동 같은 편집 기능은 제공하지 않는다.
- 소스 중립적인 `DataViewContent`를 `Table`, `ListView`, `Gallery`로 닫힌 모델링한다. 세 타입은 `DataSet`(제목·열·행·표시 제목 열 위치)을 공유하고, 표 옵션과 갤러리 옵션은 해당 하위 타입에만 둔다. `DataColumn`은 이름·선택적 의미상 픽셀 폭·줄바꿈 설정을, `DataRow`는 셀별 `InlineContent`와 선택적인 행 링크·아이콘·커버 `MediaSource`만 가진다. 열은 하나 이상이며 행의 셀 수와 일치하고 제목 열 위치와 고정 열 수는 범위를 검증한다. Notion DTO, 원본 설정 JSON, CSS 문자열을 도메인에 저장하지 않는다.
- 새 스냅샷은 논리 `data_view` kind와 명시적인 `table`/`list`/`gallery` 레이아웃을 저장한다. 이전 버전 1의 `data_table`은 스냅샷 어댑터에서 `DataViewContent.Table`로 복원하며 구 도메인 타입을 병렬로 유지하지 않는다. 이전 표 스냅샷에는 원본 뷰 타입이 없으므로 복원된 표는 다음 정상 동기화에서 새 지원 정책으로 교체한다.
- 갤러리 커버는 명시적인 `page_cover`, 파일 속성, `page_content` 첫 이미지 설정을 지원한다. 공개 행 확인 후에만 커버를 정규화한다. 파일 속성은 파일명/URL 경로의 이미지 확장자로 식별하며 원격 HEAD 요청으로 형식을 추측하지 않는다. `page_content`는 그 행의 직접 블록을 기존 페이지네이션/시간/수집량 한도 안에서 읽어 첫 이미지 하나만 쓰고, 중첩 본문이나 링크 페이지를 재귀 수집하지 않는다. 서명 URL은 `MediaSource.SourceHosted`로 만료 시각을 보존한다. 커버 없음·접근 불가·만료는 이미지 없는 카드로 표시하며 임의의 다른 이미지로 대체하지 않는다.
- 제목/텍스트, 숫자, 체크박스, 선택·상태, 날짜, URL, 이메일·전화번호, 공개 표시 이름, 파일 이름, 계산된 스칼라 값은 안전한 텍스트/리치 텍스트로 표현한다. 관련 문서, 파일 바이너리, 사용자 ID·이메일 메타데이터는 따라가지 않는다. 알 수 없는 속성과 불완전한 속성 응답은 원시 JSON이나 잘린 값을 완전한 값처럼 표시하지 않고 명시적인 미지원/불완전 안내를 제공한다.
- 뷰·행·셀 수집은 게시글의 전체 제한 시간과 수집량 제한 안에서 처리한다. 소속 확인이 완료된 DB의 표시용 뷰 접근 실패는 안전한 참조로 대체하며, 구조적 소속 확인 실패·인증·일시 장애·잘못된 응답·수집 한도 초과는 동기화 실패로 분류해 기존 스냅샷을 유지한다. 불완전한 쿼리를 정상적인 전체 표나 완전한 자식 목록으로 저장하지 않는다.
- 부모 페이지 수정 시각만 같아도 DB 행·스키마·뷰가 바뀔 수 있다. 인라인 DB가 포함된 소스 버전에는 정규화된 데이터와 뷰 구성을 반영한 지문을 포함해 행 수정, 미게시 전환, 필터·열 변경이 다음 동기화에서 반영되게 한다. 원본 응답이나 비밀 값은 소스 버전에 기록하지 않는다.

표시 계층은 도메인의 세 타입에 대응하는 명시적 뷰 타입과 Thymeleaf 프래그먼트를 사용한다. 데이터베이스 제목은 독립된 섹션 제목으로 한 번 표시한다. 실제 표시 본문이 있으면 제목은 외부 링크로 만들지 않는다. 뷰 이름은 전환이 필요한 다중 뷰의 탭에서만 시각적으로 표시하며, 표 caption과 리스트·갤러리 내부 제목은 스크린 리더용 이름으로만 남겨 제목을 반복하지 않는다. 표의 caption/thead/tbody, 리스트의 목록/속성 의미, 갤러리 카드 제목·이미지 대체 텍스트와 빈 결과 안내를 제공한다. 열 폭은 고정된 CSS 폭 단계로 제한하고 고정 열의 좌측 위치는 신뢰된 자산 스크립트가 실제 DOM 폭을 측정해 계산한다. 데이터베이스 섹션은 일반 문서 참조 카드의 테두리·그림자·호버 배경을 제거하고 제목과 읽기 전용 본문을 Notion 인라인 데이터베이스처럼 이어서 표시한다. 편집 전용 검색·필터·정렬·새로 만들기 컨트롤을 흉내 내지 않는다. 원본 CSS나 inline script를 삽입하지 않는다. 새 버전의 CSS/JS와 추가 전용 프로필 마이그레이션으로 기존 자산을 유지한다. 코드 블록의 언어별 하이라이팅은 이 변경에 포함하지 않는다.

### 13.8 Notion 플랫폼 구조를 먼저 정의한다

Notion이 정한 응답 형태·필드 규칙과 블로그의 변환·표시 정책을 구분하는 내부 구조를 [Notion 어댑터 구조 설계](notion-adapter-structure.md)에 정리한다. 이는 구현 전 설계이며, 현재 일부 DTO 선언은 실제 JSON 해석 경로에서 사용되지 않는다. 문서에 목표 타입을 적었다는 이유로 기존 파싱 경로가 전환되었다고 간주하지 않는다.

같은 플랫폼 자료인 미디어·리치 텍스트 등을 본문, 아이콘, 데이터베이스 카드가 함께 사용하도록 형식 해석의 책임을 정한다. 블로그의 공개 범위·게시 상태·미지원 처리 정책은 별도로 유지한다. 구조 전환과 입력 허용 정책 변경을 한꺼번에 수행하지 않으며, 수집 순서·수집 제한·스케줄러의 전면 재설계는 이번 구조 정의에 포함하지 않는다.

## 14. 영속성 어댑터 설계

### 14.1 목표 스키마

```text
post
  post_id UUID PK
  title
  created_at
  updated_at

post_source_binding
  source_id
  external_id
  post_id FK UNIQUE
  PRIMARY KEY (source_id, external_id)

post_snapshot
  post_id PK/FK
  snapshot_json JSONB
  source_revision
  captured_at

post_availability
  post_id PK/FK
  status: PUBLISHED | UNPUBLISHED
  confirmed_at

publication
  publication_id PK
  root_post_id FK nullable
  active_revision_id nullable

publication_revision
  revision_id PK
  publication_id FK
  state: STAGING | ACTIVE | SUPERSEDED | ABANDONED
  started_at
  activated_at nullable

publication_member
  revision_id FK
  post_id FK
  parent_post_id nullable
  depth
  PRIMARY KEY (revision_id, post_id)

site_configuration
  site_id PK
  publication_id FK UNIQUE
  root source reference
  optional header/footer source reference
  normalized metadata JSONB
  presentation_profile_id
  presentation_profile_version
  synced_at

presentation_profile
  presentation_profile_id
  profile_key
  version
  token_json JSONB
  is_current
  created_at
  PRIMARY KEY (presentation_profile_id, version)

presentation_profile_asset
  presentation_profile_id
  presentation_profile_version
  asset_kind: STYLE_SHEET | SCRIPT
  asset_key
  asset_version
  integrity
  position
  PRIMARY KEY (presentation_profile_id, presentation_profile_version, asset_kind, position)

sync_state
  target_kind
  target_key
  last_success_at
  refresh_after
  failure_count
  last_error_kind
  PRIMARY KEY (target_kind, target_key)
```

필수 DB 제약은 다음과 같다.

- `(source_id, external_id)`는 전역에서 하나의 `PostId`에만 연결된다.
- `BlogPublication`마다 활성 버전은 최대 하나다.
- `root_post_id`와 `active_revision_id`는 함께 `NULL`이거나 함께 값이 있어야 한다.
- 부분 고유 인덱스로 공개 범위 버전마다 루트 구성원이 최대 하나임을 보장하고, 활성화 검증으로 정확히 하나임을 보장한다.
- 부모 구성원은 자식과 같은 공개 범위 버전에 존재한다. `(revision_id, parent_post_id)`는 `(revision_id, post_id)`를 참조하는 복합 외래 키다.
- `depth`는 음수가 아니다.
- 게시 상태와 공개 범위 버전 상태는 대문자 논리 값만 허용한다.
- `ACTIVE`와 `SUPERSEDED` revision은 최초 활성화 시각을 보존하고, 한 번도 활성화되지 않은 `STAGING`과 `ABANDONED`만 `activated_at`이 `NULL`이다.
- 실패 횟수는 음수가 아니다.
- 표현 프로필의 `version`, 자산 버전, 자산 순서는 음수가 아니다.
- 사이트 설정이 참조하는 표현 프로필 ID와 버전은 실제 `presentation_profile` 행에 존재한다.
- `(profile_key, version)`은 유일하며, 외부 설정의 키는 등록된 프로필만 선택할 수 있다.
- 프로필 key마다 `is_current = true`인 버전은 최대 하나이며, 외부 설정의 key는 그 현재 버전으로 해석한다. 새 버전을 등록하는 것만으로 현재 버전이 바뀌지는 않는다.
- 단일 `site_configuration`은 정확히 하나의 `BlogPublication`을 참조한다. 루트 소스 참조 변경은 새 publication을 만들지 않고 해당 publication의 새 revision을 요청한다.
- `publication.active_revision_id`는 같은 `publication_id`에 속한 revision만 참조한다.

`page_route`를 대체하는 테이블은 만들지 않는다. `/posts/{postId}`는 저장된 경로가 아니라 웹 어댑터의 결정적 규칙이다.

### 14.2 Exposed 구현

- Exposed `Table`, `ResultRow`, SQL 표현식은 `adapter.output.persistence.exposed` 밖으로 노출하지 않는다.
- 저장소는 도메인 모델 또는 명시적 애플리케이션 프로젝션을 반환한다.
- 쓰기 트랜잭션은 애플리케이션 서비스의 공개 메서드가 소유한다.
- 저장소 내부에서 임의로 `transaction {}`을 중첩하지 않는다.
- 읽기 전용 애플리케이션 서비스에는 `@Transactional(readOnly = true)`를 사용한다.
- PostgreSQL 제약과 트랜잭션 롤백은 Testcontainers로 검증한다.

### 14.3 포트 분리

기존 PoC의 단일 `BlogPersistencePort`처럼 경로, 게시글, 설정, 스케줄러, 실패 상태를 한 인터페이스에 모으지 않는다. 목적별 네 저장소로 나누되, 단일 메서드만을 위한 인터페이스를 추가하지 않는다. 모든 포트 메서드는 구현을 강제하며 무음 기본 구현을 금지한다. 표현 프로필은 사이트 설정과 같은 수명 주기에서 `SiteConfigurationRepository`가 관리한다.

## 15. 스냅샷 JSON 계약

### 15.1 형식

```json
{
  "schemaVersion": 1,
  "kind": "block_tree_snapshot",
  "blocks": [
    {
      "kind": "paragraph",
      "id": "block-id",
      "style": {
        "foreground": "default",
        "background": "default"
      },
      "content": {
        "richText": [
          {
            "kind": "text",
            "text": "다른 글",
            "link": {
              "kind": "source_document",
              "sourceId": "notion",
              "externalId": "0123456789abcdef0123456789abcdef"
            }
          }
        ]
      },
      "children": []
    }
  ]
}
```

### 15.2 규칙

- `schemaVersion`은 디코딩 계약의 버전이다.
- `kind`는 문서화된 논리 값만 사용한다.
- `kind`는 `paragraph`, `heading`, `list_item`, `media`, `document_reference`처럼 소스 중립적인 도메인 의미를 나타낸다. Notion의 `heading_1`~`heading_4`는 `kind: heading`과 `level`로, 파일 계열은 `kind: media`와 `mediaType`으로 저장한다.
- 논리 `kind`와 Kotlin 클래스 이름 사이에 이름 기반 자동 매핑을 두지 않는다. `when` 기반의 명시적 매퍼가 양방향 변환을 소유한다.
- 도메인에는 Jackson 애너테이션을 넣지 않는다.
- 영속성 어댑터의 스냅샷 DTO와 명시적 매퍼가 도메인 모델을 변환한다.
- 알 수 없는 블록 `kind`는 `UnsupportedContent`로 안전하게 복원한다.
- 지원하지 않는 스냅샷 스키마 버전은 디코딩 실패로 분류하고 저장된 활성 콘텐츠를 임의로 덮어쓰지 않는다.
- 스냅샷은 정규화한 데이터만 저장하며 Notion 원본 응답을 보관하지 않는다.
- `activateDefaultTyping`, 클래스 이름 판별자, FQCN 허용 목록을 사용하지 않는다.

`JsonBlockTreeSnapshotCodec`과 스냅샷 DTO는 영속성 어댑터 내부 구현이다. 애플리케이션이 JSON 문자열을 인코딩하거나 디코딩하지 않는다.

### 15.3 기존 FQCN 스냅샷 처리

기존 PoC 스냅샷은 현재 런타임 코덱의 입력으로 지원하지 않는다. PoC 전환에 적용한 정책은 다음과 같다.

1. 기존 PoC DB 스키마와 데이터는 지원하거나 이관하지 않는다.
2. 새 Flyway 기준선은 목표 테이블만 생성하며 `notion_page`, `site_settings`, `page_snapshot`, `page_route`를 만들지 않는다.
3. 배포 시 DB를 새로 만들고 루트 기준 전체 소스 재동기화로 논리 판별자 스냅샷을 생성한다.
4. 첫 공개 범위 버전이 검증되고 활성화되기 전까지 읽기 요청은 초기화 중 `503`을 반환한다.
5. FQCN 레거시 디코더와 기존 스냅샷 읽기 경로는 두지 않는다.

기존 PoC DB를 유지한 무중단 전환은 지원 범위가 아니다. 외부 배포 하네스는 새 DB의 초기 동기화와 활성 `BlogPublication` 존재를 확인한 뒤 트래픽을 전환해야 한다. 이 확인은 Actuator readiness와 별개다.

기존 `linkedPageIds`는 구조적 자식 정보가 아니므로 공개 범위를 역으로 채우는 데 사용하지 않는다.

## 16. 트랜잭션과 동시성

외부 HTTP 호출과 DB 트랜잭션을 분리한다.

```text
PostSource.fetch                    // DB transaction 없음
  -> 정규화 및 검증
  -> StagePublicationMemberService  // 짧은 @Transactional
```

공개 범위 활성화는 다음 변경을 하나의 트랜잭션에서 수행한다.

- 스테이징 그래프와 게시 가능 조건 최종 검증
- 기존 활성 버전을 `SUPERSEDED`로 전환
- 새 스테이징 버전을 `ACTIVE`로 전환
- `BlogPublication`의 루트 게시글과 활성 버전 포인터 교체
- 이전 범위에만 속했던 게시글의 동기화 예약 삭제

게시글 상태 변경은 다음 단위로 원자적이어야 한다.

- 새 제목과 스냅샷 저장 및 `PUBLISHED` 전환
- `UNPUBLISHED` 전환
- 동기화 성공 상태와 다음 갱신 시각 갱신
- 실패 횟수와 다음 재시도 시각 갱신

동일 소스 문서의 동시 동기화는 프로세스 내에서 합친다. 여러 복제본에서 스케줄러를 활성화하려면 PostgreSQL advisory lock이나 DB lease를 별도 도입한다.

시간 판단은 애플리케이션 서비스에 주입한 `Clock`과 `Instant`를 사용한다. 도메인이나 서비스에서 `Instant.now()`를 직접 호출하지 않는다. 오래 남은 `STAGING` 버전은 다음 동기화 시작 시 `ABANDONED`로 정리하고 새 버전으로 다시 시도한다.

영속성 어댑터도 `Instant.now()`로 시각을 결정하지 않는다. `SiteConfigurationRepository.save`의 동기화 시각과 `saveProfile`의 생성 시각처럼 DB에 기록할 시각은 애플리케이션 서비스 또는 명시적 부트스트랩 서비스가 주입된 `Clock`으로 만들고 포트 인자로 전달한다.

## 17. 웹과 렌더링

### 17.1 컨트롤러

`BlogController`는 `@Controller`를 사용하고 `GetBlogPageService`에 조회를 위임한 뒤 Thymeleaf 뷰 이름과 모델을 반환한다. `TemplateEngine.process()`로 HTML 문자열을 직접 만들거나 `ResponseEntity<Any>`에 HTML을 담지 않는다.

```text
GET /
  -> 활성 루트 Post 조회
  -> Found: 200 + Thymeleaf view
  -> 루트 UNPUBLISHED: 404
  -> 활성 루트 데이터가 없거나 손상됨: 503
  -> 아직 활성 BlogPublication이 없음: 503

GET /posts/{postId}
  -> 아직 활성 BlogPublication이 없음: 503
  -> 활성 구성원 + PUBLISHED + 스냅샷: 200
  -> 구성원 아님 또는 UNPUBLISHED: 404
  -> PUBLISHED이지만 스냅샷이 없거나 손상됨: 503
```

형식이 잘못된 `postId`와 존재하지 않는 `postId`는 모두 `404`로 처리한다. 내부 식별자의 파싱 오류를 별도 `400` 응답으로 노출할 이유가 없다.

### 17.2 뷰 모델

`PostPageViewAssembler`는 도메인 `Post`와 링크 해석 결과를 타입이 명확한 뷰 모델로 바꾼다. 하나의 `BlockView(kind, nullable fields...)` 속성 가방을 사용하지 않는다.

```text
BlockView
  ├── ParagraphView
  ├── HeadingView
  ├── ListView
  ├── MediaView
  ├── EmbedView
  ├── TableView
  ├── ColumnListView
  ├── TabView
  ├── NavigationView
  ├── MeetingNotesView
  └── UnsupportedView
```

Thymeleaf 프래그먼트는 뷰 하위 타입의 명시적 계약을 사용한다. 도메인 모델이나 Notion DTO를 직접 참조하지 않는다.

### 17.3 안전한 링크와 미디어

- 외부 링크와 미디어 URL은 `http`와 `https`만 허용한다.
- 내부 링크는 `/posts/{PostId}` 형식으로만 만든다.
- `javascript:`, `data:`, 원시 HTML을 거부한다.
- 외부 링크에는 적절한 `rel` 정책을 적용한다.
- 임베드는 제공자 허용 목록, `sandbox`, `title` 정책이 마련된 타입만 `DEGRADED` 또는 `FULL`로 지원한다.
- 수식은 신뢰된 KaTeX 자산으로 렌더링하고 실패하면 원문 표현식을 표시한다. `mermaid` 등 실행형 코드 언어는 별도 보안 정책이 생기기 전까지 일반 코드로 표시한다.
- 지원하지 않는 블록은 타입과 자식을 보존하는 안전한 폴백을 렌더링한다.

## 18. CSS와 표현 계층

Notion과 유사한 외형은 도메인 데이터를 흉내 내는 CSS 선택자가 아니라 안정적인 의미론적 HTML과 토큰으로 구현한다.

### 18.1 기본 토큰

- 페이지 폭과 가로 여백
- 글꼴 계열, 크기, 굵기, 줄 높이
- 전경색과 배경색 팔레트
- 블록 세로 간격
- 제목 크기 체계
- 테두리, 모서리 반경, 그림자
- 콜아웃, 인용, 코드, 북마크, 표 토큰
- 밝은 모드와 어두운 모드
- 모바일 중단점과 열 쌓기

### 18.2 블록 스타일 매핑

```text
BlockStyle(foreground = RED)
  -> class="notion-color-red"

BlockStyle(background = BLUE)
  -> class="notion-background-blue"
```

CSS 클래스 이름은 웹 어댑터의 고정 매핑이다. 스냅샷에는 클래스 문자열을 저장하지 않는다.

열 폭도 웹 뷰 모델에서 5% 단위의 닫힌 값으로 정규화한 뒤 허용 목록의 `notion-column-width-*` 클래스 하나로만 렌더링한다. 템플릿은 폭 비율이나 열 수를 `style` 속성 또는 임의 CSS 클래스로 만들지 않는다.

### 18.3 영속 표현 프로필

초기 구현은 클래스패스의 버전 지정 CSS를 사용할 수 있다. 추후 영속성에서 표현 프로필을 가져오더라도 DB에는 다음만 저장한다.

- 테마와 버전
- 허용된 디자인 토큰 값
- 신뢰된 자산 키와 자산 버전
- 무결성 값 또는 콘텐츠 해시
- `SiteConfiguration`이 선택한 활성 프로필 참조

`presentation_profile`은 디자인 토큰을, `presentation_profile_asset`은 정렬된 자산 참조만 저장한다. 자산 키를 실제 공개 경로로 바꾸는 `PresentationAssetCatalog`의 구현체는 `ClasspathPresentationAssetCatalog`다. `ApplicationConfiguration`은 타입 안전한 `BlogProperties`에서 배포 산출물의 자산 목록을 읽어 이 어댑터에 주입한다. DB에는 파일 경로, 원격 URL, CSS 본문, JavaScript 본문을 저장하지 않는다.

초기 부트스트랩은 애플리케이션이 제공하는 불변 기본 프로필을 추가 전용 Flyway 마이그레이션으로 등록한다. 이 기본 프로필의 ID·version·토큰·정렬된 자산 참조와 무결성 값은 해당 애플리케이션 버전의 `ClasspathPresentationAssetCatalog` 등록값과 정확히 일치해야 한다. 애플리케이션 시작 runner가 임의 시각에 DB를 수정하거나 외부 소스를 호출하지 않는다. 관리자가 새 프로필을 활성화할 때는 새 migration 또는 별도 관리 유스케이스가 모든 자산 키와 무결성 값을 카탈로그에서 검증한 뒤 `SiteConfiguration.presentationProfile`을 원자적으로 교체한다.

Notion 콘텐츠와 설정에서 원시 CSS나 JavaScript를 받아 저장하거나 실행하지 않는다. JavaScript가 필요하면 관리자 전용 신뢰 경계에서 등록한 콘텐츠 해시가 고정된 자산만 허용한다.

추후 CSS나 JavaScript 파일 본문 자체를 영속화해야 한다면 별도 ADR로 관리자 전용 `PresentationAssetStore`를 추가한다. 이 경우에도 프로필은 불변의 콘텐츠 주소와 무결성 값만 참조하고, Notion 어댑터는 자산 쓰기 권한을 갖지 않는다. 현재 요구사항만으로 이 포트나 저장소를 미리 만들지는 않는다.

### 18.4 Notion 유사 CSS 완료 기준

- 블록별 DOM 계약과 CSS 선택자는 스냅샷 종류가 아니라 뷰 타입을 기준으로 한다.
- 리치 텍스트 전경색과 배경색, 블록 배경색을 서로 다른 토큰으로 표현한다.
- 토글 제목, 중첩 목록, 표 머리글, 열 비율, 탭, 캡션을 의미론에 맞는 HTML로 렌더링한다.
- 이미지 대체 텍스트, 표 `scope`, iframe 제목, 키보드 초점 상태를 제공한다.
- 데스크톱과 모바일 대표 문서를 스크린숏 회귀 테스트로 비교한다.
- Notion의 비공개 CSS 클래스명이나 DOM 구조를 복사하지 않고 시각 토큰과 공개 API 의미를 기준으로 구현한다.

## 19. 스케줄링, 오래된 콘텐츠와 오류

`SynchronizationScheduler`는 갱신 시각이 된 대상 조회와 애플리케이션 서비스 호출만 담당한다. Notion HTTP, 블록 매핑, 공개 범위 정책, SQL 쓰기를 직접 수행하지 않는다.

빈 DB에서도 최초 설정 pull이 시작되어야 하므로 기본 표현 프로필을 등록하는 부트스트랩 migration은 `SyncTarget.SiteConfiguration`에 대응하는 실패 없는 due `sync_state` 행도 함께 넣는다. 이후 설정·publication·post 쓰기 서비스가 성공 또는 실패 시각을 갱신한다. 스케줄러가 설정 행의 부재를 별도 저장소 조회로 추측하거나 시작 시 외부 호출을 직접 수행하지 않는다.

게시글의 예약 유지 기준은 게시 여부가 아니라 활성 공개 범위의 구성원 여부다. 범위에서 제외되면 예약을 삭제하고, 미게시 구성원은 재공개를 감지할 수 있도록 계속 확인한다. 남아 있던 범위 밖 예약은 개별 동기화 진입 시 정리한다. 최초 설정 저장과 첫 공개 범위 수집 사이의 실행 중단 복구는 별도 후속 과제로 남긴다.

### 19.1 성공

- 정상 게시글과 설정은 설정된 주기가 지난 뒤 다시 확인한다.
- 소스 버전이 같으면 불필요한 스냅샷 재작성을 피한다.

### 19.2 명시적 미게시

- `UNPUBLISHED`로 즉시 전환한다.
- 스냅샷은 보관할 수 있지만 읽기 경로에서 절대 제공하지 않는다.
- 해당 게시글의 내부 URL은 `404`를 반환한다.

### 19.3 외부 실패

- 마지막으로 확인한 게시 상태와 스냅샷을 유지한다.
- 기존 `PUBLISHED` 게시글은 오래된 스냅샷이라도 제공한다.
- 재시도 가능한 실패는 백오프를 적용한다.
- Notion 장애는 readiness 실패로 연결하지 않는다.

로그에는 소스 종류, 내부 `PostId` 또는 해시한 소스 참조, 오류 분류, 허용된 상태 코드만 남긴다. 토큰, 원본 응답, 사용자 입력 전체 URL, 임의 예외 메시지를 남기지 않는다.

## 20. 보안 경계

- Notion 토큰과 DB 자격 증명은 비밀 값이며 저장소, 이미지, 로그, 테스트 고정 데이터에 저장하지 않는다.
- Notion 페이지 참조 파서는 공식 Notion 호스트와 원시 ID만 허용하고 유사 호스트를 거부한다.
- Notion 원본 HTML은 저장하거나 렌더링하지 않는다.
- 스냅샷 디코더는 허용된 `schemaVersion`과 논리 `kind`만 처리한다.
- 스냅샷 디코더가 클래스 이름을 해석하거나 클래스 로딩을 수행하지 않는다.
- 원시 CSS, 원시 JavaScript, 원시 head HTML을 소스에서 가져오지 않는다.
- 미디어와 임베드는 스킴 및 제공자 정책을 적용한다.
- 응답에는 표현 자산과 허용된 임베드 제공자만 허용하는 Content Security Policy를 적용한다.
- 미게시 게시글의 스냅샷이 DB에 남아 있어도 애플리케이션 조회가 이를 반환하지 않는다.

## 21. 테스트 전략

구조 변경은 문서를 먼저 갱신한 뒤 실패 테스트부터 작성한다.

### 21.1 도메인

- `Post`와 재귀 `BlockTree` 불변식
- 전경색과 배경색 스타일 구분
- `BlogPublication` 루트 유일성
- 부모가 같은 공개 범위 버전의 구성원인지 검증
- 순환과 고립된 구성원 거부
- 링크만으로 공개 범위 구성원이 생성되지 않음
- 미게시 부모 아래의 게시된 후손도 공개 범위 구성원임
- `PUBLISHED`이지만 스냅샷이 없는 구성원의 활성화 거부

### 21.2 스냅샷

- 모든 블록 하위 타입의 논리 판별자 왕복
- 중첩 자식과 리치 텍스트 링크 왕복
- `schemaVersion` 검증
- 알 수 없는 블록 `kind`의 폴백
- 알 수 없는 스키마 버전 거부
- 출력 JSON에 `xyz.`, `java.`, `kotlin.`, `@class`가 없음
- Jackson 기본 타입 지정을 사용하지 않음

### 21.3 Notion 어댑터

- `2026-03-11`의 36개 블록 응답 대안 고정 데이터
- 페이지와 설정 데이터 소스의 커서 페이지네이션
- 중첩 블록 자식 수집
- `child_page`와 일반 링크 분리
- DB·데이터 소스·행 부모 검증과 블록 부모 추적, 외부 연결 소스 제외
- 뷰 필터·지원 레이아웃과 독립된 소유 행 열거, 미게시 유지·휴지통 제외
- 구조 열거 실패·불완전 응답 시 기존 공개 범위 보존
- 리치 텍스트의 `text`, `equation`, 모든 멘션 종류 매핑
- `heading_4`, `tab`, `meeting_notes`, `unsupported.block_type` 매핑
- 알 수 없는 타입은 폴백하고 알려진 타입의 깨진 필수 필드는 동기화 실패로 분류
- `public_url` 게시 상태 매핑
- 미게시 부모의 자식 순회
- Notion 호스팅 미디어의 만료 시각 보존
- 타임아웃, `429`, `5xx`, 인증, 설정 오류 분류
- 최대 깊이, 블록 수, 수집 제한 시간

### 21.4 애플리케이션

- 루트와 게시된 후손 동기화
- 공개 범위 안의 `UNPUBLISHED` 대상은 내부 URL이지만 조회 결과 `NotFound`
- 공개 범위 밖 링크는 원래 외부 URL 유지
- 외부 실패가 `UNPUBLISHED`로 전환되지 않음
- 외부 HTTP 호출 중 DB 트랜잭션이 없음
- 스테이징 실패 시 기존 활성 버전 유지
- 공개 범위 활성화의 원자성
- 범위 이탈 시 예약 삭제, 미게시·범위 안 이동 시 예약 유지
- 잔여 예약 정리 후 유효 대상의 차례 확보, 초기화 전 예약 보존
- 활성화 롤백 시 기존 범위와 예약 동시 보존
- 명시적 미게시 취소의 즉시성
- 등록되지 않은 표현 프로필 키와 자산 키 거부
- 공개 범위 밖이거나 미게시된 header/footer 문서 생략

### 21.5 영속성

- Flyway와 Exposed 매핑 일치
- 소스 바인딩 유일성
- 공개 범위 버전당 루트 유일성
- 활성 버전 유일성
- 같은 공개 범위 버전의 부모를 가리키는 복합 외래 키
- 트랜잭션 롤백
- 표현 프로필과 자산 참조의 복합 외래 키
- JSONB 저장, 조회, 코덱 왕복

PostgreSQL 동작은 H2로 대체하지 않고 Testcontainers PostgreSQL로 검증한다.

### 21.6 웹과 렌더링

- `/`와 `/posts/{postId}`의 `200`, `404`, 초기화 중 `503`
- 범위 안의 미게시 링크 클릭 시 `404`
- 공개된 header/footer 문서만 레이아웃 조각으로 렌더링
- 지원 블록의 의미론적 HTML
- 미지원 폴백과 자식 보존
- 위험 URL과 HTML escaping
- Content Security Policy와 외부 링크 `rel` 정책
- 표 머리글, 토글 제목, 중첩 목록, 탭, 반응형 열 구조
- 만료된 미디어 URL의 안전한 폴백
- 데스크톱과 모바일 시각 회귀
- 접근성: 대체 텍스트, 제목 계층, 표 범위, iframe 제목, 초점 상태

### 21.7 스케줄러와 운영 경계

- 주입된 `Clock` 기준으로 갱신 시각이 된 대상만 전달
- 성공 주기와 실패 백오프 상태 전이
- 오래 남은 `STAGING` 버전 폐기와 재시도
- 여러 실행이 같은 소스 문서 동기화로 합쳐짐
- Notion 장애가 Actuator readiness를 실패시키지 않음

## 22. PoC 전환 순서와 적용 기록

1~7단계의 기본 구조 전환은 현재 코드에 적용되어 있다. 아래 목록은 전환 당시의 순서와 근거를 보존한다. 8단계의 지원 블록·표현 확장은 계속 진행하며, 시각 회귀를 포함한 전체 완료 여부는 21절의 테스트 전략과 25절의 완료 조건으로 판단한다.

### 1단계: 현재 동작 특성화

- Notion 페이지네이션과 오류 분류
- 오래된 스냅샷 제공
- 미게시 취소
- 루트 렌더링
- 현재 지원 블록 폴백과 XSS 방어

새 동작으로 대체할 slug, canonical, alias 테스트는 장기 보존 대상이 아니다.

### 2단계: 새 도메인과 스냅샷 계약

- `Post`, `BlockTree`, `SourceDocumentRef` 추가
- 공개 범위와 게시 상태 분리
- 논리 판별자 코덱의 실패 테스트 작성
- 어댑터 내부 스냅샷 DTO와 코덱 구현
- FQCN이 출력되지 않는 테스트 통과

### 3단계: 영속성 경계

- `BlogPersistencePort`를 목적별 저장소로 분리
- 무음 기본 구현 제거
- 빈 DB 기준 V1 마이그레이션으로 목표 테이블만 생성
- 새 Exposed 저장소와 Testcontainers 통합 테스트 작성

### 4단계: Notion 소스 어댑터

- `NotionGateway`를 `PostSource`와 `SiteConfigurationSource`로 교체
- Notion API 버전을 `2026-03-11`로 고정
- 36개 블록 응답 대안과 세 가지 리치 텍스트 타입의 고정 데이터 작성
- Notion DTO, 매퍼, 참조 파서를 어댑터 내부로 이동
- 구조적 자식과 본문 링크 매핑 분리

### 5단계: 공개 범위 동기화

- 루트 하위 트리의 스테이징과 활성화 구현
- 개별 게시글 갱신 구현
- 외부 호출과 트랜잭션 분리
- 기존 활성 `BlogPublication` 폴백 검증

### 6단계: 웹과 렌더링

- `/`와 `/posts/{postId}` 읽기 경로 구현
- `@Controller`와 타입이 명확한 뷰 모델 적용
- 링크 일괄 해석 구현
- 의미론적 HTML과 CSS 토큰 적용

### 7단계: 기존 구조 제거

새 동작의 회귀 테스트와 함께 다음 기존 구성요소를 제거했다.

- `NotionPage`, `NotionPageId`, domain의 `NotionPageReference`
- `Slug`, `PageRoute`, `PageRoutes`, alias/canonical logic
- `NotionBlock`이라는 소스 종속적 이름
- `BlogPersistencePort`와 기본 메서드
- `NotionGateway`
- `TransactionalPageStore`, `TransactionalSettingsStore`
- `TaggedPageSnapshotCodec`, `PageSnapshotCodec` port
- `NotionPageRenderer`의 수동 HTML 렌더링
- `/notion/{pageId}` lazy proxy 경로
- `page_route` 읽기·쓰기 경로

기존 PoC V1/V2는 새 기준선에서 제거했다. 현재 `V1__create_blog_persistence.sql`과 후속 마이그레이션은 이 전환 이후의 이력이며 수정하거나 다시 기준선으로 합치지 않는다.

### 8단계: 블록과 시각적 충실도 확장

- 지원 블록 행렬을 API 버전별 고정 데이터로 관리
- 리치 텍스트, 스타일, 표, 열, 미디어 손실 제거
- 블록별 의미론적 HTML과 Notion 유사 CSS 구현
- 시각 회귀와 접근성 테스트 추가

## 23. 기존 PoC에서 현재 구조로의 대응

| 기존 PoC 구성요소 | 현재 구성요소 |
|---|---|
| `NotionPage` | `Post`, `BlogPublication`, `PostAvailability` |
| `NotionBlock` | `BlockTree`, `BlockNode`, `BlockContent` |
| `NotionPageId` | `PostId`, `SourceDocumentRef`, `PostSourceBinding` |
| `Slug`, `PageRoute` | 제거. `/posts/{PostId}`로 결정 |
| `NotionGateway` | `PostSource`, `SiteConfigurationSource` |
| `NotionRestClientAdapter` | `NotionApiClient`, `NotionPostSource`, `NotionSiteConfigurationSource` |
| `NotionJsonMapper` | 페이지, 블록, 설정 매퍼와 참조 파서 |
| `BlogPersistencePort` | 네 개의 목적별 저장소 |
| `PageSnapshotCodec` | 영속성 어댑터 내부 `JsonBlockTreeSnapshotCodec` |
| `PageRefreshService` | 공개 범위 구조 동기화와 게시글 갱신 서비스 |
| `PageAccessService` | `GetPublishedPostService`, `ResolvePostLinksService` |
| `NotionPageRenderer` | `PostPageViewAssembler`, 타입이 명확한 뷰 모델, Thymeleaf |
| `page_route` | 제거 |

## 24. 런타임과 저장소 산출물 경계

이 저장소는 다음만 소유한다.

1. 애플리케이션 소스와 테스트
2. Gradle, Flyway, 런타임 설정
3. Dockerfile
4. GitHub Actions CI

Helm chart, Kubernetes manifest, GitOps 설정, 복제본과 스케줄러 리더 정책, Secret/ConfigMap 주입, Ingress와 TLS는 외부 하네스 저장소가 소유한다.

런타임 산출물은 단일 Spring Boot OCI 이미지다. 애플리케이션은 `8080` 포트와 Actuator liveness/readiness 엔드포인트를 제공한다. Notion 장애는 readiness를 내리지 않는다.

## 25. 완료 조건

- 도메인이 Kotlin/JDK 외 라이브러리에 의존하지 않는다.
- Notion DTO와 ID 규칙이 Notion 어댑터 밖으로 노출되지 않는다.
- Exposed 타입이 영속성 어댑터 밖으로 노출되지 않는다.
- 외부 HTTP 호출 중 DB 트랜잭션을 유지하지 않는다.
- 공개 범위와 게시 상태가 별도 모델과 테스트로 검증된다.
- 미게시된 공개 범위 구성원의 내부 URL이 `404`를 반환한다.
- 본문 링크가 공개 범위를 넓히지 않는다.
- slug, canonical, alias, `page_route`가 새 구조에 남지 않는다.
- 스냅샷 JSON에 FQCN이나 Jackson 클래스 메타데이터가 없다.
- 기존 FQCN 스냅샷은 소스 재동기화로 대체되고 런타임 레거시 디코더가 남지 않는다.
- `2026-03-11`의 36개 Notion 블록 응답 대안이 모두 명시적 타입 또는 안전한 폴백으로 처리된다.
- 지원하지 않는 블록이 조용히 소실되지 않는다.
- CSS와 스크립트는 신뢰된 표현 자산 경계를 통해서만 적용된다.
- 관련 단위·경계·통합 테스트와 전체 Gradle build가 통과한다.
- 단일 OCI 이미지와 Actuator 엔드포인트가 유지된다.

## 26. 외부 계약 참고 자료

- [Notion API versioning](https://developers.notion.com/reference/versioning)
- [Notion Block object](https://developers.notion.com/reference/block)
- [Retrieve block children](https://developers.notion.com/reference/get-block-children)
- [Notion Rich text](https://developers.notion.com/reference/rich-text)
- [Notion Parent](https://developers.notion.com/reference/parent-object)
- [Retrieve a database](https://developers.notion.com/reference/retrieve-database)
- [Notion Data source](https://developers.notion.com/reference/data-source)
- [Query a data source](https://developers.notion.com/reference/query-a-data-source)
- [Query large data sources](https://developers.notion.com/guides/data-apis/query-large-data-sources)
- [Notion API `2026-03-11` upgrade guide](https://developers.notion.com/guides/get-started/upgrade-guide-2026-03-11)
- [Notion API `2025-09-03` data source upgrade guide](https://developers.notion.com/guides/get-started/upgrade-guide-2025-09-03)

외부 문서는 변경될 수 있으므로 구현 시점에는 고정한 `Notion-Version`의 응답 고정 데이터와 공식 변경 기록을 다시 확인한다. 공식 문서의 변화가 이 설계와 충돌하면 먼저 이 문서와 ADR을 갱신한다.
