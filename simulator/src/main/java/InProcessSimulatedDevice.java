import java.util.concurrent.atomic.AtomicReference;

import org.mdpnp.simulator.core.DeviceDescriptor;
import org.mdpnp.simulator.core.DeviceStatus;
import org.mdpnp.simulator.core.SimulatedDevice;

final class InProcessSimulatedDevice implements SimulatedDevice {
    interface DeviceRunnable {
        void run() throws Exception;
    }

    private final DeviceDescriptor descriptor;
    private final DeviceRunnable runner;
    private final Runnable stopHook;
    private final AtomicReference<DeviceStatus> status = new AtomicReference<DeviceStatus>(DeviceStatus.STOPPED);
    private volatile Thread thread;
    private volatile String lastMessage = "Stopped";

    InProcessSimulatedDevice(DeviceDescriptor descriptor, DeviceRunnable runner, Runnable stopHook) {
        this.descriptor = descriptor;
        this.runner = runner;
        this.stopHook = stopHook;
    }

    @Override
    public DeviceDescriptor descriptor() {
        return descriptor;
    }

    @Override
    public DeviceStatus status() {
        Thread current = thread;
        if (current != null && status.get() == DeviceStatus.RUNNING && !current.isAlive()) {
            status.set(DeviceStatus.STOPPED);
        }
        return status.get();
    }

    @Override
    public String lastMessage() {
        status();
        return lastMessage;
    }

    @Override
    public synchronized void start() {
        if (status() == DeviceStatus.RUNNING || status() == DeviceStatus.STARTING) {
            return;
        }
        status.set(DeviceStatus.STARTING);
        thread = new Thread(() -> {
            status.set(DeviceStatus.RUNNING);
            lastMessage = "Running";
            try {
                runner.run();
                lastMessage = "Stopped";
                status.set(DeviceStatus.STOPPED);
            } catch (Exception e) {
                lastMessage = e.getMessage();
                status.set(DeviceStatus.FAILED);
            }
        }, "sim-" + descriptor.id());
        thread.setDaemon(true);
        thread.start();
    }

    @Override
    public synchronized void stop() {
        status.set(DeviceStatus.STOPPING);
        if (stopHook != null) {
            stopHook.run();
        }
        Thread current = thread;
        if (current != null) {
            try {
                current.join(1500);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        thread = null;
        status.set(DeviceStatus.STOPPED);
        lastMessage = "Stopped";
    }
}
