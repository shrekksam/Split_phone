package com.ninja.splitlauncher;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.widget.Toast;

/**
 * Activité transparente (sans UI) servant de cible au raccourci d'écran
 * d'accueil, à la tuile et à la notification de repli. Elle lance le profil
 * actif (ou un profil ciblé) puis se termine.
 *
 * Correctif v1.3 : elle RESTE AU PREMIER PLAN le temps du lancement du split.
 * Sur Android 14/15, un startActivity depuis un contexte d'arrière-plan est
 * bloqué ; en gardant une activité au premier plan jusqu'à la fin de la cascade,
 * le lancement est autorisé. Un filet de sécurité (8 s) garantit qu'on ne reste
 * jamais bloqué au premier plan.
 */
public class LaunchSplitActivity extends Activity {

    private static final long SAFETY_TIMEOUT_MS = 8000;

    private boolean finished = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        ProfileManager profile = new ProfileManager(this);

        // Raccourci dynamique par profil : on active le profil demandé.
        Intent in = getIntent();
        String profileId = in != null ? in.getStringExtra(ShortcutHelper.EXTRA_PROFILE_ID) : null;
        if (profileId != null) {
            profile.setActiveProfile(profileId);
        }

        AppInfo a = profile.getAppA(this);
        AppInfo b = profile.getAppB(this);

        if (a == null || b == null) {
            // Aucun profil complet : on ouvre l'écran principal.
            Intent open = new Intent(this, MainActivity.class);
            open.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(open);
            finishSafely();
            return;
        }

        // On lance le split en RESTANT au premier plan (voir le commentaire de classe).
        SplitController.launchSplit(this, a, b, profile.isLandscape(),
                profile.getRatio(), (outcome, msg) -> {
                    if (outcome == SplitEngine.Outcome.ASSISTED
                            || outcome == SplitEngine.Outcome.FAILED
                            || outcome == SplitEngine.Outcome.BUSY) {
                        Toast.makeText(this, msg, Toast.LENGTH_LONG).show();
                    }
                    finishSafely();
                });

        // Filet de sécurité : on ne reste jamais bloqué au premier plan.
        new Handler(Looper.getMainLooper()).postDelayed(this::finishSafely, SAFETY_TIMEOUT_MS);
    }

    private void finishSafely() {
        if (!finished) {
            finished = true;
            finish();
        }
    }
}
