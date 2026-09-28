package com.ninja.splitlauncher;

import android.content.Context;
import android.graphics.Rect;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Lancement d'un VRAI split multi-fenêtres via "am start --windowingMode",
 * qui est le seul mécanisme réellement fiable pour imposer le multi-fenêtres
 * AOSP (le mode SPLIT_PRIMARY / SPLIT_SECONDARY).
 *
 * Canal privilégié, par ordre de préférence :
 *   1. SHIZUKU (uid shell, SANS root) — méthode recommandée (UserService).
 *   2. ROOT ("su -c") — repli UNIQUEMENT si l'utilisateur a activé le mode root.
 *
 * Correctifs v1.3 :
 *   - le chemin shell est GARDÉ par {@link DeviceSupport#canUseShellSplit} :
 *     plus de sondage "su" à chaque lancement (fin de l'invite Magisk bloquante) ;
 *   - le mode root est OPT-IN (préférence "mode root", désactivée par défaut) ;
 *   - le ratio est appliqué via "am task resize" quand un shell est disponible.
 *
 * Correctifs v1.4 :
 *   - le COMPOSANT est protégé par des quotes simples dans "am start -n
 *     'pkg/activity'" : un nom contenant « $ » (activités internes ou alias,
 *     courants) n'est plus expansé en chaîne vide par sh ;
 *   - "dumpsys activity activities" est FILTRÉ côté shell (grep) : on évite de
 *     rapatrier une sortie de plusieurs centaines de Ko via Binder (limite ~1 Mo) ;
 *   - l'échec de l'application du ratio n'est plus SILENCIEUX : il est signalé
 *     dans le résultat (RATIO_NOTE) et remonté à l'utilisateur ;
 *   - le cache shell POSITIF est invalidé si une commande échoue (ex : Shizuku
 *     arrêté en cours de session), pour forcer une nouvelle détection.
 */
public final class ShellSplitter {

    private ShellSplitter() {}

    // WindowManager.LayoutParams windowing modes (API interne mais stable).
    private static final int WINDOWING_MODE_SPLIT_PRIMARY = 3;
    private static final int WINDOWING_MODE_SPLIT_SECONDARY = 4;

    private static final Pattern TASK_ID = Pattern.compile("Task\\{[^}]*#(\\d+)");

    /** Note remontée lorsque le ratio demandé n'a pas pu être appliqué. */
    public static final String RATIO_NOTE = "ratio non appliqué (diviseur inchangé)";

    /** Filtre shell pour limiter la taille de "dumpsys" (limite Binder ~1 Mo). */
    private static final String DUMPSYS_FILTER =
            "dumpsys activity activities | grep -e Task -e realActivity";

    // ------------------------------------------------------------------ DÉTECTION

    /**
     * Un accès shell (Shizuku ou root) est-il disponible ?
     * On ne sonde "su" QUE si l'utilisateur a explicitement activé le mode root.
     */
    public static boolean isShellAvailable(Context ctx) {
        if (ShizukuBridge.isAvailable()) return true;
        if (ctx != null && new ProfileManager(ctx).isUseRoot()) {
            return hasSuBinary() && isRootGranted();
        }
        return false;
    }

    /** Un binaire "su" est-il présent ? (ne déclenche AUCUNE invite). */
    static boolean hasSuBinary() {
        ShellExecutor.Result r = ShellExecutor.run("which su");
        return r.ok && r.output != null && r.output.trim().length() > 0
                && !r.output.contains("not found");
    }

    /** Root effectivement accordé ? (peut afficher une invite — mode root opt-in). */
    static boolean isRootGranted() {
        ShellExecutor.Result r = ShellExecutor.run("su -c id");
        return r.ok && r.output != null && r.output.contains("uid=0");
    }

    // ------------------------------------------------------------------ LANCEMENT

    /**
     * Lance A en primaire et B en secondaire, puis applique le ratio (best-effort).
     * @return ok=true uniquement si les deux lancements ont réussi.
     */
    public static ShellExecutor.Result launchSplitViaShell(Context ctx, AppInfo a, AppInfo b,
                                                           float ratio, boolean landscape) {
        // GARDE-FOU : on ne tente RIEN si aucun shell n'est disponible.
        if (!DeviceSupport.canUseShellSplit(ctx)) {
            return new ShellExecutor.Result(false, "shell indisponible", -1);
        }

        boolean useShizuku = ShizukuBridge.isAvailable();

        ShellExecutor.Result ra = exec(useShizuku, buildAmStart(a, WINDOWING_MODE_SPLIT_PRIMARY));
        if (!ra.ok) {
            invalidateIfShizukuDead(useShizuku);
            return ra;
        }

        // Laisse le temps au gestionnaire de fenêtres de créer le conteneur
        // primaire avant d'y accoler le secondaire (évite la course).
        sleep(700);

        ShellExecutor.Result rb = exec(useShizuku, buildAmStart(b, WINDOWING_MODE_SPLIT_SECONDARY));
        if (!rb.ok) {
            invalidateIfShizukuDead(useShizuku);
            return rb;
        }

        // Applique le ratio (best-effort) s'il diffère sensiblement de 50/50.
        String output = rb.output == null ? "" : rb.output;
        if (ratio > 0f && Math.abs(ratio - 0.5f) > 0.01f) {
            boolean applied = applyRatioOnly(ctx, a, ratio, landscape);
            if (!applied) {
                // On ne cache plus l'échec : il est remonté à l'utilisateur.
                output = (output + "\n" + RATIO_NOTE).trim();
            }
        }
        return new ShellExecutor.Result(rb.ok, output, rb.exitCode);
    }

    /**
     * Applique le ratio en redimensionnant la tâche primaire (best-effort).
     * "am task resize &lt;TASK_ID&gt; &lt;LEFT,TOP,RIGHT,BOTTOM&gt;" redimensionne la pile
     * existante du split, ce qui déplace le diviseur.
     *
     * Exposée publiquement pour être réutilisée sur le CHEMIN ACCESSIBILITÉ (où
     * le ratio n'était sinon jamais appliqué).
     *
     * @return true si le redimensionnement a été accepté.
     */
    public static boolean applyRatioOnly(Context ctx, AppInfo primary, float ratio, boolean landscape) {
        try {
            if (ctx == null || primary == null) return false;
            boolean useShizuku = ShizukuBridge.isAvailable();

            // Sortie FILTRÉE côté shell : on évite la limite Binder (~1 Mo).
            ShellExecutor.Result dump = exec(useShizuku, DUMPSYS_FILTER);
            if (dump == null || !dump.ok || dump.output == null) return false;

            Integer taskId = findTaskId(dump.output, primary.packageName);
            if (taskId == null) return false;

            Rect[] halves = SplitController.computeHalves(ctx, landscape, ratio);
            Rect p = halves[0];
            String bounds = p.left + "," + p.top + "," + p.right + "," + p.bottom;
            ShellExecutor.Result r = exec(useShizuku, "am task resize " + taskId + " " + bounds);
            return r != null && r.ok;
        } catch (Throwable ignored) {
            return false;
        }
    }

    /** Retrouve l'identifiant de tâche associé à un paquet dans "dumpsys". */
    static Integer findTaskId(String dumpsys, String pkg) {
        if (dumpsys == null || pkg == null) return null;
        Integer current = null;
        for (String line : dumpsys.split("\n")) {
            Matcher m = TASK_ID.matcher(line);
            if (m.find()) {
                try {
                    current = Integer.parseInt(m.group(1));
                } catch (NumberFormatException ignored) {}
                if (line.contains(pkg)) return current;
            } else if (current != null
                    && line.contains("realActivity=")
                    && line.contains(pkg)) {
                return current;
            }
        }
        return null;
    }

    private static void invalidateIfShizukuDead(boolean useShizuku) {
        // Correctif v1.4 : si Shizuku était le canal et qu'il n'est plus vivant
        // (arrêté en cours de session), on invalide le cache POSITIF pour forcer
        // une nouvelle détection au prochain lancement.
        if (useShizuku && !ShizukuBridge.isAvailable()) {
            DeviceSupport.resetShellCache();
        }
    }

    private static ShellExecutor.Result exec(boolean useShizuku, String command) {
        if (useShizuku) {
            return ShizukuBridge.run(command);
        }
        return ShellExecutor.run("su -c " + quote(command));
    }

    /**
     * Construit la commande "am start".
     *
     * Correctif v1.4 : le COMPOSANT "pkg/activity" est entouré de quotes simples.
     * Sans cela, un nom contenant « $ » (activités internes ou alias, fréquents)
     * voyait « $Alias » expansé en chaîne vide par sh, et le lancement échouait.
     */
    static String buildAmStart(AppInfo app, int windowingMode) {
        return "am start"
                + " --windowingMode " + windowingMode
                + " -a android.intent.action.MAIN"
                + " -c android.intent.category.LAUNCHER"
                + " -n '" + app.packageName + "/" + app.activityName + "'";
    }

    private static String quote(String s) {
        return "'" + s.replace("'", "'\\''") + "'";
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
