final class PhilipsVitalsModel {
    final int heartRate;
    final int spo2;
    final int respRate;
    final int abpSys;
    final int abpDia;
    final int nbpSys;
    final int nbpDia;
    final int etCo2;
    final int tempTenthsC;
    final int cvp;
    final int papSys;
    final int papDia;
    final String rhythm;
    final boolean waveformsEnabled;
    final boolean noiseEnabled;
    final PatientDemographics patient;

    PhilipsVitalsModel(
            int heartRate,
            int spo2,
            int respRate,
            int abpSys,
            int abpDia,
            int nbpSys,
            int nbpDia,
            int etCo2,
            int tempTenthsC,
            int cvp,
            int papSys,
            int papDia,
            String rhythm,
            boolean waveformsEnabled,
            boolean noiseEnabled,
            PatientDemographics patient) {
        this.heartRate = heartRate;
        this.spo2 = spo2;
        this.respRate = respRate;
        this.abpSys = abpSys;
        this.abpDia = abpDia;
        this.nbpSys = nbpSys;
        this.nbpDia = nbpDia;
        this.etCo2 = etCo2;
        this.tempTenthsC = tempTenthsC;
        this.cvp = cvp;
        this.papSys = papSys;
        this.papDia = papDia;
        this.rhythm = rhythm == null ? "" : rhythm;
        this.waveformsEnabled = waveformsEnabled;
        this.noiseEnabled = noiseEnabled;
        this.patient = patient;
    }

    int abpMean() {
        return abpSys > 0 ? (abpSys + 2 * abpDia) / 3 : 0;
    }

    int nbpMean() {
        return nbpSys > 0 ? (nbpSys + 2 * nbpDia) / 3 : 0;
    }

    int papMean() {
        return (papSys + 2 * papDia) / 3;
    }

    double tempC() {
        return tempTenthsC / 10.0;
    }
}
