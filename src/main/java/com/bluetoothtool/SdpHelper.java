package com.bluetoothtool;

import javax.bluetooth.*;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Shared SDP (Service Discovery Protocol) utilities.
 *
 *  browseServices(device)          — find all services on a device
 *  lookupConnectionUrl(device, uuid) — SDP lookup for a specific UUID
 *  lookupConnectionUrl(address, uuid) — same but from cached address
 */
public class SdpHelper {

    private static final long TIMEOUT_SEC = 20;

    // ── ServiceInfo ───────────────────────────────────────────────────────────

    /** Summary of a single SDP service record. */
    public static class ServiceInfo {
        public final String name;
        public final String uuid;
        public final String connectionUrl; // may be empty if not RFCOMM-connectable

        public ServiceInfo(String name, String uuid, String connectionUrl) {
            this.name = name;
            this.uuid = uuid;
            this.connectionUrl = connectionUrl;
        }

        public boolean isConnectable() { return connectionUrl != null && !connectionUrl.isEmpty(); }

        @Override
        public String toString() {
            String label = (name != null && !name.isEmpty()) ? name : uuid;
            return isConnectable() ? label : label + "  (not connectable)";
        }
    }

    // ── Browse all services ───────────────────────────────────────────────────

    /**
     * Enumerate all Bluetooth services on {@code device}.
     * Runs synchronously (call from a background thread).
     */
    public static List<ServiceInfo> browseServices(RemoteDevice device)
            throws BluetoothException {

        CountDownLatch latch   = new CountDownLatch(1);
        List<ServiceInfo> out  = new CopyOnWriteArrayList<>();
        AtomicReference<String> err = new AtomicReference<>();

        int[] attrIDs = {
            0x0001, // ServiceClassIDList
            0x0004, // ProtocolDescriptorList (contains RFCOMM channel)
            0x0100, // ServiceName
        };

        DiscoveryAgent agent = getAgent();

        DiscoveryListener listener = new DiscoveryListener() {
            @Override
            public void servicesDiscovered(int transID, ServiceRecord[] records) {
                for (ServiceRecord sr : records) {
                    String name = extractServiceName(sr);
                    String uuid = extractUUID(sr);
                    String url  = sr.getConnectionURL(
                            ServiceRecord.NOAUTHENTICATE_NOENCRYPT, false);
                    out.add(new ServiceInfo(name, uuid, url == null ? "" : url));
                }
            }

            @Override
            public void serviceSearchCompleted(int transID, int respCode) {
                if (respCode == DiscoveryListener.SERVICE_SEARCH_ERROR)
                    err.set("SDP error from device");
                else if (respCode == DiscoveryListener.SERVICE_SEARCH_DEVICE_NOT_REACHABLE)
                    err.set("Device not reachable");
                latch.countDown();
            }

            @Override public void deviceDiscovered(RemoteDevice rd, DeviceClass dc) {}
            @Override public void inquiryCompleted(int d) {}
        };

        try {
            agent.searchServices(attrIDs, BROWSE_UUIDS, device, listener);
        } catch (BluetoothStateException e) {
            throw new BluetoothException("Could not start SDP search: " + e.getMessage());
        }

        try {
            if (!latch.await(TIMEOUT_SEC, TimeUnit.SECONDS))
                throw new BluetoothException("SDP browse timed out after " + TIMEOUT_SEC + "s");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new BluetoothException("SDP browse interrupted");
        }

        if (err.get() != null) throw new BluetoothException(err.get());
        return new ArrayList<>(out);
    }

    // ── Lookup a specific service URL ─────────────────────────────────────────

    /**
     * SDP lookup for a specific UUID. Returns a connection URL with the correct
     * RFCOMM channel number.
     */
    public static String lookupConnectionUrl(RemoteDevice device, UUID serviceUUID)
            throws BluetoothException {

        CountDownLatch latch = new CountDownLatch(1);
        AtomicReference<String> foundUrl = new AtomicReference<>();
        AtomicReference<String> errMsg   = new AtomicReference<>();

        int[] attrIDs = { 0x0001, 0x0004, 0x0100 };

        DiscoveryListener listener = new DiscoveryListener() {
            @Override
            public void servicesDiscovered(int transID, ServiceRecord[] records) {
                for (ServiceRecord sr : records) {
                    String url = sr.getConnectionURL(
                            ServiceRecord.NOAUTHENTICATE_NOENCRYPT, false);
                    if (url != null && !url.isEmpty()) {
                        foundUrl.compareAndSet(null, url);
                        break;
                    }
                }
            }

            @Override
            public void serviceSearchCompleted(int transID, int respCode) {
                if (respCode == DiscoveryListener.SERVICE_SEARCH_ERROR)
                    errMsg.set("SDP search error");
                else if (respCode == DiscoveryListener.SERVICE_SEARCH_DEVICE_NOT_REACHABLE)
                    errMsg.set("Device not reachable");
                else if (foundUrl.get() == null
                        && respCode == DiscoveryListener.SERVICE_SEARCH_COMPLETED)
                    errMsg.set("Service not found on device");
                latch.countDown();
            }

            @Override public void deviceDiscovered(RemoteDevice rd, DeviceClass dc) {}
            @Override public void inquiryCompleted(int d) {}
        };

        try {
            getAgent().searchServices(attrIDs, new UUID[]{serviceUUID}, device, listener);
        } catch (BluetoothStateException e) {
            throw new BluetoothException("Could not start SDP search: " + e.getMessage());
        }

        try {
            if (!latch.await(TIMEOUT_SEC, TimeUnit.SECONDS))
                throw new BluetoothException("SDP search timed out");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new BluetoothException("SDP search interrupted");
        }

        if (errMsg.get() != null) throw new BluetoothException(errMsg.get());
        if (foundUrl.get() == null) throw new BluetoothException("Service not found on device");
        return foundUrl.get();
    }

