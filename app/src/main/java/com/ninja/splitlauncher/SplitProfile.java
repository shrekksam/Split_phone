package com.ninja.splitlauncher;

import android.content.Context;

import org.json.JSONException;
import org.json.JSONObject;

import java.util.UUID;

/**
 * Un « profil » = une paire d'applications nommée (A | B).
 * Plusieurs profils peuvent être enregistrés et rappelés en un tap.
 */
public class SplitProfile {

    public String id;
    public String name;

    public String pkgA, actA, labelA;
    public String pkgB, actB, labelB;

    public SplitProfile() {
        this.id = UUID.randomUUID().toString();
        this.name = "";
    }

    public SplitProfile(String name) {
        this();
        this.name = name;
    }

    public void setApps(AppInfo a, AppInfo b) {
        pkgA = a != null ? a.packageName : null;
        actA = a != null ? a.activityName : null;
        labelA = a != null ? a.label : null;
        pkgB = b != null ? b.packageName : null;
        actB = b != null ? b.activityName : null;
        labelB = b != null ? b.label : null;
    }

    public AppInfo getAppA(Context ctx) {
        return AppInfo.fromProfile(ctx, pkgA, actA, labelA);
    }

    public AppInfo getAppB(Context ctx) {
        return AppInfo.fromProfile(ctx, pkgB, actB, labelB);
    }

    public boolean hasBoth() {
        return pkgA != null && actA != null && pkgB != null && actB != null;
    }

    /** Libellé affiché dans le sélecteur de profils. */
    public String displayName() {
        if (name != null && !name.trim().isEmpty()) return name;
        String a = labelA != null ? labelA : "—";
        String b = labelB != null ? labelB : "—";
        return a + " | " + b;
    }

    // ------------------------------------------------------------------- JSON

    public JSONObject toJson() throws JSONException {
        JSONObject o = new JSONObject();
        o.put("id", id);
        o.put("name", name);
        o.put("pkgA", pkgA);
        o.put("actA", actA);
        o.put("labelA", labelA);
        o.put("pkgB", pkgB);
        o.put("actB", actB);
        o.put("labelB", labelB);
        return o;
    }

    public static SplitProfile fromJson(JSONObject o) {
        SplitProfile p = new SplitProfile();
        p.id = o.optString("id", UUID.randomUUID().toString());
        p.name = o.optString("name", "");
        p.pkgA = opt(o, "pkgA");
        p.actA = opt(o, "actA");
        p.labelA = opt(o, "labelA");
        p.pkgB = opt(o, "pkgB");
        p.actB = opt(o, "actB");
        p.labelB = opt(o, "labelB");
        return p;
    }

    private static String opt(JSONObject o, String key) {
        return o.isNull(key) ? null : o.optString(key, null);
    }
}
