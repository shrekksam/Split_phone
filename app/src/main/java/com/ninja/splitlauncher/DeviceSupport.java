package com.ninja.splitlauncher;

import android.content.Context;
import android.os.Build;

/**
 * Détection des capacités de l'appareil concernant le multi-fenêtres.
 *
 * Le lancement programmatique "adjacent" (setLaunchBounds +
 * FLAG_ACTIVITY_LAUNCH_ADJACENT) n'est PAS garanti par l'API publique :
 * AOSP ignore silencieusement les bounds hors mode freeform. Seuls certains
 * OEM (Samsung, LG…) l'honorent.
 */
public final class DeviceSupport {

    private DeviceSupport() {}

    /** Constructeurs connus pour honorer le split programmatique. */
    private static final String[] RELIABLE_MANUFACTURERS = {
            "samsung", "lg", "lge"
    };

    // ------------------------------------------------------------------ SHELL
    // Correctif : un résultat NÉGATIF n'est plus mis en cache définitivement
    // (l'utilisateur peut accorder root/Shizuku après coup). On applique un TTL
    // court pour éviter de sonder le système à chaque appel.

    private static final long NEGATIVE_TTL_MS = 30_000L;

    private static Boolean shellAvailable = null; // true = définitif, false = temporaire
    private static long shellCheckedAt = 0L;

    /**
     * Une méthode shell (Shizuku/root) est-elle disponible ?
     * Un positif est mémorisé ; un négatif expire au bout de 30 s.
     *
     * C'est le GARDE-FOU unique du chemin shell : il évite de tenter "su" à
     * chaque lancement (et donc d'afficher une invite Magisk bloquante) quand
     * aucun accès privilégié n'est disponible.
     */
    public static synchronized boolean canUseShellSplit(Context ctx) {
        long now = System.currentTimeMillis();
        if (shellAvailable != null) {
            if (shellAvailable) return true;                 // positif : définitif
            if (now - shellCheckedAt < NEGATIVE_TTL_MS) {    // négatif : encore valide
                return false;
            }
        }
        boolean available = ShellSplitter.isShellAvailable(ctx);
        shellAvailable = available;
        shellCheckedAt = now;
        return available;
    }

    /** Force une nouvelle détection (ex : après autorisation Shizuku). */
    public static synchronized void resetShellCache() {
        shellAvailable = null;
        shellCheckedAt = 0L;
    }

    // -------------------------------------------------------------- API PUBLIQUE

    /** Le split programmatique via API publique est-il fiable sur cet appareil ? */
    public static boolean isProgrammaticSplitReliable() {
        String mfr = Build.MANUFACTURER == null ? "" : Build.MANUFACTURER.toLowerCase();
        for (String r : RELIABLE_MANUFACTURERS) {
            if (mfr.contains(r)) return true;
        }
        return false;
    }

    /** L'appareil est-il en mode RAM faible (souvent limité pour le split) ? */
    public static boolean isLowRam(Context ctx) {
        try {
            android.app.ActivityManager am =
                    (android.app.ActivityManager) ctx.getSystemService(
                            Context.ACTIVITY_SERVICE);
            return am != null && am.isLowRamDevice();
        } catch (Throwable t) {
            return false;
        }
    }
}
