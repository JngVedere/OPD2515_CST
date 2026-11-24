# Circle-to-Search Trigger (OpdCTS)

**한국어** · [English](./README.en.md)

**OPPO Pad Mini에서 Circle to Search와 Gemini Assistant를 자연스럽게 사용해 보세요.**

OpdCTS는 ColorOS에서 Google Circle to Search와 Gemini Assistant를 편리하게 호출할 수 있도록 도와주는 Android 앱입니다. 반응성과 자연스러운 사용자 경험을 중심으로 개발되었으며, 연결 앱을 자유롭게 변경할 수 있습니다.

- 🪶 1MB 미만의 초경량 앱
- 루팅 및 부트로더 잠금 해제 불필요
- MiCTS 없이 Circle to Search 직접 실행
- 상주 알림 및 백그라운드 폴링 없이 동작
- 설정 화면을 닫은 상태에서 약 50MB의 메모리 사용량

**[최신 버전 다운로드](https://github.com/JngVedere/OPD2515_CST/releases/latest)** · [변경 사항](./RELEASES.md) · [문제 제보](https://github.com/JngVedere/OPD2515_CST/issues)

> OPPO Pad Mini(OPD2515)에서 동작을 검증했습니다. 다른 기기 및 펌웨어에서의 정상 작동은 보장하지 않습니다.

<br>

## 1. 주요 기능

### 실행 방식

| 기능 | 설명 | ADB 필요 |
| --- | --- | :---: |
| **하단 영역 인식** | 화면 하단 중앙의 인식 영역을 길게 눌러 실행 | X |
| **ColorOS 기본 하단 바** | ColorOS 기본 제스처 및 애니메이션을 유지하며 실행 | O |
| **Gemini Assistant** | 전원 버튼을 길게 눌러 Gemini 호출 | O |
| **사용자 지정 앱** | Circle to Search 대신 원하는 앱 실행 | 방식에 따라 다름 |

3버튼 탐색 환경에서는 홈 버튼을 길게 눌러 실행할 수 있습니다.

하단 영역 인식과 ColorOS 기본 하단 바 중 하나를 선택할 수 있으며, Gemini Assistant는 독립적으로 활성화할 수 있습니다.

### 부가 기능

| 기능 | 설명 |
| --- | --- |
| 터치 영역 설정 | 인식 영역의 위치·크기 및 길게 누르기 시간 조절, 가로·세로 화면별 설정 |
| 빠른 설정 타일 | 사용 켜기·끄기, 검색, 어시스턴트, 딥 다크 모드 |
| 앱 아이콘 설정 | OpdCTS 아이콘을 설정, 검색 또는 어시스턴트 실행에 연결 |
| 재부팅 후 자동 복구 | 재부팅 이후 접근성 서비스 복구 지원 |
| 최근 앱 표시 | 설정 화면의 최근 앱 목록 표시 여부 변경 |
| 업데이트 | 앱 내 새 버전 확인 및 설치 |
| 상태 알림 | 기기 로그 연결 및 업데이트 알림, 카메라 섬 표시 지원 |
| 시스템 편의 기능 | 애니메이션 배율, 최대 주사율, 화면 밝기, 회전 제안, 시계 초 표시, 기기 이름 변경 등 |
| 시스템 바로가기 | 주요 시스템 설정 화면에 빠르게 접근 |
| 전원 버튼 두 번 누르기 | 원하는 앱 실행 (실험적 기능) |

<br>

## 2. 지원 환경 및 준비물

### 지원 환경

| 항목 | 환경 |
| --- | --- |
| 검증 기기 | OPPO Pad Mini (OPD2515) |
| 운영체제 | ColorOS 17 CN |
| 루팅 | 필요 없음 |
| ADB | 일부 실행 방식에서 필요 |

### 필요한 앱

- **[Google](https://play.google.com/store/apps/details?id=com.google.android.googlequicksearchbox)** — Circle to Search 및 Gemini 실행
- **[Google Speech Recognition & Synthesis](https://play.google.com/store/search?q=google+speech+services&c=apps)** — 호환성을 위해 설치 권장

Google 서비스가 없는 기기는 Google 서비스와 Play 스토어를 먼저 준비해 주세요.

0.3.6부터 MiCTS 설치는 필요하지 않습니다. 기존에 설치되어 있다면 직접 실행에 실패했을 때 예비 호출 방식으로 사용됩니다.

<br>

## 3. 권한 및 시스템 변경 사항

OpdCTS는 선택한 실행 방식과 활성화한 기능에 따라 다음 권한을 사용합니다.

### 권한 사용 목적

| 권한 | 사용 목적 |
| --- | --- |
| 접근성 서비스 | 하단 인식 영역 유지 및 연결 앱 실행 |
| `READ_LOGS` | ColorOS 기본 하단 바 및 전원 버튼 길게 누르기 감지 |
| `WRITE_SECURE_SETTINGS` | 접근성 자동 복구, 제스처 전환 및 시스템 편의 기능 |
| `INTERNET` | 새 버전 확인 및 APK 다운로드 |
| `REQUEST_INSTALL_PACKAGES` | 앱 내 업데이트 설치 요청 |
| `POST_NOTIFICATIONS` | 기기 로그 연결 및 업데이트 상태 알림 |
| `POST_PROMOTED_NOTIFICATIONS` | 카메라 섬 등 라이브 알림 지원 |
| 배터리 최적화 제외 | 백그라운드 동작 중 서비스 중단 최소화 |
| 백그라운드 실행 허용 | 화면 전환 및 장시간 사용 중 서비스 유지 |

직접 설치한 APK의 접근성 서비스를 활성화하려면 Android의 제한된 설정 허용이 필요할 수 있습니다.

### ADB 원클릭 설정 시 변경되는 항목

ADB 설정 스크립트는 다음 시스템 설정을 변경하거나 활성화를 요청합니다.

- Breeno(Xiaobu Assistant) 비활성화
- Google을 기본 디지털 어시스턴트로 지정
- 어시스턴트의 화면 및 앱 데이터 사용 허용
- 접근성 서비스 및 필요한 권한 활성화
- 배터리 최적화 및 백그라운드 실행 제한 해제
- 일반 알림 및 라이브 알림 권한 허용
- ColorOS 기본 하단 바 실행 방식 선택
- Circle to Search 및 전원 버튼 Gemini 활성화
- 재부팅 후 자동 복구 활성화

시스템 최적화 해제, 기기 로그 접근 승인, 자동 실행 허용 및 최근 앱 잠금은 사용자가 직접 진행해야 합니다.

> **주의:** 접근성, 기기 로그 읽기 및 보안 설정 쓰기는 민감한 정보나 시스템 설정에 접근할 수 있는 권한입니다. 시스템 최적화 해제는 다른 앱 및 시스템 동작에도 영향을 줄 수 있습니다. 권한의 사용 목적과 스크립트 내용을 확인한 후 설정해 주세요.

<br>

## 4. 개인정보 및 데이터 처리

### 화면 데이터

OpdCTS 자체는 화면 내용을 읽거나 캡처하지 않습니다. Circle to Search의 화면 인식 및 검색은 Google 앱에서 처리합니다.

### 기기 로그

기기 로그는 ColorOS의 제스처와 전원 버튼 동작을 감지하는 데 사용합니다. OpdCTS는 해당 로그를 저장하거나 외부로 전송하지 않습니다.

Android의 보안 정책에 따라 재부팅 이후 기기 로그 접근을 다시 승인해야 합니다.

### 네트워크 및 업데이트

OpdCTS의 인터넷 연결은 앱 업데이트 확인과 다운로드에 사용됩니다. GitHub에서 버전 정보 및 APK를 가져오며, 이 과정에서 별도로 기기 정보를 전송하지 않습니다.

Google 및 MiCTS 등 외부 앱이 처리하는 데이터는 각 서비스의 개인정보 처리 정책을 따릅니다.

## 5. 설치

1. [최신 릴리즈](https://github.com/JngVedere/OPD2515_CST/releases/latest)에서 APK를 다운로드합니다.
2. 태블릿에서 APK를 열어 설치합니다.
3. 앱 서랍에서 **OpdCTS**를 실행합니다.
4. 아래 ADB 원클릭 설정 또는 수동 설정 중 원하는 방식을 선택합니다.

APK 설치가 제한되는 경우 파일을 실행한 앱의 출처 허용 설정을 확인해 주세요.

<br>

## 6. ADB 원클릭 설정

ColorOS 기본 하단 바와 전원 버튼 Gemini를 함께 사용하려는 경우 권장합니다.

### 준비물

- OpdCTS가 설치된 태블릿
- Windows PC
- [Android Platform Tools (ADB)](https://developer.android.com/tools/releases/platform-tools)
- USB 디버깅이 활성화된 기기

ADB가 PATH에 등록되어 있거나 PowerShell에서 실행할 수 있어야 합니다.

### 설정 방법

1. 태블릿의 **개발자 옵션 → USB 디버깅**을 활성화합니다.
2. 태블릿을 PC에 연결하고 **USB 디버깅을 허용** 합니다.
3. **Windows PowerShell** 에서 다음 명령어를 실행합니다.

```powershell
irm https://raw.githubusercontent.com/JngVedere/OPD2515_CST/main/scripts/adb_setup.ps1 | iex
```

4. 개발자 옵션에서 **Disable system optimization**을 활성화한 뒤 PowerShell에서 Enter를 누릅니다.
5. 기기 로그 접근 허용 팝업에서 **일회성 액세스 허용**을 선택합니다. 팝업이 나타나지 않으면 앱의 로그 연결 안내를 눌러 주세요.
6. PowerShell에서 Enter를 누릅니다. 자동 설정이 완료되면 Google 어시스턴트의 최초 호출을 시도합니다.
7. **설정 → 앱 → 자동 실행**에서 Circle-to-Search Trigger를 허용합니다.
8. OpdCTS 설정에서 **최근 앱 정리 제외 설정**을 열고 앱 카드를 잠급니다.

위 절차를 모두 완료하면 설정이 끝납니다.

- 최근 앱을 잠근 뒤 OpdCTS의 **최근 앱에 표시**를 끄면 설정 화면을 최근 앱 목록에서 숨길 수 있습니다.
- 필요한 경우 설정 → 앱 → 관련 실행에서 Circle-to-Search Trigger를 허용할 수 있습니다. (선택 사항)

[ADB 설정 스크립트 원본 보기](./scripts/adb_setup.ps1)

> 위 명령어는 GitHub의 main 브랜치에서 최신 스크립트를 가져와 즉시 실행합니다. 실행 전 원본 스크립트와 시스템 변경 사항을 확인하는 것을 권장합니다.


<br>

## 7. 수동 설정

ADB 원클릭 설정을 사용하지 않거나 원하는 기능만 활성화하려는 경우 수동 설정 문서를 참고해 주세요.

**[수동 설정 가이드](./HOW%20TO%20SETUP%28MANUAL%29.md)**

기본 하단 영역 인식 방식은 태블릿만으로 설정할 수 있습니다. ColorOS 기본 하단 바 및 전원 버튼 Gemini 기능을 사용하려면 ADB가 필요합니다.

### 설정 화면 다시 열기

다음 방법으로 OpdCTS 설정에 접근할 수 있습니다.

- 앱 서랍의 OpdCTS 아이콘 누르기
- OpdCTS 아이콘 길게 누르기 → 설정
- 설정 → 앱 → 앱 관리 → Circle-to-Search Trigger → 앱 추가 설정
- 설정 → 접근성 → 다운로드한 앱 → Circle-to-Search Trigger → 설정
- 빠른 설정의 Circle-to-Search Trigger 타일 길게 누르기

<br>

## 8. 제거 및 시스템 설정 복원

앱 삭제 전에 다음 설정을 해제하는 것을 권장합니다.

1. OpdCTS 설정에서 **끄기**를 선택합니다.
2. 전원 버튼 두 번 누르기 기능을 활성화했다면 해제합니다.
3. OpdCTS 접근성 서비스를 비활성화합니다.
4. 앱을 삭제합니다.

ColorOS 기본 하단 바 및 Gemini 기능을 사용했다면 다음 ADB 명령어로 관련 시스템 설정을 복원할 수 있습니다.

```powershell
adb shell pm enable --user 0 com.heytap.speechassist
adb shell settings --user 0 put secure oplus_gesture_handle_cui_enable 1
adb shell settings delete global power_button_short_press
adb shell settings delete global power_button_double_press
```

원클릭 설정으로 변경한 다음 항목도 필요한 경우 직접 복원해 주세요.

- 기본 디지털 어시스턴트
- 어시스턴트의 화면 및 앱 데이터 사용
- 개발자 옵션의 Disable system optimization

위 명령어는 일부 ColorOS 설정을 복원하는 용도이며, 설치 이전의 모든 설정값을 자동으로 복구하지는 않습니다.

<br>

## 9. 알려진 한계 및 문제 해결

### 알려진 한계

- ColorOS 기본 하단 바 및 전원 버튼 Gemini 기능을 사용하면 재부팅 이후 기기 로그 접근을 다시 승인해야 합니다.
- 재부팅 후 최초 잠금 해제 과정에서 접근성 복구 또는 로그 연결이 정상적으로 이루어지지 않을 수 있습니다.
- 현재 VIS 기반 실행 방식에서는 Circle to Search의 Scroll Translate(Continuous Translate)를 지원하지 않습니다.
- Google 앱 및 ColorOS 업데이트에 따라 일부 기능의 동작이 달라질 수 있습니다.

### 자주 묻는 질문

**Q. 최근 앱에서 모두 닫기를 누르면 작동하지 않습니다.**

접근성 서비스, 자동 실행, 백그라운드 활동 허용 및 최근 앱 잠금 상태를 확인해 주세요.

**Q. 재부팅 이후 작동하지 않습니다.**

기기의 잠금을 한 번 해제하고 기기 로그 접근을 승인해 주세요. 문제가 지속된다면 접근성 상태와 재부팅 후 자동 복구 설정을 확인하고, 필요 시 ADB 권한 설정을 다시 진행해 주세요.

**Q. Circle to Search가 실행되지 않습니다.**

Google이 기본 디지털 어시스턴트로 지정되어 있는지 확인하고, 화면 및 앱 데이터 사용을 허용해 주세요.

**Q. '화면에 관해 물어보기'로 전환할 때 느립니다.**

Google 앱의 처리 과정 및 화면 내용에 따라 전환 시간이 달라질 수 있습니다. OpdCTS의 실행 방식을 변경하더라도 해당 지연이 해결되지 않을 수 있습니다.

**Q. 기기 보호를 위해 앱 설치가 차단됩니다.**

Google Play Protect의 설치 제한일 수 있습니다. APK를 신뢰할 수 있는지 확인한 뒤 ADB를 통한 설치를 시도할 수 있습니다.

```powershell
adb install Circle-to-Search-Trigger-0.4.0.apk
```

파일명은 실제 다운로드한 APK의 이름으로 변경해 주세요.

**Q. 접근성 서비스가 회색으로 표시되거나 액세스가 거부됩니다.**

1. 설정 → 앱 → 앱 관리 → Circle-to-Search Trigger를 엽니다.
2. 우측 상단 메뉴에서 **제한된 설정 허용**을 선택합니다.
3. 화면 잠금 인증을 완료합니다.
4. 접근성 설정에서 Circle-to-Search Trigger를 활성화합니다.

메뉴가 보이지 않으면 접근성 활성화를 한 번 시도한 뒤 앱 정보 화면을 다시 열어 주세요.

그 외 문제는 [GitHub Issues](https://github.com/JngVedere/OPD2515_CST/issues)를 통해 제보해 주세요.

<br>

## 10. 관련 프로젝트

- [MiCTS](https://github.com/parallelcc/MiCTS) — Circle to Search 호출 방식 참고
- [ColorGoogle](https://github.com/joeyandu/ColorGoogle) — ColorOS 기본 하단 바 실행 방식 참고
- [MindTrigger Assist](https://github.com/evokermc098-coder/MindTriggerAssist) — 기기 로그 연결 방식 참고

<br>

## 11. 라이선스 및 변경 사항

0.3.6부터 **[GNU GPL-3.0](./LICENSE)** 라이선스로 배포합니다.

- [LICENSE](./LICENSE) — 라이선스 전문
- [NOTICE](./NOTICE) — 참고 프로젝트 및 포함된 라이브러리 고지
- [RELEASES.md](./RELEASES.md) — 버전별 변경 사항

<br>

## 12. AI 도구 활용

프로젝트의 기능과 개발 방향은 직접 결정하며, 구현 및 검토 과정에서 다음 AI 도구를 활용했습니다.

| 도구 | 사용 모델 | 활용 범위 |
| --- | --- | --- |
| ChatGPT | GPT Sol 6.1 High | 요구사항 정리, 작업 조율, 기능 구현 및 수정, ADB 실기기 테스트 |
| Claude | Claude Opus 5.5 High | 기능 구현, 독립적인 코드 리뷰 및 버그 분석 |
| Gemini | Gemini 4 Neon High | 문서 검토 |
