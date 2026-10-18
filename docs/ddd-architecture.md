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
- **자식 값:** 공개 결정(`PostPublication`)과 소스 바인딩은 게시글마다 하나이므로 Post 어그리게이트의 값으로 둔다. 가져온 내용은 Post가 소유한 불변 `PostRevision`이다.
- **같은 BC의 새 루트:** 표현 프로필은 사이트 설정 없이 등록·버전 관리되고 여러 설정에서 선택될 수 있으므로 SiteConfiguration과 다른 루트이며 `PresentationProfileRef`로 참조한다.
- **같은 BC의 새 루트(시리즈):** 시리즈는 글 없이 만들고 지우며 소속 글의 순서가 시리즈의 불변식이다. 순서를 Post 필드로 두면 순서 변경이 여러 Post를 쓰고 발행 저장과 같은 행을 다투므로, Series가 순서 있는 `PostId` 목록을 소유한다. 글당 0..1 소속은 여러 Series에 걸친 규칙이라 DB 유일 제약으로 보장한다. 소속은 Post에 두지 않는다.
- **조회:** RSS 피드는 저장된 공개 결정·공개본 revision·최초 게시 시각을 읽는 조회 모델이다. 피드용 루트나 저장 테이블을 만들지 않는다.
- **판단 보류:** 백링크·관련 글은 본문 링크를 영속 관계로 저장해야 하는 요구가 확인될 때 별도 조회 모델을 검토한다. 링크를 Post 간 관계로 저장하지 않는다.

## 현재 업무 규칙

**공개 여부는 이 앱의 관리자 명령과 DB 상태만 결정한다.** Notion의 `public_url`·휴지통·이동·접근 오류는 진단 정보일 뿐 공개 여부를 바꾸지 않는다. 글은 Notion에서만 만들고 고친다. 앱은 Notion 글 데이터베이스의 글을 찾아 revision으로 가져오고, 관리자가 미리보기를 확인한 뒤 발행한다. 공개 읽기는 저장된 공개본 revision만 제공하며 Notion을 호출하지 않는다. Notion·파일 서버 호출은 관리자의 새로고침과 스케줄러만 DB 트랜잭션 밖에서 한다.

| BC | 루트와 소유 상태 | 규칙 |
| --- | --- | --- |
| `blog` | Post: `PostId`, 소스 바인딩, `PostPublication`(공개 여부·공개본 revision·보관 여부·generation), 최신 revision 포인터, 최초 게시 시각, 카테고리 ID 집합. revision(`PostRevision`: 제목·요약·태그·표지·`BlockTree`·소스 revision)은 불변이며 수정·삭제하지 않는다. | 공개 여부는 발행·발행 취소·보관·복원 명령과 1회성 레거시 이전만 바꾼다. 동기화·카탈로그 스캔은 쓰지 않는다. 발행·취소·보관·복원은 generation을 올린다. 보관하면 비공개이며 동기화·발행 대상이 아니고, 복원해도 비공개다. 최초 게시 시각은 한 번 정하면 바꾸지 않는다. 카테고리는 관리자만 바꾸며 revision·공개 상태·generation과 독립적이다. |
| `blog` | BlogPublication: 레거시 공개 범위(루트 `PostId`·활성 revision) | 이전 데이터로 보존한다. 새 공개 판정에 쓰지 않는다. 새로 만드는 경우는 사이트 설정의 첫 적용뿐이며 `site_configuration.publication_id`가 참조할 빈 행(루트·활성 revision 없음)이다. |
| `blog` | SiteConfiguration: 단일 사이트의 `SiteDocuments`(루트는 선택, 헤더·푸터), 메타데이터, 표현 프로필 참조 | 설정 동기화에 실패하면 마지막 정상 설정을 유지한다. |
| `blog` | PresentationProfile: `PresentationProfileRef`(ID·버전), 키, 토큰, 신뢰된 자산 | 저장한 버전은 바꾸지 않는다. 키마다 명시적으로 활성화한 현재 버전만 선택한다. |
| `blog` | SyncState: `SyncTarget`별 다음 갱신 시각, 마지막 성공 시각, 연속 실패 기록(`MEDIA` 포함) | 실패 횟수와 마지막 실패 종류는 함께 존재한다. 성공하면 실패 기록을 지우고 실패하면 백오프한다. |
| `blog` | HomeConfiguration: 단일 홈의 목록 형태·정렬·쪽 크기·표시 항목 | 관리자만 저장한다. 설정이 없으면 목록형·최신순·10개·모든 항목 표시를 사용한다. Notion 설정 동기화는 읽거나 쓰지 않는다. |
| `blog` | Category: ID·이름·표시 순서 | 화면에서는 "태그"라고 부른다. 앱에서 생성·수정·삭제한다. 이름은 공백 제거 후 1~50자이며 대소문자 구분 없이 유일하다. 글에 배정된 카테고리는 삭제하지 않는다. |
| `blog` | Series: `SeriesId`·제목·설명·순서 있는 소속 `PostId` 목록 | 관리자만 생성·수정·삭제한다. 제목은 공백 제거 후 1~100자, 설명은 0~300자이며 소속 글은 중복되지 않는다. 글은 시리즈 0개 또는 1개에 속한다. 시리즈 삭제·제외·이동은 글·태그·revision·공개 상태·generation을 바꾸지 않는다. 새 시리즈는 비어 있고 기존 글을 자동으로 묶지 않는다. |

