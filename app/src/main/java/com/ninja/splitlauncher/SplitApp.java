package com.ninja.splitlauncher;

import android.app.Application;

/**
 * Classe Application : point d'initialisation global.
 *
 * On y initialise le bridge Shizuku (qui a besoin d'un Context pour construire
 * le {@code ComponentName} du UserService) une seule fois, avant tout usage.
 */
public class SplitApp extends Application {

    @Override
    public void onCreate() {
        super.onCreate();
        ShizukuBridge.init(this);
    }
}
