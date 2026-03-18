package com.bluetoothtool;

import org.java_websocket.client.WebSocketClient;
import org.java_websocket.handshake.ServerHandshake;
import org.json.JSONObject;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

/**
 * Manages the lifecycle of the Python BLE bridge subprocess and the
 * WebSocket connection to it.
 *
 * Usage:
 *   BleBridgeManager mgr = new BleBridgeManager(scriptPath);
 *   mgr.addListener(event -> { ... });
 *   mgr.start();          // spawns Python, connects WebSocket
 *   mgr.send(cmd);        // send a JSON command
 *   mgr.stop();           // kill subprocess, close WS
 */
public class BleBridgeManager {

    public interface BridgeListener {
        void onEvent(JSONObject event);
        default void onBridgeStatus(String status, boolean running) {}
    }

    private static final int    PORT          = 7878;
    private static final int    CONNECT_TRIES = 10;
    private static final long   RETRY_DELAY   = 600; // ms between retries

    private final String scriptPath;
    private final List<BridgeListener> listeners = new CopyOnWriteArrayList<>();

    private Process          process;
    private BridgeWebSocket  ws;
    private volatile boolean stopped = false;

    public BleBridgeManager(String scriptPath) {
        this.scriptPath = scriptPath;
    }

    public void addListener(BridgeListener l)    { listeners.add(l); }
    public void removeListener(BridgeListener l) { listeners.remove(l); }

    /** Start the bridge subprocess and connect via WebSocket. Runs in a background thread. */
    public void start() {
        stopped = false;
        new Thread(this::launchAndConnect, "ble-bridge-launcher").start();
    }

    /** Send a JSON command object to the bridge. No-op if not connected. */
    public void send(JSONObject cmd) {
        if (ws != null && ws.isOpen()) {
            ws.send(cmd.toString());
        }
    }

    /** Convenience builder for simple commands. */
    public void send(String cmdName) {
        send(new JSONObject().put("cmd", cmdName));
    }

    public boolean isConnected() {
        return ws != null && ws.isOpen();
    }

    /** Stop the bridge: close WebSocket and kill the subprocess. */
    public void stop() {
        stopped = true;
        if (ws != null) { try { ws.close(); } catch (Exception ignored) {} }
        if (process != null && process.isAlive()) process.destroyForcibly();
        notifyStatus("Stopped", false);
    }

    // ── Internal ──────────────────────────────────────────────────────────────

    private void launchAndConnect() {
        notifyStatus("Starting bridge…", false);

        // Find Python
        String python = findPython();
        if (python == null) {
            notifyStatus("Python not found. Install Python 3 and run: pip install bleak websockets", false);
            return;
        }

        // Spawn the subprocess
        try {
            ProcessBuilder pb = new ProcessBuilder(python, scriptPath);
            pb.redirectErrorStream(true);
            process = pb.start();

            // Drain subprocess stdout so it doesn't block
            new Thread(() -> {
                try (var reader = process.inputReader()) {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        System.out.println("[bridge] " + line);
                    }
                } catch (Exception ignored) {}
            }, "ble-bridge-stdout").start();

        } catch (Exception e) {
            notifyStatus("Failed to start bridge: " + e.getMessage(), false);
            return;
        }

        // Wait for WebSocket server to come up, then connect
        notifyStatus("Waiting for bridge…", false);
        URI uri;
        try {
            uri = new URI("ws://127.0.0.1:" + PORT);
        } catch (Exception e) {
            notifyStatus("Bad URI: " + e.getMessage(), false);
            return;
        }

        for (int attempt = 0; attempt < CONNECT_TRIES; attempt++) {
            if (stopped) return;
            try {
                Thread.sleep(RETRY_DELAY);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
            try {
                ws = new BridgeWebSocket(uri);
                ws.connectBlocking();
                if (ws.isOpen()) {
                    notifyStatus("Bridge connected", true);
                    return;
                }
            } catch (Exception ignored) {}
        }

        notifyStatus("Could not connect to bridge after " + CONNECT_TRIES + " attempts", false);
    }

    private String findPython() {
        for (String candidate : List.of("python", "python3",
                System.getenv().getOrDefault("PYTHON", ""))) {
            if (candidate.isBlank()) continue;
            try {
                Process p = new ProcessBuilder(candidate, "--version")
                        .redirectErrorStream(true).start();
                if (p.waitFor() == 0) return candidate;
            } catch (Exception ignored) {}
        }
        return null;
    }

    private void notifyStatus(String status, boolean running) {
        for (BridgeListener l : listeners) {
            try { l.onBridgeStatus(status, running); } catch (Exception ignored) {}
        }
    }

    private void notifyEvent(JSONObject event) {
        for (BridgeListener l : listeners) {
            try { l.onEvent(event); } catch (Exception ignored) {}
        }
    }

    // ── WebSocket client ──────────────────────────────────────────────────────

    private class BridgeWebSocket extends WebSocketClient {

        BridgeWebSocket(URI uri) { super(uri); }

        @Override
        public void onOpen(ServerHandshake hs) {
            notifyStatus("Bridge connected", true);
        }

        @Override
        public void onMessage(String message) {
            try {
                notifyEvent(new JSONObject(message));
            } catch (Exception e) {
                System.err.println("[bridge] Bad JSON from bridge: " + message);
            }
        }

        @Override
        public void onClose(int code, String reason, boolean remote) {
            if (!stopped) notifyStatus("Bridge disconnected", false);
        }

        @Override
        public void onError(Exception ex) {
            // suppress noisy connection-refused during startup retries
            if (!ex.getMessage().contains("Connection refused")) {
                System.err.println("[bridge] WS error: " + ex.getMessage());
            }
        }
    }
}
