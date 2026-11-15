# 베이비드라이버

**내비게이션 경로와 차량 데이터를 바탕으로 AI가 그날의 주행에 맞는 동화를 실시간으로 만들어 읽어 주고, 도착하면 한 권의 동화책으로 저장해 주는 뒷좌석 아이용 오디오 동화 앱.**

현대자동차그룹 차량용 앱 개발 공모전 출품작 (팀 Mumu Lab).

## 주요 기능

| 기능 | 활용하는 차량 데이터 | 설명 |
|---|---|---|
| 주행 맞춤 동화 생성 | 목적지, 경로, 현재 위치, 시간대, 와이퍼(비) | 터널, 과속방지턱, 어린이보호구역 같은 길 위의 신호가 이야기 속 사건이 된다 |
| 도착 시각에 맞춰 끝나는 이야기 | 남은 시간, 경로 변경, 정체 | 12분 등원길은 짧은 한 편, 65분 나들이는 긴 한 편. 목적지에 닿는 순간 결말이 나온다 |
| 멀미 케어 선행 안내 | 다음 회전 방향과 거리, 급커브, 제동 | 차가 흔들리기 전에 먼저 알려 주고 시선을 창밖 멀리 보낸다 |
| 차량 음성 낭독 | 차량 TTS, 오디오 포커스 | 내비게이션 안내가 나오면 양보하고 문장 단위로 이어 읽는다 |
| 여행 동화책 저장 | 그날의 경로, 목적지, 날짜 | 주행 한 번이 책 한 권으로 책장에 남는다 (주차 중에만 열람) |

## 현재 프로토타입의 범위

이 저장소의 코드는 위 기획 중 **주행 신호를 이야기로 연출하는 엔진**을 먼저 구현하고 검증한 프로토타입이다.

| 구분 | 현재 프로토타입 | 목표 (2차 PT 전까지) |
|---|---|---|
| 동화 문장 | 앱에 미리 넣어 둔 이야기 문장을 주행 신호에 맞춰 읽는다 | Pleos LLM SDK로 주행마다 생성한다 |
| 음성 | 미리 만든 나레이션(MeloTTS)을 재생하고, 아이 이름·지명이 든 문장만 기기 TTS가 읽는다 | Pleos TextToSpeech SDK로 낭독한다 |
| 주행 신호 | 앱에 넣어 둔 주행 시나리오(시연 대본)로 재생한다 | 실차의 NaviHelper·Vehicle SDK 신호를 받는다 |

Pleos Connect Emulator v2.1.0에서는 온디바이스 LLM 추론과 음성 엔진이 비활성 상태이고 외부에서 주행 신호를 넣을 수 없어, 위와 같이 연출 엔진부터 검증했다. LLM 권한 부여, 서비스 연결, 콜백 등록이 정상 동작하는 것은 확인했다 (`docs/SDK_실측.md`).

이미 동작하는 것: NaviHelper 연동, 차량 신호 7개 도메인 연결, 남은 시간 기반 길이 조절, 멀미 선행 안내, 도착 시 동화책 저장과 책장, 주행 제한(CarUxRestrictions) 대응.

## APK

`apk/MumuLab_베이비드라이버_release.apk` (release 빌드, v3 서명)

## 실행 방법

```
adb install -r --user 10 apk/MumuLab_베이비드라이버_release.apk
adb shell pm grant --user 10 kr.sidelab.donghwa android.car.permission.CAR_SPEED
adb shell am start --user 10 -n kr.sidelab.donghwa/.MainActivity
```

`CAR_SPEED`는 런타임 권한이라 첫 실행에서 한 번 묻는다(화면에서 「허용」을 눌러도 된다). 나머지 Pleos 권한은 설치만으로 부여된다.

### 시연 모드 (실제 주행 없이 전 구간 재생)

```
adb shell am start --user 10 -n kr.sidelab.donghwa/.MainActivity \
    --ez demo true --es script a --es name 하늘 --ei age 5
```

