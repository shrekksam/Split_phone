# ---------------------------------------------------------------------------
# Règles R8 / ProGuard — SplitLauncher
# ---------------------------------------------------------------------------

# Modèle sérialisé en JSON (réflexion sur les champs) : à conserver.
-keep class com.ninja.splitlauncher.AppInfo { *; }
-keep class com.ninja.splitlauncher.SplitProfile { *; }

# Services & composants référencés par le système.
-keep class com.ninja.splitlauncher.SplitAccessibilityService { *; }
-keep class com.ninja.splitlauncher.FloatingBarService { *; }
-keep class com.ninja.splitlauncher.BootReceiver { *; }
-keep class com.ninja.splitlauncher.SplitTileService { *; }

# Shizuku (accès direct + UserService).
-keep class rikka.shizuku.** { *; }
-dontwarn rikka.shizuku.**

# --- Shizuku UserService -----------------------------------------------------
# Le UserService est instancié par le serveur Shizuku (par réflexion) et son
# constructeur avec Context doit être conservé.
-keep class com.ninja.splitlauncher.ShizukuUserService { *; }
-keep class com.ninja.splitlauncher.IUserService { *; }
-keep class com.ninja.splitlauncher.IUserService$Stub { *; }
-keepclassmembers class com.ninja.splitlauncher.ShizukuUserService {
    <init>(...);
}

# Conserver les noms des classes ViewModel instanciées par réflexion.
-keepclassmembers class * extends androidx.lifecycle.ViewModel {
    <init>(...);
}
