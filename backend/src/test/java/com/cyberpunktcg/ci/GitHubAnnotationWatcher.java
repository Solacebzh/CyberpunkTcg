package com.cyberpunktcg.ci;

import org.junit.jupiter.api.extension.ExtensionContext;
import org.junit.jupiter.api.extension.TestWatcher;

/**
 * Republie chaque <strong>échec de test</strong> sous forme d'annotation GitHub
 * Actions (commande de workflow {@code ::error file=…,line=…,title=…::message}).
 *
 * <p>Pourquoi : les journaux bruts d'un run Actions sont servis par des hôtes
 * blob qui ne sont pas toujours joignables (environnements restreints), alors
 * que les annotations, elles, restent lisibles via l'API
 * {@code GET /repos/{owner}/{repo}/check-runs/{check_run_id}/annotations}. Cet
 * observateur rend donc un build rouge diagnosticable sans télécharger le
 * journal complet.</p>
 *
 * <p>Sortie silence quand tout est vert : aucune annotation n'est émise en cas
 * de succès (seuls {@code testFailed} et {@code testAborted} parlent). La ligne
 * remonte dans le journal parce que Surefire retranscrit la sortie standard des
 * tests, où le runner GitHub détecte les commandes de workflow.</p>
 *
 * <p>Enregistrement : automatique via {@code ServiceLoader}
 * ({@code META-INF/services/org.junit.jupiter.api.extension.Extension} +
 * {@code junit.jupiter.extensions.autodetection.enabled=true} dans
 * {@code junit-platform.properties}). Aucune annotation {@code @ExtendWith}
 * n'est nécessaire dans les classes de test.</p>
 */
public class GitHubAnnotationWatcher implements TestWatcher {

    /** Longueur maximale du message publié (les annotations restent lisibles). */
    private static final int MAX_MESSAGE = 600;

    @Override
    public void testFailed(ExtensionContext context, Throwable cause) {
        publish("error", context, cause);
    }

    @Override
    public void testAborted(ExtensionContext context, Throwable cause) {
        publish("warning", context, cause);
    }

    /** Émet une commande de workflow GitHub pour un test non passé. */
    private void publish(String level, ExtensionContext context, Throwable cause) {
        Class<?> testClass = context.getRequiredTestClass();
        String title = testClass.getSimpleName() + " — " + context.getDisplayName();
        String message = describe(cause);

        StringBuilder command = new StringBuilder("::").append(level);
        String location = locate(testClass, cause);
        if (location != null) {
            command.append(' ').append(location);
        }
        command.append(" title=").append(escape(title)).append("::").append(escape(message));
        System.out.println(command);
        System.out.flush();
    }

    /**
     * Retrouve la ligne du test dans son fichier source (chemin relatif à la
     * racine du dépôt, seul format accepté par les annotations).
     */
    private String locate(Class<?> testClass, Throwable cause) {
        if (cause == null) {
            return null;
        }
        String name = testClass.getName();
        for (StackTraceElement element : cause.getStackTrace()) {
            if (name.equals(element.getClassName()) && element.getLineNumber() > 0) {
                return "file=" + sourcePath(name) + ",line=" + element.getLineNumber();
            }
        }
        StackTraceElement[] trace = cause.getStackTrace();
        if (trace.length > 0 && trace[0].getLineNumber() > 0) {
            return "file=" + sourcePath(trace[0].getClassName()) + ",line=" + trace[0].getLineNumber();
        }
        return null;
    }

    /** {@code com.x.YTest} → {@code backend/src/test/java/com/x/YTest.java}. */
    private String sourcePath(String className) {
        return "backend/src/test/java/" + className.replace('.', '/') + ".java";
    }

    /** Message lisible : type d'erreur + premier message non vide de la chaîne de causes. */
    private String describe(Throwable cause) {
        if (cause == null) {
            return "échec sans exception";
        }
        StringBuilder text = new StringBuilder(cause.getClass().getSimpleName());
        Throwable current = cause;
        int guard = 0;
        while (current != null && guard++ < 5) {
            String message = current.getMessage();
            if (message != null && !message.isBlank()) {
                text.append(" : ").append(message.replaceAll("\\s+", " ").trim());
                break;
            }
            current = current.getCause();
        }
        String flat = text.toString();
        return flat.length() <= MAX_MESSAGE ? flat : flat.substring(0, MAX_MESSAGE) + "…";
    }

    /** Échappement GitHub : {@code %0A}, {@code %0D}, {@code %3A}, {@code %2C}. */
    private String escape(String value) {
        return value.replace("%", "%25")
                .replace("\r", "%0D")
                .replace("\n", "%0A")
                .replace(":", "%3A")
                .replace(",", "%2C");
    }
}
