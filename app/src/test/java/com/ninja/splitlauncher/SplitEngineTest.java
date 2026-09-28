package com.ninja.splitlauncher;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Before;
import org.junit.Test;

import java.util.ArrayDeque;
import java.util.concurrent.Executor;

/**
 * Tests de la CASCADE de lancement du Split (voir {@link SplitEngine}).
 *
 * On injecte une {@link SplitEngine.Platform} factice (fake) et un exécuteur
 * SYNCHRONE : la cascade est ainsi entièrement déterministe et testable sans
 * appareil. On vérifie l'{@link SplitEngine.Outcome} produit et les effets de
 * bord réellement déclenchés (lancement séquentiel, appel shell, etc.).
 *
 * Correctif v1.3 : ajout des cas « déjà en split » (on ne bascule PAS) et
 * « double tap » (la garde anti-taps renvoie BUSY).
 */
public class SplitEngineTest {

    /** Exécuteur direct : exécute la tâche immédiatement, sur le thread courant. */
    private static final Executor DIRECT = Runnable::run;

    @Before
    public void resetGuard() {
        // La garde anti-taps est STATIQUE (partagée) : on la réinitialise entre
        // chaque test pour garantir l'isolation.
        SplitEngine.resetLaunchGuard();
    }

    private static AppInfo app(String label, String pkg) {
        return new AppInfo(label, pkg, pkg + ".Main", null);
    }

    private final AppInfo a = app("YouTube", "com.google.android.youtube");
    private final AppInfo b = app("Messages", "com.google.android.apps.messaging");

    /** Récupère le résultat produit par la cascade (synchrone ici). */
    private SplitEngine.Outcome run(FakePlatform platform, AppInfo x, AppInfo y) {
        SplitEngine.resetLaunchGuard();
        SplitEngine engine = new SplitEngine(platform, DIRECT, DIRECT);
        final SplitEngine.Outcome[] captured = new SplitEngine.Outcome[1];
        engine.launchSplit(x, y, true, (outcome, msg) -> captured[0] = outcome);
        return captured[0];
    }

    // ------------------------------------------------------------ CASCADE

    @Test
    public void accessibilityAvailable_takesAccessibilityPath() {
        FakePlatform p = new FakePlatform();
        p.accessibilityAvailable = true;
        p.accessibilitySucceeds = true;

        SplitEngine.Outcome o = run(p, a, b);

        assertEquals(SplitEngine.Outcome.SPLIT_ACCESSIBILITY, o);
        assertTrue(p.accessibilityPerformed);
        assertFalse(p.shellCalled);           // on n'essaie pas le shell ensuite
        assertFalse(p.apiCalled);
        assertFalse(p.sequentialCalled);
    }

    @Test
    public void accessibilityFails_fallsBackToShell() {
        FakePlatform p = new FakePlatform();
        p.accessibilityAvailable = true;
        p.accessibilitySucceeds = false;      // action globale refusée
        p.shellOk = true;

        SplitEngine.Outcome o = run(p, a, b);

        assertEquals(SplitEngine.Outcome.SPLIT_SHELL, o);
        assertTrue(p.shellCalled);
        assertFalse(p.apiCalled);
    }

    @Test
    public void noAccessibility_shellSucceeds_takesShellPath() {
        FakePlatform p = new FakePlatform();
        p.accessibilityAvailable = false;
        p.shellOk = true;

        SplitEngine.Outcome o = run(p, a, b);

        assertEquals(SplitEngine.Outcome.SPLIT_SHELL, o);
        assertTrue(p.shellCalled);
        assertFalse(p.apiCalled);
    }

    @Test
    public void shellFails_reliableOem_usesApi() {
        FakePlatform p = new FakePlatform();
        p.accessibilityAvailable = false;
        p.shellOk = false;
        p.programmaticReliable = true;        // ex : Samsung
        p.apiSucceeds = true;

        SplitEngine.Outcome o = run(p, a, b);

        assertEquals(SplitEngine.Outcome.SPLIT_API, o);
        assertTrue(p.apiCalled);
        assertFalse(p.sequentialCalled);
    }

