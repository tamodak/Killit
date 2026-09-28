plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "org.tamodak.killit"
    compileSdk {
        version = release(36) {
            minorApiLevel = 1
        }
    }

    defaultConfig {
        applicationId = "org.tamodak.killit"
        // The app assumes API 26 platform behaviour. A few features degrade below their ceiling
        // here — force-stop blocking needs 30 — and each is guarded at its call site rather than
        // raising the floor for everyone.
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"

        // Killit's meaningful tests are instrumented: DataStore and the device policy service have
        // no JVM equivalent worth faking.
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        // How long "unblock all & remove admin" waits before it can be carried out. The delay is
        // the feature: it puts distance between the decision and its effect, so a moment of
        // weakness cannot undo everything with one tap.
        //
        // Three seconds while developing, because otherwise the flow is untestable. Overridden
        // below for release.
        buildConfigField("long", "RELEASE_DELAY_MILLIS", "3_000L")
    }

    buildTypes {
        release {
            optimization {
                // Code shrinking, obfuscation and resource shrinking. Keep rules that survive it
                // live in src/main/keepRules/.
                enable = true
            }
            // Three days. Long enough that the decision has to survive a change of mood, short
            // enough that someone with a genuine reason is not stranded for a week.
            buildConfigField("long", "RELEASE_DELAY_MILLIS", "259_200_000L")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    buildFeatures {
        compose = true
        // For IKillitPrivilegedService, the binder interface to the Shizuku user service.
        aidl = true
        // KillitPrivilegedService rebuilds the admin component from BuildConfig.APPLICATION_ID
        // rather than trusting one passed over binder.
        buildConfig = true
    }
}

dependencies {
    // The BOM pins every Compose artifact to one compatible set, so the entries below carry no
    // versions of their own. Applied to androidTest as well, or the test artifacts drift from it.
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.core)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    // Backs LockPreferences: the local half of the credential store.
    implementation(libs.androidx.datastore.preferences)
    // Provisioning without a computer. `api` is the client, `provider` the manifest component.
    implementation(libs.shizuku.api)
    implementation(libs.shizuku.provider)
    testImplementation(libs.junit)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
    debugImplementation(libs.androidx.compose.ui.tooling)
}