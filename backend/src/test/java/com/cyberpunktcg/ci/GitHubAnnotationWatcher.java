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

    /**
     * Compteur d'échecs : chaque annotation est numérotée, donc unique — GitHub
     * déduplique les annotations identiques, ce qui masquerait sinon le nombre
     * réel de tests en échec. Le dernier numéro publié donne le total.
     */
    private static final java.util.concurrent.atomic.AtomicInteger FAILURES =
            new java.util.concurrent.atomic.AtomicInteger();

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
        String method = context.getTestMethod().map(java.lang.reflect.Method::getName).orElse("?");
        int number = FAILURES.incrementAndGet();
        String title = "#" + number + " " + testClass.getSimpleName() + "." + method;

        // Les propriétés d'une commande de workflow sont séparées par des VIRGULES
        // (`file=…,line=…,title=…`) : un autre séparateur casse leur analyse.
        StringBuilder properties = new StringBuilder("title=").append(escape(title));
        int line = locateLine(testClass, cause);
        if (line > 0) {
            properties.append(",file=").append(escape(sourcePath(testClass.getName())))
                    .append(",line=").append(line);
        }
        String message = "#" + number + " " + testClass.getSimpleName() + "#" + method
                + (line > 0 ? " (ligne " + line + ")" : "") + " | " + describe(cause);

        System.out.println("::" + level + " " + properties + "::" + escape(message));
        System.out.flush();
    }

    /**
     * Ligne du test dans son fichier source : première frame de la pile qui
     * appartient à la classe de test (les frames AssertJ/JUnit ne correspondent
     * à aucun fichier du dépôt).
     */
    private int locateLine(Class<?> testClass, Throwable cause) {
        if (cause == null) {
            return -1;
        }
        String name = testClass.getName();
        for (StackTraceElement element : cause.getStackTrace()) {
            if (name.equals(element.getClassName()) && element.getLineNumber() > 0) {
                return element.getLineNumber();
            }
        }
        return -1;
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
