# Reflection is required for the legacy process API implemented by Shizuku Next 14.0.32.
-keepclassmembers class rikka.shizuku.Shizuku { private static rikka.shizuku.ShizukuRemoteProcess newProcess(java.lang.String[], java.lang.String[], java.lang.String); }
-keep class rikka.shizuku.ShizukuRemoteProcess { *; }

# Kadb selects Conscrypt and its exporter by reflection.
-keep class org.conscrypt.** { *; }
