package com.sabag.aura;

import android.os.Build;

/** What the TV is, for the open-apps header, "about this TV" and the logo on its little portrait. */
final class Device {
    final String name, screen, platform, chip, logo;

    private Device(String name, String screen, String platform, String chip, String logo) {
        this.name = name;
        this.screen = screen;
        this.platform = platform;
        this.chip = chip;
        this.logo = logo;
    }

    private static Device current;

    static Device get() {
        if (current == null) current = detect();
        return current;
    }

    private static Device detect() {
        String maker = Build.MANUFACTURER == null ? "" : Build.MANUFACTURER;
        String model = Build.MODEL == null ? "" : Build.MODEL;
        String android = "Android " + Build.VERSION.RELEASE;
        if (maker.equalsIgnoreCase("TCL")) {
            return new Device("TCL Smart TV Pro", "75 אינץ׳ · 4K UHD", "Google TV · " + android, "Realtek RTD2881", "TCL");
        }
        if (model.startsWith("MiTV-MSSP3")) {
            return new Device("Xiaomi Mi TV 4S", "43 אינץ׳ · 4K UHD", "Android TV · " + android, "MStar 6886", "Mi");
        }
        String soc = Build.VERSION.SDK_INT >= 31 ? Build.SOC_MODEL : Build.HARDWARE;
        return new Device((maker + " " + model).trim(), "", "Android TV · " + android, soc, maker);
    }
}
