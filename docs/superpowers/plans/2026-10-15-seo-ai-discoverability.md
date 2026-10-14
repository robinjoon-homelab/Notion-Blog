# 검색엔진·AI 발견성 구현 계획

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** `/robots.txt`, `/sitemap.xml`, `/llms.txt`를 추가하고, 글 HTML `<head>`에 canonical·글별 description·Open Graph·JSON-LD를 넣는다.

**Architecture:** 공개 색인 유스케이스(`GetPublicIndexUseCase`)를 새로 만들고, RSS와 공유하는 활성 범위 검증은 `ActivePublicationScopeReader`로 옮긴다. 웹 어댑터에서 조건부 응답 처리를 `ConditionalRepresentation`으로 뽑아 RSS·sitemap·llms.txt가 함께 쓴다. 글 페이지는 기존 조회 결과에 게시·수정 시각과 루트 여부를 더해 head 메타를 만든다.

**Tech Stack:** Kotlin 2.3, Spring Boot 4.1 MVC, Thymeleaf, Exposed 1.5, Jackson 3 (`tools.jackson`), MockK, AssertJ, Spring REST Docs.

**Spec:** [docs/superpowers/specs/2026-10-14-seo-ai-discoverability-design.md](../specs/2026-10-14-seo-ai-discoverability-design.md)

## Global Constraints

- 공개 판정: 활성 revision 구성원 + `PUBLISHED` + 렌더링 가능한 스냅샷. 헤더·푸터 레이아웃 문서는 목록에서 제외한다.
- 루트 글 URL은 항상 `{base}/`. `/posts/{rootId}`를 목록·canonical에 쓰지 않는다.
- 수정 시각은 스냅샷 `capturedAt`. `SourceRevision`을 시각으로 해석하지 않는다.
- JSON-LD `author`를 넣지 않는다. 새 환경 변수·설정 스키마를 추가하지 않는다.
- JSON-LD 안의 `<`, `>`, `&`는 `<`, `>`, `&`으로 바꾼다. CSP는 바꾸지 않는다.
- detekt: 함수 매개변수 4개, 생성자 6개, 함수 30줄 이하. 웹 어댑터에서 `tools.jackson.databind.JsonNode`를 import하지 않는다.
- 테스트는 결과만 검증한다. MockK `verify`·호출 기록 금지. 통합·동시성 테스트는 추가하지 않는다.
- 공개 경로 목록은 `TargetArchitectureBoundaryTest.allowedWebRoutes`와 `docs/ddd-architecture.md`를 함께 바꾼다.

## Review Focus

1. 공개 주소(`BLOG_PUBLIC_BASE_URL`)가 비어 있을 때: robots.txt는 Sitemap 줄 없이 200, sitemap·llms.txt는 503, 글 head는 canonical·og:url·JSON-LD url 생략. Task 5·6·7 테스트로 고정한다.
2. 글 제목에 `</script>`, `&`, `]` 같은 문자가 있을 때: JSON-LD는 태그를 깨지 않고, llms.txt 링크 텍스트는 깨지지 않고, sitemap XML은 유효하다. Task 6·7 테스트로 고정한다.
3. 루트 글이 미게시이거나 스냅샷이 없을 때: 색인에서 루트 항목만 빠지고 나머지 글은 남는다. Task 2 테스트로 고정한다.
4. 헤더 문서가 루트와 같은 문서로 지정됐을 때: 루트는 색인에 남는다. Task 2 테스트로 고정한다.
5. `If-None-Match: *`가 503 상황에서 304로 바뀌지 않는다. Task 5 테스트로 고정한다.

---

### Task 1: 저장소 조회 확장 (게시 시각·수정 시각·전체 목록)