    /**
     * Cœur du correctif : sur un OEM NON fiable, on NE prétend PAS que le split
     * a marché. On bascule honnêtement en mode ASSISTÉ (lancement séquentiel).
     */
    @Test
    public void shellFails_unreliableOem_goesAssisted_neverClaimsApiSuccess() {
        FakePlatform p = new FakePlatform();
        p.accessibilityAvailable = false;
        p.shellOk = false;
        p.programmaticReliable = false;       // OEM non fiable (AOSP…)

        SplitEngine.Outcome o = run(p, a, b);

        assertEquals(SplitEngine.Outcome.ASSISTED, o);
        assertFalse(p.apiCalled);             // l'API n'est même pas tentée
        assertTrue(p.sequentialCalled);       // A puis B lancés
    }

    @Test
    public void shellFails_reliableOem_butApiThrows_fallsToAssisted() {
        FakePlatform p = new FakePlatform();
        p.accessibilityAvailable = false;
        p.shellOk = false;
        p.programmaticReliable = true;
        p.apiSucceeds = false;                // l'API échoue malgré tout

        SplitEngine.Outcome o = run(p, a, b);

        assertEquals(SplitEngine.Outcome.ASSISTED, o);
        assertTrue(p.sequentialCalled);
    }

    // ------------------------------------------------- « DÉJÀ EN SPLIT »

    /**
     * Si l'utilisateur est DÉJÀ en split, GLOBAL_ACTION_TOGGLE_SPLIT_SCREEN le
     * QUITTERAIT. Le moteur doit donc transmettre l'état « déjà en split » à la
     * plateforme, qui se contente d'ajouter B (sans basculer).
     */
    @Test
    public void alreadyInSplit_doesNotToggle_addsApp() {
        FakePlatform p = new FakePlatform();
        p.accessibilityAvailable = true;
        p.accessibilitySucceeds = true;
        p.inSplitScreen = true;

        SplitEngine.Outcome o = run(p, a, b);

        assertEquals(SplitEngine.Outcome.SPLIT_ACCESSIBILITY, o);
        assertTrue(p.accessibilityPerformed);
        assertTrue(p.alreadyInSplitArg);      // l'état a bien été transmis
    }

    /** Hors split : l'état transmis doit être false (on bascule normalement). */
    @Test
    public void notInSplit_togglesNormally() {
        FakePlatform p = new FakePlatform();
        p.accessibilityAvailable = true;
        p.accessibilitySucceeds = true;
        p.inSplitScreen = false;

        SplitEngine.Outcome o = run(p, a, b);

        assertEquals(SplitEngine.Outcome.SPLIT_ACCESSIBILITY, o);
        assertFalse(p.alreadyInSplitArg);
    }

    // ----------------------------------------------------- GARDE ANTI-TAPS

    /**
     * Double tap : tant qu'un lancement est en cours, le second appel est ignoré
     * (Outcome.BUSY) au lieu d'empiler un second lancement dans l'exécuteur.
     */
    @Test
    public void doubleTap_secondIsBusy() {
        SplitEngine.resetLaunchGuard();
        FakePlatform p = new FakePlatform();
        p.accessibilityAvailable = true;
        p.accessibilitySucceeds = true;

        // Exécuteur d'arrière-plan DIFFÉRÉ : la 1re tâche reste « en cours ».
        ArrayDeque<Runnable> queue = new ArrayDeque<>();
        Executor deferred = queue::add;

        SplitEngine engine = new SplitEngine(p, deferred, DIRECT);
        final SplitEngine.Outcome[] first = new SplitEngine.Outcome[1];
        final SplitEngine.Outcome[] second = new SplitEngine.Outcome[1];

        engine.launchSplit(a, b, true, (o, m) -> first[0] = o);
        assertTrue(SplitEngine.isLaunching());          // 1re en cours

        engine.launchSplit(a, b, true, (o, m) -> second[0] = o);
        assertEquals(SplitEngine.Outcome.BUSY, second[0]);
        assertNull(first[0]);                           // la 1re n'est pas terminée

        // On draine la file : la 1re se termine et libère la garde.
        while (!queue.isEmpty()) queue.poll().run();
        assertEquals(SplitEngine.Outcome.SPLIT_ACCESSIBILITY, first[0]);
        assertFalse(SplitEngine.isLaunching());
    }

