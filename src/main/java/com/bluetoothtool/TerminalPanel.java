package com.bluetoothtool;

import javax.bluetooth.LocalDevice;
import javax.bluetooth.UUID;
import javax.microedition.io.Connector;
import javax.microedition.io.StreamConnection;
import javax.microedition.io.StreamConnectionNotifier;
import javax.swing.*;
import javax.swing.border.EmptyBorder;
import javax.swing.border.TitledBorder;
import javax.swing.text.*;
import java.awt.*;
import java.io.IOException;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.List;

/**
 * Terminal tab — raw RFCOMM serial terminal for Arduino, HC-05/HC-06, etc.
 */
public class TerminalPanel extends JPanel {

    private static final String SPP_UUID = "0000110100001000800000805F9B34FB";
    private static final UUID   SPP_UUID_OBJ  = new UUID(0x1101); // used for SDP lookup
    private static final DateTimeFormatter TIME_FMT = DateTimeFormatter.ofPattern("HH:mm:ss");

    // Colors
    private static final Color COLOR_RECEIVED = new Color(0, 0, 160);
    private static final Color COLOR_SENT      = new Color(0, 100, 0);
    private static final Color COLOR_SYSTEM    = new Color(100, 100, 100);
    private static final Color COLOR_ERROR     = new Color(180, 0, 0);

    private final SharedState state;

    // Mode
    private boolean serverMode = false;
    private volatile StreamConnectionNotifier serverNotifier;
    private final BluetoothConnection connection = new BluetoothConnection();

    // Top bar
    private JToggleButton modeServerToggle;
    private JToggleButton modeClientToggle;
    private JButton connectButton;
    private JButton disconnectButton;
    private JLabel statusLabel;

    // Client controls
    private JComboBox<SharedState.DeviceEntry> deviceCombo;
    private JTextField manualAddressField;
    private JPanel clientPanel;

    // Output area
    private JTextPane outputPane;
    private StyledDocument outputDoc;

    // Input bar
    private JTextField inputField;
    private JButton sendButton;
    private JComboBox<String> lineEndingCombo;
    private JToggleButton textModeToggle;
    private JToggleButton hexModeToggle;
    private JButton clearButton;
    private JCheckBox timestampCheck;

    // Styles
    private Style receivedStyle;
    private Style sentStyle;
    private Style systemStyle;
    private Style errorStyle;

    public TerminalPanel(SharedState state) {
        this.state = state;
        setLayout(new BorderLayout(6, 6));
        setBorder(new EmptyBorder(10, 10, 10, 10));
        buildUI();

        // Listen for device list changes to refresh combo
        state.addListener(new SharedState.StateListener() {
            @Override
            public void onDevicesChanged(List<SharedState.DeviceEntry> devices) {
                SwingUtilities.invokeLater(() -> refreshDeviceCombo(devices));
            }
        });
    }

    private void buildUI() {
        add(buildTopBar(), BorderLayout.NORTH);
        add(buildOutputArea(), BorderLayout.CENTER);
        add(buildInputBar(), BorderLayout.SOUTH);
        updateModeUI();
    }

    // ── Top bar ───────────────────────────────────────────────────────────────

