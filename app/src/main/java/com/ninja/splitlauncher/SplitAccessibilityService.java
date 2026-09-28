package com.ninja.splitlauncher;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.AccessibilityServiceInfo;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.provider.Settings;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityManager;
import android.view.accessibility.AccessibilityWindowInfo;

import java.util.List;

/**
 * Service d'accessibilité chargé de DÉCLENCHER et de VÉRIFIER le split natif.
 *
 * C'est LA méthode fiable, sans root, utilisée par les applis du même type :
 *   1. lancer l'application A ;
 *   2. appeler GLOBAL_ACTION_TOGGLE_SPLIT_SCREEN (API 24+) ;
 *   3. VÉRIFIER que le split est réellement actif (présence d'un diviseur) ;
 *   4. lancer l'application B (elle rejoint l'autre moitié).
 *
 * Correctif v1.3 : GLOBAL_ACTION_TOGGLE_SPLIT_SCREEN est un BASCULEMENT. Si
 * l'utilisateur est déjà en split, l'appeler QUITTE le split. On vérifie donc
 * l'état avant d'agir, et on ne bascule que si nécessaire.
 *
 * Confidentialité : pour vérifier l'état, le service lit la STRUCTURE des
 * fenêtres (leur TYPE). Il ne lit ni ne transmet aucun CONTENU (texte, champs,
 * images). Cette utilisation est divulguée à l'utilisateur.
 */
public class SplitAccessibilityService extends AccessibilityService {

    private static volatile SplitAccessibilityService instance;

    /** Instance active, ou null si le service n'est pas connecté. */
    public static SplitAccessibilityService getInstance() {
        return instance;
    }

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        instance = this;
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        // Aucun traitement : le service sert uniquement d'émetteur d'action
        // et de vérificateur d'état (structure des fenêtres).
    }

    @Override
    public void onInterrupt() {
        // Rien à faire.
    }

    @Override
    public boolean onUnbind(Intent intent) {
        instance = null;
        return super.onUnbind(intent);
    }

    @Override
    public void onDestroy() {
        if (instance == this) instance = null;
        super.onDestroy();
    }

    /**
     * Déclenche le basculement en écran partagé.
     * @return true si l'action globale a été acceptée par le système.
     */
    public boolean toggleSplitScreen() {
        return performGlobalAction(GLOBAL_ACTION_TOGGLE_SPLIT_SCREEN);
    }

    // ------------------------------------------------------------- VÉRIFICATION

    /**
     * Le système est-il RÉELLEMENT en écran partagé ?
     *
     * On se base sur la présence d'une fenêtre de type
     * {@link AccessibilityWindowInfo#TYPE_SPLIT_SCREEN_DIVIDER}. Nécessite
     * canRetrieveWindowContent + flagRetrieveInteractiveWindows (voir la config).
     */
    public boolean isInSplitScreen() {
        try {
            List<AccessibilityWindowInfo> windows = getWindows();
            if (windows == null) return false;
            for (AccessibilityWindowInfo w : windows) {
                if (w != null && w.getType() == AccessibilityWindowInfo.TYPE_SPLIT_SCREEN_DIVIDER) {
                    return true;
                }
            }
        } catch (Throwable ignored) {}
        return false;
    }

    /**
     * Attend (polling) que le split devienne actif, jusqu'à {@code timeoutMs}.
     * Remplace les délais fixes fragiles par une attente VÉRIFIÉE.
     */
    public boolean waitForSplit(long timeoutMs) {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            if (isInSplitScreen()) return true;
            sleep(80);
        }
        return isInSplitScreen();
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    // ------------------------------------------------------------- DÉTECTION

    /** Le service d'accessibilité est-il activé par l'utilisateur ? */
    public static boolean isEnabled(Context ctx) {
        if (instance != null) return true;
        try {
            AccessibilityManager am = (AccessibilityManager)
                    ctx.getSystemService(Context.ACCESSIBILITY_SERVICE);
            if (am == null) return false;
            List<AccessibilityServiceInfo> enabled =
                    am.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK);
            String ours = new ComponentName(ctx, SplitAccessibilityService.class)
                    .flattenToString();
            for (AccessibilityServiceInfo info : enabled) {
                if (info.getResolveInfo() == null
                        || info.getResolveInfo().serviceInfo == null) continue;
                ComponentName cn = new ComponentName(
                        info.getResolveInfo().serviceInfo.packageName,
                        info.getResolveInfo().serviceInfo.name);
                if (ours.equals(cn.flattenToString())
                        || SplitAccessibilityService.class.getName().equals(cn.getClassName())) {
                    return true;
                }
            }
        } catch (Throwable ignored) {}
        return false;
    }

    /** Intent vers l'écran des réglages d'accessibilité. */
    public static Intent settingsIntent(Context ctx) {
        Intent i = new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS);
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        return i;
    }
}
