package org.mdpnp.simulator.core;

public interface SimulatedDevice extends AutoCloseable {
    DeviceDescriptor descriptor();
    DeviceStatus status();
    String lastMessage();
    void start() throws Exception;
    void stop();

    @Override
    default void close() {
        stop();
    }
}
