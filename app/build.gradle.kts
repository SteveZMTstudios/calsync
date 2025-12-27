plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
}

tasks.withType<JavaCompile> {
    options.encoding = "utf-8"
}
android {
    namespace = "top.stevezmt.calsync"
    compileSdk = 36

    // Native (llama.cpp) build requires a valid NDK installation.
    // Pin to an installed version that contains source.properties.
    ndkVersion = "29.0.13599879"

    defaultConfig {
        applicationId = "top.stevezmt.calsync"
        minSdk = 23
        targetSdk = 36
        versionCode = 10
        versionName = "0.1.4"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        externalNativeBuild {
            cmake {
                cppFlags += "-std=c++17"
            }
        }
    }

    buildFeatures {
        buildConfig = true
    }

    flavorDimensions.add("version")
    productFlavors {
        create("foss") {
            dimension = "version"
            versionNameSuffix = "-foss"
        }
        create("full") {
            dimension = "version"
        }
    }

    val requestedAbis = project.findProperty("abiFilter")?.toString()?.split(",")
    splits {
        abi {
            isEnable = true
            reset()
            if (requestedAbis != null) {
                include(*requestedAbis.toTypedArray())
            } else {
                include("arm64-v8a", "x86_64", "armeabi-v7a", "x86")
            }
            isUniversalApk = false
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            // TODO: Remove this before production release
            // signingConfig = signingConfigs.getByName("debug")
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    kotlinOptions {
        jvmTarget = "11"
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
        }
    }

    applicationVariants.all {
        val baseVersionCode = versionCode
        outputs.all {
            val output = this as com.android.build.gradle.internal.api.ApkVariantOutputImpl
            val abi = output.getFilter(com.android.build.OutputFile.ABI)

            // Map ABI to a suffix as requested by F-Droid auditors
            // 1 for armeabi-v7a, 2 for arm64-v8a, etc.
            val abiSuffix = when (abi) {
                "armeabi-v7a" -> 1
                "arm64-v8a" -> 2
                "x86" -> 3
                "x86_64" -> 4
                else -> 0 // universal or others
            }

            output.versionCodeOverride = baseVersionCode * 10 + abiSuffix

            val abiName = abi ?: "universal"
            output.outputFileName = "calsync-${versionName}-${abiName}.apk"
        }
    }

    dependenciesInfo {
        includeInApk = false
        includeInBundle = false
    }
}

dependencies {

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.material)
    // jieba for chinese segmentation to improve title extraction
    implementation(libs.jieba)
    // Natural language time parsing (Java, rule-based)
    implementation(libs.xk.time)
    "fullImplementation"(libs.mlkit.entity.extraction)
    testImplementation(libs.junit)
    testImplementation(libs.mockito.core)
    testImplementation(libs.mockito.kotlin)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
}
