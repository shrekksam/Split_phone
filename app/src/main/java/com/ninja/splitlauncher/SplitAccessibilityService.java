package com.ninja.splitlauncher;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.AccessibilityServiceInfo;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.graphics.Rect;
import android.os.Build;
import android.os.Looper;
import android.os.SystemClock;
import android.provider.Settings;
import android.text.TextUtils;
import android.util.Log;
import android.view.Display;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityManager;
import android.view.accessibility.AccessibilityWindowInfo;

import java.util.ArrayList;
import java.util.List;

/**
 * Service d'accessibilité chargé de DÉCLENCHER et de VÉRIFIER le split natif.
 *
 * C'est LA méthode fiable, sans root :
 *   1. lancer l'application A ;
 *   2. appeler GLOBAL_ACTION_TOGGLE_SPLIT_SCREEN (API 24+) ;
 *   3. VÉRIFIER que le split est réellement actif ;
 *   4. lancer l'application B (elle rejoint l'autre moitié).
 *
 * <h3>Correctif v1.3</h3>
 * GLOBAL_ACTION_TOGGLE_SPLIT_SCREEN est un BASCULEMENT : s'il est appelé alors
 * que l'utilisateur est déjà en split, il QUITTE le split. On vérifie donc
 * l'état avant d'agir ({@link #enterSplitScreen}, {@link #exitSplitScreen}).
 *
 * <h3>Correctif v1.5 — vérification robuste</h3>
 * <ol>
 *   <li><b>Deuxième critère.</b> La seule présence d'une fenêtre
 *       {@link AccessibilityWindowInfo#TYPE_SPLIT_SCREEN_DIVIDER} ne suffit pas :
 *       plusieurs OEM (et certaines versions d'AOSP pendant/après l'animation)
 *       n'exposent PAS le diviseur à l'accessibilité. On ajoute donc un critère
 *       GÉOMÉTRIQUE : deux fenêtres d'application occupant deux RÉGIONS
 *       DISTINCTES de l'écran (côte à côte ou empilées, sans recouvrement).
 *       Un seul des deux critères suffit à conclure « split actif ».</li>
 *   <li><b>Recyclage des fenêtres sur API &lt; 33.</b> Avant Android 13, les
 *       {@link AccessibilityWindowInfo} viennent d'un pool d'objets : ne pas les
 *       recycler fait fuir de la mémoire à chaque sondage (le polling en produit
 *       des dizaines par lancement). Depuis l'API 33 le pooling a été supprimé
 *       et {@code recycle()} est un no-op déprécié : on ne l'appelle plus.</li>
 *   <li><b>{@link #waitForSplitExit(long)}.</b> Symétrique de
 *       {@link #waitForSplit(long)} : permet de repartir d'un état PROPRE
 *       (sortie effective du split) avant de reconstruire un split.</li>
 *   <li><b>{@link #awaitConnection(Context, long)}.</b> Le système lie le
 *       service de façon ASYNCHRONE : juste après l'activation par
 *       l'utilisateur (ou après un redémarrage du processus), le service est
 *       « activé » sans être encore connecté. On attend brièvement la connexion
 *       au lieu d'échouer et de retomber inutilement sur le mode assisté.</li>
 * </ol>
 *
 * <h3>Attentes et thread</h3>
 * Les attentes sont ÉVÉNEMENTIELLES (réveil sur {@code TYPE_WINDOWS_CHANGED} /
 * {@code TYPE_WINDOW_STATE_CHANGED}) avec un sondage de sécurité toutes les
 * {@value #POLL_INTERVAL_MS} ms. Elles ne doivent JAMAIS être appelées depuis le
 * thread principal : les méthodes le détectent et renvoient immédiatement la
 * dernière lecture plutôt que de risquer un ANR (les événements d'accessibilité
 * étant eux-mêmes délivrés sur ce thread, y attendre serait un interblocage).
 *
 * <h3>Confidentialité</h3>
 * La vérification lit uniquement la STRUCTURE des fenêtres : leur TYPE et leurs
 * DIMENSIONS à l'écran. Aucun CONTENU (texte, champs de saisie, images) n'est lu
 * ni transmis ; {@code getRoot()} n'est jamais appelé. Cette utilisation est
 * divulguée à l'utilisateur (voir {@code accessibility_service_description}).
 */
