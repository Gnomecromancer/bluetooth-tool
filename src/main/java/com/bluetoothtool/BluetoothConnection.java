package com.bluetoothtool;

import javax.microedition.io.Connector;
import javax.microedition.io.StreamConnection;
import java.io.*;
import java.util.function.Consumer;

/**
 * Wraps a BlueCove StreamConnection with buffered I/O and a background reader thread.
 */
public class BluetoothConnection {

    private StreamConnection streamConnection;
    private BufferedReader reader;
    private PrintWriter writer;
    private InputStream rawInputStream;
    private OutputStream rawOutputStream;
    private volatile boolean open = false;
    private Thread readerThread;

    /**
     * Open a connection to the given btspp:// URL.
     */
    public void open(String url) throws IOException {
        streamConnection = (StreamConnection) Connector.open(url);
        rawInputStream    = streamConnection.openInputStream();
        rawOutputStream   = streamConnection.openOutputStream();
        reader = new BufferedReader(new InputStreamReader(rawInputStream, "UTF-8"));
        writer = new PrintWriter(new OutputStreamWriter(rawOutputStream, "UTF-8"), true);
        open = true;
    }

    /**
     * Open using an already-established StreamConnection (e.g. from a server notifier).
     */
    public void open(StreamConnection existingConnection) throws IOException {
        streamConnection = existingConnection;
        rawInputStream    = streamConnection.openInputStream();
        rawOutputStream   = streamConnection.openOutputStream();
        reader = new BufferedReader(new InputStreamReader(rawInputStream, "UTF-8"));
        writer = new PrintWriter(new OutputStreamWriter(rawOutputStream, "UTF-8"), true);
        open = true;
    }

    /**
     * Close the connection. Safe to call multiple times.
     */
    public void close() {
        open = false;
        // Interrupt the reader thread so it unblocks
        if (readerThread != null) {
            readerThread.interrupt();
        }
        try { if (reader != null) reader.close(); } catch (IOException ignored) {}
        try { if (writer != null) writer.close(); } catch (Exception ignored) {}
        try { if (rawOutputStream != null) rawOutputStream.close(); } catch (IOException ignored) {}
        try { if (streamConnection != null) streamConnection.close(); } catch (IOException ignored) {}
        reader = null;
        writer = null;
        rawOutputStream = null;
        streamConnection = null;
    }

    /**
     * Send a text line (with newline appended).
     */
    public void sendLine(String text) {
        if (writer != null && open) {
            writer.println(text);
        }
    }

    /**
     * Send raw bytes.
     */
    public void sendBytes(byte[] bytes) throws IOException {
        if (rawOutputStream != null && open) {
            rawOutputStream.write(bytes);
            rawOutputStream.flush();
        }
    }

    /**
     * Start a background reader thread that calls callbacks as data arrives.
     *
     * @param onLine   called with each text line received
     * @param onBytes  called with each chunk of raw bytes (may be null if you only care about lines)
     * @param onClose  called when the connection closes or an error occurs
     */
    public void startReading(Consumer<String> onLine, Consumer<byte[]> onBytes, Runnable onClose) {
        readerThread = new Thread(() -> {
            try {
                if (onBytes != null) {
                    // Raw bytes mode: read directly from the input stream
                    byte[] buf = new byte[1024];
                    int n;
                    while (open && (n = rawInputStream.read(buf)) != -1) {
                        byte[] chunk = new byte[n];
                        System.arraycopy(buf, 0, chunk, 0, n);
                        onBytes.accept(chunk);
                        // Also fire onLine for text content
                        if (onLine != null) {
                            onLine.accept(new String(chunk, "UTF-8").replace("\r", "").replace("\n", ""));
                        }
                    }
                } else {
                    // Line mode
                    String line;
                    while (open && reader != null && (line = reader.readLine()) != null) {
                        onLine.accept(line);
                    }
                }
            } catch (IOException e) {
                if (open) {
                    // Unexpected disconnect
                }
            } finally {
                open = false;
                if (onClose != null) onClose.run();
            }
        }, "bt-reader");
        readerThread.setDaemon(true);
        readerThread.start();
    }

    public boolean isOpen() {
        return open;
    }

    public BufferedReader getReader() {
        return reader;
    }

    public PrintWriter getWriter() {
        return writer;
    }
}
