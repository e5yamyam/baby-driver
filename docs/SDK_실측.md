# 차 신호 실측 — 값이 실제로 오는 신호 14개

> Vehicle SDK 의 신호를 하나씩 눌러 재 본 기록이다. **문서에 있다와 이 차에서 온다는 다른 말이라서**,
> `checkXxxCapabilities` → `getCurrentXxx` → `registerXxx` 순으로 전수 측정했다.
> 되는 것만이 아니라 **안 되는 것과 그 이유까지** 적었다.
> 측정 환경: Pleos Connect Emulator v2.1.0 · android-34 · arm64-v8a · Vehicle SDK 2.3.2.
> (이 문서는 공개용으로 정리한 판이고, 원본 기록은 팀 내부 기술 노트에 있다.)

## 1. AAR 에 무엇이 있나 — 도메인 20 · 리스너 72

의존하고 있는 `Vehicle-2.3.2.aar` 를 풀어 보면 **도메인 20개 · 리스너 72개**가 들어 있다.

재현:

```
AAR=$(find ~/.gradle/caches/modules-2 -name "Vehicle-2.3.2*.aar" | head -1)
unzip -q "$AAR" classes.jar && unzip -q classes.jar -d cls
ls cls/ai/pleos/playground/vehicle/api/*.class        # 도메인
ls cls/ai/pleos/playground/vehicle/listener/*.class   # 리스너
```

도메인 20개: `Brake` `CarInfo` `Display` `Door` `DrivingMode` `EvBattery` `Fuel` `Gear` `HVAC` `Light`
`Odometer` `Safety` `Seat` `SideMirror` `SteeringWheel` `Tire` `Toll` `TurnSignal` `Window` `Wiper`

이야기에 바로 붙는 리스너:

| 리스너 | 이야기에서 쓸 자리 |
|---|---|
| `WiperStateListener` | **비 오는 날 = 비 오는 화.** 날씨 API 없이 차가 안다 |
| `WindowPositionListener` | 창문을 열면 **바람이 들어온다** — 시즌 1 의 먹이가 물·바람·빛·소리다 |
| `SeatOccupancyListener` · `SeatBeltBuckledStateListener` | **뒷좌석에 아이가 탔는지 차가 안다** → 아이가 타야 이야기가 켜진다 |
| `TurnSignalStateListener` | 내비 TBT 보다 **직접적인** "지금 기운다" — 운전자가 꺾기로 마음먹은 순간 |
| `SteeringWheelAngleListener` | 실제로 도는 순간(선행 안내와 짝) |
| `DoorPositionListener` · `DoorLockStateListener` | 경유지에서 누가 타고 내린다 |
| `LightStateListener` · `AmbientLightBrightness*Listener` | 밤 장면 |
| `SmartGlassStateListener` · `SmartGlassLevelListener` | 조광 유리 — 터널·밤 연출 |
| `RangeRemainingListener` · `DistanceDrivenListener` | 분량 조절(ETA 와 교차 검증) |

- 이 `register*` 들은 **조회 계열**이다(`set*` 은 Vehicle **Control** 이라 준비 중).
- **Virtual Console 의 Convenience 탭에서 도어·창문·시트·조명·와이퍼를 사람이 조작할 수 있다**
  → 심사 자리에서 **와이퍼를 켜면 이야기에 비가 오는 것**을 그대로 보여 줄 수 있다.
- Vehicle SDK 는 register 직후 같은 값을 두 번 보내므로 **값이 바뀐 때만** 쓴다.
- 안 쓰기로 한 것: `Toll`·`Fuel`·`Tire`·`Odometer` 는 이야기에 안 붙는다.
  `SeatAirbagDeployed*`·`SeatBeltNotBuckledWarning` 같은 **안전 경고 계열은 아이 콘텐츠에 넣지 않는다.**

## 2. 그중 실제로 값이 오는 신호 — 탐침으로 재 봤다 (2026-11-02)

1절은 "AAR 에 있다"는 목록이다. 이건 **이 차에서 실제로 값이 오는가**다. 둘은 다른 말이다.
탐침(`VehicleProbe`)이 권한 확인 → `check*Capabilities` → `getCurrent*` → `register*` 순으로
한 신호씩 적는다. 헤드리스·창 모드 두 번 돌려 같은 결과를 얻었다.

**권한은 선언만 하면 전부 설치 시 granted** — `CAR_WINDOWS` `CAR_SEATS` `CAR_DOORS` `CAR_LIGHTS`
`CAR_SAFETY` 를 새로 선언해 **8개 전부 granted**. 즉 **권한이 걸림돌이 아니다.**
단 도메인마다 권한이 따로니, 선언하지 않으면 "지원 안 함"과 "권한 없음"이 구별되지 않는다.

**✅ 값이 실제로 온 신호 (register 콜백까지 받음)**

