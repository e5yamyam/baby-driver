package kr.sidelab.donghwa

import ai.pleos.playground.navi.constants.NaviErrorCode
import ai.pleos.playground.navi.data.CautionPoint
import ai.pleos.playground.navi.data.CurrentLocationInfo
import ai.pleos.playground.navi.data.DestinationArrivedInfo
import ai.pleos.playground.navi.data.DestinationInfo
import ai.pleos.playground.navi.constants.RouteOption
import ai.pleos.playground.navi.data.RouteInfo
import ai.pleos.playground.navi.data.RouteStartInfo
import ai.pleos.playground.navi.data.TBTInfo
import ai.pleos.playground.navi.data.TripInfo
import ai.pleos.playground.navi.data.WaypointArrivedInfo
import ai.pleos.playground.navi.helper.NaviHelper
import ai.pleos.playground.navi.helper.listener.NaviHelperEventListener
import ai.pleos.playground.vehicle.Vehicle
import ai.pleos.playground.vehicle.api.model.area.SeatArea
import ai.pleos.playground.vehicle.api.model.area.WindowArea
import ai.pleos.playground.vehicle.constant.area.SeatValue
import ai.pleos.playground.vehicle.constant.area.TurnSignalValue
import ai.pleos.playground.vehicle.constant.area.WindowValue
import ai.pleos.playground.vehicle.constant.control.WiperState
import ai.pleos.playground.vehicle.listener.BrakePedalStatusListener
import ai.pleos.playground.vehicle.listener.EvBatteryStateOfChargeListener
import ai.pleos.playground.vehicle.listener.SeatOccupancyListener
import ai.pleos.playground.vehicle.listener.SteeringWheelAngleListener
import ai.pleos.playground.vehicle.listener.TurnSignalStateListener
import ai.pleos.playground.vehicle.listener.WindowPositionListener
import ai.pleos.playground.vehicle.listener.WiperStateListener
import android.car.Car
import android.car.VehicleGear
import android.car.VehiclePropertyIds
import android.car.drivingstate.CarUxRestrictionsManager
import android.car.hardware.CarPropertyValue
import android.car.hardware.property.CarPropertyManager
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log

/**
 * 차에서 오는 신호를 모아 [CarEvent] 로 바꿔 내보낸다.
 *
 * 설계 규칙
 * - SDK 마다 따로 실패할 수 있다. 하나가 안 열려도 나머지는 계속 흘러야 한다.
 *   그래서 initialize 는 묶어서 try 하지 않고 **구역별로** 감싼다.
 * - 콜백은 어느 스레드로 올지 보장이 없으니 메인 핸들러로 모은다.
 * - 문서가 "실제로 오는 이벤트는 Navigation 앱 구현에 따라 다르다"고 했으므로
 *   받은 콜백은 전부 로그로 남긴다(무엇이 실제로 오는지가 이 단계의 목표다).
 */
