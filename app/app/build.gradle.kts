import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

/**
 * 배포판 서명 정보. **키와 비밀번호는 저장소 밖**(`~/.side/donghwa-signing.properties`)에 둔다.
 * 그 파일이 없으면 release 는 서명 없이 빌드된다 — 소스를 받은 사람이 빌드조차 못 하면 안 되니까.
 */
val signProps = File(System.getProperty("user.home"), ".side/donghwa-signing.properties")
val signing = Properties().apply {
    if (signProps.exists()) signProps.inputStream().use { load(it) }
}

android {
    namespace = "kr.sidelab.donghwa"
    compileSdk = 34

    defaultConfig {
        applicationId = "kr.sidelab.donghwa"
        minSdk = 28          // 차량 사이버보안 요건(사이닝 키 로테이션) — 문서가 정한 하한
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"
    }

    signingConfigs {
        if (signing.isNotEmpty()) {
            create("release") {
                storeFile = File(signing.getProperty("storeFile"))
                storePassword = signing.getProperty("storePassword")
                keyAlias = signing.getProperty("keyAlias")
                keyPassword = signing.getProperty("keyPassword")
                // minSdk 28 을 고른 이유가 **사이닝 키 로테이션**인데 그건 v3 기능이다.
                // 기본값으로 두면 v2 만 붙어 있었다(2026-11-01 apksigner 로 확인).
                enableV2Signing = true
                enableV3Signing = true
            }
        }
    }
    buildTypes {
        release {
            // ⛔ 줄이기(minify)는 켜지 않는다. Pleos SDK 가 리스너를 이름으로 찾는 구석이 있어
            //    규칙 없이 켜면 **조용히** 이벤트가 안 온다. 켤 거면 실주행으로 확인한 뒤에.
            isMinifyEnabled = false
            signingConfig = signingConfigs.findByName("release")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }

    // 이야기 박자는 **실주행 한 시간을 흘려 봐야** 검증된다. 그걸 JVM 에서 가짜 시계로 돈다
    // (에뮬레이터 압축 재생으로는 실주행에서만 나는 버그가 숨는다 — 2026-10-31).
    testOptions { unitTests.isReturnDefaultValues = true }

    // AAOS 표준 차량 API(android.car.Car, CarPropertyManager, CarUxRestrictionsManager)
    useLibrary("android.car")
}

dependencies {
    implementation("ai.pleos.playground:NaviHelper:3.0.5")
    implementation("ai.pleos.playground:Vehicle:2.3.2")
    implementation("ai.pleos.playground:TextToSpeech:3.3.1")
    // LLM 은 공모전 공식 SDK 목록(FAQ)에 없는 아티팩트지만 **넣어야 한다.**
    // ⛔ 2026-11-16 실측: 안 넣었더니 [PleosBackend] 가 ClassNotFoundException 으로 떨어져
    //    **어느 차에서도 생성이 안 됐다.** 클라이언트 라이브러리는 APK 안에 있어야 한다.
    //    리플렉션으로 잡는 건 '없을 때' 가 아니라 **판이 달라 메서드가 바뀌었을 때**
    //    NoSuchMethodError 로 앱이 죽지 않게 하려는 장치다.
    implementation("ai.pleos.playground:LLM:2.1.3.2")
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    testImplementation("junit:junit:4.13.2")
}