**Files:**
- Modify: `src/main/kotlin/xyz/robinjoon/notionblog/blog/application/model/StoredPost.kt`
- Modify: `src/main/kotlin/xyz/robinjoon/notionblog/blog/application/model/PostFeedEntry.kt`
- Modify: `src/main/kotlin/xyz/robinjoon/notionblog/blog/application/port/output/persistence/PostRepository.kt`
- Modify: `src/main/kotlin/xyz/robinjoon/notionblog/blog/adapter/outbound/persistence/exposed/ExposedPostFeedQuery.kt`
- Modify: `src/main/kotlin/xyz/robinjoon/notionblog/blog/adapter/outbound/persistence/exposed/ExposedPostRepository.kt`
- Test: `src/test/kotlin/xyz/robinjoon/notionblog/blog/adapter/outbound/persistence/exposed/post/ExposedPostFeedRepositoryTest.kt`, `ExposedPostRepositoryTest.kt`

**Interfaces:**
- Produces: `StoredPost(post, sourceRevision, capturedAt, firstPublishedAt: Instant?)`, `PostFeedEntry(post, firstPublishedAt: Instant, lastModifiedAt: Instant)`, `PostRepository.findPublishedPosts(publicationId: PublicationId, excludedPostIds: Set<PostId>): List<PostFeedEntry>`

- [ ] **Step 1: 실패 테스트** — `ExposedPostFeedRepositoryTest`에 추가:
  - `lists every published member in feed order with snapshot capture time` — 25개 글을 넣고 `findPublishedPosts(publicationId, setOf(root))`가 25개 모두를 최초 게시 내림차순·ID 오름차순으로 반환하며 각 `lastModifiedAt`이 스냅샷 `capturedAt`과 같음.
  - 기존 `selects only published snapshotted members...`에 `findPublishedPosts` 결과도 같은지 단언.
  - `ExposedPostRepositoryTest`의 `find` 결과에 `firstPublishedAt`(기록 전 `null`, 기록 후 값) 단언.
- [ ] **Step 2:** `./gradlew :test --tests '*ExposedPost*RepositoryTest'` → 컴파일 실패 확인.
- [ ] **Step 3: 구현** — 쿼리 select에 `PostSnapshotTable.capturedAt` 추가. 저장소는 정렬·매핑을 private 함수로 공유:

```kotlin
override fun findRecentPublishedPosts(publicationId: PublicationId, excludedPostIds: Set<PostId>, limit: Int) =
    publishedPosts(publicationId, excludedPostIds).limit(limit).map { it.toFeedEntry() }

override fun findPublishedPosts(publicationId: PublicationId, excludedPostIds: Set<PostId>) =
    publishedPosts(publicationId, excludedPostIds).map { it.toFeedEntry() }

private fun publishedPosts(publicationId: PublicationId, excludedPostIds: Set<PostId>): Query {
    val query = publishedPostFeedQuery(publicationId)
    if (excludedPostIds.isNotEmpty()) {
        query.andWhere { PostTable.postId notInList excludedPostIds.map(PostId::value) }
    }
    return query.orderBy(PostTable.firstPublishedAt to SortOrder.DESC, PostTable.postId to SortOrder.ASC)
}

private fun ResultRow.toFeedEntry() =
    PostFeedEntry(toPost(), requireNotNull(this[PostTable.firstPublishedAt]).toInstant(), this[PostSnapshotTable.capturedAt].toInstant())
```

  `find`는 `row[PostTable.firstPublishedAt]?.toInstant()`를 넣는다. 다른 `StoredPost`·`PostFeedEntry` 생성 지점(테스트 포함)은 새 인자를 채운다.
- [ ] **Step 4:** 같은 명령으로 통과 확인.

### Task 2: 활성 범위 읽기 분리와 공개 색인 유스케이스

**Files:**
- Create: `application/model/ActivePublicationScope.kt`, `application/model/PublicIndex.kt`(`PublicIndex`, `PublicIndexEntry`), `application/model/PublicIndexLookupResult.kt`
- Create: `application/port/input/GetPublicIndexUseCase.kt`
- Create: `application/service/ActivePublicationScopeReader.kt`, `application/service/GetPublicIndexService.kt`
- Modify: `application/service/GetPostFeedService.kt`, `application/port/output/diagnostics/SnapshotFailureReporter.kt`(`INDEX_LOOKUP`), `config/QueryConfig.kt`
- Test: `GetPostFeedServiceTest.kt`(생성자만 변경), `GetPublicIndexServiceTest.kt`(신규)

**Interfaces:**
- Consumes: Task 1의 `findPublishedPosts`, `PostFeedEntry.lastModifiedAt`
- Produces:

