package com.bluetoothtool;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import javax.swing.border.TitledBorder;
import javax.swing.table.DefaultTableModel;
import java.awt.*;
import java.awt.datatransfer.StringSelection;
import java.util.List;
import java.util.function.BiConsumer;

/**
 * Service Browser tab — queries the SDP records of a selected device.
 */
public class ServiceBrowserPanel extends JPanel {

    private final SharedState state;

    private JButton browseButton;
    private JButton openInTerminalBtn;
    private JLabel statusLabel;
    private DefaultTableModel tableModel;
    private JTable serviceTable;

    // Callback: (url, serviceName) -> open in terminal
    private BiConsumer<String, String> openInTerminalCallback;

    public ServiceBrowserPanel(SharedState state) {
        this.state = state;
        setLayout(new BorderLayout(8, 8));
        setBorder(new EmptyBorder(10, 10, 10, 10));
        buildUI();
    }

    /** Wire up the "Open in Terminal" button. Call this from MainWindow after all panels are created. */
    public void setOpenInTerminalCallback(BiConsumer<String, String> callback) {
        this.openInTerminalCallback = callback;
    }

    private void buildUI() {
        // ── Table ─────────────────────────────────────────────────────────────
        tableModel = new DefaultTableModel(new String[]{"Service Name", "UUID", "Connection URL"}, 0) {
            @Override public boolean isCellEditable(int row, int col) { return false; }
        };

        serviceTable = new JTable(tableModel);
        serviceTable.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
        serviceTable.setRowHeight(22);
        serviceTable.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        serviceTable.getColumnModel().getColumn(0).setPreferredWidth(180);
        serviceTable.getColumnModel().getColumn(1).setPreferredWidth(300);
        serviceTable.getColumnModel().getColumn(2).setPreferredWidth(360);
        serviceTable.setAutoResizeMode(JTable.AUTO_RESIZE_LAST_COLUMN);

        // Double-click copies URL to clipboard
        serviceTable.addMouseListener(new java.awt.event.MouseAdapter() {
            @Override
            public void mouseClicked(java.awt.event.MouseEvent e) {
                if (e.getClickCount() == 2) {
                    int row = serviceTable.getSelectedRow();
                    if (row >= 0) {
                        String url = (String) tableModel.getValueAt(row, 2);
                        if (url != null && !url.isEmpty()) {
                            Toolkit.getDefaultToolkit()
                                .getSystemClipboard()
                                .setContents(new StringSelection(url), null);
                            setStatus("Copied URL to clipboard: " + url, new Color(0, 100, 0));
                        }
                    }
                }
            }
        });

        // Enable "Open in Terminal" when a row with a non-empty URL is selected
        serviceTable.getSelectionModel().addListSelectionListener(e -> {
            if (!e.getValueIsAdjusting()) updateOpenButton();
        });

        JScrollPane scrollPane = new JScrollPane(serviceTable);
        scrollPane.setBorder(new TitledBorder("Services  (double-click a row to copy URL)"));
        add(scrollPane, BorderLayout.CENTER);

        // ── Bottom bar ────────────────────────────────────────────────────────
        JPanel bottomPanel = new JPanel(new BorderLayout(6, 0));
        bottomPanel.setBorder(new EmptyBorder(4, 0, 0, 0));

        statusLabel = new JLabel("Select a device in the Devices tab, then click Browse.");
        statusLabel.setFont(statusLabel.getFont().deriveFont(Font.ITALIC));
        statusLabel.setForeground(Color.GRAY);

        JPanel btnRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
        browseButton = new JButton("Browse Selected Device");
        browseButton.addActionListener(e -> startBrowse());

        openInTerminalBtn = new JButton("Open in Terminal");
        openInTerminalBtn.setEnabled(false);
        openInTerminalBtn.setToolTipText("Connect to selected service in the Terminal tab");
        openInTerminalBtn.addActionListener(e -> openSelectedInTerminal());

        JButton clearBtn = new JButton("Clear");
        clearBtn.addActionListener(e -> {
            tableModel.setRowCount(0);
            setStatus("Table cleared.", Color.GRAY);
            updateOpenButton();
        });

        btnRow.add(browseButton);
        btnRow.add(openInTerminalBtn);
        btnRow.add(clearBtn);
        bottomPanel.add(btnRow, BorderLayout.WEST);
        bottomPanel.add(statusLabel, BorderLayout.EAST);
        add(bottomPanel, BorderLayout.SOUTH);
    }

    // ── Browse logic ──────────────────────────────────────────────────────────

    private void startBrowse() {
        SharedState.DeviceEntry target = state.getSelectedDevice();
        if (target == null) {
            JOptionPane.showMessageDialog(this,
                "No device selected.\nGo to the Devices tab and select a device first.",
                "No Device Selected", JOptionPane.INFORMATION_MESSAGE);
            return;
        }

        browseButton.setEnabled(false);
        tableModel.setRowCount(0);
        setStatus("Browsing services on " + target.name + "…", new Color(0, 100, 200));

        new Thread(() -> {
            try {
                List<SdpHelper.ServiceInfo> services = SdpHelper.browseServices(target.device);
                SwingUtilities.invokeLater(() -> {
                    for (SdpHelper.ServiceInfo svc : services) {
                        tableModel.addRow(new Object[]{svc.name, svc.uuid, svc.connectionUrl});
                    }
                    int count = services.size();
                    setStatus(count == 0 ? "No services found." : count + " service(s) found.",
                              count == 0 ? Color.GRAY : new Color(0, 128, 0));
                    updateOpenButton();
                });
            } catch (SdpHelper.BluetoothException e) {
                SwingUtilities.invokeLater(() ->
                    setStatus("Browse failed: " + e.getMessage(), Color.RED));
            } finally {
                SwingUtilities.invokeLater(() -> browseButton.setEnabled(true));
            }
        }, "bt-service-browse").start();
    }

    // ── Open in Terminal ──────────────────────────────────────────────────────

    private void updateOpenButton() {
        if (openInTerminalBtn == null) return;
        int row = serviceTable.getSelectedRow();
        if (row < 0) { openInTerminalBtn.setEnabled(false); return; }
        String url = (String) tableModel.getValueAt(row, 2);
        openInTerminalBtn.setEnabled(openInTerminalCallback != null
                && url != null && !url.isEmpty());
    }

    private void openSelectedInTerminal() {
        int row = serviceTable.getSelectedRow();
        if (row < 0 || openInTerminalCallback == null) return;
        String url  = (String) tableModel.getValueAt(row, 2);
        String name = (String) tableModel.getValueAt(row, 0);
        if (url == null || url.isEmpty()) return;
        openInTerminalCallback.accept(url, name);
    }

    // ── Helpers (delegate to SdpHelper) ──────────────────────────────────────

    private void setStatus(String text, Color color) {
        SwingUtilities.invokeLater(() -> {
            statusLabel.setText(text);
            statusLabel.setForeground(color);
        });
    }
}
