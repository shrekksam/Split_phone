package com.ninja.splitlauncher;

import android.content.Context;
import android.os.RemoteException;

import androidx.annotation.Keep;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;

/**
 * Implémentation du « UserService » Shizuku.
 *
 * Ce service ne tourne PAS dans le processus de l'application : Shizuku le
 * démarre dans un processus séparé avec l'identité shell (uid 2000) ou root
 * (uid 0). Il exécute donc les commandes « am start --windowingMode » avec les
 * privilèges nécessaires, SANS root côté application.
 *
 * Remarque : ce service n'a PAS besoin d'être déclaré dans le manifeste (il est
 * instancié par le serveur Shizuku), mais il DOIT être conservé par R8.
 */
public class ShizukuUserService extends IUserService.Stub {

    /** Constructeur par défaut (obligatoire). */
    public ShizukuUserService() {
    }

    /**
     * Constructeur avec Context : disponible depuis Shizuku v13.
     * {@link Keep} empêche R8 de le supprimer (appelé par réflexion).
     */
    @Keep
    public ShizukuUserService(Context context) {
        // Le Context fourni par Shizuku n'est pas un Context applicatif complet.
    }

    /** Méthode réservée par Shizuku : arrêt du service. */
    @Override
    public void destroy() {
        System.exit(0);
    }

    @Override
    public String exec(String command) throws RemoteException {
        Process process = null;
        try {
            ProcessBuilder pb = new ProcessBuilder("sh", "-c", command);
            pb.redirectErrorStream(true);
            process = pb.start();

            final Process p = process;
            final StringBuilder sb = new StringBuilder();
            Thread reader = new Thread(() -> {
                try (BufferedReader br = new BufferedReader(
                        new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
                    String line;
                    while ((line = br.readLine()) != null) sb.append(line).append('\n');
                } catch (Exception ignored) {}
            });
            reader.setDaemon(true);
            reader.start();

            boolean finished = process.waitFor(12, TimeUnit.SECONDS);
            if (!finished) {
                process.destroyForcibly();
                return "exit=-1\n" + sb.toString().trim() + "\n[timeout]";
            }
            reader.join(500);
            int exit = process.exitValue();
            return "exit=" + exit + "\n" + sb.toString().trim();
        } catch (Throwable t) {
            return "exit=-1\nerror: " + t.getMessage();
        } finally {
            if (process != null) process.destroy();
        }
    }
}
