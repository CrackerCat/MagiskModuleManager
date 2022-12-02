package com.fox2code.mmm.androidacy;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Looper;
import android.util.Log;
import android.widget.Toast;

import androidx.annotation.NonNull;

import com.fingerprintjs.android.fingerprint.Fingerprinter;
import com.fingerprintjs.android.fingerprint.FingerprinterFactory;
import com.fingerprintjs.android.fpjs_pro.Configuration;
import com.fingerprintjs.android.fpjs_pro.FingerprintJS;
import com.fingerprintjs.android.fpjs_pro.FingerprintJSFactory;
import com.fox2code.mmm.BuildConfig;
import com.fox2code.mmm.MainApplication;
import com.fox2code.mmm.R;
import com.fox2code.mmm.manager.ModuleInfo;
import com.fox2code.mmm.repo.RepoData;
import com.fox2code.mmm.repo.RepoManager;
import com.fox2code.mmm.repo.RepoModule;
import com.fox2code.mmm.utils.Http;
import com.fox2code.mmm.utils.HttpException;
import com.fox2code.mmm.utils.PropUtils;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

import okhttp3.HttpUrl;

@SuppressWarnings("KotlinInternalInJava")
public final class AndroidacyRepoData extends RepoData {
    private static final String TAG = "AndroidacyRepoData";

    static {
        HttpUrl.Builder OK_HTTP_URL_BUILDER = new HttpUrl.Builder().scheme("https");
        // Using HttpUrl.Builder.host(String) crash the app
        OK_HTTP_URL_BUILDER.setHost$okhttp(".androidacy.com");
        OK_HTTP_URL_BUILDER.build();
    }

    private final boolean testMode;
    private final String host;
    public String token = MainApplication.getINSTANCE().getSharedPreferences("androidacy", 0).getString("pref_androidacy_api_token", null);
    // Avoid spamming requests to Androidacy
    private long androidacyBlockade = 0;

    public AndroidacyRepoData(File cacheRoot, SharedPreferences cachedPreferences, boolean testMode) {
        super(testMode ? RepoManager.ANDROIDACY_TEST_MAGISK_REPO_ENDPOINT : RepoManager.ANDROIDACY_MAGISK_REPO_ENDPOINT, cacheRoot, cachedPreferences);
        if (this.metaDataCache.exists() && !testMode) {
            this.androidacyBlockade = this.metaDataCache.lastModified() + 30_000L;
            if (this.androidacyBlockade - 60_000L > System.currentTimeMillis()) {
                this.androidacyBlockade = 0; // Don't allow time travel. // Well why not???
            }
        }
        this.defaultName = "Androidacy Modules Repo";
        this.defaultWebsite = RepoManager.ANDROIDACY_MAGISK_REPO_HOMEPAGE;
        this.defaultSupport = "https://t.me/androidacy_discussions";
        this.defaultDonate = "https://www.androidacy.com/membership-join/?utm_source=foxmmm&utm-medium=app&utm_campaign=fox-inapp";
        this.defaultSubmitModule = "https://www.androidacy.com/module-repository-applications/";
        this.host = testMode ? "staging-api.androidacy.com" : "production-api.androidacy.com";
        this.testMode = testMode;
    }

    public static AndroidacyRepoData getInstance() {
        return RepoManager.getINSTANCE().getAndroidacyRepoData();
    }

    private static String filterURL(String url) {
        if (url == null || url.isEmpty() || PropUtils.isInvalidURL(url)) {
            return null;
        }
        return url;
    }

    // Generates a unique device ID. This is used to identify the device in the API for rate
    // limiting and fraud detection.
    public static String generateDeviceId() {
        // Try to get the device ID from the shared preferences
        SharedPreferences sharedPreferences = MainApplication.getINSTANCE().getSharedPreferences("androidacy", 0);
        String deviceIdPref = sharedPreferences.getString("device_id", null);
        if (deviceIdPref != null) {
            return deviceIdPref;
        } else {
            Context context = MainApplication.getINSTANCE().getApplicationContext();
            FingerprintJSFactory factory = new FingerprintJSFactory(context);
            Configuration.Region region = Configuration.Region.US;
            Configuration configuration = new Configuration(
                    "NiZiHi266YaTLreOIOzc",
                    region,
                    region.getEndpointUrl(),
                    true
            );

            FingerprintJS fpjsClient = factory.createInstance(
                    configuration
            );

            fpjsClient.getVisitorId(visitorIdResponse -> {
                // Use the ID
                String visitorId = visitorIdResponse.getVisitorId();
                // Save the ID in the shared preferences
                SharedPreferences.Editor editor = sharedPreferences.edit();
                editor.putString("device_id", visitorId);
                editor.apply();
                return null;
            });
            // return the id
            return sharedPreferences.getString("device_id", null);
        }
    }

