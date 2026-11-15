# Circle-to-Search Trigger 0.3.0 개발 안내

Android 앱 `dev.padhelper.fastcts`, 버전 **0.3.0 / 코드 18**입니다. 대상은 OPD2515 / ColorOS 17 CN / Android 17입니다. 사용자 설치·권한·앱 설정 열기는 [사용 가이드](../../HOW%20TO%20USE.MD)를 참고하세요.

`GeminiSession.launch(Context)`는 현재 Google 음성 서비스의 일반 어시스턴트 세션을 요청합니다. Google의 모바일 어시스턴트를 Gemini로 설정해야 작은 Gemini 창이 열립니다. 전체 앱 실행이나 ASSIST intent로 대체하지 않습니다.

0.3.0는 Google 선택 값이 정상이어도 내부 음성 서비스 연결이 끊겨 Gemini 요청만 수락되는 경우를 복구합니다. `VoiceRepair`는 사용 시작·전원 옵션 켜기·수동 재연결 버튼에서만, 보안 설정 쓰기 권한과 기존 Google 선택을 확인한 뒤 같은 서비스를 잠시 해제하고 복원합니다. 원래 값은 먼저 저장하고, 도중 종료·권한 회수·다른 어시스턴트 선택을 처리하며 중복 요청을 병렬 실행하지 않습니다. 전원 이벤트 자체에서는 재연결하지 않습니다. 설정 화면의 상태 반영도 사용자 입력과 구분합니다.

## 구조

| 경로 | 동작 |
| --- | --- |
| 하단 영역 | 접근성 오버레이 200dp × 38dp에서 약 400ms 길게 누르기 → `MainActivity` → Google 준비 후 MiCTS 또는 선택 앱 |
| ColorOS 기본 바 | OEM 제스처 값 1 + 비활성화된 Breeno 서비스 시작 실패 로그(SystemUI 호출 UID) → `MainActivity` |
| 전원 버튼 | `KEYLOG_SinglePowerKeyMonitor: PowerKey:onLongPress` → `GeminiSession.launch(Context)` |

기본 바와 전원 버튼은 `BarService`(접근성 서비스)가 소유하는 **앱 UID의 logd 연결 하나**를 공유합니다. 셸 에이전트·`app_process`·`logcat` 자식 프로세스·포그라운드 서비스·일반 오버레이는 없습니다. 0.2.6의 ADB 셸 Binder 에이전트(`NativeBar*`)와 DUMP 보호 등록 Provider는 제거했습니다.

### 로그 연결 (`LogSession`, `LogTrigger`)

- `logdr` 예약 소켓에 SOCK_SEQPACKET으로 접속해 `stream lids=3 tail=1`(system 버퍼만)을 요청하고 블로킹으로 읽습니다. 폴링·heartbeat·주기 타이머는 없습니다.
- **연결됨** 판정은 logd 헤더의 작성자 UID가 1000인 항목을 받은 뒤입니다. 앱 자신의 UID 항목은 건강 신호로 쓰지 않습니다. Android가 승인을 거부하면 자기 UID 로그만 전달되므로, 35초 안에 시스템 UID 항목이 없으면 연결을 닫고 그 부팅의 자동 요청을 멈춥니다.
- 분류는 작성자 UID 1000·system 버퍼·정확한 태그와 메시지 형식을 요구합니다. 기본 바는 ActivityManager 경고, 정확한 Breeno 서비스 실패 형식, SystemUI 호출 UID와 현재 사용자를 검사합니다. 전원 버튼이 함께 남기는 호출 UID 1000의 같은 실패 로그는 기본 바로 인식하지 않습니다.
- 세션 시작 시각 이전(tail 재생), 미래, 1500ms보다 오래된 항목은 버리고, 종류별 1500ms 안의 중복(예: 3ms 간격의 이중 전원 마커)과 순서가 뒤바뀐 항목을 막습니다.
- 원본 로그는 리더 스레드 밖으로 나가지 않습니다. 저장·재기록·전송하지 않고, 종류(BAR/POWER)만 메인 스레드로 넘깁니다.
- 화면 켜짐/꺼짐이나 설정 화면 방문으로 연결을 다시 열지 않습니다. 기본 바도 전원도 쓰지 않거나 READ_LOGS가 없으면 연결을 닫습니다. 화면이 꺼진 동안에도 system 버퍼 항목을 받아 헤더만 확인합니다.

