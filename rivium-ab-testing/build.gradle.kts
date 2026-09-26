plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
    id("com.vanniktech.maven.publish")
}

android {
    namespace = "co.rivium.abtesting"
    compileSdk = 34

    defaultConfig {
        minSdk = 21

        consumerProguardFiles("consumer-rules.pro")
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }

    kotlinOptions {
        jvmTarget = "11"
    }

    testOptions {
        // android.util.Log in unit tests: do nothing instead of throwing.
        unitTests.isReturnDefaultValues = true
    }
}

dependencies {
    // AndroidX
    implementation("androidx.core:core-ktx:1.12.0")
    implementation("androidx.lifecycle:lifecycle-process:2.7.0")

    // Coroutines
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")

    // HTTP & JSON
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("com.google.code.gson:gson:2.10.1")

    testImplementation("junit:junit:4.13.2")
    testImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.7.3")
    // The real org.json, instead of the empty stub unit tests get.
    testImplementation("org.json:json:20231013")
}

mavenPublishing {
    publishToMavenCentral(
        com.vanniktech.maven.publish.SonatypeHost.CENTRAL_PORTAL,
        automaticRelease = true
    )
    signAllPublications()

    coordinates("co.rivium", "rivium-ab-testing-android", "0.2.0")

    pom {
        name.set("Rivium A/B Testing Android SDK")
        description.set("A/B Testing and Feature Flags SDK for Android with offline-first sync")
        inceptionYear.set("2025")
        url.set("https://rivium.co")

        licenses {
            license {
                name.set("MIT License")
                url.set("https://opensource.org/licenses/MIT")
                distribution.set("repo")
            }
        }

        developers {
            developer {
                id.set("rivium")
                name.set("Rivium")
                email.set("founder@rivium.co")
                url.set("https://rivium.co")
            }
        }

        scm {
            url.set("https://github.com/Rivium-co/rivium-ab-testing-android-sdk")
            connection.set("scm:git:git://github.com/Rivium-co/rivium-ab-testing-android-sdk.git")
            developerConnection.set("scm:git:ssh://git@github.com/Rivium-co/rivium-ab-testing-android-sdk.git")
        }
    }
}
