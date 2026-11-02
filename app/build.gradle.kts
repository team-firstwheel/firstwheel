import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// 서명 정보는 저장소에 올리지 않는 keystore.properties(루트)에서 읽는다.
// 파일이 없으면 디버그 키로 서명해, 키가 없어도 설치·실행 가능한 release APK를 만든다.
val keystoreProps = Properties().apply {
    val f = rootProject.file("keystore.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}
val hasReleaseKey = keystoreProps.getProperty("storeFile") != null

// 제출 규칙: 팀명_앱이름_release.apk
// 팀명은 gradle.properties에 두면 한글이 깨질 수 있어 여기에 둔다
val teamName = "공파리"
val appDisplayName = "첫바퀴"

android {
    namespace = "ai.firstwheel.app"
    compileSdk = 34
    // AAOS Car API(CarPropertyManager) 사용
    useLibrary("android.car")

    defaultConfig {
        applicationId = "ai.firstwheel.app"
        minSdk = 29
        targetSdk = 34
        versionCode = 1
        versionName = "0.1.0"
    }

    signingConfigs {
        if (hasReleaseKey) {
            create("release") {
                storeFile = rootProject.file(keystoreProps.getProperty("storeFile"))
                storePassword = keystoreProps.getProperty("storePassword")
                keyAlias = keystoreProps.getProperty("keyAlias")
                keyPassword = keystoreProps.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = if (hasReleaseKey) signingConfigs.getByName("release") else signingConfigs.getByName("debug")
        }
    }

    applicationVariants.all {
        val variant = this
        outputs.all {
            if (variant.buildType.name == "release") {
                (this as com.android.build.gradle.internal.api.BaseVariantOutputImpl).outputFileName =
                    "${teamName}_${appDisplayName}_release.apk"
            }
        }
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }
    composeOptions {
        kotlinCompilerExtensionVersion = "1.5.14"
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    sourceSets["main"].java.srcDirs("src/main/kotlin")
}

dependencies {
    implementation(project(":core"))

    implementation(platform("androidx.compose:compose-bom:2024.06.00"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.activity:activity-compose:1.9.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.2")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.2")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")

    // TODO: Pleos Connect SDK (Vehicle / ADAS / NaviHelper / Gleo AI / Sync)
    // 연동 가이드에 안내된 좌표로 추가한 뒤 PleosVehicleDataSource의 TODO를 채운다.
    // implementation("<pleos-connect-sdk-coordinates>")
}
