package com.bluetoothtool;

import org.json.JSONArray;
import org.json.JSONObject;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import javax.swing.border.TitledBorder;
import javax.swing.table.DefaultTableModel;
import javax.swing.text.*;
import java.awt.*;
import java.awt.event.ActionEvent;

/**
 * Classic Bluetooth tab — delegates all BT Classic operations to the Python bridge
 * via BleBridgeManager (WebSocket JSON protocol).
 *
 * Layout (horizontal split, divider at 320):
 *   Left  — device scanner, SDP service browser, connect controls
 *   Right — RFCOMM terminal (receive + send)
 */
public class ClassicBtPanel extends JPanel {

    private final BleBridgeManager bridge;

    // ── Left: Scanner ─────────────────────────────────────────────────────────
    private DefaultTableModel deviceTableModel;
    private JTable             deviceTable;
    private JButton            scanBtn;
    private JButton            stopBtn;
    private JButton            browseBtn;
    private JButton            connectBtn;
    private JLabel             scanStatus;

    // ── Left: Service list ────────────────────────────────────────────────────
    private DefaultListModel<ServiceEntry> serviceListModel;
    private JList<ServiceEntry>            serviceList;

    // ── Right: Connection status ──────────────────────────────────────────────
    private JLabel  connStatus;
    private JButton disconnectBtn;

    // ── Right: Terminal ───────────────────────────────────────────────────────
    private JTextPane      termPane;
    private StyledDocument termDoc;
    private SimpleAttributeSet attrRecv, attrSent, attrSys, attrErr;

    // ── Right: Input bar ──────────────────────────────────────────────────────
    private JTextField         inputField;
    private JComboBox<String>  lineEndingCombo;
    private JToggleButton      textModeBtn;
    private JToggleButton      hexModeBtn;
    private JButton            sendBtn;
    private JButton            clearBtn;

    // ── State ─────────────────────────────────────────────────────────────────
    private boolean connected = false;

    // ─────────────────────────────────────────────────────────────────────────

    public ClassicBtPanel(BleBridgeManager bridge) {
        super(new BorderLayout(0, 0));
        this.bridge = bridge;
        buildUI();
        initStyles();
        hookBridge();
        setTerminalEnabled(false);
    }

    // ── UI construction ───────────────────────────────────────────────────────

