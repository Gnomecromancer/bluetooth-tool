package com.bluetoothtool;

import javax.bluetooth.DiscoveryAgent;
import javax.bluetooth.LocalDevice;
import javax.swing.*;
import javax.swing.border.EmptyBorder;
import javax.swing.border.TitledBorder;
import java.awt.*;

/**
 * Local Info tab — displays local Bluetooth adapter details.
 */
public class LocalInfoPanel extends JPanel {

    private JTextField nameField;
    private JTextField addressField;
    private JTextField stackField;
    private JTextField discoverableField;
    private JLabel statusLabel;
    private JButton refreshButton;
    private JButton makeDiscoverableButton;

    public LocalInfoPanel() {
        setLayout(new BorderLayout(10, 10));
        setBorder(new EmptyBorder(16, 16, 16, 16));
        buildUI();
        loadInfo();
    }

    private void buildUI() {
        // ── Info form ─────────────────────────────────────────────────────────
        JPanel formPanel = new JPanel(new GridBagLayout());
        formPanel.setBorder(new TitledBorder("Local Bluetooth Adapter"));

        GridBagConstraints labelGbc = new GridBagConstraints();
        labelGbc.gridx = 0;
        labelGbc.anchor = GridBagConstraints.WEST;
        labelGbc.insets = new Insets(6, 8, 6, 12);

        GridBagConstraints fieldGbc = new GridBagConstraints();
        fieldGbc.gridx = 1;
        fieldGbc.fill = GridBagConstraints.HORIZONTAL;
        fieldGbc.weightx = 1.0;
        fieldGbc.insets = new Insets(6, 0, 6, 8);

        // Row 0 — Name
        labelGbc.gridy = 0;
        fieldGbc.gridy = 0;
        formPanel.add(boldLabel("Device Name:"), labelGbc);
        nameField = readOnlyField(24);
        formPanel.add(nameField, fieldGbc);

        // Row 1 — Address
        labelGbc.gridy = 1;
        fieldGbc.gridy = 1;
        formPanel.add(boldLabel("Address:"), labelGbc);
        addressField = readOnlyField(24);
        formPanel.add(addressField, fieldGbc);

        // Row 2 — Stack
        labelGbc.gridy = 2;
        fieldGbc.gridy = 2;
        formPanel.add(boldLabel("Bluetooth Stack:"), labelGbc);
        stackField = readOnlyField(24);
        formPanel.add(stackField, fieldGbc);

        // Row 3 — Discoverable
        labelGbc.gridy = 3;
        fieldGbc.gridy = 3;
        formPanel.add(boldLabel("Discoverable:"), labelGbc);
        discoverableField = readOnlyField(24);
        formPanel.add(discoverableField, fieldGbc);

        // Filler row to push content up
        GridBagConstraints fillerGbc = new GridBagConstraints();
        fillerGbc.gridy = 4;
        fillerGbc.gridx = 0;
        fillerGbc.gridwidth = 2;
        fillerGbc.weighty = 1.0;
        fillerGbc.fill = GridBagConstraints.VERTICAL;
        formPanel.add(Box.createVerticalGlue(), fillerGbc);

        add(formPanel, BorderLayout.CENTER);

        // ── Bottom bar ────────────────────────────────────────────────────────
        JPanel bottomBar = new JPanel(new BorderLayout(6, 0));
        bottomBar.setBorder(new EmptyBorder(6, 0, 0, 0));

        JPanel btnRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));

        refreshButton = new JButton("Refresh");
        refreshButton.setToolTipText("Reload adapter information");
        refreshButton.addActionListener(e -> loadInfo());

        makeDiscoverableButton = new JButton("Make Discoverable (GIAC)");
        makeDiscoverableButton.setToolTipText("Set this device to GIAC discoverable mode");
        makeDiscoverableButton.addActionListener(e -> makeDiscoverable());

        btnRow.add(refreshButton);
        btnRow.add(makeDiscoverableButton);

        statusLabel = new JLabel("Loading…");
        statusLabel.setFont(statusLabel.getFont().deriveFont(Font.ITALIC));
        statusLabel.setForeground(Color.GRAY);

        bottomBar.add(btnRow, BorderLayout.WEST);
        bottomBar.add(statusLabel, BorderLayout.EAST);
        add(bottomBar, BorderLayout.SOUTH);
    }

    // ── Load info ─────────────────────────────────────────────────────────────

    private void loadInfo() {
        refreshButton.setEnabled(false);
        makeDiscoverableButton.setEnabled(false);
        setStatus("Loading adapter information…", Color.GRAY);

        clearFields();

        new Thread(() -> {
            try {
                LocalDevice local = LocalDevice.getLocalDevice();

                String name = local.getFriendlyName();
                String rawAddr = local.getBluetoothAddress();
                String address = formatAddress(rawAddr);

                // Stack info via system property set by BlueCove
                String stack = System.getProperty("bluecove.stack", "Unknown");
                if (stack.equals("Unknown")) {
                    // Try to infer from class name
                    stack = local.getClass().getPackage() != null
                        ? local.getClass().getPackage().getName()
                        : "BlueCove";
                }

                int discMode = local.getDiscoverable();
                String discStr = discoverableString(discMode);

                final String finalName  = name;
                final String finalAddr  = address;
                final String finalStack = stack;
                final String finalDisc  = discStr;

                SwingUtilities.invokeLater(() -> {
                    nameField.setText(finalName);
                    addressField.setText(finalAddr);
                    stackField.setText(finalStack);
                    discoverableField.setText(finalDisc);
                    setStatus("Adapter information loaded.", new Color(0, 128, 0));
                    refreshButton.setEnabled(true);
                    makeDiscoverableButton.setEnabled(true);
                });

            } catch (Exception e) {
                SwingUtilities.invokeLater(() -> {
                    nameField.setText("Unavailable");
                    addressField.setText("Unavailable");
                    stackField.setText("Unavailable");
                    discoverableField.setText("Unavailable");
                    setStatus("Error: " + e.getMessage(), Color.RED);
                    refreshButton.setEnabled(true);
                    makeDiscoverableButton.setEnabled(false);
                });
            }
        }, "bt-local-info").start();
    }

    private void makeDiscoverable() {
        makeDiscoverableButton.setEnabled(false);
        setStatus("Setting discoverable mode…", Color.ORANGE);

        new Thread(() -> {
            try {
                LocalDevice local = LocalDevice.getLocalDevice();
                boolean success = local.setDiscoverable(DiscoveryAgent.GIAC);
                SwingUtilities.invokeLater(() -> {
                    if (success) {
                        discoverableField.setText("GIAC (General Discoverable)");
                        setStatus("Now discoverable (GIAC).", new Color(0, 128, 0));
                    } else {
                        setStatus("Could not set discoverable mode.", Color.RED);
                    }
                    makeDiscoverableButton.setEnabled(true);
                });
            } catch (Exception e) {
                SwingUtilities.invokeLater(() -> {
                    setStatus("Error: " + e.getMessage(), Color.RED);
                    makeDiscoverableButton.setEnabled(true);
                });
            }
        }, "bt-set-discoverable").start();
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private void clearFields() {
        nameField.setText("");
        addressField.setText("");
        stackField.setText("");
        discoverableField.setText("");
    }

    private void setStatus(String text, Color color) {
        SwingUtilities.invokeLater(() -> {
            statusLabel.setText(text);
            statusLabel.setForeground(color);
        });
    }

    private static String formatAddress(String raw) {
        if (raw == null || raw.length() != 12) return raw;
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 12; i += 2) {
            if (i > 0) sb.append(':');
            sb.append(raw, i, i + 2);
        }
        return sb.toString().toUpperCase();
    }

    private static String discoverableString(int mode) {
        return switch (mode) {
            case DiscoveryAgent.GIAC -> "GIAC (General Discoverable)";
            case DiscoveryAgent.LIAC -> "LIAC (Limited Discoverable)";
            case DiscoveryAgent.NOT_DISCOVERABLE -> "Not Discoverable";
            default -> "Unknown (" + mode + ")";
        };
    }

    private static JLabel boldLabel(String text) {
        JLabel lbl = new JLabel(text);
        lbl.setFont(lbl.getFont().deriveFont(Font.BOLD));
        return lbl;
    }

    private static JTextField readOnlyField(int cols) {
        JTextField f = new JTextField(cols);
        f.setEditable(false);
        f.setBackground(UIManager.getColor("Panel.background"));
        f.setBorder(BorderFactory.createCompoundBorder(
            BorderFactory.createLineBorder(new Color(180, 180, 180)),
            new EmptyBorder(2, 6, 2, 6)
        ));
        f.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
        return f;
    }
}
