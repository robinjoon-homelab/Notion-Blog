# 검색엔진·AI 발견성 설계

- 작성일: 2026-10-14
- 상태: 승인됨
- 배경 설명 문서: [검색엔진·AI 최적화 원리와 Notion-Blog 적용 계획](https://claude.ai/artifact/LFU9s4Fm95qjxL33dUQkSX)

## 목표

검색엔진과 AI 크롤러가 공개 글을 발견하고 올바르게 이해하도록 다음을 추가한다.

- `GET /robots.txt`, `GET /sitemap.xml`, `GET /llms.txt`
- 글 HTML `<head>`의 canonical, 글별 description, Open Graph·Twitter 카드, JSON-LD

## 확정된 결정

| 항목 | 결정 |
| --- | --- |
| AI 크롤러 정책 | 학습·검색·사용자 요청 봇 모두 허용. 봇별 규칙을 두지 않는다 |
| 범위 | 기본 SEO + `llms.txt`. 글 Markdown 버전은 만들지 않는다 |
| 구조 | 공개 색인 유스케이스 신설(A안). RSS 조회에 옵션을 추가하지 않는다 |
| 저자 | JSON-LD `author`를 넣지 않는다. 설정 스키마를 바꾸지 않는다 |

## 범위 밖

`og:image`(커버 미수집), slug URL(기존 업무 규칙상 금지), 글 Markdown 버전, sitemap 분할(50,000 URL 미만 전제), `priority`·`changefreq`, 검색엔진 등록 자동화, 오류 페이지 메타 변경.

## 공개 계약

모든 목록은 기존 공개 판정을 따른다. 활성 revision의 구성원이고 `PUBLISHED`이며 렌더링 가능한 스냅샷이 있는 글만 포함한다. 헤더·푸터로 지정된 레이아웃 문서는 RSS와 같이 제외한다.

### 공통 항목 순서와 URL

- 루트 글을 첫 항목으로 두고 URL은 `{base}/`로 쓴다. `/posts/{rootId}`를 따로 넣지 않는다.
- 나머지는 최초 게시 시각 내림차순, 같으면 `PostId` 오름차순으로 정렬한다. URL은 `{base}/posts/{uuid}`다.
- `{base}`는 `BLOG_PUBLIC_BASE_URL`이다.

### `GET /robots.txt`

- 항상 200, `text/plain; charset=UTF-8`. DB를 읽지 않는다.
- 본문: `User-agent: *`, `Allow: /`. 공개 주소가 있으면 `Sitemap: {base}/sitemap.xml` 줄을 추가한다.

### `GET /sitemap.xml`

- 200, `application/xml; charset=UTF-8`, sitemaps.org 0.9 `urlset`.
- 항목마다 `<loc>`와 `<lastmod>`(스냅샷 `capturedAt`, UTC ISO-8601)를 쓴다.
- 공개 주소가 없거나 공개 색인을 사용할 수 없으면 503과 `no-store`를 반환한다(RSS와 같음).
- ETag(본문 SHA-256), `no-cache`, `If-None-Match` 일치 시 304를 반환한다(RSS와 같음).

### `GET /llms.txt`

- 200, `text/plain; charset=UTF-8`. 실패·캐시 규칙은 sitemap과 같다.
- 형식([llmstxt.org](https://llmstxt.org/) 제안):

```markdown
# {siteName}

> {defaultDescription}

## Posts

- [{제목}]({URL}): {요약}

## Optional

- [RSS]({base}/feed.xml): 최근 글 구독 피드
- [Sitemap]({base}/sitemap.xml): 전체 공개 URL 목록
```

- `defaultDescription`이 없으면 인용 블록을 생략한다.
- 제목과 요약은 공백·줄바꿈을 한 칸으로 합치고, 제목의 `\`, `[`, `]`는 백슬래시로 이스케이프한다.

### 글 HTML `<head>`

| 태그 | 값 | 공개 주소 없음 |
| --- | --- | --- |
| `link rel=canonical` | 루트는 `{base}/`, 그 외 `{base}/posts/{uuid}` | 생략 |
| `meta name=description` | 글 요약. 본문 텍스트가 없으면 사이트 기본 설명 | 유지 |
| `og:type` | 루트 `website`, 그 외 `article` | 유지 |
| `og:title`, `og:description`, `og:site_name`, `og:locale` | 글 제목, 위 description, 사이트명, `languageTag`의 `-`를 `_`로 바꾼 값 | 유지 |
| `og:url` | canonical과 같음 | 생략 |
| `article:published_time`, `article:modified_time` | 최초 게시 시각, `capturedAt`. 루트에는 넣지 않는다 | 유지 |
| `twitter:card` | `summary` | 유지 |
| JSON-LD | 루트 `WebSite`, 그 외 `BlogPosting` | `url`·`mainEntityOfPage` 생략 |

- `BlogPosting` 필드: `headline`, `description`, `datePublished`, `dateModified`, `inLanguage`, `url`, `mainEntityOfPage`, `isPartOf`(`WebSite` 이름·URL).
- `WebSite` 필드: `name`, `description`, `inLanguage`, `url`.
- 최초 게시 시각이 없으면 `datePublished`와 `article:published_time`을 생략한다.
- JSON-LD는 Jackson으로 직렬화하고 `<`, `>`, `&`를 `\u003c`, `\u003e`, `\u0026`으로 바꿔 `<script type="application/ld+json">` 안에 넣는다. 실행되지 않는 데이터 블록이라 현재 CSP를 바꾸지 않는다.

## 구성 요소

### 애플리케이션

- `GetPublicIndexUseCase.get(): PublicIndexLookupResult`
  - `Found(PublicIndex)` 또는 `ContentUnavailable`.
  - `PublicIndex(metadata: SiteMetadata, entries: List<PublicIndexEntry>)`.
  - `PublicIndexEntry(post: Post, root: Boolean, firstPublishedAt: Instant, lastModifiedAt: Instant)`.
- `GetPublicIndexService`: `REPEATABLE_READ` 읽기 전용 트랜잭션 하나에서 설정·활성 범위·게시 상태·스냅샷을 읽는다. `SnapshotContentException`이 나면 보고 후 `ContentUnavailable`을 반환한다. 보고 작업 종류로 `INDEX_LOOKUP`을 추가한다.
- `ActivePublicationScopeReader`(신규, 같은 패키지): `GetPostFeedService`에 있는 설정·publication·활성 revision 일치 검증과 레이아웃 문서 제외 계산을 옮긴다. 결과는 범위 값 또는 `null`이다. `GetPostFeedService`와 `GetPublicIndexService`가 함께 쓰고, RSS 동작은 바꾸지 않는다.
- `PostRepository.findPublishedPosts(publicationId, excludedPostIds): List<PostFeedEntry>`: 기존 `publishedPostFeedQuery`를 재사용하고 개수 제한 없이 정렬해 반환한다.
- `PostFeedEntry`에 `lastModifiedAt`(스냅샷 `capturedAt`)을 추가한다. RSS 렌더러는 이 값을 쓰지 않는다.
- 글 페이지:
  - `StoredPost`에 `firstPublishedAt: Instant?`를 추가한다.
  - `PostLookupResult.Found`에 `firstPublishedAt`, `lastModifiedAt`, `root: Boolean`을 추가한다. `root`는 `GetPublishedPostService`가 이미 읽은 publication의 루트와 비교해 정한다.
  - `BlogPage`에 같은 세 값을 추가한다.

### 수정 시각의 출처

`lastmod`·`dateModified`는 스냅샷 `capturedAt`을 쓴다. `ApplyImportedPostService`는 `sourceRevision`이 바뀔 때만 스냅샷을 다시 쓰므로, 이 값은 원문이 바뀐 시점만 반영한다. `SourceRevision` 문자열을 시각으로 해석하지 않는다. 출처별 의미를 가진 불투명 값이기 때문이다. 스냅샷 복구로 다시 쓸 때도 갱신되지만 드물어 허용한다.

### 웹 어댑터

- `PostSummaryExtractor`: `RssSummaryExtractor`의 이름을 바꾼다. RSS·llms.txt·HTML description이 함께 쓰므로 RSS 전용 이름을 버린다. 동작은 바꾸지 않는다.
- `ConditionalRepresentation`: `RssFeedController`의 ETag 계산, 조건부 요청(와일드카드 포함) 판정, 503 응답을 옮긴다. RSS·sitemap·llms.txt 컨트롤러가 함께 쓴다.
- `RobotsTxtController`, `SitemapController`·`SitemapRenderer`, `LlmsTxtController`·`LlmsTxtRenderer`를 추가한다. 렌더러는 URL 생성에 `PublicBlogUrls`를 쓴다.
- `PublicBlogUrls`에 `sitemapUrl`, `llmsTxtUrl`, `pageUrl(postId: PostId, root: Boolean): String?`을 추가한다. `root`가 참이면 `rootUrl`, 아니면 `/posts/{uuid}`이고 공개 주소가 없으면 `null`이다.
- `PostPageView`에 `canonicalUrl`, `openGraph`(값 묶음), `jsonLd: String?`를 추가한다. `PostPageViewAssembler`가 `PublicBlogUrls`, `PostSummaryExtractor`, `JsonLdWriter`(신규)로 채운다.
- `templates/blog/post.html`의 `<head>`에 태그를 추가한다.

### 설정·빈

`QueryConfig`에 `ActivePublicationScopeReader`, `GetPublicIndexUseCase`를 등록한다. `WebConfig`에 새 렌더러·컨트롤러 의존과 `JsonLdWriter`를 등록한다. 새 환경 변수는 없다.

## 문서 변경

- `docs/ddd-architecture.md`의 "공개와 동기화 규칙"에 있는 경로 목록에 `/robots.txt`, `/sitemap.xml`, `/llms.txt`를 추가한다. 공개 판정과 레이아웃 제외 규칙이 같다는 점도 적는다.
- REST Docs 설명과 `src/docs/asciidoc/index.adoc` 스니펫 연결을 추가한다.

## 테스트

[테스트 범위](../../development.md#테스트-범위) 기준을 따른다.

| 대상 | 방식 | 확인할 결과 |
| --- | --- | --- |
| `GetPublicIndexService` | MockK 출력 포트 | 루트가 첫 항목, 레이아웃 제외, 설정·revision 불일치 시 `ContentUnavailable`, 스냅샷 오류 시 `ContentUnavailable` |
| `GetPostFeedService` | 기존 테스트 유지 | 범위 검증을 옮긴 뒤에도 기존 결과가 같음 |
| `ExposedPostRepository.findPublishedPosts` | H2 어댑터 테스트 | 미게시·비활성 revision·제외 ID가 빠지고 `capturedAt`과 정렬이 맞음 |
| `SitemapRenderer`, `LlmsTxtRenderer` | 순수 단위 | XML·Markdown 이스케이프, 루트 URL, 설명 생략, 순서 |
| `JsonLdWriter` | 순수 단위 | `</script>`가 들어간 제목이 이스케이프됨, 루트·글 타입별 필드 |
| `PostPageViewAssembler` | 순수 단위 | 루트 canonical이 `/`, 공개 주소 없을 때 생략, 요약이 비면 사이트 설명 |
| HTTP 계약 | `BlogHttpDocumentationTest` | 세 경로의 200·503·304, 미디어 타입, 글 페이지 head 태그, REST Docs 스니펫 |

## 완료 기준

- `./gradlew build`가 통과하고 자동 포맷 이후 diff를 확인한다.
- 생성된 REST Docs에 세 경로가 나타난다.
- 실제 배포와 Search Console 등록은 이 작업에 포함하지 않는다.
