package com.cyberpunktcg.ws;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.messaging.converter.MappingJackson2MessageConverter;
import org.springframework.messaging.simp.stomp.StompFrameHandler;
import org.springframework.messaging.simp.stomp.StompHeaders;
import org.springframework.messaging.simp.stomp.StompSession;
import org.springframework.messaging.simp.stomp.StompSessionHandlerAdapter;
import org.springframework.web.socket.client.standard.StandardWebSocketClient;
import org.springframework.web.socket.messaging.WebSocketStompClient;

import java.lang.reflect.Type;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

/**
 * Client STOMP minimal pour les tests d'intégration : chaque souscription
 * alimente une {@link BlockingQueue} de nœuds JSON, et les envois sont
 * sérialisés en JSON par Jackson.
 */
public class StompTestClient implements AutoCloseable {

    private final ObjectMapper mapper = new ObjectMapper();
    private final WebSocketStompClient stomp;
    private final Map<String, BlockingQueue<JsonNode>> queues = new ConcurrentHashMap<>();
    private final CopyOnWriteArrayList<StompSession.Subscription> subscriptions = new CopyOnWriteArrayList<>();

    private StompSession session;
    private String pseudo;

    public StompTestClient() {
        // Le conteneur Tomcat client borne par défaut les messages texte à 8 Ko ;
        // un état de partie complet fait ~9 Ko → on élargit le buffer (le vrai
        // client, navigateur, n'a pas cette limite).
        org.apache.tomcat.websocket.WsWebSocketContainer container =
                (org.apache.tomcat.websocket.WsWebSocketContainer)
                        jakarta.websocket.ContainerProvider.getWebSocketContainer();
        container.setDefaultMaxTextMessageBufferSize(1_000_000);
        container.setDefaultMaxBinaryMessageBufferSize(1_000_000);
        this.stomp = new WebSocketStompClient(new StandardWebSocketClient(container));
        this.stomp.setMessageConverter(new MappingJackson2MessageConverter());
        // Les états serveur peuvent dépasser la limite STOMP par défaut (64 Ko) plus tard.
        this.stomp.setInboundMessageSizeLimit(1_000_000);
    }

    public void connect(int port, String pseudo) throws Exception {
        this.pseudo = pseudo;
        StompHeaders connectHeaders = new StompHeaders();
        if (pseudo != null) {
            connectHeaders.add(StompAuthInterceptor.PSEUDO_HEADER, pseudo);
        }
        java.net.URI url = java.net.URI.create("ws://localhost:" + port + "/ws");
        this.session = stomp.connect(url, new org.springframework.web.socket.WebSocketHttpHeaders(),
                connectHeaders, new StompSessionHandlerAdapter() {
                }).get(15, TimeUnit.SECONDS);
    }

    public BlockingQueue<JsonNode> subscribe(String destination) throws Exception {
        BlockingQueue<JsonNode> queue = new LinkedBlockingQueue<>();
        queues.put(destination, queue);
        StompSession.Subscription subscription = session.subscribe(destination, new StompFrameHandler() {
            @Override
            public Type getPayloadType(StompHeaders headers) {
                return byte[].class;
            }

            @Override
            public void handleFrame(StompHeaders headers, Object payload) {
                try {
                    queue.add(mapper.readTree((byte[]) payload));
                } catch (Exception error) {
                    throw new IllegalStateException("Frame JSON illisible", error);
                }
            }
        });
        subscriptions.add(subscription);
        // Laisse au broker le temps d'enregistrer la souscription.
        Thread.sleep(100);
        return queue;
    }

    public void send(String destination, Object payload) {
        session.send(destination, payload);
    }

    public void sendEmpty(String destination) {
        session.send(destination, Map.of());
    }

    /** Attend le premier message satisfaisant le prédicat (les autres sont laissés de côté). */
    public JsonNode await(BlockingQueue<JsonNode> queue,
                          java.util.function.Predicate<JsonNode> matcher, long timeoutSeconds)
            throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutSeconds * 1000;
        while (System.currentTimeMillis() < deadline) {
            JsonNode node = queue.poll(Math.max(1, deadline - System.currentTimeMillis()), TimeUnit.MILLISECONDS);
            if (node != null && matcher.test(node)) {
                return node;
            }
        }
        return null;
    }

    public JsonNode next(BlockingQueue<JsonNode> queue, long timeoutSeconds) throws InterruptedException {
        return queue.poll(timeoutSeconds, TimeUnit.SECONDS);
    }

    public String getPseudo() {
        return pseudo;
    }

    public StompSession getSession() {
        return session;
    }

    @Override
    public void close() {
        for (StompSession.Subscription subscription : subscriptions) {
            try {
                subscription.unsubscribe();
            } catch (RuntimeException ignored) {
                // la session peut déjà être fermée
            }
        }
        if (session != null && session.isConnected()) {
            try {
                session.disconnect();
                // Le serveur doit avoir le temps de traiter le DISCONNECT.
                Thread.sleep(150);
            } catch (Exception ignored) {
                // fermeture déjà effective
            }
        }
    }
}
