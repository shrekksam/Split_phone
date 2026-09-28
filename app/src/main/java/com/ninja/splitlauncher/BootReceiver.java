package com.ninja.splitlauncher;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Build;

/**
 * Reçoit le signal de démarrage du système.
 *
 * Si le « démarrage automatique » est activé :
 *   - démarre la barre flottante (service de premier plan) si autorisé ;
 *   - tente le lancement du Split enregistré UNIQUEMENT si le contexte le
 *     permet réellement, avec un REPLI par notification « appuyer pour lancer ».
 *
 * IMPORTANT : sur Android 10+ (et strictement sur 14/15), le lancement d'activité
 * depuis un BroadcastReceiver en arrière-plan est bloqué par défaut. On ne
 * suppose donc PAS que le split au boot fonctionne : on tente (si l'overlay est
 * accordé, ce qui est une exemption) et, en cas d'échec, on publie une
 * notification de repli que l'utilisateur peut toucher.
 */
public class BootReceiver extends BroadcastReceiver {

    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null || intent.getAction() == null) return;

        String action = intent.getAction();
        if (!Intent.ACTION_BOOT_COMPLETED.equals(action)
                && !"android.intent.action.QUICKBOOT_POWERON".equals(action)
                && !"com.htc.intent.action.QUICKBOOT_POWERON".equals(action)) {
            return;
        }

        final Context app = context.getApplicationContext();
        ProfileManager profile = new ProfileManager(app);
        if (!profile.isAutoStart()) return;

        boolean overlayGranted = WindowManagerHelper.canDrawOverlays(app);

        // 1) Démarre la barre flottante si activée ET overlay accordé.
        if (profile.isFloatingBar() && overlayGranted) {
            Intent service = new Intent(app, FloatingBarService.class);
            service.setAction(FloatingBarService.ACTION_SHOW);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                app.startForegroundService(service);
            } else {
                app.startService(service);
            }
        }

        AppInfo a = profile.getAppA(app);
        AppInfo b = profile.getAppB(app);
        if (a == null || b == null) return;
        if (!IntentLauncher.isLaunchable(app, a)
                || !IntentLauncher.isLaunchable(app, b)) {
            // Une des applis a disparu : on invalide le profil pour éviter des
            // tentatives répétées au prochain boot.
            profile.setAutoStart(false);
            return;
        }

        // 2) Sans overlay (exemption de lancement en arrière-plan), le split au
        //    boot est très probablement bloqué : on propose directement la
        //    notification de repli.
        if (!overlayGranted) {
            NotificationHelper.postBootLaunch(app);
            return;
        }

        // 3) Tentative best-effort ; en cas d'échec, repli par notification.
        try {
            SplitController.launchSplit(app, a, b, profile.isLandscape(),
                    profile.getRatio(), (outcome, msg) -> {
                        boolean realSplit = outcome == SplitEngine.Outcome.SPLIT_ACCESSIBILITY
                                || outcome == SplitEngine.Outcome.SPLIT_SHELL
                                || outcome == SplitEngine.Outcome.SPLIT_API;
                        if (!realSplit) {
                            NotificationHelper.postBootLaunch(app);
                        }
                    });
        } catch (Exception ignored) {
            NotificationHelper.postBootLaunch(app);
        }
    }
}
