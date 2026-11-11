package kr.sidelab.donghwa

import ai.pleos.playground.vehicle.Vehicle
import ai.pleos.playground.vehicle.api.model.area.DoorArea
import ai.pleos.playground.vehicle.api.model.area.SeatArea
import ai.pleos.playground.vehicle.api.model.area.WindowArea
import ai.pleos.playground.vehicle.constant.area.DoorValue
import ai.pleos.playground.vehicle.constant.area.SeatValue
import ai.pleos.playground.vehicle.constant.area.WindowValue
import ai.pleos.playground.vehicle.listener.AmbientLightBrightnessListener
import ai.pleos.playground.vehicle.listener.DistanceDrivenListener
import ai.pleos.playground.vehicle.listener.DoorLockStateListener
import ai.pleos.playground.vehicle.listener.DoorPositionListener
import ai.pleos.playground.vehicle.listener.DrivingModeStateListener
import ai.pleos.playground.vehicle.listener.EvBatteryLevelListener
import ai.pleos.playground.vehicle.listener.EvChargePortOpenListener
import ai.pleos.playground.vehicle.listener.GearSelectionListener
import ai.pleos.playground.vehicle.listener.LightStateListener
import ai.pleos.playground.vehicle.listener.RangeRemainingListener
import ai.pleos.playground.vehicle.listener.SeatBeltBuckledStateListener
import ai.pleos.playground.vehicle.listener.SeatOccupancyListener
import ai.pleos.playground.vehicle.listener.SmartGlassStateListener
import ai.pleos.playground.vehicle.listener.SteeringWheelAngleListener
import ai.pleos.playground.vehicle.listener.TurnSignalStateListener
import ai.pleos.playground.vehicle.listener.WindowPositionListener
import ai.pleos.playground.vehicle.listener.WiperStateListener
import android.app.Activity
import android.content.pm.PackageManager
import android.os.Bundle
import android.util.Log

/**
 * 탐침 — **어떤 차 신호가 실제로 오는지** 직접 눌러 보고 적기 위한 도구.
 *
 * Vehicle SDK 는 도메인 20개·리스너 72개인데 우리는 둘만 쓰고 있었다. 나머지가
 * "문서에 있다"와 "이 차에서 실제로 온다"는 **다른 말**이다. SDK 가 그걸 가리는 함수를
 * 셋 가지고 있으니 셋 다 쓴다:
 *
 *   1. `getCapabilityInfo` — 차가 스스로 대는 목록(한 번에 전부)
 *   2. `check*Capabilities` — 신호별 지원 여부·허용 범위
 *   3. `register*` + 사람이 Virtual Console 에서 조작 → **값이 정말 오나**
 *
 * 1·2 가 "된다"고 해도 3 이 안 오면 이야기에 쓸 수 없다. 그래서 셋을 따로 적는다.
 *
 * 켜는 법:
 *   adb shell am start --user 10 -n kr.sidelab.donghwa/.VehicleProbe
 *   adb logcat -s DonghwaProbe
 *
 * ⛔ 재 보고 나면 지운다(배포판에 들어갈 물건이 아니다).
 */
class VehicleProbe : Activity() {

