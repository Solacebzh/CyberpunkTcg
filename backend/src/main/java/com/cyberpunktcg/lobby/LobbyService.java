package com.cyberpunktcg.lobby;

import com.cyberpunktcg.domain.deck.DeckService;
import com.cyberpunktcg.domain.game.GameState;
import com.cyberpunktcg.service.GameService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

/**
 * Gestion mémoire des salons d'attente.
 *
 * <p>Un joueur ne peut occuper qu'un seul salon à la fois (index
 * {@code roomByPseudo}). Dès que deux joueurs sont assis, la partie est
 * créée automatiquement via {@link GameService#createGame(String, String,
 * List, List)} et le salon passe {@link RoomStatus#PLAYING}.</p>
 *
 * <p>Mini-Feature 9D « Lobby & Deck de Jeu » : un deck sauvegardé est
 * désormais <strong>obligatoire</strong> pour rejoindre ou créer un salon.
 * Le salon stocke l'identifiant du deck ({@code deckId}) — pas la liste
 * des cartes. Le service vérifie que le deck existe, qu'il appartient au
 * joueur (username dérivé du pseudo STOMP), et résout la liste de cartes
 * au démarrage de la partie. Un joueur sans deck se voit refuser l'accès
 * ({@link LobbyException} {@code NO_DECK_SELECTED}).</p>
 */
@Service
public class LobbyService {

    private static final Logger log = LoggerFactory.getLogger(LobbyService.class);

    /** Lettres/chiffres sans ambiguïté (pas de 0/O, 1/I). */
    private static final char[] CODE_ALPHABET = "ABCDEFGHJKMNPQRSTUVWXYZ23456789".toCharArray();
    private static final int CODE_LENGTH = 6;
    private static final Pattern PSEUDO_PATTERN = Pattern.compile("^[A-Za-z0-9_\\-À-ÿ]{2,20}$");

    private final Map<String, Room> roomsByCode = new ConcurrentHashMap<>();
    /** Pseudo → code du salon courant, pour empêcher les doubles sièges. */
    private final Map<String, String> roomByPseudo = new ConcurrentHashMap<>();

    private final SecureRandom random = new SecureRandom();
    private final GameService gameService;
    private final DeckService deckService;

    public LobbyService(GameService gameService, DeckService deckService) {
        this.gameService = gameService;
        this.deckService = deckService;
    }

    /** Valide la syntaxe d'un pseudo (2 à 20 caractères). */
    public static void requireValidPseudo(String pseudo) {
        if (pseudo == null || !PSEUDO_PATTERN.matcher(pseudo).matches()) {
            throw new LobbyException("INVALID_PSEUDO",
                    "Le pseudo doit contenir entre 2 et 20 caractères (lettres, chiffres, _ ou -)");
        }
    }

    /**
     * Vérifie qu'un deck existe et appartient au joueur. La résolution des
     * cartes est <em>différée</em> au démarrage de la partie — le salon ne
     * stocke que l'identifiant du deck, pas la liste de cartes.
     *
     * @param username pseudo STOMP — sert d'identifiant de compte pour la
     *                 vérification de propriété. Le client frontend doit
     *                 utiliser le même pseudo que son compte JWT.
     * @param deckId   identifiant du deck à charger
     * @throws LobbyException {@code NO_DECK_SELECTED} si l'identifiant est nul ;
     *                        {@code DECK_NOT_OWNED} si le deck n'existe pas ou
     *                        n'appartient pas au joueur (le serveur ne révèle pas
     *                        l'existence du deck d'un autre compte).
     */
    public void requireOwnedDeck(String username, Long deckId) {
        if (deckId == null) {
            throw new LobbyException("NO_DECK_SELECTED",
                    "Sélectionne un deck sauvegardé avant de rejoindre la partie");
        }
        try {
            if (deckService.getDeck(username, deckId).cardIds().isEmpty()) {
                throw new LobbyException("DECK_INVALID",
                        "Le deck sélectionné est vide");
            }
        } catch (ResponseStatusException error) {
            // 404 = deck introuvable OU pas la propriété du joueur — indistinct
            // pour ne rien révéler du catalogue des autres comptes. On accepte
            // aussi les autres codes 4xx comme « non autorisé ».
            if (error.getStatusCode() == HttpStatus.NOT_FOUND) {
                throw new LobbyException("DECK_NOT_OWNED",
                        "Ce deck n'existe pas ou ne t'appartient pas : " + deckId);
            }
            throw error;
        }
    }

