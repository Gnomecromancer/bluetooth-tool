package com.bluetoothtool;

import javax.bluetooth.*;
import javax.swing.*;
import javax.swing.border.EmptyBorder;
import javax.swing.border.TitledBorder;
import java.awt.*;
import java.util.ArrayList;
import java.util.List;

/**
 * Scanner tab — discovers nearby Bluetooth Classic devices via GIAC inquiry.
 */
public class DevicePanel extends JPanel implements DiscoveryListener {

    private final SharedState state;

    private JButton scanButton;
    private JButton clearButton;
    private JLabel statusLabel;
    private DefaultListModel<SharedState.DeviceEntry> listModel;
    private JList<SharedState.DeviceEntry> deviceList;

    private final Object inquiryLock = new Object();
    private final List<SharedState.DeviceEntry> tempFound = new ArrayList<>();

    public DevicePanel(SharedState state) {
        this.state = state;
        setLayout(new BorderLayout(8, 8));
        setBorder(new EmptyBorder(12, 12, 12, 12));
        buildUI();
    }

    private void buildUI() {
        // ── Device list ───────────────────────────────────────────────────────
        listModel = new DefaultListModel<>();
        deviceList = new JList<>(listModel);
        deviceList.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
        deviceList.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        deviceList.setCellRenderer(new DeviceCellRenderer());

        deviceList.addListSelectionListener(e -> {
            if (!e.getValueIsAdjusting()) {
                SharedState.DeviceEntry selected = deviceList.getSelectedValue();
                state.setSelectedDevice(selected);
            }
        });

        JScrollPane scrollPane = new JScrollPane(deviceList);
        scrollPane.setBorder(new TitledBorder("Discovered Devices"));
        add(scrollPane, BorderLayout.CENTER);

        // ── Bottom bar ────────────────────────────────────────────────────────
        JPanel bottomPanel = new JPanel(new BorderLayout(8, 0));
        bottomPanel.setBorder(new EmptyBorder(4, 0, 0, 0));

        statusLabel = new JLabel("Ready — press Scan to discover devices.");
        statusLabel.setFont(statusLabel.getFont().deriveFont(Font.ITALIC));
        statusLabel.setForeground(Color.GRAY);

        JPanel buttonRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));

        scanButton = new JButton("Scan");
        scanButton.setToolTipText("Start GIAC inquiry for Bluetooth devices");
        scanButton.addActionListener(e -> startScan());

        clearButton = new JButton("Clear");
        clearButton.setToolTipText("Clear the device list");
        clearButton.addActionListener(e -> {
            listModel.clear();
            state.clearDiscoveredDevices();
            statusLabel.setText("Device list cleared.");
            statusLabel.setForeground(Color.GRAY);
        });

        buttonRow.add(scanButton);
        buttonRow.add(clearButton);

        bottomPanel.add(buttonRow, BorderLayout.WEST);
        bottomPanel.add(statusLabel, BorderLayout.EAST);
        add(bottomPanel, BorderLayout.SOUTH);
    }

    // ── Scan logic ────────────────────────────────────────────────────────────

    private void startScan() {
        scanButton.setEnabled(false);
        clearButton.setEnabled(false);
        listModel.clear();
        tempFound.clear();
        state.clearDiscoveredDevices();
        setStatus("Scanning for devices…", new Color(0, 100, 200));

        new Thread(() -> {
            try {
                DiscoveryAgent agent = LocalDevice.getLocalDevice().getDiscoveryAgent();
                synchronized (inquiryLock) {
                    agent.startInquiry(DiscoveryAgent.GIAC, this);
                    inquiryLock.wait();
                }
            } catch (BluetoothStateException e) {
                SwingUtilities.invokeLater(() ->
                    setStatus("Bluetooth error: " + e.getMessage(), Color.RED));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                SwingUtilities.invokeLater(() ->
                    setStatus("Scan interrupted.", Color.GRAY));
            } finally {
                SwingUtilities.invokeLater(() -> {
                    scanButton.setEnabled(true);
                    clearButton.setEnabled(true);
                });
            }
        }, "bt-scan").start();
    }

    private void setStatus(String text, Color color) {
        SwingUtilities.invokeLater(() -> {
            statusLabel.setText(text);
            statusLabel.setForeground(color);
        });
    }

    // ── DiscoveryListener ─────────────────────────────────────────────────────

    @Override
    public void deviceDiscovered(RemoteDevice btDevice, DeviceClass cod) {
        String addr = formatAddress(btDevice.getBluetoothAddress());
        String name;
        try {
            name = btDevice.getFriendlyName(false);
            if (name == null || name.isBlank()) name = "(no name)";
        } catch (Exception e) {
            name = "(unknown)";
        }

        int majorClass = cod.getMajorDeviceClass();
        SharedState.DeviceEntry entry = new SharedState.DeviceEntry(btDevice, name, addr, majorClass);
        tempFound.add(entry);
        state.addDiscoveredDevice(entry);

        SwingUtilities.invokeLater(() -> {
            listModel.addElement(entry);
            setStatus(listModel.getSize() + " device(s) found…", new Color(0, 100, 200));
        });
    }

    @Override
    public void inquiryCompleted(int discType) {
        String message;
        Color color;
        if (discType == DiscoveryListener.INQUIRY_COMPLETED) {
            int count = tempFound.size();
            message = count == 0 ? "No devices found." : count + " device(s) found.";
            color = count == 0 ? Color.GRAY : new Color(0, 128, 0);
        } else if (discType == DiscoveryListener.INQUIRY_TERMINATED) {
            message = "Scan terminated.";
            color = Color.GRAY;
        } else {
            message = "Scan error (code=" + discType + ").";
            color = Color.RED;
        }
        SwingUtilities.invokeLater(() -> setStatus(message, color));
        synchronized (inquiryLock) {
            inquiryLock.notifyAll();
        }
    }

    @Override
    public void servicesDiscovered(int transID, ServiceRecord[] servRecord) {
        // Not used in scan tab
    }

    @Override
    public void serviceSearchCompleted(int transID, int respCode) {
        // Not used in scan tab
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private static String formatAddress(String raw) {
        if (raw == null || raw.length() != 12) return raw;
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 12; i += 2) {
            if (i > 0) sb.append(':');
            sb.append(raw, i, i + 2);
        }
        return sb.toString().toUpperCase();
    }

    // ── Custom cell renderer ──────────────────────────────────────────────────

    private static class DeviceCellRenderer extends DefaultListCellRenderer {

        @Override
        public Component getListCellRendererComponent(JList<?> list, Object value,
                int index, boolean isSelected, boolean cellHasFocus) {

            super.getListCellRendererComponent(list, value, index, isSelected, cellHasFocus);

            if (value instanceof SharedState.DeviceEntry entry) {
                String classLabel = entry.getMajorClassLabel();
                String html = "<html><b>" + escapeHtml(entry.name) + "</b>"
                        + " &nbsp;<font color='gray'>[" + entry.address + "]</font>"
                        + " &nbsp;<font color='#557799'><i>" + classLabel + "</i></font></html>";
                setText(html);
                setBorder(new EmptyBorder(3, 6, 3, 6));

                // Icon by class
                setIcon(getClassIcon(entry.majorClass));
            }
            return this;
        }

        private Icon getClassIcon(int majorClass) {
            // Use UIManager stock icons as placeholders
            return switch (majorClass) {
                case 0x0100 -> UIManager.getIcon("FileView.computerIcon");
                case 0x0200 -> UIManager.getIcon("FileView.fileIcon");
                case 0x0400 -> UIManager.getIcon("OptionPane.informationIcon");
                default     -> UIManager.getIcon("FileView.directoryIcon");
            };
        }

        private static String escapeHtml(String s) {
            return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
        }
    }
}
