package com.bluetoothtool;

import javax.bluetooth.*;
import javax.microedition.io.Connector;
import javax.microedition.io.StreamConnection;
import javax.microedition.io.StreamConnectionNotifier;
import javax.swing.*;
import javax.swing.border.EmptyBorder;
import javax.swing.border.TitledBorder;
import java.awt.*;
import java.io.*;
import java.util.ArrayList;
import java.util.List;

/**
 * Universal Bluetooth Tool
 *
 * Tabs:
 *  1. Scanner  — discover nearby Bluetooth Classic devices
 *  2. Chat     — RFCOMM peer-to-peer messaging (server or client mode)
 */
public class MainWindow extends JFrame implements DiscoveryListener {

    // ── Shared service UUID for the chat ──────────────────────────────────────
    private static final String CHAT_UUID = "27012F0C5B8E4B4E9B3A6B9D0E3A4F5A";
    private static final String CHAT_NAME = "BTChat";

    // ── Scanner tab ───────────────────────────────────────────────────────────
    private JButton scanButton;
    private JLabel  scanStatusLabel;
    private DefaultListModel<DeviceEntry> scanListModel;
    private JList<DeviceEntry>            scanList;
    private final List<RemoteDevice>      discovered = new ArrayList<>();
    private final Object                  inquiryLock = new Object();

    // ── Chat tab ──────────────────────────────────────────────────────────────
    private JButton  serverButton;
    private JButton  stopServerButton;
    private JButton  connectButton;
    private JLabel   chatStatusLabel;
    private JTextArea chatArea;
    private JTextField chatInput;
    private JButton  sendButton;

    private volatile StreamConnectionNotifier serverNotifier;
    private volatile StreamConnection         connection;
    private volatile PrintWriter              writer;
    private volatile boolean                  serverRunning;

    private final List<String>  serviceURLs  = new ArrayList<>();
    private final Object        serviceLock  = new Object();

    // ── Construction ──────────────────────────────────────────────────────────

    public MainWindow() {
        super("Universal Bluetooth Tool");
        setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        setSize(820, 580);
        setMinimumSize(new Dimension(640, 480));
        buildUI();
        setLocationRelativeTo(null);
        setVisible(true);
        initLocalDevice();
    }

    // ── UI ────────────────────────────────────────────────────────────────────

    private void buildUI() {
        JTabbedPane tabs = new JTabbedPane();
        tabs.addTab("Scanner",  buildScannerTab());
        tabs.addTab("Chat",     buildChatTab());
        add(tabs);
    }

    // ── Scanner tab ───────────────────────────────────────────────────────────

    private JPanel buildScannerTab() {
        JPanel panel = new JPanel(new BorderLayout(8, 8));
        panel.setBorder(new EmptyBorder(12, 12, 12, 12));

        // Device list
        scanListModel = new DefaultListModel<>();
        scanList = new JList<>(scanListModel);
        scanList.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
        scanList.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        JScrollPane scroll = new JScrollPane(scanList);
        scroll.setBorder(new TitledBorder("Discovered Devices"));
        panel.add(scroll, BorderLayout.CENTER);

        // Buttons + status
        JPanel bottom = new JPanel(new BorderLayout(8, 0));
        scanStatusLabel = new JLabel("Ready");
        scanStatusLabel.setFont(scanStatusLabel.getFont().deriveFont(Font.ITALIC));
        scanStatusLabel.setForeground(Color.GRAY);

        JPanel btnRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
        scanButton = new JButton("Scan for Devices");
        scanButton.addActionListener(e -> startScan());

        JButton clearBtn = new JButton("Clear");
        clearBtn.addActionListener(e -> {
            scanListModel.clear();
            discovered.clear();
            scanStatusLabel.setText("Cleared.");
        });

        // "Connect to chat" shortcut from scanner
        JButton chatBtn = new JButton("Open in Chat Tab →");
        chatBtn.addActionListener(e -> {
            DeviceEntry sel = scanList.getSelectedValue();
            if (sel == null) { JOptionPane.showMessageDialog(this, "Select a device first."); return; }
            // Switch to chat tab and pre-select the device
            JTabbedPane tabs = (JTabbedPane) getContentPane().getComponent(0);
            tabs.setSelectedIndex(1);
            // Trigger a service search on that device
            initiateClientConnect(sel);
        });
        chatBtn.setEnabled(false);
        scanList.addListSelectionListener(e -> chatBtn.setEnabled(scanList.getSelectedIndex() >= 0));

        btnRow.add(scanButton);
        btnRow.add(clearBtn);
        btnRow.add(chatBtn);
        bottom.add(btnRow, BorderLayout.WEST);
        bottom.add(scanStatusLabel, BorderLayout.EAST);
        panel.add(bottom, BorderLayout.SOUTH);

        return panel;
    }