    /**
     * Crée un salon dont le joueur devient l'hôte (siège 0).
     *
     * @param pseudo   pseudo STOMP (= username du compte)
     * @param roomName nom optionnel du salon
     * @param deckId   identifiant d'un deck sauvegardé par le joueur (obligatoire)
     * @return le salon créé (état WAITING)
     */
    public Room createRoom(String pseudo, String roomName, Long deckId) {
        requireValidPseudo(pseudo);
        if (roomByPseudo.containsKey(pseudo)) {
            throw new LobbyException("ALREADY_IN_ROOM", "Vous êtes déjà dans un salon");
        }
        // Validation en amont : un salon invalide n'est jamais créé.
        requireOwnedDeck(pseudo, deckId);
        String code = generateUniqueCode();
        String displayName = (roomName == null || roomName.isBlank())
                ? "Salon de " + pseudo
                : roomName.trim();
        Room room = new Room(code, displayName, pseudo, deckId);
        roomsByCode.put(code, room);
        roomByPseudo.put(pseudo, code);
        log.info("Salon {} créé par {} (deck #{})", code, pseudo, deckId);
        return room;
    }

    /**
     * Fait rejoindre un salon. Si c'est le second joueur, la partie démarre
     * automatiquement et le salon passe PLAYING.
     *
     * @param pseudo   pseudo STOMP (= username du compte)
     * @param roomCode code du salon
     * @param deckId   identifiant d'un deck sauvegardé par le joueur (obligatoire)
     */
    public Room joinRoom(String pseudo, String roomCode, Long deckId) {
        requireValidPseudo(pseudo);
        if (roomByPseudo.containsKey(pseudo)) {
            throw new LobbyException("ALREADY_IN_ROOM", "Vous êtes déjà dans un salon");
        }
        Room room = roomsByCode.get(normalizeCode(roomCode));
        if (room == null) {
            throw new LobbyException("ROOM_NOT_FOUND", "Salon introuvable : " + roomCode);
        }
        if (!room.isWaiting() || room.isFull()) {
            throw new LobbyException("ROOM_NOT_JOINABLE", "Ce salon n'est pas rejoignable");
        }
        if (room.contains(pseudo)) {
            throw new LobbyException("PSEUDO_TAKEN", "Ce pseudo est déjà utilisé dans ce salon");
        }
        // Validation en amont : aucun siège fantôme avec un deck invalide.
        requireOwnedDeck(pseudo, deckId);
        room.addGuest(pseudo, deckId);
        roomByPseudo.put(pseudo, room.getCode());
        log.info("{} rejoint le salon {} (deck #{})", pseudo, room.getCode(), deckId);

        if (room.isFull()) {
            startGame(room);
        }
        return room;
    }

    /**
     * Démarre la partie à partir des decks persistés des deux sièges : on
     * relit le {@code DeckService} avec le pseudo de chaque joueur pour
     * récupérer la liste de cartes réelle. Si un deck a été supprimé entre
     * temps, le salon est refermé et une erreur est consignée.
     */
    private void startGame(Room room) {
        List<Room.SeatView> seats = room.seatView();
        String host = seats.get(0).pseudo();
        String guest = seats.get(1).pseudo();
        try {
            List<String> hostDeck = deckService.getDeck(host, room.deckIdOf(host)).cardIds();
            List<String> guestDeck = deckService.getDeck(guest, room.deckIdOf(guest)).cardIds();
            GameState state = gameService.createGame(host, guest, hostDeck, guestDeck);
            room.markStarted(state.getGameId());
            log.info("Partie {} démarrée dans le salon {} ({} vs {})",
                    state.getGameId(), room.getCode(), host, guest);
        } catch (RuntimeException error) {
            // Deck supprimé / devenu invalide entre l'arrivée des joueurs et
            // le démarrage : on referme proprement le salon pour permettre une
            // nouvelle tentative, sans laisser un siège orphelin.
            log.warn("Démarrage impossible dans le salon {} : {}", room.getCode(), error.getMessage());
            closeRoom(room);
            throw new LobbyException("DECK_INVALID",
                    "Démarrage impossible : un des decks n'est plus disponible");
        }
    }

