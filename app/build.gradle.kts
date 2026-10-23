plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.alisport.goldpin"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.alisport.goldpin"
        minSdk = 24
        targetSdk = 34
        versionCode = 54
        versionName = "1.4.41"
        resourceConfigurations += listOf("fa", "en")
    }

    signingConfigs {
        create("appkey") {
            storeFile = file("../keystore/goldpin.jks")
            storePassword = "goldpin1384"
            keyAlias = "goldpin"
            keyPassword = "goldpin1384"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            isShrinkResources = false
            signingConfig = signingConfigs.getByName("appkey")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    kotlinOptions { jvmTarget = "11" }

    packaging {
        resources.excludes += setOf("META-INF/*.kotlin_module", "META-INF/versions/**")
    }

    // دادهٔ یک‌سالهٔ بک‌تست (gzip) داخل APK باندل شده است. فشرده‌سازی دوبارهٔ یک فایل gzip
    // تقریباً هیچ سودی ندارد، ولی استخراجش را کند و `openFd` را غیرممکن می‌کند؛ پس خاموش است.
    androidResources {
        noCompress += "gz"
    }

    lint { abortOnError = false }

    testOptions {
        unitTests.isIncludeAndroidResources = true
        unitTests.isReturnDefaultValues = true
        unitTests.all {
            it.testLogging { showStandardStreams = true }
        }
    }
}

dependencies {
    implementation(project(":engine"))

    // تست دود (اجرای واقعی اپ روی JVM) — فقط برای بیلد محلی، داخل APK نمی‌رود
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.robolectric:robolectric:4.12.2")
}
