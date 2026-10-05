plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.compose.compiler)
}

android {
    namespace = "io.github.nicolasraoul.moag"
    compileSdk = 35

    defaultConfig {
        applicationId = "io.github.nicolasraoul.moag"
        minSdk = 31
        targetSdk = 35
        versionCode = 1
        versionName = "1.0"
        
        ndk {
            abiFilters.addAll(listOf("armeabi-v7a", "arm64-v8a", "x86", "x86_64"))
        }
    }
    
    buildFeatures {
        compose = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    
    kotlinOptions {
        jvmTarget = "17"
    }

    packaging {
        resources {
            excludes += "META-INF/NOTICE.md"
            excludes += "META-INF/LICENSE.md"
        }
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.material3)
    implementation(libs.coroutines.android)
    
    // Networking and JSON
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("com.google.code.gson:gson:2.10.1")
    
    // Security for API Key
    implementation(libs.androidx.security.crypto)
    
    // Play Services Provider (fixes Conscrypt bugs on Android 13 devices)
    implementation("com.google.android.gms:play-services-base:18.2.0")
    
    // IMAP Email Client
    implementation("com.sun.mail:android-mail:1.6.7")
    implementation("com.sun.mail:android-activation:1.6.7")
    
    // For local LLM fallback
    implementation("com.google.ai.edge.aicore:aicore:0.0.1-exp02")


}
