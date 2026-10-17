# Notion Blog

## 작업 시작

- 에이전트는 [AGENTS.md](AGENTS.md)에서 작업에 필요한 절을 선택한다.
- 실행은 [개발 지침](docs/development.md#실행과-검증), 공통 템플릿 갱신은 [템플릿 적용](docs/development.md#템플릿-적용)을 따른다.
- 검토자는 변경된 동작·선택 근거·검증 결과·남은 제약을 코드와 테스트에서 확인한다.

## 사용 범위

- 공개 화면은 인증 없이 관리자가 발행한 글만 읽기 전용으로 제공한다. `/admin/**`은 고정 아이디·비밀번호로 로그인한 단일 운영자만 쓰며 설정이 비면 닫힌다. 이 앱의 발행 상태 규칙을 거치지 않고 Notion 문서나 저장된 revision을 제공하지 않는다.
- 글은 Notion에서만 만들고 고친다. 앱은 글 가져오기·공개 여부·홈 표시·카테고리를 관리하며 Notion 페이지를 만들거나 수정하지 않는다. 홈과 분류 규칙은 [홈과 카테고리](docs/ddd-architecture.md#홈과-카테고리)를 따른다.
- Notion 토큰·설정 데이터 소스 ID·관리자 비밀번호·DB 자격 증명은 실행 환경에서 주입한다. 저장소·이미지·로그·테스트 fixture에 넣지 않는다.

## 작업별 원본

- [아키텍처](docs/ddd-architecture.md): BC·어그리게이트 선정, 업무 규칙과 계층·트랜잭션 규칙.
- [코드 품질](docs/code-quality.md): 수치 제한·예외·금지 사항.
- [개발 절차](docs/development.md): 기능·API·DB 변경, 테스트 범위와 검증.
- [배포](docs/deployment.md): 공용 파일 유지·앱별 설정·실행·확인.
- [검사기 유지보수](docs/quality-maintenance.md): 검사 규칙·빌드 연결을 바꿀 때만 참조.

## 전환 기획

- [Notion 데이터베이스 기반 블로그 전환 설계](docs/plans/notion-backed-blog/README.md): 기획, 화면·사용 흐름, 구현 범위. 운영 규칙은 위 원본 문서를 따르며 실제 배포와 운영 데이터 이전은 아직 수행되지 않았다.
