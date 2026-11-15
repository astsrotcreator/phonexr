plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}
android {
    namespace = "dev.chessar.liveprobe"
    compileSdk = 35
    defaultConfig {
        applicationId = "dev.chessar.liveprobe"
        minSdk = 26
        targetSdk = 34
        versionCode = 3
        versionName = "0.3"
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }
    kotlinOptions { jvmTarget = "1.8" }
    packaging {
        resources.excludes.add("META-INF/INDEX.LIST")
        resources.merges.add("META-INF/io.netty.versions.properties")
        jniLibs.pickFirsts.add("**/libc++_shared.so")
    }
}
dependencies {
    val v = "3.0.2.8"
    implementation("com.topstep.wearkit:sdk-core:$v")
    implementation("com.topstep.wearkit:sdk-abmate-adapter:$v")
    implementation("com.polidea.rxandroidble3:rxandroidble:1.17.2")
    implementation("io.reactivex.rxjava3:rxjava:3.1.5")
    implementation("io.reactivex.rxjava3:rxandroid:3.0.2")
    implementation("com.jakewharton.timber:timber:5.0.1")
    implementation("com.google.code.gson:gson:2.13.2")
    implementation("org.nanohttpd:nanohttpd:2.3.1")
    implementation("androidx.lifecycle:lifecycle-process:2.5.1")
    implementation("androidx.appcompat:appcompat:1.6.1")
}
