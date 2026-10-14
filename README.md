# Notion Blog

## 작업 시작

- 에이전트는 [AGENTS.md](AGENTS.md)에서 작업에 필요한 절을 선택한다.
- 실행은 [개발 지침](docs/development.md#실행과-검증), 공통 템플릿 갱신은 [템플릿 적용](docs/development.md#템플릿-적용)을 따른다.
- 검토자는 변경된 동작·선택 근거·검증 결과·남은 제약을 코드와 테스트에서 확인한다.

## 사용 범위

- 인증·인가 없이 Notion에서 게시한 문서만 공개하는 읽기 전용 블로그다. 공개 범위·게시 상태 규칙을 거치지 않고 Notion 문서나 저장된 스냅샷을 제공하지 않는다.
- Notion 토큰·설정 데이터 소스 ID·DB 자격 증명은 실행 환경에서 주입한다. 저장소·이미지·로그·테스트 fixture에 넣지 않는다.

## 작업별 원본

- [아키텍처](docs/ddd-architecture.md): BC·어그리게이트 선정, 업무 규칙과 계층·트랜잭션 규칙.
- [코드 품질](docs/code-quality.md): 수치 제한·예외·금지 사항.
- [개발 절차](docs/development.md): 기능·API·DB 변경, 테스트 범위와 검증.
- [배포](docs/deployment.md): 공용 파일 유지·앱별 설정·실행·확인.
- [검사기 유지보수](docs/quality-maintenance.md): 검사 규칙·빌드 연결을 바꿀 때만 참조.
