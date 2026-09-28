package com.ninja.splitlauncher;

import android.content.Context;
import android.content.Intent;
import android.content.pm.ShortcutInfo;
import android.content.pm.ShortcutManager;
import android.graphics.drawable.Icon;
import android.os.Build;

import java.util.ArrayList;
import java.util.List;

/**
 * Publication de raccourcis DYNAMIQUES, un par profil.
 *
 * Correctif v1.3 : le raccourci statique ne lance que le profil ACTIF. On publie
 * en plus un raccourci épinglable PAR PROFIL, ce qui est bien plus utile quand
 * l'utilisateur a plusieurs paires d'applications.
 *
 * Chaque raccourci ouvre {@link LaunchSplitActivity} avec l'id du profil en
 * extra : l'activité active ce profil puis lance le split au premier plan.
 */
public final class ShortcutHelper {

    /** Extra transmis à {@link LaunchSplitActivity} pour cibler un profil précis. */
    public static final String EXTRA_PROFILE_ID = "profileId";

    /** Nombre maximal de raccourcis dynamiques publiés (limite système ~5). */
    private static final int MAX_DYNAMIC = 4;

    private ShortcutHelper() {}

    /** (Re)publie un raccourci dynamique par profil. Appelé au démarrage de l'UI. */
    public static void publishProfileShortcuts(Context ctx) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N_MR1) return;

        ShortcutManager sm = ctx.getSystemService(ShortcutManager.class);
        if (sm == null) return;

        ProfileManager pm = new ProfileManager(ctx);
        List<SplitProfile> profiles = pm.getProfiles();

        List<ShortcutInfo> shortcuts = new ArrayList<>();
        int count = 0;
        for (SplitProfile p : profiles) {
            if (count >= MAX_DYNAMIC) break;
            if (p == null || p.id == null) continue;

            Intent intent = new Intent(ctx, LaunchSplitActivity.class);
            intent.setAction("com.ninja.splitlauncher.action.LAUNCH_PROFILE");
            intent.putExtra(EXTRA_PROFILE_ID, p.id);

            try {
                ShortcutInfo si = new ShortcutInfo.Builder(ctx, "profile_" + p.id)
                        .setShortLabel(truncate(p.displayName()))
                        .setLongLabel(p.displayName())
                        .setIcon(Icon.createWithResource(ctx, R.mipmap.ic_launcher))
                        .setIntent(intent)
                        .build();
                shortcuts.add(si);
                count++;
            } catch (Throwable ignored) {}
        }

        try {
            sm.setDynamicShortcuts(shortcuts);
        } catch (Throwable ignored) {
            // Certains lanceurs limitent le nombre de raccourcis : on ignore.
        }
    }

    private static String truncate(String s) {
        if (s == null) return "";
        return s.length() > 20 ? s.substring(0, 19) + "\u2026" : s;
    }
}