```kotlin
data class ActivePublicationScope(val site: SiteConfiguration, val publicationId: PublicationId, val rootPostId: PostId, val layoutPostIds: Set<PostId>)
class ActivePublicationScopeReader(posts: PostRepository, publications: PublicationRepository, siteConfigurations: SiteConfigurationRepository) {
    fun read(): ActivePublicationScope?
}
data class PublicIndex(val metadata: SiteMetadata, val entries: List<PublicIndexEntry>)
data class PublicIndexEntry(val post: Post, val root: Boolean, val firstPublishedAt: Instant, val lastModifiedAt: Instant)
sealed interface PublicIndexLookupResult { data class Found(val index: PublicIndex); data object ContentUnavailable }
interface GetPublicIndexUseCase { fun get(): PublicIndexLookupResult }
class GetPublicIndexService(scopes: ActivePublicationScopeReader, posts: PostRepository, snapshotFailures: SnapshotFailureReporter) : GetPublicIndexUseCase
```

- [ ] **Step 1: 실패 테스트** — `GetPublicIndexServiceTest`(MockK 출력 포트, 실제 `ActivePublicationScopeReader`):
  - 루트가 목록 중간에 있어도 첫 항목(`root = true`)이 되고 나머지 순서는 유지된다.
  - 헤더·푸터 바인딩은 `findPublishedPosts`의 제외 목록에 들어가고 루트는 제외 목록에 들어가지 않는다(제외 집합별로 다른 응답을 준비해 결과로 확인).
  - 헤더가 루트와 같은 문서여도 루트 항목이 남는다.
  - 루트가 결과에 없으면 루트 항목 없이 나머지만 반환한다.
  - 설정 없음·publication 불일치·활성 revision 불일치 → `ContentUnavailable`.
  - `findPublishedPosts`가 `SnapshotContentException` → `ContentUnavailable`.
- [ ] **Step 2:** `./gradlew :test --tests '*GetPublicIndexServiceTest' --tests '*GetPostFeedServiceTest'` → 실패 확인.
- [ ] **Step 3: 구현** — `GetPostFeedService`의 범위 검증을 `ActivePublicationScopeReader.read()`로 옮기고, 피드는 `scope.layoutPostIds + scope.rootPostId`를 제외한다. 색인 서비스:

```kotlin
@Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
override fun get(): PublicIndexLookupResult {
    val scope = scopes.read() ?: return PublicIndexLookupResult.ContentUnavailable
    return try {
        val published = posts.findPublishedPosts(scope.publicationId, scope.layoutPostIds - scope.rootPostId)
        val (root, others) = published.partition { it.post.id == scope.rootPostId }
        val entries = root.map { it.toIndexEntry(true) } + others.map { it.toIndexEntry(false) }
        PublicIndexLookupResult.Found(PublicIndex(scope.site.metadata, entries))
    } catch (failure: SnapshotContentException) {
        snapshotFailures.report(failure, SnapshotFailureOperation.INDEX_LOOKUP, null)
        PublicIndexLookupResult.ContentUnavailable
    }
}
```

  `QueryConfig`에 `activePublicationScopeReader()`와 `getPublicIndexUseCase()` 빈을 등록하고 `getPostFeedUseCase`가 reader를 받게 한다.
- [ ] **Step 4:** 같은 명령으로 통과 확인. 기존 `GetPostFeedServiceTest` 단언은 그대로 둔다.

### Task 3: 글 조회 결과에 게시·수정 시각과 루트 여부 추가

**Files:**
- Modify: `application/model/PostLookupResult.kt`, `application/model/BlogPage.kt`, `application/service/GetPublishedPostService.kt`, `application/service/GetBlogPageService.kt`
- Test: `GetPublishedPostServiceTest.kt`, `GetBlogPageServiceTest.kt`

**Interfaces:**
- Produces: `PostLookupResult.Found(post, firstPublishedAt: Instant?, lastModifiedAt: Instant, root: Boolean)`, `BlogPage(..., links, firstPublishedAt: Instant?, lastModifiedAt: Instant, root: Boolean)`