    // ── Chat tab ──────────────────────────────────────────────────────────────

    private JPanel buildChatTab() {
        JPanel panel = new JPanel(new BorderLayout(8, 8));
        panel.setBorder(new EmptyBorder(12, 12, 12, 12));

        // Chat history
        chatArea = new JTextArea();
        chatArea.setEditable(false);
        chatArea.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
        chatArea.setLineWrap(true);
        chatArea.setWrapStyleWord(true);
        JScrollPane chatScroll = new JScrollPane(chatArea);
        chatScroll.setBorder(new TitledBorder("Messages"));
        panel.add(chatScroll, BorderLayout.CENTER);

        // Top controls
        JPanel top = new JPanel(new BorderLayout(8, 4));

        chatStatusLabel = new JLabel("Not connected");
        chatStatusLabel.setFont(chatStatusLabel.getFont().deriveFont(Font.BOLD));
        top.add(chatStatusLabel, BorderLayout.NORTH);

        JPanel controlRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
        serverButton     = new JButton("Start Server");
        stopServerButton = new JButton("Stop");
        connectButton    = new JButton("Connect to Device…");
        stopServerButton.setEnabled(false);

        serverButton.addActionListener(e  -> startServer());
        stopServerButton.addActionListener(e -> stopServer());
        connectButton.addActionListener(e  -> pickAndConnect());

        controlRow.add(serverButton);
        controlRow.add(stopServerButton);
        controlRow.add(new JSeparator(SwingConstants.VERTICAL));
        controlRow.add(connectButton);
        top.add(controlRow, BorderLayout.CENTER);
        panel.add(top, BorderLayout.NORTH);

        // Message send bar
        JPanel sendRow = new JPanel(new BorderLayout(6, 0));
        chatInput = new JTextField();
        chatInput.setEnabled(false);
        chatInput.addActionListener(e -> sendChat());

        sendButton = new JButton("Send");
        sendButton.setEnabled(false);
        sendButton.addActionListener(e -> sendChat());

        sendRow.add(chatInput, BorderLayout.CENTER);
        sendRow.add(sendButton, BorderLayout.EAST);
        panel.add(sendRow, BorderLayout.SOUTH);

        return panel;
    }

    // ── Local device init ─────────────────────────────────────────────────────

    private void initLocalDevice() {
        new Thread(() -> {
            try {
                LocalDevice local = LocalDevice.getLocalDevice();
                String info = local.getFriendlyName() + "  [" + fmt(local.getBluetoothAddress()) + "]";
                SwingUtilities.invokeLater(() -> setTitle("Universal Bluetooth Tool — " + info));
                chatLog("[System] Local device: " + info);
            } catch (BluetoothStateException e) {
                chatLog("[ERROR] Bluetooth unavailable: " + e.getMessage());
                SwingUtilities.invokeLater(() -> {
                    scanButton.setEnabled(false);
                    serverButton.setEnabled(false);
                    connectButton.setEnabled(false);
                    chatStatusLabel.setText("Bluetooth unavailable");
                });
            }
        }, "bt-init").start();
    }

    // ── Scanner ───────────────────────────────────────────────────────────────

    private void startScan() {
        scanButton.setEnabled(false);
        scanListModel.clear();
        discovered.clear();
        SwingUtilities.invokeLater(() -> scanStatusLabel.setText("Scanning…"));

        new Thread(() -> {
            try {
                DiscoveryAgent agent = LocalDevice.getLocalDevice().getDiscoveryAgent();
                synchronized (inquiryLock) {
                    agent.startInquiry(DiscoveryAgent.GIAC, this);
                    inquiryLock.wait();
                }
            } catch (BluetoothStateException | InterruptedException e) {
                SwingUtilities.invokeLater(() -> scanStatusLabel.setText("Error: " + e.getMessage()));
            } finally {
                SwingUtilities.invokeLater(() -> scanButton.setEnabled(true));
            }
        }, "bt-scan").start();
    }

