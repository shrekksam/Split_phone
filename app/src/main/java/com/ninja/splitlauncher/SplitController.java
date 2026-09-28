package com.ninja.splitlauncher;

import android.content.Context;
import android.graphics.Rect;
import android.os.Build;
import android.util.DisplayMetrics;
import android.view.WindowManager;

import androidx.core.content.ContextCompat;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Façade du Split : géométrie des moitiés + point d'entrée du {@link SplitEngine}.
 *
 * La logique de décision (cascade) vit dans SplitEngine ; ici on ne garde que
 * les helpers purs (géométrie) et le câblage Android.
 */
public final class SplitController {

    private SplitController() {}

    /** Exécuteur partagé : toutes les opérations shell/attente hors UI. */
    private static final ExecutorService BACKGROUND =
            Executors.newSingleThreadExecutor(r -> {
                Thread t = new Thread(r, "split-engine");
                t.setDaemon(true);
                return t;
            });

    // ------------------------------------------------------------------ PUBLIC

    /**
     * Lance les deux applications en Split (asynchrone).
     *
     * @param landscape si vrai, découpe gauche/droite (paysage permanent).
     * @param ratio     fraction de la première moitié (0.5 = 50/50, 0.7 = 70/30).
     */
    public static void launchSplit(Context ctx, AppInfo a, AppInfo b,
                                   boolean landscape, float ratio,
                                   SplitEngine.Callback callback) {
        SplitEngine.Platform platform = new AndroidSplitPlatform(ctx, ratio);
        SplitEngine engine = new SplitEngine(
                platform, BACKGROUND, ContextCompat.getMainExecutor(ctx));
        engine.launchSplit(a, b, landscape, callback);
    }

    /** Surcharge pratique (ratio 50/50). */
    public static void launchSplit(Context ctx, AppInfo a, AppInfo b,
                                   boolean landscape, SplitEngine.Callback callback) {
        launchSplit(ctx, a, b, landscape, 0.5f, callback);
    }

    // ------------------------------------------------------------ GÉOMÉTRIE

    /**
     * Calcule les deux rectangles (moitiés).
     *
     * L'orientation RÉELLE est déduite des dimensions (width > height) et non du
     * seul réglage « paysage permanent » : ainsi, si l'appareil est physiquement
     * en paysage alors que le réglage est off, la découpe reste correcte.
     * Méthode PURE et testable (aucune dépendance Android).
     */
    public static Rect[] computeHalves(int width, int height, boolean landscape, float ratio) {
        boolean leftRight = landscape || width > height;
        float r = (ratio <= 0f || ratio >= 1f) ? 0.5f : ratio;

        Rect a, b;
        if (leftRight) {
            int split = Math.round(width * r);
            a = new Rect(0, 0, split, height);
            b = new Rect(split, 0, width, height);
        } else {
            int split = Math.round(height * r);
            a = new Rect(0, 0, width, split);
            b = new Rect(0, split, width, height);
        }
        return new Rect[]{a, b};
    }

    /** Surcharge historique (ratio 50/50). */
    public static Rect[] computeHalves(int width, int height, boolean landscape) {
        return computeHalves(width, height, landscape, 0.5f);
    }

    /** Variante Context : récupère la taille réelle de l'écran puis découpe. */
    public static Rect[] computeHalves(Context ctx, boolean landscape, float ratio) {
        int w, h;
        WindowManager wm = (WindowManager) ctx.getSystemService(Context.WINDOW_SERVICE);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && wm != null) {
            Rect full = wm.getCurrentWindowMetrics().getBounds();
            w = full.width();
            h = full.height();
        } else {
            DisplayMetrics dm = new DisplayMetrics();
            if (wm != null) {
                wm.getDefaultDisplay().getRealMetrics(dm);
            }
            w = dm.widthPixels;
            h = dm.heightPixels;
        }
        return computeHalves(w, h, landscape, ratio);
    }

    public static Rect[] computeHalves(Context ctx, boolean landscape) {
        return computeHalves(ctx, landscape, 0.5f);
    }
}