- [ ] **Step 1: 실패 테스트** — `GetPublishedPostServiceTest`: `getRoot()`는 `root = true`, 같은 ID를 `get()`으로 조회해도 `root = true`, 다른 글은 `false`; 저장본의 `capturedAt`·`firstPublishedAt`이 결과에 실린다. `GetBlogPageServiceTest`: 결과 `BlogPage`에 세 값이 그대로 전달된다.
- [ ] **Step 2:** `./gradlew :test --tests '*GetPublishedPostServiceTest' --tests '*GetBlogPageServiceTest'` → 실패 확인.
- [ ] **Step 3: 구현** — `Found(it.post, it.firstPublishedAt, it.capturedAt, postId == publication.rootPostId)`. `GetBlogPageService.buildPage`는 `Found` 전체를 받아 `BlogPage`에 세 값을 넣는다.
- [ ] **Step 4:** 통과 확인.

### Task 4: 웹 공통 요소 정리 (요약 추출기 이름, URL, 조건부 응답)

**Files:**
- Rename: `adapter/inbound/web/RssSummaryExtractor.kt` → `PostSummaryExtractor.kt` (테스트도 `PostSummaryExtractorTest.kt`)
- Create: `adapter/inbound/web/ConditionalRepresentation.kt`
- Modify: `adapter/inbound/web/PublicBlogUrls.kt`, `adapter/inbound/web/RssFeedController.kt`, `adapter/inbound/web/RssFeedRenderer.kt`, `config/WebConfig.kt`
- Test: `PublicBlogUrlsTest.kt`, `PostSummaryExtractorTest.kt`, 기존 `RssFeedControllerTest.kt`

**Interfaces:**
- Produces:

```kotlin
class PostSummaryExtractor { fun extract(post: Post): String; fun bodySummary(post: Post): String? }
class PublicBlogUrls { val sitemapUrl: String?; val llmsTxtUrl: String?; fun pageUrl(postId: PostId, root: Boolean): String? }
internal object ConditionalRepresentation {
    fun respond(request: ServletWebRequest, bytes: ByteArray, mediaType: MediaType): ResponseEntity<ByteArray>
    fun unavailable(): ResponseEntity<ByteArray>
}
```

- [ ] **Step 1: 실패 테스트** — `PublicBlogUrlsTest`: sitemap·llms URL, `pageUrl(id, true)`는 루트 URL, `pageUrl(id, false)`는 `/posts/{uuid}`, 공개 주소 없으면 모두 `null`. `PostSummaryExtractorTest`: 본문이 비면 `bodySummary`는 `null`, `extract`는 제목.
- [ ] **Step 2:** `./gradlew :test --tests '*PublicBlogUrlsTest' --tests '*PostSummaryExtractorTest'` → 실패 확인.
- [ ] **Step 3: 구현** — `extract(post) = bodySummary(post) ?: 제목 폴백` 형태로 바꾸고 동작은 유지. `RssFeedController`의 ETag·조건부·503 코드를 `ConditionalRepresentation`으로 옮긴다.
- [ ] **Step 4:** 위 명령과 `--tests '*RssFeed*'`로 통과 확인(기존 RSS 테스트가 그대로 통과해야 한다).

### Task 5: robots.txt

**Files:**
- Create: `adapter/inbound/web/RobotsTxtController.kt`
- Test: `RobotsTxtControllerTest.kt`(MockMvc 단독)

- [ ] **Step 1: 실패 테스트** — 공개 주소 있음: 200, `text/plain;charset=UTF-8`, 본문 `User-agent: *\nAllow: /\n\nSitemap: https://blog.example/sitemap.xml\n`. 없음: Sitemap 줄 없이 `User-agent: *\nAllow: /\n`.
- [ ] **Step 2:** 실패 확인.
- [ ] **Step 3: 구현**

```kotlin
@RestController
class RobotsTxtController(private val urls: PublicBlogUrls) {
    @GetMapping("/robots.txt", produces = ["text/plain"])
    fun robots(): ResponseEntity<ByteArray> {
        val body = buildString {
            append("User-agent: *\nAllow: /\n")
            urls.sitemapUrl?.let { append("\nSitemap: ").append(it).append('\n') }
        }
        return ResponseEntity.ok().contentType(MediaType("text", "plain", Charsets.UTF_8)).body(body.toByteArray(Charsets.UTF_8))
    }
}
```

