package com.ninja.splitlauncher;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.net.Uri;
import android.os.Build;
import android.provider.Settings;

/**
 * Encapsule les interactions avec le WindowManager et les réglages système.
 *
 * NOTE SÉCURITÉ : la rotation n'est gérée QU'AU NIVEAU DE L'ACTIVITÉ via
 * setRequestedOrientation(). On n'utilise PLUS WRITE_SETTINGS (permission
 * protégée lourde, risque de rejet Play Store) ni USER_ROTATION global, qui
 * provoquait une orientation inversée sur les appareils à paysage naturel
 * (tablettes). SCREEN_ORIENTATION_LANDSCAPE gère correctement l'orientation
 * naturelle de l'appareil.
 */
public final class WindowManagerHelper {

    private WindowManagerHelper() {}

    // ------------------------------------------------------------------ OVERLAY

    /** L'application peut-elle dessiner par-dessus les autres ? */
    public static boolean canDrawOverlays(Context ctx) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            return Settings.canDrawOverlays(ctx);
        }
        return true; // Avant Marshmallow, autorisé à l'installation.
    }

    /** Intent pour demander l'autorisation d'overlay. */
    public static Intent overlayPermissionIntent(Context ctx) {
        Intent i = new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:" + ctx.getPackageName()));
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        return i;
    }

    // -------------------------------------------------------------- ORIENTATION

    /**
     * Applique l'orientation demandée à une activité (niveau app uniquement).
     *
     * @param landscape si vrai, force le paysage (gère l'orientation naturelle).
     * @param lock      si vrai (et pas paysage), verrouille l'orientation courante.
     */
    public static void applyOrientation(Activity activity, boolean landscape, boolean lock) {
        int orientation;
        if (landscape) {
            // SCREEN_ORIENTATION_LANDSCAPE choisit le bon sens selon
            // l'orientation naturelle de l'appareil (pas de ROTATION_90 forcé).
            orientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE;
        } else if (lock) {
            orientation = ActivityInfo.SCREEN_ORIENTATION_LOCKED;
        } else {
            orientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED;
        }
        activity.setRequestedOrientation(orientation);
    }

    // ------------------------------------------------------------- HELPERS

    /** Ouvre en sécurité une activité d'autorisation, sans crash si absente. */
    public static void safeStartActivity(Context ctx, Intent intent) {
        try {
            ctx.startActivity(intent);
        } catch (ActivityNotFoundException ignored) {
        }
    }
}
