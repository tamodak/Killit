// Root build file.
//
// Killit is a single-module project, so there is nothing shared to configure here. The plugins are
// declared with `apply false` purely to pin their versions from the catalogue; `:app` is what
// actually applies them.
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.ksp) apply false
    alias(libs.plugins.room) apply false
}
