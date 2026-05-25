import java.nio.ByteBuffer;

final class PhilipsWaveformFrame {
    static final int NOM_PART_SCADA = 0x0002;
    static final int NOM_ATTR_SA_VAL_OBS = 0x096E;
    static final int NOM_ATTR_SA_SPECN = 0x096D;
    static final int NOM_ATTR_SA_FIXED_VAL_SPECN = 0x0A16;
    static final int NOM_ATTR_TIME_PD_SAMP = 0x098D;
    static final int NOM_ATTR_UNIT_CODE = 0x0996;
    static final int NOM_ATTR_SA_RANGE_PHYS_I16 = 0x096A;
    static final int NOM_ATTR_ID_TYPE = 0x092F;

    private PhilipsWaveformFrame() { }

    static void writeObservation(ByteBuffer buf, int handle, int physioId, short[] samples, int sampleRate, int unitCode) {
        buf.putShort((short) handle);

        buf.putShort((short) 7);
        int attrListLenPos = buf.position();
        buf.putShort((short) 0);
        int attrStart = buf.position();

        buf.putShort((short) NOM_ATTR_SA_VAL_OBS);
        buf.putShort((short) (6 + samples.length));
        buf.putShort((short) physioId);
        buf.putShort((short) 0x0000);
        buf.putShort((short) samples.length);
        for (short sample : samples) {
            buf.put(sampleByte(sample));
        }

        buf.putShort((short) NOM_ATTR_SA_SPECN);
        buf.putShort((short) 6);
        buf.putShort((short) samples.length);
        buf.put((byte) 8);
        buf.put((byte) 8);
        buf.putShort((short) 0);

        buf.putShort((short) NOM_ATTR_TIME_PD_SAMP);
        buf.putShort((short) 4);
        buf.putInt(relativeTimeTicks(sampleRate));

        buf.putShort((short) NOM_ATTR_SA_FIXED_VAL_SPECN);
        buf.putShort((short) 8);
        buf.putShort((short) 1);
        buf.putShort((short) 4);
        buf.putShort((short) 0);
        buf.putShort((short) 255);

        buf.putShort((short) NOM_ATTR_UNIT_CODE);
        buf.putShort((short) 2);
        buf.putShort((short) unitCode);

        buf.putShort((short) NOM_ATTR_SA_RANGE_PHYS_I16);
        buf.putShort((short) 4);
        buf.putShort((short) 0);
        buf.putShort((short) 255);

        buf.putShort((short) NOM_ATTR_ID_TYPE);
        buf.putShort((short) 4);
        buf.putShort((short) NOM_PART_SCADA);
        buf.putShort((short) physioId);

        int attrEnd = buf.position();
        buf.putShort(attrListLenPos, (short) (attrEnd - attrStart));
    }

    static byte sampleByte(short sample) {
        int value = sample;
        if (value < 0) {
            value += 2048;
        }
        if (value > 255) {
            value = (int) Math.round(Math.max(0, Math.min(4095, value)) / 4095.0 * 255.0);
        }
        if (value < 0) { value = 0; }
        if (value > 255) { value = 255; }
        return (byte) value;
    }

    private static int relativeTimeTicks(int sampleRate) {
        return Math.max(1, Math.round(8000.0f / Math.max(1, sampleRate)));
    }
}
