# 베이비드라이버 (Donghwa)

**차가 지금 보내는 신호를 받아, 뒷좌석 아이에게 들려줄 동화 한 편으로 연출하는 차량 앱.**

목적지를 넣고 출발하면 도착 시각에 **딱 맞게 끝나는** 이야기가 읽히고, 도착하면 그 주행이
동화책 한 권이 되어 앱 책장에 남는다. 커브·과속방지턱·터널·어린이보호구역이 이야기의 장면이 되고,
급커브는 **흔들리기 전에** 먼저 알려 준다(멀미 케어).

현대자동차그룹 차량용 앱 개발 공모전 출품작.

## 함께 넣은 문서

| | |
|---|---|
| `docs/상세설계.md` | 접수 서류 ②(앱 프로젝트 소개)의 **상세판** — 근거·수치를 줄이지 않은 판 |
| `docs/SDK_실측.md` | 차 신호를 하나씩 눌러 재 본 결과 — **값이 실제로 오는 신호 14개**와 안 되는 것의 이유 |
| `docs/전사.txt` | 검사 전사 전문 — 주행 대본별로 **언제 무슨 줄을 읽었는지** 분 단위 기록 |
| `docs/사용설명서.html` | 앱 사용설명서 |
| `story/시즌1.md` | 시즌 1 이야기 정본 — 12화 뼈대·설정·분량 배분 |
| `app/app/src/main/java/kr/sidelab/donghwa/Episode.kt` | **원고 본문 290쪽** — 책 본문·조각 대사·나이별 짧은 판 |
| `화면/` | 앱 화면 7장 — 에뮬레이터에서 실제로 돌려 찍은 것 |

## APK

`apk/MumuLab_베이비드라이버_release.apk` — release 빌드, v3 서명.

```
adb install -r --user 10 apk/MumuLab_베이비드라이버_release.apk
adb shell pm grant --user 10 kr.sidelab.donghwa android.car.permission.CAR_SPEED
adb shell am start --user 10 -n kr.sidelab.donghwa/.MainActivity
```

`CAR_SPEED` 는 런타임 권한이라 첫 실행에서 한 번 묻는다(화면에서 「허용」을 눌러도 된다).
나머지 Pleos 권한은 설치만으로 부여된다.

## 테스트 환경

| | |
|---|---|
| 에뮬레이터 | Pleos Connect Emulator v2.1.0 (`system-images;android-34;pleos_car_emulator;arm64-v8a`) |
| AVD | android-34 · arm64-v8a · 2560×1440 |
| SDK | NaviHelper 3.0.5 · Vehicle 2.3.2 · TextToSpeech 3.3.1 |
| 빌드 | AGP / Gradle (JDK 17), `minSdk 28` · `targetSdk 34` |
| 확인 | macOS 헤드리스 에뮬레이터(`-no-window`) + 실제 재부팅으로 자동 시작까지 |

## 직접 빌드하려면

```
./gradlew :app:assembleDebug        # 디버그
./gradlew :app:assembleRelease      # 배포판(서명 키가 없으면 서명 없이 나온다)
```

서명 키와 비밀번호는 저장소에 넣지 않는다. 로컬 키 설정 파일이 있으면 그것으로 서명하고,
없으면 서명 없이 빌드된다.

## 보여 주는 길 (시연)

실제 주행 없이 전 구간을 재생할 수 있다.

```
adb shell am start --user 10 -n kr.sidelab.donghwa/.MainActivity \
    --ez demo true --es script a --es name 현차 --ei age 5
```

| 대본 | 무엇 |
|---|---|
| `a` | 나들이 65분 — 커브·방지턱·터널·휴게소가 골고루 |
| `b` | 등원 12분 — 짧아서 꼭 필요한 쪽만 읽는다 |
| `c` | 조용한 길 — 사건이 없어도 입을 다물지 않는다 |
| `d` | 충전 대기 25분 — 주행 신호가 **하나도** 없는 길 |
| `e` | 비 오는 날 20분 — 와이퍼·창문·뒷좌석 신호가 이야기를 바꾼다 |

`--ei episode 12` 로 마지막 화(작별)부터 볼 수 있다.

## 짜임새

```
CarEventHub     차 신호(NaviHelper·Vehicle·CarProperty)를 모아 CarEvent 로 바꾼다
StoryDirector   언제 무엇을 말할지 정한다. 차 SDK 를 모른다 — 그래서 JVM 에서 시험된다
Episode         이야기 원고(12화). 책 본문 · 조각 대사 · 나이별 짧은 판
Engine          한 벌만 사는 본체. 화면은 붙었다 떼지기만 한다
StoryService    화면 없이 이야기를 끌고 간다(지도를 밀어내지 않는다)
SceneView       장면을 그린다. 배경은 그려 둔 수채화, 배와 손님은 코드 — 손님은 화가 갈수록 커진다
Narrator        소리. 미리 구운 나레이션을 먼저 틀고, 없는 문장만 기기 TTS 로
Library         주행 하나를 동화책 한 권으로 저장(기기 안에만)
SettingsActivity / BookActivity   주차 중에만 열린다
```

## 실주행 박자로 돌려 보는 검사

압축 재생(시연)으로는 **실주행에서만 나는 버그가 숨는다.** 그래서 가짜 시계로 주행 수십 번을
밀리초에 흘려 보낸다. 사람이 읽을 전사가 `app/build/전사.txt` 에 떨어진다
(돌려 보지 않고 읽으려면 **`docs/전사.txt`** 에 담아 뒀다).

```
cd app && ./gradlew :app:testDebugUnitTest --rerun-tasks
```

## 그림과 소리

그림은 전부 **우리가 직접 생성**했다(FLUX.2 klein 4B, 가중치 Apache-2.0 · 로컬 생성).
받아 쓴 소재가 아니라 저작권이 걸리지 않는다.

- **장면 배경 26장**(`drawable-nodpi/bg_*.jpg`, 2048×1152).
  차 화면이 2560×1440 이라 1024 로 그리면 2.5배로 늘어나 붓자국이 뭉갠다.
  수평선 높이는 눈대중이 아니라 **재서** 적어 두고, `SceneArt.kt` 가 그 값으로 배를 띄운다.
- **배·손님 그림**(`drawable-nodpi/sp_*.png`).
  단색 바탕에 그리게 하고 테두리에서 번져 들어가며 오려 낸다.
  한 장을 **행렬로** 올려서 손님은 일곱 단계로 커지고 배는 길이 휘면 같이 기운다.
- **앱 아이콘**(`mipmap-*`) — 밤하늘 배경에 손님 그림을 겹친다.
- **나레이션 337줄**(`app/src/main/assets/voice/`) — **MeloTTS(MIT)** 로 구웠다.
  **아이 이름·지명·남은 시간이 든 문장은 굽지 않는다**(사람마다 다르다). 그런 줄은 기기 TTS 가 읽는다.
  파일 이름은 문장의 SHA-1 앞 16자이고, 하네스가 앱 쪽 규칙과 **대조해서 증명한다**.

## 개인정보

아이 이름과 다녀온 장소가 들어가는 이야기라서 **기기 밖으로 내보내지 않는다.**
서버도 계정도 쓰지 않고, 만든 책은 앱 내부 저장소에만 둔다.
