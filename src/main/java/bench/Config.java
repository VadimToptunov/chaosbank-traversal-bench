package bench;

import java.util.Arrays;
import java.util.List;

/** Run settings, from system properties (-Dplatform=ios -Ddefects=a,b ...). */
public record Config(App.Platform platform, String appiumUrl, String udid, String device, List<String> defects) {

    public static Config fromSystem() {
        return fromSystem(System.getProperty("defects", ""));
    }

    public static Config fromSystem(String defects) {
        App.Platform p = System.getProperty("platform", "ios").equalsIgnoreCase("android")
                ? App.Platform.ANDROID : App.Platform.IOS;
        return new Config(p,
                System.getProperty("appium", "http://127.0.0.1:4723"),
                System.getProperty("udid"),
                System.getProperty("device"),
                Arrays.stream(defects.split(",")).map(String::trim).filter(s -> !s.isEmpty()).toList());
    }

    public Config withDefects(List<String> d) {
        return new Config(platform, appiumUrl, udid, device, d);
    }
}