| 대본 | 내용 |
|---|---|
| `a` | 나들이 65분: 커브, 방지턱, 터널, 휴게소가 고루 나온다 |
| `b` | 등원 12분: 짧은 한 편 |
| `c` | 조용한 길: 사건이 없어도 이야기가 이어진다 |
| `d` | 충전 대기 25분: 주행 신호가 하나도 없는 경우 |
| `e` | 비 오는 날 20분: 와이퍼, 창문, 뒷좌석 신호가 이야기를 바꾼다 |

## 테스트 환경과 필요한 설정

| 항목 | 내용 |
|---|---|
| 에뮬레이터 | Pleos Connect Emulator v2.1.0 (`system-images;android-34;pleos_car_emulator;arm64-v8a`) |
| 화면 규격 | 2560×1440 (AVD: android-34, arm64-v8a) |
| SDK | NaviHelper 3.0.5, Vehicle 2.3.2, TextToSpeech 3.3.1 |
| 빌드 | AGP / Gradle (JDK 17), `minSdk 28`, `targetSdk 34` |
| 확인 환경 | macOS 헤드리스 에뮬레이터(`-no-window`), 재부팅 후 자동 시작까지 확인 |

## 직접 빌드

```
./gradlew :app:assembleDebug        # 디버그
./gradlew :app:assembleRelease      # 배포판 (서명 키가 없으면 서명 없이 빌드된다)
```

서명 키와 비밀번호는 저장소에 넣지 않았다.

## 구조

```
CarEventHub     차량 신호(NaviHelper, Vehicle, CarProperty)를 모아 주행 이벤트로 정리한다
StoryDirector   남은 시간으로 분량과 간격을 정하고, 어떤 이벤트를 언제 이야기에 넣을지 결정한다
Episode         프로토타입용 이야기 문장 (LLM 생성 연결 후에는 예비 이야기로 쓴다)
Narrator        음성 출력. 오디오 포커스를 양보하고 문장 단위로 이어 읽는다
SceneView       장면에 맞는 정지 그림을 표시한다
StoryService    화면 없이 이야기를 진행한다 (지도를 밀어내지 않는다)
Library         주행 하나를 동화책 한 권으로 저장한다 (기기 안에만)
SettingsActivity / BookActivity   주차 중에만 열린다
```

StoryDirector는 차량 SDK에 의존하지 않도록 분리해, 이후 LLM 생성과 TTS 낭독을 연결할 때 연출 규칙을 그대로 쓴다.

## 자동 검사

실제 주행 시간을 가상 시계로 빠르게 재생해, 주행 시나리오 66가지에서 문장 반복, 긴 침묵, 도착 전 결말, 나이에 맞지 않는 긴 문장 등을 검사한다. 결과 전사는 `docs/전사.txt`에 있다.

```
cd app && ./gradlew :app:testDebugUnitTest --rerun-tasks
```

## 함께 넣은 문서

| 경로 | 내용 |
|---|---|
| `docs/상세설계.md` | 프로토타입 연출 엔진의 상세 설계 |
| `docs/SDK_실측.md` | 차량 신호 실측 결과 |
| `docs/전사.txt` | 자동 검사 전사 |
| `docs/사용설명서.html` | 앱 사용설명서 |
| `story/시즌1.md` | 프로토타입용 이야기 설정 |
| `화면/` | 앱 화면 7장 (에뮬레이터 실행 화면) |

## 그림과 소리

- 장면 그림과 배·구름 그림은 직접 생성했다 (FLUX.2 klein 4B, 가중치 Apache-2.0, 로컬 생성).
- 프로토타입 나레이션은 MeloTTS(MIT)로 만들었다.

## 개인정보

아이 이름과 다녀온 장소가 들어가는 이야기이므로 기기 밖으로 내보내지 않는다. 서버와 계정을 쓰지 않고, 만든 책은 앱 내부 저장소에만 둔다.
