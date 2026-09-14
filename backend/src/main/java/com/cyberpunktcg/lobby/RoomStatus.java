package com.cyberpunktcg.lobby;

/**
 * Cycle de vie d'un salon.
 * <ul>
 *   <li>{@link #WAITING} : un seul joueur assis, un second peut rejoindre ;</li>
 *   <li>{@link #PLAYING} : deux joueurs, partie démarrée, plus de rejointe ;</li>
 *   <li>{@link #CLOSED} : terminé/abandonné/supprimé, retiré de la liste.</li>
 * </ul>
 */
public enum RoomStatus {
    WAITING,
    PLAYING,
    CLOSED
}
