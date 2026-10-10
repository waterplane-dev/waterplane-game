package dev.waterplane.game;

import java.io.*;
import java.lang.reflect.Method;

/** Renders a scripted flight through Sfx's real mixer to a WAV file. */
public class RenderSfx {
    public static void main(String[] a) throws Exception {
        Sfx s = new Sfx();
        Method mix = Sfx.class.getDeclaredMethod("mix", short[].class);
        mix.setAccessible(true);
        ByteArrayOutputStream pcm = new ByteArrayOutputStream();
        short[] buf = new short[256];
        // {time, action}: engine levels and one-shots
        Object[][] script = {
            {0.0, "click"}, {0.3, "idle"}, {1.5, "throttle"}, {2.6, "idle"}, {2.8, "collect"},
            {3.2, "throttle"}, {3.6, "collect"}, {4.2, "idle"}, {4.6, "warn"}, {4.95, "warn"},
            {5.2, "skimstart"}, {6.8, "throttle"}, {7.4, "idle"}, {8.0, "crash"}, {9.4, "off"},
            {9.6, "click"}, {10.0, "win"}, {11.6, "end"}};
        int k = 0; double t = 0; boolean skim = false; float lvl = 0;
        while (true) {
            while (k < script.length && (double) script[k][0] <= t) {
                String act = (String) script[k++][1];
                switch (act) {
                    case "idle": lvl = 1; break;
                    case "throttle": lvl = 2; skim = false; break;
                    case "off": lvl = 0; skim = false; break;
                    case "skimstart": skim = true; s.play(Sfx.SPLASH, 0.45f); break;
                    case "crash": s.play(Sfx.CRASH, 1); lvl = 0; skim = false; break;
                    case "click": s.play(Sfx.CLICK, 1); break;
                    case "collect": s.play(Sfx.COLLECT, 1); break;
                    case "warn": s.play(Sfx.WARN, 1); break;
                    case "win": s.play(Sfx.WIN, 1); break;
                    case "end": write(pcm.toByteArray(), a[0]); return;
                }
                s.engine(lvl, skim);
            }
            mix.invoke(s, (Object) buf);
            for (short v : buf) { pcm.write(v & 0xff); pcm.write((v >> 8) & 0xff); }
            t += 256.0 / Sfx.RATE;
        }
    }

    static void write(byte[] d, String path) throws IOException {
        DataOutputStream o = new DataOutputStream(new FileOutputStream(path));
        o.writeBytes("RIFF"); o.writeInt(Integer.reverseBytes(36 + d.length)); o.writeBytes("WAVEfmt ");
        o.writeInt(Integer.reverseBytes(16)); o.writeShort(Short.reverseBytes((short) 1)); o.writeShort(Short.reverseBytes((short) 1));
        o.writeInt(Integer.reverseBytes(Sfx.RATE)); o.writeInt(Integer.reverseBytes(Sfx.RATE * 2));
        o.writeShort(Short.reverseBytes((short) 2)); o.writeShort(Short.reverseBytes((short) 16));
        o.writeBytes("data"); o.writeInt(Integer.reverseBytes(d.length)); o.write(d); o.close();
    }
}
