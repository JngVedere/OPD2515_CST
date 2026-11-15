# 하단 검색 2.9 개발 안내

Android 앱 `dev.padhelper.fastcts`, 버전 **2.9.0 / 코드 13**입니다. OPD2515 / ColorOS 17 CN / Android 17에서 확인했습니다. 사용자 설치·권한·앱 설정 열기는 [사용 가이드](../../HOW%20TO%20USE.MD)를 참고하세요.

## 두 실행 방식

기본값인 **하단 영역 인식**은 접근성 오버레이 200dp × 38dp에서 약 400ms 길게 누르기를 인식합니다. 이동·취소·중복 실행을 막고 설정 안에서만 범위를 표시합니다.

**ColorOS 기본 하단 바 · 로그 감지**는 투명 터치 영역을 제거하고 OEM의 기본 바 제스처·애니메이션을 사용합니다. 비활성화된 Breeno 서비스의 호출 시도를 셸 에이전트가 감지합니다. system 버퍼·로그 생산 UID 1000·ActivityManager 경고로 입력을 제한하고, SystemUI 호출 UID·현재 사용자·정확한 실패 형식·새 시각·중복 조건을 검사합니다. 확인한 전원 버튼 호출 UID 1000은 제외합니다. UID는 동적으로 조회하며 다른 ROM은 별도 검증이 필요합니다.

기존 Breeno 창 감지·뒤로 닫기 방식과는 다릅니다. 접근성 이벤트 유형은 0이며 화면 읽기·캡처·제스처 주입을 요청하지 않습니다. `setup_native_bar`는 계속 무시합니다. 새 선택은 `trigger_mode`의 `touch` 또는 `coloros_log`입니다.

기본 연결은 Google 준비 후 공식 MiCTS 2.6 호출입니다. 다른 앱 선택, 최근 앱 카드 표시, 범위 미리보기, 빠른 설정 타일과 아이콘 없는 설정 진입도 지원합니다.

## Binder 에이전트

앱의 **기본 바 ADB 활성화 안내**가 현재 APK 경로·UID·버전에 맞는 `app_process` 명령을 생성합니다. 사용자가 ADB 셸로 실행해야 하며 루트·Shizuku·별도 Windows GUI는 필요하지 않습니다. 재부팅·APK 업데이트 후에는 다시 활성화해야 합니다.

앱 등록 Provider는 `android.permission.DUMP`와 실제 호출 UID 2000을 모두 확인합니다. 셸은 Android `content` 명령과 같은 외부 Provider API로 등록하고 Provider 소유 패키지·UID를 확인합니다. 이후 양방향 Binder는 호출 UID, 인터페이스, 고정 크기 데이터, 프로토콜·앱 버전, 연결 세대와 이벤트 시각을 확인합니다. 등록 완료 뒤 READY를 받기 전에는 RUN을 보내지 않습니다.

원본 로그는 파일이나 앱에 전달하지 않습니다. 전달되는 것은 길게 누르기 신호·세대·시각뿐입니다. 앱에 READ_LOGS나 네트워크 권한을 추가하지 않습니다. 셸 로그 읽기는 블로킹 방식이며 영역 모드·사용 꺼짐·화면 꺼짐에서는 로그 자식을 중지하고 연결만 유지합니다. Binder 단절·예상치 못한 로그 자식 종료는 제한된 재연결 경로로 복구하고, 명시적 종료는 재연결하지 않습니다. 정상 동작이 확인되지 않는 반복 실패는 최대 30초 뒤 중단합니다.

## 파일 역할

| 파일 | 역할 |
| --- | --- |
| `MainActivity.java` | 내부 실행 연결, Google 준비와 공식 MiCTS 또는 선택 앱 실행 |
| `BarService.java` | 영역 터치·모드 전환·화면 상태·Binder 이벤트 수신 후 실행 |
| `SetupActivity.java` | 방식·범위·최근 앱·연결 앱·권한·활성화 안내 |
| `SetupCommandActivity.java` | DUMP로 보호한 개발용 설정 진입점 |
| `BarGesture.java` | OEM 값 0/1 적용, 최초 원래 값 보관과 복원 |
| `NativeBarProtocol.java` | 프로토콜 상수, 이벤트 출처와 시각 판별 |
| `NativeBarWire.java` | 고정 크기 one-way Binder 메시지 |
| `NativeBarProvider.java` | 셸 전용 등록 창구 |
| `NativeBarBridge.java` | 접근성 소유 세션·UID·세대·수명 관리 |
| `NativeBarAgent.java` | 셸 등록, 제한된 로그 읽기·분류와 복구 |
| `BootRecovery.java`, `BootReceiver.java` | 이전 승인 조건 아래 자기 접근성만 부팅 직후 복구 |
| `BarTile.java` | 빠른 설정 사용 전환과 설정 열기 |
| `App-Version.json`, `Build.ps1` | 버전 선언, 컴파일·검증·서명 |