| 신호 | 받은 값 | 지원 범위(`check*`) | 이야기에서 |
|---|---|---|---|
| `Wiper` wiperState | `OFF` | **2개 영역**(앞/뒤 유리) · `[OFF, ON, SERVICE]` | 비 오는 날 |
| `Window` windowPosition | `0` (3개 영역 확인) | **4개 영역** · `0~10` ⚠️ 0~100 아니다 | 창문 열면 바람 |
| `Seat` seatOccupancy | **`true`** (1열 우·2열 좌·우) | **5개 영역** | 뒷좌석에 아이가 탔나 |
| `Seat` seatBeltBuckledState | `false` (3개 영역) | — | 안전벨트 |
| `TurnSignal` turnSignalState | `OFF` | `accessType=READ` | 지금 꺾는다 |
| `SteeringWheel` steeringWheelAngle | `0.0` | **`-470.0 ~ 500.0`** | 실제로 도는 순간 |
| `Gear` gearSelection | **`GEAR_PARK`** | — | 멈춤 판정(deprecated 인데 **값은 온다**) |
| `Odometer` distanceDriven | `0.0` | — | 분량 교차검증 |
| `Odometer` rangeRemaining | `500000.0` (m) | — | 분량 교차검증 |
| `Light` lowBeamLightState | `OFF` | — | 밤 장면 |
| `Light` cabinLightState | `OFF` | — | 실내 밝기 |
| `Door` doorPosition | `0` (3개 영역) | **6개 영역** · 문 4개 `0~1`, 2개(후드·트렁크) `0~100` | 타고 내림 |
| `EvBattery` evBatteryLevel | `77400.0` (Wh) | — | 충전 |
| `EvBattery` evChargePortOpen | `false` | — | 충전구 |

**등록은 받지만 값이 안 오는 것** — `Window.smartGlassState`, `Light.ambientLightBrightness`.
예외도 없고 콜백도 없다(= 조용히 안 온다). **"되는 것 / 안 되는 것" 다음의 세 번째 분류다.**

**안 되는 것**

| 신호 | 이유 |
|---|---|
| `CarInfo.getCapabilityInfo` | `SecurityException: getCapabilityInfo need Permission` — `CAR_INFO` 가 granted 인데도 거부. 특권 권한으로 보인다 |
| `DrivingMode.drivingModeState` | `property ID is not in carPropertyConfig list: 0x21400410` — 이 차에 없다 |
| `Door.doorLockState` 2열 | `area ID 0x10/0x40 not supported for DOOR_LOCK` — **1열만 있다** |

**값을 밖에서 바꿔 보는 길은 둘 다 막혀 있다**

1. **Virtual Console 쓰기 = `503 {"error":"emulator_inactive"}`.**
   읽기(`GET /vehicle/state`·`/vehicle/catalog`)는 되는데 쓰기(`PUT`)가 전부 거부된다.
   상태를 보면 `availability = {available:false, source:"not-run", transport:"dds"}`,
   `dds_ready.all = false` — **DDS 브리지가 붙지 않았다.**
   쿼리스트링 형식·세션 유지·브라우저 헤더·페이지 선 요청·드라이빙뷰 재시작,
   그리고 **창 모드 재시작**까지 해 봤지만 같다(헤드리스 탓이 아니다).
2. **`cmd car_service` 디버그 명령은 user 빌드라 막혀 있다.**
   `inject-vhal-event`·`set-property-value`·`get-carpropertyconfig` 가 모두
   `SecurityException: requires non-user build` — **VHAL 직접 주입은 이 AVD 에서 불가.**

그래서 **시연 대본**(`--es script e`, 비 오는 날)과 **검사 하네스**가 실차에서 올 신호를 흉내 내어
이야기 경로가 끝까지 이어지는지 본다. 신호를 바꿀 길이 열리면 그 대본은 지운다.

**Virtual Console 은 기계로 읽을 수 있는 신호 목록을 갖고 있다** — `GET /vehicle/catalog`
(JSON, 도메인 8개 · **기능 63개**). 기능마다 `label`·`write_endpoint`·`control`·`car_property`
(이름·hex id)가 적혀 있어 **"콘솔에서 사람이 바꿀 수 있는 신호"의 정본**이 된다.
우리 이야기에 붙는 property: `WINDSHIELD_WIPERS_SWITCH 0x13400BC7` · `WINDOW_POS 0x13400BC0` ·
`SEAT_OCCUPANCY 0x15400BB0` · `TURN_SIGNAL_STATE 0x11400408` · `PERF_STEERING_ANGLE 0x11600209` ·
`DOOR_POS 0x16400B00` · `CABIN_LIGHTS_SWITCH 0x11400F02` · `EXTERIOR_LIGHTS_MODE 0x21400754` ·
`RANGE_REMAINING 0x11600308` · `EV_BATTERY_LEVEL 0x11600309` · `EV_CHARGE_PORT_OPEN 0x1120030A`.

