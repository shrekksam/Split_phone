package com.ninja.splitlauncher;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;

/**
 * Construit et lance les Intents d'ouverture des applications.
 * Gère aussi la vérification d'existence d'une application.
 */
public final class IntentLauncher {

    private IntentLauncher() {}

    /** Intent standard de lancement d'une activité "launcher". */
    public static Intent buildLaunchIntent(String pkg, String activity) {
        Intent i = new Intent(Intent.ACTION_MAIN);
        i.addCategory(Intent.CATEGORY_LAUNCHER);
        i.setComponent(new ComponentName(pkg, activity));
        i.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
        return i;
    }

    public static Intent buildLaunchIntent(AppInfo app) {
        return buildLaunchIntent(app.packageName, app.activityName);
    }

    /** Vérifie que l'application est toujours installée et lançable. */
    public static boolean isLaunchable(Context ctx, AppInfo app) {
        if (app == null || !app.isValid()) return false;
        PackageManager pm = ctx.getPackageManager();
        Intent i = new Intent(Intent.ACTION_MAIN);
        i.addCategory(Intent.CATEGORY_LAUNCHER);
        i.setComponent(app.toComponentName());
        return pm.resolveActivity(i, 0) != null;
    }

    /** Lance une application de façon simple (sans Split). */
    public static void launch(Context ctx, AppInfo app) {
        try {
            ctx.startActivity(buildLaunchIntent(app));
        } catch (Exception ignored) {
        }
    }
}
