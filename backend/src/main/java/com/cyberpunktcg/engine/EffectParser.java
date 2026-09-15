package com.cyberpunktcg.engine;

import com.cyberpunktcg.domain.game.CardInstance;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Interprète les capacités ({@code abilities}) d'une carte depuis leur forme JSON.
 *
 * <p>Trois niveaux, dans cet ordre :</p>
 * <ol>
 *   <li><strong>Mini-langage structuré</strong> (prioritaire, jamais ambigu) :
 *   {@code TRIGGER:EFFET:VALEUR[:CIBLE]}, par exemple
 *   {@code ON_PLAY:DRAW:2}, {@code FLIP:GRANT_POWER:2:SELF},
 *   {@code ON_ATTACK:DAMAGE:3:TARGET_UNIT}. Cible par défaut selon l'effet
 *   (voir {@link #defaultTarget(EffectType)}).</li>
 *   <li><strong>Analyse du texte naturel du catalogue</strong> (feature 6.5) : le
 *   texte est découpé en <em>segments</em> par les marqueurs officiels
 *   ({@code {Play}}, {@code {Attack}}, {@code {Flip}}, {@code {Quick}},
 *   {@code {Defeated}}…), puis chaque segment est confronté à une liste de motifs
 *   <strong>non ambigus</strong> (voir {@link #NATURAL_EFFECTS}).</li>
 *   <li><strong>Repli</strong> : heuristique historique « draw N ».</li>
 * </ol>
 *
 * <p><strong>Sécurité d'abord</strong> : tout ce qui n'est pas reconnu avec
 * certitude est ignoré silencieusement. Sont explicitement écartés : les
 * capacités activées ({@code {Spend} 1 €$ …}) qui exigent de payer un coût, les
 * capacités modales (« Choose one effect … // … »), les capacités
 * conditionnelles (« You may… », « If you have more ☆… », « When a friendly
 * Unit steals… »), les rappels de mots-clés entre parenthèses et les effets
 * globaux du type « Defeat all other Units ». Une capacité ignorée ne fait
 * jamais échouer une partie — elle est documentée comme limite V1
 * ({@code docs/RULE-ENGINE.md} §5.2 et §11).</p>
 */
public final class EffectParser {

    /** Marqueurs officiels du texte : {@code {Play}}, {@code {Go Solo}}… */
    private static final Pattern TRIGGER_MARKER = Pattern.compile("\\{([A-Za-z ]+)\\}");

    /** Motifs reconnus dans les textes naturels (segment par segment). */
    private static final Pattern DRAW_PATTERN =
            Pattern.compile("(?i)\\bdraw (\\d+)\\b");
    /** « Defeat a rival Unit [with power N or less]. » (fin de proposition exigée). */
    private static final Pattern DEFEAT_RIVAL_UNIT = Pattern.compile(
            "(?i)\\bdefeat a rival unit(?: with power (\\d+) or less)?(?=\\s*(?:\\.|,|$|then\\b|if\\b))");
    /** « Give a friendly Unit +N power [this turn]. » */
    private static final Pattern GRANT_FRIENDLY_UNIT = Pattern.compile(
            "(?i)\\bgive a friendly unit \\+(\\d+) power(?: this turn)?(?=\\s*(?:\\.|,|$))");
    /** « Increase a Gig by up to N. » */
    private static final Pattern BOOST_GIG = Pattern.compile(
            "(?i)\\bincrease a gig by up to (\\d+)(?=\\s*(?:\\.|,|$))");
    /** « Decrease a rival Gig by up to N. » */
    private static final Pattern REDUCE_GIG = Pattern.compile(
            "(?i)\\bdecrease a rival gig by up to (\\d+)(?=\\s*(?:\\.|,|$))");

    /** Marqueurs qui n'ouvrent aucune fenêtre de résolution implémentée en V1. */
    private static final String[] SKIPPED_MARKERS = {"spend", "call", "go solo", "blocker"};

    /** Expressions qui trahissent une capacité conditionnelle (non résolue en V1). */
    private static final String[] CONDITIONAL_HINTS = {
            "you may", "if you do", "if you have", "if a ", "if it", "if your",
            "whenever", "each time", "the first time", "at the start of", "choose one effect", "//"
    };

    private EffectParser() {
        // Classe utilitaire non instanciable.
    }

    /**
     * Convertit toutes les capacités d'un exemplaire en effets structurés.
     * Les capacités non reconnues sont ignorées (jamais d'exception).
     */
    public static List<GameEffect> parseAbilities(CardInstance source) {
        List<GameEffect> effects = new ArrayList<GameEffect>();
        for (String ability : source.getAbilities()) {
            GameEffect effect = parseAbility(ability);
            if (effect != null) {
                effects.add(effect);
                continue;
            }
            effects.addAll(parseNatural(ability));
        }
        return effects;
    }

    /**
     * Analyse une capacité au format {@code TRIGGER:EFFET:VALEUR[:CIBLE]}.
     *
     * @return l'effet, ou {@code null} si le texte ne suit pas le format
     */
    public static GameEffect parseAbility(String abilityText) {
        if (abilityText == null) {
            return null;
        }
        String[] parts = abilityText.trim().split("\\s*:\\s*");
        if (parts.length < 3 || parts.length > 4) {
            return null;
        }
        TriggerType trigger;
        EffectType type;
        int value;
        try {
            trigger = TriggerType.valueOf(parts[0].trim().toUpperCase());
            type = EffectType.valueOf(parts[1].trim().toUpperCase());
            value = Integer.parseInt(parts[2].trim());
        } catch (IllegalArgumentException error) {
            return null;
        }
        if (value < 0) {
            return null;
        }
        EffectTarget target;
        if (parts.length == 4) {
            try {
                target = EffectTarget.valueOf(parts[3].trim().toUpperCase());
            } catch (IllegalArgumentException error) {
                return null;
            }
        } else {
            target = defaultTarget(type);
        }
        return new GameEffect(trigger, type, value, target);
    }

    /** Cible par défaut d'un effet quand la capacité ne la précise pas. */
    public static EffectTarget defaultTarget(EffectType type) {
        switch (type) {
            case DAMAGE:
                return EffectTarget.TARGET_UNIT;
            case HEAL:
                return EffectTarget.SELF;
            case DRAW:
                return EffectTarget.SELF_PLAYER;
            case GRANT_POWER:
                return EffectTarget.SELF;
            case STEAL_GIG:
                return EffectTarget.RIVAL_PLAYER;
            case REDUCE_COST:
                return EffectTarget.SELF_PLAYER;
            case DEFEAT_UNIT:
                return EffectTarget.RIVAL_UNIT;
            case BOOST_GIG:
                return EffectTarget.SELF_PLAYER;
            case REDUCE_GIG:
                return EffectTarget.RIVAL_PLAYER;
            default:
                return EffectTarget.SELF;
        }
    }

    /**
     * Analyse un texte naturel du catalogue : découpage en segments puis
     * reconnaissance des motifs non ambigus.
     *
     * @param abilityText texte imprimé ({@code text} ou élément de {@code abilities})
     * @return la liste (éventuellement vide) des effets reconnus
     */
    public static List<GameEffect> parseNatural(String abilityText) {
        if (abilityText == null || abilityText.isBlank()) {
            return Collections.emptyList();
        }
        List<GameEffect> effects = new ArrayList<GameEffect>();
        for (Segment segment : split(abilityText)) {
            effects.addAll(match(segment.trigger, segment.body));
        }
        return effects;
    }

    /** Repli historique conservé pour compatibilité : « draw N » → {@code ON_PLAY:DRAW:N}. */
    static GameEffect parseHeuristic(String abilityText) {
        if (abilityText == null) {
            return null;
        }
        Matcher draw = DRAW_PATTERN.matcher(abilityText);
        if (draw.find()) {
            int value = Integer.parseInt(draw.group(1));
            return new GameEffect(TriggerType.ON_PLAY, EffectType.DRAW, value, EffectTarget.SELF_PLAYER);
        }
        return null;
    }

    // ------------------------------------------------------------------
    // Découpage en segments
    // ------------------------------------------------------------------

    /**
     * Découpe un texte imprimé en segments {@code (déclencheur, corps de texte)}.
     * Visible pour les tests.
     */
    static List<Segment> split(String text) {
        List<Segment> segments = new ArrayList<Segment>();
        Matcher marker = TRIGGER_MARKER.matcher(text);
        List<int[]> markers = new ArrayList<int[]>();
        List<String> names = new ArrayList<String>();
        while (marker.find()) {
            markers.add(new int[]{marker.start(), marker.end()});
            names.add(triggerOf(marker.group(1)));
        }
        if (markers.isEmpty()) {
            addPreamble(segments, text);
            return segments;
        }
        addPreamble(segments, text.substring(0, markers.get(0)[0]));
        for (int i = 0; i < markers.size(); i++) {
            String body = text.substring(markers.get(i)[1],
                    i + 1 < markers.size() ? markers.get(i + 1)[0] : text.length());
            String trigger = names.get(i);
            if (trigger != null) {
                segments.add(new Segment(trigger, body));
            }
        }
        return segments;
    }

    private static void addPreamble(List<Segment> segments, String preamble) {
        if (preamble == null || preamble.isBlank()) {
            return;
        }
        String text = preamble.trim();
        TriggerType trigger = null;
        String body = text;
        String lower = text.toLowerCase();
        if (lower.startsWith("at the end of your turn")) {
            trigger = TriggerType.ON_TURN_END;
            body = afterComma(text);
        } else if (lower.startsWith("when you play this")) {
            trigger = TriggerType.ON_PLAY;
            body = afterComma(text);
        }
        if (trigger == null) {
            // Texte d'ouverture conditionnel : jamais interprété en V1.
            if (containsAny(lower, CONDITIONAL_HINTS) || containsAny(lower, new String[]{"when", "if ", "that stole"})) {
                return;
            }
            trigger = TriggerType.ON_PLAY;
        }
        segments.add(new Segment(trigger.name(), body));
    }

    private static String afterComma(String text) {
        int comma = text.indexOf(',');
        return comma < 0 ? text : text.substring(comma + 1);
    }

    /** Traduit un marqueur {@code {X}} en déclencheur, ou {@code null} s'il est ignoré. */
    private static String triggerOf(String marker) {
        String key = marker.trim().toLowerCase();
        for (String skipped : SKIPPED_MARKERS) {
            if (key.equals(skipped)) {
                return null;
            }
        }
        switch (key) {
            case "play":
                return TriggerType.ON_PLAY.name();
            case "attack":
                return TriggerType.ON_ATTACK.name();
            case "flip":
                return TriggerType.FLIP.name();
            case "quick":
                return TriggerType.QUICK.name();
            case "defeated":
                return TriggerType.ON_DEATH.name();
            default:
                return null;
        }
    }

    // ------------------------------------------------------------------
    // Reconnaissance des motifs d'un segment
    // ------------------------------------------------------------------

    private static List<GameEffect> match(String triggerName, String body) {
        if (body == null || body.isBlank()) {
            return Collections.emptyList();
        }
        String text = body.trim();
        if (text.startsWith("(")) {
            // Rappel de mot-clé (« (Pay this Legend's cost …) ») : informatif.
            return Collections.emptyList();
        }
        TriggerType trigger;
        try {
            trigger = TriggerType.valueOf(triggerName);
        } catch (IllegalArgumentException error) {
            return Collections.emptyList();
        }
        String lower = text.toLowerCase();
        if (containsAny(lower, CONDITIONAL_HINTS)) {
            return Collections.emptyList();
        }
        List<GameEffect> effects = new ArrayList<GameEffect>();

        Matcher defeat = DEFEAT_RIVAL_UNIT.matcher(text);
        if (defeat.find()) {
            int cap = defeat.group(1) == null ? 0 : Integer.parseInt(defeat.group(1));
            effects.add(new GameEffect(trigger, EffectType.DEFEAT_UNIT, cap, EffectTarget.RIVAL_UNIT));
        }
        Matcher grant = GRANT_FRIENDLY_UNIT.matcher(text);
        if (grant.find()) {
            effects.add(new GameEffect(trigger, EffectType.GRANT_POWER,
                    Integer.parseInt(grant.group(1)), EffectTarget.FRIENDLY_UNIT));
        }
        Matcher boost = BOOST_GIG.matcher(text);
        if (boost.find()) {
            effects.add(new GameEffect(trigger, EffectType.BOOST_GIG,
                    Integer.parseInt(boost.group(1)), EffectTarget.SELF_PLAYER));
        }
        Matcher reduce = REDUCE_GIG.matcher(text);
        if (reduce.find()) {
            effects.add(new GameEffect(trigger, EffectType.REDUCE_GIG,
                    Integer.parseInt(reduce.group(1)), EffectTarget.RIVAL_PLAYER));
        }
        Matcher draw = DRAW_PATTERN.matcher(text);
        if (draw.find()) {
            effects.add(new GameEffect(trigger, EffectType.DRAW,
                    Integer.parseInt(draw.group(1)), EffectTarget.SELF_PLAYER));
        }
        return effects;
    }

    private static boolean containsAny(String lowerText, String[] needles) {
        for (String needle : needles) {
            if (lowerText.contains(needle)) {
                return true;
            }
        }
        return false;
    }

    /** Un segment de texte imprimé et le déclencheur qui l'ouvre. */
    static final class Segment {
        private final String trigger;
        private final String body;

        Segment(String trigger, String body) {
            this.trigger = trigger;
            this.body = body;
        }

        String trigger() {
            return trigger;
        }

        String body() {
            return body;
        }
    }
}
