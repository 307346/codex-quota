# OkHttp
-dontwarn okhttp3.**
-dontwarn okio.**
-dontwarn org.conscrypt.**

# WorkManager instantiates workers reflectively by class name.
-keep class * extends androidx.work.ListenableWorker {
    <init>(android.content.Context, androidx.work.WorkerParameters);
}

# Components are referenced from the manifest by name.
-keep class com.lcs.codexquota.MainActivity { *; }
-keep class com.lcs.codexquota.CodexQuotaApp { *; }
-keep class com.lcs.codexquota.work.PrimeService { *; }
-keep class com.lcs.codexquota.work.PrimeReceiver { *; }
-keep class com.lcs.codexquota.work.BootReceiver { *; }
