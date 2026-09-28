package com.ninja.splitlauncher;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;

/**
 * Exécution de commandes shell, de façon ROBUSTE et NON BLOQUANTE.
 *
 * Correctifs par rapport à la v1.1 :
 *   - stderr est fusionné dans stdout (redirectErrorStream) : plus de risque de
 *     blocage sur un tampon stderr plein, et on peut détecter les erreurs ;
 *   - un code de sortie 0 ne suffit plus : on inspecte aussi la sortie à la
 *     recherche de "Error:" / "Exception" (fréquent avec "am start") ;
 *   - un délai maximal est appliqué (waitFor avec timeout) puis destruction
 *     forcée : plus de blocage possible.
 *
 * Cette classe ne fait AUCUNE hypothèse sur le canal (root/Shizuku) : elle
 * reçoit la commande complète à exécuter. Les appelants l'exécutent depuis un
 * thread d'arrière-plan (voir SplitEngine).
 */
public final class ShellExecutor {

    /** Résultat d'une exécution. */
    public static final class Result {
        public final boolean ok;
        public final String output;
        public final int exitCode;

        public Result(boolean ok, String output, int exitCode) {
            this.ok = ok;
            this.output = output;
            this.exitCode = exitCode;
        }
    }

    private static final long TIMEOUT_SECONDS = 12;

    private ShellExecutor() {}

    /** Exécute une commande via "sh -c". Bloquant : à appeler hors thread principal. */
    public static Result run(String command) {
        Process process = null;
        try {
            ProcessBuilder pb = new ProcessBuilder("sh", "-c", command);
            pb.redirectErrorStream(true); // stderr -> stdout (pas de blocage)
            process = pb.start();

            final Process p = process;
            final StringBuilder sb = new StringBuilder();
            Thread reader = new Thread(() -> {
                try (BufferedReader br = new BufferedReader(
                        new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
                    String line;
                    while ((line = br.readLine()) != null) {
                        sb.append(line).append('\n');
                    }
                } catch (Exception ignored) {}
            });
            reader.setDaemon(true);
            reader.start();

            boolean finished = process.waitFor(TIMEOUT_SECONDS, TimeUnit.SECONDS);
            if (!finished) {
                process.destroyForcibly();
                return new Result(false, sb.toString().trim() + "\n[timeout]", -1);
            }
            reader.join(500);

            int exit = process.exitValue();
            String output = sb.toString().trim();
            boolean ok = exit == 0 && !looksLikeError(output);
            return new Result(ok, output, exit);
        } catch (Exception e) {
            return new Result(false, String.valueOf(e.getMessage()), -1);
        } finally {
            if (process != null) process.destroy();
        }
    }

    /**
     * "am start" renvoie souvent "Error: ..." avec un code de sortie 0.
     *
     * Correctif v1.3 : on ne cherche plus les sous-chaînes ambiguës
     * ("exception", "not found") qui provoquaient des FAUX POSITIFS sur des
     * sorties normales. On cible désormais "Error:" EN DÉBUT DE LIGNE (format
     * exact des échecs de "am start"), plus quelques messages d'échec non
     * ambigus.
     */
    static boolean looksLikeError(String output) {
        if (output == null) return false;
        String lower = output.toLowerCase();

        // 1) "error:" en début de ligne (après espaces éventuels).
        for (String line : lower.split("\n")) {
            if (line.trim().startsWith("error:")) return true;
        }

        // 2) messages d'échec non ambigus.
        return lower.contains("permission denied")
                || lower.contains("unable to")
                || lower.contains("does not exist")
                || lower.contains("no such file")
                || lower.contains("unknown package");
    }
}
