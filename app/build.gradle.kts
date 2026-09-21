plugins {
    id("com.android.application")
}

android {
    namespace = "dev.uzumi.ime"
    compileSdk = 36

    defaultConfig {
        applicationId = "dev.uzumi.ime"
        minSdk = 30
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    testOptions {
        unitTests.isReturnDefaultValues = false
    }
}

dependencies {
    testImplementation("junit:junit:4.13.2")
}
