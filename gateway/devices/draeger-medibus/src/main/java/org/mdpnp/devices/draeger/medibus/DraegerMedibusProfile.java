package org.mdpnp.devices.draeger.medibus;

public enum DraegerMedibusProfile {
    V500("v500", true, true, true, true, true, true, true, true),
    INTENSIVE_CARE("intensive-care", false, true, true, true, true, true, true, true),
    EVITA("evita", false, false, false, true, true, true, true, true),
    SAVINA("savina", false, true, false, true, true, true, true, true),
    FABIUS("fabius", false, true, false, true, true, true, true, true);

    private final String id;
    private final boolean measuredCodePage2;
    private final boolean alarmCodePage2;
    private final boolean alarmCodePage3;
    private final boolean alarmLimits;
    private final boolean settings;
    private final boolean textMessages;
    private final boolean realtime;
    private final boolean trend;

    DraegerMedibusProfile(String id, boolean measuredCodePage2, boolean alarmCodePage2,
            boolean alarmCodePage3, boolean alarmLimits, boolean settings, boolean textMessages,
            boolean realtime, boolean trend) {
        this.id = id;
        this.measuredCodePage2 = measuredCodePage2;
        this.alarmCodePage2 = alarmCodePage2;
        this.alarmCodePage3 = alarmCodePage3;
        this.alarmLimits = alarmLimits;
        this.settings = settings;
        this.textMessages = textMessages;
        this.realtime = realtime;
        this.trend = trend;
    }

    public String id() { return id; }
    public boolean measuredCodePage2() { return measuredCodePage2; }
    public boolean alarmCodePage2() { return alarmCodePage2; }
    public boolean alarmCodePage3() { return alarmCodePage3; }
    public boolean alarmLimits() { return alarmLimits; }
    public boolean settings() { return settings; }
    public boolean textMessages() { return textMessages; }
    public boolean realtime() { return realtime; }
    public boolean trend() { return trend; }

    public static DraegerMedibusProfile parse(String value) {
        if (value == null || value.length() == 0) { return V500; }
        String normalized = value.trim().toLowerCase().replace('_', '-');
        if ("default".equals(normalized) || "generic".equals(normalized) || "v500".equals(normalized)) { return V500; }
        if ("intensive".equals(normalized) || "intensive-care".equals(normalized) || "icu".equals(normalized)) { return INTENSIVE_CARE; }
        if ("evita".equals(normalized) || "evita4".equals(normalized) || "evita-4".equals(normalized)) { return EVITA; }
        if ("savina".equals(normalized)) { return SAVINA; }
        if ("fabius".equals(normalized) || "fabius-gs".equals(normalized) || "tiro".equals(normalized) || "fabius-tiro".equals(normalized)) { return FABIUS; }
        throw new IllegalArgumentException("Unknown Draeger profile: " + value + " (use v500, intensive-care, evita, savina, or fabius)");
    }
}
