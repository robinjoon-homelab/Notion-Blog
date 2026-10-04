# Notion 어댑터 내부 구조안

**Notion이 보내는 데이터의 모양을 먼저 표현하고, 그 데이터를 블로그에서 쓰는 의미로 바꾸는 코드를 분리한다.** 같은 정보를 소비하는 기능은 같은 해석 코드를 호출하며, 이름이나 평문만 쓰는 곳에 전체 구조 검증을 강제하지 않는다.

상태: **구현 전 설계**, 2026-10-05. [기준 아키텍처](kotlin-spring-architecture.md)의 13절을 구체화한다. 이 문서를 작성하면서 애플리케이션 코드는 변경하지 않았다.

대상은 현재 고정된 `Notion-Version: 2026-03-11`과 저장소의 응답 고정 데이터다. Notion API 전체를 미리 구현하거나 버전을 올리는 제안이 아니다. **수집 순서, 페이지네이션, 개수·깊이·시간 제한은 현재 동작을 유지한다.**

## 1. 무엇을 분리하려는가

이미지 하나를 읽는 데도 서로 다른 두 질문이 있다.

1. **Notion 응답의 모양:** 외부 파일인가, Notion 보관 파일인가? URL과 만료 시각이 어떤 필드에 있는가?
2. **블로그에서의 쓰임:** 본문 이미지인가, 카드 표지인가? 만료되었을 때 어떻게 표시하는가?

첫 질문의 답은 본문·표지·아이콘에서 공통이다. 두 번째 답은 사용하는 기능에 따라 달라질 수 있다. 지금은 JSON 필드 읽기와 블로그 모델 변환이 같은 함수에 섞여 있어, 공통 규칙이 여러 경로에 흩어져 있다.

예를 들어 `expiry_time: 42`는 본문 파일과 일반 블록 아이콘에서 만료 정보 없음으로 처리되지만, 갤러리 커버와 행 아이콘에서는 실패한다. **구조를 정리하는 결정과, 어느 처리가 맞는지 정하는 결정은 구분해야 한다.** 이 문서는 앞의 구조를 정하며 뒤의 동작 변경은 승인된 것으로 간주하지 않는다.

## 2. 현재 구현과 목표의 차이

| 현재 확인한 구현 | 목표 |
|---|---|
| API client, 블록·페이지·설정·셀 매퍼, 커버 reader가 JSON을 각각 읽는다. | JSON 해석은 Notion 응답 모델과 그 디코더에 모은다. 매퍼는 해석된 값을 받는다. |
| `dto/block`, `dto/richtext`의 7개 파일·16개 선언은 실제 읽기 경로에서 사용되지 않는다. | 실제 실행 경로에 연결된 모델만 유지한다. 대체 동작을 검증한 뒤 기존 미사용 선언을 제거한다. |
| 여러 블록의 필드를 nullable 속성으로 모은 DTO가 남아 있다. | 각 타입에 필요한 필드를 명시한다. 예: 할 일에는 체크 상태, 코드에는 언어가 있다. |
| 파일·아이콘의 만료값과 URL 검증이 호출 위치에 따라 다르다. | 공통 파일 형식은 한 곳에서 읽고, 남겨야 할 사용처별 정책은 이름 있는 변환 코드에서 구분한다. |

이 구조는 도메인과 비즈니스 로직을 Notion 쪽으로 옮기자는 뜻이 아니다. 기존 `PostSource`와 `SiteConfigurationSource`의 출력, 소스 중립적인 도메인은 유지한다.

## 3. 책임과 데이터 흐름

```text
Notion HTTP 응답
  → client: 요청·응답과 HTTP 오류 처리
  → decoding: JSON 모양 확인, 타입별 dto 생성
  → mapping: Notion 값을 블로그 모델로 변환
  → NotionPostSource / NotionSiteConfigurationSource: 결과 조립
  → ImportedPost / ImportedSiteConfiguration
```

호출 순서를 전부 직렬로 바꾼다는 그림은 아니다. 소스와 reader가 지금처럼 필요한 요청을 조율하며, 각 응답을 해석할 때 이 경계를 따른다.

**`dto`는 Notion 응답을 표현한다.** `file`과 `external`, 페이지 부모의 종류, 리치 텍스트의 종류와 같이 Notion이 정하는 구분을 담는다. `PostId`, 공개 범위, 렌더링 클래스나 CSS는 넣지 않는다.

