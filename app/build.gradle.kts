plugins { id("com.android.application"); id("org.jetbrains.kotlin.android") }
android {
    namespace = "ru.smartbasket.app"
    compileSdk = 34
    defaultConfig.testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    defaultConfig { applicationId = "ru.smartbasket.app"; minSdk = 26; targetSdk = 34; versionCode = 26; versionName = "0.12.3" }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    kotlinOptions { jvmTarget = "17" }
}

dependencies { testImplementation("junit:junit:4.13.2"); testImplementation("org.json:json:20240303") }

dependencies {
    androidTestImplementation("androidx.test.ext:junit:1.1.5")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.5.1")
}

dependencies { implementation("androidx.activity:activity-ktx:1.8.2"); implementation("androidx.health.connect:connect-client:1.1.0-alpha07"); implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3") }

dependencies { implementation("com.squareup.okhttp3:okhttp:4.12.0"); implementation("com.squareup.okhttp3:okhttp-dnsoverhttps:4.12.0") }
