package com.bluetoothtool;

import org.json.JSONArray;
import org.json.JSONObject;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import javax.swing.border.TitledBorder;
import javax.swing.table.DefaultTableModel;
import javax.swing.text.*;
import java.awt.*;
import java.awt.datatransfer.StringSelection;

/**
 * BLE tab — delegates all BLE operations to the Python bridge subprocess
 * via BleBridgeManager (WebSocket JSON protocol).
 *
 * Layout (horizontal split):
 *   Left  — device scanner + scan controls
 *   Right — connected device: services list → characteristics list + actions + log
 */
public class BlePanel extends JPanel {

    private final BleBridgeManager bridge;

    // ── Scanner ───────────────────────────────────────────────────────────────
    private JButton   scanBtn;
    private JButton   stopScanBtn;
    private JButton   connectBtn;
    private JLabel    scanStatus;
    private DefaultTableModel deviceTableModel;
    private JTable    deviceTable;

    // ── Connection ────────────────────────────────────────────────────────────
    private JLabel    connStatus;
    private JButton   disconnectBtn;

    // ── Services / characteristics ────────────────────────────────────────────
    private DefaultListModel<String>   serviceListModel;
    private JList<String>              serviceList;
    private DefaultListModel<CharEntry> charListModel;
    private JList<CharEntry>           charList;
    private JSONArray                  currentServices; // raw JSON from bridge

    // ── Characteristic actions ────────────────────────────────────────────────
    private JButton   readBtn;
    private JButton   notifyBtn;
    private JButton   writeBtn;
    private JTextField writeField;
    private JComboBox<String> writeFmt;
    private boolean   notifying = false;

    // ── Log ───────────────────────────────────────────────────────────────────
    private JTextPane logPane;
    private StyledDocument logDoc;
    private SimpleAttributeSet attrInfo, attrRecv, attrSend, attrErr;

    // ── Bridge status bar ─────────────────────────────────────────────────────
    private JLabel bridgeStatusLabel;

    // ─────────────────────────────────────────────────────────────────────────

    public BlePanel(BleBridgeManager bridge) {
        super(new BorderLayout(0, 0));
        this.bridge = bridge;
        buildUI();
        initStyles();
        hookBridge();
    }

    // ── UI construction ───────────────────────────────────────────────────────

