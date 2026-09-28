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
 *     seule foi de {@code performGlobalAction()}. On attend la confirmation
 *     effective de l'état via {@link SplitAccessibilityService#waitForSplit}.
 *   - GESTION « déjà en split » : GLOBAL_ACTION_TOGGLE_SPLIT_SCREEN est un
 *     BASCULEMENT. Si l'utilisateur est déjà en split, on ne bascule PAS (sinon
 *     on quitte le split) : on ajoute simplement B à la moitié existante.
 *   - RATIO : il n'est PAS applicable via l'accessibilité (les bounds hors mode
 *     freeform sont ignorés par AOSP). Il est appliqué uniquement sur le chemin
 *     shell ({@link ShellSplitter}).
 *
 * Correctifs v1.5 :
 *   - CONNEXION : le service est lié de façon asynchrone. On l'attend
 *     brièvement ({@link SplitAccessibilityService#awaitConnection}) au lieu
 *     d'abandonner l'accessibilité juste après son activation.
 *   - RECONSTRUCTION : si l'ajout de B referme le split (OEM capricieux), on
 *     repart d'une base PROPRE — sortie confirmée via
 *     {@link SplitAccessibilityService#waitForSplitExit}, puis A, bascule
 *     vérifiée, B.
 */
public class AndroidSplitPlatform implements SplitEngine.Platform {

    /** Délai maximal d'attente de la CONFIRMATION du split après basculement. */
    private static final long SPLIT_WAIT_MS = 2500;
    /** Délai maximal d'attente après le lancement de A (avant le basculement). */
    private static final long FOREGROUND_WAIT_MS = 1200;
    /** Délai maximal d'attente de la connexion du service d'accessibilité. */
    private static final long CONNECT_WAIT_MS = 1200;
    /** Délai maximal d'attente de la SORTIE effective d'un split résiduel. */
    private static final long EXIT_WAIT_MS = 1200;
    /** Délai de contrôle : le split a-t-il survécu à l'ajout de B ? */
    private static final long REJOIN_WAIT_MS = 800;

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
        // Le système lie le service de façon ASYNCHRONE : juste après son
        // activation, il est « activé » sans être encore connecté.
        SplitAccessibilityService svc =
                SplitAccessibilityService.awaitConnection(ctx, CONNECT_WAIT_MS);
        if (svc == null) return false;
        try {
            if (alreadyInSplit || svc.isInSplitScreen()) {
                // Déjà en split : NE PAS basculer (sinon on quitte le split).
                // On lance simplement B en adjacent pour qu'elle rejoigne l'autre moitié.
                launchAdjacent(b, landscape);

                // CONTRÔLE : sur certains OEM, l'ajout referme le split. On ne
                // prétend pas avoir réussi sans l'avoir vérifié.
                if (svc.waitForSplit(REJOIN_WAIT_MS)) return true;
            }
            return buildSplitFromScratch(svc, a, b, landscape);
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * Construit un split complet, chaque étape étant CONFIRMÉE :
     * sortie d'un éventuel split résiduel → A au premier plan → bascule
     * vérifiée → B en adjacent.
     */
    private boolean buildSplitFromScratch(SplitAccessibilityService svc,
                                          AppInfo a, AppInfo b, boolean landscape) {
        // Vérification IMPOSSIBLE (fenêtres illisibles) : on ne bascule PAS à
        // l'aveugle. On rend la main à la cascade (shell, puis mode assisté)
        // plutôt que de perturber l'état courant sans pouvoir rien confirmer.
        if (!svc.canVerifySplit()) return false;

        // 0) Base PROPRE. Sans sortie CONFIRMÉE (waitForSplitExit), la bascule
        //    de l'étape 2 retomberait sur un split encore en cours de fermeture
        //    et le rouvrirait avec les mauvaises applications.
        if (!svc.exitSplitScreen(EXIT_WAIT_MS)) return false;

        // 1) Lancer A et attendre qu'elle soit au premier plan.
        ctx.startActivity(IntentLauncher.buildLaunchIntent(a));
        sleep(FOREGROUND_WAIT_MS);

        // 2+3) Basculer en écran partagé PUIS VÉRIFIER que le split est
        //      réellement actif (diviseur, ou deux fenêtres d'app dans des
        //      régions distinctes). Remplace les délais fixes fragiles.
        if (!svc.enterSplitScreen(SPLIT_WAIT_MS)) return false;

        // 4) Lancer B : elle rejoint l'autre moitié.
        launchAdjacent(b, landscape);
        return true;
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
