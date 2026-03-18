package com.bluetoothtool;

import javax.microedition.io.Connector;
import javax.microedition.io.StreamConnection;
import javax.microedition.io.StreamConnectionNotifier;
import javax.swing.*;
import javax.swing.border.EmptyBorder;
import java.awt.*;
import java.io.IOException;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.List;

/**
 * Chat tab — bubble-style peer-to-peer RFCOMM messaging.
 */
public class ChatPanel extends JPanel {

    private static final String CHAT_UUID = "27012F0C5B8E4B4E9B3A6B9D0E3A4F5A";
    private static final String SERVICE_NAME = "BTChat";
    private static final DateTimeFormatter TIME_FMT = DateTimeFormatter.ofPattern("HH:mm:ss");

    // Bubble colors
    private static final Color MY_BUBBLE_BG    = new Color(0, 120, 212);
    private static final Color MY_BUBBLE_FG    = Color.WHITE;
    private static final Color THEIR_BUBBLE_BG = new Color(220, 220, 220);
    private static final Color THEIR_BUBBLE_FG = new Color(30, 30, 30);
    private static final Color SYSTEM_FG       = new Color(120, 120, 120);

    private final SharedState state;

    // Connection
    private volatile StreamConnectionNotifier serverNotifier;
    private final BluetoothConnection connection = new BluetoothConnection();
    private boolean serverMode = false;

    // Top controls
    private JToggleButton clientModeBtn;
    private JToggleButton serverModeBtn;
    private JButton connectButton;
    private JButton disconnectButton;
    private JLabel statusLabel;
    private JComboBox<SharedState.DeviceEntry> deviceCombo;
    private JPanel clientControlPanel;

    // Message area
    private JPanel bubbleContainer;
    private JScrollPane scrollPane;

    // Input
    private JTextField inputField;
    private JButton sendButton;

    public ChatPanel(SharedState state) {
        this.state = state;
        setLayout(new BorderLayout(6, 6));
        setBorder(new EmptyBorder(10, 10, 10, 10));
        buildUI();

        state.addListener(new SharedState.StateListener() {
            @Override
            public void onDevicesChanged(List<SharedState.DeviceEntry> devices) {
                SwingUtilities.invokeLater(() -> refreshDeviceCombo(devices));
            }
        });
    }

    private void buildUI() {
        add(buildTopBar(), BorderLayout.NORTH);
        add(buildBubbleArea(), BorderLayout.CENTER);
        add(buildInputRow(), BorderLayout.SOUTH);
        updateModeUI();
    }

    // ── Top bar ───────────────────────────────────────────────────────────────