## 권한과 복원

요청 권한은 `RECEIVE_BOOT_COMPLETED`와 `WRITE_SECURE_SETTINGS`입니다. 보안 설정 쓰기는 영역 모드에서 선택 사항이며 기본 바 모드에서 필요합니다. 앱이 스스로 부여할 수 없습니다.

영역은 실제 터치 창이 붙은 뒤 OEM 호출 값을 0으로, 기본 바는 로그 수신 확인 뒤 1로 설정합니다. 첫 원래 값을 모드 전환 중에도 보존하고 끄기·접근성 해제 시 복원을 시도합니다. 앱 제거 전에 먼저 끄기를 누릅니다. 강제 종료·삭제 뒤 복원은 보장하지 않습니다. ColorOS 전체 보호를 약화하지 않습니다.

부팅 복구는 이전 자기 승인·사용·복구 옵션·권한·같은 부팅·부팅 전 승인 목록을 확인하고 부팅 직후 약 5초만 동작합니다. 다른 접근성 목록을 보존합니다. 셸 에이전트의 재부팅 후 재활성화는 대신하지 않습니다.

## 빌드·검사·배포

JDK 21과 Android SDK Platform/Build-Tools `android-37.0`이 필요합니다. SDK 기본 루트는 `%LOCALAPPDATA%/Android/Sdk`이며 `-Sdk`로 변경합니다. Gradle과 별도 앱 라이브러리는 사용하지 않습니다.

```powershell
powershell.exe -NoProfile -ExecutionPolicy Bypass -File .\micts\fast-launch\Build.ps1
powershell.exe -NoProfile -ExecutionPolicy Bypass -File .\Test-AndroidRecovery.ps1
powershell.exe -NoProfile -ExecutionPolicy Bypass -File .\Test-NativeBar.ps1
powershell.exe -NoProfile -ExecutionPolicy Bypass -File .\Test-Release.ps1
powershell.exe -NoProfile -ExecutionPolicy Bypass -File .\Build-Release.ps1
```

검증한 후보만 `MiCTS-Quick.apk`로 교체하고 루트 `App-Release.json`을 갱신합니다. 최종 배포는 `dist/Bottom-Search-2.9.0.apk`와 `.apk.sha256`입니다. 기존 설치 업데이트에는 같은 서명이 필요합니다. `build/keys/`와 개인 기기 기록은 Git·배포에서 제외합니다.

## 검증과 제한

Android 복구·보안 147개, 이벤트·시각 정책 67개, 배포 49개로 **263개 검사**를 통과했습니다. 실제 Android 정규식·Binder는 실기기에서 검증했습니다. 모두 닫기 뒤 영역 검색 2회·기본 바 검색 3회, UI 전환, 서비스 재연결, 로그 자식 종료 후 복구, 잘못된 등록 거부, 무권한 앱 차단, 종료·재활성화, 화면 꺼짐/켜짐과 전원 버튼 제외를 확인했습니다. 재부팅 뒤 접근성 복구·영역 방식·수동 ADB 재활성화 뒤 기본 바 검색도 확인했습니다.

Google의 **화면에 관해 물어보기** 내부 PNG 압축 지연과 0.5초 목표는 별도 미해결 문제입니다. 이번 기능은 검색용 이미지 축소나 Google 내부 처리 변경을 하지 않습니다. 장시간 배터리, 다른 기기·펌웨어와 실제 보안 설정 쓰기 권한 회수 후 동작은 미검증입니다.

트리거 아이디어는 [ColorGoogle 고정 버전](https://github.com/joeyandu/ColorGoogle/tree/38ead061d06d5f24b5ff20b92f58d5e4632a0298)을 참고했습니다. 셸 등록 API는 [Android content 명령](https://android.googlesource.com/platform/frameworks/base/+/refs/heads/main/cmds/content/src/com/android/commands/content/Content.java)을 참고했습니다. 이전 Windows 도구·개인 기록·서명 키·APK는 이 소스 저장소에 포함하지 않습니다.