    public boolean isValidToken(String token) throws IOException {
        String deviceId = generateDeviceId();
        try {
            Http.doHttpGet("https://" + this.host + "/auth/me?token=" + token + "&device_id=" + deviceId, false);
        } catch (HttpException e) {
            if (e.getErrorCode() == 401) {
                Log.w(TAG, "Invalid token, resetting...");
                // Remove saved preference
                SharedPreferences.Editor editor = MainApplication.getINSTANCE().getSharedPreferences("androidacy", 0).edit();
                editor.remove("pref_androidacy_api_token");
                editor.apply();
                return false;
            }
            throw e;
        }
        // If status code is 200, we are good
        return true;
    }

    @Override
    protected boolean prepare() {
        if (Http.needCaptchaAndroidacy()) return false;
        // Check if we have a device ID yet
        SharedPreferences sharedPreferences = MainApplication.getINSTANCE().getSharedPreferences("androidacy", 0);
        String deviceIdPref = sharedPreferences.getString("device_id", null);
        if (deviceIdPref == null) {
            // Generate a device ID
            generateDeviceId();
            // Loop until we have a device ID
            while (sharedPreferences.getString("device_id", null) == null) {
                try {
                    Thread.sleep(100);
                } catch (InterruptedException e) {
                    e.printStackTrace();
                }
            }
        }
        // Implementation details discussed on telegram
        // First, ping the server to check if it's alive
        try {
            Http.doHttpGet("https://" + this.host + "/ping", false);
        } catch (Exception e) {
            Log.e(TAG, "Failed to ping server", e);
            // Inform user
            Looper.prepare();
            MaterialAlertDialogBuilder builder = new MaterialAlertDialogBuilder(MainApplication.getINSTANCE().getBaseContext());
            builder.setTitle("Androidacy Server Down");
            builder.setMessage("The Androidacy server is down. Unfortunately, this means that you" +
                    " will not be able to download or view modules from the Androidacy repository" +
                    ". Please try again later.");
            builder.setPositiveButton("OK", (dialog, which) -> dialog.dismiss());
            builder.show();
            Looper.loop();
            return false;
        }
        String deviceId = generateDeviceId();
        long time = System.currentTimeMillis();
        if (this.androidacyBlockade > time) return false;
        this.androidacyBlockade = time + 30_000L;
        try {
            if (this.token == null) {
                this.token = this.cachedPreferences.getString("pref_androidacy_api_token", null);
                if (this.token != null && !this.isValidToken(this.token)) {
                    this.token = null;
                } else {
                    Log.i(TAG, "Using cached token");
                }
            } else if (!this.isValidToken(this.token)) {
                if (BuildConfig.DEBUG) {
                    throw new IllegalStateException("Invalid token: " + this.token);
                }
                this.token = null;
            }
        } catch (IOException e) {
            if (HttpException.shouldTimeout(e)) {
                Log.e(TAG, "We are being rate limited!", e);
                this.androidacyBlockade = time + 3_600_000L;
            }
            return false;
        }
        if (token == null) {
            try {
                Log.i(TAG, "Requesting new token...");
                // POST json request to https://production-api.androidacy.com/auth/register
                token = new String(Http.doHttpPost("https://" + this.host + "/auth/register", "{\"device_id\":\"" + deviceId + "\"}", false));
                // Parse token
                try {
                    JSONObject jsonObject = new JSONObject(token);
                    token = jsonObject.getString("token");
                } catch (JSONException e) {
                    Log.e(TAG, "Failed to parse token", e);
                    // Show a toast
                    Toast.makeText(MainApplication.getINSTANCE(), R.string.androidacy_failed_to_parse_token, Toast.LENGTH_LONG).show();
                    return false;
                }
                // Ensure token is valid
                if (!isValidToken(token)) {
                    Log.e(TAG, "Failed to validate token");
                    // Show a toast
                    Toast.makeText(MainApplication.getINSTANCE(), R.string.androidacy_failed_to_validate_token, Toast.LENGTH_LONG).show();
                    return false;
                }
                // Save token to shared preference
                SharedPreferences.Editor editor = MainApplication.getINSTANCE().getSharedPreferences("androidacy", 0).edit();
                editor.putString("pref_androidacy_api_token", token);
                editor.apply();
            } catch (Exception e) {
                if (HttpException.shouldTimeout(e)) {
                    Log.e(TAG, "We are being rate limited!", e);
                    this.androidacyBlockade = time + 3_600_000L;
                }
                Log.e(TAG, "Failed to get a new token", e);
                return false;
            }
        }
        //noinspection SillyAssignment // who are you calling silly?
        this.token = token;
        return true;
    }

