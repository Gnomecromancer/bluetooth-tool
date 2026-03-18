package com.bluetoothtool;

import javax.bluetooth.*;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Utility: perform an SDP service search on a remote device and return a
 * ready-to-use connection URL via {@link ServiceRecord#getConnectionURL}.
 */
public class SdpHelper {

    /** Timeout waiting for the SDP search to complete. */
    private static final long TIMEOUT_SEC = 15;

    /**
     * Convenience overload: look up by raw address string (no colons/dashes).
     * Searches the discovered-device cache for a matching {@link RemoteDevice}.
     * Falls back to PREKNOWN list if the address is not in the scan cache.
     *
     * @throws BluetoothException if the address cannot be resolved to a cached device
     */
    public static String lookupConnectionUrl(String rawAddress, UUID serviceUUID)
            throws BluetoothException {
        String normalised = rawAddress.replace(":", "").replace("-", "").toUpperCase();
        DiscoveryAgent agent;
        try {
            agent = LocalDevice.getLocalDevice().getDiscoveryAgent();
        } catch (BluetoothStateException e) {
            throw new BluetoothException("Bluetooth not available: " + e.getMessage());
        }
        for (int opt : new int[]{DiscoveryAgent.CACHED, DiscoveryAgent.PREKNOWN}) {
            RemoteDevice[] devices = agent.retrieveDevices(opt);
            if (devices == null) continue;
            for (RemoteDevice rd : devices) {
                if (rd.getBluetoothAddress().equalsIgnoreCase(normalised)) {
                    return lookupConnectionUrl(rd, serviceUUID);
                }
            }
        }
        throw new BluetoothException(
            "Device " + rawAddress + " not in cache — scan for it in the Devices tab first");
    }

    /**
     * Look up a service on {@code device} matching {@code serviceUUID} and
     * return a connection URL suitable for passing to
     * {@code javax.microedition.io.Connector.open()}.
     *
     * @param device      the remote Bluetooth device
     * @param serviceUUID the service UUID (e.g. {@code new UUID(0x1101)} for SPP,
     *                    or {@code new UUID("27012F0C...", false)} for custom)
     * @return a btspp:// URL with the correct numeric channel embedded
     * @throws BluetoothException if no matching service is found or SDP fails
     */
    public static String lookupConnectionUrl(RemoteDevice device, UUID serviceUUID)
            throws BluetoothException {

        CountDownLatch latch       = new CountDownLatch(1);
        AtomicReference<String>  foundUrl  = new AtomicReference<>();
        AtomicReference<String>  errorMsg  = new AtomicReference<>();

        int[] attrIDs = {
            0x0001, // ServiceClassIDList
            0x0004, // ProtocolDescriptorList (contains RFCOMM channel)
            0x0100, // ServiceName
        };

        DiscoveryAgent agent;
        try {
            agent = LocalDevice.getLocalDevice().getDiscoveryAgent();
        } catch (BluetoothStateException e) {
            throw new BluetoothException("Bluetooth not available: " + e.getMessage());
        }

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
                if (respCode == DiscoveryListener.SERVICE_SEARCH_ERROR) {
                    errorMsg.set("SDP search error (code=" + respCode + ")");
                } else if (respCode == DiscoveryListener.SERVICE_SEARCH_DEVICE_NOT_REACHABLE) {
                    errorMsg.set("Device not reachable");
                } else if (foundUrl.get() == null
                        && respCode == DiscoveryListener.SERVICE_SEARCH_COMPLETED) {
                    errorMsg.set("Service not found on device");
                }
                latch.countDown();
            }

            // Not used but required by interface
            @Override public void deviceDiscovered(RemoteDevice rd, DeviceClass dc) {}
            @Override public void inquiryCompleted(int discType) {}
        };

        try {
            agent.searchServices(attrIDs, new UUID[]{serviceUUID}, device, listener);
        } catch (BluetoothStateException e) {
            throw new BluetoothException("Could not start SDP search: " + e.getMessage());
        }

        try {
            if (!latch.await(TIMEOUT_SEC, TimeUnit.SECONDS)) {
                throw new BluetoothException("SDP search timed out after " + TIMEOUT_SEC + "s");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new BluetoothException("SDP search interrupted");
        }

        if (errorMsg.get() != null) throw new BluetoothException(errorMsg.get());
        if (foundUrl.get() == null) throw new BluetoothException("Service not found on device");
        return foundUrl.get();
    }

    /** Simple checked exception for SDP errors. */
    public static class BluetoothException extends Exception {
        public BluetoothException(String message) { super(message); }
    }
}