### 승인 브리지 (`LogAccessActivity`)

Android의 `LogcatManagerService`는 새 로그 연결을 요청한 UID가 TOP일 때만 확인 창을 띄웁니다. 그래서 새 연결은 투명·비노출·최근 앱 제외 브리지에서만 엽니다. 브리지는 실제 창 포커스를 얻은 뒤 420ms 뒤 한 번만 요청하고, 잠금 상태면 요청하지 않습니다. 6.5초 안에 포커스를 못 얻으면(다른 시스템 창) 종료하고, 최대 12초 뒤에는 무조건 종료합니다. 확인 창을 누르거나 건너뛰지 않습니다.

자동 표시는 `ACTION_USER_PRESENT`와 접근성 연결 시점에만 검사합니다. 조건은 사용 중인 로그 기능, READ_LOGS, 접근성 승인, 화면 켜짐·잠금 해제, 브리지 미표시, 이번 부팅에서 거부 기록 없음, 부팅당 3회 미만입니다(`Settings.Global.BOOT_COUNT` 기준). 설정의 **기기 로그 접근 승인 요청**, 기본 바 선택, 전원 스위치 켜기, 사용 시작은 사용자의 명시적 조작이므로 거부 기록과 무관하게 한 번 요청합니다.

## 파일 역할

| 파일 | 역할 |
| --- | --- |
| `MainActivity.java` | 내부 실행 연결, Google 준비와 공식 MiCTS 또는 선택 앱 실행 |
| `BarService.java` | 영역 터치·모드·전원 스위치·화면 상태, 로그 연결 소유, 자동 승인 요청 한도 |
| `LogSession.java` | 단일 logd 소켓, 시스템 UID 건강 판정, 활성화 시간 제한, Breeno 상태 |
| `LogTrigger.java` | 순수 Java 패킷 분류, 신선도·중복·재생 차단, 자동 요청 조건 |
| `LogAccessActivity.java` | 포커스 확인 뒤 새 연결을 요청하는 제한된 투명 브리지 |
| `GeminiSession.java` | 잠금·Google 기본 어시스턴트 확인, 허용된 Google provider 준비, 작은 어시스턴트 세션 요청 |
| `SetupActivity.java` | 방식·전원·기기 로그 상태·범위·최근 앱·연결 앱·권한 안내 |
| `SetupCommandActivity.java` | DUMP로 보호한 개발용 설정 진입점(`setup_mode`, `setup_enable`, `setup_power`, `setup_boot_recovery`, `setup_circle`) |
| `BarGesture.java` | OEM 값 0/1 적용, 최초 원래 값 보관과 복원 |
| `BootRecovery.java`, `BootReceiver.java` | 이전 승인 조건 아래 자기 접근성만 부팅 직후 복구 |
| `BarTile.java` | 빠른 설정 사용 전환과 설정 열기 |
| `App-Version.json`, `Build.ps1` | 버전 선언, 컴파일·검증·서명 |

## 권한과 복원

요청 권한은 `RECEIVE_BOOT_COMPLETED`, `WRITE_SECURE_SETTINGS`, `READ_LOGS` 세 개입니다. `Build.ps1`은 이 목록 밖의 권한과 READ_LOGS 누락, 런처 아이콘을 거부합니다. 보안 설정 쓰기와 READ_LOGS는 앱이 스스로 부여할 수 없으며 최초 한 번 ADB로 부여합니다. READ_LOGS 부여는 유지되지만 각 새 로그 연결에는 Android의 사용자 확인이 필요합니다.

영역은 실제 터치 창이 붙은 뒤 OEM 호출 값을 0으로, 기본 바는 로그 연결이 시스템 UID 항목을 받은 뒤 1로 설정합니다. 첫 원래 값을 모드 전환 중에도 보존하고 끄기·접근성 해제 시 복원을 시도합니다. 부팅 복구는 이전 자기 승인·사용·복구 옵션·권한·같은 부팅·부팅 전 승인 목록을 확인하고 부팅 직후 약 5초만 동작합니다. ColorOS 전체 보호·절전 설정·다른 패키지는 바꾸지 않습니다.

## 빌드·검사·배포

JDK 21과 Android SDK Platform/Build-Tools `android-37.0`이 필요합니다. SDK 기본 루트는 `%LOCALAPPDATA%/Android/Sdk`이며 `-Sdk`로 변경합니다. Gradle은 사용하지 않습니다.

