final class PatientDemographics {
    final String fullName;
    final String patientId;
    final String dob;
    final String sex;
    final String heightCm;
    final String weightKg;

    PatientDemographics(String fullName, String patientId, String dob, String sex, String heightCm, String weightKg) {
        this.fullName = clean(fullName);
        this.patientId = clean(patientId);
        this.dob = clean(dob);
        this.sex = clean(sex);
        this.heightCm = clean(heightCm);
        this.weightKg = clean(weightKg);
    }

    private static String clean(String value) {
        return value == null ? "" : value.trim();
    }
}
