### 필수 설정

**재부팅 시 자동 시작 설정 (필수)**
1. 앱 내 **`추가 설정 → 재부팅 후 자동 복구 → 켬`**
2. 태블릿 **`설정 → 앱 → 자동 실행 → 'Circle-to-Search Trigger' → 켬`**
3. 태블릿 **`설정 → 앱 → 관련 실행 → 'Circle-to-Search Trigger' → 켬`** (OPTIONAL)
  

**배터리 제한 해제 (필수)**
1. 앱 내 **앱 정보와 절전 설정**에서 **배터리 사용량 → 백그라운드 활동 허용**을 켭니다.
   

**최근 앱 정리 제외 설정 (필수)**
1. 앱 내 **최근 앱 정리 제외 설정** 누른 후 **`최근 앱 목록(홈 버튼) → 'Circle-to-Search..' 앱 이름 누르기 → 잠금`** 후 앱 내 **`'최근 앱에 표시' 끄기`**

   

### 하단 영역 인식

1. [다운로드 페이지](https://github.com/JngVedere/OPD2515_CST/releases/latest)에서 **`Circle-to-Search-Trigger`로 시작하는 `.apk` 파일**을 받습니다.
2. 태블릿에서 APK를 열어 설치합니다. 설치가 차단되면 해당 파일을 연 앱의 **이 출처 허용**을 켭니다.
3. 앱 서랍의 **OpdCTS** 아이콘을 눌러 Circle-to-Search Trigger 설정을 엽니다. (또는 태블릿 **`설정 → 앱 → 앱 관리 → 'Circle-to-Search Trigger' → 앱 추가 설정`**)
4. **실행 방식 → 연결 앱 → 기본 어시스턴트 설정**에서 Google을 선택 및 **`앱 선택 → 서클 투 서치`**, 이후 **화면 및 앱 데이터 사용**을 **허용**합니다.
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
7. 앱 내 맨 위 **기기 로그가 연결되지 않았습니다** 안내를 눌러 **일회성 액세스 허용**을 선택합니다.
8. 앱 내 상단의 **`사용 시작`** 클릭
9. 앱의 연결 상태를 확인한 뒤 앱 내 **설정 닫기**를 눌러 닫습니다.
10. 다른 앱을 연 상태에서 **`화면 하단 중앙을 잠시 길게 누르면`** 서클 투 서치가 실행됩니다.


### Gemini Assistant 사용하기
1. **ColorOS 기본 하단 바 사용하기** 7단계까지 진행해주세요
2. 앱 내 **`전원 버튼 → 전원 버튼 길게 눌러 Gemini Assistant 열기`** 활성화
3. 앱 내 상단의 **`사용 시작`** 클릭
4. 앱의 연결 상태를 확인한 뒤 앱 내 **설정 닫기**를 눌러 닫습니다.


**태블릿을 재부팅한 후에는 로그 수집 권한을 다시 허용해야 합니다.** 재부팅 후 최초 잠금 해제 후 수 초 내에 팝업이 뜹니다.

Breeno를 비활성화하면 전원 버튼 등으로 Breeno를 여는 기능에도 영향을 줄 수 있습니다.
