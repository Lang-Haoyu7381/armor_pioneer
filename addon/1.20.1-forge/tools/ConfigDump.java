import com.ywzjvehicle.armor.ArmorConfig;

import java.lang.reflect.Method;

/**
 * Prints exactly what ArmorConfig writes on first launch.
 *
 * <p>It calls the mod's own defaultProfile() and the mod's own writer rather than rebuilding
 * either here, so this dump can never drift away from what players actually get.
 */
public class ConfigDump {
    public static void main(String[] args) throws Exception {
        Method defaultProfile = ArmorConfig.class.getDeclaredMethod("defaultProfile");
        defaultProfile.setAccessible(true);
        ArmorConfig.Profile p = (ArmorConfig.Profile) defaultProfile.invoke(null);
        System.out.println(ArmorConfig.configWriter().toJson(p));
    }
}