⚠️ 콘솔이 보는 값과 **SDK 가 읽는 값이 다르다** — 콘솔은 `seat.occupied` 가 전부 `false`,
SDK 는 `true`. 콘솔 쪽 `source` 가 `"default"`(기본값 표)라서다. **판정은 SDK 쪽으로 한다.**

### 탐침은 배포판에 들어가지 않는다

도구는 `app/src/debug/` 에만 있다 — 소스는 남고 배포판에는 **클래스도 권한도 들어가지 않는다**.
선언과 탐침용 권한은 `app/src/debug/AndroidManifest.xml` 에 따로 둔다.
그 결과 **배포 권한이 14개 → 9개로 줄었다.** 재 보려고만 선언했던 `CAR_DOORS`·`CAR_SAFETY` 등을 빼고
배포판으로 실제로 돌려 와이퍼·창문·좌석이 그대로 붙는 것을 확인했다(등록 실패 0건).
쓰지도 않는 권한을 요구하는 것은 차량 앱에서 그 자체로 흠이다.

## 3. 그중 이야기에 실제로 배선한 신호

재 본 14종 가운데 **이야기를 바꾸는 것만** 골라 넣었다. 나머지는 읽히는 것을 확인만 해 뒀다 —
쓰지 않는 신호에 권한을 달아 두면 설명할 수 없다.

| 신호 | 이야기 | 코드 |
|---|---|---|
| `WiperState`(앞유리) | **비** | `CarEvent.Rain` → `SceneKind.RAIN` |
| `WindowPosition`(2열 좌·우, 1열 우) | **바람** | `CarEvent.Breeze` → `SceneKind.WIND` |
| `SeatOccupancy`(2열 좌·우) | 동승자 수 | `CarEvent.Riders` → `StoryDirector.riders` |
| `TurnSignal` turnSignalState | **지금 꺾는다** | `CarEvent.Turning` → 화면의 배가 기운다 |
| `SteeringWheel` steeringWheelAngle | **얼마나 꺾나** | 기울기의 세기(크게 꺾은 커브가 더 눕는다) |

내비 TBT 는 180m 전에 **예고**하고(멀미 선행 안내), 깜빡이는 그 커브가 **실제로 오는 순간**이다.
둘을 다 가진 건 차 안뿐이다 — 말해 둔 것이 실제로 일어나는 걸 아이가 몸으로 확인한다.

왜 이것이 이야기를 늘리나: 길이 주는 장면(터널·방지턱)은 한 주행에 한 번뿐이라 어느 날이나 같은데,
와이퍼·창문은 **그날 날씨·그날 창문**이다. 같은 책이 비 오는 날과 맑은 날에 다른 쪽을 읽는다.

### 붙이면서 걸린 함정

- **핸들 각도를 이벤트로 내보내지 않는다.** 주행 중 쉴 새 없이 바뀌어서 그대로 흘리면
  이야기가 그것만 받는다. `CarEventHub` 가 **적어만 두었다가** 깜빡이가 켜질 때 한 번 읽는다.
- **깜빡이는 깜빡인다.** 차가 상태가 아니라 램프를 그대로 올려 주면 0.5초마다 켜짐/꺼짐이 온다.
  **꺼짐은 2초 늦게 믿는다**(`TURN_HOLD_MS`). 안 그러면 배가 떨린다.
- **신호마다 말을 만들지 않는다.** 깜빡이는 한 번 가는 길에 수십 번 켜지고, 그때마다 한 줄씩 끼면
  책이 아니라 주행 안내가 된다. 쓰는 쪽은 화면 하나다.
- 기울기는 커브마다 **두 번**(들어갈 때·나올 때)만 바뀐다 — 주행 중엔 정지 그림이라는
  화면 원칙 그대로다. 각도를 실시간으로 따라가게 하면 그 원칙이 깨진다.
- 기울기에 **바닥 0.35** 를 둔다 — 각도가 안 오는 차에서도 깜빡이만으로 기울어야 한다.
  하네스(`TurningLeanTest`)가 지킨다.
- 와이퍼는 **`== ON`** 으로 본다. `!= OFF` 면 **`SERVICE`(정비 위치)에서도 비가 온다.**
- 창문 위치는 **0~10** 이다. 백분율로 알고 30 쯤에서 자르면 영원히 안 열린 것이 된다.
- 좌석 탑승은 에뮬레이터 기본값이 `true` 다 → "앉으면 켠다"를 조건으로 걸면 늘 참이다.
  **없을 때 빼는 쪽으로만** 쓴다.
- 도메인마다 `runCatching` 을 따로 감싼다. 한 덩이로 묶으면 와이퍼가 없는 차에서
  **뒤에 오는 창문·좌석이 통째로 안 붙는다.**
- 날씨 쪽에서는 손님이 자라지 않게 막는다(검사기가 지킨다). 날씨 쪽은 순서를 건너뛰어
  당겨 읽히므로, 거기서 과제가 풀리면 뒤에 남은 "아직 말랐어" 쪽이 그 뒤에 나온다.
