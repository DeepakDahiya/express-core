# Keep all Sentry classes and members
-keep class io.sentry.** { *; }
-dontwarn io.sentry.**

# Keep Android integrations
-keep class io.sentry.android.** { *; }
-dontwarn io.sentry.android.**

# Keep JetBrains annotations (optional)
-dontwarn org.jetbrains.annotations.**

# Keep constructors for reflection
-keepclassmembers class * {
    public <init>(...);
}