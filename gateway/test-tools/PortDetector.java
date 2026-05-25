import java.io.*;
import java.util.*;

/**
 * Auto-detects which serial port has a Draeger ventilator (MEDIBUS)
 * and which has a Philips monitor (MIB/RS232).
 *
 * Strategy:
 *   1. Open each candidate serial port
 *   2. Configure for Draeger MEDIBUS (19200 baud, 8N1) using stty
 *   3. Send a MEDIBUS DeviceId request and wait for SOH response
 *   4. If a valid MEDIBUS response arrives → Draeger
 *   5. Remaining port → Philips (by elimination)
 *
 * Usage:
 *   java -cp test-tools PortDetector [port1] [port2] ...
 *   java -cp test-tools PortDetector /dev/ttyS0 /dev/ttyS1
 *   java -cp test-tools PortDetector              # scans /dev/ttyS0,S1,S2,S3
 *
 * Output (machine-readable, one per line):
 *   DRAEGER=/dev/ttyS1
 *   PHILIPS=/dev/ttyS0
 *   UNKNOWN=/dev/ttyS2
 *
 * Exit codes:
 *   0 = at least one device detected
 *   1 = no devices detected
 *   2 = error
 */
public class PortDetector {

    static final byte ESC = 0x1B;
    static final byte SOH = 0x01;
    static final byte CR  = 0x0D;
    static final byte CMD_REQ_DEVICE_ID = 0x52;

    static final int DRAEGER_BAUD = 19200;
    static final int PROBE_TIMEOUT_MS = 3000;

    public static void main(String[] args) {
        List<String> ports;
        if (args.length > 0) {
            ports = Arrays.asList(args);
        } else {
            ports = findCandidatePorts();
        }

        if (ports.isEmpty()) {
            System.err.println("No serial ports found to scan.");
            System.exit(2);
        }

        System.err.println("Scanning " + ports.size() + " port(s): " + ports);
        System.err.println("");

        String draegerPort = null;
        List<String> unknownPorts = new ArrayList<>();

        for (String port : ports) {
            System.err.print("  " + port + " ... ");
            String result = probePort(port);

            if ("draeger".equals(result)) {
                System.err.println("DRAEGER (MEDIBUS response detected)");
                draegerPort = port;
            } else if ("error".equals(result)) {
                System.err.println("SKIP (cannot open)");
            } else {
                System.err.println("no MEDIBUS response");
                unknownPorts.add(port);
            }
        }

        System.err.println("");

        // Output machine-readable results
        boolean found = false;
        if (draegerPort != null) {
            System.out.println("DRAEGER=" + draegerPort);
            found = true;
        }

        // If we found Draeger and there's exactly one other port, assume Philips
        if (draegerPort != null && unknownPorts.size() == 1) {
            System.out.println("PHILIPS=" + unknownPorts.get(0));
            System.err.println("Detected: Draeger on " + draegerPort +
                    ", Philips assumed on " + unknownPorts.get(0));
            found = true;
        } else if (draegerPort != null && unknownPorts.size() > 1) {
            System.err.println("Detected: Draeger on " + draegerPort +
                    ", but multiple remaining ports — cannot determine Philips.");
            for (String p : unknownPorts) {
                System.out.println("UNKNOWN=" + p);
            }
        } else if (draegerPort == null) {
            System.err.println("No Draeger device detected on any port.");
            // Try to detect Philips by checking if any port has data at 115200
            for (String p : unknownPorts) {
                String philipsResult = probePhilips(p);
                if ("philips".equals(philipsResult)) {
                    System.out.println("PHILIPS=" + p);
                    System.err.println("Detected: Philips on " + p);
                    found = true;
                } else {
                    System.out.println("UNKNOWN=" + p);
                }
            }
        }

        System.exit(found ? 0 : 1);
    }