- [ ] **Step 4:** 통과 확인.

### Task 6: sitemap.xml과 llms.txt

**Files:**
- Create: `adapter/inbound/web/SitemapRenderer.kt`, `SitemapController.kt`, `LlmsTxtRenderer.kt`, `LlmsTxtController.kt`
- Modify: `config/WebConfig.kt`
- Test: `SitemapRendererTest.kt`, `LlmsTxtRendererTest.kt`, `PublicIndexControllerTest.kt`(두 컨트롤러의 200·304·503)

**Interfaces:**
- Consumes: Task 2 `GetPublicIndexUseCase`, Task 4 `ConditionalRepresentation`, `PublicBlogUrls.pageUrl`, `PostSummaryExtractor`
- Produces: `SitemapRenderer.render(index: PublicIndex, urls: PublicBlogUrls): ByteArray`, `LlmsTxtRenderer(summaries).render(index, urls): ByteArray`

- [ ] **Step 1: 실패 테스트**
  - Sitemap: 루트 `<loc>`가 `https://blog.example/`이고 첫 항목, 글은 `/posts/{uuid}`, `<lastmod>`는 초 단위 UTC(`2026-10-05T03:00:00Z`), 네임스페이스 `http://www.sitemaps.org/schemas/sitemap/0.9`, 빈 색인은 빈 `urlset`.
  - llms.txt: `# 사이트명`, `> 설명`(없으면 생략), `## Posts` 아래 `- [제목](URL): 요약`, 제목 `a [b] \c` → `a \[b\] \\c`, 줄바꿈이 섞인 제목은 한 줄, `## Optional`의 RSS·Sitemap 링크.
  - 컨트롤러: 공개 주소 없음 → 503 `no-store`; `ContentUnavailable` + `If-None-Match: *` → 503; 정상 → 200·`no-cache`·ETag; 같은 ETag → 304.
- [ ] **Step 2:** 실패 확인.
- [ ] **Step 3: 구현** — sitemap은 `XMLStreamWriter`로 `urlset/url/loc,lastmod`를 쓴다. llms.txt는 `StringBuilder`로 쓰고 `RssXmlText.clean` 후 공백을 한 칸으로 합친다. 컨트롤러는 RSS와 같은 순서(공개 주소 확인 → 조회 → 렌더 → `ConditionalRepresentation.respond`). 미디어 타입은 `application/xml;charset=UTF-8`, `text/plain;charset=UTF-8`.
- [ ] **Step 4:** 통과 확인.

### Task 7: 글 HTML head (canonical·description·Open Graph·JSON-LD)

**Files:**
- Create: `adapter/inbound/web/JsonLdWriter.kt`
- Modify: `adapter/inbound/web/view/PostPageView.kt`(`canonicalUrl`, `openGraph: OpenGraphView`, `jsonLd: String`), `PostPageViewAssembler.kt`, `templates/blog/post.html`, `config/WebConfig.kt`
- Test: `JsonLdWriterTest.kt`, `PostPageViewAssemblerTest.kt`

**Interfaces:**
- Consumes: Task 3 `BlogPage.root/firstPublishedAt/lastModifiedAt`, Task 4 `PublicBlogUrls.pageUrl`, `PostSummaryExtractor.bodySummary`
- Produces:

```kotlin
data class OpenGraphView(val type: String, val locale: String, val url: String?, val publishedTime: String?, val modifiedTime: String?)
class JsonLdWriter { fun write(page: BlogPage, description: String?, urls: PublicBlogUrls): String }
class PostPageViewAssembler(clock: Clock, urls: PublicBlogUrls = PublicBlogUrls(null), summaries: PostSummaryExtractor = PostSummaryExtractor(), jsonLd: JsonLdWriter = JsonLdWriter())
```

