import java.net.URI

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    // Room's annotation processor runs under KSP.
    alias(libs.plugins.ksp)
    alias(libs.plugins.room)
    // Generates the API client's JSON codecs at compile time, so nothing is bound by reflection.
    alias(libs.plugins.kotlin.serialization)
}

// The pairing server. Release builds talk to production; debug builds to the test server, unless
// `-Pkillit.apiBaseUrl=http://10.0.2.2:8080` points them at a server on the development machine
// (the debug network security config allows cleartext to that address only). The audience every
// request is signed for is the URL's host and port (protocol/SPEC.md §4.2).
val releaseApiBaseUrl = "https://api.tamodak.org"
val debugApiBaseUrl = (findProperty("killit.apiBaseUrl") as String?) ?: "https://api.test.tamodak.org"
fun audienceOf(baseUrl: String): String = URI(baseUrl).authority

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

        // Every other wait is a field too (PLAN.md §11, "Durations per build type"), three seconds
        // in debug builds. Windows the user has to act within (an invite code's 15 minutes, a
        // request's ±5-minute signature window) are not shortened: three seconds would make them
        // unusable.
        //
        // How long a guardian who never answers an UNPAIR keeps being a guardian (B1, B2).
        buildConfigField("long", "UNPAIR_TIMER_MILLIS", "3_000L")
        // The pause after a denied or abandoned unlock request (C4).
        buildConfigField("long", "UNLOCK_COOLDOWN_MILLIS", "3_000L")
        // Unlock requests allowed per day (C4); unlimited while developing.
        buildConfigField("int", "UNLOCKS_PER_DAY", "Integer.MAX_VALUE")
        // A guardian's reminder about waiting requests (D9): once, this long after a request
        // arrives, in debug builds; a daily daytime digest in release builds (0 here).
        buildConfigField("long", "REMINDER_DELAY_MILLIS", "3_000L")
        // How long a protected phone may stay silent before its guardians are told (H8).
        buildConfigField("long", "SILENCE_ALERT_MILLIS", "3_000L")

        buildConfigField("String", "API_BASE_URL", "\"$debugApiBaseUrl\"")
        buildConfigField("String", "API_AUDIENCE", "\"${audienceOf(debugApiBaseUrl)}\"")
    }

    buildTypes {
        release {
            optimization {
                // Code shrinking, obfuscation and resource shrinking. Keep rules that survive it
                // live in src/main/keepRules/.
                enable = true
            }
            // Fourteen days. Long enough to outlast a craving or a bad week, so the release only
            // happens if the decision still stands two weeks later; the countdown can be
            // cancelled at any point, so a change of heart costs nothing.
            buildConfigField("long", "RELEASE_DELAY_MILLIS", "1_209_600_000L")
            // Thirty days: a guardian who has vanished can't keep a phone forever, and one who is
            // still there has a month to say no.
            buildConfigField("long", "UNPAIR_TIMER_MILLIS", "2_592_000_000L")
            buildConfigField("long", "UNLOCK_COOLDOWN_MILLIS", "300_000L")
            buildConfigField("int", "UNLOCKS_PER_DAY", "6")
            buildConfigField("long", "REMINDER_DELAY_MILLIS", "0L")
            buildConfigField("long", "SILENCE_ALERT_MILLIS", "604_800_000L")
            buildConfigField("String", "API_BASE_URL", "\"$releaseApiBaseUrl\"")
            buildConfigField("String", "API_AUDIENCE", "\"${audienceOf(releaseApiBaseUrl)}\"")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    testOptions {
        // On the JVM, android.jar's stubs (Log above all) return defaults instead of throwing, so
        // code that logs can be unit-tested. No JVM test relies on an Android method doing anything.
        unitTests.isReturnDefaultValues = true
    }
    // The protocol vector tests run twice: on the JVM, where they are fast, and on a device, where
    // the code under test meets Android's own UTF-8 decoder and security providers, which are not
    // the desktop JVM's.
    sourceSets {
        for (name in listOf("test", "androidTest")) {
            getByName(name) {
                kotlin.directories.add("src/sharedTest/java")
                resources.directories.add("src/sharedTest/resources")
            }
        }
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

// Every schema version is exported and committed, so a migration can be tested against the exact
// tables an earlier build created.
room {
    schemaDirectory("$projectDir/schemas")
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
    // Backs the known-package list: the local half of what default-blocking remembers.
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)
    // Runs the periodic package check.
    implementation(libs.androidx.work.runtime)
    // Provisioning without a computer. `api` is the client, `provider` the manifest component.
    implementation(libs.shizuku.api)
    implementation(libs.shizuku.provider)
    // Pairing: end-to-end envelopes (Tink HPKE), the API client and its JSON.
    implementation(libs.tink.android)
    implementation(libs.okhttp)
    implementation(libs.kotlinx.serialization.json)
    // Hashes the passkey with Argon2id. Its POM also lists appcompat as a runtime dependency that
    // none of its classes use, so that is left out rather than shipped in a device-owner app.
    implementation(libs.argon2kt) {
        exclude(group = "androidx.appcompat", module = "appcompat")
    }
    testImplementation(libs.junit)
    testImplementation(libs.okhttp.mockwebserver)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
    debugImplementation(libs.androidx.compose.ui.tooling)
}