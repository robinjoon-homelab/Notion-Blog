# 배포 작업 지침

## 공용 파일 사용

- [.github/workflows/ci.yml](../.github/workflows/ci.yml), [scripts/deploy.py](../scripts/deploy.py), `scripts/tests`, [Dockerfile](../Dockerfile), `.dockerignore`, Gradle 빌드 구성을 템플릿과 공통으로 유지한다.
- 앱별 값은 아래 변수와 [최초 워크로드 생성 설정](#최초-워크로드-생성-설정)으로 지정한다. 기능 추가를 위해 공용 배포 파일을 개별 수정하지 않는다.
- 이 앱은 Notion 런타임 환경변수와 기존 DB·도메인 때문에 `.github/deployment.json`을 유지한다. `scripts/tests/test_notion_blog_deployment.py`가 이 설정의 내용을 검증한다.
- CI 변경 시 PR 소스 커밋 검사를 유지한다. 신뢰하지 않는 PR에 배포 자격 증명을 제공하거나 `pull_request_target`으로 실행하지 않는다.

## 배포 전 설정

- 서비스 저장소의 **Template repository 설정을 끈다**.
- 플랫폼의 SMS에 저장소·`.github/workflows/ci.yml`·`refs/heads/master`가 등록되어 있고 `zot`·`harness` 자격 증명을 받을 수 있는 상태에서만 배포한다.
- 운영 기본 브랜치는 `master` 하나다. **다음 `master` 푸시 전에** 배포 변수를 설정한다.
- 자격 증명을 코드·Variables에 넣지 않는다. SMS 거절은 등록·권한 설정을 바로잡고 저장소 Secrets로 우회하지 않는다.

## 배포 이름과 이미지 설정

GitHub Actions Variables에 다음 값을 설정한다.

| 변수 | 설정할 값 |
| --- | --- |
| `HOMELAB_APP_NAME` | 하네스의 기존 앱 이름 `notion-blog`. 소문자 영숫자·하이픈 1~50자. 시작·끝은 영숫자. |
| `HOMELAB_REGISTRY_HOST` | 스킴·경로·포트 없는 호스트. 점이 있는 주소 또는 `localhost`. |
| `HOMELAB_REGISTRY_IMAGE` | 호스트·태그를 제외한 하네스의 기존 이미지 경로. |

- 기존 앱은 하네스의 앱 이름·이미지 repository를 사용한다. 저장소 이름·ID로 대상을 추측하거나 불일치를 앱 삭제·검사 우회로 해결하지 않는다.

## 컨테이너와 DB 계약

- `build/libs/application.jar`, JRE 25, 컨테이너 포트 **8080**, 비루트 UID/GID `10001`, `linux/amd64`를 유지한다.
- `prod` 프로필에서 PostgreSQL을 사용하고 `SPRING_DATASOURCE_URL`, `SPRING_DATASOURCE_USERNAME`, `SPRING_DATASOURCE_PASSWORD`를 설정한다. H2로 대체하지 않는다.
- `NOTION_TOKEN`, `NOTION_SETTINGS_DATA_SOURCE_ID`, `BLOG_ADMIN_PASSWORD`는 Secret 참조로 주입한다. 값은 저장소·이미지·로그에 넣지 않는다. 글 데이터 소스(`NOTION_POSTS_*`), 관리자 계정 아이디(`BLOG_ADMIN_USERNAME`), 파일 한도(`BLOG_MEDIA_*`)는 [개발 지침](development.md#실행-환경)의 이름으로 하네스에서 설정한다. `.github/deployment.json`은 최초 생성에만 적용된다.
- 관리자 아이디·비밀번호 중 하나라도 비면 관리 화면은 닫힌다(`403`). 업로드 파일은 PostgreSQL에 저장되므로 DB 백업과 용량에 포함한다.
- 앱 JVM에 힙·OOM 옵션을 임의로 추가하거나 하네스 메모리 제한을 바꾸지 않는다.
- liveness는 `/actuator/health/liveness`, readiness는 `/actuator/health/readiness`(애플리케이션 준비 상태와 DB)다. Notion 장애를 readiness에 연결하지 않는다. Kubernetes 프로브 연결을 보고할 때는 실제 워크로드 설정을 확인한다.
- 스케줄러는 다중 인스턴스 조정을 하지 않는다. 스케줄러를 활성화한 인스턴스는 하나만 둔다.
- 공개 범위는 [사용 범위](../README.md#사용-범위)를 따른다.

## 배포 없이 검증

- 공용 배포 파일이나 `.github/deployment.json`을 변경하면 다음 검증을 수행한다. 로컬 검증에서 `scripts/deploy.py apply`를 실행하지 않는다.

```sh
APP_NAME=notion-blog REGISTRY_HOST=registry.homelab.robinjoon.xyz \
  REGISTRY_IMAGE=apps/notion-blog python3 scripts/deploy.py plan
python3 -m unittest discover -s scripts/tests -v
./gradlew build
docker build -t notion-blog:local .
```

## 배포 실행과 확인

- 검증만 필요하면 PR을 사용한다. 실제 배포에는 `master` 푸시 또는 해당 브랜치의 수동 실행을 사용한다.
- 워크플로가 만든 이미지 태그를 사용하고 `latest`나 임의 태그로 바꾸지 않는다.
- 배포 단계가 건너뛰어지지 않았는지, 하네스의 이미지 태그가 대상 커밋과 일치하는지, `https://blog.homelab.robinjoon.xyz`와 `/actuator/health` 응답이 정상인지 확인한다. 태그 반영만 확인했다면 정상 가동으로 보고하지 않는다.
- 실패 후 재실행하기 전에 하네스 실행과 현재 태그를 확인한다. 원격 작업 중단이나 자동 롤백을 가정하지 않는다.

## 최초 워크로드 생성 설정

- `.github/deployment.json`은 DB `notion_blog`, 도메인 `blog.homelab.robinjoon.xyz`, prod·DB·Notion·동기화·공개 주소 환경변수를 선언한다. [deploy.py](../scripts/deploy.py)의 `identity`·`default_environment`와 위 `plan` 출력에서 최종 설정을 확인한다.
- 허용 필드는 `database`, `host`, `env`, `serviceAccountName`이다. **최초 생성에만 적용**하며 기존 앱의 설정 변경은 하네스에서 처리한다. 앱 이름을 바꿔 데이터 이전을 대신하지 않는다.
- `env`에는 **교체할 전체 목록**을 쓰고 prod·DB 설정을 포함한다. URL의 DB명은 `database`와 맞추며, `DB_HOST`는 설정하지 않고 `DB_PORT`는 이를 참조하는 URL보다 먼저 선언한다.
- 비밀값은 `secretKeyRef`로 지정한다. 공용 DB Secret `shared-db-app`과 Notion Secret `notion-blog-runtime`을 먼저 준비하고 `plan`으로 최종 설정을 확인한다.

## 전환 배포와 롤백

이 절은 절차이며 실제 배포·운영 데이터 이전은 수행되지 않았다. 운영 데이터가 이전되었다고 보고하지 않는다.

- **사전 조건:** 새 앱을 시작하기 전에 **모든 이전 인스턴스의 스케줄러와 쓰기 작업을 중지**한다. 이전 앱과 새 앱이 함께 쓰면 시작 시 이전 중간에 레거시 원본 상태가 바뀔 수 있다.
- **백업:** DB(`pg_dump`)와 복원 확인을 먼저 끝내고 스테이징에서 같은 절차를 검증한다. 업로드 파일은 PostgreSQL에 저장되므로 DB 백업에 포함된다.
- **시작 이전:** Flyway V1~V7은 그대로이고 V8은 추가형이다. 시작할 때 `MigrateLegacyPostsService`가 `SmartInitializingSingleton`으로 웹 서버와 스케줄러보다 먼저 한 번 실행된다. 관리되지 않는 글만 가져오고 이미 관리 중인 글은 건너뛰며, 실패하면 앱이 시작하지 않는다. 이전에 실제로 공개되던 글만 같은 내용으로 계속 공개하고 나머지는 비공개이며, 레거시 행은 수정·삭제하지 않는다.
- **첫 배포는 `BLOG_ADMIN_USERNAME`과 `BLOG_ADMIN_PASSWORD`를 설정하지 않은 채** 한다(관리 화면 닫힘).
- **파일 보존 확인:** 시작 이전은 파일 참조만 복사하며 만료된 Notion 파일의 바이트를 복구하지 않는다. 시작이 이를 강제하지 않는다. 비공개·보관 글의 기존 데이터도 보존 대상이므로 공개 여부와 상관없이 **모든 revision**의 빈 보관 키를 센다. 관리 화면이 닫힌 상태에서는 읽기 전용 SQL로 확인하고, 동기화가 한 번 돈 뒤에도 0이어야 한다.

```sql
-- 전체 (게이트): 모든 revision의 보관되지 않은 파일
select count(*) from post_revision_media where media_key is null;
-- 참고: 현재 공개본이 참조하는 것만
select count(*) from post_publication p
join post_revision_media m on m.revision_id = p.published_revision_id
where p.visibility = 'PUBLIC' and m.media_key is null;
```

  전체 수가 0이 아니면 트래픽 전환과 관리 화면 개방을 미루거나 사용자의 명시적 결정을 받는다. 공개본 수만 0이라고 통과시키지 않는다. 관리 화면의 `missingMedia`는 최신·공개본 revision만 세므로 전체 수와 다를 수 있다. 인증된 관리 화면 확인은 격리된 스테이징에서만 한다.
- **확인:** 이전에 공개되던 글의 UUID·`/feed.xml` GUID·최초 게시 시각이 그대로이고, 그 밖의 글이 공개로 바뀌지 않았다. 이를 확인한 뒤 두 값을 설정해 관리 화면을 연다.
- 스케줄러 인스턴스는 하나만 둔다.
- **롤백:** 새 앱이 한 번이라도 시작해 글을 관리하기 시작하면 이전 이미지로 돌아가는 것을 안전한 롤백으로 보지 않는다. 이전 이미지가 쓰는 공개 상태는 새 앱에 반영되지 않고, 이미 관리 중인 글은 이전 시 건너뛰므로 롤백 기간의 Notion 쪽 변경을 새 앱이 다시 가져오지 않는다. 이전 이미지로 돌아갔다면 다시 전환하기 전에 공개 상태를 사람이 대조해 맞춘다(롤백 기간의 변경 포함). 관리자 발행 취소는 항상 보존한다. 문제는 전환 이미지 안에서 고치거나, 백업 복원의 영향(복원 시점 이후의 관리자 명령 손실)을 확인한 뒤에만 복원한다. 마이그레이션은 추가형이라 새 테이블을 지우지 않는다.
