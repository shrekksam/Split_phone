package com.ninja.splitlauncher;

import android.app.ActivityOptions;
import android.content.Context;
import android.content.Intent;
import android.graphics.Rect;
import android.os.Build;

/**
 * Implémentation Android réelle de {@link SplitEngine.Platform}.
 *
 * Toutes les méthodes bloquantes (attente, shell) sont appelées depuis le thread
 * d'arrière-plan du moteur — jamais depuis l'UI.
 *
 * Correctifs v1.3 :
 *   - VÉRIFICATION réelle du split : on n'affirme plus « Split lancé » sur la
 *     seule foi de {@code performGlobalAction()}. On attend (polling) la présence
 *     effective du diviseur via {@link SplitAccessibilityService#waitForSplit}.
 *   - GESTION « déjà en split » : GLOBAL_ACTION_TOGGLE_SPLIT_SCREEN est un
 *     BASCULEMENT. Si l'utilisateur est déjà en split, on ne bascule PAS (sinon
 *     on quitte le split) : on ajoute simplement B à la moitié existante.
 *   - RATIO : il n'est PAS applicable via l'accessibilité (les bounds hors mode
 *     freeform sont ignorés par AOSP). Il est appliqué uniquement sur le chemin
 *     shell ({@link ShellSplitter}).
 */
public class AndroidSplitPlatform implements SplitEngine.Platform {

    /** Délai maximal d'attente de l'apparition du diviseur après le basculement. */
    private static final long SPLIT_WAIT_MS = 2500;
    /** Délai maximal d'attente après le lancement de A (avant le basculement). */
    private static final long FOREGROUND_WAIT_MS = 1200;

    private final Context ctx;
    private final float ratio; // fraction de la première moitié (0.5 = 50/50, 0.7 = 70/30)

    public AndroidSplitPlatform(Context ctx, float ratio) {
        this.ctx = ctx.getApplicationContext();
        this.ratio = ratio;
    }

    @Override
    public boolean isLaunchable(AppInfo app) {
        return IntentLauncher.isLaunchable(ctx, app);
    }

    @Override
    public boolean accessibilityAvailable() {
        return SplitAccessibilityService.isEnabled(ctx);
    }

    @Override
    public boolean isInSplitScreen() {
        SplitAccessibilityService svc = SplitAccessibilityService.getInstance();
        return svc != null && svc.isInSplitScreen();
    }

    @Override
    public boolean performAccessibilitySplit(AppInfo a, AppInfo b, boolean landscape,
                                             boolean alreadyInSplit) {
        SplitAccessibilityService svc = SplitAccessibilityService.getInstance();
        if (svc == null) return false;
        try {
            if (alreadyInSplit) {
                // Déjà en split : NE PAS basculer (sinon on quitte le split).
                // On lance simplement B en adjacent pour qu'elle rejoigne l'autre moitié.
                launchAdjacent(b, landscape);
                return true;
            }

            // 1) Lancer A et attendre qu'elle soit au premier plan.
            ctx.startActivity(IntentLauncher.buildLaunchIntent(a));
            sleep(FOREGROUND_WAIT_MS);

            // 2) Basculer en écran partagé.
            if (!svc.toggleSplitScreen()) return false;

            // 3) VÉRIFIER que le split est réellement actif (diviseur présent).
            //    Remplace les délais fixes fragiles par une attente vérifiée.
            if (!svc.waitForSplit(SPLIT_WAIT_MS)) {
                // Le basculement n'a pas abouti : on ne prétend pas avoir réussi.
                return false;
            }

            // 4) Lancer B : elle rejoint l'autre moitié.
            launchAdjacent(b, landscape);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    /** Lance B en « adjacent » (rejoint l'autre moitié du split). */
    private void launchAdjacent(AppInfo b, boolean landscape) {
        Intent ib = IntentLauncher.buildLaunchIntent(b);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            ib.addFlags(Intent.FLAG_ACTIVITY_LAUNCH_ADJACENT);
            Rect[] halves = SplitController.computeHalves(ctx, landscape, ratio);
            ActivityOptions opts = ActivityOptions.makeBasic();
            opts.setLaunchBounds(halves[1]);
            ctx.startActivity(ib, opts.toBundle());
        } else {
            ctx.startActivity(ib);
        }
    }

    @Override
    public ShellExecutor.Result shellSplit(AppInfo a, AppInfo b, boolean landscape) {
        return ShellSplitter.launchSplitViaShell(ctx, a, b, ratio, landscape);
    }

    @Override
    public boolean launchViaApi(AppInfo a, AppInfo b, boolean landscape) {
        try {
            Rect[] halves = SplitController.computeHalves(ctx, landscape, ratio);

            Intent intentA = IntentLauncher.buildLaunchIntent(a);
            ActivityOptions optsA = ActivityOptions.makeBasic();
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                optsA.setLaunchBounds(halves[0]);
            }
            ctx.startActivity(intentA, optsA.toBundle());

            sleep(400); // petite pause pour laisser le conteneur se créer

            Intent intentB = IntentLauncher.buildLaunchIntent(b);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                intentB.addFlags(Intent.FLAG_ACTIVITY_LAUNCH_ADJACENT);
            }
            ActivityOptions optsB = ActivityOptions.makeBasic();
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                optsB.setLaunchBounds(halves[1]);
            }
            ctx.startActivity(intentB, optsB.toBundle());
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    @Override
    public void launchSequential(AppInfo a, AppInfo b) {
        try {
            ctx.startActivity(IntentLauncher.buildLaunchIntent(a));
            sleep(400);
            ctx.startActivity(IntentLauncher.buildLaunchIntent(b));
        } catch (Exception ignored) {}
    }

    @Override
    public boolean programmaticReliable() {
        return DeviceSupport.isProgrammaticSplitReliable();
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