    private void buildUI() {
        JSplitPane split = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT,
                buildLeftPanel(), buildRightPanel());
        split.setDividerLocation(320);
        split.setResizeWeight(0.35);
        add(split, BorderLayout.CENTER);
    }

    // ── LEFT PANEL ────────────────────────────────────────────────────────────

    private JPanel buildLeftPanel() {
        JPanel p = new JPanel(new BorderLayout(6, 6));
        p.setBorder(new EmptyBorder(8, 8, 8, 4));

        // Device table
        deviceTableModel = new DefaultTableModel(
                new String[]{"Name", "Address", "Class"}, 0) {
            @Override
            public boolean isCellEditable(int r, int c) { return false; }
        };
        deviceTable = new JTable(deviceTableModel);
        deviceTable.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        deviceTable.getColumnModel().getColumn(0).setPreferredWidth(120);
        deviceTable.getColumnModel().getColumn(1).setPreferredWidth(130);
        deviceTable.getColumnModel().getColumn(2).setPreferredWidth(60);
        JScrollPane deviceScroll = new JScrollPane(deviceTable);
        deviceScroll.setBorder(new TitledBorder("Nearby Devices"));

        // Buttons
        scanBtn    = new JButton("Scan");
        stopBtn    = new JButton("Stop");
        browseBtn  = new JButton("Browse Services");
        connectBtn = new JButton("Connect");
        stopBtn.setEnabled(false);
        browseBtn.setEnabled(false);
        connectBtn.setEnabled(false);

        scanBtn.addActionListener(e -> startScan());
        stopBtn.addActionListener(e -> stopScan());
        browseBtn.addActionListener(e -> browseServices());
        connectBtn.addActionListener(e -> connectToService());

        deviceTable.getSelectionModel().addListSelectionListener(e -> {
            boolean sel = deviceTable.getSelectedRow() >= 0;
            browseBtn.setEnabled(sel);
        });

        JPanel btnRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 0));
        btnRow.add(scanBtn);
        btnRow.add(stopBtn);
        btnRow.add(browseBtn);
        btnRow.add(connectBtn);

        scanStatus = new JLabel(" ");
        scanStatus.setFont(scanStatus.getFont().deriveFont(Font.ITALIC, 11f));
        scanStatus.setForeground(Color.GRAY);

        // Service list
        serviceListModel = new DefaultListModel<>();
        serviceList = new JList<>(serviceListModel);
        serviceList.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        serviceList.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 11));
        JScrollPane serviceScroll = new JScrollPane(serviceList);
        serviceScroll.setBorder(new TitledBorder("Services"));

        serviceList.addListSelectionListener(e -> {
            connectBtn.setEnabled(serviceList.getSelectedIndex() >= 0);
        });

        // Assemble bottom of left panel
        JPanel bottomLeft = new JPanel(new BorderLayout(4, 4));
        bottomLeft.add(btnRow,         BorderLayout.NORTH);
        bottomLeft.add(scanStatus,     BorderLayout.CENTER);
        bottomLeft.add(serviceScroll,  BorderLayout.SOUTH);
        serviceScroll.setPreferredSize(new Dimension(0, 180));

        p.add(deviceScroll, BorderLayout.CENTER);
        p.add(bottomLeft,   BorderLayout.SOUTH);
        return p;
    }

    // ── RIGHT PANEL ───────────────────────────────────────────────────────────

    private JPanel buildRightPanel() {
        JPanel p = new JPanel(new BorderLayout(6, 6));
        p.setBorder(new EmptyBorder(8, 4, 8, 8));

        // Top bar: connection status + disconnect
        JPanel topBar = new JPanel(new BorderLayout(6, 0));
        connStatus    = new JLabel("Not connected");
        connStatus.setFont(connStatus.getFont().deriveFont(Font.BOLD));
        disconnectBtn = new JButton("Disconnect");
        disconnectBtn.setEnabled(false);
        disconnectBtn.addActionListener(e -> doDisconnect());
        topBar.add(connStatus,    BorderLayout.CENTER);
        topBar.add(disconnectBtn, BorderLayout.EAST);
        p.add(topBar, BorderLayout.NORTH);

        // Terminal pane
        termPane = new JTextPane();
        termPane.setEditable(false);
        termPane.setBackground(new Color(24, 24, 24));
        termPane.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
        termDoc = termPane.getStyledDocument();
        JScrollPane termScroll = new JScrollPane(termPane);
        termScroll.setBorder(new TitledBorder("Terminal"));
        p.add(termScroll, BorderLayout.CENTER);

        // Input bar
        p.add(buildInputBar(), BorderLayout.SOUTH);
        return p;
    }

    private JPanel buildInputBar() {
        JPanel bar = new JPanel(new BorderLayout(4, 4));
        bar.setBorder(new EmptyBorder(4, 0, 0, 0));

        inputField     = new JTextField();
        lineEndingCombo = new JComboBox<>(new String[]{"None", "CR", "LF", "CR+LF"});
        lineEndingCombo.setSelectedItem("CR+LF");

        textModeBtn = new JToggleButton("Text", true);
        hexModeBtn  = new JToggleButton("Hex",  false);
        ButtonGroup modeGroup = new ButtonGroup();
        modeGroup.add(textModeBtn);
        modeGroup.add(hexModeBtn);

        sendBtn  = new JButton("Send");
        clearBtn = new JButton("Clear");

        sendBtn.addActionListener(e -> doSend());
        clearBtn.addActionListener(e -> termPane.setText(""));
        inputField.addActionListener(e -> doSend());

        JPanel rightControls = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 0));
        rightControls.add(lineEndingCombo);
        rightControls.add(textModeBtn);
        rightControls.add(hexModeBtn);
        rightControls.add(sendBtn);
        rightControls.add(clearBtn);

        bar.add(inputField,     BorderLayout.CENTER);
        bar.add(rightControls,  BorderLayout.EAST);
        return bar;
    }

    // ── Styles ────────────────────────────────────────────────────────────────

    private void initStyles() {
        attrRecv = new SimpleAttributeSet();
        StyleConstants.setForeground(attrRecv, new Color(100, 180, 255));

        attrSent = new SimpleAttributeSet();
        StyleConstants.setForeground(attrSent, new Color(100, 220, 100));

        attrSys = new SimpleAttributeSet();
        StyleConstants.setForeground(attrSys, Color.GRAY);
        StyleConstants.setItalic(attrSys, true);

        attrErr = new SimpleAttributeSet();
        StyleConstants.setForeground(attrErr, new Color(255, 80, 80));
        StyleConstants.setBold(attrErr, true);
    }

    // ── Bridge listener ───────────────────────────────────────────────────────

    private void hookBridge() {
        bridge.addListener(new BleBridgeManager.BridgeListener() {
            @Override
            public void onEvent(JSONObject event) {
                SwingUtilities.invokeLater(() -> handleEvent(event));
            }
        });
    }

    private void handleEvent(JSONObject ev) {
        String type = ev.optString("event", "");

        if ("bt_device".equals(type)) {
            String addr = ev.optString("address", "");
            String name = ev.optString("name", "");
            int    cod  = ev.optInt("cod", 0);
            // Avoid duplicates by address
            for (int i = 0; i < deviceTableModel.getRowCount(); i++) {
                if (addr.equals(deviceTableModel.getValueAt(i, 1))) return;
            }
            deviceTableModel.addRow(new Object[]{
                name.isEmpty() ? "(unnamed)" : name,
                addr,
                "0x" + Integer.toHexString(cod).toUpperCase()
            });
            scanStatus.setText(deviceTableModel.getRowCount() + " device(s) found");

        } else if ("bt_scan_done".equals(type)) {
            scanBtn.setEnabled(true);
            stopBtn.setEnabled(false);
            scanStatus.setText("Scan complete — " + deviceTableModel.getRowCount() + " device(s)");

        } else if ("bt_services".equals(type)) {
            serviceListModel.clear();
            JSONArray services = ev.optJSONArray("services");
            if (services != null) {
                for (int i = 0; i < services.length(); i++) {
                    JSONObject svc = services.getJSONObject(i);
                    String address  = svc.optString("host", "");
                    // If host is empty, use the address from the currently selected device
                    if (address.isEmpty()) {
                        int row = deviceTable.getSelectedRow();
                        if (row >= 0) {
                            address = (String) deviceTableModel.getValueAt(row, 1);
                        }
                    }
                    serviceListModel.addElement(new ServiceEntry(
                            svc.optString("name", "(unknown)"),
                            address,
                            svc.optInt("port", 0),
                            svc.optString("protocol", "RFCOMM"),
                            svc.optString("description", "")
                    ));
                }
            }
            scanStatus.setText(serviceListModel.size() + " RFCOMM service(s) found");

        } else if ("bt_connected".equals(type)) {
            String addr = ev.optString("address", "");
            int    port = ev.optInt("port", 0);
            connected = true;
            connStatus.setText("Connected: " + addr + " (port " + port + ")");
            connStatus.setForeground(new Color(100, 220, 100));
            disconnectBtn.setEnabled(true);
            setTerminalEnabled(true);
            appendTerm("[Connected to " + addr + " port " + port + "]\n", attrSys);

        } else if ("bt_disconnected".equals(type)) {
            connected = false;
            connStatus.setText("Not connected");
            connStatus.setForeground(Color.GRAY);
            disconnectBtn.setEnabled(false);
            setTerminalEnabled(false);
            appendTerm("[Disconnected]\n", attrSys);

        } else if ("bt_data".equals(type)) {
            String hex  = ev.optString("hex", "");
            String text = ev.optString("text", "");
            if (hexModeBtn.isSelected() || text.isEmpty()) {
                appendTerm("RX: " + hex + "\n", attrRecv);
            } else {
                appendTerm(text, attrRecv);
            }

        } else if ("bt_error".equals(type)) {
            appendTerm("ERROR: " + ev.optString("message", "") + "\n", attrErr);
        }
    }

    // ── Commands ──────────────────────────────────────────────────────────────

    private void startScan() {
        deviceTableModel.setRowCount(0);
        serviceListModel.clear();
        scanStatus.setText("Scanning for Classic BT devices…");
        scanBtn.setEnabled(false);
        stopBtn.setEnabled(true);
        browseBtn.setEnabled(false);
        connectBtn.setEnabled(false);
        bridge.send(new JSONObject().put("cmd", "bt_scan").put("timeout", 8));
    }

    private void stopScan() {
        // Classic BT scan cannot be interrupted mid-flight by pybluez2;
        // disable the stop button and inform the user
        stopBtn.setEnabled(false);
        scanStatus.setText("Waiting for scan to finish…");
    }

    private void browseServices() {
        int row = deviceTable.getSelectedRow();
        if (row < 0) return;
        String addr = (String) deviceTableModel.getValueAt(row, 1);
        scanStatus.setText("Browsing services on " + addr + "…");
        serviceListModel.clear();
        connectBtn.setEnabled(false);
        bridge.send(new JSONObject().put("cmd", "bt_services").put("address", addr));
    }

    private void connectToService() {
        ServiceEntry svc = serviceList.getSelectedValue();
        if (svc == null) return;
        appendTerm("[Connecting to " + svc.address + " port " + svc.port + "…]\n", attrSys);
        bridge.send(new JSONObject()
                .put("cmd",     "bt_connect")
                .put("address", svc.address)
                .put("port",    svc.port));
    }

    private void doDisconnect() {
        bridge.send("bt_disconnect");
    }

    private void doSend() {
        if (!connected) return;
        String input = inputField.getText();
        if (input.isEmpty()) return;

        if (hexModeBtn.isSelected()) {
            // Hex mode: send raw bytes
            String hex = input.replaceAll("\\s+", "");
            if (!hex.matches("[0-9A-Fa-f]+")) {
                appendTerm("ERROR: Invalid hex input\n", attrErr);
                return;
            }
            appendTerm("TX: " + hex.toUpperCase() + "\n", attrSent);
            bridge.send(new JSONObject().put("cmd", "bt_send").put("hex", hex));
        } else {
            // Text mode: append line ending
            String lineEnding = (String) lineEndingCombo.getSelectedItem();
            String suffix;
            if ("CR".equals(lineEnding)) {
                suffix = "\r";
            } else if ("LF".equals(lineEnding)) {
                suffix = "\n";
            } else if ("CR+LF".equals(lineEnding)) {
                suffix = "\r\n";
            } else {
                suffix = "";
            }
            String text = input + suffix;
            appendTerm("TX: " + input + "\n", attrSent);
            bridge.send(new JSONObject().put("cmd", "bt_send_text").put("text", text));
        }

        inputField.setText("");
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private void appendTerm(String text, AttributeSet attr) {
        try {
            termDoc.insertString(termDoc.getLength(), text, attr);
            termPane.setCaretPosition(termDoc.getLength());
        } catch (BadLocationException ignored) {}
    }

    private void setTerminalEnabled(boolean enabled) {
        sendBtn.setEnabled(enabled);
        inputField.setEnabled(enabled);
        lineEndingCombo.setEnabled(enabled);
        textModeBtn.setEnabled(enabled);
        hexModeBtn.setEnabled(enabled);
    }

    // ── Inner: ServiceEntry ───────────────────────────────────────────────────

    private static class ServiceEntry {
        final String name;
        final String address;
        final int    port;
        final String protocol;
        final String description;

        ServiceEntry(String name, String address, int port, String protocol, String description) {
            this.name        = name;
            this.address     = address;
            this.port        = port;
            this.protocol    = protocol;
            this.description = description;
        }

        @Override
        public String toString() {
            return name + " [" + protocol + ":" + port + "]";
        }
    }
}
