package com.ninja.splitlauncher;

import android.content.ComponentName;
import android.content.Context;
import android.content.ServiceConnection;
import android.content.pm.PackageManager;
import android.os.IBinder;

import java.util.concurrent.atomic.AtomicBoolean;

import rikka.shizuku.Shizuku;

/**
 * Bridge vers Shizuku (https://shizuku.rikka.app/).
 *
 * Shizuku permet d'exécuter des commandes shell avec l'identité « shell »
 * (uid 2000) SANS root, ce qui est la méthode privilégiée pour imposer un vrai
 * multi-fenêtres via « am start --windowingMode ».
 *
 * Correctif v1.3 : on utilise l'API PUBLIQUE directement
 * ({@code Shizuku.pingBinder()}, {@code checkSelfPermission()},
 * {@code requestPermission()}, {@code bindUserService()}) au lieu de la
 * RÉFLEXION sur {@code Shizuku.newProcess} — méthode dépréciée puis retirée de
 * l'API publique (v13). Les commandes sont exécutées par un
 * {@link ShizukuUserService} (méthode recommandée par Shizuku).
 *
 * Correctif v1.4 : le drapeau {@code binding} est ATOMIQUE et remis à false
 * après un échec/timeout de connexion. Avant, si {@code onServiceConnected}
 * n'arrivait jamais, {@code binding} restait à true : chaque appel suivant
 * attendait 5 s puis échouait SANS JAMAIS se reconnecter. On libère désormais la
 * liaison (unbind) pour permettre une reconnexion au prochain appel.
 *
 * Si Shizuku n'est pas installé / démarré / autorisé, toutes les méthodes
 * renvoient une valeur neutre et le moteur retombe proprement sur le mode
 * assisté (ou root si l'utilisateur l'a explicitement activé).
 */
public final class ShizukuBridge {

    /** Délai maximal d'attente de la connexion du UserService. */
    private static final long CONNECT_TIMEOUT_MS = 5000;

    private static Context appContext;

    private static volatile IUserService service;
    private static final AtomicBoolean binding = new AtomicBoolean(false);

    private ShizukuBridge() {}

    /** À appeler une fois au démarrage de l'application (voir SplitApp). */
    public static void init(Context ctx) {
        if (ctx != null) appContext = ctx.getApplicationContext();
    }

    // ------------------------------------------------------------- ÉTAT / PERM

    /** Le binder Shizuku est-il vivant ? */
    public static boolean isBinderAlive() {
        try {
            return Shizuku.pingBinder();
        } catch (Throwable t) {
            return false;
        }
    }

    /** La permission Shizuku est-elle accordée ? */
    public static boolean hasPermission() {
        try {
            if (Shizuku.isPreV11()) return false;
            return Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED;
        } catch (Throwable t) {
            return false;
        }
    }

    /** Shizuku est-il installé, démarré ET la permission accordée ? */
    public static boolean isAvailable() {
        return isBinderAlive() && hasPermission();
    }

    /** Demande la permission Shizuku (à appeler depuis l'UI). */
    public static void requestPermission(int requestCode) {
        try {
            Shizuku.requestPermission(requestCode);
        } catch (Throwable ignored) {}
    }

    /** Enregistre un écouteur du résultat de la demande de permission. */
    public static void addPermissionListener(Shizuku.OnRequestPermissionResultListener l) {
        try {
            Shizuku.addRequestPermissionResultListener(l);
        } catch (Throwable ignored) {}
    }

    /** Retire l'écouteur du résultat de la demande de permission. */
    public static void removePermissionListener(Shizuku.OnRequestPermissionResultListener l) {
        try {
            Shizuku.removeRequestPermissionResultListener(l);
        } catch (Throwable ignored) {}
    }

    // ------------------------------------------------------- SERVICE PRIVILÉGIÉ

    private static Shizuku.UserServiceArgs serviceArgs() {
        String pkg = appContext != null
                ? appContext.getPackageName()
                : "com.ninja.splitlauncher";
        return new Shizuku.UserServiceArgs(
                new ComponentName(pkg, ShizukuUserService.class.getName()))
                .daemon(false)
                .processNameSuffix("shell")
                .debuggable(false)
                .version(1);
    }

    private static final ServiceConnection CONNECTION = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder binder) {
            binding.set(false);
            if (binder != null && binder.pingBinder()) {
                service = IUserService.Stub.asInterface(binder);
            } else {
                service = null;
            }
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            service = null;
            binding.set(false);
        }
    };

    /** Lie (si nécessaire) le UserService et attend sa connexion. */
    private static boolean ensureService() {
        if (service != null) return true;
        if (!isAvailable()) return false;

        if (binding.get()) {
            // Une liaison est déjà en cours : on attend simplement.
            if (awaitService(CONNECT_TIMEOUT_MS)) return true;
            // Correctif v1.4 : la liaison n'a jamais abouti. On la LIBÈRE pour
            // pouvoir réessayer au prochain appel (sinon blocage définitif).
            binding.set(false);
            unbindQuietly();
            return false;
        }

        try {
            binding.set(true);
            Shizuku.bindUserService(serviceArgs(), CONNECTION);
        } catch (Throwable t) {
            binding.set(false);
            return false;
        }

        if (awaitService(CONNECT_TIMEOUT_MS)) return true;

        // Timeout : on remet l'état à zéro et on libère la liaison.
        binding.set(false);
        unbindQuietly();
        return false;
    }

    private static boolean awaitService(long timeoutMs) {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (service == null && System.currentTimeMillis() < deadline) {
            try {
                Thread.sleep(50);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        return service != null;
    }

    /**
     * Exécute une commande via le UserService Shizuku (uid shell/root).
     * Bloquant : à appeler hors thread principal.
     */
    public static ShellExecutor.Result run(String command) {
        if (!ensureService()) {
            return new ShellExecutor.Result(false, "Shizuku: service indisponible", -1);
        }
        try {
            return parseUserServiceOutput(service.exec(command));
        } catch (Throwable t) {
            // La liaison est probablement morte : on la libère pour reconnecter.
            service = null;
            binding.set(false);
            return new ShellExecutor.Result(false, "Shizuku: " + t.getMessage(), -1);
        }
    }

    /**
     * Analyse la réponse du UserService au format « exit=&lt;code&gt;\n&lt;sortie&gt; ».
     * Méthode PURE et testable (voir ShellParsingTest).
     */
    static ShellExecutor.Result parseUserServiceOutput(String raw) {
        if (raw == null) {
            return new ShellExecutor.Result(false, "Shizuku: réponse vide", -1);
        }
        int nl = raw.indexOf('\n');
        String header = nl >= 0 ? raw.substring(0, nl) : raw;
        String output = nl >= 0 ? raw.substring(nl + 1) : "";
        int exit = -1;
        if (header.startsWith("exit=")) {
            try {
                exit = Integer.parseInt(header.substring(5).trim());
            } catch (NumberFormatException ignored) {}
        }
        boolean ok = exit == 0 && !ShellExecutor.looksLikeError(output);
        return new ShellExecutor.Result(ok, output, exit);
    }

    /** Déconnecte le UserService (ex : à la fermeture). */
    public static void unbind() {
        unbindQuietly();
        service = null;
        binding.set(false);
    }

    private static void unbindQuietly() {
        try {
            Shizuku.unbindUserService(serviceArgs(), CONNECTION, true);
        } catch (Throwable ignored) {}
    }
}