    private var vehicle: Vehicle? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        permissions()
        val v = runCatching { Vehicle(this).also { it.initialize() } }
            .onFailure { Log.e(TAG, "FAIL vehicle.init ${it.javaClass.simpleName} ${it.message}") }
            .getOrNull() ?: return
        vehicle = v
        capabilityInfo(v)
        wiper(v)
        window(v)
        seat(v)
        drive(v)
        light(v)
        door(v)
        energy(v)
        Log.i(TAG, "── 등록 끝. 이제 Virtual Console 에서 조작하면 VAL 줄이 떠야 한다")
    }

    /** 도메인마다 권한이 따로다. 무엇이 받아졌는지 먼저 적는다. */
    private fun permissions() {
        for (p in READ_PERMISSIONS) {
            val ok = checkSelfPermission(p) == PackageManager.PERMISSION_GRANTED
            Log.i(TAG, "PERM ${p.substringAfterLast('.')} = ${if (ok) "granted" else "DENIED"}")
        }
    }

    /** 차가 스스로 대는 목록. 이게 1순위 근거다. */
    private fun capabilityInfo(v: Vehicle) = ask("capabilityInfo") {
        v.getCarInfo().getCapabilityInfo(
            { map ->
                // ⚠️ 값의 타입(`vehicle.data.CapabilityConfig`)은 우리 클래스패스에 없다
                //    (별도 아티팩트). 그래서 **키만 꺼내고 본문은 toString 으로** 적는다.
                Log.i(TAG, "CAPINFO 개수=${map?.size}")
                map?.keys?.sorted()?.forEach { k -> Log.i(TAG, "CAPINFO key=$k") }
                Log.i(TAG, "CAPINFO raw=$map")
            },
            { e -> Log.w(TAG, "CAPINFO 실패 ${e.javaClass.simpleName} ${e.message}") },
        )
    }

    // ──────────────────────────────── 비 (와이퍼)

    private fun wiper(v: Vehicle) {
        val front = WindowArea(WindowValue.FRONT_WINDSHIELD)
        val w = v.getWiper()
        ask("wiper.check") {
            w.checkWiperStateCapabilities(
                { m -> cap("wiperState", m) },
                { e -> fail("wiperState", e) },
            )
        }
        ask("wiper.get") {
            w.getCurrentWiperState(front, { s -> got("wiperState", s) }, { e -> fail("wiperState.get", e) })
        }
        ask("wiper.reg") {
            w.registerWiperState(front, object : WiperStateListener {
                override fun onWiperStateUpdated(state: ai.pleos.playground.vehicle.constant.control.WiperState?) =
                    val_("wiperState", state)
                override fun onFailed(e: Exception) = fail("wiperState.reg", e)
            })
        }
    }

    // ──────────────────────────────── 바람 (창문·조광유리)

    private fun window(v: Vehicle) {
        val w = v.getWindow()
        ask("window.check") {
            w.checkWindowPositionCapabilities(
                { m -> cap("windowPosition", m) },
                { e -> fail("windowPosition", e) },
            )
        }
        for (area in listOf(WindowValue.ROW_1_LEFT, WindowValue.ROW_2_LEFT, WindowValue.ROW_2_RIGHT)) {
            val a = WindowArea(area)
            ask("window.get.$area") {
                w.getCurrentWindowPosition(a, { p -> got("windowPosition.$area", p) }, { e -> fail("windowPosition.$area", e) })
            }
            ask("window.reg.$area") {
                w.registerWindowPosition(a, object : WindowPositionListener {
                    override fun onWindowPositionUpdated(position: Int?) = val_("windowPosition.$area", position)
                    override fun onFailed(e: Exception) = fail("windowPosition.$area.reg", e)
                })
            }
        }
        ask("smartGlass.reg") {
            w.registerSmartGlassState(object : SmartGlassStateListener {
                override fun onSmartGlassStateUpdated(state: Boolean?) = val_("smartGlassState", state)
                override fun onFailed(e: Exception) = fail("smartGlassState", e)
            })
        }
    }

    // ──────────────────────────────── 뒷좌석에 아이가 탔나

    private fun seat(v: Vehicle) {
        val s = v.getSeat()
        ask("seat.check.occupancy") {
            s.checkSeatOccupancyCapabilities({ m -> cap("seatOccupancy", m) }, { e -> fail("seatOccupancy", e) })
        }
        for (area in listOf(SeatValue.ROW_1_RIGHT, SeatValue.ROW_2_LEFT, SeatValue.ROW_2_RIGHT)) {
            val a = SeatArea(area)
            ask("seat.get.$area") {
                s.getCurrentSeatOccupancy(a, { o -> got("seatOccupancy.$area", o) }, { e -> fail("seatOccupancy.$area", e) })
            }
            ask("seat.reg.$area") {
                s.registerSeatOccupancy(a, object : SeatOccupancyListener {
                    override fun onSeatOccupancyUpdated(occupied: Boolean?) = val_("seatOccupancy.$area", occupied)
                    override fun onFailed(e: Exception) = fail("seatOccupancy.$area.reg", e)
                })
            }
            ask("belt.reg.$area") {
                s.registerSeatBeltBuckledState(a, object : SeatBeltBuckledStateListener {
                    override fun onSeatBeltBuckledStateUpdated(buckled: Boolean?) = val_("seatBelt.$area", buckled)
                    override fun onFailed(e: Exception) = fail("seatBelt.$area", e)
                })
            }
        }
    }

    // ──────────────────────────────── 지금 기운다 (깜빡이·조향각·기어·주행모드)

    private fun drive(v: Vehicle) {
        val t = v.getTurnSignal()
        ask("turnSignal.check") {
            t.checkTurnSignalStateCapabilities({ c -> cap("turnSignal", c) }, { e -> fail("turnSignal", e) })
        }
        ask("turnSignal.get") {
            t.getCurrentTurnSignalState({ s -> got("turnSignal", s) }, { e -> fail("turnSignal.get", e) })
        }
        ask("turnSignal.reg") {
            t.registerTurnSignalState(object : TurnSignalStateListener {
                override fun onTurnSignalStateUpdated(value: ai.pleos.playground.vehicle.constant.area.TurnSignalValue?) =
                    val_("turnSignal", value)
                override fun onFailed(e: Exception) = fail("turnSignal.reg", e)
            })
        }

        val sw = v.getSteeringWheel()
        ask("steering.check") {
            // 이것만 Function2 다 — 지원 여부와 **허용 범위**를 같이 준다.
            sw.checkSteeringWheelAngleCapabilities(
                { c, bounds -> cap("steeringAngle", "$c bounds=$bounds") },
                { e -> fail("steeringAngle", e) },
            )
        }
        ask("steering.get") {
            sw.getCurrentSteeringWheelAngle({ a -> got("steeringAngle", a) }, { e -> fail("steeringAngle.get", e) })
        }
        ask("steering.reg") {
            sw.registerSteeringWheelAngle(object : SteeringWheelAngleListener {
                override fun onSteeringWheelAngleUpdated(angle: Float?) = val_("steeringAngle", angle)
                override fun onFailed(e: Exception) = fail("steeringAngle.reg", e)
            })
        }

        ask("gear.reg") {
            @Suppress("DEPRECATION")
            v.getGear().registerGearSelection(object : GearSelectionListener {
                override fun onSelected(gear: ai.pleos.playground.vehicle.constant.control.VehicleGear?) =
                    val_("gearSelection", gear)
                override fun onFailed(e: Exception) = fail("gearSelection", e)
            })
        }

        val dm = v.getDrivingMode()
        ask("drivingMode.get") {
            dm.getCurrentDrivingModeState({ m -> got("drivingMode", m) }, { e -> fail("drivingMode.get", e) })
        }
        ask("drivingMode.reg") {
            dm.registerDrivingModeState(object : DrivingModeStateListener {
                override fun onDrivingModeStateUpdated(mode: ai.pleos.playground.vehicle.constant.control.DrivingModeType?) =
                    val_("drivingMode", mode)
                override fun onFailed(e: Exception) = fail("drivingMode.reg", e)
            })
        }

        val od = v.getOdometer()
        ask("odometer.get") {
            od.getCurrentDistanceDriven({ d -> got("distanceDriven", d) }, { e -> fail("distanceDriven.get", e) })
        }
        ask("odometer.reg") {
            od.registerDistanceDriven(object : DistanceDrivenListener {
                override fun onDistanceDrivenUpdated(km: Float?) = val_("distanceDriven", km)
                override fun onFailed(e: Exception) = fail("distanceDriven.reg", e)
            })
        }
        ask("range.reg") {
            od.registerRangeRemainingListener(object : RangeRemainingListener {
                override fun onRangeRemainingChanged(km: Float?) = val_("rangeRemaining", km)
                override fun onFailed(e: Exception) = fail("rangeRemaining", e)
            })
        }
    }

    // ──────────────────────────────── 밤 (전조등·실내등·앰비언트)

    private fun light(v: Vehicle) {
        val l = v.getLight()
        ask("lowBeam.get") {
            l.getCurrentLowBeamLightState({ s -> got("lowBeam", s) }, { e -> fail("lowBeam.get", e) })
        }
        ask("lowBeam.reg") {
            l.registerLowBeamLightState(object : LightStateListener {
                override fun onLightStateUpdated(state: ai.pleos.playground.vehicle.constant.control.LightState?) =
                    val_("lowBeam", state)
                override fun onFailed(e: Exception) = fail("lowBeam.reg", e)
            })
        }
        ask("cabinLight.reg") {
            l.registerCabinLightState(object : LightStateListener {
                override fun onLightStateUpdated(state: ai.pleos.playground.vehicle.constant.control.LightState?) =
                    val_("cabinLight", state)
                override fun onFailed(e: Exception) = fail("cabinLight", e)
            })
        }
        ask("ambient.reg") {
            l.registerAmbientLightBrightnessListener(
                SeatArea(SeatValue.ROW_2_LEFT),
                object : AmbientLightBrightnessListener {
                    override fun onBrightnessUpdated(level: Int?) = val_("ambientBrightness", level)
                    override fun onFailed(e: Exception) = fail("ambientBrightness", e)
                },
            )
        }
    }

    // ──────────────────────────────── 누가 타고 내린다

    private fun door(v: Vehicle) {
        val d = v.getDoor()
        ask("door.check") {
            d.checkDoorPositionCapabilities({ m -> cap("doorPosition", m) }, { e -> fail("doorPosition", e) })
        }
        for (area in listOf(DoorValue.ROW_1_LEFT, DoorValue.ROW_2_LEFT, DoorValue.ROW_2_RIGHT)) {
            val a = DoorArea(area)
            ask("door.get.$area") {
                d.getCurrentDoorPosition(a, { p -> got("doorPosition.$area", p) }, { e -> fail("doorPosition.$area", e) })
            }
            ask("door.reg.$area") {
                d.registerDoorPosition(a, object : DoorPositionListener {
                    override fun onPositionUpdated(position: Int?) = val_("doorPosition.$area", position)
                    override fun onFailed(e: Exception) = fail("doorPosition.$area.reg", e)
                })
            }
            ask("lock.reg.$area") {
                d.registerDoorLockState(a, object : DoorLockStateListener {
                    override fun onDoorLockStateUpdated(locked: Boolean?) = val_("doorLock.$area", locked)
                    override fun onFailed(e: Exception) = fail("doorLock.$area", e)
                })
            }
        }
    }

    // ──────────────────────────────── 충전 (이미 쓰는 SOC 말고 나머지)

    private fun energy(v: Vehicle) {
        val b = v.getEvBattery()
        ask("batteryLevel.reg") {
            b.registerEvBatteryLevel(object : EvBatteryLevelListener {
                override fun onEvBatteryLevelUpdated(wh: Float?) = val_("evBatteryLevel", wh)
                override fun onFailed(e: Exception) = fail("evBatteryLevel", e)
            })
        }
        ask("chargePort.reg") {
            b.registerEvChargePortOpen(object : EvChargePortOpenListener {
                override fun onEvChargePortOpenUpdated(open: Boolean?) = val_("evChargePortOpen", open)
                override fun onFailed(e: Exception) = fail("evChargePortOpen", e)
            })
        }
    }

    // ──────────────────────────────── 적는 법

    /** 부르는 것 자체가 터질 수 있다(미지원 도메인). 터져도 다음 신호로 넘어간다. */
    private inline fun ask(what: String, body: () -> Unit) {
        runCatching(body).onFailure { Log.w(TAG, "THROW $what ${it.javaClass.simpleName} ${it.message}") }
    }

    // ⚠️ `Log.i` 는 Int 를 돌려준다. 식 본문으로 쓰면 반환형이 Int 가 되어
    //    리스너의 `Unit` 콜백을 못 구현한다 — 반환형을 Unit 으로 못박는다.
    private fun cap(name: String, value: Any?): Unit { Log.i(TAG, "CAP $name = $value") }
    private fun got(name: String, value: Any?): Unit { Log.i(TAG, "GET $name = $value") }
    private fun val_(name: String, value: Any?): Unit { Log.i(TAG, "VAL $name = $value") }
    private fun fail(name: String, e: Exception): Unit {
        Log.w(TAG, "FAIL $name ${e.javaClass.simpleName} ${e.message}")
    }

    override fun onDestroy() {
        runCatching { vehicle?.release() }
        super.onDestroy()
    }

    private companion object {
        const val TAG = "DonghwaProbe"

        val READ_PERMISSIONS = listOf(
            "pleos.car.permission.CAR_DRIVE",
            "pleos.car.permission.CAR_ENERGY",
            "pleos.car.permission.CAR_INFO",
            "pleos.car.permission.CAR_WINDOWS",
            "pleos.car.permission.CAR_SEATS",
            "pleos.car.permission.CAR_DOORS",
            "pleos.car.permission.CAR_LIGHTS",
            "pleos.car.permission.CAR_SAFETY",
        )
    }
}
