package android.webkit;

/** Host tests only. This class is never compiled into the production or instrumentation APK. */
public final class CookieManager {
    private static final CookieManager INSTANCE = new CookieManager();
    public static CookieManager getInstance() { return INSTANCE; }
    public String getCookie(String url) { return null; }
}