    private JPanel buildTopBar() {
        JPanel topPanel = new JPanel(new BorderLayout(6, 4));

        // Mode toggle
        JPanel modeRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 0));
        modeRow.add(new JLabel("Mode:"));

        ButtonGroup modeGroup = new ButtonGroup();
        modeClientToggle = new JToggleButton("Client", true);
        modeServerToggle = new JToggleButton("Server");
        modeGroup.add(modeClientToggle);
        modeGroup.add(modeServerToggle);

        modeClientToggle.addActionListener(e -> { serverMode = false; updateModeUI(); });
        modeServerToggle.addActionListener(e -> { serverMode = true; updateModeUI(); });

        modeRow.add(modeClientToggle);
        modeRow.add(modeServerToggle);

        connectButton = new JButton("Connect");
        disconnectButton = new JButton("Disconnect");
        disconnectButton.setEnabled(false);
        connectButton.addActionListener(e -> handleConnect());
        disconnectButton.addActionListener(e -> handleDisconnect());

        modeRow.add(Box.createHorizontalStrut(8));
        modeRow.add(connectButton);
        modeRow.add(disconnectButton);

        statusLabel = new JLabel("Not connected");
        statusLabel.setFont(statusLabel.getFont().deriveFont(Font.ITALIC));
        statusLabel.setForeground(Color.GRAY);
        modeRow.add(Box.createHorizontalStrut(12));
        modeRow.add(statusLabel);

        topPanel.add(modeRow, BorderLayout.NORTH);

        // Client target selection panel
        clientPanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 2));
        clientPanel.setBorder(new TitledBorder("Target Device"));

        deviceCombo = new JComboBox<>();
        deviceCombo.setPreferredSize(new Dimension(260, 24));
        deviceCombo.setToolTipText("Select a discovered device");

        clientPanel.add(new JLabel("Device:"));
        clientPanel.add(deviceCombo);
        clientPanel.add(new JLabel("  or address:"));

        manualAddressField = new JTextField(14);
        manualAddressField.setToolTipText("Manual Bluetooth address, e.g. 00:11:22:33:44:55");
        clientPanel.add(manualAddressField);

        topPanel.add(clientPanel, BorderLayout.CENTER);
        return topPanel;
    }

    // ── Output area ───────────────────────────────────────────────────────────

    private JScrollPane buildOutputArea() {
        outputPane = new JTextPane();
        outputPane.setEditable(false);
        outputPane.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
        outputPane.setBackground(new Color(24, 24, 24));

        outputDoc = outputPane.getStyledDocument();

        // Define styles
        receivedStyle = outputDoc.addStyle("received", null);
        StyleConstants.setForeground(receivedStyle, COLOR_RECEIVED);
        StyleConstants.setBold(receivedStyle, false);

        sentStyle = outputDoc.addStyle("sent", null);
        StyleConstants.setForeground(sentStyle, COLOR_SENT);
        StyleConstants.setBold(sentStyle, true);

        systemStyle = outputDoc.addStyle("system", null);
        StyleConstants.setForeground(systemStyle, new Color(160, 160, 160));
        StyleConstants.setItalic(systemStyle, true);

        errorStyle = outputDoc.addStyle("error", null);
        StyleConstants.setForeground(errorStyle, COLOR_ERROR);
        StyleConstants.setBold(errorStyle, true);

        // Override colors for dark background
        StyleConstants.setForeground(receivedStyle, new Color(100, 180, 255));
        StyleConstants.setForeground(sentStyle, new Color(100, 220, 100));
        StyleConstants.setForeground(systemStyle, new Color(180, 180, 180));
        StyleConstants.setForeground(errorStyle, new Color(255, 100, 100));

        JScrollPane scrollPane = new JScrollPane(outputPane);
        scrollPane.setBorder(new TitledBorder("Output"));
        return scrollPane;
    }

    // ── Input bar ─────────────────────────────────────────────────────────────

    private JPanel buildInputBar() {
        JPanel inputBar = new JPanel(new BorderLayout(6, 0));
        inputBar.setBorder(new EmptyBorder(4, 0, 0, 0));

        // Input field + send
        JPanel sendRow = new JPanel(new BorderLayout(4, 0));
        inputField = new JTextField();
        inputField.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
        inputField.setEnabled(false);
        inputField.addActionListener(e -> sendData());

        sendButton = new JButton("Send");
        sendButton.setEnabled(false);
        sendButton.addActionListener(e -> sendData());

        sendRow.add(inputField, BorderLayout.CENTER);
        sendRow.add(sendButton, BorderLayout.EAST);

        // Options row
        JPanel optRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));

        optRow.add(new JLabel("Line ending:"));
        lineEndingCombo = new JComboBox<>(new String[]{"None", "CR", "LF", "CR+LF"});
        lineEndingCombo.setSelectedIndex(3); // Default CR+LF
        optRow.add(lineEndingCombo);

        optRow.add(Box.createHorizontalStrut(10));

        ButtonGroup viewGroup = new ButtonGroup();
        textModeToggle = new JToggleButton("Text", true);
        hexModeToggle  = new JToggleButton("Hex");
        viewGroup.add(textModeToggle);
        viewGroup.add(hexModeToggle);
        optRow.add(new JLabel("View:"));
        optRow.add(textModeToggle);
        optRow.add(hexModeToggle);

        optRow.add(Box.createHorizontalStrut(10));

        timestampCheck = new JCheckBox("Timestamps");
        optRow.add(timestampCheck);

        optRow.add(Box.createHorizontalStrut(10));

        clearButton = new JButton("Clear");
        clearButton.addActionListener(e -> {
            try {
                outputDoc.remove(0, outputDoc.getLength());
            } catch (BadLocationException ignored) {}
        });
        optRow.add(clearButton);

        inputBar.add(optRow, BorderLayout.NORTH);
        inputBar.add(sendRow, BorderLayout.CENTER);

        return inputBar;
    }

    // ── Mode UI ───────────────────────────────────────────────────────────────

    private void updateModeUI() {
        clientPanel.setVisible(!serverMode);
        connectButton.setText(serverMode ? "Listen" : "Connect");
    }

    private void refreshDeviceCombo(java.util.List<SharedState.DeviceEntry> devices) {
        SharedState.DeviceEntry current = (SharedState.DeviceEntry) deviceCombo.getSelectedItem();
        deviceCombo.removeAllItems();
        for (SharedState.DeviceEntry d : devices) {
            deviceCombo.addItem(d);
        }
        if (current != null) deviceCombo.setSelectedItem(current);
    }

    // ── Connect / Disconnect ──────────────────────────────────────────────────

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
        appendSystem("[Disconnected]");
    }

    private void startServer() {
        connectButton.setEnabled(false);
        setStatus("Opening RFCOMM server…", Color.GRAY);
        appendSystem("[Server] Opening RFCOMM service (SPP UUID)…");

        new Thread(() -> {
            try {
                String url = "btspp://localhost:" + SPP_UUID
                        + ";name=BT-Terminal;authenticate=false;encrypt=false;master=false";
                serverNotifier = (StreamConnectionNotifier) Connector.open(url);
                appendSystem("[Server] Waiting for connection…");
                SwingUtilities.invokeLater(() -> setStatus("Waiting for connection…", Color.ORANGE));

                StreamConnection sc = serverNotifier.acceptAndOpen();

                connection.open(sc);
                SwingUtilities.invokeLater(() -> {
                    setConnectedUI(true);
                    setStatus("Client connected (server mode)", new Color(0, 128, 0));
                    appendSystem("[Server] Client connected.");
                });

                connection.startReading(
                    line -> appendReceived(line),
                    hexModeToggle.isSelected() ? bytes -> appendReceivedBytes(bytes) : null,
                    () -> {
                        SwingUtilities.invokeLater(() -> {
                            setConnectedUI(false);
                            appendSystem("[Server] Client disconnected.");
                        });
                    }
                );

            } catch (IOException e) {
                SwingUtilities.invokeLater(() -> {
                    connectButton.setEnabled(true);
                    setStatus("Server error: " + e.getMessage(), Color.RED);
                    appendError("[Server] " + e.getMessage());
                });
            }
        }, "bt-terminal-server").start();
    }

    private void startClient() {
        String targetAddress = manualAddressField.getText().trim();
        SharedState.DeviceEntry selected = (SharedState.DeviceEntry) deviceCombo.getSelectedItem();

        if (targetAddress.isEmpty() && selected == null) {
            JOptionPane.showMessageDialog(this, "Select a device or enter a manual address.", "No Target", JOptionPane.WARNING_MESSAGE);
            return;
        }

        // Build raw address from selected device if no manual address
        String rawAddr;
        if (!targetAddress.isEmpty()) {
            rawAddr = targetAddress.replace(":", "").replace("-", "").toUpperCase();
        } else {
            rawAddr = selected.device.getBluetoothAddress().toUpperCase();
        }

        connectButton.setEnabled(false);
        setStatus("Looking up SPP service…", Color.ORANGE);
        appendSystem("[Client] Looking up SPP service on " + rawAddr + "…");

        final String finalAddr = rawAddr;
        new Thread(() -> {
            try {
                // SDP lookup: get a URL with the correct numeric RFCOMM channel
                String url = (selected != null)
                        ? SdpHelper.lookupConnectionUrl(selected.device, SPP_UUID_OBJ)
                        : SdpHelper.lookupConnectionUrl(finalAddr, SPP_UUID_OBJ);

                SwingUtilities.invokeLater(() ->
                    appendSystem("[Client] Connecting via " + url + "…"));

                connection.open(url);
                SwingUtilities.invokeLater(() -> {
                    setConnectedUI(true);
                    setStatus("Connected (client mode)", new Color(0, 128, 0));
                    appendSystem("[Client] Connected.");
                });

                connection.startReading(
                    line -> appendReceived(line),
                    hexModeToggle.isSelected() ? bytes -> appendReceivedBytes(bytes) : null,
                    () -> SwingUtilities.invokeLater(() -> {
                        setConnectedUI(false);
                        appendSystem("[Client] Connection closed.");
                    })
                );

            } catch (SdpHelper.BluetoothException e) {
                SwingUtilities.invokeLater(() -> {
                    connectButton.setEnabled(true);
                    setStatus("SDP lookup failed: " + e.getMessage(), Color.RED);
                    appendError("[Client] SDP: " + e.getMessage());
                });
            } catch (IOException e) {
                SwingUtilities.invokeLater(() -> {
                    connectButton.setEnabled(true);
                    setStatus("Connect failed: " + e.getMessage(), Color.RED);
                    appendError("[Client] " + e.getMessage());
                });
            }
        }, "bt-terminal-client").start();
    }

    // ── Send ──────────────────────────────────────────────────────────────────

    private void sendData() {
        String text = inputField.getText();
        if (text.isEmpty()) return;
        if (!connection.isOpen()) return;

        String suffix = switch ((String) lineEndingCombo.getSelectedItem()) {
            case "CR"    -> "\r";
            case "LF"    -> "\n";
            case "CR+LF" -> "\r\n";
            default      -> "";
        };

        try {
            byte[] data = (text + suffix).getBytes("UTF-8");
            connection.sendBytes(data);
            appendSent(text);
            inputField.setText("");
        } catch (IOException e) {
            appendError("[Send error] " + e.getMessage());
        }
    }

    // ── UI helpers ────────────────────────────────────────────────────────────

    private void setConnectedUI(boolean connected) {
        connectButton.setEnabled(!connected);
        disconnectButton.setEnabled(connected);
        inputField.setEnabled(connected);
        sendButton.setEnabled(connected);
        modeClientToggle.setEnabled(!connected);
        modeServerToggle.setEnabled(!connected);
        if (!connected) {
            setStatus("Not connected", Color.GRAY);
        }
    }

    private void setStatus(String text, Color color) {
        SwingUtilities.invokeLater(() -> {
            statusLabel.setText(text);
            statusLabel.setForeground(color);
        });
    }

    private void appendReceived(String text) {
        if (hexModeToggle.isSelected()) return; // bytes handler covers hex mode
        String prefix = timestampCheck.isSelected() ? "[" + LocalTime.now().format(TIME_FMT) + "] " : "";
        appendStyled(prefix + text + "\n", receivedStyle);
    }

    private void appendReceivedBytes(byte[] bytes) {
        if (!hexModeToggle.isSelected()) return;
        StringBuilder hex = new StringBuilder();
        String prefix = timestampCheck.isSelected() ? "[" + LocalTime.now().format(TIME_FMT) + "] " : "";
        hex.append(prefix);
        for (byte b : bytes) {
            hex.append(String.format("%02X ", b));
        }
        hex.append("\n");
        appendStyled(hex.toString(), receivedStyle);
    }

    private void appendSent(String text) {
        String prefix = timestampCheck.isSelected() ? "[" + LocalTime.now().format(TIME_FMT) + "] " : "";
        appendStyled(prefix + "> " + text + "\n", sentStyle);
    }

    private void appendSystem(String text) {
        SwingUtilities.invokeLater(() -> appendStyled(text + "\n", systemStyle));
    }

    private void appendError(String text) {
        SwingUtilities.invokeLater(() -> appendStyled(text + "\n", errorStyle));
    }

    private void appendStyled(String text, Style style) {
        SwingUtilities.invokeLater(() -> {
            try {
                outputDoc.insertString(outputDoc.getLength(), text, style);
                outputPane.setCaretPosition(outputDoc.getLength());
            } catch (BadLocationException ignored) {}
        });
    }
}
