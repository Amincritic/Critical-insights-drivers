final class DraegerVitalsModel {
    final int tidalVolume;
    final int respRate;
    final int peakPressure;
    final int peep;
    final int fio2;
    final int compliance;
    final int resistance;
    final int airwayTemp;
    final String model;
    final String ventMode;
    final boolean waveformsEnabled;
    final boolean noiseEnabled;

    DraegerVitalsModel(
            int tidalVolume,
            int respRate,
            int peakPressure,
            int peep,
            int fio2,
            int compliance,
            int resistance,
            int airwayTemp,
            String model,
            String ventMode,
            boolean waveformsEnabled,
            boolean noiseEnabled) {
        this.tidalVolume = tidalVolume;
        this.respRate = respRate;
        this.peakPressure = peakPressure;
        this.peep = peep;
        this.fio2 = fio2;
        this.compliance = compliance;
        this.resistance = resistance;
        this.airwayTemp = airwayTemp;
        this.model = model == null ? "" : model;
        this.ventMode = ventMode == null ? "" : ventMode;
        this.waveformsEnabled = waveformsEnabled;
        this.noiseEnabled = noiseEnabled;
    }

    double minuteVolume() {
        return tidalVolume * respRate / 1000.0;
    }
}
