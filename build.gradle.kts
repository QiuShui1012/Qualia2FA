// Declaring a plugin here, even with `apply false`, is what puts its implementation on the
// classpath every module shares. A plugin a module applies but this block does not declare is
// resolved into a classloader of its own, and the plugins in the shared one then cannot see
// its classes: the Kotlin plugin reads the Android plugin's version by reflecting on
// `com.android.Version`, so with the Android plugin isolated that lookup fails and the build
// stops with "Can't infer current AndroidGradlePluginVersion: Is the Android plugin
// applied?". The Android plugin therefore has to be named here as well.
plugins {
    alias(libs.plugins.kotlinMultiplatform) apply false
    alias(libs.plugins.kotlinComposeCompiler) apply false
    alias(libs.plugins.kotlinAtomicfu) apply false
    alias(libs.plugins.composeMultiplatform) apply false
    alias(libs.plugins.androidApplication) apply false
    alias(libs.plugins.gobleyCargo) apply false
    alias(libs.plugins.gobleyUniffi) apply false
}
