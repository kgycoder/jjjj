# Arena Agent

LMArena(`https://arena.ai/text/direct?model_a=max`) 웹을 AI 백엔드로 쓰고, Android 접근성 서비스로 기기를 직접 조작하는 에이전트 앱.

## 빌드 (GitHub Actions)
1. 이 폴더를 GitHub 저장소에 push (main/master)
2. Actions 탭 → **Build APK** → Artifacts 의 `arena-agent-debug-apk` 다운로드
3. APK 설치

## 첫 실행
1. 앱 실행 → 로딩 UI → Google 로그인 창 → 로그인 (쿠키는 저장되어 재실행 시 유지)
2. 상단 좌측 버튼 또는 "접근성 서비스 켜기" → **Arena Agent** 활성화
   - Android 13+ 사이드로드 앱: 앱 정보 → ⋮ → **제한된 설정 허용** 후 활성화
3. 명령 입력 (예: "설정 앱에서 Wi-Fi 상태 알려줘")

## 구조
- `ArenaController` : WebView 로 LMArena 로그인/전송/스트리밍/중단/새 세션 (DOM 주입). 폴링 110ms, 완성된 work 블록이 보이면 조기 종료
- `WorkParser` : 응답의 ```work JSON 블록 해석
- `AgentViewModel` : 에이전트 루프 (최대 40 step). 로컬 FastPath → AI 루프 순으로 처리
- `FastPath` : "볼륨 50%", "손전등 켜줘", "10분 타이머", "유튜브 열어줘" 같은 단일 명령은 AI 왕복 없이 즉시 실행
- `SystemApi` : UI 를 거치지 않는 시스템/앱 API (volume, brightness, flashlight, ringer, dnd, media, clipboard, alarm, timer, get_info, find_app, open_settings 딥링크, intent, dial/sms/email/search/map, launch_app, open_url)
- `AgentAccessibilityService` : 화면 덤프 + tap/click_text/wait_for/swipe/scroll/type/key. 고정 delay 대신 화면 변화 감지(settle)
- `ui/` : Claude 모바일 스타일 UI (순수 검정 / 포인트 블루 / 블랙그레이 / 화이트). Liquid Glass 제거

## 속도 개선 요약
1. 단순 작업은 FastPath 로 즉시 처리 (AI 왕복 0회)
2. AI 가 필요한 작업도 시스템 API 로 1회 왕복에 완료 (`done` + `commands` 동시 응답)
3. 설정 이동은 `open_settings` 딥링크 + `click_text`/`wait_for` 로 한 턴에 묶어 실행 (명령 최대 10개)
4. API 만 실행했으면 화면 덤프를 생략해 프롬프트/응답 시간 단축
5. 고정 sleep 제거: 화면이 잠잠해질 때까지만 대기, 커서 애니메이션 대기시간 축소

## 필요 권한 (선택)
- 밝기 조절: 설정 → 앱 → Arena Agent → "시스템 설정 수정" 허용 (없으면 에이전트가 권한 화면을 열어줌)
- 무음/방해금지: "방해 금지 접근" 허용
