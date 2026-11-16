# Circle-to-Search Trigger

> 다른 기기에서도 동작할 수 있으나, 동작을 보장하지 않습니다. 동작 테스트는 **OPPO Pad Mini OPD2515에서만** 진행했습니다.

**오포 패드 미니에서 Circle to Search와 Gemini Assistant를 사용해보세요.**

OPPO Pad Mini에서 Google 서클 투 서치를 편하게 열 수 있도록 도와주는 Android 앱입니다. 서클 투 서치 대신 자주 사용하는 다른 앱을 연결할 수도 있습니다. 루팅 없이 사용할 수 있으며, 앱서랍에는 아이콘이 표시되지 않습니다.
기존에 있던 다른 앱 대비 반응성과 User Experience에서의 자연스러움을 최대한 고려한 앱입니다.

[**APK 다운로드**](https://github.com/JngVedere/OPD2515_CST/releases/latest)

## 주요 기능

- 하단 중앙을 길게 눌러 서클 투 서치 실행
- **하단 영역 인식**과 **ColorOS 기본 하단 바** 중 실행 방식 선택
- 하단 인식 범위 미리 보기
- 최근 앱 목록에서 설정 화면 숨기기·보이기
- 길게 눌렀을 때 실행할 앱 직접 선택
- 빠른 설정 타일로 사용 켜기·끄기 및 설정 열기
- 선택 사항으로 재부팅 후 접근성 자동 복구
- 전원 버튼 길게 눌러 Gemini Assistant 호출

## 사용 환경/준비물

대상 기기는 **OPPO Pad Mini OPD2515 / ColorOS 17 중국 내수용**입니다. 다른 기기나 펌웨어에서는 동작이 다를 수 있습니다.

서클 투 서치를 사용하려면 다음 앱을 별도로 준비해주세요.

- **[Google](https://play.google.com/store/apps/details?id=com.google.android.googlequicksearchbox)** — Google LLC에서 배포하는 앱
- **[Google Speech Recognition & Synthesis](https://play.google.com/store/search?q=google+speech+services&c=apps)** - 호환성을 위해 권장
- **[MiCTS 2.6](https://github.com/parallelcc/MiCTS/releases/tag/v2.6)** — 서클 투 서치 실행에 필요

Google 서비스가 없는 기기는 먼저 기기의 Google 서비스와 Play 스토어를 준비해주세요. 

## 설치 및 시작하기

### 실행 방식 선택

| 방식 | 사용 방법 | 필요한 설정 |
| --- | --- | --- |
| **하단 영역 인식** · 기본값 | 하단 중앙의 투명한 인식 영역을 길게 누릅니다. | 접근성 허용 |
| **ColorOS 기본 하단 바 · 로그 감지** | ColorOS 기본 바의 제스처와 애니메이션을 사용합니다. | 접근성, 보안 설정 쓰기 권한, Breeno 비활성화, 컴퓨터에서 ADB 활성화 |
| **Gemini Assistant** | 전원 버튼을 꾹 눌러 호출합니다. | 접근성, 보안 설정 쓰기 권한, Breeno 비활성화, 컴퓨터에서 ADB 활성화 |

**하단 영역 인식**과 **ColorOS 기본 하단 바** 두 방식 중 하나를 선택해 사용합니다.
**Gemini Assistant**는 독립적으로 켜고 끌 수 있습니다.

기본 **하단 영역 인식** 방식은 태블릿만으로 설정할 수 있습니다.
**ColorOS 기본 하단 바 · 로그 감지**와 **Gemini Assistant**는 컴퓨터와 ADB가 필요합니다.

### 필수 설정

**재부팅 시 자동 시작 설정 (필수)**
1. 앱 내 **`추가 설정 → 재부팅 후 자동 복구 → 켬`**
2. 태블릿 **`설정 → 앱 → 자동 실행 → 'Circle-to-Search Trigger' → 켬`**
3. 태블릿 **`설정 → 앱 → 관련 실행 → 'Circle-to-Search Trigger' → 켬`** (OPTIONAL)
  

**배터리 제한 해제 (필수)**
1. 앱 내 **앱 정보와 절전 설정**에서 **배터리 사용량 → 백그라운드 활동 허용**을 켭니다.
   

**최근 앱 정리 제외 설정 (필수)**
1. 앱 내 **최근 앱 정리 제외 설정** 누른 후 **`최근 앱 목록(홈 버튼) → 'Circle-to-Search' 앱 이름 누르기 → 잠금`** 후 앱 내 **`'최근 앱에 표시' 끄기`**

   

### 하단 영역 인식

1. [다운로드 페이지](https://github.com/JngVedere/OPD2515_CST/releases/latest)에서 **`Bottom-Search`로 시작하는 `.apk` 파일**을 받습니다.
2. 태블릿에서 APK를 열어 설치합니다. 설치가 차단되면 해당 파일을 연 앱의 **이 출처 허용**을 켭니다.
3. 태블릿 **`설정 → 앱 → 앱 관리 → 'Circle-to-Search Trigger' → 앱 추가 설정`** 경로로 Circle-to-Search Trigger 설정을 엽니다.
4. **연결 앱 → 기본 어시스턴트 설정**에서 Google을 선택 및 **`앱 선택 → 서클 투 서치`**, 이후 **화면 및 앱 데이터 사용**을 **허용**합니다.
5. 태블릿 **`설정 → 앱 → 앱 관리 → 'Circle-to-Search Trigger' → 권한 → 우측 상단의 ... → 권한 제한 해제`** 경로로 Circle-to-Search Trigger 설정을 엽니다.
6. 앱 내 **접근성 설정**에서 **다운로드한 앱 → Circle-to-Search Trigger**을 켭니다.
7.  앱 내 **`실행 방식 → '하단 영역 인식' → 상단의 '사용 시작'`**
8.  앱의 연결 상태를 확인한 뒤 앱 내 **`설정 닫기`** 를 눌러 닫습니다.
9.  다른 앱을 연 상태에서 **화면 하단 중앙을 잠시 길게 누르면** 서클 투 서치가 실행됩니다.


### ColorOS 기본 하단 바 사용하기
1. **하단 영역  인식** 6단계까지 진행해주세요
2. USB 디버깅을 허용한 태블릿을 ADB가 준비된 컴퓨터에 연결합니다. (태블릿 : **`개발자 옵션 → USB 디버깅 활성화`**, PC : [ADB](https://developer.android.com/tools/releases/platform-tools?hl=ko))
3. **개발자 옵션 → Disable System Optimization → 켬**으로 세팅합니다. (옵션이 보이지 않는다면 시스템 언어를 English로 바꾸고 재시도 해주세요. 사용 시작 후에는 언어를 기존 언어로 바꿔도 됩니다.)
4. 앱 내 **추가 설정 → 최초 ADB 설정 안내**를 열고 Breeno가 꺼져있는지 확인합니다. (Breeno가 켜져 있다면 아래 명령어를 CMD나 POWERSHELL에 입력하여 비활성화합니다. 앱이 자동으로 끄지는 않습니다.)
```POWERSHELL
adb shell pm disable-user --user 0 com.heytap.speechassist
```
5. **아래 명령어**를 입력하여 권한을 부여합니다. 컴퓨터 CMD 혹은 POWERSHELL에서 실행합니다. (ADB 필요)
```POWERSHELL
adb shell pm grant --user 0 dev.padhelper.fastcts android.permission.WRITE_SECURE_SETTINGS
adb shell pm grant --user 0 dev.padhelper.fastcts android.permission.READ_LOGS
```
6. 앱 내 **`실행 방식 → ColorOS 기본 하단 바 · 로그 감지**를 선택합니다.
7. 앱 내 **기기 로그 접근 승인 요청 → 일회성 액세스 허용**을 선택합니다.
8. 앱 내 상단의 **`사용 시작`** 클릭
9. 앱의 연결 상태를 확인한 뒤 앱 내 **설정 닫기**를 눌러 닫습니다.
10. 다른 앱을 연 상태에서 **`화면 하단 중앙을 잠시 길게 누르면`** 서클 투 서치가 실행됩니다.


### Gemini Assistant 사용하기
1. **ColorOS 기본 하단 바 사용하기** 7단계까지 진행해주세요
2. 앱 내 **`전원 버튼 → 전원 버튼 길게 눌러 작은 Gemini 열기`** 활성화
3. 앱 내 상단의 **`사용 시작`** 클릭
4. 앱의 연결 상태를 확인한 뒤 앱 내 **설정 닫기**를 눌러 닫습니다.


**태블릿을 재부팅한 후에는 로그 수집 권한을 다시 허용해야 합니다.** 재부팅 후 최초 잠금 해제 후 수 초 내에 팝업이 뜹니다.

Breeno를 비활성화하면 전원 버튼 등으로 Breeno를 여는 기능에도 영향을 줄 수 있습니다.


## 부가 기능

| 설정 | 설명 |
| --- | --- |
| **인식 영역 표시** | 설정 화면에서 하단 인식 범위를 파란색으로 보여줍니다. 설정을 벗어나면 다시 투명해집니다. 영역 크기를 바꾸는 기능은 아닙니다. |
| **최근 앱에 표시** | Circle-to-Search Trigger 설정 화면을 최근 앱 목록에 보이거나 숨깁니다. 앱서랍 아이콘에는 영향을 주지 않습니다. |
| **앱 선택** | 길게 눌렀을 때 실행할 앱을 고릅니다. 서클 투 서치로 돌아가려면 기본값을 선택하세요. |
| **사용 시작 / 끄기** | 선택한 실행 방식을 켜거나 끕니다. |
|**Google 음성 연결 다시 시작** | Gemini Assistant가 호출되지 않을 경우 이 버튼을 눌러주세요.|

## 설정 다시 열기

앱서랍 아이콘을 숨긴 앱이므로 다음 경로 중 하나로 설정을 열 수 있습니다. 메뉴 이름은 기기 설정에 따라 조금 다를 수 있습니다.

- **설정 → 앱 → 앱 관리 → Circle-to-Search Trigger → 앱 추가 설정**
- **설정 → 접근성 → 다운로드한 앱 → Circle-to-Search Trigger → 설정**
- **빠른 설정의 Circle-to-Search Trigger 타일 길게 누르기**

앱 설정의 **빠른 설정에 버튼 추가**를 눌러 타일을 추가해두면 편리합니다. 최근 앱에서 숨겨도 위 경로로 다시 열 수 있습니다.

## 알려진 한계

1. **실행 방식 → ColorOS 기본 하단 바 · 로그 감지** 또는 **Gemini Assistant**를 사용하는 경우, 재부팅 시 로그 수집 권한을 다시 허용해야 합니다. 일반적으로 최초 잠금 해제 후 팝업이 등장하지만, 기기 상태에 따라 해당 팝업이 나오지 않을 수도 있습니다.
2. 서클 투 서치(Circle to Search)의 **화면에 관해 물어보기**를 누르면 일부 화면에서 약 **2.8초**의 전환 지연이 발생할 수 있습니다. 지연 시간은 Google 앱과 화면 내용에 따라 달라질 수 있습니다.
3. 구글 음성 서비스가 비정상적으로 종료되어 전원 버튼에 할당된 Gemini Assistant가 반응하지 않는 버그가 있습니다.
4. CTS가 예상대비 평균 150ms, Gemini Assistant가 예상대비 평균 600ms 늦게 열리는 버그가 있습니다.
5. '설정 -> 앱에 작업 표시줄  표시'가 켜져있는 상태에서 ColorOS 하단바를 통한 Circle to Search가 안 되는 버그
6. '설정 -> 제스처 안내 막대 숨기기'가 켜져있는 상태에서 앱 내 안내가 잘못 표시되는 버그 

## 자주 묻는 질문

**Q1.최근 앱에서 ‘모두 닫기’를 누른 뒤 반응하지 않아요.**

A1.Circle-to-Search Trigger의 접근성이 켜져 있는지 확인하고, 앱 설정의 **자동 실행 설정**과 **앱 정보와 절전 설정**에서 백그라운드 실행을 허용해주세요. 필요하면 **최근 앱 정리 제외 설정** 안내에 따라 최근 앱 카드를 잠근 뒤 다시 숨길 수 있습니다.

**Q2.재부팅한 뒤 작동하지 않아요.**

A2.먼저 태블릿 잠금을 한 번 풀어주세요. 하단 영역 방식은 접근성 상태와 자동 복구 옵션을 확인하고, 기본 바 방식은 앱 안내의 ADB 활성화 명령을 다시 실행해주세요.

**Q3.CTS를 호출할 때 ‘Trigger failed’가 떠요.**

A3.Google이 기본 디지털 어시스턴트로 선택되어 있는지, 화면 텍스트·스크린샷 사용이 허용되어 있는지 확인해주세요. 필요한 음성 인식 서비스도 설치한 뒤 MiCTS를 직접 열어보세요.

**Q4.‘화면에 관해 물어보기’로 넘어갈 때 느려요.**

A4.Google 앱과 화면 내용에 따라 전환이 지연될 수 있습니다. Circle-to-Search Trigger에서 실행 방식을 바꿔도 이 지연이 해결되지는 않습니다.

**Q5.앱 삭제 후 기본 상태로 복원하려면 어떻게 하나요?**

A5. adb로 일부 옵션을 복원해야 합니다. 
   USB 디버깅을 허용한 태블릿을 ADB가 준비된 컴퓨터에 연결합니다. (태블릿 : **개발자 옵션 → USB 디버깅 활성화**, PC : [ADB](https://developer.android.com/tools/releases/platform-tools?hl=ko))
   이후 아래 명령어를 입력해주면 기본 상태로 복원됩니다
   ```POWERSHELL
   adb shell pm enable --user 0 com.heytap.speechassist
   adb shell settings --user 0 put secure oplus_gesture_handle_cui_enable 1
   ```

## 권한 안내

**접근성**은 하단 인식 영역과 실행 기능을 유지하는 데 사용합니다. Circle-to-Search Trigger 자체는 화면 내용을 읽거나 캡처하지 않습니다. 서클 투 서치의 화면 인식과 검색은 Google 앱이 처리합니다.

**보안 설정 쓰기**는 앱이 시스템 설정을 바꿀 수 있는 강한 권한입니다. 재부팅 후 접근성 자동 복구와 하단 바 제스처 전환에 사용합니다. ColorOS 기본 바 방식에서는 이 권한과 별도 ADB 활성화가 필요합니다.

**기기 로그 읽기**는 ColorOS 기본 바와 전원 버튼 길게 누르기를 감지하는 데 사용합니다. 로그를 저장하거나 외부로 보내지 않습니다. 권한은 재부팅 후에도 유지되지만, 재부팅마다 첫 잠금 해제 뒤 Android의 확인 창에서 직접 허용해야 합니다.

## 관련 프로젝트

- [MiCTS](https://github.com/parallelcc/MiCTS) — 서클 투 서치 실행
- [ColorGoogle](https://github.com/joeyandu/ColorGoogle) — ColorOS 기본 바 실행 방식의 참고 프로젝트

## AI 도구 활용

기능과 사용 방향을 직접 정하고, 개발과 검토 과정에 다음 도구를 활용했습니다.

| 도구 | 사용 모델|활용 방식 |
| --- | --- | --- |
| **CHATGPT** | GPT Sol 6.1 xhigh,high|요구사항 정리와 작업 조율, 앱 구현·수정, ADB를 통한 실기기 테스트와 문제 확인에 활용했습니다. |
| **CLAUDE** | Claude Opus 5.5 high|일부 개발 작업을 위임하고, 코드 리뷰와 버그 분석을 요청하는 데 활용했습니다. |
| **GEMINI** |Gemini 4 Hydrogen Chat|문서 검토에 활용했습니다. |
