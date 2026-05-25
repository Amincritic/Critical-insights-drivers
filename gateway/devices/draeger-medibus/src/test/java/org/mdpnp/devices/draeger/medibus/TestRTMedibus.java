package org.mdpnp.devices.draeger.medibus;

import org.junit.Test;
import static org.junit.Assert.assertEquals;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;

import org.mdpnp.devices.draeger.medibus.types.RealtimeData;

public class TestRTMedibus {
    @Test
    public void testParseInt() throws Exception {
        String[] str = new String[] {"101.483", "-100", "  -1", "  1  ", "999", "-4.342   ", "-  80"};
        int[]    itg = new int[]    {101, -100, -1, 1, 999, -4, -80};
        for(int i = 0; i < str.length; i++) {
            assertEquals(itg[i], RTMedibus.parseInt(str[i].getBytes("ASCII")));
        }
        
        assertEquals(-80, RTMedibus.parseInt(new byte[] {'1','0','-',' ',' ','-',' ',' ','8','0','.','5'}, 3, 9));
    }

    @Test
    public void testRealtimeValueBytesAreHighThenLowSixBits() throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        CapturingRTMedibus medibus = new CapturingRTMedibus(out);
        RTMedibus.RTDataConfig config = new RTMedibus.RTDataConfig();
        config.realtimeData = RealtimeData.AirwayPressure;
        config.min = 0;
        config.max = 4095;
        config.maxbin = 4095;
        medibus.sendRTTransmissionCommand(new RTMedibus.RTTransmit[] {
                new RTMedibus.RTTransmit(RealtimeData.AirwayPressure, 1, config)
        });
        medibus.receiveRealtimeConfigSuccess();

        medibus.receiveData(0x81, 0x82);

        assertEquals(66.0, medibus.lastValue, 0.001);
    }

    private static class CapturingRTMedibus extends RTMedibus {
        double lastValue;

        CapturingRTMedibus(ByteArrayOutputStream out) throws Exception {
            super(new ByteArrayInputStream(new byte[0]), out);
        }

        @Override
        public void receiveDataValue(RTDataConfig config, int multiplier, int streamIndex, Object realtimeData, double data) {
            lastValue = data;
        }
    }
}
