package com.streambert.mobile;

public class BridgeSecurity {

    private final MainActivity activity;

    public BridgeSecurity(MainActivity activity) {
        this.activity = activity;
    }

    @android.webkit.JavascriptInterface
    public String secureGet(String key) {
        if (key == null) return null;
        String value = SecureStore.get(activity, "k_" + key);
        return value == null || value.isEmpty() ? null : value;
    }

    @android.webkit.JavascriptInterface
    public void secureSet(String key, String value) {
        if (key == null) return;
        SecureStore.set(activity, "k_" + key, value);
    }

    @android.webkit.JavascriptInterface
    public String hashSecret(String secret) {
        if (secret == null || secret.isEmpty()) return "";
        return PinHash.create(secret);
    }

    @android.webkit.JavascriptInterface
    public boolean verifySecret(String candidate, String stored) {
        return PinHash.verify(candidate, stored);
    }

    @android.webkit.JavascriptInterface
    public boolean isHashedSecret(String stored) {
        return PinHash.isHash(stored);
    }
}