최초 빌드는 Maven Central의 AndroidHiddenApiBypass 6.1을 `build/deps/`에 받습니다. AAR와 내부 JAR의 SHA256을 고정해 검사하고, 이후에는 검증된 캐시를 사용합니다. Apache 2.0 라이선스와 출처는 `res/raw/notice_hidden_api_bypass.txt`에 포함됩니다. 이 PC 빌드 의존성은 앱에 네트워크 권한을 추가하지 않습니다. `Test-AndroidRecovery.ps1` 전에 빌드를 한 번 실행해주세요.

```powershell
powershell.exe -NoProfile -ExecutionPolicy Bypass -File .\micts\fast-launch\Build.ps1
powershell.exe -NoProfile -ExecutionPolicy Bypass -File .\Test-AndroidRecovery.ps1
powershell.exe -NoProfile -ExecutionPolicy Bypass -File .\Test-NativeBar.ps1
powershell.exe -NoProfile -ExecutionPolicy Bypass -File .\Test-Release.ps1
powershell.exe -NoProfile -ExecutionPolicy Bypass -File .\Build-Release.ps1
```

- `Test-NativeBar.ps1`: 실제 `LogTrigger` 코드로 패킷 헤더 파싱, 작성자 UID·버퍼·태그·형식 분류, 자기 UID heartbeat 배제, 신선도·중복·재생 차단, 자동 요청 한도를 JDK만으로 검사합니다.
- `Test-AndroidRecovery.ps1`: 전체 소스 컴파일, 부팅 복구·제스처 상태 검사와 함께 Provider 부재, 브리지 비노출, 정확한 권한 집합, 포그라운드 서비스·오버레이·추가 프로세스 부재, 제거된 에이전트 소스, 명령 실행 부재, 브리지 전용 연결 열기, 로그 비저장, 전원→`GeminiSession.launch` 연결, Gemini의 launcher/ASSIST 대체 금지를 검사합니다.

검증한 후보만 `MiCTS-Quick.apk`로 교체하고 루트 `App-Release.json`을 갱신합니다. 최종 배포는 `dist/Circle-to-Search-Trigger-0.3.0.apk`와 `.apk.sha256`입니다. 기존 설치 업데이트에는 같은 서명이 필요합니다. `build/keys/`와 개인 기기 기록은 Git·배포에서 제외합니다.

## 검증과 제한

0.2.7은 OPD2515에서 작은 Gemini 팝업, 기본 하단 바 CTS, 기존 영역 방식과 전원 기능의 병행, 전원 기능 끄기, 로그 연결 종료를 확인했습니다. USB를 분리한 상태로 재부팅한 뒤 첫 잠금 해제에서 승인창이 자동 표시되고, 사용자 승인 한 번으로 두 기능이 복구되는 것도 확인했습니다. 앱을 직접 열거나 ADB 권한을 재부여하지 않았습니다.

자동 검사는 `Test-NativeBar.ps1` 230개, `Test-AndroidRecovery.ps1` 77 + 56 + 31개, `Test-Release.ps1` 47개가 통과했습니다. 대기 후 재호출은 약 55초 구간에서 확인했으며 장시간 배터리와 다른 기기·펌웨어에서의 동작은 보장하지 않습니다. Android의 새 로그 연결 승인을 생략하거나 대신 누르지 않습니다.

접근성 서비스가 실행 중일 때 `uiautomator dump`는 일반 접근성 서비스를 일시 중지시켜 로그 연결을 끊습니다. 기기 검사에는 `screencap`, `dumpsys`, `logcat`을 사용해야 합니다. UIAutomator 사용 뒤에는 로그 세션 재승인이 필요할 수 있습니다.

참고: 기본 바 트리거 아이디어는 [ColorGoogle](https://github.com/joeyandu/ColorGoogle/tree/38ead061d06d5f24b5ff20b92f58d5e4632a0298), 앱 자체 logd 연결과 포커스 브리지 방식은 [MindTrigger Assist](https://github.com/evokermc098-coder/MindTriggerAssist/tree/ba0d945a9be65b9114e697fc794098c1f6b46f21)(GPL-3.0)를 참고해 별도로 구현했습니다. 두 프로젝트의 소스 파일은 포함하지 않습니다. logd 패킷 형식은 AOSP `logger_entry` v4를 따릅니다.