    // ── Chat: server ──────────────────────────────────────────────────────────

    private void startServer() {
        serverButton.setEnabled(false);
        stopServerButton.setEnabled(true);
        serverRunning = true;
        setChatStatus("Listening for incoming connection…", false);
        chatLog("[Server] Opening RFCOMM socket…");

        new Thread(() -> {
            try {
                String url = "btspp://localhost:" + CHAT_UUID
                        + ";name=" + CHAT_NAME + ";authenticate=false;encrypt=false";
                serverNotifier = (StreamConnectionNotifier) Connector.open(url);
                chatLog("[Server] Ready. UUID: " + CHAT_UUID);

                StreamConnection conn = serverNotifier.acceptAndOpen();
                if (!serverRunning) return;

                SwingUtilities.invokeLater(() -> stopServerButton.setEnabled(false));
                handleConnection(conn, "client");

            } catch (IOException e) {
                if (serverRunning) chatLog("[Server] Error: " + e.getMessage());
            } finally {
                closeNotifier();
                SwingUtilities.invokeLater(() -> {
                    serverButton.setEnabled(true);
                    stopServerButton.setEnabled(false);
                    serverRunning = false;
                });
            }
        }, "bt-server").start();
    }

    private void stopServer() {
        serverRunning = false;
        closeNotifier();
        chatLog("[Server] Stopped.");
        setChatStatus("Stopped", false);
        serverButton.setEnabled(true);
        stopServerButton.setEnabled(false);
    }

    private void closeNotifier() {
        if (serverNotifier != null) {
            try { serverNotifier.close(); } catch (IOException ignored) {}
            serverNotifier = null;
        }
    }

    // ── Chat: client — pick from scan results ─────────────────────────────────

    private void pickAndConnect() {
        if (discovered.isEmpty()) {
            int choice = JOptionPane.showConfirmDialog(this,
                "No devices scanned yet. Run a scan first?", "No devices", JOptionPane.YES_NO_OPTION);
            if (choice == JOptionPane.YES_OPTION) {
                JTabbedPane tabs = (JTabbedPane) getContentPane().getComponent(0);
                tabs.setSelectedIndex(0);
                startScan();
            }
            return;
        }
        DeviceEntry[] options = new DeviceEntry[discovered.size()];
        for (int i = 0; i < discovered.size(); i++) {
            options[i] = scanListModel.get(i);
        }
        DeviceEntry choice = (DeviceEntry) JOptionPane.showInputDialog(
                this, "Select device to connect to:", "Connect",
                JOptionPane.PLAIN_MESSAGE, null, options, options[0]);
        if (choice != null) initiateClientConnect(choice);
    }

    private void initiateClientConnect(DeviceEntry entry) {
        connectButton.setEnabled(false);
        setChatStatus("Searching for chat service on " + entry.name + "…", false);
        chatLog("[Client] Searching for service on " + entry + "…");

        new Thread(() -> {
            try {
                UUID[] uuids   = { new UUID(CHAT_UUID, false) };
                int[]  attrSet = { 0x0100 };
                serviceURLs.clear();
                DiscoveryAgent agent = LocalDevice.getLocalDevice().getDiscoveryAgent();
                synchronized (serviceLock) {
                    agent.searchServices(attrSet, uuids, entry.device, this);
                    serviceLock.wait(20_000);
                }
                if (serviceURLs.isEmpty()) {
                    chatLog("[Client] Service not found. Is the server running on that device?");
                    SwingUtilities.invokeLater(() -> connectButton.setEnabled(true));
                    return;
                }
                chatLog("[Client] Connecting to: " + serviceURLs.get(0));
                StreamConnection conn = (StreamConnection) Connector.open(serviceURLs.get(0));
                handleConnection(conn, "server");
            } catch (InterruptedException | IOException e) {
                chatLog("[Client] Error: " + e.getMessage());
                SwingUtilities.invokeLater(() -> connectButton.setEnabled(true));
            }
        }, "bt-connect").start();
    }

    // ── Shared connection handler ─────────────────────────────────────────────