**`decoding`은 JSON을 읽는다.** `type`에 맞는 필드가 있는지, 배열·문자열·숫자 형태가 맞는지 확인한다. Notion ID 정규화와 날짜 파싱도 이 경계에 둔다. 블로그 공개 여부나 표시할 열은 결정하지 않는다.

**`mapping`은 블로그의 해석을 담당한다.** 제목 기본값, Notion 색상의 전경·배경 분리, 파일을 `MediaSource`로 변환하는 일, 설정의 `rootPage` 행 해석이 여기에 속한다. `rootPage`와 `head`는 Notion의 플랫폼 규칙이 아니라 이 블로그가 정한 설정 관례다.

**소스와 reader는 필요한 자료를 모은다.** 자식 블록 요청, 부모 확인, 데이터베이스 뷰·행·커버 조회를 담당한다. JSON 검증을 다시 구현하지 않는다. 블로그 전체 공개 범위의 활성화, 예약, 트랜잭션은 기존 application service의 책임이다.

**해석할 범위와 시점도 유지한다.** 먼저 현재 공통 메타데이터를 검증하고, 지원하는 뷰·공개 행·선택한 속성·활성 설정 행에 한해 필요한 내용을 디코딩한다. 사용하지 않는 속성이나 아이콘까지 먼저 엄격하게 읽으면, 지금 무시하는 값 때문에 동기화가 실패할 수 있다.

선택 전 응답 조각을 보관해야 한다면 decoding 내부의 작은 envelope에 한정한다. 소스는 필요한 속성 ID 등을 전달하고 타입으로 된 결과를 받는다. 매퍼가 다시 `JsonNode`를 읽거나, 범용 지연 실행 체계를 추가하지 않는다.

구현 시에는 `adapter.output.notion` 안의 다음 작은 묶음을 사용한다. 별도 Gradle 모듈이나 내부 인터페이스는 만들지 않는다.

```text
notion/
  client/                  기존 HTTP client와 수집 동작
  dto/
    page/                  페이지, 부모, 속성
    block/                 공통 블록 정보와 타입별 내용
    richtext/              글자 조각, 꾸밈, 멘션
    media/                 파일과 아이콘
    database/              DB, 데이터 소스 스키마, 뷰
    NotionPaginationResponse
  decoding/                위 모델을 읽는 구체 디코더
  mapping/                 기존 매퍼와 공통 미디어·리치 텍스트 변환
  NotionPostSource 등       기존 소스와 reader
```

이름과 파일 수보다 책임의 위치가 기준이다. 서로 관련된 작은 타입은 한 파일에 둘 수 있다. 디코더 등록소, 리플렉션 기반 분기, 플러그인 틀은 만들지 않는다. 명시적인 `when`과 구체 함수 호출로 연결한다.

## 4. 지금 필요한 Notion 모델

다음 이름은 목표 모델의 예시다. **응답 모델 외에 동일한 내용을 가진 거대한 중간 Notion 도메인을 한 벌 더 만들지 않는다.** 디코딩한 DTO에서 기존 블로그 모델로 바로 변환한다.

### 파일과 아이콘

`NotionFile`은 `External(url)`과 `Hosted(url, expiry)`로 구분한다. 실제 URL·만료 정보를 쓰는 본문 미디어, 페이지 커버, 커버용 파일 속성, 파일 아이콘이 같은 타입과 같은 `NotionFileDecoder`를 사용한다. 캡션·파일명은 그것을 제공하는 바깥 응답에 둔다.

**파일 셀은 현재처럼 이름만 읽는다.** 객체의 `name`만 읽는 좁은 경로를 두고 `type`·URL·만료값의 해석을 요구하지 않는다. 같은 파일 속성도 커버로 쓸 때만 파일 소스 디코더를 호출한다.

`NotionIcon`은 현재 코드가 다루는 emoji, native icon, custom emoji, 파일 아이콘을 구분한다. 파일 아이콘만 `NotionFile`을 포함한다. custom emoji의 표시 이름·ID·URL은 해당 타입에만 둔다.

`NotionMediaMapper`가 URL 허용 정책과 `MediaSource` 변환을 소유한다. 커버 reader는 어떤 파일을 표지로 고를지만 결정한다. 만료된 파일을 표시하지 않는 기존 렌더링 동작은 유지한다.

### 리치 텍스트

`NotionRichText`는 `Text`, `Equation`, `Mention`으로 나누고 각 타입에 필요한 내용과 공통 꾸밈 정보를 둔다. 멘션은 현재 처리하는 page·database·user·date·template·link preview와 그 외 타입을 구분한다.

