package org.subsound.configuration.constants;

public class Constants {
    public static final String APP_ID = "io.github.Subsound";
    public static final String PROJECT_LINK = "https://github.com/subsoundorg/subsound-gtk";
    public static final String USER_AGENT = "%s/1.0 (%s)".formatted(Constants.APP_ID, Constants.PROJECT_LINK);
    public static final class Application {
        public static final String VERSION = "@version@";
    }
}
