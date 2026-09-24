plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "dev.jev.wechatmood"
    compileSdk = 35

    defaultConfig {
        applicationId = "dev.jev.wechatmood"
        minSdk = 28          // 与 WeKit 一致；Android 9+
        targetSdk = 35
        versionCode = 14
        versionName = "1.1.3"

        // 依赖（androidx + DexKit）把方法数撑出了十几个 dex，入口类一度落在
        // classes11.dex 里。框架加载入口类走模块自己的 ClassLoader，理论上
        // 能找到任意 dex 里的类，但显式开 multidex 更稳妥。
        multiDexEnabled = true
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
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
        viewBinding = true
        buildConfig = true
    }

    lint {
        // 未签名的 debug 包不需要跑 release lint，避免拖慢迭代
        checkReleaseBuilds = false
    }

    packaging {
        resources.excludes += setOf(
            "META-INF/*.kotlin_module",
            "META-INF/DEPENDENCIES",
            "META-INF/LICENSE*",
        )
    }
}

dependencies {
    // 编译期需要 Xposed API，运行期由框架注入
    compileOnly(libs.xposed.api)

    // 钉住 androidx 的传递依赖版本。
    // YukiHookAPI 会带进较新的 androidx（core-ktx 1.17 / appcompat 1.7.1），
    // 那些要求 compileSdk 36+ 与 AGP 8.9.1+；而 compileSdk 37 对应的
    // platforms;android-37 在 Google 的 SDK 仓库里还不存在，拉不起来。
    // 用 constraints 固定到与 compileSdk 35 兼容的版本，避免构建被传递依赖顶掉。
    constraints {
        implementation("androidx.core:core-ktx") {
            version { strictly(libs.versions.coreKtx.get()) }
            because("核心库升到 1.17 会要求 compileSdk 36+")
        }
        implementation("androidx.core:core") {
            version { strictly(libs.versions.coreKtx.get()) }
            because("同上，core 与 core-ktx 必须同版本")
        }
        implementation("androidx.appcompat:appcompat") {
            version { strictly(libs.versions.appcompat.get()) }
            because("appcompat 1.7.1 要求 AGP 8.9.1+")
        }
        implementation("androidx.activity:activity") {
            version { strictly("1.9.3") }
            because("1.11.0 要求 compileSdk 36+")
        }
        implementation("androidx.activity:activity-ktx") {
            version { strictly("1.9.3") }
            because("同上")
        }
    }

    implementation(libs.yukihookapi.api)

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.material)
    implementation(libs.coroutines.android)
    implementation(libs.okhttp)

    // DexKit 要在微信进程里运行时读 dex，所以是 implementation
    implementation(libs.dexkit)

    testImplementation(libs.junit)
    testImplementation("org.json:json:20240303")
    testImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")
}

// Live tests are opt-in, never part of a normal build and never store credentials in BuildConfig.
tasks.withType<Test>().configureEach {
    if (providers.gradleProperty("jevLiveTest").orNull == "true") {
        include("**/LiveJevVerificationTest*")
        outputs.upToDateWhen { false }
        testLogging.showStandardStreams = true
    } else {
        exclude("**/LiveJevVerificationTest*")
    }
}
