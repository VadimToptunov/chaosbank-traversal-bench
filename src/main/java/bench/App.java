package bench;

import io.appium.java_client.AppiumBy;
import io.appium.java_client.AppiumDriver;
import io.appium.java_client.android.AndroidDriver;
import io.appium.java_client.android.options.UiAutomator2Options;
import io.appium.java_client.ios.IOSDriver;
import io.appium.java_client.ios.options.XCUITestOptions;
import org.openqa.selenium.By;
import org.openqa.selenium.WebElement;
import org.openqa.selenium.support.ui.WebDriverWait;

import java.net.URI;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * One Appium session against ChaosBank. Same accessibility ids on both platforms,
 * so the steps never branch on the platform; only launching and locating do.
 */
public final class App {
    public enum Platform { IOS, ANDROID }

    public static final String IOS_BUNDLE = "VadimToptunov.ChaosBank";
    public static final String ANDROID_PACKAGE = "com.vadimtoptunov.chaosbank_android";

    public final Platform platform;
    public final AppiumDriver driver;
    private final List<String> defects;

    private App(Platform platform, AppiumDriver driver, List<String> defects) {
        this.platform = platform;
        this.driver = driver;
        this.defects = defects;
    }

    public static App connect(Config cfg) throws Exception {
        URI server = URI.create(cfg.appiumUrl());
        AppiumDriver driver;
        if (cfg.platform() == Platform.IOS) {
            XCUITestOptions o = new XCUITestOptions()
                    .setBundleId(IOS_BUNDLE)
                    .setNoReset(true)
                    .setAutoAcceptAlerts(true)
                    .setNewCommandTimeout(Duration.ofMinutes(5));
            if (cfg.udid() != null) o.setUdid(cfg.udid());
            if (cfg.device() != null) o.setDeviceName(cfg.device());
            o.setCapability("appium:shouldTerminateApp", true);
            driver = new IOSDriver(server.toURL(), o);
        } else {
            UiAutomator2Options o = new UiAutomator2Options()
                    .setAppPackage(ANDROID_PACKAGE)
                    .setAppActivity(".MainActivity")
                    .setNoReset(true)
                    .setUiautomator2ServerLaunchTimeout(Duration.ofMinutes(2))
                    .setNewCommandTimeout(Duration.ofMinutes(5));
            if (cfg.udid() != null) o.setUdid(cfg.udid());
            driver = new AndroidDriver(server.toURL(), o);
        }
        driver.manage().timeouts().implicitlyWait(Duration.ZERO);
        return new App(cfg.platform(), driver, cfg.defects());
    }

    /** Kills the app and starts it again, unlocked, with the run's defects. */
    public void launchFresh() {
        String joined = String.join(",", defects);
        if (platform == Platform.IOS) {
            driver.executeScript("mobile: terminateApp", Map.of("bundleId", IOS_BUNDLE));
            List<String> args = new ArrayList<>(List.of("-ChaosBankStartUnlocked", "1"));
            if (!defects.isEmpty()) args.addAll(List.of("-ChaosBankDefects", joined));
            driver.executeScript("mobile: launchApp", Map.of("bundleId", IOS_BUNDLE, "arguments", args));
        } else {
            List<List<String>> extras = new ArrayList<>();
            extras.add(List.of("s", "CHAOSBANK_START_UNLOCKED", "1"));
            if (!defects.isEmpty()) extras.add(List.of("s", "CHAOSBANK_DEFECTS", joined));
            // `component`, not `intent`: the driver puts `intent` positionally before
            // the extras, and `am` ignores every option after a positional argument.
            driver.executeScript("mobile: startActivity", Map.of(
                    "component", ANDROID_PACKAGE + "/.MainActivity",
                    "wait", true,
                    "stop", true,
                    "extras", extras));
        }
    }

    public By byId(String id) {
        return platform == Platform.IOS
                ? AppiumBy.accessibilityId(id)
                // Needs Compose `testTagsAsResourceId = true` in the app.
                : AppiumBy.androidUIAutomator("new UiSelector().resourceId(\"" + id + "\")");
    }

    public WebElement el(String id) {
        return el(id, Duration.ofSeconds(10));
    }

    public WebElement el(String id, Duration timeout) {
        By by = byId(id);
        return new WebDriverWait(driver, timeout, Duration.ofMillis(100))
                .withMessage("no element '" + id + "' within " + timeout.toSeconds() + " s")
                .until(d -> {
                    List<WebElement> found = d.findElements(by);
                    return found.isEmpty() ? null : found.get(0);
                });
    }

    public boolean gone(String id, Duration timeout) {
        By by = byId(id);
        try {
            new WebDriverWait(driver, timeout, Duration.ofMillis(150)).until(d -> d.findElements(by).isEmpty());
            return true;
        } catch (RuntimeException e) {
            return false;
        }
    }

    /** What a user would read: the label on iOS, the text or content description on Android. */
    public String read(WebElement e) {
        if (platform == Platform.IOS) {
            String label = e.getAttribute("label");
            return label != null ? label : String.valueOf(e.getAttribute("value"));
        }
        String text = e.getText();
        if (text != null && !text.isBlank()) return text;
        String desc = e.getAttribute("content-desc");
        if (desc != null && !desc.isBlank()) return desc;
        // A Compose container (a card) doesn't carry its children's text the way an
        // iOS combined element carries its label, so read them in order, like TalkBack.
        StringBuilder all = new StringBuilder();
        for (WebElement child : e.findElements(By.xpath(".//*[@text!='']"))) {
            all.append(all.isEmpty() ? "" : " ").append(child.getText());
        }
        return all.toString();
    }

    public String value(WebElement e) {
        String v = platform == Platform.IOS ? e.getAttribute("value") : e.getText();
        return v == null ? "" : v;
    }

    private static final Pattern NUMBER = Pattern.compile("([+\\-−]?)[€$£]?([0-9][0-9,]*(?:\\.[0-9]+)?)");

    /** The last number in a label, e.g. 12,345.67 from "EUR €12,345.67". */
    public static double lastNumber(String label) {
        Matcher m = NUMBER.matcher(label);
        Double last = null;
        while (m.find()) {
            double v = Double.parseDouble(m.group(2).replace(",", ""));
            String sign = m.group(1);
            last = sign.equals("-") || sign.equals("−") ? -v : v;
        }
        if (last == null) throw new AssertionError("no number in '" + label + "'");
        return last;
    }

    /** Puts the keyboard away so it doesn't cover the button below the form (Android only). */
    public void hideKeyboard() {
        if (platform != Platform.ANDROID) return;
        AndroidDriver android = (AndroidDriver) driver;
        if (android.isKeyboardShown()) android.hideKeyboard();
    }

    public void quit() {
        try { driver.quit(); } catch (RuntimeException ignored) { }
    }
}