    private JPanel buildTopBar() {
        JPanel topPanel = new JPanel(new BorderLayout(6, 4));

        // Mode + connect buttons
        JPanel controlRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 0));

        controlRow.add(new JLabel("Mode:"));
        ButtonGroup grp = new ButtonGroup();
        clientModeBtn = new JToggleButton("Client", true);
        serverModeBtn = new JToggleButton("Server");
        grp.add(clientModeBtn);
        grp.add(serverModeBtn);
        clientModeBtn.addActionListener(e -> { serverMode = false; updateModeUI(); });
        serverModeBtn.addActionListener(e -> { serverMode = true; updateModeUI(); });
        controlRow.add(clientModeBtn);
        controlRow.add(serverModeBtn);

        controlRow.add(Box.createHorizontalStrut(8));

        connectButton = new JButton("Connect");
        disconnectButton = new JButton("Disconnect");
        disconnectButton.setEnabled(false);
        connectButton.addActionListener(e -> handleConnect());
        disconnectButton.addActionListener(e -> handleDisconnect());
        controlRow.add(connectButton);
        controlRow.add(disconnectButton);

        statusLabel = new JLabel("Not connected");
        statusLabel.setForeground(Color.GRAY);
        statusLabel.setFont(statusLabel.getFont().deriveFont(Font.ITALIC));
        controlRow.add(Box.createHorizontalStrut(12));
        controlRow.add(statusLabel);

        topPanel.add(controlRow, BorderLayout.NORTH);

        // Client device selector
        clientControlPanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 2));
        clientControlPanel.add(new JLabel("Connect to:"));
        deviceCombo = new JComboBox<>();
        deviceCombo.setPreferredSize(new Dimension(280, 24));
        clientControlPanel.add(deviceCombo);
        topPanel.add(clientControlPanel, BorderLayout.CENTER);

        return topPanel;
    }

    // ── Bubble message area ───────────────────────────────────────────────────

    private JScrollPane buildBubbleArea() {
        bubbleContainer = new JPanel();
        bubbleContainer.setLayout(new BoxLayout(bubbleContainer, BoxLayout.Y_AXIS));
        bubbleContainer.setBackground(new Color(245, 245, 250));
        bubbleContainer.setBorder(new EmptyBorder(8, 8, 8, 8));

        scrollPane = new JScrollPane(bubbleContainer);
        scrollPane.setVerticalScrollBarPolicy(JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED);
        scrollPane.getVerticalScrollBar().setUnitIncrement(16);
        scrollPane.setBorder(BorderFactory.createLineBorder(new Color(200, 200, 200)));
        return scrollPane;
    }

    // ── Input row ─────────────────────────────────────────────────────────────

    private JPanel buildInputRow() {
        JPanel row = new JPanel(new BorderLayout(6, 0));
        row.setBorder(new EmptyBorder(4, 0, 0, 0));

        inputField = new JTextField();
        inputField.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 13));
        inputField.setEnabled(false);
        inputField.addActionListener(e -> sendMessage());

        sendButton = new JButton("Send");
        sendButton.setEnabled(false);
        sendButton.addActionListener(e -> sendMessage());

        row.add(inputField, BorderLayout.CENTER);
        row.add(sendButton, BorderLayout.EAST);
        return row;
    }

    // ── Mode UI ───────────────────────────────────────────────────────────────

    private void updateModeUI() {
        clientControlPanel.setVisible(!serverMode);
        connectButton.setText(serverMode ? "Listen" : "Connect");
    }

    private void refreshDeviceCombo(List<SharedState.DeviceEntry> devices) {
        SharedState.DeviceEntry current = (SharedState.DeviceEntry) deviceCombo.getSelectedItem();
        deviceCombo.removeAllItems();
        for (SharedState.DeviceEntry d : devices) {
            deviceCombo.addItem(d);
        }
        if (current != null) deviceCombo.setSelectedItem(current);
    }

    // ── Connect / disconnect ──────────────────────────────────────────────────

    private void handleConnect() {
        if (serverMode) {
            startServer();
        } else {
            startClient();
        }
    }

    private void handleDisconnect() {
        if (serverNotifier != null) {
            try { serverNotifier.close(); } catch (IOException ignored) {}
            serverNotifier = null;
        }
        connection.close();
        setConnectedUI(false);
        addSystemMessage("Disconnected.");
    }

    private void startServer() {
        connectButton.setEnabled(false);
        setStatus("Opening chat service…", Color.GRAY);
        addSystemMessage("Starting chat server…");

        new Thread(() -> {
            try {
                String url = "btspp://localhost:" + CHAT_UUID
                        + ";name=" + SERVICE_NAME + ";authenticate=false;encrypt=false;master=false";
                serverNotifier = (StreamConnectionNotifier) Connector.open(url);

                SwingUtilities.invokeLater(() -> {
                    setStatus("Waiting for connection…", Color.ORANGE);
                    addSystemMessage("Listening… Share your Bluetooth address with your peer.");
                });

                StreamConnection sc = serverNotifier.acceptAndOpen();
                connection.open(sc);

                SwingUtilities.invokeLater(() -> {
                    setConnectedUI(true);
                    setStatus("Peer connected (server)", new Color(0, 128, 0));
                    addSystemMessage("Peer connected.");
                });

                startReading("Peer");

            } catch (IOException e) {
                SwingUtilities.invokeLater(() -> {
                    connectButton.setEnabled(true);
                    setStatus("Error: " + e.getMessage(), Color.RED);
                    addSystemMessage("Server error: " + e.getMessage());
                });
            }
        }, "bt-chat-server").start();
    }

    private void startClient() {
        SharedState.DeviceEntry selected = (SharedState.DeviceEntry) deviceCombo.getSelectedItem();
        if (selected == null) {
            JOptionPane.showMessageDialog(this, "No device selected. Run a scan first.", "No Device", JOptionPane.WARNING_MESSAGE);
            return;
        }

        connectButton.setEnabled(false);
        setStatus("Connecting to " + selected.name + "…", Color.ORANGE);
        addSystemMessage("Connecting to " + selected.name + "…");

        String rawAddr = selected.device.getBluetoothAddress().toUpperCase();
        String url = "btspp://" + rawAddr + ":" + CHAT_UUID
                + ";authenticate=false;encrypt=false;master=false";

        new Thread(() -> {
            try {
                connection.open(url);
                SwingUtilities.invokeLater(() -> {
                    setConnectedUI(true);
                    setStatus("Connected to " + selected.name, new Color(0, 128, 0));
                    addSystemMessage("Connected to " + selected.name + ".");
                });
                startReading(selected.name);
            } catch (IOException e) {
                SwingUtilities.invokeLater(() -> {
                    connectButton.setEnabled(true);
                    setStatus("Failed: " + e.getMessage(), Color.RED);
                    addSystemMessage("Connection failed: " + e.getMessage());
                });
            }
        }, "bt-chat-client").start();
    }

    private void startReading(String peerName) {
        connection.startReading(
            line -> {
                if (!line.isBlank()) {
                    SwingUtilities.invokeLater(() -> addTheirBubble(peerName, line));
                }
            },
            null,
            () -> SwingUtilities.invokeLater(() -> {
                setConnectedUI(false);
                addSystemMessage(peerName + " disconnected.");
            })
        );
    }

    // ── Send ──────────────────────────────────────────────────────────────────

    private void sendMessage() {
        String text = inputField.getText().trim();
        if (text.isEmpty()) return;
        if (!connection.isOpen()) return;

        connection.sendLine(text);
        addMyBubble(text);
        inputField.setText("");
    }

    // ── UI helpers ────────────────────────────────────────────────────────────

    private void setConnectedUI(boolean connected) {
        connectButton.setEnabled(!connected);
        disconnectButton.setEnabled(connected);
        inputField.setEnabled(connected);
        sendButton.setEnabled(connected);
        clientModeBtn.setEnabled(!connected);
        serverModeBtn.setEnabled(!connected);
        if (!connected) setStatus("Not connected", Color.GRAY);
    }

    private void setStatus(String text, Color color) {
        SwingUtilities.invokeLater(() -> {
            statusLabel.setText(text);
            statusLabel.setForeground(color);
        });
    }

    // ── Bubble rendering ──────────────────────────────────────────────────────

    private void addMyBubble(String text) {
        String ts = LocalTime.now().format(TIME_FMT);
        JPanel bubble = createBubble(text, ts, null, MY_BUBBLE_BG, MY_BUBBLE_FG, true);
        appendBubble(bubble);
    }

    private void addTheirBubble(String sender, String text) {
        String ts = LocalTime.now().format(TIME_FMT);
        JPanel bubble = createBubble(text, ts, sender, THEIR_BUBBLE_BG, THEIR_BUBBLE_FG, false);
        appendBubble(bubble);
    }

    private void addSystemMessage(String text) {
        JLabel label = new JLabel(text, SwingConstants.CENTER);
        label.setFont(label.getFont().deriveFont(Font.ITALIC, 11f));
        label.setForeground(SYSTEM_FG);
        label.setAlignmentX(Component.CENTER_ALIGNMENT);
        label.setBorder(new EmptyBorder(4, 0, 4, 0));

        JPanel wrapper = new JPanel(new FlowLayout(FlowLayout.CENTER));
        wrapper.setOpaque(false);
        wrapper.add(label);

        SwingUtilities.invokeLater(() -> appendBubble(wrapper));
    }

    private JPanel createBubble(String text, String timestamp, String senderLabel,
                                Color bgColor, Color fgColor, boolean mine) {
        // Outer wrapper aligns bubble left or right
        JPanel outer = new JPanel(new FlowLayout(mine ? FlowLayout.RIGHT : FlowLayout.LEFT, 6, 2));
        outer.setOpaque(false);
        outer.setMaximumSize(new Dimension(Integer.MAX_VALUE, Integer.MAX_VALUE));

        // Bubble content panel
        JPanel bubble = new JPanel() {
            @Override
            protected void paintComponent(Graphics g) {
                Graphics2D g2 = (Graphics2D) g.create();
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                g2.setColor(bgColor);
                g2.fillRoundRect(0, 0, getWidth() - 1, getHeight() - 1, 16, 16);
                g2.dispose();
                super.paintComponent(g);
            }
        };
        bubble.setLayout(new BoxLayout(bubble, BoxLayout.Y_AXIS));
        bubble.setOpaque(false);
        bubble.setBorder(new EmptyBorder(6, 10, 6, 10));

        // Sender label (only for their messages)
        if (senderLabel != null) {
            JLabel nameLabel = new JLabel(senderLabel);
            nameLabel.setFont(nameLabel.getFont().deriveFont(Font.BOLD, 10f));
            nameLabel.setForeground(new Color(60, 60, 120));
            nameLabel.setAlignmentX(Component.LEFT_ALIGNMENT);
            bubble.add(nameLabel);
            bubble.add(Box.createVerticalStrut(2));
        }

        // Message text (word-wrapped using JTextArea trick inside JPanel)
        JTextArea messageText = new JTextArea(text);
        messageText.setEditable(false);
        messageText.setLineWrap(true);
        messageText.setWrapStyleWord(true);
        messageText.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 13));
        messageText.setForeground(fgColor);
        messageText.setBackground(bgColor);
        messageText.setOpaque(false);
        messageText.setMaximumSize(new Dimension(340, Integer.MAX_VALUE));
        messageText.setPreferredSize(null);
        messageText.setAlignmentX(mine ? Component.RIGHT_ALIGNMENT : Component.LEFT_ALIGNMENT);
        bubble.add(messageText);

        // Timestamp
        JLabel tsLabel = new JLabel(timestamp);
        tsLabel.setFont(tsLabel.getFont().deriveFont(Font.PLAIN, 9f));
        tsLabel.setForeground(mine ? new Color(180, 220, 255) : new Color(130, 130, 130));
        tsLabel.setAlignmentX(mine ? Component.RIGHT_ALIGNMENT : Component.LEFT_ALIGNMENT);
        bubble.add(Box.createVerticalStrut(2));
        bubble.add(tsLabel);

        outer.add(bubble);
        return outer;
    }

    private void appendBubble(JPanel bubble) {
        bubbleContainer.add(bubble);
        bubbleContainer.add(Box.createVerticalStrut(4));
        bubbleContainer.revalidate();
        bubbleContainer.repaint();
        // Scroll to bottom
        SwingUtilities.invokeLater(() -> {
            JScrollBar vsb = scrollPane.getVerticalScrollBar();
            vsb.setValue(vsb.getMaximum());
        });
    }
}
