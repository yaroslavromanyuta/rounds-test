plugins {
    alias(libs.plugins.android.application)
}

android {
    namespace = "com.rounds.test.app"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        applicationId = "com.rounds.test.app"
        minSdk = 24
        targetSdk = 37
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            optimization {
                enable = false
            }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }

    buildFeatures {
        viewBinding = true
    }
}

dependencies {
    constraints {
        // Nothing here uses ViewPager2 directly. It arrives through Material, and RecyclerView
        // publishes a constraint pinning it to 1.1.0-beta02, which would put a pre-release
        // artifact in the resolved graph. 1.1.0 is the stable release of that same line.
        implementation(libs.androidx.viewpager2)
    }

    implementation(project(":imageloader"))
    implementation(libs.androidx.activity.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.constraintlayout)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.ktx)
    implementation(libs.androidx.recyclerview)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.material)
    testImplementation(libs.junit)
    testImplementation(libs.json)
    testImplementation(libs.kotlinx.coroutines.test)
}