    /**
     * Overload: look up by raw address string from the discovered-device cache.
     */
    public static String lookupConnectionUrl(String rawAddress, UUID serviceUUID)
            throws BluetoothException {
        String normalised = rawAddress.replace(":", "").replace("-", "").toUpperCase();
        for (int opt : new int[]{DiscoveryAgent.CACHED, DiscoveryAgent.PREKNOWN}) {
            RemoteDevice[] devices = getAgent().retrieveDevices(opt);
            if (devices == null) continue;
            for (RemoteDevice rd : devices) {
                if (rd.getBluetoothAddress().equalsIgnoreCase(normalised))
                    return lookupConnectionUrl(rd, serviceUUID);
            }
        }
        throw new BluetoothException(
            "Device " + rawAddress + " not in cache — scan for it in the Devices tab first");
    }

    // ── Internal helpers ──────────────────────────────────────────────────────

    private static DiscoveryAgent getAgent() throws BluetoothException {
        try {
            return LocalDevice.getLocalDevice().getDiscoveryAgent();
        } catch (BluetoothStateException e) {
            throw new BluetoothException("Bluetooth not available: " + e.getMessage());
        }
    }

    static String extractServiceName(ServiceRecord sr) {
        DataElement nameAttr = sr.getAttributeValue(0x0100);
        if (nameAttr != null && nameAttr.getDataType() == DataElement.STRING)
            return (String) nameAttr.getValue();
        DataElement classList = sr.getAttributeValue(0x0001);
        if (classList != null && classList.getDataType() == DataElement.DATSEQ) {
            java.util.Enumeration<?> en = (java.util.Enumeration<?>) classList.getValue();
            while (en.hasMoreElements()) {
                Object elem = en.nextElement();
                if (elem instanceof DataElement de && de.getDataType() == DataElement.UUID) {
                    String known = resolveKnownUUID((UUID) de.getValue());
                    if (known != null) return known;
                    return "Service";
                }
            }
        }
        return "(unnamed)";
    }

    static String extractUUID(ServiceRecord sr) {
        DataElement classList = sr.getAttributeValue(0x0001);
        if (classList == null) return "";
        if (classList.getDataType() == DataElement.DATSEQ) {
            StringBuilder sb = new StringBuilder();
            java.util.Enumeration<?> en = (java.util.Enumeration<?>) classList.getValue();
            while (en.hasMoreElements()) {
                Object elem = en.nextElement();
                if (elem instanceof DataElement de && de.getDataType() == DataElement.UUID) {
                    if (sb.length() > 0) sb.append(", ");
                    sb.append(de.getValue().toString());
                }
            }
            return sb.toString();
        }
        return "";
    }

    public static String resolveKnownUUID(UUID uuid) {
        String s = uuid.toString().toUpperCase();
        return switch (s) {
            case "1101" -> "Serial Port (SPP)";
            case "1102" -> "LAN Access via PPP";
            case "1103" -> "Dialup Networking";
            case "1104" -> "IrMC Sync";
            case "1105" -> "OBEX Object Push";
            case "1106" -> "OBEX File Transfer";
            case "1108" -> "Headset";
            case "110A" -> "Audio Source (A2DP)";
            case "110B" -> "Audio Sink (A2DP)";
            case "110C" -> "A/V Remote Control Target";
            case "110E" -> "A/V Remote Control";
            case "1112" -> "Headset Audio Gateway";
            case "111E" -> "Handsfree";
            case "111F" -> "Handsfree AG";
            case "1124" -> "HID";
            case "1115" -> "PANU";
            case "1116" -> "NAP";
            case "1117" -> "GN";
            case "112D" -> "SIM Access";
            case "1200" -> "PnP Information";
            case "1203" -> "Generic Audio";
            default -> null;
        };
    }

    // ── Browse UUID list ──────────────────────────────────────────────────────
    // A broad set of well-known Bluetooth profile UUIDs. searchServices() with
    // this list will return any service that matches at least one UUID.

    static final UUID[] BROWSE_UUIDS = {
        new UUID(0x0001),  // SDP
        new UUID(0x0003),  // RFCOMM
        new UUID(0x0100),  // L2CAP
        new UUID(0x1000),  // ServiceDiscoveryServer
        new UUID(0x1001),  // BrowseGroupDescriptor
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
        new UUID(0x1110),  // Intercom
        new UUID(0x1111),  // Fax
        new UUID(0x1112),  // HeadsetAudioGateway
        new UUID(0x1115),  // PANU
        new UUID(0x1116),  // NAP
        new UUID(0x1117),  // GN
        new UUID(0x111E),  // Handsfree
        new UUID(0x111F),  // HandsfreeAudioGateway
        new UUID(0x1124),  // HID
        new UUID(0x112D),  // SIM_Access
        new UUID(0x1200),  // PnPInformation
        new UUID(0x1203),  // GenericAudio
    };

    // ── Exception ─────────────────────────────────────────────────────────────

    public static class BluetoothException extends Exception {
        public BluetoothException(String message) { super(message); }
    }
}
