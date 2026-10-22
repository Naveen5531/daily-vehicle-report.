// Top-level build file where you can add configuration options common to all sub-projects/modules.
plugins {
    id("com.android.application") version "8.11.1" apply false
    id("org.jetbrains.kotlin.android") version "2.0.21" apply false
    // Firebase (sign-in and each account's cloud copy). Applied in app/ only when google-services.json is present.
    id("com.google.gms.google-services") version "4.4.2" apply false
}
