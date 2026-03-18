package com.bluetoothtool;

import javax.bluetooth.*;
import javax.swing.*;
import javax.swing.border.EmptyBorder;
import javax.swing.border.TitledBorder;
import javax.swing.table.DefaultTableModel;
import java.awt.*;
import java.awt.datatransfer.StringSelection;
import java.util.ArrayList;
import java.util.List;
import java.util.function.BiConsumer;

/**
 * Service Browser tab — queries the SDP records of a selected device.
 */
public class ServiceBrowserPanel extends JPanel implements DiscoveryListener {

    private final SharedState state;

    private JButton browseButton;
    private JButton openInTerminalBtn;
    private JLabel statusLabel;
    private DefaultTableModel tableModel;
    private JTable serviceTable;

    // Callback: (url, serviceName) -> open in terminal
    private BiConsumer<String, String> openInTerminalCallback;

    // Inquiry lock for service search
    private final Object searchLock = new Object();
    private final List<ServiceRecord> foundRecords = new ArrayList<>();
    private volatile int searchTransactionId = -1;

    // Common UUIDs to search for
    private static final UUID[] COMMON_UUIDS = {
        new UUID(0x0001),  // SDP
        new UUID(0x0003),  // RFCOMM
        new UUID(0x000C),  // HTTP
        new UUID(0x0100),  // L2CAP
        new UUID(0x0101),  // HIDP
        new UUID(0x1000),  // ServiceDiscoveryServerServiceClassID
        new UUID(0x1001),  // BrowseGroupDescriptorServiceClassID
        new UUID(0x1002),  // PublicBrowseGroup
        new UUID(0x1101),  // SerialPort (SPP)
        new UUID(0x1102),  // LANAccessUsingPPP
        new UUID(0x1103),  // DialupNetworking
        new UUID(0x1104),  // IrMCSync
        new UUID(0x1105),  // OBEXObjectPush
        new UUID(0x1106),  // OBEXFileTransfer
        new UUID(0x1108),  // Headset
        new UUID(0x1109),  // CordlessTelephony
        new UUID(0x110A),  // AudioSource
        new UUID(0x110B),  // AudioSink
        new UUID(0x110C),  // A/V_RemoteControlTarget
        new UUID(0x110D),  // AdvancedAudioDistribution
        new UUID(0x110E),  // A/V_RemoteControl
        new UUID(0x110F),  // VideoConferencing
        new UUID(0x1110),  // Intercom
        new UUID(0x1111),  // Fax
        new UUID(0x1112),  // HeadsetAudioGateway
        new UUID(0x1115),  // PANU
        new UUID(0x1116),  // NAP
        new UUID(0x1117),  // GN
        new UUID(0x1118),  // DirectPrinting
        new UUID(0x1119),  // ReferencePrinting
        new UUID(0x111A),  // ImagingResponder
        new UUID(0x111B),  // ImagingAutomaticArchive
        new UUID(0x111C),  // ImagingReferencedObjects
        new UUID(0x111E),  // HandsfreeAudioGateway
        new UUID(0x111F),  // HidDevice
        new UUID(0x1120),  // HardcopyCableReplacement
        new UUID(0x1121),  // HCR_Print
        new UUID(0x1122),  // HCR_Scan
        new UUID(0x1124),  // HID
        new UUID(0x1125),  // HardcopyStatus
        new UUID(0x1126),  // HardcopyCableReplacementPrint
        new UUID(0x112D),  // SIM_Access
        new UUID(0x112F),  // PhonebookAccessPCE
        new UUID(0x1130),  // PhonebookAccessPSE
        new UUID(0x1131),  // PhonebookAccess
        new UUID(0x1132),  // MessageAccessServer
        new UUID(0x1133),  // MessageNotificationServer
        new UUID(0x1134),  // MessageAccessProfile
        new UUID(0x1200),  // PnPInformation
        new UUID(0x1201),  // GenericNetworking
        new UUID(0x1202),  // GenericFileTransfer
        new UUID(0x1203),  // GenericAudio
        new UUID(0x1204),  // GenericTelephony
        new UUID(0x1303),  // VideoSource
        new UUID(0x1304),  // VideoSink
        new UUID(0x1305),  // VideoDistribution
    };

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
        foundRecords.clear();
        setStatus("Browsing services on " + target.name + " [" + target.address + "]…", new Color(0, 100, 200));