`NotionRichTextDecoder`는 응답 형태를, `NotionRichTextMapper`는 `InlineContent`와 안전한 링크 변환을 담당한다. 전체 인라인 구조를 사용하는 본문·캡션·DB의 title/rich_text 셀에서 재사용한다. 셀 제목에 행 링크를 붙이는 일은 셀 매퍼가 맡는다.

**페이지·데이터베이스 제목과 설정 문자열은 `plain_text`만 읽는 경로를 유지한다.** decoding 안의 좁은 평문 읽기 함수가 문자열 조각을 반환하고, 각 매퍼가 합치기·제목 기본값 등을 적용한다. 이 경로에서는 `type`·`annotations`·세부 payload를 요구하거나 검증하지 않는다.

### 페이지와 부모

`NotionPageResponse`는 ID, 부모, `public_url`, 휴지통 여부, 수정 시각, 속성, 선택적 아이콘·커버를 가진다. `public_url`은 필드 존재를 먼저 검사한 뒤에만 nullable 값으로 표현한다.

`NotionPageParent`는 page 부모와 data source 부모처럼 현재 사용하는 종류를 구분한다. 필요한 부모 ID가 없는 상태를 nullable 필드 조합으로 숨기지 않는다. 현재 사용하지 않는 부모 종류는 타입 표식만 보존하고 공개 범위의 근거로 삼지 않는다.

페이지 수정 시각을 `SourceRevision`으로 만드는 일과 `public_url`·휴지통 여부를 게시 상태로 해석하는 일은 페이지 매퍼가 맡는다. `child_page`의 실제 부모 확인은 소스가 맡는다.

### 블록

`NotionBlockEnvelope`은 ID, 열린 타입 문자열, 자식·휴지통 여부를 읽는다. 타입별 내용은 `NotionBlockDecoder`의 명시적 분기로 해석한다. `JsonNode`는 이 해석 경계까지만 사용한다.

예를 들어 `ToDo(richText, checked)`, `Code(richText, language, caption)`, `Media(file, caption)`는 서로 필요한 필드가 다르다. 공통 파일·리치 텍스트 타입을 조합하며, 기존 nullable 속성 가방을 그대로 활성화하지 않는다.

블로그의 `BlockNode`처럼 자식 트리를 DTO에 또 완성하지 않는다. 지금처럼 소스가 수집한 자식을 블록 매퍼에 전달한다. 알려지지 않은 블록의 타입과 가능한 자식은 기존 안전한 폴백으로 보존한다.

### 속성과 데이터베이스 뷰

속성 **정의**는 ID·이름·종류, 속성 **값**은 종류별 값과 불완전 여부를 표현한다. 제목·리치 텍스트, 숫자·체크박스, 선택·날짜·파일 등 현재 표시하는 값부터 모델링한다. 이름으로 접근하는 설정 행과 ID로 선택하는 표시 열의 차이도 보존한다.

알 수 없는 속성과 불완전한 값은 기존 안내 표현에 필요한 상태만 남긴다. 관계를 따라가거나 사용자 정보를 더 읽는 기능은 추가하지 않는다. 파일 속성은 표시 이름만 읽는 셀과 실제 파일 소스를 읽는 커버 경로를 구분한다.

뷰는 공통 ID·소속 DB·이름·데이터 소스 참조와 `Table`, `List`, `Gallery` 설정을 나눈다. 열 목록의 **설정 없음**과 **명시적 빈 목록**을 구분한다. 디코더가 숨긴 열을 기본 열로 복원해서는 안 된다.

어떤 뷰를 지원할지, 미게시 행을 제외할지, 기본 제목 열을 표시할지는 블로그의 뷰 해석 책임이다. `configuration`의 누락/null에 적용하는 현재 기본값을 그대로 옮기며, 다른 뷰 타입을 제외하는 정책도 유지한다.

### 페이지네이션

기존 `NotionPaginationResponse<T>`의 결과·`has_more`·커서 조합을 유지한다. 블록·설정 행·뷰 목록·뷰 쿼리 결과가 같은 응답 모양 해석을 사용한다.

**응답 모양의 재사용은 수집 루프의 통합을 뜻하지 않는다.** 루프, 커서 반복 감지 위치, 제한 시간의 시작점, 수집량 계산은 이번 구조 정의에서 바꾸지 않는다.

## 5. 누락과 오류를 어떻게 다룰 것인가

`null`은 뜻이 정해졌을 때만 사용한다. “필드가 없다”, “명시적인 null이다”, “숫자가 왔다”, “문자열이지만 해석할 수 없다”를 모두 `null`로 만드는 공통 함수는 만들지 않는다.

