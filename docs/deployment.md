# Notion Blog 배포 계약

이 저장소는 애플리케이션, Gradle/Flyway 설정, 단일 Docker 이미지와 GitHub Actions CI를 소유한다. `.github/deployment.json`은 공통 배포 API에 전달할 최초 생성 설정이다. 실제 워크로드·DB·Ingress·Secret·리소스·프로브 연결은 [Simple-K3S-Herness](https://github.com/robinjoon-homelab/Simple-K3S-Herness)가 소유한다.

## 유지하는 운영 식별자

2026-10-08 공통 템플릿의 로컬 변경본을 반영한다. 이름은 GitHub 저장소명이나 숫자 ID에서 만들지 않으며 CI의 `APP_NAME: notion-blog`로 고정한다. 공통 helper와 테스트는 템플릿과 동일하게 유지하고 앱별 회귀는 별도 테스트 파일에 둔다.

- 앱 `notion-blog`, 컨테이너 `app`, PostgreSQL DB `notion_blog`.
- 공개 주소 `https://blog.homelab.robinjoon.xyz`, 컨테이너 포트 `8080`, 이미지 플랫폼 `linux/amd64`.
- 레지스트리는 GitHub Variables `HOMELAB_REGISTRY_HOST`와 `HOMELAB_REGISTRY_IMAGE`로 지정한다. 확인한 하네스 이미지 저장소는 `registry.homelab.robinjoon.xyz/apps/notion-blog`다. 발행 대상과 하네스의 이미지 저장소를 함께 유지해야 한다. 릴리스는 기존 저장소의 태그만 변경한다.
- 기존 운영 브랜치 `master`의 push와 `workflow_dispatch`를 사용한다. 다른 브랜치의 수동 실행은 검증만 수행한다.

확인 근거는 애플리케이션의 `.github/workflows/ci.yml`·`application.yml`과 로컬 하네스 커밋 [`11ce95932b56c1b7c08954dce5d75fb643636ab0`](https://github.com/robinjoon-homelab/Simple-K3S-Herness/tree/11ce95932b56c1b7c08954dce5d75fb643636ab0)의 `workloads/notion-blog/values.json`이다. 이 근거는 실행 중인 클러스터의 현재 설정을 검증한 결과는 아니다.

## CI 검증과 이미지 전달

PR은 `opened`, `synchronize`, `reopened`, `ready_for_review`에 검증한다. PR 소스 저장소와 head SHA를 명시해 체크아웃하고 저장소 자격 증명을 남기지 않는다. 권한은 `contents: read`, PR의 Gradle 캐시는 읽기 전용이다.

1. 네트워크 호출을 모의 처리한 Python 배포 테스트와 `./gradlew build --no-daemon`을 실행한다. Gradle 빌드는 포맷·detekt·사용자 정의 규칙 테스트·애플리케이션 테스트·문서 생성을 포함한다.
2. `git diff --exit-code`로 자동 포맷 변경도 실패시킨다.
3. 발행 가능한 `master` 실행에서 검증한 `build/libs/application.jar`를 보존 기간 **1일**의 아티팩트로 저장한다. 테스트·품질 보고서와 생성 문서는 성공 여부와 관계없이 **7일** 보관한다.
4. 발행 잡이 같은 실행의 JAR를 내려받고 `deploy.py metadata`로 앱 이름·레지스트리·초기 설정을 검증한다. 검증은 SMS/OIDC와 레지스트리 로그인보다 먼저 실행한다.
5. Dockerfile은 검증한 JAR를 JRE 25 이미지에 넣는다. Docker 빌드 중 Gradle을 다시 실행하지 않는다. Docker context는 Dockerfile과 해당 JAR만 허용한다.
6. metadata가 만든 `sha-<전체 SHA>-run-<실행 ID>-<재실행 횟수>` 불변 태그를 이미지 발행과 배포 요청 양쪽에 사용한다.

발행 잡은 순차 실행하며 새 실행이 진행 중인 릴리스 확인을 취소하지 않는다. PR 검증은 새 커밋이 오면 이전 실행을 취소한다. 이미지를 발행한 뒤 현재 `master`의 최신 SHA가 아니면 하네스 변경을 건너뛴다.

## SMS와 토큰 권한

`load-ci-secrets@v1.0.0`이 GitHub OIDC로 SMS의 `zot`·`harness` 객체를 읽는다. `zot`은 `REGISTRY_USERNAME`·`REGISTRY_PASSWORD`, `harness`는 `HARNESS_ACTIONS_TOKEN`을 제공한다. 저장소별 GitHub Secrets로 같은 값을 다시 등록하지 않는다.

SMS는 이 저장소, `.github/workflows/ci.yml`, `refs/heads/master`를 허용해야 한다. `id-token: write`는 발행 잡에만 부여하며 PR 검증은 SMS 자격 증명을 요청하지 않는다.

`HARNESS_ACTIONS_TOKEN`은 배포 API 인증, 하네스 `main`의 Contents 조회, `apply-workload.yml`·`release-workload-image.yml` 실행과 생성 실행 결과 조회가 가능해야 한다. 배포 API는 호출자의 토큰으로 GitHub에 접근한다. Actions 실행에는 `Actions: write`, 실행 조회에는 `Actions: read` 권한이 필요하다. Contents 읽기 가능 여부는 공개 저장소 접근과 토큰 정책에 따라 다르므로 실제 접근 조건을 확인한다. 애플리케이션 브랜치 조회는 별도의 `github.token`을 사용한다.

이전 Actions 실행 결과만 확인하던 예외는 제거했다. 공통 템플릿과 같이 하네스 main의 목표 태그를 조회하며, 필요한 토큰 권한·SMS 신뢰 정책과 실제 값은 로컬 검증에서 조회하거나 변경하지 않는다.

## 릴리스 성공 조건과 실패 처리

`scripts/deploy.py apply`는 push 또는 수동 실행의 main/master만 허용한다. 이 앱의 CI gate는 기존처럼 master만 허용한다. 전달된 태그가 현재 SHA·실행 ID·재실행 횟수의 불변 태그와 다르면 API 호출 전에 거절한다. 이미지 게시 후 현재 브랜치 HEAD를 다시 확인하고 오래된 SHA이면 하네스 요청을 생략한다.

1. 배포 API의 `GET /v1/apps/notion-blog`를 조회한다. HTTP 404만 없는 앱으로 해석한다. 인증 오류, 서버 오류, JSON null·배열·잘못된 응답은 생성 근거가 아니다.
2. 없는 앱이면 `.github/deployment.json`에서 만든 payload로 생성을 요청한다. 반환된 실행 ID의 `committed` 또는 `unchanged` 결과를 기다린 뒤 워크로드를 다시 읽는다. payload는 현재 repository·tag를 포함한 완전한 `app` 컨테이너를 전달한다.
3. 기존 앱이면 정확히 하나의 `app` 컨테이너와 지정한 repository를 확인한다. 다른 repository나 잘못된 컨테이너 목록은 실패한다. 태그가 다르면 `release-workload-image.yml`에 `app=notion-blog`, `container=app`, `tag=IMAGE_TAG`만 전달한다. 이미 목표 태그면 요청을 생략한다.
4. 하네스 main에서 목표 repository와 태그를 확인하고, 조회한 Ingress의 실제 URL을 Actions 요약에 표시한다. 기존 DB·도메인·환경변수·ServiceAccount는 재적용하지 않는다.

생성 결과와 태그 반영은 각각 **최대 60회**, 미완료 응답 뒤 **10초 간격**으로 조회한다. HTTP 요청 제한은 **30초**, GitHub CLI 호출 제한은 **60초**다. 요청 시간이 추가되므로 600초의 전체 상한은 아니다. 발행 잡 전체 제한 **30분**에는 이미지 빌드·게시도 포함된다.

잘못된 생성 실행 ID·응답, 인증·HTTP 오류, CLI 실패, 생성 실패·취소·건너뜀과 시간 초과는 실패로 처리한다. 오류 출력에는 토큰이나 원본 HTTP 응답을 포함하지 않는다. 통신 오류의 자동 재요청이나 실패 시 롤백은 하지 않는다.

**성공은 하네스 main에 목표 이미지가 기록됐다는 뜻이다.** Argo CD 동기화, Pod 준비 상태, DNS와 TLS를 검증한 결과는 아니다. 요청 후 확인이 실패해도 원격 실행이 계속될 수 있으므로 재실행 전에 하네스 실행과 현재 태그를 확인한다.

## 최초 생성 설정과 Secret 사전조건

`.github/deployment.json`은 `database`, `host`, `env`, 선택적 `serviceAccountName`만 지원한다. 블로그는 DB `notion_blog`, 도메인 `blog.homelab.robinjoon.xyz`, replica 1, 포트 8080의 `app` 컨테이너와 Service·HTTPS Ingress를 생성한다. `serviceAccountName`은 지정하지 않으며 별도 ServiceAccount·RBAC를 생성하지 않는다.

`env`는 기본 목록을 전부 교체하므로 prod·DB·Notion 설정을 모두 선언한다. `DB_HOST`는 하네스가 먼저 주입한다. `shared-db-app/port`를 참조하는 `DB_PORT`는 JDBC URL보다 앞에 두고, DB 사용자명·비밀번호는 같은 Secret의 `username`·`password`를 참조한다. helper는 `DB_PORT` 선언 전에 이를 참조하는 잘못된 순서를 거절한다. 논리 DB 생성은 공유 PostgreSQL에 요청하며 새 DB 서버나 사용자 자격 증명을 만들지 않는다.

`notion-blog` namespace에 다음 Secret 참조를 준비해야 한다. helper는 실제 Secret 존재 여부나 값을 조회하지 않는다.

- `shared-db-app`: `port`, `username`, `password`. 플랫폼의 공용 DB Secret 반영 설정이 필요하다.
- `registry-credentials`: 이미지 pull에 사용할 플랫폼 Secret.
- `notion-blog-runtime`: `NOTION_TOKEN`, `NOTION_SETTINGS_DATA_SOURCE_ID`. 읽을 Notion 콘텐츠와 설정 데이터 소스에 integration 접근 권한이 필요하다.

최초 생성은 `NOTION_API_VERSION=2026-03-11`, `BLOG_SYNCHRONIZATION_ENABLED=true`, `BLOG_SYNCHRONIZATION_INTERVAL_MS=60000`, `BLOG_SYNCHRONIZATION_SUCCESS_INTERVAL=1m`, `BLOG_PUBLIC_BASE_URL=https://blog.homelab.robinjoon.xyz`를 사용한다. 기존 하네스의 `BLOG_BASE_URL`은 앱이 읽는 설정명이 아니지만, 기존 릴리스 경로는 환경변수를 변경하지 않으므로 자동 교정하지 않는다.

이 설정은 **최초 생성에만 사용**한다. 이후 설정 변경은 하네스에서 별도로 수행한다. 앱 이름을 바꾸면 다른 워크로드를 대상으로 하며 기존 DB 이전·삭제는 하지 않는다.

## 런타임과 데이터베이스

Dockerfile은 JRE 25와 UID/GID `10001:10001`을 사용한다. PostgreSQL을 사용하므로 템플릿의 H2 데이터 디렉터리를 만들지 않는다.

컨테이너는 `java -jar /app/application.jar`로 실행하며 JVM·메모리는 JRE 기본값을 사용한다. 명시적인 힙 크기, 메모리 비율과 OOM 종료 옵션을 이미지나 앱 배포 설정에 넣지 않는다. Gradle 빌드 JVM 설정과 Spring 가상 스레드 설정은 유지하며 기존 하네스의 리소스 제한도 변경하지 않는다.

하네스는 `SPRING_DATASOURCE_URL=jdbc:postgresql://$(DB_HOST):$(DB_PORT)/notion_blog`, `SPRING_DATASOURCE_USERNAME`, `SPRING_DATASOURCE_PASSWORD`를 주입한다. 기존 릴리스에서는 DB 설정과 Secret 참조를 그대로 보존한다.

Flyway는 애플리케이션 시작 시 실행한다. `/actuator/health/liveness`와 `/actuator/health/readiness`를 제공하며 Notion 장애는 readiness를 실패시키지 않는다. scheduler는 다중 인스턴스 조정을 하지 않으므로 scheduler가 활성화된 인스턴스는 **하나**여야 한다. 실제 프로브 연결과 실행 보안 정책은 하네스가 소유한다.

## 배포 없이 검증

```sh
APP_NAME=notion-blog REGISTRY_HOST=registry.homelab.robinjoon.xyz \
  REGISTRY_IMAGE=apps/notion-blog python3 scripts/deploy.py plan
python3 -m unittest discover -s scripts/tests -v
./gradlew test
./gradlew build
docker build -t notion-blog:local .
docker run --rm --env-file local.env -p 8080:8080 notion-blog:local
```

`scripts/deploy.py apply`는 실제 릴리스 요청을 보내므로 로컬 검증에 사용하지 않는다. 위 이미지 실행에는 기존 README의 PostgreSQL·Notion 환경변수가 필요하다.

`.github/CODEOWNERS`와 `.github/rulesets/main-master.json`은 검토 가능한 로컬 설정이다. 파일만으로 GitHub 병합 보호가 활성화되지 않는다. 서버에 적용하려면 `Build and test` 검사가 통과한 뒤 실제 write 권한이 있는 담당자와 별도 승인자를 확인해야 한다. 제공한 ruleset은 **승인 1개**, 코드 소유자 승인, 마지막 푸시의 다른 사람 승인과 미해결 대화 해소를 요구한다. 현재 CI 운영 브랜치는 `master`이며 `main`으로 이전할 때는 CI·SMS 허용 브랜치도 함께 변경해야 한다.
