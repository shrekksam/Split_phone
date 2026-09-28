package com.ninja.splitlauncher;

import android.content.ComponentName;
import android.content.Context;
import android.content.pm.PackageManager;
import android.graphics.drawable.Drawable;
import android.util.LruCache;

/**
 * Modèle représentant une application installée sélectionnable dans un slot.
 *
 * Correctif v1.2 : le cache stocke désormais le {@link Drawable.ConstantState}
 * (et non un Drawable partagé). Chaque appel à {@link #loadIcon} renvoie une
 * COPIE indépendante (constantState.newDrawable().mutate()), ce qui évite de
 * partager une même instance de Drawable entre plusieurs ImageView.
 */
public class AppInfo {

    /** Cache des ConstantState d'icônes (clé = package/activité), borné en nombre. */
    private static final LruCache<String, Drawable.ConstantState> ICON_CACHE =
            new LruCache<String, Drawable.ConstantState>(256) {
                @Override
                protected int sizeOf(String key, Drawable.ConstantState value) {
                    return 1; // borné en nombre d'entrées
                }
            };

    public final String label;
    public final String packageName;
    public final String activityName;
    public final Drawable icon; // peut être null : chargé à la demande

    public AppInfo(String label, String packageName, String activityName, Drawable icon) {
        this.label = label;
        this.packageName = packageName;
        this.activityName = activityName;
        this.icon = icon;
    }

    public boolean isValid() {
        return packageName != null && !packageName.isEmpty()
                && activityName != null && !activityName.isEmpty();
    }

    public boolean isSame(AppInfo other) {
        return other != null
                && packageName != null && packageName.equals(other.packageName)
                && activityName != null && activityName.equals(other.activityName);
    }

    public ComponentName toComponentName() {
        return new ComponentName(packageName, activityName);
    }

    private static String cacheKey(String pkg, String act) {
        return pkg + "/" + act;
    }

    /**
     * Charge l'icône d'une application et renvoie une COPIE indépendante.
     * Le ConstantState est mis en cache pour éviter de recharger le Drawable.
     */
    public static Drawable loadIcon(Context ctx, String pkg, String act) {
        if (pkg == null || act == null) return null;
        String key = cacheKey(pkg, act);

        Drawable.ConstantState cs = ICON_CACHE.get(key);
        if (cs == null) {
            PackageManager pm = ctx.getPackageManager();
            Drawable icon = null;
            try {
                icon = pm.getActivityIcon(new ComponentName(pkg, act));
            } catch (Exception e) {
                try {
                    icon = pm.getApplicationIcon(pkg);
                } catch (Exception ex) {
                    icon = null;
                }
            }
            if (icon != null) {
                cs = icon.getConstantState();
                if (cs != null) ICON_CACHE.put(key, cs);
                else return icon; // ConstantState indisponible : on renvoie tel quel
            }
        }
        if (cs == null) return null;
        try {
            return cs.newDrawable(ctx.getResources()).mutate();
        } catch (Exception e) {
            return null;
        }
    }

    /** Reconstruit un AppInfo depuis un profil sauvegardé (icône chargée à la demande). */
    public static AppInfo fromProfile(Context ctx, String pkg, String act, String label) {
        if (pkg == null || act == null) return null;
        String name = (label != null && !label.isEmpty()) ? label : pkg;
        return new AppInfo(name, pkg, act, null);
    }

    /** Vide le cache d'icônes (utile après install/désinstall d'applis). */
    public static void clearIconCache() {
        ICON_CACHE.evictAll();
    }
}