| 입력 사례 | 유지할 계약 또는 남은 결정 |
|---|---|
| `public_url` 누락·숫자·빈 문자열 | 현재처럼 `SourceConfigurationException`. 마지막 게시 상태와 스냅샷 보존. |
| `public_url: null`, 또는 정상 응답의 `in_trash: true` | 현재처럼 `UNPUBLISHED`. `public_url`에 새로운 URI/HTTPS 검증은 추가하지 않음. |
| 파일 소스를 읽는 경로의 정상 만료 시각 문자열 | 현재 `Instant.parse`가 처리하는 ISO 시각을 보존. 생략/null은 만료 정보 없음. |
| 숫자·공백만 있는 파일 만료값 | 본문·일반 아이콘과 커버·행 아이콘의 현재 차이를 먼저 테스트로 기록. 통일 방향은 별도 결정. |

파일 소스를 읽는 경로에서 ISO 형식이 깨진 **비어 있지 않은 만료 시각 문자열**은 현재처럼 실패한다. 이름만 표시하는 파일 셀은 사용하지 않는 URL·만료값이 잘못되어도 지금처럼 이름을 표시한다. 시간 파싱은 공통으로 만들되, 숫자나 빈 문자열을 허용할지는 별개의 판단이다.

알 수 없는 타입을 무조건 같은 방식으로 처리하지 않는다. 일반 **블록**은 안전한 폴백, 미지원 **뷰**는 제외, 미지원 **속성**은 안내를 유지한다. 알 수 없는 최상위 **리치 텍스트 종류**의 매핑 실패는 전체 인라인을 읽는 경로에만 적용하며, 평문만 읽는 경로는 종류를 검사하지 않는다.

블록 폴백에도 현재의 공통 필드 검증은 적용한다. 알 수 없는 멘션의 세부 종류는 `OTHER`를 유지한다. 선택한 속성 자체가 없으면 불완전 안내, 값이 명시적 null이면 빈 셀이라는 차이도 보존한다.

현재 셀 해석에서 `has_more: true`, people·relation·rollup 배열 25개 이상, title/rich_text 안의 page·user 멘션 25개 이상은 불완전 안내를 사용한다. 이 기준을 일반 텍스트 조각 수 제한으로 확대하지 않는다.

알려진 타입의 **현재 필수 필드**가 깨진 경우는 실패를 유지한다. 구조 정리 중 새 필수 필드를 늘리거나 지금 허용하는 응답을 일괄 거부하지 않는다. 예외를 옮길 때도 `CONFIGURATION`과 `MAPPING`의 기존 분류가 달라지지 않게 경계에서 변환한다.

**구현 전에 정할 사항:** 잘못된 선택 필드까지 모두 엄격하게 거부할지, 파일 URL의 host·userinfo 검사 차이를 통일할지. 새 부모·아이콘 모델을 도입하면서 현재 허용하는 생략이나 모양을 거부하게 되는지도 같은 기준으로 판단한다. 특히 아이콘은 현재 `type`만 따르지 않고 emoji·native icon·custom emoji·file/external 필드 순서로 해석하므로, 이를 엄격한 타입 판별로 교체하는 것은 별도 동작 변경이다.

정책을 결정하기 전에는 해당 경로를 새 엄격한 디코더로 교체하지 않는다. 임시 허용 모드가 계속 늘어나는 범용 정책 틀을 만들기보다, 동작이 같은 부분부터 옮기고 차이가 있는 부분은 결정 후 옮긴다.

## 6. 기능을 추가할 때 바뀌는 곳

예를 들어 **표지 파일명 표시** 기능을 추가한다고 가정한다. 현재 API가 새 필드를 제공한다는 주장이 아니라 구조를 설명하기 위한 기능 예시다.

1. 이미 읽는 파일 응답에 필요한 이름이 있는지 해당 버전의 응답 고정 데이터로 확인한다.
2. 이름이 필요한 바깥 응답 모델과 디코더에만 필드를 추가한다. 파일 URL·만료 시각 해석은 공통 코드를 그대로 쓴다.
3. 블로그에 저장·표시할 새 정보가 실제로 필요하면 매퍼, 소스 중립 모델, 스냅샷, 뷰를 함께 변경한다.
4. 본문 이미지의 URL 검증과 갤러리 표지의 파일 읽기를 각각 찾아 수정할 필요가 없도록 공통 경로 테스트를 실행한다.