**하나의 BC:** 게시글·분류·홈 표현은 같은 블로그의 공개 대상을 다루며, 사이트 설정·표현 프로필·동기화도 이 공개 규칙을 따른다. Notion에서 가져온 내용과 앱이 관리하는 공개·분류·표현을 하나의 `blog` BC로 유지한다. 테이블·서비스·어댑터 수는 분리 근거가 아니다.

**여러 루트:** Post는 문서마다 revision이 쌓이고 관리자 명령으로 공개가 바뀌며, SiteConfiguration은 설정 동기화로, PresentationProfile은 배포 산출물로, SyncState는 대상별 일정으로 바뀐다. 생명주기와 동시 변경이 다르므로 별도 루트로 둔다. `SyncTarget`·`PostId`·`PresentationProfileRef`로 서로 참조한다.

HomeConfiguration은 Notion 설정 동기화 전에도 편집할 수 있고, Category와 Series는 글에 배정되기 전에 생성·수정할 수 있어 각각 독립 루트다. 홈 설정을 SiteConfiguration에 합치면 Notion이 전체 설정을 다시 저장할 때 관리자 선택을 덮어쓰므로 별도로 저장한다. 카테고리 배정은 Post의 자식 상태이며 별도 루트를 만들지 않는다.

### 홈과 카테고리

**용어:** 화면의 "태그"는 코드·DB의 `Category`(`category`·`post_category`)이며 ID·이름·표시 순서·다중 연결을 그대로 쓴다. 화면의 "Notion 원문 태그"는 revision의 `PostProperties.tags`다. 화면의 "태그 표시"는 `HomePostDetails.showCategories`다. 코드의 `tags`가 이미 원문 태그를 뜻하므로 `Category`를 `Tag`로 바꾸지 않는다.

