# 아키텍처와 도메인 경계

- 검사기의 범용 허용 범위를 앱에 임의로 적용하지 않는다.
- 코드 작성은 [품질 지침](code-quality.md), 실행은 [개발 지침](development.md#실행과-검증), 검사기 변경은 [유지보수 지침](quality-maintenance.md)을 따른다.

## 새 기능을 분류하는 절차

**업무 사실 → BC → 불변식·생명주기 → 어그리게이트 → 트랜잭션·패키지** 순서로 판단한다.

- **BC:** 업무 모델과 용어가 같은 뜻으로 통하는 범위. 업무 목적·의미·결정 책임·변경 이유로 정한다. 결정 책임은 실제 사람·개발팀 수가 아니라 규칙과 용어를 정하는 권한이다.
- **어그리게이트:** 한 루트가 소유한 상태의 일관성을 지키는 단위. 불변식·소유 관계·독립 생명주기·동시 변경으로 정한다. 한 BC에 여러 어그리게이트, 한 어그리게이트에 여러 엔티티·값 객체가 있을 수 있다.

개념의 근거는 [Evans의 DDD Reference](https://www.domainlanguage.com/wp-content/uploads/2016/05/DDD_Reference_2015-03.pdf)와 [Fowler의 Bounded Context](https://martinfowler.com/bliki/BoundedContext.html)를 따른다. 아래 업무 전제·배치·절차는 이 템플릿의 선택이다.

1. **명령·조회와 업무 규칙을 적는다.** 누가 무엇을 바꾸고 어떤 결과·실패·동시 요청을 허용하는지 확인한다. 클래스·테이블·API·화면 개수나 함께 읽는다는 사실로 경계를 추측하지 않는다.
2. **기존 BC부터 확인한다.** 같은 의미·결정 책임을 유지하면 기존 BC를 쓴다. 독립된 목적·모델이 필요한 구체적 이유가 있을 때 새 BC를 검토한다. 같은 단어의 의미 충돌은 증거지만 필수조건은 아니다. 다른 소비자·담당자·개발팀·향후 확장 가능성만으로 분리하거나 합치지 않는다.
3. **조회에 필요한 사실을 확인한다.** 이미 저장된 사실이면 기존 조회 또는 애플리케이션 모델·조회용 `Store`를 쓴다. 여러 루트의 조회가 새 루트를 뜻하지 않는다. 취소 시각처럼 없는 사실은 소유 모델에 기록하는 변경도 필요하다. 월별 집계가 접수일·취소일 중 무엇을 기준으로 하는지 모르면 질문한다.
4. **상태의 소유와 생명주기를 확인한다.** 기존 루트와 함께 존재하거나 그 불변식에 속하면 필드·값·자식 엔티티로 추가한다. 독립 생성·변경·종료와 자기 규칙이 있으면 같은 BC의 새 어그리게이트를 검토한다. 자식 ID만으로 분리하지 않는다. 큰 컬렉션·무제한 이력·잠금 경합은 소유·보존 요구부터 재검토한다.
5. **읽기·쓰기·판정 시점·실패 계약을 정한다.** 예제는 다른 루트를 읽고 하나를 변경한다. 여러 루트의 원자적 쓰기가 필요하면 불변식과 경계를 재검토하고 이유·잠금·롤백 계약을 기록한다. 한 트랜잭션이 필요하다고 BC·루트를 자동 병합하거나 연동 요구만으로 비동기 전달을 추가하지 않는다.
6. **코드·등록 목록·행동 테스트에 반영한다.** 경계를 바꾸는 요구라면 이전 선택을 덮어쓰지 않고 변경 이유를 남긴다.

확정된 사실로 판단 가능한 부분은 구현한다. **결과를 바꾸는 업무 사실이 빠졌을 때만 질문**한다. 기존 BC에도 맞지 않고 새 모델도 불명확하면 배치를 보류한다. Todo·공용 도메인을 임시 수용소로 쓰지 않는다. 별도 모델이 확인된 CRUD는 새 BC, 기존 모델의 CRUD 확장은 기존 BC, 업무 모델이 없는 기술 연결은 `config`·어댑터에 둔다.

BC가 정해져도 루트의 소유·생명주기가 미정이면 루트와 테이블은 확정하지 않는다. 예를 들어 점검 업무에 장비 ID가 등장해도 이 시스템이 장비를 생성·관리하는지 모르면 Equipment 루트를 만들지 않는다.

### 변경 설명에 남길 최소 기록

모든 변경은 위 절차로 소유 루트·불변식·실패·일관성 영향을 검토하고 [자동 검사와 완료 조건](#자동-검사와-완료-조건)을 충족한다. 소유 루트·선택 근거·검증할 규칙·실패·동시 실행 계약을 남기고, 새 BC·어그리게이트 또는 생명주기·일관성·연동 계약 변경에는 다음 항목을 모두 기록한다.

```text
요구와 확정된 업무 사실:
소유 BC와 선택 근거:
루트·소유 상태·불변식·독립 생명주기:
읽는 것 / 쓰는 것 / 판정 시점·실패 계약:
분류 결과와 채택하지 않은 대안의 이유:
변경할 패키지·경계 목록·행동 테스트:
불명확한 사실과 필요한 질문:
```

### 분류 사례

- **기존 루트:** 새 Notion 블록 종류 지원은 Post가 소유한 BlockTree의 콘텐츠 값과 스냅샷 형식을 바꾸는 일이다. 블록 저장소나 블록 루트를 만들지 않는다.
- **자식 값:** 게시 상태(`PostAvailability`)와 소스 바인딩은 게시글마다 하나이고 스냅샷과 함께 바뀌므로 Post 어그리게이트의 값으로 둔다.
- **같은 BC의 새 루트:** 표현 프로필은 사이트 설정 없이 등록·버전 관리되고 여러 설정에서 선택될 수 있으므로 SiteConfiguration과 다른 루트이며 `PresentationProfileRef`로 참조한다.
- **조회:** RSS 피드는 저장된 공개 범위·게시 상태·최초 게시 시각을 읽는 조회 모델이다. 피드용 루트나 저장 테이블을 만들지 않는다.
- **판단 보류:** 백링크·관련 글은 본문 링크를 영속 관계로 저장해야 하는 요구가 확인될 때 별도 조회 모델을 검토한다. 링크를 Post 간 관계로 저장하지 않는다.

## 현재 업무 규칙

블로그 운영자는 Notion 설정 데이터 소스와 Notion 페이지의 게시 여부로 공개할 글을 결정한다. 이 앱은 그 결정을 주기적으로 가져와 정규화한 스냅샷으로 저장하고, 웹 요청은 저장된 스냅샷만 제공한다. 웹 요청 중에 Notion을 호출하지 않는다.

| BC | 루트와 소유 상태 | 규칙 |
| --- | --- | --- |
| `blog` | Post: `PostId`, 제목·`BlockTree` 스냅샷. 소스 바인딩·게시 상태·최초 게시 시각을 함께 저장 | 제목은 공백만일 수 없다. `PUBLISHED`는 렌더링 가능한 스냅샷 저장과 같은 트랜잭션에서 기록한다. 최초 게시 시각은 처음 저장한 뒤 바꾸지 않는다. |
| `blog` | BlogPublication: `PublicationId`, 루트 `PostId`·활성 revision. revision과 구성원은 revision별로 저장·조회 | 루트와 활성 revision은 함께 설정한다. revision은 `STAGING → ACTIVE → SUPERSEDED` 또는 `STAGING → ABANDONED`. 활성화는 단일 루트·깊이·순환 없음·모든 구성원의 게시 상태 확인·`PUBLISHED` 구성원의 스냅샷을 검증한다. |
| `blog` | SiteConfiguration: 단일 사이트의 `SiteDocuments`(루트·헤더·푸터), 메타데이터, 표현 프로필 참조 | 루트 문서가 바뀌면 같은 publication의 새 revision을 수집한다. 실패하면 마지막 정상 설정과 활성 revision을 유지한다. |
| `blog` | PresentationProfile: `PresentationProfileRef`(ID·버전), 키, 토큰, 신뢰된 자산 | 저장한 버전은 바꾸지 않는다. 키마다 명시적으로 활성화한 현재 버전만 선택한다. |
| `blog` | SyncState: `SyncTarget`별 다음 갱신 시각, 마지막 성공 시각, 연속 실패 기록 | 실패 횟수와 마지막 실패 종류는 함께 존재한다. 성공하면 실패 기록을 지우고 실패하면 백오프한다. |

**하나의 BC:** 게시글·공개 범위·사이트 설정·표현 프로필·동기화는 같은 운영자가 같은 Notion 원본의 언어로 결정한다. 공개 판정이 세 루트의 사실을 함께 쓰고 동기화가 그 사실을 같은 트랜잭션에서 갱신하므로 하나의 `blog` BC로 유지한다. 테이블·서비스·어댑터 수는 분리 근거가 아니다.

**여러 루트:** Post는 문서마다 생성·갱신되고, BlogPublication은 구조 동기화마다 revision을 바꾸며, SiteConfiguration은 설정 동기화로, PresentationProfile은 배포 산출물로, SyncState는 대상별 일정으로 바뀐다. 생명주기와 동시 변경이 다르므로 별도 루트로 둔다. `SyncTarget`·`PublicationId`·`PostId`·`PresentationProfileRef`로 서로 참조한다.

### 공개와 동기화 규칙

- 글을 제공하려면 **활성 revision의 구성원이고, 게시 상태가 `PUBLISHED`이며, 렌더링 가능한 스냅샷이 있어야 한다.** 범위 밖·미게시·없는 ID·UUID가 아닌 ID는 404, 초기화 전이나 사용할 수 없는 설정·스냅샷은 503이다.
- HTML 경로는 `GET /`(활성 루트)와 `GET /posts/{postId}`(내부 UUID)뿐이다. RSS는 `GET /feed.xml`이다. 제목 기반 경로·slug·별칭을 만들지 않는다.
- 크롤러용 경로는 `GET /robots.txt`, `GET /sitemap.xml`, `GET /llms.txt`다. robots.txt는 모든 크롤러를 허용하고 저장 데이터를 읽지 않는다. sitemap과 llms.txt는 글 제공과 같은 공개 판정을 쓰고 RSS처럼 헤더·푸터 문서를 제외한다. 루트 글은 `/`로만 싣고 글 HTML의 canonical도 `/`를 가리킨다.
- 수정 시각(sitemap `lastmod`, JSON-LD `dateModified`)은 스냅샷 저장 시각이다. 원본 revision이 바뀔 때만 스냅샷을 다시 쓰므로 동기화 주기로 흔들리지 않는다. `SourceRevision`을 시각으로 해석하지 않는다.
- 공개 범위는 루트 문서와 부모 정보로 확인한 구조적 자식·후손이다. 본문 링크·멘션·북마크·`link_to_page`는 범위를 넓히지 않는다. 루트 아래에 실제로 속한 인라인 데이터베이스의 행은 별도 게시글로 수집한다.
- Notion 응답의 `public_url`이 명시적 `null`이거나 `in_trash`가 참일 때만 `UNPUBLISHED`로 기록한다. 필드 누락·잘못된 값·타임아웃·`429`·`5xx`·인증·매핑 실패는 동기화 실패로 기록하고 마지막 게시 상태와 스냅샷을 유지한다. Notion 장애를 readiness 실패로 연결하지 않는다.
- 동기화는 갱신 시각이 지난 `SyncTarget`만 처리한다. 범위에서 빠진 글의 예약은 활성화 트랜잭션에서 삭제하고, 미게시 구성원은 재게시 확인을 위해 계속 동기화한다.
- 스케줄러는 대상마다 `RuntimeException`을 격리해 작업 종류·예외 타입만 기록하고 다음 대상을 계속한다. 원인에 인터럽트가 있거나 그 밖의 `Throwable`이면 원래 예외를 전파한다. 스케줄러를 활성화한 인스턴스는 하나여야 한다.
- 스냅샷 JSON에는 논리적 `kind`와 `schemaVersion`만 저장한다. 구현 클래스 이름·Jackson default typing·Notion 원본 응답을 저장하지 않는다.
- 블록 스타일은 의미 값만 저장한다. CSS·JavaScript·`<head>` 조각을 Notion에서 받거나 저장하지 않고, 표현은 등록된 프로필과 신뢰된 자산만 사용한다.

### Notion 설정 데이터 소스

`NOTION_SETTINGS_DATA_SOURCE_ID`의 데이터 소스를 API 버전 `2026-03-11`로 끝까지 조회한다. 다른 API 버전으로는 시작하지 않는다.

| 속성 | 타입 | 규칙 |
| --- | --- | --- |
| `Key` | title | `rootPage`, `header`, `footer`, `head` 중 하나 |
| `Kind` | select | `page`, `blocks`, `head` 중 하나 |
| `Enabled` | checkbox | `true`인 첫 번째 같은 key 행만 적용 |
| `Page` | rich text | Notion page URL 또는 32자리 page ID |
| `Data` | rich text | `head` 행의 JSON 객체 |

- `rootPage`(`Kind=page`)는 필수다. 없으면 설정 동기화가 실패하고 마지막 정상 설정을 유지한다.
- `header`·`footer`(`Kind=blocks`)는 활성 범위의 `PUBLISHED` 구성원이고 스냅샷이 있을 때만 렌더링하고, 아니면 본문을 실패시키지 않고 생략한다.
- `head`의 JSON은 `siteName`, `defaultDescription`, `languageTag`, `faviconAssetKey`, `presentationProfileKey`만 허용한다. 다른 필드·HTML·CSS·JavaScript·원격 자산 URL은 거부한다.

### 여러 루트를 함께 쓰는 업무

다음 업무는 불변식 때문에 한 쓰기 트랜잭션에서 여러 루트를 바꾼다. 범위를 넓히거나 나누지 않는다.

- **공개 범위 활성화(`ActivatePublicationService`):** 이전 revision `SUPERSEDED`, 새 revision `ACTIVE`, BlogPublication 포인터 교체, 범위에서 빠진 글의 SyncState 삭제, publication 성공 기록. 활성 범위와 갱신 예약이 어긋나지 않게 한다. 중간 실패는 전체 롤백하고 이전 활성 revision을 유지한다.
- **게시글 반영(`ApplyImportedPostService`):** Post 식별·스냅샷·게시 상태·최초 게시 시각과 해당 SyncState 성공 기록. 게시 상태와 스냅샷이 어긋나 503이 되는 상태를 만들지 않는다.
- **사이트 설정 적용(`ApplyImportedSiteConfigurationService`):** SiteConfiguration 저장, 첫 적용 시 BlogPublication 생성, 설정 SyncState 성공 기록.
- **RSS 조회(`GetPostFeedService`):** 설정·활성 범위·게시 상태·스냅샷을 `REPEATABLE_READ` 읽기 전용 트랜잭션 하나에서 읽는다.

동일 소스의 동시 동기화는 단일 스케줄러 실행으로 직렬화한다. 여러 인스턴스에서 스케줄러를 실행하려면 DB 잠금·lease를 먼저 설계한다.

### BC 간 연동 요구가 생기면

공개 데이터 계약·모델 번역·변경 책임·판정 시점·실패·재시도·원자성을 먼저 정하고 전달 기술을 선택한다. 각 BC의 유스케이스가 자기 변경·트랜잭션을 소유하는 것이 기본이다. 여러 BC 쓰기의 동시 커밋이 필요하면 분리 전제도 재검토하되 자동 병합하거나 실패를 무시한 연속 호출로 대체하지 않는다.

소비측 코어는 자기 모델·출력 포트를 쓰고 어댑터가 외부 공개 데이터를 번역한다. 인프로세스 어댑터가 상대 입력 포트를 호출하는 구현은 현재 검사에서 실패하므로 필요한 검사 변경과 정상·위반 fixture도 함께 설계한다. 같은 DB·JVM이라는 이유로 상대 테이블에 접근하거나 `config`의 공용 포트·함수 값으로 업무 결합을 숨기지 않는다.

## 패키지 배치

업무 패키지 하나가 BC 하나다. `blog.posts.domain` 같은 중첩 기능 경로는 금지하며 계층 아래에는 역할·구현 하위 패키지를 둘 수 있다.

```text
blog/domain/                         BC 안에서 의미·제약이 같은 공유 값 (SourceDocumentRef 등)
blog/domain/<aggregate>/             루트 하나와 소유 ID·값·엔티티 (post, publication, site, presentation, sync)
blog/domain/<aggregate>/<helper>/    같은 어그리게이트 내부 (post/block 등)
blog/application/model/              순수 유스케이스 데이터·저장 projection
blog/application/port/input/         유스케이스 계약
blog/application/port/output/        persistence·source·presentation·diagnostics 계약
blog/application/service/            유스케이스 구현
blog/adapter/inbound/<group>/        web(HTTP·Thymeleaf·RSS), scheduling
blog/adapter/outbound/<group>/       notion, persistence(Exposed·스냅샷 JSON), presentation, diagnostics
domain/AggregateRoot.kt              공용 기술 표식
config/                             빈 조립·기술 설정
```

- 모든 운영 클래스는 허용 계층 또는 시작 클래스 `BlogApplication`에 둔다. 검사 밖에 새 클래스를 두어 회피하지 않는다.
- 루트 직속 공용 애플리케이션·어댑터는 업무 기능 타입에 의존하지 않는 코드에만 사용한다.

## 의존 규칙

`adapter → application → domain`을 유지한다. 아래의 '자기 BC'에는 허용된 루트 공용 타입도 포함한다. 다른 BC의 모델·입력 계약을 코어에서 직접 참조하지 않는다. 패키지 간 순환도 금지한다.

- `domain`: 언어 타입과 자기 BC 도메인만 사용한다.
- `application/model`: 언어 타입·자기 BC 도메인·모델만 사용한다. 포트는 모델을 참조할 수 있고 모델은 포트를 참조하지 않는다.
- 입력·출력 포트: 언어 타입·자기 BC 도메인·모델·포트만 사용한다. Spring·Exposed·HTTP·서비스 구현·어댑터·설정을 참조하지 않는다.
- `application/service`: 자기 BC의 도메인·애플리케이션을 사용한다. Spring 타입은 **`org.springframework.transaction.annotation`의 `Transactional`·`Propagation`·`Isolation`만** 허용한다.
- 입력 어댑터: 자기 BC 입력 포트·도메인·모델과 입력 기술을 사용한다. 출력 어댑터: 자기 BC 출력 포트·도메인·모델과 외부 기술을 사용한다. 서비스 구현·반대 방향 포트·다른 어댑터 그룹·`config`를 직접 참조하지 않는다.
- Exposed·Spring JDBC·`java.sql`·`javax.sql`은 출력 어댑터의 `persistence` 경로와 `config`에서만 사용한다. Exposed 행·테이블·쿼리·트랜잭션 타입을 포트·도메인·웹 요청/응답으로 노출하지 않고 어댑터 안에서 변환한다.
- Notion API DTO·HTTP 클라이언트는 `adapter/outbound/notion`, JSON 처리는 Notion과 `persistence` 출력 어댑터 안에 둔다. 도메인·애플리케이션은 Notion ID·DTO·응답을 알지 못한다.
- `config`는 모든 BC 구현의 조립과 기술 설정에 접근할 수 있지만 금지된 업무 호출을 만들지 않는다.

코어의 직접 I/O는 금지한다. 대상은 `java.io`, `java.sql`, `javax.sql`, `java.net`, `java.nio.file`, `java.nio.channels`, `java.rmi`, `java.util.prefs`, `kotlin.io`와 `Process`·`ProcessBuilder`·`ProcessHandle`·`Runtime`이다. 예외는 순수 계약 `java.io.Serializable`과 주소 값 `java.net.URI`뿐이다. 파일·네트워크·DB·외부 프로세스는 출력 포트로 요청한다. 시각은 주입한 `Clock`에서 얻고 `Instant.now()`를 직접 호출하지 않는다.

### 역할과 위치

- 컨트롤러와 합성 컨트롤러 애너테이션은 입력 어댑터의 `*Controller`, `@Configuration`은 `config`의 `*Config`에 둔다. 시작 클래스의 `@SpringBootApplication`은 예외다.
- 입력 포트 구현은 자기 BC 서비스, 출력 포트·`*Repository` 구현은 자기 BC 출력 어댑터에 둔다. 보조 서비스에 포트 구현을 강제하지 않는다.
- 포트 역할의 전체 경로는 구현 경로에 **연속된 완전한 세그먼트**로 들어가야 한다. `port/output/payment/refund`는 `adapter/outbound/stripe/payment/refund` 또는 `adapter/outbound/payment/refund/stripe`에서 구현한다. 하위 역할이 없는 포트는 자기 기능의 해당 구현 계층에 둔다. 루트 공용 포트는 기능별 구현에서 사용할 수 있다. 입력 어댑터가 호출하는 입력 포트에도 역할 경로를 맞춘다.
- 어댑터 격리 단위는 **BC + 방향 + 첫 그룹**이다. `outbound/stripe/...`와 `outbound/paypal/...`은 격리되고 같은 그룹의 하위 패키지는 함께 쓸 수 있다. 공급자 격리는 첫 그룹으로 표현한다.
- 포트 패키지의 `UseCase`·`Repository`·`Port`·`Source`·`Catalog`·`Reporter`·`Gateway`·`Verifier`·`Store`·`Lookup`·`Classifier` 접미사는 인터페이스에 쓴다. `Command`·`Result`와 입력 포트의 예외 타입은 순수 데이터 계약으로 둘 수 있다.

### 어그리게이트 규칙

- 루트에 `@AggregateRoot`를 붙이고 `<bc>.domain.<aggregate>` 바로 아래에 하나만 둔다. BC의 `domain` 바로 아래나 루트의 하위 패키지에 새 루트를 두지 않는다.
- 생성자·생성된 `copy`는 비공개, 프로퍼티는 `val`로 유지하고 생성·복원·상태 전이를 구분한다. 보관한 컬렉션·요소의 외부 변경도 검토한다.
- 도메인은 다른 어그리게이트의 루트 객체 대신 의미가 맞는 ID·불변 값을 참조한다. 자기 루트는 내부 하위 패키지에서 참조할 수 있다. 자기 BC 애플리케이션·어댑터와 `config`는 루트를 사용하고 서비스는 여러 저장소를 호출할 수 있다.
- 루트를 다루는 출력 포트는 이름과 무관하게 저장소다. **포트 하나의 루트는 최대 하나, 루트 하나의 출력 포트도 최대 하나**이며 상속·제네릭에도 적용한다. 루트를 노출하지 않는 조회용 `Store`는 별도 계약이다. 현재 대응은 Post-`PostRepository`, BlogPublication-`PublicationRepository`, SiteConfiguration-`SiteConfigurationRepository`, PresentationProfile-`PresentationProfileRepository`, SyncState-`SyncStateRepository`다.

### BC 경계

- `HexagonalArchitectureTest.contextAggregates`에 `blog → post.Post, publication.BlogPublication, site.SiteConfiguration, presentation.PresentationProfile, sync.SyncState`를 유지하고 새 BC·루트를 같은 변경에서 등록한다.
- 미등록 업무 패키지·루트, 코드 없는 등록 항목, 중첩 기능 경로를 만들지 않는다.
- 루트 공용 도메인에는 `AggregateRoot`만 둔다. BC 안의 공유 값도 의미·제약이 같은 경우에만 올린다.
- 자기 저장소·테이블을 사용하고 다른 BC 타입을 직접 참조하지 않는다. `config`의 조립만 예외로 둔다.
- 어댑터의 다른 BC 모델 참조처럼 검사에 잡히지 않는 결합도 검토한다. 새 연동은 [별도 계약](#bc-간-연동-요구가-생기면)을 설계한다.

## 의존성 주입과 빈 조립

- 운영 의존성은 생성자 `private val`로 받는다. 필드·세터·메서드의 `@Autowired`·`@Inject`·`@Resource`·`@Value` 주입은 금지한다.
- 유스케이스에 `@Service`·`@Component`를 붙이지 않고 `config`의 `@Bean`으로 조립한다. 설정은 `@ConfigurationProperties`로 바인딩한다.
- 테스트 프레임워크 주입은 [테스트 예외](code-quality.md#테스트-코드의-차이)를 따른다.

## 트랜잭션

- 유스케이스의 Spring `@Transactional`로 업무 단위를 정하고 어그리게이트에서 불변식을 지킨다.
- Exposed Spring Boot 4 스타터의 `springTransactionManager`를 사용한다. `Database`·매니저를 중복 구성하지 않고 저장소에 별도 `transaction {}`를 넣지 않는다.
- 유스케이스에 `TransactionTemplate`·매니저를 주입하거나 공통 트랜잭션 추상화·데코레이터를 추가하지 않는다.
- 쓰기 서비스(`Apply*`, `Stage*`, `Activate*`, `Cancel*`)는 쓰기 `@Transactional`, 조회 서비스(`GetBlogPageService`, `SynchronizationQueryService`)는 `@Transactional(readOnly = true)`를 쓴다. `GetPostFeedService`는 `REPEATABLE_READ` 읽기 전용이다. 기본 `REQUIRED`의 기존 트랜잭션 참여 범위도 확인한다.
- Notion을 호출하는 `Synchronize*Service`는 트랜잭션을 열지 않는 오케스트레이터다. 외부 HTTP 호출 동안 DB 트랜잭션을 유지하지 않고 읽기·쓰기 서비스 호출로 DB 경계를 나눈다. 스케줄러는 갱신 대상 조회와 서비스 호출만 한다.
- Spring이 관리하는 빈을 외부에서 호출한다. 직접 생성한 객체나 자기 객체 내부 호출을 새 트랜잭션 경계로 취급하지 않는다. `readOnly`를 접근 제어나 쓰기 차단에 쓰지 않는다.
- 기본 롤백 대상은 `RuntimeException`과 `Error`다. 그 밖의 예외도 롤백해야 한다면 `rollbackFor`를 명시한다.
- 가상 스레드에서도 JDBC 트랜잭션 안에서 비동기·코루틴 디스패처 전환으로 작업을 넘기지 않는다. 가상 스레드를 이유로 연결 풀 제한을 없애지 않고 트래픽·쿼리에 맞게 조정한다.
- 모든 입력·출력 어댑터의 클래스·메서드는 직접·합성·상속 `@Transactional`, 트랜잭션 인프라 의존, 수동 트랜잭션 제어를 금지한다. 연결·커넥터 경유나 메서드 참조도 같다. 일반 저장소 조회는 허용한다. 정확한 탐지 대상과 JTA 도입 조건은 [트랜잭션 검사](quality-maintenance.md#트랜잭션-검사-확장)에 있다.
- 여러 루트의 원자적 쓰기는 [여러 루트를 함께 쓰는 업무](#여러-루트를-함께-쓰는-업무)에 기록하고 위 분류 절차에서 먼저 재검토한다.

## 자동 검사와 완료 조건

- 선택 근거·BC와 루트 등록·[기본 범위](development.md#테스트-범위)의 불변식과 실패 경로 테스트를 남기고 [전체 빌드](development.md#실행과-검증)를 통과한다.
- 공통·DDD·BC 검사를 운영 코드에 함께 적용한다. Notion·JSON·Exposed 위치, 공개 경로, 스냅샷 형식, 시각 사용은 `TargetArchitectureBoundaryTest`가 추가로 검사한다.
- 정적 검사 통과만으로 업무 의미·깊은 불변성·조립 내용·실제 변경 대상을 보장한다고 보고하지 않는다.
- 누락된 루트 표식, 함수 값·반사·인라인·값 클래스 소거를 통한 결합은 코드 검토와 행동 테스트로 확인한다. 요구에 없는 사업 결정을 추측하지 않는다.
