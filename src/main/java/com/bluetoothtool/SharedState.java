package com.bluetoothtool;

import javax.bluetooth.RemoteDevice;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Shared application state accessible by all panels.
 * Panels register listeners to react to device/connection changes.
 */
public class SharedState {

    // ── Data ─────────────────────────────────────────────────────────────────

    private final List<DeviceEntry> discoveredDevices = new ArrayList<>();
    private DeviceEntry selectedDevice;
    private BluetoothConnection activeConnection;

    // ── Listeners ─────────────────────────────────────────────────────────────

    public interface StateListener {
        default void onSelectedDeviceChanged(DeviceEntry device) {}
        default void onConnectionChanged(BluetoothConnection connection) {}
        default void onDevicesChanged(List<DeviceEntry> devices) {}
    }

    private final CopyOnWriteArrayList<StateListener> listeners = new CopyOnWriteArrayList<>();

    public void addListener(StateListener l) {
        listeners.add(l);
    }

    public void removeListener(StateListener l) {
        listeners.remove(l);
    }

    // ── Accessors ─────────────────────────────────────────────────────────────

    public List<DeviceEntry> getDiscoveredDevices() {
        return discoveredDevices;
    }

    public synchronized DeviceEntry getSelectedDevice() {
        return selectedDevice;
    }

    public synchronized void setSelectedDevice(DeviceEntry device) {
        this.selectedDevice = device;
        for (StateListener l : listeners) {
            l.onSelectedDeviceChanged(device);
        }
    }

    public synchronized BluetoothConnection getActiveConnection() {
        return activeConnection;
    }

    public synchronized void setActiveConnection(BluetoothConnection connection) {
        this.activeConnection = connection;
        for (StateListener l : listeners) {
            l.onConnectionChanged(connection);
        }
    }

    /** Replace the device list and notify listeners. */
    public synchronized void setDiscoveredDevices(List<DeviceEntry> devices) {
        discoveredDevices.clear();
        discoveredDevices.addAll(devices);
        for (StateListener l : listeners) {
            l.onDevicesChanged(new ArrayList<>(discoveredDevices));
        }
    }

    /** Append a single device and notify listeners. */
    public synchronized void addDiscoveredDevice(DeviceEntry entry) {
        discoveredDevices.add(entry);
        for (StateListener l : listeners) {
            l.onDevicesChanged(new ArrayList<>(discoveredDevices));
        }
    }

    /** Clear the device list and notify listeners. */
    public synchronized void clearDiscoveredDevices() {
        discoveredDevices.clear();
        for (StateListener l : listeners) {
            l.onDevicesChanged(new ArrayList<>(discoveredDevices));
        }
    }

    // ── DeviceEntry ───────────────────────────────────────────────────────────

    /**
     * Represents a discovered remote Bluetooth device.
     */
    public static class DeviceEntry {
        public final RemoteDevice device;
        public final String name;
        public final String address;
        public final int majorClass;

        public DeviceEntry(RemoteDevice device, String name, String address, int majorClass) {
            this.device = device;
            this.name = name;
            this.address = address;
            this.majorClass = majorClass;
        }

        /** Human-readable label for the major device class. */
        public String getMajorClassLabel() {
            return switch (majorClass) {
                case 0x0100 -> "Computer";
                case 0x0200 -> "Phone";
                case 0x0300 -> "LAN/Network";
                case 0x0400 -> "Audio/Video";
                case 0x0500 -> "Peripheral";
                case 0x0600 -> "Imaging";
                case 0x0700 -> "Wearable";
                case 0x0800 -> "Toy";
                case 0x0900 -> "Health";
                case 0x1F00 -> "Uncategorized";
                default     -> "Unknown";
            };
        }

        @Override
        public String toString() {
            return name + "  [" + address + "]";
        }
    }
}
