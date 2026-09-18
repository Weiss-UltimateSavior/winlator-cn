package com.winlator;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Bundle;
import android.system.Os;
import android.util.Log;

import androidx.appcompat.app.AppCompatActivity;
import androidx.preference.PreferenceManager;

import com.winlator.container.Container;
import com.winlator.container.ContainerManager;
import com.winlator.container.LaunchArgs;
import com.winlator.container.Shortcut;
import com.winlator.contentdialog.ContentDialog;
import com.winlator.core.AppUtils;
import com.winlator.core.LaunchPathResolver;
import com.winlator.core.LocaleHelper;
import com.winlator.core.StringUtils;
import com.winlator.xenvironment.RootFS;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.util.Iterator;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class ExternalLaunchActivity extends AppCompatActivity {
    private static final String TAG = "ExternalLaunch";
    public static final String PREF_ALLOW_EXTERNAL_LAUNCH = "allow_external_launch";
    public static final String PREF_EXTERNAL_LAUNCH_CONFIRM = "external_launch_confirm";

    private static final Pattern CONTAINER_DIR_PATTERN = Pattern.compile(RootFS.USER+"-(\\d+)(?:/|$)");

    private static class LaunchRequest {
        private int containerId;
        private String containerName;
        private String shortcutPath;
        private String exePath;
        private String dirPath;
        private String execArgs;
        private String overrides;
        private String launchId;
        private Boolean confirm;
        private boolean save;
    }

    @Override
    protected void attachBaseContext(Context newBase) {
        super.attachBaseContext(LocaleHelper.setSystemLocale(newBase));
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        AppUtils.setActivityTheme(this);
        super.onCreate(savedInstanceState);

        try {
            handleLaunch(getIntent());
        }
        catch (Exception e) {
            Log.e(TAG, "External launch failed", e);
            AppUtils.showToast(this, R.string.external_launch_failed);
            finish();
        }
    }

    private void handleLaunch(Intent intent) {
        SharedPreferences preferences = PreferenceManager.getDefaultSharedPreferences(this);
        if (!preferences.getBoolean(PREF_ALLOW_EXTERNAL_LAUNCH, true)) {
            AppUtils.showToast(this, R.string.external_launch_disabled);
            finish();
            return;
        }

        LaunchRequest request = parseIntent(intent);
        if (request.exePath == null && request.shortcutPath == null && !hasText(request.dirPath)) {
            AppUtils.showToast(this, R.string.external_launch_no_executable);
            finish();
            return;
        }
        if (request.exePath != null && request.shortcutPath != null) {
            AppUtils.showToast(this, R.string.external_launch_conflict);
            finish();
            return;
        }

        ContainerManager containerManager = new ContainerManager(this);
        Container container = resolveContainer(containerManager, request);
        if (container == null) {
            AppUtils.showToast(this, R.string.external_launch_container_not_found);
            finish();
            return;
        }

        String execUnixPath = null;
        String dirUnixPath = null;
        String mountPath = null;
        String mountLetter = null;
        String requestedLetter = null;

        if (hasText(request.dirPath)) {
            LaunchPathResolver.Result dirResult = LaunchPathResolver.resolve(request.dirPath, container);
            if (!dirResult.isSuccess()) {
                AppUtils.showToast(this, getPathErrorMessage(dirResult.error, request.dirPath));
                finish();
                return;
            }
            if (!(new File(dirResult.unixPath)).isDirectory()) {
                AppUtils.showToast(this, getString(R.string.external_launch_dir_not_found, request.dirPath));
                finish();
                return;
            }

            dirUnixPath = dirResult.unixPath;
            if (dirResult.needsMount()) mountPath = dirResult.mountPath;
        }

        if (request.exePath != null) {
            String exeInput = request.exePath.trim();
            String relativeExe = null;

            if (dirUnixPath != null && LaunchPathResolver.isDosPath(exeInput)) {
                requestedLetter = LaunchPathResolver.getDriveLetter(exeInput);
                if (!LaunchPathResolver.hasDriveLetter(container, requestedLetter)) {
                    relativeExe = StringUtils.removeStartSlash(exeInput.substring(2).replace("\\", "/"));
                }
                else requestedLetter = null;
            }
            else if (dirUnixPath != null && !exeInput.startsWith("/") && !exeInput.startsWith("file://") && !exeInput.startsWith("content://")) {
                relativeExe = exeInput;
            }

            if (relativeExe != null) {
                execUnixPath = relativeExe.isEmpty() ? dirUnixPath : dirUnixPath+"/"+relativeExe;
                if (!(new File(execUnixPath)).exists()) {
                    AppUtils.showToast(this, getString(R.string.external_launch_file_not_found, request.exePath));
                    finish();
                    return;
                }
                if (requestedLetter != null) {
                    if (LaunchPathResolver.isReservedDriveLetter(requestedLetter)) {
                        AppUtils.showToast(this, getString(R.string.external_launch_mount_conflict));
                        finish();
                        return;
                    }
                    if (mountPath == null) mountPath = dirUnixPath;
                    mountLetter = null;
                }
            }
            else {
                LaunchPathResolver.Result result = LaunchPathResolver.resolve(exeInput, container);
                if (!result.isSuccess()) {
                    AppUtils.showToast(this, getPathErrorMessage(result.error, request.exePath));
                    finish();
                    return;
                }
                execUnixPath = result.unixPath;

                if (result.needsMount()) {
                    if (mountPath != null && !mountPath.equals(result.mountPath)) {
                        AppUtils.showToast(this, getString(R.string.external_launch_mount_conflict));
                        finish();
                        return;
                    }
                    mountPath = result.mountPath;
                }
            }
        }
        else if (execUnixPath == null && dirUnixPath != null) {
            execUnixPath = dirUnixPath;
        }
        else if (mountPath == null && execUnixPath == null && !(new File(request.shortcutPath)).isFile()) {
            AppUtils.showToast(this, getString(R.string.external_launch_file_not_found, request.shortcutPath));
            finish();
            return;
        }

        LaunchArgs.ValidationResult validation = LaunchArgs.validate(this, request.overrides);
        if (validation.hasErrors()) {
            AppUtils.showToast(this, getString(R.string.external_launch_invalid_overrides, android.text.TextUtils.join(", ", validation.errors)));
            finish();
            return;
        }
        if (!validation.warnings.isEmpty()) Log.w(TAG, "Ignored unknown overrides: "+validation.warnings);

        JSONObject overrides = validation.overrides;
        String drivesOverride = null;
        if (mountPath != null) {
            String baseDrives = overrides.has("drives") ? overrides.optString("drives") : container.getDrives();
            String existingLetter = LaunchPathResolver.findDriveLetter(baseDrives, mountPath);

            if (requestedLetter != null) {
                String pathForLetter = LaunchPathResolver.getDrivePath(baseDrives, requestedLetter);
                if (pathForLetter != null && !pathForLetter.equals(mountPath)) {
                    AppUtils.showToast(this, getString(R.string.external_launch_mount_conflict));
                    finish();
                    return;
                }
                mountLetter = requestedLetter;
            }
            else if (existingLetter != null) mountLetter = existingLetter;
            else mountLetter = LaunchPathResolver.allocateDriveLetter(baseDrives);

            if (mountLetter == null) {
                AppUtils.showToast(this, R.string.external_launch_no_free_drive);
                finish();
                return;
            }

            if (existingLetter == null || (requestedLetter != null && !requestedLetter.equals(existingLetter))) {
                drivesOverride = LaunchPathResolver.appendDrive(baseDrives, mountLetter, mountPath);
            }
        }
        if (drivesOverride != null) {
            try {
                overrides.put("drives", drivesOverride);
            }
            catch (JSONException e) {}
        }
        String execArgs = mergeExecArgs(container, request, overrides);
        if (!execArgs.isEmpty()) {
            try {
                overrides.put("execArgs", execArgs);
            }
            catch (JSONException e) {}
        }

        Log.i(TAG, "External launch by "+(getCallerPackage() != null ? getCallerPackage() : "unknown")+
                   " container="+container.id+" exe="+(execUnixPath != null ? execUnixPath : request.shortcutPath)+
                   " overrides="+overrides);

        final String launchExecUnixPath = execUnixPath;
        Runnable launchAction = () -> {
            if (request.save) persistOverrides(container, overrides);
            startSession(container, request, launchExecUnixPath, overrides);
        };

        boolean userWantsConfirm = preferences.getBoolean(PREF_EXTERNAL_LAUNCH_CONFIRM, true);
        if (request.save || Boolean.TRUE.equals(request.confirm) || userWantsConfirm) {
            ContentDialog dialog = new ContentDialog(this);
            dialog.setCancelable(false);
            dialog.setTitle(R.string.external_launch_confirm_title);
            dialog.setMessage(buildConfirmMessage(container, request, execUnixPath, overrides, mountLetter, mountPath), R.drawable.content_dialog_type_confirm);
            dialog.setOnConfirmCallback(launchAction);
            dialog.setOnCancelCallback(this::finish);
            dialog.show();
        }
        else launchAction.run();
    }

    private LaunchRequest parseIntent(Intent intent) {
        LaunchRequest request = new LaunchRequest();
        request.containerId = intent.getIntExtra("container_id", 0);
        request.containerName = intent.getStringExtra("container_name");
        request.shortcutPath = intent.getStringExtra("shortcut_path");
        request.exePath = intent.getStringExtra("exe_path");
        request.dirPath = intent.getStringExtra("dir_path");
        request.execArgs = intent.getStringExtra("exec_args");
        request.overrides = intent.getStringExtra("overrides");
        request.launchId = intent.getStringExtra(LaunchArgs.EXTRA_LAUNCH_ID);
        if (intent.hasExtra("confirm")) request.confirm = intent.getBooleanExtra("confirm", true);
        request.save = intent.getBooleanExtra("save", false);

        Uri data = intent.getData();
        if (data != null && "winlator".equals(data.getScheme())) {
            if (request.containerId == 0) request.containerId = parseInt(data.getQueryParameter("container"), 0);
            if (request.containerName == null) request.containerName = data.getQueryParameter("container_name");
            if (request.shortcutPath == null) request.shortcutPath = data.getQueryParameter("shortcut");
            if (request.exePath == null) request.exePath = data.getQueryParameter("exe");
            if (request.dirPath == null) request.dirPath = data.getQueryParameter("dir");
            if (request.execArgs == null) request.execArgs = data.getQueryParameter("args");
            if (request.overrides == null) request.overrides = data.getQueryParameter("overrides");
            if (request.launchId == null) request.launchId = data.getQueryParameter("launch_id");
            if (request.confirm == null && data.getQueryParameter("confirm") != null) request.confirm = parseBoolean(data.getQueryParameter("confirm"));
            if (!request.save && data.getQueryParameter("save") != null) request.save = Boolean.TRUE.equals(parseBoolean(data.getQueryParameter("save")));
        }

        return request;
    }

    private Container resolveContainer(ContainerManager containerManager, LaunchRequest request) {
        if (request.containerId > 0) return containerManager.getContainerById(request.containerId);

        if (request.containerName != null && !request.containerName.isEmpty()) {
            for (Container container : containerManager.getContainers()) {
                if (container.getName().equalsIgnoreCase(request.containerName)) return container;
            }
            return null;
        }

        if (request.shortcutPath != null) {
            Matcher matcher = CONTAINER_DIR_PATTERN.matcher(request.shortcutPath);
            if (matcher.find()) {
                Container container = containerManager.getContainerById(Integer.parseInt(matcher.group(1)));
                if (container != null) return container;
            }
        }

        return getLastUsedContainer(containerManager);
    }

    private Container getLastUsedContainer(ContainerManager containerManager) {
        File link = new File(new File(RootFS.find(this).getRootDir(), "home"), RootFS.USER);
        try {
            String target = Os.readlink(link.getAbsolutePath());
            String prefix = RootFS.USER+"-";
            if (target != null && target.startsWith(prefix)) {
                return containerManager.getContainerById(Integer.parseInt(target.substring(prefix.length())));
            }
        }
        catch (Exception e) {}
        return null;
    }

    private String mergeExecArgs(Container container, LaunchRequest request, JSONObject overrides) {
        String result = "";
        if (request.shortcutPath != null) {
            result = (new Shortcut(container, new File(request.shortcutPath))).getExtra("execArgs");
        }

        if (request.execArgs != null && !request.execArgs.trim().isEmpty()) {
            result = result.isEmpty() ? request.execArgs.trim() : result+" "+request.execArgs.trim();
        }

        String overrideArgs = overrides.optString("execArgs", "");
        if (!overrideArgs.isEmpty()) {
            result = result.isEmpty() ? overrideArgs : result+" "+overrideArgs;
        }
        return result;
    }

    private void persistOverrides(Container container, JSONObject overrides) {
        Iterator<String> keys = overrides.keys();
        while (keys.hasNext()) {
            String key = keys.next();
            if (LaunchArgs.SESSION_ONLY_KEYS.contains(key)) continue;

            String value = overrides.optString(key);
            switch (key) {
                case "screenSize": container.setScreenSize(value); break;
                case "screenOrientation": container.setScreenOrientation(value); break;
                case "swapResolution": container.setSwapResolution(value.equals("true")); break;
                case "graphicsDriver": container.setGraphicsDriver(value); break;
                case "graphicsDriverConfig": container.setGraphicsDriverConfig(value); break;
                case "dxwrapper": container.setDXWrapper(value); break;
                case "dxwrapperConfig": container.setDXWrapperConfig(value); break;
                case "audioDriver": container.setAudioDriver(value); break;
                case "audioDriverConfig": container.setAudioDriverConfig(value); break;
                case "wincomponents": container.setWinComponents(value); break;
                case "envVars": container.setEnvVars(value); break;
                case "box64Version": container.setBox64Version(value); break;
                case "box64Preset": container.setBox64Preset(value); break;
                case "drives": container.setDrives(value); break;
                case "controlsProfile": container.putExtra("controlsProfile", value); break;
                case "dinputMapperType": container.putExtra("dinputMapperType", value); break;
            }
        }
        container.saveData();
        AppUtils.showToast(this, R.string.external_launch_container_saved);
    }

    private void startSession(Container container, LaunchRequest request, String execUnixPath, JSONObject overrides) {
        Intent intent = new Intent(this, XServerDisplayActivity.class);
        intent.putExtra("container_id", container.id);
        if (execUnixPath != null) intent.putExtra("exec_path", execUnixPath);
        if (request.shortcutPath != null) intent.putExtra("shortcut_path", request.shortcutPath);
        intent.putExtra(LaunchArgs.EXTRA_LAUNCH_OVERRIDES, overrides.toString());
        intent.putExtra(LaunchArgs.EXTRA_EXTERNAL_LAUNCH, true);
        if (request.launchId != null) intent.putExtra(LaunchArgs.EXTRA_LAUNCH_ID, request.launchId);

        startActivity(intent);
        overridePendingTransition(0, 0);
        finish();
    }

    private String buildConfirmMessage(Container container, LaunchRequest request, String execUnixPath, JSONObject overrides, String mountLetter, String mountPath) {
        String application = execUnixPath != null ? execUnixPath : request.shortcutPath;
        String overrideText = getString(R.string.external_launch_none);
        if (overrides.length() > 0) {
            StringBuilder builder = new StringBuilder();
            Iterator<String> keys = overrides.keys();
            while (keys.hasNext()) {
                if (builder.length() > 0) builder.append(", ");
                String key = keys.next();
                String value = overrides.optString(key);
                if (value.length() > 64) value = value.substring(0, 61)+"...";
                builder.append(key).append("=").append(value);
            }
            overrideText = builder.toString();
        }

        StringBuilder message = new StringBuilder(getString(R.string.external_launch_confirm_message,
            container.getName(), application, overrideText));

        if (mountPath != null) message.append("\n").append(getString(R.string.external_launch_mount, mountLetter+": "+mountPath));
        String callerPackage = getCallerPackage();
        if (callerPackage != null) message.append("\n").append(getString(R.string.external_launch_source, callerPackage));
        if (request.save) message.append("\n").append(getString(R.string.external_launch_save_warning));
        return message.toString();
    }

    private String getPathErrorMessage(int error, String input) {
        switch (error) {
            case LaunchPathResolver.ERROR_NOT_FOUND:
                return getString(R.string.external_launch_file_not_found, input);
            case LaunchPathResolver.ERROR_UNMAPPED:
                return getString(R.string.external_launch_unmapped_path, input);
            case LaunchPathResolver.ERROR_EMPTY:
                return getString(R.string.external_launch_no_executable);
            default:
                return getString(R.string.external_launch_invalid_path);
        }
    }

    private String getCallerPackage() {
        Uri referrer = getReferrer();
        return referrer != null ? referrer.getAuthority() : null;
    }

    private static boolean hasText(String value) {
        return value != null && !value.trim().isEmpty();
    }

    private static int parseInt(String value, int fallback) {
        try {
            return value != null ? Integer.parseInt(value) : fallback;
        }
        catch (NumberFormatException e) {
            return fallback;
        }
    }

    private static Boolean parseBoolean(String value) {
        if (value == null) return null;
        if (value.equals("1") || value.equals("true")) return Boolean.TRUE;
        if (value.equals("0") || value.equals("false")) return Boolean.FALSE;
        return null;
    }
}