- 홈은 목록형·썸네일 목록형·카드형 중 하나로 표시한다. 최초 게시 시각의 최신순·오래된 순을 선택하며 동률은 글 ID 순서다. 쪽 크기는 1~50개다. 요약·날짜·카테고리는 각각 표시 여부를 저장한다. 홈 설정은 마지막 저장이 우선한다.
- 표지는 반드시 목록 항목의 공개본 revision에서 읽는다. 사용할 표지가 없으면 앱 내장 `/ui/default-post-thumbnail.svg`를 쓴다. 기본 이미지의 업로드·URL 입력·외부 가져오기 기능은 두지 않는다.
- 홈은 저장된 설정을 사용한다. 관리자 화면의 저장 전 표시 예시는 공개 홈을 바꾸지 않는다. 기존 루트 문서의 내용·주소·공개 상태는 유지하되 임의의 ‘소개 문서’ 링크를 만들지 않는다.
- 글에는 카테고리를 여러 개 배정할 수 있고 빈 선택은 배정을 해제한다. 배정 변경은 즉시 목록에 반영하며 본문·Notion 태그·revision·발행 상태·generation을 바꾸지 않는다. 새 글은 미분류이고 기존 글을 자동 분류하지 않는다.
- 카테고리 이름은 대소문자 구분 없이 중복을 막고 표시 순서는 0 이상의 정수다. 같은 순서는 이름·ID로 정렬한다. 초안·보관 글을 포함해 배정된 글이 하나라도 있으면 삭제를 거절한다.
- 공개 카테고리 탐색과 개수에는 공개본이 있는 글만 포함하며 사이트 문서는 제외한다. 카테고리 필터 후 페이지를 나누고 다중 배정 때문에 글이 중복되지 않아야 한다. 잘못된·없는 카테고리는 404다. 페이지 이동은 선택한 카테고리를 유지한다.
- 표시 항목을 끄면 홈의 카테고리 탐색·배지를 숨기며 저장된 배정과 유효한 카테고리 링크의 조회는 유지한다. 공개 글 상세는 이 설정과 무관하게 앱 카테고리를 보여 준다. 공개 화면은 Notion 원문 태그를 보여 주지 않으며 원문 태그는 revision에 보존한다. RSS의 최신순·GUID·게시 시각은 바꾸지 않는다.
- 카테고리 삭제는 해당 행을 잠근 뒤 배정 여부를 확인하고, FK도 배정된 카테고리 삭제를 막는다. 기존 `PostRepository.save`는 게시 상태만 쓰고 동기화는 `post_category`·`series_post`를 쓰지 않으므로, 동기화·발행이 카테고리·시리즈를 덮어쓰지 않는다.
- **분류 저장:** 글 하나의 카테고리 선택·시리즈 선택·원문 태그 전환을 한 번에 저장하며, 성공이 아닌 결과는 아무것도 바꾸지 않는다. 원문 태그 후보는 공개 글이면 공개본, 그 밖의 글이면 최신 revision의 태그다. 고른 원문 태그는 대소문자 구분 없이 같은 이름의 카테고리를 쓰고, 없으면 마지막 표시 순서 뒤에 새로 만들어 그 글에 추가로 배정한다. 이름이 50자를 넘는 원문 태그는 보여 주되 옮기지 않는다. 관리자가 저장하기 전에는 원문 태그가 공개 탐색에 들어가지 않는다.
- **공개 검색:** 공개본 revision의 제목·요약에서 대소문자 구분 없이 부분 일치로 찾으며 `%`·`_`는 문자 그대로다. 카테고리 하나와 함께 적용하고 같은 조건으로 개수와 쪽을 나눈다. 미발행 변경·비공개·보관 글과 사이트 문서는 대상이 아니다. 검색어는 공백 제거 후 1~100자다.

### 시리즈

- 관리자는 제목·설명과 소속 글 전체의 순서를 한 번에 저장한다. 빠진 글은 시리즈에서만 빠지고, 다른 시리즈의 글은 그 시리즈에서 옮겨 온다. 분류 저장으로 시리즈를 바꾼 글은 새 시리즈의 마지막에 놓인다. 시리즈 저장은 마지막 저장이 우선한다.
- 공개 시리즈 목록·시리즈 화면·글의 시리즈 목차·이전/다음은 공개본이 있는 글만으로 순번과 개수를 계산하며 사이트 문서를 뺀다. 공개 글이 없는 시리즈는 목록에서 빠지고 시리즈 화면은 404다. 공개 목록은 제목·ID 순이며 대표 이미지는 첫 공개 글의 공개본 표지, 없으면 내장 썸네일이다. 제목은 공개본 revision에서 읽는다.
- 관리 화면의 소속 글 수와 시리즈 위치는 비공개·보관 글을 포함한 전체 소속 기준이다.