    @Override
    protected List<RepoModule> populate(JSONObject jsonObject) throws JSONException {
        if (!jsonObject.getString("status").equals("success"))
            throw new JSONException("Response is not a success!");
        String name = jsonObject.optString("name", "Androidacy Modules Repo");
        String nameForModules = name.endsWith(" (Official)") ? name.substring(0, name.length() - 11) : name;
        JSONArray jsonArray = jsonObject.getJSONArray("data");
        for (RepoModule repoModule : this.moduleHashMap.values()) {
            repoModule.processed = false;
        }
        ArrayList<RepoModule> newModules = new ArrayList<>();
        int len = jsonArray.length();
        long lastLastUpdate = 0;
        for (int i = 0; i < len; i++) {
            jsonObject = jsonArray.getJSONObject(i);
            String moduleId = jsonObject.getString("codename");
            // Deny remote modules ids shorter than 3 chars or containing null char or space
            if (moduleId.length() < 3 || moduleId.indexOf('\0') != -1 || moduleId.indexOf(' ') != -1 || "ak3-helper".equals(moduleId))
                continue;
            long lastUpdate = jsonObject.getLong("updated_at") * 1000;
            lastLastUpdate = Math.max(lastLastUpdate, lastUpdate);
            RepoModule repoModule = this.moduleHashMap.get(moduleId);
            if (repoModule == null) {
                repoModule = new RepoModule(this, moduleId);
                repoModule.moduleInfo.flags = 0;
                this.moduleHashMap.put(moduleId, repoModule);
                newModules.add(repoModule);
            } else {
                if (repoModule.lastUpdated < lastUpdate) {
                    newModules.add(repoModule);
                }
            }
            repoModule.processed = true;
            repoModule.lastUpdated = lastUpdate;
            repoModule.repoName = nameForModules;
            repoModule.zipUrl = filterURL(jsonObject.optString("zipUrl", ""));
            repoModule.notesUrl = filterURL(jsonObject.optString("notesUrl", ""));
            if (repoModule.zipUrl == null) {
                repoModule.zipUrl = // Fallback url in case the API doesn't have zipUrl
                        "https://" + this.host + "/magisk/info/" + moduleId;
            }
            if (repoModule.notesUrl == null) {
                repoModule.notesUrl = // Fallback url in case the API doesn't have notesUrl
                        "https://" + this.host + "/magisk/readme/" + moduleId;
            }
            repoModule.zipUrl = this.injectToken(repoModule.zipUrl);
            repoModule.notesUrl = this.injectToken(repoModule.notesUrl);
            repoModule.qualityText = R.string.module_downloads;
            repoModule.qualityValue = jsonObject.optInt("downloads", 0);
            String checksum = jsonObject.optString("checksum", "");
            repoModule.checksum = checksum.isEmpty() ? null : checksum;
            ModuleInfo moduleInfo = repoModule.moduleInfo;
            moduleInfo.name = jsonObject.getString("name");
            moduleInfo.versionCode = jsonObject.getLong("versionCode");
            moduleInfo.version = jsonObject.optString("version", "v" + moduleInfo.versionCode);
            moduleInfo.author = jsonObject.optString("author", "Unknown");
            moduleInfo.description = jsonObject.optString("description", "");
            moduleInfo.minApi = jsonObject.getInt("minApi");
            moduleInfo.maxApi = jsonObject.getInt("maxApi");
            String minMagisk = jsonObject.getString("minMagisk");
            try {
                int c = minMagisk.indexOf('.');
                if (c == -1) {
                    moduleInfo.minMagisk = Integer.parseInt(minMagisk);
                } else {
                    moduleInfo.minMagisk = // Allow 24.1 to mean 24100
                            (Integer.parseInt(minMagisk.substring(0, c)) * 1000) + (Integer.parseInt(minMagisk.substring(c + 1)) * 100);
                }
            } catch (Exception e) {
                moduleInfo.minMagisk = 0;
            }
            moduleInfo.needRamdisk = jsonObject.optBoolean("needRamdisk", false);
            moduleInfo.changeBoot = jsonObject.optBoolean("changeBoot", false);
            moduleInfo.mmtReborn = jsonObject.optBoolean("mmtReborn", false);
            moduleInfo.support = filterURL(jsonObject.optString("support"));
            moduleInfo.donate = filterURL(jsonObject.optString("donate"));
            String config = jsonObject.optString("config", "");
            moduleInfo.config = config.isEmpty() ? null : config;
            PropUtils.applyFallbacks(moduleInfo); // Apply fallbacks
            Log.d(TAG, "Module " + moduleInfo.name + " " + moduleInfo.id + " " + moduleInfo.version + " " + moduleInfo.versionCode);
        }
        Iterator<RepoModule> moduleInfoIterator = this.moduleHashMap.values().iterator();
        while (moduleInfoIterator.hasNext()) {
            RepoModule repoModule = moduleInfoIterator.next();
            if (!repoModule.processed) {
                moduleInfoIterator.remove();
            } else {
                repoModule.moduleInfo.verify();
            }
        }
        this.lastUpdate = lastLastUpdate;
        this.name = name;
        this.website = jsonObject.optString("website");
        this.support = jsonObject.optString("support");
        this.donate = jsonObject.optString("donate");
        this.submitModule = jsonObject.optString("submitModule");
        return newModules;
    }

