import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

val releaseSigningProperties = Properties().apply {
    val signingFile = rootProject.file(".release-signing.local")
    if (signingFile.exists()) {
        signingFile.inputStream().use(::load)
    }
}

android {
    namespace = "me.xiaozhi.androidclient"
    compileSdk = 34

    defaultConfig {
        applicationId = "me.xiaozhi.androidclient"
        minSdk = 26
        targetSdk = 34
        versionCode = 15
        versionName = "1.2.11"
    }

    signingConfigs {
        if (releaseSigningProperties.isNotEmpty()) {
            create("release") {
                storeFile = rootProject.file(releaseSigningProperties.getProperty("storeFile"))
                storePassword = releaseSigningProperties.getProperty("storePassword")
                keyAlias = releaseSigningProperties.getProperty("keyAlias")
                keyPassword = releaseSigningProperties.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            signingConfigs.findByName("release")?.let { signingConfig = it }
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        compose = true
        // 生成 BuildConfig.DEBUG，用于把开发期调试接口限制在 debug 构建里。
        buildConfig = true
    }

    composeOptions {
        kotlinCompilerExtensionVersion = "1.5.14"
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

dependencies {
    implementation(platform("androidx.compose:compose-bom:2024.06.00"))
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.activity:activity-compose:1.9.1")
    implementation("androidx.activity:activity-ktx:1.9.1")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.4")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.4")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.4")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material:material-icons-core")
    // 注意：**不要**加回 material-icons-extended。
    //
    // 那个 AAR 有 35 MB —— 它把全部约一万个图标都编进 dex，而本项目一共只用过其中 2 个
    // （Timer 和 Visibility，现已改成自带的矢量 XML，见 res/drawable/ic_timer.xml）。
    // 实测后果（TV5701，32 位 Android 9）：debug 包光 classes.dex 就有 43 MB，
    // 而该机没做过 dexopt，ART 每次冷启动都要现场校验这堆字节码——
    // `am start -W` 量到 WaitTime 10179ms；手动 `cmd package compile -m speed` 之后
    // 掉到 2744ms。开机那 10 秒白屏基本全是它。
    //
    // 用到的另外 4 个图标（ArrowBack / Close / PlayArrow / Settings）都在 icons-core 里，
    // 这个小包继续留着。
    implementation("androidx.compose.material3:material3")
    implementation("com.google.android.material:material:1.12.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("io.github.jaredmdobson:concentus:1.0.2")
    implementation("io.coil-kt:coil-compose:2.7.0")
    implementation("com.belerweb:pinyin4j:2.5.1")
    implementation("com.google.zxing:core:3.5.3")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20231013")
    debugImplementation("androidx.compose.ui:ui-tooling")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}
