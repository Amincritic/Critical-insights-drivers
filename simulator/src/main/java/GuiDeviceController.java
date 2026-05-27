import java.util.concurrent.atomic.AtomicBoolean;

final class GuiDeviceController {
    enum State {
        STOPPED,
        STARTING,
        CONNECTED,
        RECONNECTING,
        FAULT
    }

    private final String name;
    private final AtomicBoolean running = new AtomicBoolean(false);
    private volatile State state = State.STOPPED;
    private volatile String message = "Stopped";

    GuiDeviceController(String name) {
        this.name = name;
    }

    String name() {
        return name;
    }

    boolean beginStarting(String message) {
        if (!running.compareAndSet(false, true)) {
            return false;
        }
        setState(State.STARTING, message);
        return true;
    }

    void requestStop() {
        running.set(false);
    }

    boolean isRunning() {
        return running.get();
    }

    boolean isActive() {
        return state != State.STOPPED;
    }

    State state() {
        return state;
    }

    String message() {
        return message;
    }

    void setState(State state, String message) {
        this.state = state;
        this.message = message;
    }
}