    @Override
    public void storeMetadata(RepoModule repoModule, byte[] data) {
    }

    @Override
    public boolean tryLoadMetadata(RepoModule repoModule) {
        if (this.moduleHashMap.containsKey(repoModule.id)) {
            repoModule.moduleInfo.flags &= ~ModuleInfo.FLAG_METADATA_INVALID;
            return true;
        }
        repoModule.moduleInfo.flags |= ModuleInfo.FLAG_METADATA_INVALID;
        return false;
    }

    @Override
    public String getUrl() {
        return this.token == null ? this.url :
                this.url + "?token=" + this.token + "&v=" + BuildConfig.VERSION_CODE + "&c=" + BuildConfig.VERSION_NAME + "&device_id=" + generateDeviceId();
    }

    private String injectToken(String url) {
        // Do not inject token for non Androidacy urls
        if (!AndroidacyUtil.isAndroidacyLink(url)) return url;
        if (this.testMode) {
            if (url.startsWith("https://production-api.androidacy.com/")) {
                Log.e(TAG, "Got non test mode url: " + AndroidacyUtil.hideToken(url));
                url = "https://staging-api.androidacy.com/" + url.substring(38);
            }
        } else {
            if (url.startsWith("https://staging-api.androidacy.com/")) {
                Log.e(TAG, "Got test mode url: " + AndroidacyUtil.hideToken(url));
                url = "https://production-api.androidacy.com/" + url.substring(35);
            }
        }
        String token = "token=" + this.token;
        String deviceId = "device_id=" + generateDeviceId();
        if (!url.contains(token)) {
            if (url.lastIndexOf('/') < url.lastIndexOf('?')) {
                return url + '&' + token;
            } else {
                return url + '?' + token;
            }
        }
        if (!url.contains(deviceId)) {
            if (url.lastIndexOf('/') < url.lastIndexOf('?')) {
                return url + '&' + deviceId;
            } else {
                return url + '?' + deviceId;
            }
        }
        return url;
    }

    @NonNull
    @Override
    public String getName() {
        return this.testMode ? super.getName() + " (Test Mode)" : super.getName();
    }

    public void setToken(String token) {
        if (Http.hasWebView()) {
            this.token = token;
        }
    }
}