    private void handleConnection(StreamConnection conn, String peerRole) {
        connection = conn;
        try {
            InputStream  is  = conn.openInputStream();
            OutputStream os  = conn.openOutputStream();
            OutputStreamWriter osw = new OutputStreamWriter(os);
            writer = new PrintWriter(osw, true);

            SwingUtilities.invokeLater(() -> {
                setChatStatus("Connected to " + peerRole, true);
                chatInput.setEnabled(true);
                sendButton.setEnabled(true);
                chatInput.requestFocus();
                serverButton.setEnabled(false);
                connectButton.setEnabled(false);
            });
            chatLog("[System] Connected to " + peerRole + ". Start typing.");

            BufferedReader reader = new BufferedReader(new InputStreamReader(is));
            String line;
            while ((line = reader.readLine()) != null) {
                final String msg = line;
                SwingUtilities.invokeLater(() -> chatLog("[" + peerRole + "] " + msg));
            }
        } catch (IOException e) {
            chatLog("[System] Connection closed: " + e.getMessage());
        } finally {
            disconnect();
        }
    }

    private void sendChat() {
        String text = chatInput.getText().trim();
        if (text.isEmpty() || writer == null) return;
        writer.println(text);
        chatLog("[me] " + text);
        chatInput.setText("");
    }

    private void disconnect() {
        if (connection != null) {
            try { connection.close(); } catch (IOException ignored) {}
            connection = null;
        }
        writer = null;
        SwingUtilities.invokeLater(() -> {
            setChatStatus("Disconnected", false);
            chatInput.setEnabled(false);
            sendButton.setEnabled(false);
            serverButton.setEnabled(true);
            stopServerButton.setEnabled(false);
            connectButton.setEnabled(true);
        });
        chatLog("[System] Disconnected.");
    }

    // ── DiscoveryListener ─────────────────────────────────────────────────────

    @Override
    public void deviceDiscovered(RemoteDevice btDevice, DeviceClass cod) {
        discovered.add(btDevice);
        String addr = fmt(btDevice.getBluetoothAddress());
        String name;
        try {
            name = btDevice.getFriendlyName(false);
            if (name == null || name.isBlank()) name = "(no name)";
        } catch (Exception e) { name = "(unknown)"; }
        DeviceEntry entry = new DeviceEntry(btDevice, name, addr);
        SwingUtilities.invokeLater(() -> {
            scanListModel.addElement(entry);
            scanStatusLabel.setText(scanListModel.size() + " device(s) found…");
        });
    }

    @Override
    public void inquiryCompleted(int discType) {
        String result = discType == DiscoveryListener.INQUIRY_COMPLETED
                ? discovered.size() + " device(s) found."
                : "Scan ended (code=" + discType + ").";
        SwingUtilities.invokeLater(() -> scanStatusLabel.setText(result));
        synchronized (inquiryLock) { inquiryLock.notifyAll(); }
    }

    @Override
    public void servicesDiscovered(int transID, ServiceRecord[] servRecord) {
        for (ServiceRecord sr : servRecord) {
            String url = sr.getConnectionURL(ServiceRecord.NOAUTHENTICATE_NOENCRYPT, false);
            if (url != null) serviceURLs.add(url);
        }
    }

    @Override
    public void serviceSearchCompleted(int transID, int respCode) {
        synchronized (serviceLock) { serviceLock.notifyAll(); }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private void chatLog(String msg) {
        SwingUtilities.invokeLater(() -> {
            chatArea.append(msg + "\n");
            chatArea.setCaretPosition(chatArea.getDocument().getLength());
        });
    }

    private void setChatStatus(String msg, boolean connected) {
        SwingUtilities.invokeLater(() -> {
            chatStatusLabel.setText(msg);
            chatStatusLabel.setForeground(connected ? new Color(0, 128, 0) : Color.DARK_GRAY);
        });
    }

    private static String fmt(String raw) {
        if (raw == null || raw.length() != 12) return raw;
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 12; i += 2) {
            if (i > 0) sb.append(':');
            sb.append(raw, i, i + 2);
        }
        return sb.toString().toUpperCase();
    }

    // ── Device entry ──────────────────────────────────────────────────────────

    static class DeviceEntry {
        final RemoteDevice device;
        final String name, address;
        DeviceEntry(RemoteDevice d, String n, String a) { device=d; name=n; address=a; }
        @Override public String toString() { return name + "  [" + address + "]"; }
    }
}