### 공개와 동기화 규칙

- 글을 제공하려면 **`PostPublication`이 공개이고 공개본 revision을 읽을 수 있어야 한다.** 비공개·보관·없는 ID·UUID가 아닌 ID는 404, 사이트 설정·표현 자산이 없거나 읽을 수 없는 revision은 503이다.
- 공개 HTML 경로는 `GET /`(글 목록), `GET /posts/{postId}`(내부 UUID), `GET /series`(시리즈 목록), `GET /series/{seriesId}`(시리즈)이다. RSS는 `GET /feed.xml`, 공개 파일은 `GET /media/{mediaKey}`다. 제목 기반 경로·slug·별칭을 만들지 않는다. 관리 경로는 `/admin/**`이며 조회는 `GET`, 새로고침·발행·발행 취소·보관·복원은 `POST`만 받는다.
- 경로는 애너테이션 컨트롤러로만 선언한다. 컨트롤러 경로의 HTTP 메서드·경로 정확한 목록은 `TargetArchitectureBoundaryTest`의 허용 목록과 REST Docs가 원본이다. 보안 체인이 소유한 로그인 제출(`POST /admin/login`)·로그아웃(`POST /admin/logout`)은 이 목록에 넣지 않고 `AdminSecurityConfig`와 `AdminSecurity*Test`가 원본이다. 정적 자원과 actuator 상태 경로도 컨트롤러 목록 대상이 아니다.
- **발행:** 미리보기가 준 `revisionId`와 `generation`을 함께 보낸다. 잠금 안에서 없음·보관됨·같은 revision으로 이미 공개(`AlreadyPublished`)·generation 또는 최신 revision 불일치(`StaleRevision`)·파일 미보관(`MediaNotReady`) 순으로 판정한다. 같은 revision이 이미 공개이면 generation 비교보다 먼저 `AlreadyPublished`일 수 있다. 검토하지 않은 revision은 공개되지 않는다.
- **발행 취소·보관:** 기대 버전 없이 항상 성공하며 Notion 없이 DB만 쓴다. 이미 비공개여도 generation을 올려 진행 중인 발행 요청을 무효로 만든다. 복원은 비공개로 되돌린다.
- **동기화는 초안만 만든다.** 최신 revision을 추가하고 포인터를 옮기며 공개 여부와 공개본 revision을 건드리지 않는다. 새 revision은 모든 업로드 파일의 보관이 끝난 뒤에만 만든다. 파일은 revision마다 새로 내려받고 SHA-256이 같으면 한 번만 저장하며, 이미 저장된 파일의 바이트·형식·저장 시각은 이후 가져오기가 바꾸지 않는다.
- **파일:** Notion 업로드 파일(`SourceHosted`)만 PostgreSQL에 바이트로 보관하고 `External`은 그대로 둔다. revision마다 `post_revision_media`에 원본 키와 보관 키를 묶으며 행은 수정하지 않는다. 레거시 행은 보관 키가 비어 있다. 복구는 그 행이 저장한 자기 URL, 또는 소스 revision이 정확히 같을 때의 새 URL로만 시도하며 만료되었으면 비어 있을 수 있다. 비어 있는 파일은 화면에서 사용할 수 없다는 표시로 렌더링하고 원본 URL로 대체하지 않으며 발행을 막고 누락 파일 수로 보인다. 공개 `/media`는 공개본 revision이 참조하는 파일만, 관리 미리보기는 모든 revision의 파일을 제공한다.
- **글 작성은 Notion 전용이다.** 앱은 Notion 페이지를 만들거나 고치지 않는다. 카탈로그 스캔이 글 데이터베이스에서 소스 문서를 찾으면 그때 Post를 등록하고 `PostId`를 정한다. 등록 전에 `PostId`를 예약하거나 요청 마커를 기록하지 않는다. 관리 화면은 Notion 편집 링크만 제공한다. Notion 쿼리의 `POST` 호출은 읽기 작업이다.
- **이전 데이터:** 시작할 때 한 번 [레거시 이전](#레거시-글-이전)이 실행된다. 이후 Notion의 `public_url`·`in_trash`·이동·`403`·`404`·타임아웃은 동기화 실패 종류로만 기록한다.
- 동기화는 갱신 시각이 지난 `SyncTarget`만 처리한다. 카탈로그 스캔은 별도 고정 지연 스케줄러가 실행하며 등록만 하고 해제하지 않는다.
- 스케줄러는 대상마다 `RuntimeException`을 격리해 작업 종류·예외 타입만 기록하고 다음 대상을 계속한다. 원인에 인터럽트가 있거나 그 밖의 `Throwable`이면 원래 예외를 전파한다. 스케줄러를 활성화한 인스턴스는 하나여야 한다.
- 관리자 새로고침 실패는 결과 종류로 답하고 작업·예외 타입·가장 안쪽 원인 타입만 진단 로그로 남긴다. 예외 메시지·스택·Notion 응답 원문은 기록하지 않는다.
- 본문의 `SourceDocument` 링크는 이 앱에서 공개 상태인 글만 내부 링크로 만든다. 비공개·알 수 없는 대상은 링크 없이 저장된 원래 표시(URL)를 유지하고, 명시적 외부 URL은 그대로 외부 링크다. 인라인 데이터베이스의 읽을 수 있는 행은 Notion `public_url`과 무관하게 가져오며 선택한 열·필터는 원본 설정을 따른다.
- 스냅샷 JSON에는 논리적 `kind`와 `schemaVersion`만 저장한다. 구현 클래스 이름·Jackson default typing·Notion 원본 응답을 저장하지 않는다.
- 블록 스타일은 의미 값만 저장한다. CSS·JavaScript·`<head>` 조각을 Notion에서 받거나 저장하지 않고, 표현은 등록된 프로필과 신뢰된 자산만 사용한다.
- 관리 화면은 관리자 아이디·비밀번호 설정이 하나라도 비면 닫힌다(`403`). 인증 설정은 `config`의 보안 설정 안에 두고 포트·유스케이스·모델은 인증 방식을 알지 못한다.

### 레거시 글 이전

- `MigrateLegacyPostsService`가 시작 시 웹 서버와 스케줄러보다 먼저 실행되고, 소스에 연결된 모든 글 중 아직 관리되지 않는 글을 `PostPublication`으로 가져온다. 실패하면 앱이 시작하지 않는다.
- 이전에 실제로 공개되던 글(활성 구성원·`PUBLISHED`·저장본 있음)만 같은 스냅샷의 복사본을 공개본으로 공개한다. 나머지는 비공개다. 기존 UUID·최초 게시 시각·히스토리와 실효 공개 상태를 보존한다.
- 레거시 테이블과 행은 읽기만 하고 수정·삭제하지 않는다. 이미 관리 중인 글은 건너뛰므로 다시 실행해도 변하지 않는다.

### Notion 설정 데이터 소스

`NOTION_SETTINGS_DATA_SOURCE_ID`의 데이터 소스를 API 버전 `2026-03-11`로 끝까지 조회한다. 다른 API 버전으로는 시작하지 않는다. 글 데이터 소스는 `notion.posts.*`(`NOTION_POSTS_*`)로 따로 지정하며 속성은 ID로 가리킨다. 데이터 소스 ID가 비면 카탈로그 스캔이 설정 오류로 실패한다.

| 속성 | 타입 | 규칙 |
| --- | --- | --- |
| `Key` | title | `rootPage`, `header`, `footer`, `head` 중 하나 |
| `Kind` | select | `page`, `blocks`, `head` 중 하나 |
| `Enabled` | checkbox | `true`인 첫 번째 같은 key 행만 적용 |
| `Page` | rich text | Notion page URL 또는 32자리 page ID |
| `Data` | rich text | `head` 행의 JSON 객체 |

- `rootPage`(`Kind=page`)는 선택이다. 있으면 레거시 루트 문서를 `/posts/{postId}`로 유지하고 사이트 문서(루트·헤더·푸터)는 목록·RSS에서 제외한다.
- `header`·`footer`(`Kind=blocks`)는 공개 상태이고 공개본을 읽을 수 있을 때만 렌더링하고, 아니면 본문을 실패시키지 않고 생략한다.
- `head`의 JSON은 `siteName`, `defaultDescription`, `languageTag`, `faviconAssetKey`, `presentationProfileKey`만 허용한다. 다른 필드·HTML·CSS·JavaScript·원격 자산 URL은 거부한다.

### 여러 루트를 함께 쓰는 업무

다음 업무는 불변식 때문에 한 쓰기 트랜잭션에서 여러 루트를 바꾼다. 범위를 넓히거나 나누지 않는다.

- **글 반영(`ApplyImportedPostService`):** Post의 revision·미디어 바인딩·최신 포인터와 SyncState 성공 기록. 소스 조회는 트랜잭션 밖에서 하므로 잠금 안에서 보관 여부를 다시 판정하고, 보관됐거나 관리되지 않는 글이면 파일·revision·포인터·일정을 쓰지 않는다(`NotFound`). 동기화 실패 기록도 같은 잠금·판정 뒤에만 쓴다. 같은 소스 revision이면 변경하지 않는다.
- **글 등록(`RegisterPostService`):** Post 등록과 SyncState 예약. 한 페이지는 하나의 `PostId`에 연결한다.
- **사이트 설정 적용(`ApplyImportedSiteConfigurationService`):** SiteConfiguration 저장, 첫 적용이면 `site_configuration.publication_id`가 참조할 빈 BlogPublication 생성, 사이트 문서의 Post 등록과 SyncState 예약, 설정 SyncState 성공 기록.
- **레거시 이전(`MigrateLegacyPostsService`):** 대상 글 전체의 Post 등록·복사 revision·SyncState 예약을 `migrate()` 한 번의 트랜잭션에서 처리한다.
- **분류 저장(`PostClassificationService.save`):** 새 Category(0개 이상), 지금 시리즈와 대상 시리즈, Post의 카테고리 연결. "분류 저장" 한 번이 부분 반영되지 않고, 옮긴 원문 태그가 글에 배정까지 되어야 한다. category 행 → series 행 → `post_publication` 행 순서로 잠근 뒤 잠근 글의 현재 공개 상태로 원문 태그 후보를 다시 판정하고 나서 처음 쓴다. 시리즈 이동은 지금 시리즈에서 빼는 쓰기를 먼저 한다.
- **시리즈 저장(`SeriesManagementService.save`):** 대상 Series와, 옮겨 오는 글을 가진 다른 Series. 글당 0..1 소속을 지키며 옮기려면 함께 바꿔야 한다. 관련 series 행을 ID 순서로 잠그고 다른 시리즈에서 빼는 쓰기를 먼저 한다. 잠그지 못한 시리즈가 그사이 같은 글을 가져가면 유일 제약 위반을 `SeriesMembershipConflictException`으로 바꿔 전체를 롤백한다.
- **RSS 조회(`GetPublicFeedService`):** 설정·공개 글·공개본 revision을 `REPEATABLE_READ` 읽기 전용 트랜잭션 하나에서 읽는다.

관리되는 같은 글에 대한 발행·발행 취소·보관·복원·동기화 반영·동기화 실패 기록·동기화 일정 취소는 `post_publication` 행 잠금으로 직렬화한다. `sync_state`를 함께 쓰는 경로는 이 잠금을 먼저 잡는다. 관리되지 않는 ID의 일정 취소는 잠글 행이 없으므로 남은 일정만 삭제하며, 새 등록과의 동시 실행은 이 행 잠금의 보장 대상이 아니다. 동일 소스의 동시 동기화는 단일 스케줄러 실행으로도 줄인다. 여러 인스턴스에서 스케줄러를 실행하려면 DB 잠금·lease를 먼저 설계한다.

### BC 간 연동 요구가 생기면

공개 데이터 계약·모델 번역·변경 책임·판정 시점·실패·재시도·원자성을 먼저 정하고 전달 기술을 선택한다. 각 BC의 유스케이스가 자기 변경·트랜잭션을 소유하는 것이 기본이다. 여러 BC 쓰기의 동시 커밋이 필요하면 분리 전제도 재검토하되 자동 병합하거나 실패를 무시한 연속 호출로 대체하지 않는다.

소비측 코어는 자기 모델·출력 포트를 쓰고 어댑터가 외부 공개 데이터를 번역한다. 인프로세스 어댑터가 상대 입력 포트를 호출하는 구현은 현재 검사에서 실패하므로 필요한 검사 변경과 정상·위반 fixture도 함께 설계한다. 같은 DB·JVM이라는 이유로 상대 테이블에 접근하거나 `config`의 공용 포트·함수 값으로 업무 결합을 숨기지 않는다.

## 패키지 배치

업무 패키지 하나가 BC 하나다. `blog.posts.domain` 같은 중첩 기능 경로는 금지하며 계층 아래에는 역할·구현 하위 패키지를 둘 수 있다.

```text
blog/domain/                         BC 안에서 의미·제약이 같은 공유 값 (SourceDocumentRef 등)
blog/domain/<aggregate>/             루트 하나와 소유 ID·값·엔티티 (post, publication, site, presentation, sync, home, category, series)
blog/domain/<aggregate>/<helper>/    같은 어그리게이트 내부 (post/block 등)
blog/application/model/              순수 유스케이스 데이터·저장 projection
blog/application/port/input/         유스케이스 계약
blog/application/port/output/        persistence·source·presentation·diagnostics 계약
blog/application/service/            유스케이스 구현
blog/adapter/inbound/<group>/        web(공개·관리 HTTP·Thymeleaf·RSS·파일), scheduling, startup
blog/adapter/outbound/<group>/       notion, media(파일 내려받기), persistence(Exposed·스냅샷 JSON), presentation, diagnostics
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
- Notion API DTO와 Spring HTTP 클라이언트(`RestClient`·요청 팩토리)는 `adapter/outbound/notion`에만 둔다. JDK `HttpClient` 같은 HTTP 전송은 Notion API 호출(`notion`)과 파일 내려받기(`media`) 출력 어댑터에만 둔다. 파일 내려받기는 Notion 토큰을 붙이는 클라이언트를 쓰지 않고 인증·쿠키 헤더를 보내지 않는다. JSON 처리는 Notion과 `persistence` 출력 어댑터 안에 둔다. 도메인·애플리케이션은 Notion ID·DTO·응답을 알지 못한다.
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
- 루트를 다루는 출력 포트는 이름과 무관하게 저장소다. **포트 하나의 루트는 최대 하나, 루트 하나의 출력 포트도 최대 하나**이며 상속·제네릭에도 적용한다. 루트를 노출하지 않는 조회용 `Store`는 별도 계약이다. 현재 대응은 Post-`PostRepository`, BlogPublication-`PublicationRepository`, SiteConfiguration-`SiteConfigurationRepository`, PresentationProfile-`PresentationProfileRepository`, SyncState-`SyncStateRepository`, HomeConfiguration-`HomeConfigurationRepository`, Category-`CategoryRepository`, Series-`SeriesRepository`다.

### BC 경계

- `HexagonalArchitectureTest.contextAggregates`에 `blog → post.Post, publication.BlogPublication, site.SiteConfiguration, presentation.PresentationProfile, sync.SyncState, home.HomeConfiguration, category.Category, series.Series`를 유지하고 새 BC·루트를 같은 변경에서 등록한다.
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
- 쓰기 서비스(`Apply*`, `Register*`, `Publish*`, `Cancel*`, `Migrate*`)와 관리 명령 서비스(`CategoryManagementService`, `SeriesManagementService`, `PostClassificationService`, `HomeConfigurationService`)의 명령은 쓰기 `@Transactional`, 조회 서비스(`GetPublicPostService`, `AdminPostQueryService`, `SynchronizationQueryService` 등)는 `@Transactional(readOnly = true)`를 쓴다. `GetPublicFeedService`는 `REPEATABLE_READ` 읽기 전용이다. 관리되는 글의 발행·취소·보관·복원, 글 반영과 그 실패 기록, 동기화 일정 취소는 `post_publication` 행을 잠근 쓰기 트랜잭션 안에서 보관 여부까지 판정한다. 트랜잭션 밖에서 읽은 보관 여부로 쓰기를 결정하지 않는다. 기본 `REQUIRED`의 기존 트랜잭션 참여 범위도 확인한다.
- Notion·파일 서버를 호출하는 `Synchronize*Service`는 트랜잭션을 열지 않는 오케스트레이터다. 공개 조회는 Notion을 호출하지 않고, 관리자의 새로고침만 DB 트랜잭션 밖에서 호출한다. 외부 HTTP 호출 동안 DB 트랜잭션을 유지하지 않고 읽기·쓰기 서비스 호출로 DB 경계를 나눈다. 스케줄러는 갱신 대상 조회와 서비스 호출만 한다.
- Spring이 관리하는 빈을 외부에서 호출한다. 직접 생성한 객체나 자기 객체 내부 호출을 새 트랜잭션 경계로 취급하지 않는다. `readOnly`를 접근 제어나 쓰기 차단에 쓰지 않는다.
- 기본 롤백 대상은 `RuntimeException`과 `Error`다. 그 밖의 예외도 롤백해야 한다면 `rollbackFor`를 명시한다. 여러 저장을 쓰는 쓰기 경계는 Exposed의 `ExposedSQLException`이 검사 예외이므로 `@Transactional(rollbackFor = [Exception::class])`를 명시하고 원인을 보존해 전체를 롤백한다.
- 가상 스레드에서도 JDBC 트랜잭션 안에서 비동기·코루틴 디스패처 전환으로 작업을 넘기지 않는다. 가상 스레드를 이유로 연결 풀 제한을 없애지 않고 트래픽·쿼리에 맞게 조정한다.
- 모든 입력·출력 어댑터의 클래스·메서드는 직접·합성·상속 `@Transactional`, 트랜잭션 인프라 의존, 수동 트랜잭션 제어를 금지한다. 연결·커넥터 경유나 메서드 참조도 같다. 일반 저장소 조회는 허용한다. 정확한 탐지 대상과 JTA 도입 조건은 [트랜잭션 검사](quality-maintenance.md#트랜잭션-검사-확장)에 있다.
- 여러 루트의 원자적 쓰기는 [여러 루트를 함께 쓰는 업무](#여러-루트를-함께-쓰는-업무)에 기록하고 위 분류 절차에서 먼저 재검토한다.

## 자동 검사와 완료 조건

- 선택 근거·BC와 루트 등록·[기본 범위](development.md#테스트-범위)의 불변식과 실패 경로 테스트를 남기고 [전체 빌드](development.md#실행과-검증)를 통과한다.
- 공통·DDD·BC 검사를 운영 코드에 함께 적용한다. Notion DTO·HTTP 클라이언트·JSON·Exposed 위치, 컨트롤러의 HTTP 메서드·경로 목록과 경로 등록 방식, 스냅샷 형식, 시각 사용은 `TargetArchitectureBoundaryTest`가 추가로 검사한다.
- 정적 검사 통과만으로 업무 의미·깊은 불변성·조립 내용·실제 변경 대상을 보장한다고 보고하지 않는다.
- 누락된 루트 표식, 함수 값·반사·인라인·값 클래스 소거를 통한 결합은 코드 검토와 행동 테스트로 확인한다. 요구에 없는 사업 결정을 추측하지 않는다.