    private void buildUI() {
        // Bridge status bar at the top
        bridgeStatusLabel = new JLabel("Bridge: starting…");
        bridgeStatusLabel.setFont(bridgeStatusLabel.getFont().deriveFont(Font.ITALIC, 11f));
        bridgeStatusLabel.setForeground(Color.GRAY);
        bridgeStatusLabel.setBorder(new EmptyBorder(4, 8, 4, 8));
        add(bridgeStatusLabel, BorderLayout.NORTH);

        // Main split: left=scanner, right=device detail
        JSplitPane split = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT,
                buildScannerPanel(), buildDevicePanel());
        split.setDividerLocation(300);
        split.setResizeWeight(0.35);
        add(split, BorderLayout.CENTER);
    }

    private JPanel buildScannerPanel() {
        JPanel p = new JPanel(new BorderLayout(6, 6));
        p.setBorder(new EmptyBorder(8, 8, 8, 4));

        // Table: Name | Address | RSSI
        deviceTableModel = new DefaultTableModel(
                new String[]{"Name", "Address", "RSSI"}, 0) {
            @Override public boolean isCellEditable(int r, int c) { return false; }
        };
        deviceTable = new JTable(deviceTableModel);
        deviceTable.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        deviceTable.getColumnModel().getColumn(0).setPreferredWidth(120);
        deviceTable.getColumnModel().getColumn(1).setPreferredWidth(130);
        deviceTable.getColumnModel().getColumn(2).setPreferredWidth(50);
        JScrollPane scroll = new JScrollPane(deviceTable);
        scroll.setBorder(new TitledBorder("Nearby BLE Devices"));
        p.add(scroll, BorderLayout.CENTER);

        // Controls
        JPanel controls = new JPanel(new BorderLayout(4, 4));
        JPanel btnRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 0));
        scanBtn     = new JButton("Scan");
        stopScanBtn = new JButton("Stop");
        connectBtn  = new JButton("Connect");
        stopScanBtn.setEnabled(false);
        connectBtn.setEnabled(false);

        scanBtn.addActionListener(e -> startScan());
        stopScanBtn.addActionListener(e -> stopScan());
        connectBtn.addActionListener(e -> connectSelected());
        deviceTable.getSelectionModel().addListSelectionListener(
                e -> connectBtn.setEnabled(deviceTable.getSelectedRow() >= 0));

        btnRow.add(scanBtn);
        btnRow.add(stopScanBtn);
        btnRow.add(connectBtn);
        controls.add(btnRow, BorderLayout.CENTER);

        scanStatus = new JLabel(" ");
        scanStatus.setFont(scanStatus.getFont().deriveFont(Font.ITALIC, 11f));
        scanStatus.setForeground(Color.GRAY);
        controls.add(scanStatus, BorderLayout.SOUTH);
        p.add(controls, BorderLayout.SOUTH);
        return p;
    }

    private JPanel buildDevicePanel() {
        JPanel p = new JPanel(new BorderLayout(6, 6));
        p.setBorder(new EmptyBorder(8, 4, 8, 8));

        // Top: connection status + disconnect
        JPanel topBar = new JPanel(new BorderLayout(6, 0));
        connStatus    = new JLabel("Not connected");
        connStatus.setFont(connStatus.getFont().deriveFont(Font.BOLD));
        disconnectBtn = new JButton("Disconnect");
        disconnectBtn.setEnabled(false);
        disconnectBtn.addActionListener(e -> doDisconnect());
        topBar.add(connStatus,    BorderLayout.CENTER);
        topBar.add(disconnectBtn, BorderLayout.EAST);
        p.add(topBar, BorderLayout.NORTH);

        // Services + characteristics (left side of inner split)
        serviceListModel = new DefaultListModel<>();
        serviceList      = new JList<>(serviceListModel);
        serviceList.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        serviceList.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 11));
        JScrollPane svcScroll = new JScrollPane(serviceList);
        svcScroll.setBorder(new TitledBorder("Services"));
        svcScroll.setPreferredSize(new Dimension(200, 0));

        charListModel = new DefaultListModel<>();
        charList      = new JList<>(charListModel);
        charList.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        charList.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 11));
        JScrollPane charScroll = new JScrollPane(charList);
        charScroll.setBorder(new TitledBorder("Characteristics"));

        serviceList.addListSelectionListener(e -> populateChars(serviceList.getSelectedIndex()));
        charList.addListSelectionListener(e -> {
            boolean sel = charList.getSelectedIndex() >= 0;
            readBtn.setEnabled(sel);
            notifyBtn.setEnabled(sel);
            writeBtn.setEnabled(sel);
        });

        JSplitPane treeSplit = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, svcScroll, charScroll);
        treeSplit.setDividerLocation(200);
        treeSplit.setResizeWeight(0.4);

        // Bottom: action row + log
        JPanel bottomPanel = new JPanel(new BorderLayout(0, 4));

        // Action row
        JPanel actionRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 0));
        readBtn    = new JButton("Read");
        notifyBtn  = new JButton("Notify");
        writeBtn   = new JButton("Write");
        writeField = new JTextField(14);
        writeFmt   = new JComboBox<>(new String[]{"Hex", "UTF-8"});
        readBtn.setEnabled(false);
        notifyBtn.setEnabled(false);
        writeBtn.setEnabled(false);

        readBtn.addActionListener(e    -> doRead());
        notifyBtn.addActionListener(e  -> doToggleNotify());
        writeBtn.addActionListener(e   -> doWrite());

        actionRow.add(readBtn);
        actionRow.add(notifyBtn);
        actionRow.add(new JSeparator(SwingConstants.VERTICAL));
        actionRow.add(writeField);
        actionRow.add(writeFmt);
        actionRow.add(writeBtn);

        // Log
        logPane = new JTextPane();
        logPane.setEditable(false);
        logPane.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
        logDoc  = logPane.getStyledDocument();
        JScrollPane logScroll = new JScrollPane(logPane);
        logScroll.setBorder(new TitledBorder("Log"));

        // Clear + Copy buttons for log
        JPanel logBtns = new JPanel(new FlowLayout(FlowLayout.RIGHT, 4, 0));
        JButton clearBtn = new JButton("Clear");
        JButton copyBtn  = new JButton("Copy");
        clearBtn.addActionListener(e -> logPane.setText(""));
        copyBtn.addActionListener(e  -> copyLog());
        logBtns.add(clearBtn);
        logBtns.add(copyBtn);

        bottomPanel.add(actionRow,  BorderLayout.NORTH);
        bottomPanel.add(logScroll,  BorderLayout.CENTER);
        bottomPanel.add(logBtns,    BorderLayout.SOUTH);

        // Inner vertical split: tree top, actions+log bottom
        JSplitPane vertSplit = new JSplitPane(JSplitPane.VERTICAL_SPLIT, treeSplit, bottomPanel);
        vertSplit.setDividerLocation(220);
        vertSplit.setResizeWeight(0.4);

        p.add(vertSplit, BorderLayout.CENTER);
        return p;
    }

    // ── Styles ────────────────────────────────────────────────────────────────

    private void initStyles() {
        attrInfo = new SimpleAttributeSet();
        StyleConstants.setForeground(attrInfo, Color.DARK_GRAY);

        attrRecv = new SimpleAttributeSet();
        StyleConstants.setForeground(attrRecv, new Color(0, 128, 0));

        attrSend = new SimpleAttributeSet();
        StyleConstants.setForeground(attrSend, new Color(0, 0, 180));

        attrErr = new SimpleAttributeSet();
        StyleConstants.setForeground(attrErr, Color.RED);
        StyleConstants.setBold(attrErr, true);
    }

    // ── Bridge listener ───────────────────────────────────────────────────────

    private void hookBridge() {
        bridge.addListener(new BleBridgeManager.BridgeListener() {
            @Override
            public void onEvent(JSONObject event) {
                SwingUtilities.invokeLater(() -> handleEvent(event));
            }

            @Override
            public void onBridgeStatus(String status, boolean running) {
                SwingUtilities.invokeLater(() -> {
                    bridgeStatusLabel.setText("Bridge: " + status);
                    bridgeStatusLabel.setForeground(running ? new Color(0, 128, 0) : Color.GRAY);
                });
            }
        });
    }

    private void handleEvent(JSONObject ev) {
        String type = ev.optString("event", "");
        if ("device".equals(type)) {
            String name = ev.optString("name", "(unknown)");
            String addr = ev.optString("address", "");
            int    rssi = ev.optInt("rssi", 0);
            for (int i = 0; i < deviceTableModel.getRowCount(); i++) {
                if (addr.equals(deviceTableModel.getValueAt(i, 1))) return;
            }
            deviceTableModel.addRow(new Object[]{
                name.isEmpty() ? "(unnamed)" : name, addr, rssi});
            scanStatus.setText(deviceTableModel.getRowCount() + " device(s) found");
        } else if ("scan_done".equals(type)) {
            scanBtn.setEnabled(true);
            stopScanBtn.setEnabled(false);
            scanStatus.setText("Scan complete — " + ev.optInt("count", 0) + " device(s)");
            log("Scan finished.", attrInfo);
        } else if ("connected".equals(type)) {
            String addr = ev.optString("address", "");
            connStatus.setText("Connected: " + addr);
            connStatus.setForeground(new Color(0, 128, 0));
            disconnectBtn.setEnabled(true);
            log("Connected to " + addr, attrInfo);
            bridge.send("services");
        } else if ("disconnected".equals(type)) {
            connStatus.setText("Not connected");
            connStatus.setForeground(Color.DARK_GRAY);
            disconnectBtn.setEnabled(false);
            serviceListModel.clear();
            charListModel.clear();
            currentServices = null;
            notifying = false;
            notifyBtn.setText("Notify");
            log("Disconnected.", attrInfo);
        } else if ("services".equals(type)) {
            currentServices = ev.optJSONArray("services");
            populateServices();
            log("Services loaded.", attrInfo);
        } else if ("read_result".equals(type)) {
            String hex  = ev.optString("hex", "");
            String text = ev.optString("text", "");
            log("READ  hex=" + hex + (text.isEmpty() ? "" : "  text=\"" + text + "\""), attrRecv);
        } else if ("notify".equals(type)) {
            String hex  = ev.optString("hex", "");
            String text = ev.optString("text", "");
            log("NTFY  hex=" + hex + (text.isEmpty() ? "" : "  text=\"" + text + "\""), attrRecv);
        } else if ("notify_started".equals(type)) {
            notifying = true;
            notifyBtn.setText("Stop Notify");
            log("Notifications started for " + ev.optString("char", ""), attrInfo);
        } else if ("notify_stopped".equals(type)) {
            notifying = false;
            notifyBtn.setText("Notify");
            log("Notifications stopped.", attrInfo);
        } else if ("write_ok".equals(type)) {
            log("WRITE OK  char=" + ev.optString("char", ""), attrSend);
        } else if ("error".equals(type)) {
            log("ERROR: " + ev.optString("message", ""), attrErr);
        } else if ("pong".equals(type)) {
            log("Pong.", attrInfo);
        }
    }

    // ── Service / characteristic population ───────────────────────────────────

    private void populateServices() {
        serviceListModel.clear();
        charListModel.clear();
        if (currentServices == null) return;
        for (int i = 0; i < currentServices.length(); i++) {
            JSONObject svc = currentServices.getJSONObject(i);
            String uuid = svc.optString("uuid", "");
            String desc = svc.optString("desc", "");
            String label = desc.isEmpty() ? uuid : desc + " [" + shorten(uuid) + "]";
            serviceListModel.addElement(label);
        }
        if (serviceListModel.size() > 0) serviceList.setSelectedIndex(0);
    }

    private void populateChars(int serviceIdx) {
        charListModel.clear();
        readBtn.setEnabled(false);
        notifyBtn.setEnabled(false);
        writeBtn.setEnabled(false);
        if (currentServices == null || serviceIdx < 0 || serviceIdx >= currentServices.length()) return;
        JSONArray chars = currentServices.getJSONObject(serviceIdx).optJSONArray("chars");
        if (chars == null) return;
        for (int i = 0; i < chars.length(); i++) {
            JSONObject c = chars.getJSONObject(i);
            charListModel.addElement(new CharEntry(c));
        }
    }

    // ── Commands ──────────────────────────────────────────────────────────────

    private void startScan() {
        deviceTableModel.setRowCount(0);
        scanStatus.setText("Scanning…");
        scanBtn.setEnabled(false);
        stopScanBtn.setEnabled(true);
        log("Starting scan…", attrInfo);
        bridge.send(new JSONObject().put("cmd", "scan_start").put("timeout", 10));
    }

    private void stopScan() {
        bridge.send("scan_stop");
        stopScanBtn.setEnabled(false);
        scanBtn.setEnabled(true);
        scanStatus.setText("Stopped.");
    }

    private void connectSelected() {
        int row = deviceTable.getSelectedRow();
        if (row < 0) return;
        String addr = (String) deviceTableModel.getValueAt(row, 1);
        log("Connecting to " + addr + "…", attrInfo);
        bridge.send(new JSONObject().put("cmd", "connect").put("address", addr));
    }

    private void doDisconnect() {
        bridge.send("disconnect");
    }

    private void doRead() {
        CharEntry ce = charList.getSelectedValue();
        if (ce == null) return;
        int svcIdx = serviceList.getSelectedIndex();
        if (svcIdx < 0 || currentServices == null) return;
        String svcUuid = currentServices.getJSONObject(svcIdx).optString("uuid", "");
        bridge.send(new JSONObject()
                .put("cmd", "read")
                .put("service", svcUuid)
                .put("char", ce.uuid));
    }

    private void doToggleNotify() {
        CharEntry ce = charList.getSelectedValue();
        if (ce == null) return;
        int svcIdx = serviceList.getSelectedIndex();
        if (svcIdx < 0 || currentServices == null) return;
        String svcUuid = currentServices.getJSONObject(svcIdx).optString("uuid", "");
        if (notifying) {
            bridge.send(new JSONObject()
                    .put("cmd", "notify_stop")
                    .put("service", svcUuid)
                    .put("char", ce.uuid));
        } else {
            bridge.send(new JSONObject()
                    .put("cmd", "notify_start")
                    .put("service", svcUuid)
                    .put("char", ce.uuid));
        }
    }

    private void doWrite() {
        CharEntry ce = charList.getSelectedValue();
        if (ce == null) return;
        int svcIdx = serviceList.getSelectedIndex();
        if (svcIdx < 0 || currentServices == null) return;
        String svcUuid = currentServices.getJSONObject(svcIdx).optString("uuid", "");

        String raw = writeField.getText().trim();
        if (raw.isEmpty()) return;

        String fmt = (String) writeFmt.getSelectedItem();
        String hexStr;
        if ("UTF-8".equals(fmt)) {
            try {
                byte[] bytes = raw.getBytes(java.nio.charset.StandardCharsets.UTF_8);
                StringBuilder sb = new StringBuilder();
                for (byte b : bytes) sb.append(String.format("%02X", b));
                hexStr = sb.toString();
            } catch (Exception ex) {
                log("Invalid UTF-8 input", attrErr);
                return;
            }
        } else {
            hexStr = raw.replaceAll("\\s+", "");
            if (!hexStr.matches("[0-9A-Fa-f]+")) {
                log("Invalid hex input: " + hexStr, attrErr);
                return;
            }
        }

        log("WRITE → " + hexStr, attrSend);
        bridge.send(new JSONObject()
                .put("cmd", "write")
                .put("service", svcUuid)
                .put("char", ce.uuid)
                .put("hex", hexStr)
                .put("response", true));
    }

    // ── Log helpers ───────────────────────────────────────────────────────────

    private void log(String text, AttributeSet attr) {
        try {
            logDoc.insertString(logDoc.getLength(), text + "\n", attr);
            logPane.setCaretPosition(logDoc.getLength());
        } catch (BadLocationException ignored) {}
    }

    private void copyLog() {
        String text = logPane.getText();
        Toolkit.getDefaultToolkit().getSystemClipboard()
               .setContents(new StringSelection(text), null);
    }

    // ── Utility ───────────────────────────────────────────────────────────────

    private static String shorten(String uuid) {
        if (uuid == null) return "";
        return uuid.length() > 8 ? uuid.substring(0, 8) + "…" : uuid;
    }

    // ── Inner: CharEntry ──────────────────────────────────────────────────────

    private static class CharEntry {
        final String uuid;
        final String desc;
        final java.util.List<String> props;

        CharEntry(JSONObject obj) {
            this.uuid  = obj.optString("uuid", "");
            this.desc  = obj.optString("desc", "");
            JSONArray pa = obj.optJSONArray("props");
            java.util.List<String> p = new java.util.ArrayList<>();
            if (pa != null) for (int i = 0; i < pa.length(); i++) p.add(pa.getString(i));
            this.props = p;
        }

        @Override
        public String toString() {
            String label = desc.isEmpty() ? shorten(uuid) : desc;
            return label + "  " + props;
        }

        private static String shorten(String uuid) {
            if (uuid == null) return "";
            return uuid.length() > 8 ? uuid.substring(0, 8) + "…" : uuid;
        }
    }
}
