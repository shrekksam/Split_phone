package com.ninja.splitlauncher;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * Persistance du Split : PLUSIEURS profils (paires d'applications) + réglages
 * globaux (orientation, barre flottante, démarrage auto, ratio de split).
 *
 * Les profils sont stockés en JSON dans SharedPreferences. L'ancien format
 * mono-profil (v1.1) est migré automatiquement.
 */
public class ProfileManager {

    private static final String PREFS = "split_profile";

    // Réglages globaux
    private static final String K_LOCK = "lockOrientation";
    private static final String K_LANDSCAPE = "landscape";
    private static final String K_FLOATING = "floatingBar";
    private static final String K_AUTOSTART = "autoStart";
    private static final String K_RATIO = "splitRatio";
    // Mode root OPT-IN : par défaut DÉSACTIVÉ (on ne sonde pas « su » à l'aveugle).
    private static final String K_USE_ROOT = "useRoot";

    // Profils
    private static final String K_PROFILES = "profiles";
    private static final String K_ACTIVE = "activeProfileId";

    // Ancien format (migration)
    private static final String K_PKG_A = "pkgA";
    private static final String K_ACT_A = "actA";
    private static final String K_LBL_A = "labelA";
    private static final String K_PKG_B = "pkgB";
    private static final String K_ACT_B = "actB";
    private static final String K_LBL_B = "labelB";

    private final SharedPreferences sp;

    public ProfileManager(Context ctx) {
        sp = ctx.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        migrateLegacyIfNeeded();
    }

    // ------------------------------------------------------------- MIGRATION

    private void migrateLegacyIfNeeded() {
        if (sp.contains(K_PROFILES)) return;
        String pkgA = sp.getString(K_PKG_A, null);
        String pkgB = sp.getString(K_PKG_B, null);
        if (pkgA == null && pkgB == null) return;

        SplitProfile p = new SplitProfile("Profil 1");
        p.pkgA = pkgA;
        p.actA = sp.getString(K_ACT_A, null);
        p.labelA = sp.getString(K_LBL_A, null);
        p.pkgB = pkgB;
        p.actB = sp.getString(K_ACT_B, null);
        p.labelB = sp.getString(K_LBL_B, null);

        List<SplitProfile> list = new ArrayList<>();
        list.add(p);
        saveProfiles(list);
        sp.edit().putString(K_ACTIVE, p.id).apply();
    }

    // --------------------------------------------------------------- PROFILS

    public List<SplitProfile> getProfiles() {
        List<SplitProfile> list = new ArrayList<>();
        String json = sp.getString(K_PROFILES, null);
        if (json == null) return list;
        try {
            JSONArray arr = new JSONArray(json);
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.optJSONObject(i);
                if (o != null) list.add(SplitProfile.fromJson(o));
            }
        } catch (Exception ignored) {}
        return list;
    }

    public void saveProfiles(List<SplitProfile> profiles) {
        JSONArray arr = new JSONArray();
        for (SplitProfile p : profiles) {
            try {
                arr.put(p.toJson());
            } catch (Exception ignored) {}
        }
        sp.edit().putString(K_PROFILES, arr.toString()).apply();
    }

    /** Profil actif, ou un profil vide par défaut (créé et persisté). */
    public SplitProfile getActiveProfile() {
        List<SplitProfile> list = getProfiles();
        String activeId = sp.getString(K_ACTIVE, null);

        if (list.isEmpty()) {
            SplitProfile p = new SplitProfile("Profil 1");
            list.add(p);
            saveProfiles(list);
            sp.edit().putString(K_ACTIVE, p.id).apply();
            return p;
        }
        for (SplitProfile p : list) {
            if (p.id != null && p.id.equals(activeId)) return p;
        }
        // id actif introuvable : on prend le premier
        SplitProfile first = list.get(0);
        sp.edit().putString(K_ACTIVE, first.id).apply();
        return first;
    }

    public void setActiveProfile(String id) {
        sp.edit().putString(K_ACTIVE, id).apply();
    }

    public String getActiveProfileId() {
        return getActiveProfile().id;
    }

    public void addProfile(SplitProfile p) {
        List<SplitProfile> list = getProfiles();
        list.add(p);
        saveProfiles(list);
    }

    public void updateProfile(SplitProfile updated) {
        List<SplitProfile> list = getProfiles();
        for (int i = 0; i < list.size(); i++) {
            if (list.get(i).id != null && list.get(i).id.equals(updated.id)) {
                list.set(i, updated);
                break;
            }
        }
        saveProfiles(list);
    }

    public void deleteProfile(String id) {
        List<SplitProfile> list = getProfiles();
        for (int i = 0; i < list.size(); i++) {
            if (list.get(i).id != null && list.get(i).id.equals(id)) {
                list.remove(i);
                break;
            }
        }
        saveProfiles(list);
        if (id != null && id.equals(sp.getString(K_ACTIVE, null))) {
            if (!list.isEmpty()) sp.edit().putString(K_ACTIVE, list.get(0).id).apply();
        }
    }

    /** Met à jour les apps du profil actif. */
    public void saveActiveApps(AppInfo a, AppInfo b) {
        SplitProfile p = getActiveProfile();
        p.setApps(a, b);
        updateProfile(p);
    }

    // ----------------------------------------------------- ACCÈS MONO-PROFIL
    // (compatibilité : renvoient les apps du profil actif)

    public AppInfo getAppA(Context ctx) {
        return getActiveProfile().getAppA(ctx);
    }

    public AppInfo getAppB(Context ctx) {
        return getActiveProfile().getAppB(ctx);
    }

    /** Sauvegarde complète (compatibilité v1.1) : apps du profil actif + réglages. */
    public void save(AppInfo a, AppInfo b,
                     boolean lockOrientation, boolean landscape,
                     boolean floatingBar, boolean autoStart) {
        saveActiveApps(a, b);
        sp.edit()
                .putBoolean(K_LOCK, lockOrientation)
                .putBoolean(K_LANDSCAPE, landscape)
                .putBoolean(K_FLOATING, floatingBar)
                .putBoolean(K_AUTOSTART, autoStart)
                .apply();
    }

    // ------------------------------------------------------------- RÉGLAGES

    public boolean isLockOrientation() { return sp.getBoolean(K_LOCK, false); }
    public boolean isLandscape()       { return sp.getBoolean(K_LANDSCAPE, false); }
    public boolean isFloatingBar()     { return sp.getBoolean(K_FLOATING, false); }
    public boolean isAutoStart()       { return sp.getBoolean(K_AUTOSTART, false); }
    public float getRatio()            { return sp.getFloat(K_RATIO, 0.5f); }
    /** Mode root (opt-in) : autorise le repli « su » quand Shizuku est absent. */
    public boolean isUseRoot()         { return sp.getBoolean(K_USE_ROOT, false); }

    public void setLockOrientation(boolean v) { sp.edit().putBoolean(K_LOCK, v).apply(); }
    public void setLandscape(boolean v)       { sp.edit().putBoolean(K_LANDSCAPE, v).apply(); }
    public void setFloatingBar(boolean v)     { sp.edit().putBoolean(K_FLOATING, v).apply(); }
    public void setAutoStart(boolean v)       { sp.edit().putBoolean(K_AUTOSTART, v).apply(); }
    public void setRatio(float v)             { sp.edit().putFloat(K_RATIO, v).apply(); }
    public void setUseRoot(boolean v)         { sp.edit().putBoolean(K_USE_ROOT, v).apply(); }

    public boolean hasProfile() {
        return getActiveProfile().hasBoth();
    }
}