    /**
     * Probe a port for Draeger MEDIBUS.
     * Configures port to 19200 8N1, sends DeviceId request, waits for SOH.
     */
    static String probePort(String port) {
        try {
            // Check port exists and is accessible
            File f = new File(port);
            if (!f.exists()) return "error";

            // Configure serial port using stty
            int rc = configurePort(port, DRAEGER_BAUD);
            if (rc != 0) return "error";

            // Open port and send MEDIBUS probe
            try (FileInputStream fin = new FileInputStream(port);
                 FileOutputStream fout = new FileOutputStream(port)) {

                // Build MEDIBUS DeviceId request: ESC <cmd> <checksum_hi> <checksum_lo> CR
                int checksum = CMD_REQ_DEVICE_ID & 0xFF;
                byte[] probe = new byte[] {
                    ESC,
                    CMD_REQ_DEVICE_ID,
                    hexHi(checksum),
                    hexLo(checksum),
                    CR
                };

                // Flush any stale data
                while (fin.available() > 0) fin.read();

                // Send probe
                fout.write(probe);
                fout.flush();

                // Wait for response (look for SOH byte)
                long deadline = System.currentTimeMillis() + PROBE_TIMEOUT_MS;
                while (System.currentTimeMillis() < deadline) {
                    if (fin.available() > 0) {
                        int b = fin.read();
                        if (b == SOH) {
                            // Got a MEDIBUS response start — this is a Draeger device
                            // Drain remaining response
                            Thread.sleep(200);
                            while (fin.available() > 0) fin.read();
                            return "draeger";
                        }
                    } else {
                        Thread.sleep(50);
                    }
                }
                return "unknown";
            }
        } catch (Exception e) {
            return "error";
        }
    }

    /**
     * Probe a port for Philips MIB/RS232.
     * Configures to 115200 8N1 and checks if any data arrives within timeout.
     * Philips monitors in MIB mode periodically send association requests.
     */
    static String probePhilips(String port) {
        try {
            System.err.print("  " + port + " (115200 probe) ... ");

            int rc = configurePort(port, 115200);
            if (rc != 0) {
                System.err.println("SKIP (cannot configure)");
                return "error";
            }

            try (FileInputStream fin = new FileInputStream(port)) {
                long deadline = System.currentTimeMillis() + PROBE_TIMEOUT_MS;
                int bytesRead = 0;
                while (System.currentTimeMillis() < deadline) {
                    if (fin.available() > 0) {
                        fin.read();
                        bytesRead++;
                        if (bytesRead >= 4) {
                            System.err.println("PHILIPS (data at 115200 baud)");
                            return "philips";
                        }
                    } else {
                        Thread.sleep(50);
                    }
                }
                System.err.println("no data");
                return "unknown";
            }
        } catch (Exception e) {
            System.err.println("error: " + e.getMessage());
            return "error";
        }
    }

    /**
     * Configure serial port using stty.
     */
    static int configurePort(String port, int baud) throws Exception {
        ProcessBuilder pb = new ProcessBuilder(
            "stty", "-F", port,
            String.valueOf(baud),
            "cs8", "-cstopb", "-parenb",  // 8N1
            "raw", "-echo", "-echoe", "-echok"
        );
        pb.redirectErrorStream(true);
        Process p = pb.start();
        // Drain output
        try (InputStream is = p.getInputStream()) {
            while (is.read() != -1) {}
        }
        return p.waitFor();
    }

    /**
     * Find candidate serial ports on the system.
     */
    static List<String> findCandidatePorts() {
        List<String> ports = new ArrayList<>();
        // Built-in COM ports (AIR-021, most x86 boards)
        for (int i = 0; i <= 3; i++) {
            File f = new File("/dev/ttyS" + i);
            if (f.exists()) ports.add(f.getPath());
        }
        // USB serial adapters
        for (int i = 0; i <= 3; i++) {
            File f = new File("/dev/ttyUSB" + i);
            if (f.exists()) ports.add(f.getPath());
        }
        // ACM devices
        for (int i = 0; i <= 3; i++) {
            File f = new File("/dev/ttyACM" + i);
            if (f.exists()) ports.add(f.getPath());
        }
        return ports;
    }

    static byte hexHi(int v) { int n = (v >> 4) & 0x0F; return (byte)(n < 10 ? '0'+n : 'A'+n-10); }
    static byte hexLo(int v) { int n = v & 0x0F;        return (byte)(n < 10 ? '0'+n : 'A'+n-10); }
}
