package com.ninja.splitlauncher;

import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Moteur de lancement du Split.
 *
 * Cascade, par ordre de fiabilité, exécutée EN ARRIÈRE-PLAN (jamais sur le
 * thread principal) :
 *   1. ACCESSIBILITÉ : GLOBAL_ACTION_TOGGLE_SPLIT_SCREEN — fiable, sans root,
 *      avec VÉRIFICATION réelle de l'état du split (diviseur présent).
 *   2. SHELL : Shizuku/root "am start --windowingMode" — fiable si disponible.
 *   3. API publique : uniquement sur les OEM où elle est réellement honorée
 *      (Samsung/LG…). On NE PRÉTEND PLUS qu'elle marche ailleurs.
 *   4. MODE ASSISTÉ : sinon, on lance A et on guide l'utilisateur vers le
 *      split natif — au lieu d'afficher un faux « Split lancé ».
 *
 * Correctif v1.3 : GLOBAL_ACTION_TOGGLE_SPLIT_SCREEN est un BASCULEMENT. Si
 * l'utilisateur est déjà en split, on ne le bascule PAS (sinon on quitte le
 * split) : on ajoute simplement B à la moitié existante.
 *
 * Correctif v1.4 : la cascade est enveloppée dans un try/finally afin que la
 * garde anti-taps soit TOUJOURS libérée, même si une exception est levée dans
 * la plateforme (sinon la garde restait bloquée et tout renvoyait BUSY).
 *
 * La dépendance {@link Platform} est injectable, ce qui rend toute la cascade
 * testable sans appareil (voir SplitEngineTest).
 */
public class SplitEngine {

    /** Résultat d'une tentative de lancement. */
    public enum Outcome {
        SPLIT_ACCESSIBILITY, SPLIT_SHELL, SPLIT_API, ASSISTED, FAILED, BUSY
    }

    public interface Callback {
        void onResult(Outcome outcome, String message);
    }

    /** Abstrait les effets de bord (lancements, shell, détection) pour les tests. */
    public interface Platform {
        boolean isLaunchable(AppInfo app);
        boolean accessibilityAvailable();
        boolean isInSplitScreen();
        boolean performAccessibilitySplit(AppInfo a, AppInfo b, boolean landscape, boolean alreadyInSplit);
        ShellExecutor.Result shellSplit(AppInfo a, AppInfo b, boolean landscape);
        boolean launchViaApi(AppInfo a, AppInfo b, boolean landscape);
        void launchSequential(AppInfo a, AppInfo b);
        boolean programmaticReliable();
    }

    /**
     * Garde anti-taps répétés, PARTAGÉE par tous les points d'entrée
     * (écran principal, tuile, barre flottante, boot, raccourci). Tant qu'un
     * lancement est en cours, les suivants sont ignorés (Outcome.BUSY).
     */
    private static final AtomicBoolean LAUNCHING = new AtomicBoolean(false);

    /** Réinitialise la garde (utilisé par les tests). */
    public static void resetLaunchGuard() {
        LAUNCHING.set(false);
    }

    /** Un lancement est-il déjà en cours ? */
    public static boolean isLaunching() {
        return LAUNCHING.get();
    }

    private final Platform platform;
    private final Executor background;
    private final Executor main;

    public SplitEngine(Platform platform, Executor background, Executor main) {
        this.platform = platform;
        this.background = background;
        this.main = main;
    }

    /** Lance les deux applications en Split (asynchrone). */
    public void launchSplit(AppInfo a, AppInfo b, boolean landscape, Callback callback) {

        // --- Garde anti-taps répétés (atomique) ---
        if (!LAUNCHING.compareAndSet(false, true)) {
            main.execute(() -> {
                if (callback != null) callback.onResult(Outcome.BUSY, "Lancement déjà en cours");
            });
            return;
        }

        // --- Garde-fous (purs, immédiats) ---
        if (a == null || b == null) {
            finish(callback, Outcome.FAILED, "Deux applications requises");
            return;
        }
        if (a.isSame(b)) {
            finish(callback, Outcome.FAILED, "Les deux slots contiennent la même application");
            return;
        }
        if (!platform.isLaunchable(a)) {
            finish(callback, Outcome.FAILED, "Application A introuvable ou désinstallée");
            return;
        }
        if (!platform.isLaunchable(b)) {
            finish(callback, Outcome.FAILED, "Application B introuvable ou désinstallée");
            return;
        }

        // --- Cascade, hors thread principal ---
        // Correctif v1.4 : la cascade est enveloppée dans un try/finally afin que
        // la garde LAUNCHING soit TOUJOURS libérée, même si une exception est
        // levée dans la plateforme (ex : isInSplitScreen()). Sans cela, la garde
        // restait bloquée à true jusqu'à la mort du processus et TOUS les
        // lancements suivants renvoyaient BUSY.
        try {
            background.execute(() -> {
                Outcome outcome = Outcome.FAILED;
                String message = "Erreur inattendue";
                try {
                    boolean alreadyInSplit = platform.isInSplitScreen();

                    if (platform.accessibilityAvailable()
                            && platform.performAccessibilitySplit(a, b, landscape, alreadyInSplit)) {
                        outcome = Outcome.SPLIT_ACCESSIBILITY;
                        message = alreadyInSplit
                                ? "Split relancé (accessibilité) : " + a.label + "  |  " + b.label
                                : "Split lancé (accessibilité) : " + a.label + "  |  " + b.label;
                    } else {
                        ShellExecutor.Result shell = platform.shellSplit(a, b, landscape);
                        if (shell != null && shell.ok) {
                            outcome = Outcome.SPLIT_SHELL;
                            message = "Split lancé (shell) : " + a.label + "  |  " + b.label;
                            if (shell.output != null
                                    && shell.output.contains(ShellSplitter.RATIO_NOTE)) {
                                message += " — " + ShellSplitter.RATIO_NOTE;
                            }
                        } else if (platform.programmaticReliable()
                                && platform.launchViaApi(a, b, landscape)) {
                            outcome = Outcome.SPLIT_API;
                            message = "Split lancé (API OEM) : " + a.label + "  |  " + b.label;
                        } else {
                            // Honnête : pas de vrai split possible automatiquement ici.
                            platform.launchSequential(a, b);
                            outcome = Outcome.ASSISTED;
                            message = "Split automatique indisponible — mode assisté";
                        }
                    }
                } catch (Throwable t) {
                    // On ne prétend PAS avoir réussi : on remonte un échec explicite.
                    outcome = Outcome.FAILED;
                    message = "Erreur : " + (t.getMessage() == null
                            ? t.getClass().getSimpleName() : t.getMessage());
                } finally {
                    // Libère la garde dans TOUS les cas (succès, échec, exception).
                    finish(callback, outcome, message);
                }
            });
        } catch (Throwable rejected) {
            // L'exécuteur a refusé la tâche : on libère la garde pour ne pas la
            // laisser bloquée (sinon plus aucun lancement ne serait possible).
            finish(callback, Outcome.FAILED,
                    "Erreur d'exécution : " + rejected.getMessage());
        }
    }

    /**
     * Libère la garde puis remonte le résultat sur le thread principal.
     * La garde est libérée EN PREMIER : même si la remontée du résultat échoue,
     * un prochain lancement reste possible.
     */
    private void finish(Callback cb, Outcome outcome, String msg) {
        LAUNCHING.set(false);
        main.execute(() -> {
            if (cb != null) cb.onResult(outcome, msg);
        });
    }
}
