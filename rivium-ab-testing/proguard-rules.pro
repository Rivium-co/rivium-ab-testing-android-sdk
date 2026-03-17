# RiviumAbTesting SDK ProGuard Rules

# Keep SDK public API
-keep class co.rivium.abtesting.RiviumAbTesting { *; }
-keep class co.rivium.abtesting.RiviumAbTestingConfig { *; }
-keep class co.rivium.abtesting.RiviumAbTestingCallback { *; }
-keep class co.rivium.abtesting.RiviumAbTestingError { *; }
-keep class co.rivium.abtesting.RiviumAbTestingError$* { *; }

# Keep models
-keep class co.rivium.abtesting.models.** { *; }

# Gson
-keepattributes Signature
-keepattributes *Annotation*
-keep class com.google.gson.** { *; }
-keep class * implements com.google.gson.TypeAdapterFactory
-keep class * implements com.google.gson.JsonSerializer
-keep class * implements com.google.gson.JsonDeserializer

# OkHttp
-dontwarn okhttp3.**
-dontwarn okio.**
-keep class okhttp3.** { *; }
-keep interface okhttp3.** { *; }

# Coroutines
-keepnames class kotlinx.coroutines.internal.MainDispatcherFactory {}
-keepnames class kotlinx.coroutines.CoroutineExceptionHandler {}
-keepclassmembernames class kotlinx.** { volatile <fields>; }