public class SplitAccessibilityService extends AccessibilityService {

    private static final String TAG = "SplitA11y";

    // ------------------------------------------------------------- RÉGLAGES

    /** Intervalle maximal entre deux sondages pendant une attente (ms). */
    static final long POLL_INTERVAL_MS = 120L;

    /**
     * Intervalle MINIMAL entre deux lectures des fenêtres (ms). Les attentes
     * sont réveillées par les événements : pendant une animation de split, ils
     * arrivent en rafale. Ce plancher évite de multiplier les appels système.
     */
    static final long MIN_SCAN_INTERVAL_MS = 40L;

    /**
     * Nombre de lectures CONSÉCUTIVES concordantes exigées pour conclure avant
     * l'expiration du délai. Évite de conclure sur un état FUGITIF (animation
     * d'entrée/sortie de split, fenêtre redimensionnée en cours de transition).
     * La présence du diviseur, elle, est un signal non ambigu : une seule
     * lecture suffit.
     */
    static final int STABLE_READS = 2;

    // --- Seuils du critère géométrique (fractions de l'écran) ---------------
    // Volontairement tolérants : barres système, encoches, gestes, diviseur
    // épais, ratio 70/30, clavier ouvert… tout cela déforme les « moitiés ».

    /** En deçà, la fenêtre est une boîte de dialogue / un popup, pas une moitié. */
    static final float MIN_HALF_AREA = 0.10f;
    /** Au-delà, la fenêtre est plein écran : ce n'est donc PAS un split. */
    static final float MAX_HALF_AREA = 0.92f;
    /** Surface cumulée minimale des deux fenêtres (sinon : fenêtres flottantes). */
    static final float MIN_PAIR_AREA = 0.55f;
    /** Étendue minimale de chaque fenêtre sur l'axe PERPENDICULAIRE à la coupe. */
    static final float MIN_CROSS_SPAN = 0.60f;
    /** Alignement minimal des deux fenêtres sur cet axe perpendiculaire. */
    static final float MIN_ALIGNMENT = 0.75f;
    /** Écart maximal entre les deux fenêtres le long de l'axe de coupe. */
    static final float MAX_GAP = 0.20f;
    /** Recouvrement toléré (fraction de la plus petite des deux fenêtres). */
    static final float MAX_OVERLAP = 0.05f;

    // ------------------------------------------------------------- INSTANCE

    private static volatile SplitAccessibilityService instance;

    /** Moniteur de CONNEXION : réveillé quand le système lie le service. */
    private static final Object CONNECTION_LOCK = new Object();

    /** Moniteur de FENÊTRES : réveillé à chaque changement de fenêtres. */
    private static final Object WINDOW_LOCK = new Object();

    /** Instance active, ou null si le service n'est pas connecté. */
    public static SplitAccessibilityService getInstance() {
        return instance;
    }

    /** Le service est-il connecté (lié par le système) ici et maintenant ? */
    public static boolean isConnected() {
        return instance != null;
    }