- [ ] **Step 1: 실패 테스트**
  - `JsonLdWriterTest`: 제목 `</script><b>&` → 결과에 `<`, `>`, `&`가 없고 파싱하면 원래 제목. 루트는 `@type: WebSite`(`name`, `description`, `inLanguage`, `url`), 글은 `BlogPosting`(`headline`, `description`, `datePublished`, `dateModified`, `inLanguage`, `url`, `mainEntityOfPage`, `isPartOf`), 최초 게시 시각 없으면 `datePublished` 없음, 공개 주소 없으면 `url`·`mainEntityOfPage` 없음, `author` 없음.
  - `PostPageViewAssemblerTest`(Thymeleaf 렌더 포함): 루트 canonical·og:url이 `https://blog.example/`, 글은 `/posts/{uuid}`; 공개 주소 없으면 canonical·og:url 없음; description은 본문 요약, 본문이 비면 사이트 설명; og:type 루트 `website`·글 `article`; og:locale `ko_KR`; 루트에는 `article:*` 없음; `twitter:card` summary; `<script type="application/ld+json">` 존재.
- [ ] **Step 2:** `./gradlew :test --tests '*JsonLdWriterTest' --tests '*PostPageViewAssemblerTest'` → 실패 확인.
- [ ] **Step 3: 구현** — `JsonLdWriter`는 `JsonMapper.builder().build()`로 `Map`을 직렬화한 뒤 세 문자를 치환한다. 템플릿 head:

```html
<link th:if="${page.canonicalUrl != null}" rel="canonical" th:href="${page.canonicalUrl}">
<meta property="og:type" th:content="${page.openGraph.type}">
<meta property="og:title" th:content="${page.title}">
<meta th:if="${page.description != null}" property="og:description" th:content="${page.description}">
<meta property="og:site_name" th:content="${page.siteName}">
<meta property="og:locale" th:content="${page.openGraph.locale}">
<meta th:if="${page.openGraph.url != null}" property="og:url" th:content="${page.openGraph.url}">
<meta th:if="${page.openGraph.publishedTime != null}" property="article:published_time" th:content="${page.openGraph.publishedTime}">
<meta th:if="${page.openGraph.modifiedTime != null}" property="article:modified_time" th:content="${page.openGraph.modifiedTime}">
<meta name="twitter:card" content="summary">
<script type="application/ld+json" th:utext="${page.jsonLd}"></script>
```

  `WebConfig`의 `postPageViewAssembler`는 `PublicBlogUrls`·`PostSummaryExtractor`·`JsonLdWriter` 빈을 받는다.
- [ ] **Step 4:** 통과 확인.

### Task 8: HTTP 계약·문서·경로 규칙

**Files:**
- Modify: `src/test/kotlin/xyz/robinjoon/notionblog/documentation/BlogHttpDocumentationTest.kt`, `src/docs/asciidoc/index.adoc`, `docs/ddd-architecture.md`, `src/test/kotlin/xyz/robinjoon/notionblog/architecture/TargetArchitectureBoundaryTest.kt`

- [ ] **Step 1:** `BlogHttpDocumentationTest`에 robots(`robots-found`), sitemap(`sitemap-found`, `sitemap-not-modified`, `sitemap-unavailable`), llms(`llms-found`, `llms-unavailable`) 문서 테스트와 글 페이지 head 단언(canonical·og:type·JSON-LD)을 추가한다.
- [ ] **Step 2:** `allowedWebRoutes`에 `/robots.txt`, `/sitemap.xml`, `/llms.txt`를 추가하고 `docs/ddd-architecture.md` 경로 목록을 갱신한다.
- [ ] **Step 3:** `index.adoc`에 "검색엔진·AI 색인" 절과 스니펫 include를 추가한다.
- [ ] **Step 4:** `./gradlew :test --tests '*BlogHttpDocumentationTest' --tests '*TargetArchitectureBoundaryTest'` 통과 확인.

### Task 9: 전체 검증과 마무리

- [ ] `./gradlew ktlintFormat` 후 `./gradlew build` 통과, `git diff`로 자동 포맷 결과 확인.
- [ ] `build/docs/asciidoc/index.html`에 세 경로 절이 있는지 확인.
- [ ] 커밋 → Orca 오케스트레이션으로 Codex 리뷰 → 지적 반영을 이상 없을 때까지 반복 → 푸시·PR.
