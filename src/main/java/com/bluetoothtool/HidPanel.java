package com.bluetoothtool;

import org.json.JSONArray;
import org.json.JSONObject;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import javax.swing.border.TitledBorder;
import javax.swing.table.DefaultTableModel;
import javax.swing.text.*;
import java.awt.*;
import java.util.ArrayList;
import java.util.List;

/**
 * HID tab — delegates all HID operations to the Python bridge subprocess
 * via BleBridgeManager (WebSocket JSON protocol).
 *
 * Layout (vertical, BorderLayout):
 *   NORTH  — device table + Refresh / Open / Close buttons
 *   CENTER — vertical split:
 *              top    — input reports viewer
 *              bottom — output report writer
 */
public class HidPanel extends JPanel {

    private final BleBridgeManager bridge;

    // ── Device section ────────────────────────────────────────────────────────
    private DefaultTableModel deviceTableModel;
    private JTable             deviceTable;
    private JButton            refreshBtn;
    private JButton            openBtn;
    private JButton            closeBtn;
    private JLabel             deviceStatus;

    /** Parallel list that holds the path for each table row (not shown in table). */
    private final List<String> devicePaths = new ArrayList<>();

    // ── Input reports ─────────────────────────────────────────────────────────
    private JTextPane      inputPane;
    private StyledDocument inputDoc;
    private JButton        startReadBtn;
    private JButton        stopReadBtn;
    private JButton        clearInputBtn;
    private boolean        reading = false;

    // ── Output report ─────────────────────────────────────────────────────────
    private JTextField outputHexField;
    private JButton    writeBtn;
    private JLabel     decodedLabel;

    // ── Styles ────────────────────────────────────────────────────────────────
    private SimpleAttributeSet attrData, attrSys, attrErr;

    // ─────────────────────────────────────────────────────────────────────────

    public HidPanel(BleBridgeManager bridge) {
        super(new BorderLayout(0, 4));
        this.bridge = bridge;
        buildUI();
        initStyles();
        hookBridge();
        setDeviceControlsEnabled(false);
    }

    // ── UI construction ───────────────────────────────────────────────────────

    private void buildUI() {
        add(buildNorthPanel(), BorderLayout.NORTH);
        add(buildCenterSplit(), BorderLayout.CENTER);
    }

    // ── NORTH: Device table ───────────────────────────────────────────────────