    /**
     * Fait quitter un salon en attente. Un hôte qui part ferme le salon ;
     * un invité qui part rend le salon à nouveau rejoignable.
     */
    public Optional<Room> leaveRoom(String pseudo, String roomCode) {
        String code = roomCode != null ? normalizeCode(roomCode) : roomByPseudo.get(pseudo);
        if (code == null) {
            return Optional.empty();
        }
        Room room = roomsByCode.get(code);
        if (room == null || !room.contains(pseudo)) {
            return Optional.empty();
        }
        if (room.getStatus() == RoomStatus.PLAYING) {
            throw new LobbyException("GAME_IN_PROGRESS",
                    "Impossible de quitter : abandonnez la partie via l'action CONCEDE");
        }
        roomByPseudo.remove(pseudo);
        boolean hostLeaving = pseudo.equals(room.getHostPseudo());
        boolean empty = room.remove(pseudo);
        if (hostLeaving || empty) {
            closeRoom(room);
            log.info("Salon {} fermé (départ de l'hôte {})", code, pseudo);
        }
        return Optional.of(room);
    }

    /** Retire le salon et libère les pseudos encore assis. */
    public void closeRoom(Room room) {
        room.close();
        roomsByCode.remove(room.getCode());
        room.seatView().forEach(seat -> roomByPseudo.remove(seat.pseudo(), room.getCode()));
    }

    /** Clôture depuis le code (fin de partie/forfait). */
    public void closeByCode(String code) {
        Room room = code == null ? null : roomsByCode.get(code);
        if (room != null) {
            closeRoom(room);
        }
    }

    /** Clôture le salon associé à une partie terminée (fin/forfait). */
    public void closeByGameId(String gameId) {
        if (gameId == null) {
            return;
        }
        roomsByCode.values().stream()
                .filter(room -> gameId.equals(room.getGameId()))
                .findFirst()
                .ifPresent(this::closeRoom);
    }

    public Optional<Room> findByCode(String roomCode) {
        return Optional.ofNullable(roomsByCode.get(normalizeCode(roomCode)));
    }

    public Optional<Room> findByPlayer(String pseudo) {
        String code = roomByPseudo.get(pseudo);
        return code == null ? Optional.empty() : Optional.ofNullable(roomsByCode.get(code));
    }

    /** Tous les salons encore rejoignables, dans l'ordre de création. */
    public List<Room> listOpenRooms() {
        List<Room> open = new ArrayList<>();
        for (Room room : roomsByCode.values()) {
            if (room.isWaiting()) {
                open.add(room);
            }
        }
        return open;
    }

    private String normalizeCode(String code) {
        if (code == null) {
            throw new LobbyException("ROOM_NOT_FOUND", "Code de salon absent");
        }
        return code.trim().toUpperCase();
    }

    private String generateUniqueCode() {
        for (int attempt = 0; attempt < 50; attempt++) {
            StringBuilder builder = new StringBuilder(CODE_LENGTH);
            for (int i = 0; i < CODE_LENGTH; i++) {
                builder.append(CODE_ALPHABET[random.nextInt(CODE_ALPHABET.length)]);
            }
            String code = builder.toString();
            if (!roomsByCode.containsKey(code)) {
                return code;
            }
        }
        throw new IllegalStateException("Impossible de générer un code de salon unique");
    }
}