    /**
     * Attend que le service soit connecté, jusqu'à {@code timeoutMs}.
     *
     * Utile juste après l'activation par l'utilisateur : le service est déjà
     * « activé » dans les réglages alors que {@link #getInstance()} renvoie
     * encore null (liaison asynchrone).
     *
     * @return l'instance connectée, ou null si le délai expire.
     */
    public static SplitAccessibilityService awaitConnection(long timeoutMs) {
        SplitAccessibilityService svc = instance;
        if (svc != null) return svc;

        // Jamais d'attente bloquante sur le thread principal (risque d'ANR).
        if (isMainThread()) return null;

        final long deadline = SystemClock.uptimeMillis() + Math.max(0L, timeoutMs);
        synchronized (CONNECTION_LOCK) {
            while (instance == null) {
                long remaining = deadline - SystemClock.uptimeMillis();
                if (remaining <= 0L) break;
                try {
                    CONNECTION_LOCK.wait(remaining);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }
        return instance;
    }

    /**
     * Variante qui n'attend QUE si le service est effectivement activé par
     * l'utilisateur : inutile (et coûteux) d'attendre une connexion qui ne
     * viendra jamais.
     */
    public static SplitAccessibilityService awaitConnection(Context ctx, long timeoutMs) {
        SplitAccessibilityService svc = instance;
        if (svc != null) return svc;
        if (ctx != null && !isEnabled(ctx)) return null;
        return awaitConnection(timeoutMs);
    }

    // ------------------------------------------------------------ CYCLE DE VIE

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        ensureWindowInspectionEnabled();
        synchronized (CONNECTION_LOCK) {
            instance = this;
            CONNECTION_LOCK.notifyAll();
        }
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        // Aucun CONTENU n'est lu : on ne regarde que le TYPE de l'événement pour
        // réveiller les attentes en cours (vérification de l'état du split).
        if (event == null) return;
        int type = event.getEventType();
        if (type == AccessibilityEvent.TYPE_WINDOWS_CHANGED
                || type == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            synchronized (WINDOW_LOCK) {
                WINDOW_LOCK.notifyAll();
            }
        }
    }

    @Override
    public void onInterrupt() {
        // Rien à faire : le service n'a aucun retour utilisateur à interrompre.
    }

    @Override
    public boolean onUnbind(Intent intent) {
        clearInstance();
        return super.onUnbind(intent);
    }

    @Override
    public void onDestroy() {
        clearInstance();
        super.onDestroy();
    }

    /** Libère l'instance ET réveille tous les threads en attente. */
    private void clearInstance() {
        synchronized (CONNECTION_LOCK) {
            if (instance == this) instance = null;
            CONNECTION_LOCK.notifyAll();
        }
        synchronized (WINDOW_LOCK) {
            WINDOW_LOCK.notifyAll(); // débloque les attentes de split en cours
        }
    }

    /**
     * Garantit que la configuration RUNTIME permet la vérification.
     *
     * Sans {@code FLAG_RETRIEVE_INTERACTIVE_WINDOWS}, {@link #getWindows()}
     * renvoie une liste VIDE : impossible de vérifier quoi que ce soit. Le XML
     * le déclare déjà, mais certains OEM (ou une restauration de réglages)
     * peuvent le perdre ; on le réapplique par sécurité.
     */
    private void ensureWindowInspectionEnabled() {
        try {
            AccessibilityServiceInfo info = getServiceInfo();
            if (info == null) return;
            int neededFlags = AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS;
            int neededEvents = AccessibilityEvent.TYPE_WINDOWS_CHANGED
                    | AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED;
            boolean missingFlags = (info.flags & neededFlags) != neededFlags;
            boolean missingEvents = (info.eventTypes & neededEvents) != neededEvents;
            if (missingFlags || missingEvents) {
                info.flags |= neededFlags;
                info.eventTypes |= neededEvents;
                setServiceInfo(info);
            }
        } catch (Throwable t) {
            Log.w(TAG, "Configuration runtime impossible : " + t);
        }
    }

    // --------------------------------------------------------------- ACTIONS

    /**
     * Déclenche le BASCULEMENT en écran partagé (entrée OU sortie !).
     *
     * Préférer {@link #enterSplitScreen(long)} / {@link #exitSplitScreen(long)},
     * qui vérifient l'état avant d'agir et le confirment après.
     *
     * @return true si l'action globale a été acceptée par le système.
     */
    public boolean toggleSplitScreen() {
        try {
            return performGlobalAction(GLOBAL_ACTION_TOGGLE_SPLIT_SCREEN);
        } catch (Throwable t) {
            Log.w(TAG, "GLOBAL_ACTION_TOGGLE_SPLIT_SCREEN refusée : " + t);
            return false;
        }
    }

    /**
     * Entre en écran partagé et CONFIRME le résultat.
     * Idempotent : si le split est déjà actif, on ne bascule pas (sinon on en
     * sortirait) et on renvoie true immédiatement.
     */
    public boolean enterSplitScreen(long timeoutMs) {
        if (isInSplitScreen()) return true;
        if (!toggleSplitScreen()) return false;
        return waitForSplit(timeoutMs);
    }

    /**
     * Quitte l'écran partagé et CONFIRME la sortie.
     * Idempotent : si aucun split n'est actif, renvoie true immédiatement.
     */
    public boolean exitSplitScreen(long timeoutMs) {
        if (!isInSplitScreen()) return true;
        if (!toggleSplitScreen()) return false;
        return waitForSplitExit(timeoutMs);
    }

    // ---------------------------------------------------------- VÉRIFICATION

    /** Instantané de l'état des fenêtres (diagnostic + décision). */
    public static final class SplitState {

        /** État renvoyé quand AUCUNE fenêtre n'est lisible : on ne sait pas. */
        public static final SplitState UNVERIFIABLE =
                new SplitState(false, false, 0, false);

        /** Une fenêtre TYPE_SPLIT_SCREEN_DIVIDER est présente (critère n°1). */
        public final boolean dividerPresent;
        /** Deux fenêtres d'app occupent des régions distinctes (critère n°2). */
        public final boolean distinctAppRegions;
        /** Nombre de fenêtres d'application retenues (hors PiP / autres écrans). */
        public final int appWindowCount;
        /** Au moins une fenêtre a pu être lue : la vérification est fiable. */
        public final boolean windowsVisible;

        SplitState(boolean dividerPresent, boolean distinctAppRegions,
                   int appWindowCount, boolean windowsVisible) {
            this.dividerPresent = dividerPresent;
            this.distinctAppRegions = distinctAppRegions;
            this.appWindowCount = appWindowCount;
            this.windowsVisible = windowsVisible;
        }

        /** Split actif ? Un SEUL des deux critères suffit. */
        public boolean isSplit() {
            return dividerPresent || distinctAppRegions;
        }

        /**
         * La lecture des fenêtres a-t-elle abouti ? Si false, {@link #isSplit()}
         * vaut false par DÉFAUT (« je ne sais pas »), et non « pas de split ».
         */
        public boolean isVerifiable() {
            return windowsVisible;
        }

        @Override
        public String toString() {
            return "SplitState{divider=" + dividerPresent
                    + ", regions=" + distinctAppRegions
                    + ", appWindows=" + appWindowCount
                    + ", verifiable=" + windowsVisible + "}";
        }
    }

    /**
     * Le système est-il RÉELLEMENT en écran partagé ?
     *
     * Deux critères indépendants (voir {@link SplitState}) ; un seul suffit.
     * Ne renvoie jamais true « par optimisme » : si les fenêtres ne peuvent pas
     * être lues, la réponse est false.
     */
    public boolean isInSplitScreen() {
        return inspectSplitState().isSplit();
    }

    /**
     * La vérification est-elle seulement POSSIBLE ?
     *
     * Renvoie false quand aucune fenêtre n'est lisible (flag
     * {@code flagRetrieveInteractiveWindows} retiré par l'OEM, écran verrouillé,
     * service en cours de connexion…). L'appelant peut alors éviter de basculer
     * À L'AVEUGLE : sans vérification possible, un basculement non confirmé
     * risquerait surtout de perturber l'état courant de l'utilisateur.
     */
    public boolean canVerifySplit() {
        return inspectSplitState().isVerifiable();
    }

    /**
     * Lit la structure des fenêtres et en déduit l'état du split.
     *
     * Appelable depuis n'importe quel thread (la lecture est un appel binder
     * non bloquant). Les objets {@link AccessibilityWindowInfo} sont recyclés
     * sur API &lt; 33 (voir {@link #recycleWindows(List)}).
     */
    public SplitState inspectSplitState() {
        List<AccessibilityWindowInfo> windows = null;
        boolean divider = false;
        final List<Rect> appWindows = new ArrayList<>(4);
        final Rect screen = new Rect();

        try {
            windows = getWindows();
            if (windows == null || windows.isEmpty()) {
                // Flag manquant, service en cours de connexion, écran verrouillé…
                return SplitState.UNVERIFIABLE;
            }

            for (AccessibilityWindowInfo window : windows) {
                if (window == null) continue;
                if (!isOnDefaultDisplay(window)) continue;

                // On COPIE les bornes : l'objet peut être recyclé juste après.
                Rect bounds = new Rect();
                window.getBoundsInScreen(bounds);
                boolean usable = bounds.width() > 0 && bounds.height() > 0;

                // L'écran = union de TOUTES les fenêtres (barres système
                // comprises). Plus fiable que les métriques d'affichage : c'est
                // exactement le repère dans lequel les bornes sont exprimées.
                if (usable) screen.union(bounds);

                int type = window.getType();
                if (type == AccessibilityWindowInfo.TYPE_SPLIT_SCREEN_DIVIDER) {
                    divider = true;
                    continue;
                }
                if (type != AccessibilityWindowInfo.TYPE_APPLICATION) continue;
                if (isPictureInPicture(window)) continue; // PiP ≠ moitié de split
                if (!usable) continue;

                appWindows.add(bounds);
            }
        } catch (Throwable t) {
            Log.w(TAG, "Lecture des fenêtres impossible : " + t);
            return SplitState.UNVERIFIABLE;
        } finally {
            recycleWindows(windows);
        }

        boolean regions = hasTwoAppWindowsInDistinctRegions(appWindows, screen);
        return new SplitState(divider, regions, appWindows.size(), true);
    }

    // ------------------------------------------------------------- ATTENTES

    /**
     * Attend que le split devienne actif, jusqu'à {@code timeoutMs}.
     * Remplace les délais fixes fragiles par une attente VÉRIFIÉE.
     */
    public boolean waitForSplit(long timeoutMs) {
        return awaitSplitState(true, timeoutMs);
    }

    /**
     * Attend la SORTIE effective du split, jusqu'à {@code timeoutMs}.
     *
     * Indispensable avant de reconstruire un split : après
     * {@link #toggleSplitScreen()}, le diviseur et les moitiés survivent le
     * temps de l'animation. Relancer une application « trop tôt » la ferait
     * rejoindre le split que l'on vient de quitter.
     */
    public boolean waitForSplitExit(long timeoutMs) {
        return awaitSplitState(false, timeoutMs);
    }

    /**
     * Cœur commun des deux attentes.
     *
     * Attente ÉVÉNEMENTIELLE (réveil sur changement de fenêtres) doublée d'un
     * sondage de sécurité. Pour conclure AVANT l'expiration du délai, on exige
     * {@value #STABLE_READS} lectures consécutives concordantes — sauf si le
     * diviseur est présent, signal non ambigu. À l'expiration, la dernière
     * lecture fait foi.
     */
    private boolean awaitSplitState(boolean expected, long timeoutMs) {
        final long deadline = SystemClock.uptimeMillis() + Math.max(0L, timeoutMs);
        int confirmations = 0;

        while (true) {
            long scannedAt = SystemClock.uptimeMillis();
            SplitState state = inspectSplitState();
            boolean match = state.isSplit() == expected;

            if (match) {
                confirmations++;
                boolean unambiguous = expected && state.dividerPresent;
                if (unambiguous || confirmations >= STABLE_READS) return true;
            } else {
                confirmations = 0;
            }

            long remaining = deadline - SystemClock.uptimeMillis();
            if (remaining <= 0L) return match;

            if (!awaitWindowChange(Math.min(POLL_INTERVAL_MS, remaining))) {
                // Attente impossible (thread principal) ou interrompue : on
                // renvoie honnêtement la dernière lecture.
                return match;
            }

            // Anti-emballement : pendant une animation de split, les
            // événements s'enchaînent à un rythme élevé. Chaque lecture étant
            // un appel système, on n'en fait pas plus d'une par
            // MIN_SCAN_INTERVAL_MS.
            long idle = Math.min(MIN_SCAN_INTERVAL_MS - (SystemClock.uptimeMillis() - scannedAt),
                    deadline - SystemClock.uptimeMillis());
            if (idle > 0L && !sleepQuietly(idle)) return match;
        }
    }

    /**
     * Attend un changement de fenêtres, au plus {@code millis} ms.
     *
     * @return false si l'attente est IMPOSSIBLE (thread principal : on ne bloque
     *         jamais l'UI) ou si le thread a été interrompu.
     */
    private static boolean awaitWindowChange(long millis) {
        if (isMainThread()) {
            Log.w(TAG, "Attente de split demandée sur le thread principal : ignorée.");
            return false;
        }
        synchronized (WINDOW_LOCK) {
            try {
                WINDOW_LOCK.wait(Math.max(1L, millis));
                return true;
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
    }

    /**
     * Pause interruptible.
     *
     * @return false si le thread a été interrompu.
     */
    private static boolean sleepQuietly(long millis) {
        try {
            Thread.sleep(millis);
            return true;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    private static boolean isMainThread() {
        return Looper.myLooper() == Looper.getMainLooper();
    }

    // ------------------------------------------------- CRITÈRE N°2 (GÉOMÉTRIE)

    /**
     * Deuxième critère : deux fenêtres d'application occupent-elles deux
     * RÉGIONS DISTINCTES de l'écran ?
     *
     * Méthode PURE (aucune dépendance au service) donc entièrement testable.
     * On cherche un COUPLE de fenêtres qui :
     *   1. ne se recouvrent pas (au-delà d'une tolérance de {@value #MAX_OVERLAP}) ;
     *   2. ont chacune une taille crédible de « moitié » (ni popup, ni plein écran) ;
     *   3. couvrent ensemble une part significative de l'écran ;
     *   4. sont côte à côte (gauche/droite) ou empilées (haut/bas), alignées et
     *      séparées par un écart ≤ {@value #MAX_GAP} de l'écran (le diviseur).
     *
     * On itère sur les COUPLES : un split peut coexister avec d'autres fenêtres
     * d'application (activité translucide, assistant, fenêtre en cours de
     * fermeture…).
     *
     * @param appWindows bornes des fenêtres d'application (écran par défaut).
     * @param screen     bornes de l'écran (union des fenêtres) ; peut être vide.
     */
    static boolean hasTwoAppWindowsInDistinctRegions(List<Rect> appWindows, Rect screen) {
        if (appWindows == null || appWindows.size() < 2) return false;

        Rect bounds = (screen != null && !isDegenerate(screen)) ? screen : unionOf(appWindows);
        if (bounds == null || isDegenerate(bounds)) return false;

        final long screenArea = area(bounds);
        if (screenArea <= 0L) return false;

        // 1) Ne garder que les fenêtres de taille « crédible » pour une moitié.
        final List<Rect> halves = new ArrayList<>(appWindows.size());
        for (Rect r : appWindows) {
            if (r == null || isDegenerate(r)) continue;
            long a = area(r);
            if (a < screenArea * MIN_HALF_AREA) continue; // popup / dialogue / PiP
            if (a > screenArea * MAX_HALF_AREA) continue; // plein écran => pas un split
            halves.add(r);
        }
        if (halves.size() < 2) return false;

        // 2) Chercher un couple formant réellement un split.
        for (int i = 0; i < halves.size(); i++) {
            for (int j = i + 1; j < halves.size(); j++) {
                if (formSplitPair(halves.get(i), halves.get(j), bounds)) return true;
            }
        }
        return false;
    }

    /** Ce couple de fenêtres forme-t-il deux moitiés de split ? */
    private static boolean formSplitPair(Rect a, Rect b, Rect screen) {
        long areaA = area(a);
        long areaB = area(b);
        long smallest = Math.min(areaA, areaB);
        if (smallest <= 0L) return false;

        // Régions DISTINCTES : recouvrement négligeable (bordures, ombres).
        long overlap = overlapArea(a, b);
        if (overlap > smallest * MAX_OVERLAP) return false;

        // Surface cumulée crédible (élimine deux petites fenêtres flottantes).
        if ((areaA + areaB - overlap) < area(screen) * MIN_PAIR_AREA) return false;

        return sideBySide(a, b, screen) || stacked(a, b, screen);
    }

    /** Coupe VERTICALE : les deux fenêtres sont côte à côte (gauche | droite). */
    private static boolean sideBySide(Rect a, Rect b, Rect screen) {
        Rect left = (a.left <= b.left) ? a : b;
        Rect right = (left == a) ? b : a;

        if (right.left < left.right) return false;                       // se chevauchent
        if (right.left - left.right > screen.width() * MAX_GAP) return false; // trop éloignées
        if (!spansEnough(left.height(), screen.height())) return false;
        if (!spansEnough(right.height(), screen.height())) return false;
        return alignedEnough(left.top, left.bottom, right.top, right.bottom);
    }

    /** Coupe HORIZONTALE : les deux fenêtres sont empilées (haut / bas). */
    private static boolean stacked(Rect a, Rect b, Rect screen) {
        Rect top = (a.top <= b.top) ? a : b;
        Rect bottom = (top == a) ? b : a;

        if (bottom.top < top.bottom) return false;
        if (bottom.top - top.bottom > screen.height() * MAX_GAP) return false;
        if (!spansEnough(top.width(), screen.width())) return false;
        if (!spansEnough(bottom.width(), screen.width())) return false;
        return alignedEnough(top.left, top.right, bottom.left, bottom.right);
    }

    /** L'étendue couvre-t-elle assez l'axe perpendiculaire à la coupe ? */
    private static boolean spansEnough(int extent, int screenExtent) {
        return screenExtent > 0 && extent >= screenExtent * MIN_CROSS_SPAN;
    }

    /**
     * Les deux fenêtres sont-elles ALIGNÉES sur l'axe perpendiculaire ?
     * (deux moitiés partagent quasiment la même hauteur — ou largeur — utile.)
     */
    private static boolean alignedEnough(int startA, int endA, int startB, int endB) {
        int shared = Math.min(endA, endB) - Math.max(startA, startB);
        int smallest = Math.min(endA - startA, endB - startB);
        return smallest > 0 && shared >= smallest * MIN_ALIGNMENT;
    }

    private static boolean isDegenerate(Rect r) {
        return r.width() <= 0 || r.height() <= 0;
    }

    private static long area(Rect r) {
        return (long) r.width() * (long) r.height();
    }

    private static long overlapArea(Rect a, Rect b) {
        int w = Math.min(a.right, b.right) - Math.max(a.left, b.left);
        int h = Math.min(a.bottom, b.bottom) - Math.max(a.top, b.top);
        if (w <= 0 || h <= 0) return 0L;
        return (long) w * (long) h;
    }

    private static Rect unionOf(List<Rect> rects) {
        Rect union = null;
        for (Rect r : rects) {
            if (r == null || isDegenerate(r)) continue;
            if (union == null) {
                union = new Rect(r);
            } else {
                union.union(r);
            }
        }
        return union;
    }

    // ------------------------------------------------------- FENÊTRES / COMPAT

    /**
     * Recycle les {@link AccessibilityWindowInfo} obtenus via
     * {@link #getWindows()} — UNIQUEMENT sur API &lt; 33.
     *
     * Avant Android 13 (Tiramisu) ces objets proviennent d'un pool : les
     * abandonner fait fuir de la mémoire à chaque sondage, et une attente de
     * split en produit des dizaines. Depuis l'API 33, le pooling a été
     * supprimé : {@code recycle()} est déprécié et sans effet, l'appeler ne
     * ferait qu'ajouter du bruit (et lever IllegalStateException en cas de
     * double recyclage).
     */
    @SuppressWarnings("deprecation")
    private static void recycleWindows(List<AccessibilityWindowInfo> windows) {
        if (windows == null) return;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) return;
        for (AccessibilityWindowInfo window : windows) {
            if (window == null) continue;
            try {
                window.recycle();
            } catch (Throwable ignored) {
                // Déjà recyclé (doublon dans la liste) : sans conséquence.
            }
        }
    }

    /** La fenêtre est-elle sur l'écran principal ? (API 30+ : multi-écrans) */
    private static boolean isOnDefaultDisplay(AccessibilityWindowInfo window) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return true;
        try {
            return window.getDisplayId() == Display.DEFAULT_DISPLAY;
        } catch (Throwable t) {
            return true; // dans le doute, on garde la fenêtre
        }
    }

    /** Une fenêtre PiP n'est PAS une moitié de split (API 26+). */
    private static boolean isPictureInPicture(AccessibilityWindowInfo window) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return false;
        try {
            return window.isInPictureInPictureMode();
        } catch (Throwable t) {
            return false;
        }
    }

    // ------------------------------------------------------------- DÉTECTION

    /** Le service d'accessibilité est-il activé par l'utilisateur ? */
    public static boolean isEnabled(Context ctx) {
        if (instance != null) return true;
        if (ctx == null) return false;
        return isEnabledViaManager(ctx) || isEnabledViaSettings(ctx);
    }

    /** Source n°1 : la liste des services actifs vue par l'AccessibilityManager. */
    private static boolean isEnabledViaManager(Context ctx) {
        try {
            AccessibilityManager am = (AccessibilityManager)
                    ctx.getSystemService(Context.ACCESSIBILITY_SERVICE);
            if (am == null) return false;
            List<AccessibilityServiceInfo> enabled =
                    am.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK);
            if (enabled == null) return false;
            ComponentName ours = new ComponentName(ctx, SplitAccessibilityService.class);
            for (AccessibilityServiceInfo info : enabled) {
                if (info == null || info.getResolveInfo() == null
                        || info.getResolveInfo().serviceInfo == null) continue;
                ComponentName cn = new ComponentName(
                        info.getResolveInfo().serviceInfo.packageName,
                        info.getResolveInfo().serviceInfo.name);
                if (ours.equals(cn)
                        || SplitAccessibilityService.class.getName().equals(cn.getClassName())) {
                    return true;
                }
            }
        } catch (Throwable ignored) {}
        return false;
    }

    /**
     * Source n°2 : les réglages système.
     *
     * Filet de sécurité : sur certains OEM, {@code AccessibilityManager} renvoie
     * une liste vide alors que le service est bien coché (cache pas encore
     * rafraîchi juste après l'activation).
     */
    private static boolean isEnabledViaSettings(Context ctx) {
        try {
            String flat = Settings.Secure.getString(ctx.getContentResolver(),
                    Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
            if (TextUtils.isEmpty(flat)) return false;
            ComponentName ours = new ComponentName(ctx, SplitAccessibilityService.class);
            for (String entry : flat.split(":")) {
                if (TextUtils.isEmpty(entry)) continue;
                // unflattenFromString gère la forme abrégée « pkg/.Service ».
                ComponentName cn = ComponentName.unflattenFromString(entry.trim());
                if (cn != null && ours.equals(cn)) return true;
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