        new Thread(() -> {
            try {
                DiscoveryAgent agent = LocalDevice.getLocalDevice().getDiscoveryAgent();
                int[] attrSet = {
                    0x0000, // ServiceRecordHandle
                    0x0001, // ServiceClassIDList
                    0x0002, // ServiceRecordState
                    0x0003, // ServiceID
                    0x0004, // ProtocolDescriptorList
                    0x0005, // BrowseGroupList
                    0x0006, // LanguageBaseAttributeIDList
                    0x0007, // ServiceInfoTimeToLive
                    0x0008, // ServiceAvailability
                    0x0009, // BluetoothProfileDescriptorList
                    0x000A, // DocumentationURL
                    0x000B, // ClientExecutableURL
                    0x000C, // IconURL
                    0x0100, // ServiceName
                    0x0101, // ServiceDescription
                    0x0102, // ProviderName
                };

                synchronized (searchLock) {
                    searchTransactionId = agent.searchServices(
                        attrSet,
                        COMMON_UUIDS,
                        target.device,
                        this
                    );
                    searchLock.wait(30_000);
                }

            } catch (BluetoothStateException e) {
                SwingUtilities.invokeLater(() ->
                    setStatus("Bluetooth error: " + e.getMessage(), Color.RED));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                SwingUtilities.invokeLater(() ->
                    setStatus("Search interrupted.", Color.GRAY));
            } finally {
                SwingUtilities.invokeLater(() -> browseButton.setEnabled(true));
            }
        }, "bt-service-browse").start();
    }

    // ── DiscoveryListener ─────────────────────────────────────────────────────

    @Override
    public void servicesDiscovered(int transID, ServiceRecord[] records) {
        for (ServiceRecord sr : records) {
            foundRecords.add(sr);

            String serviceName = SdpHelper.extractServiceName(sr);
            String uuid = SdpHelper.extractUUID(sr);
            String url = sr.getConnectionURL(ServiceRecord.NOAUTHENTICATE_NOENCRYPT, false);
            if (url == null) url = "";

            final String finalName = serviceName;
            final String finalUUID = uuid;
            final String finalUrl = url;

            SwingUtilities.invokeLater(() -> {
                tableModel.addRow(new Object[]{finalName, finalUUID, finalUrl});
                setStatus("Found " + tableModel.getRowCount() + " service(s)…", new Color(0, 100, 200));
            });
        }
    }

    @Override
    public void serviceSearchCompleted(int transID, int respCode) {
        String msg;
        Color color;
        if (respCode == DiscoveryListener.SERVICE_SEARCH_COMPLETED) {
            int count = tableModel.getRowCount();
            msg = count == 0 ? "No services found." : count + " service(s) found.";
            color = count == 0 ? Color.GRAY : new Color(0, 128, 0);
        } else if (respCode == DiscoveryListener.SERVICE_SEARCH_TERMINATED) {
            msg = "Search terminated.";
            color = Color.GRAY;
        } else if (respCode == DiscoveryListener.SERVICE_SEARCH_ERROR) {
            msg = "Search error.";
            color = Color.RED;
        } else if (respCode == DiscoveryListener.SERVICE_SEARCH_NO_RECORDS) {
            msg = "No service records found.";
            color = Color.GRAY;
        } else if (respCode == DiscoveryListener.SERVICE_SEARCH_DEVICE_NOT_REACHABLE) {
            msg = "Device not reachable.";
            color = Color.RED;
        } else {
            msg = "Search complete (code=" + respCode + ").";
            color = Color.GRAY;
        }
        SwingUtilities.invokeLater(() -> setStatus(msg, color));
        synchronized (searchLock) {
            searchLock.notifyAll();
        }
    }

    @Override
    public void deviceDiscovered(RemoteDevice btDevice, DeviceClass cod) {}

    @Override
    public void inquiryCompleted(int discType) {}

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