Notion 응답 모양만 바뀌면 디코더가, 블로그의 표시 방식만 바뀌면 매퍼와 표현 계층이 주된 변경 지점이다. 두 문제가 함께 바뀌면 둘 다 수정한다. 이 경계를 통해 변경 이유를 찾기 쉽게 만든다.

## 7. 점진적으로 이전하는 순서

1. **현재 동작을 고정한다.** 기존 `2026-03-11` 고정 데이터와 경계 테스트에 누락/null/잘못된 스칼라/잘못된 날짜 사례를 보완한다. 본문·커버·아이콘의 차이는 기대값을 같게 만들지 않고 각각 기록한다.
2. **공통 부품부터 연결한다.** 동작이 같은 파일·리치 텍스트 해석을 실제 호출 경로에 연결한다. 만료값·URL 검증 차이가 있는 교체는 정책 결정 이후로 남긴다.
3. **블록 계열을 하나씩 옮긴다.** 각 디코더를 연결할 때 36개 대안의 해당 사례와 중첩 자식, 폴백, 실패 시 스냅샷 보존을 검증한다. 기존 미사용 DTO를 일괄 채택하지 않는다.
4. **페이지·설정·DB를 옮긴다.** 공통 속성·아이콘·파일을 사용하되 게시 상태, 설정 관례, 열 기본값, 불완전 셀, 커버 선택의 기존 결과를 대조한다.
5. **대체가 확인된 구 경로를 제거한다.** 두 해석 경로를 영구 유지하지 않는다. 직접 JSON 접근은 client의 응답 읽기와 decoding 안에만 남았는지 확인한다.

특성화 테스트에는 페이지·DB 제목과 설정의 **`plain_text`만 있는 조각**, 사용하지 않는 `type`·`annotations`·payload가 잘못된 조각을 포함한다. 파일 셀도 **이름만 있는 객체**와 이름은 정상이나 사용하지 않는 URL·만료값이 잘못된 객체가 기존 결과를 유지하는지 확인한다. 같은 잘못된 필드가 실제 소비되는 본문·셀 인라인·커버 경로의 실패 검증과 구분한다.

각 단계에서 관련 경계 테스트와 `./gradlew test`, `./gradlew build`를 실행한다. HTTP 응답은 MockWebServer로 검증하고 원본 응답·토큰·전체 URL이 오류 로그로 새지 않는지도 확인한다. 이 문서 작성 자체에는 프로덕션 변경이나 테스트 실행이 없다.

완료 기준은 DTO 파일 수가 아니다. **하나의 Notion 파일 형식을 한 곳에서 읽고, 새 블록을 추가할 위치와 기존 동작을 지키는 테스트가 분명해지는 것**이다. 스냅샷은 계속 소스 중립 모델만 저장한다.

## 8. 확인한 로컬 근거

- [실제 HTTP 응답 해석과 API 버전](../src/main/kotlin/xyz/robinjoon/notionblog/adapter/output/notion/client/NotionApiClient.kt), [페이지 해석](../src/main/kotlin/xyz/robinjoon/notionblog/adapter/output/notion/mapping/NotionPageMapper.kt)
- [본문·리치 텍스트·아이콘 해석](../src/main/kotlin/xyz/robinjoon/notionblog/adapter/output/notion/mapping/NotionBlockMapper.kt), [커버·행 아이콘 검증](../src/main/kotlin/xyz/robinjoon/notionblog/adapter/output/notion/NotionDataViewCoverReader.kt)
- [설정 행 해석](../src/main/kotlin/xyz/robinjoon/notionblog/adapter/output/notion/mapping/NotionSettingsMapper.kt), [속성 값 해석](../src/main/kotlin/xyz/robinjoon/notionblog/adapter/output/notion/mapping/NotionDatabaseCellMapper.kt), [인라인 DB 수집](../src/main/kotlin/xyz/robinjoon/notionblog/adapter/output/notion/NotionInlineDatabaseReader.kt)
- [블록 응답 고정 데이터](../src/test/resources/notion/2026-03-11/block-alternatives.json), [실패 시 기존 상태 보존 테스트](../src/test/kotlin/xyz/robinjoon/notionblog/adapter/output/notion/NotionFailurePreservationTest.kt), [기본 뷰 테스트](../src/test/kotlin/xyz/robinjoon/notionblog/adapter/output/notion/NotionDefaultDatabaseViewTest.kt)

이 설계는 위 코드와 고정 데이터를 근거로 한다. 구현 시 새 플랫폼 계약이 필요해지면 해당 버전의 공식 문서와 응답을 확인한 뒤 모델·테스트를 함께 보완한다.