class CarEventHub(
    private val context: Context,
    private val onEvent: (CarEvent) -> Unit,
) {
    companion object {
        const val TAG = "DonghwaCar"

        /** 멀미 선행 안내를 꺼낼 거리. 이보다 멀면 아직 말하지 않는다. */
        const val TURN_NOTICE_METERS = 180

        /** 같은 턴을 두 번 말하지 않기 위한 최소 간격(ms). */
        const val TURN_DEDUP_MS = 6_000L

        /**
         * 창문이 **닫힌** 값. 이보다 크면 바람이 들어온다.
         *
         * ⚠️ 창문 위치의 허용 범위는 **0~10** 이다(2026-11-02 실측 · 0~100 이 아니다).
         *    살짝 틔운 것도 아이에게는 바람이라 1 부터 센다.
         */
        const val WINDOW_SHUT = 0

        /** 바람은 **뒷창문**이 먼저 알려 준다 — 아이가 앉은 자리다. 앞창은 운전자 몫. */
        val REAR_WINDOWS = listOf(WindowValue.ROW_2_LEFT, WindowValue.ROW_2_RIGHT, WindowValue.ROW_1_RIGHT)

        /** 뒷좌석 — 2열 좌·우. 3열은 이 차에 없다(2026-11-02 실측: 5곳 중 확인된 것만 쓴다). */
        val REAR_SEATS = listOf(SeatValue.ROW_2_LEFT, SeatValue.ROW_2_RIGHT)

        /** 이 속도를 넘으면 "달리는 중"으로 본다(km/h). */
        const val MOVING_KPH = 1.5f

        /**
         * 깜빡이가 꺼진 뒤 이만큼은 기운 채로 둔다(ms).
         *
         * ⚠️ 깜빡이는 이름 그대로 **깜빡인다.** 차가 상태가 아니라 램프를 그대로 올려 주면
         *    0.5초마다 켜짐/꺼짐이 오고, 그대로 받으면 배가 떨린다. 꺼짐은 **늦게** 믿는다.
         */
        const val TURN_HOLD_MS = 2_000L

    }

    private val main = Handler(Looper.getMainLooper())
    private fun emit(e: CarEvent) {
        main.post {
            Log.i(TAG, "event=$e")
            onEvent(e)
        }
    }

    private var navi: NaviHelper? = null
    private var vehicle: Vehicle? = null
    private var car: Car? = null
    private var propertyManager: CarPropertyManager? = null
    private var uxManager: CarUxRestrictionsManager? = null

    private var lastTurnAt = 0L
    private var lastTurnSide: CarEvent.TurnAhead.Side? = null
    private var moving = false

    // Vehicle SDK 는 register 직후 같은 값을 두 번 보낸다(2026-10-29 실측).
    // 값이 바뀐 때만 이야기로 넘긴다.
    private var lastBraking: Boolean? = null
    private var lastSoc: Int? = null
    private var lastRain: Boolean? = null
    private var lastBreeze: Boolean? = null
    private var lastRiders: Int? = null

    /**
     * 지금 핸들 각도. **이벤트로 내보내지 않는다** — 주행 중 쉴 새 없이 바뀌어서
     * 그대로 흘리면 이야기가 그것만 받는다. 깜빡이가 켜지는 순간에 **읽기만** 한다.
     */
    private var steerAngle = 0f
    private var lastSignal: TurnSignalValue? = null

    /** 창문은 **자리마다 따로** 온다 — 한 곳만 보면 뒷창문이 열린 걸 못 본다. */
    private val windowPos = mutableMapOf<WindowValue, Int>()
    private val rearSeats = mutableMapOf<SeatValue, Boolean>()

    /** 연결 전에 들어온 목적지 요청. 연결되면 그때 보낸다. */
    private var pendingRoute: Triple<Double, Double, String>? = null
    private var naviConnected = false

    // ──────────────────────────────── 내비

    private val naviListener = object : NaviHelperEventListener {
        override fun onRouteStarted(routeStartInfo: RouteStartInfo) {
            Log.i(TAG, "onRouteStarted $routeStartInfo")
            // 길이는 목적지 정보에서 받는다 — 바로 한 번 물어본다.
            navi?.getDestinationInfo()
        }

        override fun onDestinationInfo(destinationInfo: DestinationInfo) {
            emit(
                CarEvent.RouteStarted(
                    destinationName = destinationInfo.name,
                    remainSeconds = destinationInfo.remainTime,
                    remainMeters = destinationInfo.remainDistance,
                )
            )
        }

        override fun onTripInfo(tripInfo: TripInfo) {
            // eta 가 바뀠다 = 정체·우회. 이야기를 늘리거나 줄인다.
            emit(CarEvent.EtaChanged(remainSeconds = tripInfo.eta, remainMeters = tripInfo.distance))
        }

        override fun onTBTInfo(tbtInfoList: List<TBTInfo>) {
            val next = tbtInfoList.firstOrNull() ?: return
            val meters = next.distance.toInt()
            if (meters > TURN_NOTICE_METERS) return

            next.type.toLandmark()?.let { kind ->
                emit(CarEvent.Landmark(kind, meters))
                return
            }

            val side = next.type.toSide()
            if (side == CarEvent.TurnAhead.Side.STRAIGHT || side == CarEvent.TurnAhead.Side.UNKNOWN) return

            val now = System.currentTimeMillis()
            if (side == lastTurnSide && now - lastTurnAt < TURN_DEDUP_MS) return
            lastTurnSide = side
            lastTurnAt = now

            emit(
                CarEvent.TurnAhead(
                    side = side,
                    sharp = next.type.isSharp(),
                    meters = meters,
                    description = next.description,
                )
            )
        }

        override fun onCautionPointInfo(cautionPointList: List<CautionPoint>) {
            cautionPointList.minByOrNull { it.remainDistance }?.let {
                emit(CarEvent.CautionAhead(it.type, it.remainDistance))
            }
        }

        override fun onCurrentLocationInfo(currentLocationInfo: CurrentLocationInfo) {
            emit(CarEvent.Place(currentLocationInfo.address))
        }

        override fun onWayPointArrived(waypointArrivedInfo: WaypointArrivedInfo) =
            emit(CarEvent.WaypointArrived)

        override fun onDestinationArrived(destinationArrivedInfo: DestinationArrivedInfo) =
            emit(CarEvent.DestinationArrived)

        override fun onError(errorCode: NaviErrorCode) =
            emit(CarEvent.Failed("navi", errorCode.toString()))
    }

    // ──────────────────────────────── 차량

    private val brakeListener = object : BrakePedalStatusListener {
        override fun onBrakePedalStatusUpdated(status: Boolean?) {
            val pressed = status == true
            if (pressed == lastBraking) return
            lastBraking = pressed
            emit(CarEvent.Braking(pressed))
        }

        override fun onFailed(e: Exception) = emit(CarEvent.Failed("brake", e.message ?: "?"))
    }

    private val socListener = object : EvBatteryStateOfChargeListener {
        override fun onEvBatteryStateOfChargeUpdated(value: Int?) {
            val soc = value ?: return
            if (soc == lastSoc) return
            lastSoc = soc
            emit(CarEvent.ChargeLevel(soc.toFloat()))
        }

        override fun onFailed(e: Exception) = emit(CarEvent.Failed("soc", e.message ?: "?"))
    }

    // ──────────────────────────────── 차 신호 — 비·바람·동승 (2026-11-02 실측으로 확인한 것만)

    /**
     * 와이퍼가 움직이면 **비**다.
     *
     * ⚠️ `SERVICE`(정비 위치)·`OTHER` 는 비가 아니다 — `!= OFF` 로 보면 정비 중에 비가 온다.
     */
    private val wiperListener = object : WiperStateListener {
        override fun onWiperStateUpdated(state: WiperState?) {
            val falling = state == WiperState.ON
            if (falling == lastRain) return
            lastRain = falling
            emit(CarEvent.Rain(falling))
        }

        override fun onFailed(e: Exception) = emit(CarEvent.Failed("wiper", e.message ?: "?"))
    }

    /**
     * 창문이 조금이라도 열려 있으면 **바람**이다.
     *
     * ⚠️ 위치는 **0~10** 이다(2026-11-02 실측). 백분율로 알고 30 쯤에서 자르면 영원히 안 열린다.
     */
    private fun windowListener(area: WindowValue) = object : WindowPositionListener {
        override fun onWindowPositionUpdated(position: Int?) {
            windowPos[area] = position ?: 0
            val open = windowPos.values.any { it > WINDOW_SHUT }
            if (open == lastBreeze) return
            lastBreeze = open
            emit(CarEvent.Breeze(open))
        }

        override fun onFailed(e: Exception) = emit(CarEvent.Failed("window", e.message ?: "?"))
    }

    /** 뒷좌석에 몇 명 앉아 있나. 둘이면 "같이 찾자"가 되고 혼자면 그 쪽을 안 읽는다. */
    private fun seatListener(area: SeatValue) = object : SeatOccupancyListener {
        override fun onSeatOccupancyUpdated(occupied: Boolean?) {
            rearSeats[area] = occupied == true
            val n = rearSeats.values.count { it }
            if (n == lastRiders) return
            lastRiders = n
            emit(CarEvent.Riders(n))
        }

        override fun onFailed(e: Exception) = emit(CarEvent.Failed("seat", e.message ?: "?"))
    }

    /**
     * 깜빡이 — **예고한 그 커브가 지금 온다.**
     *
     * 내비는 180m 전에 "곧 오른쪽으로 꺾어요" 라고 말해 둔다([TURN_NOTICE_METERS]).
     * 깜빡이는 그게 **실제로 일어나는 순간**이고, 그때 화면의 배가 기운다.
     *
     * ⛔ 꺼짐을 바로 믿지 않는다([TURN_HOLD_MS]) — 깜빡이는 깜빡이기 때문이다.
     */
    private val turnSignalListener = object : TurnSignalStateListener {
        override fun onTurnSignalStateUpdated(state: TurnSignalValue?) {
            if (state == lastSignal) return
            lastSignal = state
            val side = when (state) {
                TurnSignalValue.LEFT -> CarEvent.Turning.Side.LEFT
                TurnSignalValue.RIGHT -> CarEvent.Turning.Side.RIGHT
                else -> null
            }
            main.removeCallbacks(turnOff)
            if (side == null) main.postDelayed(turnOff, TURN_HOLD_MS)
            else emit(CarEvent.Turning(side, kotlin.math.abs(steerAngle)))
        }

        override fun onFailed(e: Exception) = emit(CarEvent.Failed("turnSignal", e.message ?: "?"))
    }

    private val turnOff = Runnable { emit(CarEvent.Turning(null, 0f)) }

    /** 핸들 각도 — **적어만 둔다.** 쓰는 곳은 [turnSignalListener] 하나다. */
    private val steeringListener = object : SteeringWheelAngleListener {
        override fun onSteeringWheelAngleUpdated(angle: Float?) {
            steerAngle = angle ?: 0f
        }

        override fun onFailed(e: Exception) = emit(CarEvent.Failed("steering", e.message ?: "?"))
    }

    // ──────────────────────────────── AAOS 표준(속도·주행 규제)

    private val propertyCallback = object : CarPropertyManager.CarPropertyEventCallback {
        override fun onChangeEvent(value: CarPropertyValue<*>) = when (value.propertyId) {
            VehiclePropertyIds.PERF_VEHICLE_SPEED -> onSpeed((value.value as? Float) ?: 0f)
            VehiclePropertyIds.GEAR_SELECTION -> onGear((value.value as? Int) ?: 0)
            else -> Unit
        }

        override fun onErrorEvent(propId: Int, zone: Int) {
            emit(CarEvent.Failed("property", "propId=$propId"))
        }
    }

    private fun onSpeed(mps: Float) {
        val kph = mps * 3.6f
        val nowMoving = kph > MOVING_KPH
        if (nowMoving != moving) {
            moving = nowMoving
            emit(CarEvent.MotionChanged(moving = nowMoving, speedKph = kph))
        }
    }

    /** P 로 들어가면 멈춘 것으로 본다 — 정차 중에만 그림·선택지를 쓴다. */
    private fun onGear(gear: Int) {
        if (gear == VehicleGear.GEAR_PARK && moving) {
            moving = false
            emit(CarEvent.MotionChanged(moving = false, speedKph = 0f))
        }
    }

    // ──────────────────────────────── 생애주기

    fun start() {
        startNavi()
        startVehicle()
        // ⛔ **AAOS 붙기는 메인 스레드에서 하지 않는다.** `Car.createCar` 도 `getCarManager` 도
        //    차 서비스로 가는 binder 호출이라, 차 쪽이 느리면 그대로 **막힌 채로 기다린다.**
        //    2026-11-03 에뮬레이터가 바쁠 때 `StoryService.onCreate` 가 20초를 넘겨
        //    ANR 로 앱이 죽었다(스택 top: ICar.getCarService / getPropertyList).
        //    차가 늦게 붙는 건 괜찮다 — 이야기는 내비 신호만으로도 시작한다.
        //    여기서 오는 값은 전부 [emit] 으로 나가고, emit 은 메인 스레드에 다시 얹는다.
        Thread({ startAaos() }, "donghwa-aaos").apply { isDaemon = true }.start()
    }

    private fun startNavi() = runCatching {
        NaviHelper(context).also { helper ->
            navi = helper
            helper.initialize(
                { // onConnected
                    Log.i(TAG, "navi connected")
                    naviConnected = true
                    helper.addListener(naviListener)
                    helper.getDestinationInfo()   // 이미 주행 중일 수 있다
                    helper.getCurrentLocationInfo()
                    pendingRoute?.let { (lat, lng, name) ->
                        pendingRoute = null
                        requestRoute(lat, lng, name)
                    }
                },
                { t -> emit(CarEvent.Failed("navi.init", t.message ?: "?")) },
            )
        }
    }.onFailure { emit(CarEvent.Failed("navi.create", it.message ?: "?")) }

    private fun startVehicle() = runCatching {
        Vehicle(context).also { v ->
            vehicle = v
            v.initialize()
            v.getBrake().registerBrakePedalStatus(brakeListener)
            v.getEvBattery().registerEvBatteryStateOfCharge(socListener)
            // ⚠️ **도메인마다 따로 감싼다.** 와이퍼가 없는 차에서 register 가 터지면
            //    한 덩이로 묶어 놨을 때 **뒤에 오는 창문·좌석이 통째로 안 붙는다.**
            runCatching {
                v.getWiper().registerWiperState(WindowArea(WindowValue.FRONT_WINDSHIELD), wiperListener)
            }
            for (area in REAR_WINDOWS) {
                runCatching { v.getWindow().registerWindowPosition(WindowArea(area), windowListener(area)) }
            }
            for (area in REAR_SEATS) {
                runCatching { v.getSeat().registerSeatOccupancy(SeatArea(area), seatListener(area)) }
            }
            runCatching { v.getTurnSignal().registerTurnSignalState(turnSignalListener) }
            runCatching { v.getSteeringWheel().registerSteeringWheelAngle(steeringListener) }
        }
    }.onFailure { emit(CarEvent.Failed("vehicle", it.message ?: "?")) }

    private fun startAaos() = runCatching {
        Car.createCar(context).also { c ->
            car = c
            propertyManager = (c.getCarManager(Car.PROPERTY_SERVICE) as? CarPropertyManager)?.also { pm ->
                // ⛔ 여기서 `pm.propertyList` 로 **이 차가 무엇을 주는지** 찍어 봤었다(진단용).
                //    그 한 줄이 2026-11-03 에 앱을 죽였다 — 차 서비스로 가는 binder 호출이라
                //    기계가 바쁘면 **메인 스레드가 20초 넘게 막히고 ANR** 이 난다
                //    (ANR 스택 top: CarPropertyManager.getPropertyList).
                //    등록 실패는 아래 `ok` 로도 알 수 있으니 목록은 보지 않는다.
                for (prop in intArrayOf(
                    VehiclePropertyIds.PERF_VEHICLE_SPEED,
                    VehiclePropertyIds.GEAR_SELECTION,
                )) {
                    val ok = pm.registerCallback(propertyCallback, prop, CarPropertyManager.SENSOR_RATE_ONCHANGE)
                    if (!ok) emit(CarEvent.Failed("property.register", "propId=$prop"))
                }
            }
            uxManager = (c.getCarManager(Car.CAR_UX_RESTRICTION_SERVICE) as? CarUxRestrictionsManager)?.also { ux ->
                emit(CarEvent.RestrictionChanged(ux.currentCarUxRestrictions?.isRequiresDistractionOptimization == true))
                ux.registerListener { r ->
                    emit(CarEvent.RestrictionChanged(r.isRequiresDistractionOptimization))
                }
            }
        }
    }.onFailure { emit(CarEvent.Failed("aaos", it.message ?: "?")) }

    fun stop() {
        runCatching {
            navi?.removeListener(naviListener)
            navi?.release()
            naviConnected = false
        }
        runCatching {
            vehicle?.let { v ->
                v.getBrake().unregisterBrakePedalStatus(brakeListener)
                v.getEvBattery().unregisterEvBatteryStateOfCharge(socListener)
                v.release()
            }
        }
        runCatching {
            propertyManager?.unregisterCallback(propertyCallback)
            uxManager?.unregisterListener()
            car?.disconnect()
        }
        navi = null; vehicle = null; car = null; propertyManager = null; uxManager = null
    }

    /**
     * 목적지를 넣어 본다. `NAVI_ROUTE_SEARCH` 권한이 필요하고, 실제로 경로를 타는지는
     * Navigation 앱이 결정한다 — 되면 onRouteStarted 부터 줄줄이 들어온다.
     */
    fun requestRoute(latitude: Double, longitude: Double, name: String) {
        val helper = navi
        if (helper == null || !naviConnected) {
            // 연결은 비동기다. 먼저 부르면 조용히 묻히므로 기억해 뒀다 연결될 때 보낸다.
            pendingRoute = Triple(latitude, longitude, name)
            Log.i(TAG, "navi 연결 전 — requestRoute($name) 보류")
            return
        }
        runCatching {
            helper.requestRoute(
                RouteInfo(
                    latitude, longitude, null, null,
                    name, "", "", "",
                    RouteOption.RECOMMENDED, "", false,
                )
            )
            Log.i(TAG, "requestRoute($name) 보냄")
        }.onFailure { emit(CarEvent.Failed("route", it.message ?: "?")) }
    }

    /** 주행 중 주기적으로 길 정보를 당겨 온다(푸시로만 오지 않는 값들이 있다). */
    fun poll() {
        navi?.let {
            runCatching {
                it.getTBTInfo()
                it.getDestinationInfo()
                it.getCurrentLocationInfo()
            }
        }
    }
}
