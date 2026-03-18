package com.bluetoothtool;

import javax.bluetooth.BluetoothStateException;
import javax.bluetooth.LocalDevice;
import javax.swing.*;
import javax.swing.border.EmptyBorder;
import java.awt.*;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;

/**
 * Main application window.
 *
 * Creates a SharedState and all panels, arranges them in a JTabbedPane,
 * initialises the window title from the local Bluetooth adapter,
 * and tears down any active connection on close.
 */
public class MainWindow extends JFrame {

    private final SharedState state = new SharedState();

    private DevicePanel        devicePanel;
    private TerminalPanel      terminalPanel;
    private ChatPanel          chatPanel;
    private ServiceBrowserPanel serviceBrowserPanel;
    private LocalInfoPanel     localInfoPanel;
    private BlePanel           blePanel;
    private BleBridgeManager   bleBridge;

    public MainWindow() {
        super("Bluetooth Tool Suite");
        setDefaultCloseOperation(JFrame.DO_NOTHING_ON_CLOSE);
        setSize(940, 660);
        setMinimumSize(new Dimension(720, 520));

        buildUI();
        setLocationRelativeTo(null);
        setVisible(true);

        addWindowListener(new WindowAdapter() {
            @Override
            public void windowClosing(WindowEvent e) {
                onExit();
            }
        });

        initTitleFromLocalDevice();
    }

    // ── Build UI ──────────────────────────────────────────────────────────────

    private void buildUI() {
        // Create panels — pass shared state to each that needs it
        devicePanel         = new DevicePanel(state);
        terminalPanel       = new TerminalPanel(state);
        chatPanel           = new ChatPanel(state);
        serviceBrowserPanel = new ServiceBrowserPanel(state);
        localInfoPanel      = new LocalInfoPanel();

        // BLE bridge — resolve script path relative to the jar/working dir
        String bridgeScript = resolveBridgeScript();
        bleBridge = new BleBridgeManager(bridgeScript);
        blePanel  = new BlePanel(bleBridge);
        bleBridge.start();

        JTabbedPane tabs = new JTabbedPane(JTabbedPane.TOP);
        tabs.setBorder(new EmptyBorder(4, 4, 4, 4));

        // Tab 1 — Devices (Scanner)
        tabs.addTab("Devices", iconFor("FileView.computerIcon"), devicePanel,
                "Scan for nearby Bluetooth Classic devices");

        // Tab 2 — Terminal
        tabs.addTab("Terminal", iconFor("FileView.fileIcon"), terminalPanel,
                "Raw RFCOMM serial terminal (Arduino, HC-05, HC-06, …)");

        // Tab 3 — Chat
        tabs.addTab("Chat", iconFor("OptionPane.informationIcon"), chatPanel,
                "Peer-to-peer Bluetooth chat");

        // Tab 4 — Service Browser
        tabs.addTab("Service Browser", iconFor("FileView.directoryIcon"), serviceBrowserPanel,
                "Browse SDP services on the selected device");

        // Tab 5 — Local Info
        tabs.addTab("Local Info", iconFor("OptionPane.questionIcon"), localInfoPanel,
                "View local Bluetooth adapter information");

        // Tab 6 — BLE
        tabs.addTab("BLE", iconFor("FileView.floppyDriveIcon"), blePanel,
                "BLE scanner and GATT explorer (via Python bridge)");

        add(tabs, BorderLayout.CENTER);

        // Status bar at the very bottom
        JLabel footer = new JLabel("  Universal Bluetooth Tool Suite  |  BlueCove 2.1.1  |  Bluetooth Classic (RFCOMM)");
        footer.setFont(footer.getFont().deriveFont(Font.PLAIN, 10f));
        footer.setForeground(Color.GRAY);
        footer.setBorder(BorderFactory.createCompoundBorder(
            BorderFactory.createMatteBorder(1, 0, 0, 0, new Color(200, 200, 200)),
            new EmptyBorder(2, 4, 2, 4)
        ));
        add(footer, BorderLayout.SOUTH);
    }

    // ── Init title ────────────────────────────────────────────────────────────

    /**
     * Load the local device name on a background thread and update the title bar.
     * Called once at startup; failures are silently swallowed (title stays generic).
     */
    private void initTitleFromLocalDevice() {
        new Thread(() -> {
            try {
                LocalDevice local = LocalDevice.getLocalDevice();
                String name = local.getFriendlyName();
                String addr = formatAddress(local.getBluetoothAddress());
                String title = "Bluetooth Tool Suite  \u2014  " + name + "  [" + addr + "]";
                SwingUtilities.invokeLater(() -> setTitle(title));
            } catch (BluetoothStateException e) {
                SwingUtilities.invokeLater(() ->
                    setTitle("Bluetooth Tool Suite  \u2014  Adapter unavailable"));
            }
        }, "bt-title-init").start();
    }

    // ── Shutdown ──────────────────────────────────────────────────────────────

    private void onExit() {
        // Close any active connection gracefully
        BluetoothConnection conn = state.getActiveConnection();
        if (conn != null && conn.isOpen()) {
            try { conn.close(); } catch (Exception ignored) {}
        }
        if (bleBridge != null) bleBridge.stop();
        dispose();
        System.exit(0);
    }

    /**
     * Resolve the path to ble-bridge/bridge.py.
     * Tries next to the jar first, then falls back to the project root
     * (handy when running from the IDE).
     */
    private static String resolveBridgeScript() {
        // Location of the running jar / class directory
        try {
            java.io.File jarFile = new java.io.File(
                    MainWindow.class.getProtectionDomain()
                                   .getCodeSource().getLocation().toURI());
            // Walk up until we find ble-bridge/bridge.py
            java.io.File dir = jarFile.isFile() ? jarFile.getParentFile() : jarFile;
            for (int i = 0; i < 4; i++) {
                java.io.File candidate = new java.io.File(dir, "ble-bridge/bridge.py");
                if (candidate.exists()) return candidate.getAbsolutePath();
                if (dir.getParentFile() == null) break;
                dir = dir.getParentFile();
            }
        } catch (Exception ignored) {}
        // Fallback: relative path (works when CWD is the project root)
        return "ble-bridge/bridge.py";
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    /**
     * Fetch a UIManager icon; returns null (no icon) if not available on this L&F.
     */
    private static Icon iconFor(String key) {
        return UIManager.getIcon(key);
    }

    private static String formatAddress(String raw) {
        if (raw == null || raw.length() != 12) return raw == null ? "" : raw;
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 12; i += 2) {
            if (i > 0) sb.append(':');
            sb.append(raw, i, i + 2);
        }
        return sb.toString().toUpperCase();
    }
}