    // ------------------------------------------------------------ GARDE-FOUS

    @Test
    public void nullApps_fail() {
        FakePlatform p = new FakePlatform();
        assertEquals(SplitEngine.Outcome.FAILED, run(p, null, b));
        assertEquals(SplitEngine.Outcome.FAILED, run(p, a, null));
        assertFalse(p.shellCalled);
    }

    @Test
    public void sameApp_fails() {
        FakePlatform p = new FakePlatform();
        SplitEngine.Outcome o = run(p, a, app("YouTube 2", "com.google.android.youtube"));
        assertEquals(SplitEngine.Outcome.FAILED, o);
        assertFalse(p.accessibilityPerformed);
    }

    @Test
    public void uninstalledApp_fails() {
        FakePlatform p = new FakePlatform();
        p.launchable = false;
        assertEquals(SplitEngine.Outcome.FAILED, run(p, a, b));
        assertFalse(p.shellCalled);
    }

    // ------------------------------------------------------- SHELL DÉTECTION

    @Test
    public void shellResult_looksLikeError_detectsAmStartFailures() {
        // « am start » renvoie souvent un code 0 avec un message d'erreur.
        assertTrue(ShellExecutor.looksLikeError("Error: Activity not started"));
        // Correctif v1.3 : « Exception » seul n'est PLUS un faux positif.
        assertFalse(ShellExecutor.looksLikeError("java.lang.Exception: boom"));
        assertTrue(ShellExecutor.looksLikeError("Permission denied"));
        assertTrue(ShellExecutor.looksLikeError("Unable to find component"));
        assertFalse(ShellExecutor.looksLikeError("Starting: Intent { ... }"));
        assertFalse(ShellExecutor.looksLikeError(""));
    }

    // ------------------------------------------------------------ FAKE

    /** Implémentation factice et instrumentée de la plateforme. */
    private static final class FakePlatform implements SplitEngine.Platform {
        boolean launchable = true;
        boolean accessibilityAvailable = false;
        boolean accessibilitySucceeds = true;
        boolean inSplitScreen = false;
        boolean shellOk = false;
        boolean programmaticReliable = false;
        boolean apiSucceeds = true;

        boolean accessibilityPerformed = false;
        boolean alreadyInSplitArg = false;
        boolean shellCalled = false;
        boolean apiCalled = false;
        boolean sequentialCalled = false;

        @Override
        public boolean isLaunchable(AppInfo app) {
            return launchable;
        }

        @Override
        public boolean accessibilityAvailable() {
            return accessibilityAvailable;
        }

        @Override
        public boolean isInSplitScreen() {
            return inSplitScreen;
        }

        @Override
        public boolean performAccessibilitySplit(AppInfo a, AppInfo b, boolean landscape,
                                                 boolean alreadyInSplit) {
            accessibilityPerformed = true;
            alreadyInSplitArg = alreadyInSplit;
            return accessibilitySucceeds;
        }

        @Override
        public ShellExecutor.Result shellSplit(AppInfo a, AppInfo b, boolean landscape) {
            shellCalled = true;
            return new ShellExecutor.Result(shellOk, shellOk ? "ok" : "Error: failed", shellOk ? 0 : 1);
        }

        @Override
        public boolean launchViaApi(AppInfo a, AppInfo b, boolean landscape) {
            apiCalled = true;
            return apiSucceeds;
        }

        @Override
        public void launchSequential(AppInfo a, AppInfo b) {
            sequentialCalled = true;
        }

        @Override
        public boolean programmaticReliable() {
            return programmaticReliable;
        }
    }
}