    private JPanel buildNorthPanel() {
        JPanel p = new JPanel(new BorderLayout(6, 4));
        p.setBorder(new EmptyBorder(8, 8, 4, 8));

        deviceTableModel = new DefaultTableModel(
                new String[]{"Manufacturer", "Product", "VID", "PID", "Usage Page", "Usage"}, 0) {
            @Override
            public boolean isCellEditable(int r, int c) { return false; }
        };
        deviceTable = new JTable(deviceTableModel);
        deviceTable.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        deviceTable.getColumnModel().getColumn(0).setPreferredWidth(130);
        deviceTable.getColumnModel().getColumn(1).setPreferredWidth(150);
        deviceTable.getColumnModel().getColumn(2).setPreferredWidth(55);
        deviceTable.getColumnModel().getColumn(3).setPreferredWidth(55);
        deviceTable.getColumnModel().getColumn(4).setPreferredWidth(80);
        deviceTable.getColumnModel().getColumn(5).setPreferredWidth(60);
        JScrollPane scroll = new JScrollPane(deviceTable);
        scroll.setBorder(new TitledBorder("HID Devices"));
        scroll.setPreferredSize(new Dimension(0, 160));

        deviceTable.getSelectionModel().addListSelectionListener(e -> {
            openBtn.setEnabled(deviceTable.getSelectedRow() >= 0);
        });

        // Buttons
        refreshBtn = new JButton("Refresh");
        openBtn    = new JButton("Open");
        closeBtn   = new JButton("Close");
        openBtn.setEnabled(false);

        refreshBtn.addActionListener(e -> doRefresh());
        openBtn.addActionListener(e -> doOpen());
        closeBtn.addActionListener(e -> doClose());

        JPanel btnRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 0));
        btnRow.add(refreshBtn);
        btnRow.add(openBtn);
        btnRow.add(closeBtn);

        deviceStatus = new JLabel(" ");
        deviceStatus.setFont(deviceStatus.getFont().deriveFont(Font.ITALIC, 11f));
        deviceStatus.setForeground(Color.GRAY);

        JPanel bottomBar = new JPanel(new BorderLayout(4, 2));
        bottomBar.add(btnRow,         BorderLayout.WEST);
        bottomBar.add(deviceStatus,   BorderLayout.CENTER);

        p.add(scroll,     BorderLayout.CENTER);
        p.add(bottomBar,  BorderLayout.SOUTH);
        return p;
    }

    // ── CENTER: Input + Output split ──────────────────────────────────────────

    private JSplitPane buildCenterSplit() {
        JSplitPane split = new JSplitPane(JSplitPane.VERTICAL_SPLIT,
                buildInputPanel(), buildOutputPanel());
        split.setResizeWeight(0.6);
        split.setDividerLocation(0.6);
        return split;
    }

    private JPanel buildInputPanel() {
        JPanel p = new JPanel(new BorderLayout(4, 4));
        p.setBorder(new EmptyBorder(0, 8, 4, 8));

        inputPane = new JTextPane();
        inputPane.setEditable(false);
        inputPane.setBackground(new Color(18, 18, 18));
        inputPane.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
        inputDoc = inputPane.getStyledDocument();
        JScrollPane inputScroll = new JScrollPane(inputPane);
        inputScroll.setBorder(new TitledBorder("Input Reports"));

        startReadBtn  = new JButton("Start Reading");
        stopReadBtn   = new JButton("Stop Reading");
        clearInputBtn = new JButton("Clear");
        stopReadBtn.setEnabled(false);

        startReadBtn.addActionListener(e -> doStartRead());
        stopReadBtn.addActionListener(e -> doStopRead());
        clearInputBtn.addActionListener(e -> inputPane.setText(""));

        JPanel btnRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 0));
        btnRow.add(startReadBtn);
        btnRow.add(stopReadBtn);
        btnRow.add(clearInputBtn);

        p.add(inputScroll, BorderLayout.CENTER);
        p.add(btnRow,      BorderLayout.SOUTH);
        return p;
    }

    private JPanel buildOutputPanel() {
        JPanel p = new JPanel(new BorderLayout(4, 6));
        p.setBorder(new TitledBorder("Output Report"));

        JPanel inner = new JPanel(new GridBagLayout());
        inner.setBorder(new EmptyBorder(6, 8, 6, 8));
        GridBagConstraints gc = new GridBagConstraints();
        gc.insets = new Insets(3, 4, 3, 4);
        gc.anchor = GridBagConstraints.WEST;

        JLabel hexLabel  = new JLabel("Hex bytes (e.g. 00 FF 01):");
        outputHexField   = new JTextField(30);
        writeBtn         = new JButton("Write");
        JLabel noteLabel = new JLabel(
                "(first byte = report ID, use 00 if device has no report IDs)");
        noteLabel.setFont(noteLabel.getFont().deriveFont(Font.ITALIC, 10f));
        noteLabel.setForeground(Color.GRAY);
        decodedLabel     = new JLabel(" ");
        decodedLabel.setFont(decodedLabel.getFont().deriveFont(Font.PLAIN, 11f));

        writeBtn.addActionListener(e -> doWrite());
        outputHexField.addActionListener(e -> doWrite());

        gc.gridx = 0; gc.gridy = 0; gc.gridwidth = 1; gc.fill = GridBagConstraints.NONE;
        inner.add(hexLabel, gc);
        gc.gridx = 1; gc.fill = GridBagConstraints.HORIZONTAL; gc.weightx = 1.0;
        inner.add(outputHexField, gc);
        gc.gridx = 2; gc.fill = GridBagConstraints.NONE; gc.weightx = 0;
        inner.add(writeBtn, gc);

        gc.gridx = 0; gc.gridy = 1; gc.gridwidth = 3; gc.fill = GridBagConstraints.HORIZONTAL;
        inner.add(noteLabel, gc);

        gc.gridy = 2;
        inner.add(decodedLabel, gc);

        p.add(inner, BorderLayout.NORTH);
        return p;
    }

    // ── Styles ────────────────────────────────────────────────────────────────

    private void initStyles() {
        attrData = new SimpleAttributeSet();
        StyleConstants.setForeground(attrData, new Color(100, 220, 180));

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

        if ("hid_devices".equals(type)) {
            deviceTableModel.setRowCount(0);
            devicePaths.clear();
            JSONArray devices = ev.optJSONArray("devices");
            if (devices != null) {
                for (int i = 0; i < devices.length(); i++) {
                    JSONObject d = devices.getJSONObject(i);
                    String path = d.optString("path", "");
                    int    vid  = d.optInt("vendor_id", 0);
                    int    pid  = d.optInt("product_id", 0);
                    int    up   = d.optInt("usage_page", 0);
                    int    u    = d.optInt("usage", 0);
                    deviceTableModel.addRow(new Object[]{
                        d.optString("manufacturer", ""),
                        d.optString("product", ""),
                        String.format("0x%04X", vid),
                        String.format("0x%04X", pid),
                        String.format("0x%04X", up),
                        String.format("0x%04X", u),
                    });
                    devicePaths.add(path);
                }
            }
            deviceStatus.setText(deviceTableModel.getRowCount() + " device(s) found");

        } else if ("hid_opened".equals(type)) {
            String mfr  = ev.optString("manufacturer", "");
            String prod = ev.optString("product", "");
            String label = prod.isEmpty() ? mfr : (mfr.isEmpty() ? prod : mfr + " — " + prod);
            deviceStatus.setText("Opened: " + label);
            setDeviceControlsEnabled(true);
            appendInput("[Device opened: " + label + "]\n", attrSys);

        } else if ("hid_input".equals(type)) {
            String hex    = ev.optString("hex", "");
            int    length = ev.optInt("len", 0);
            appendInput("[" + String.format("%3d", length) + " bytes] " + hex + "\n", attrData);
            updateDecoded(ev);

        } else if ("hid_closed".equals(type)) {
            deviceStatus.setText("Device closed");
            setDeviceControlsEnabled(false);
            reading = false;
            startReadBtn.setEnabled(true);
            stopReadBtn.setEnabled(false);
            appendInput("[Device closed]\n", attrSys);

        } else if ("hid_error".equals(type)) {
            appendInput("ERROR: " + ev.optString("message", "") + "\n", attrErr);
        }
    }

    // ── Commands ──────────────────────────────────────────────────────────────

    private void doRefresh() {
        deviceTableModel.setRowCount(0);
        devicePaths.clear();
        deviceStatus.setText("Enumerating…");
        bridge.send("hid_list");
    }

    private void doOpen() {
        int row = deviceTable.getSelectedRow();
        if (row < 0 || row >= devicePaths.size()) return;
        String path = devicePaths.get(row);
        deviceStatus.setText("Opening…");
        bridge.send(new JSONObject().put("cmd", "hid_open").put("path", path));
    }

    private void doClose() {
        bridge.send("hid_close");
    }

    private void doStartRead() {
        if (reading) return;
        reading = true;
        startReadBtn.setEnabled(false);
        stopReadBtn.setEnabled(true);
        bridge.send("hid_start_read");
    }

    private void doStopRead() {
        reading = false;
        startReadBtn.setEnabled(true);
        stopReadBtn.setEnabled(false);
        bridge.send("hid_stop_read");
    }

    private void doWrite() {
        String hex = outputHexField.getText().trim();
        if (hex.isEmpty()) return;
        hex = hex.replaceAll("\\s+", "");
        if (!hex.matches("[0-9A-Fa-f]+")) {
            appendInput("ERROR: Invalid hex input — " + hex + "\n", attrErr);
            return;
        }
        bridge.send(new JSONObject().put("cmd", "hid_write").put("hex", hex));
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private void appendInput(String text, AttributeSet attr) {
        try {
            inputDoc.insertString(inputDoc.getLength(), text, attr);
            inputPane.setCaretPosition(inputDoc.getLength());
        } catch (BadLocationException ignored) {}
    }

    private void setDeviceControlsEnabled(boolean enabled) {
        startReadBtn.setEnabled(enabled);
        stopReadBtn.setEnabled(false);
        writeBtn.setEnabled(enabled);
        outputHexField.setEnabled(enabled);
        closeBtn.setEnabled(enabled);
        if (!enabled) {
            reading = false;
        }
    }

    /**
     * Attempt a basic decode of a HID input report and update the decoded label.
     * Handles common report types: keyboard, mouse, gamepad. Falls back to silent no-op.
     */
    private void updateDecoded(JSONObject ev) {
        JSONArray bytesArr = ev.optJSONArray("bytes");
        if (bytesArr == null || bytesArr.length() == 0) {
            decodedLabel.setText(" ");
            return;
        }

        int len = bytesArr.length();
        int[] b = new int[len];
        for (int i = 0; i < len; i++) {
            b[i] = bytesArr.optInt(i, 0) & 0xFF;
        }

        // Heuristic: 8-byte keyboard report (report ID 0x01 or bare)
        if (len >= 8) {
            int modifiers = b[0];
            int keycode   = b[2];
            if (modifiers != 0 || keycode != 0) {
                decodedLabel.setText("Keyboard — modifiers: 0x"
                        + Integer.toHexString(modifiers).toUpperCase()
                        + "  keycode: 0x"
                        + Integer.toHexString(keycode).toUpperCase());
                return;
            }
        }

        // Heuristic: mouse-like report (3–7 bytes)
        if (len >= 3 && len <= 7) {
            int buttons = b[0];
            int dx      = (byte) b[1];  // signed
            int dy      = (byte) b[2];
            decodedLabel.setText("Mouse? — buttons: 0x"
                    + Integer.toHexString(buttons).toUpperCase()
                    + "  dX: " + dx + "  dY: " + dy);
            return;
        }

        decodedLabel.setText(" ");
    }
}
