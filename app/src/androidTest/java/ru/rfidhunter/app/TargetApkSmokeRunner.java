package ru.rfidhunter.app;

import android.Manifest;
import android.app.Activity;
import android.app.Instrumentation;
import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.res.AssetManager;
import android.os.Build;
import android.os.Bundle;
import android.util.Log;

import dalvik.system.PathClassLoader;

import org.json.JSONObject;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

// Platform-only runner: no test references to the target's R8-optimized Kotlin runtime.
public final class TargetApkSmokeRunner extends Instrumentation {
    @Override
    public void onCreate(Bundle arguments) {
        super.onCreate(arguments);
        start();
    }

    @Override
    public void onStart() {
        Bundle results = new Bundle();
        boolean success = true;
        try {
            checkVosk();
            results.putString("vosk", "PASS");
        } catch (Throwable error) {
            success = false;
            results.putString("vosk", "FAIL\n" + Log.getStackTraceString(error));
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            try {
                checkBluetoothManifest();
                results.putString("bluetooth", "PASS");
            } catch (Throwable error) {
                success = false;
                results.putString("bluetooth", "FAIL\n" + Log.getStackTraceString(error));
            }
        } else {
            results.putString("bluetooth", "SKIP: requires Android 12+");
        }
        results.putString("status", success ? "PASS" : "FAIL");
        results.putString("stream", "\nVosk: " + results.getString("vosk")
                + "\nBluetooth: " + results.getString("bluetooth") + "\n");
        finish(success ? Activity.RESULT_OK : Activity.RESULT_CANCELED, results);
    }

    private void checkVosk() throws Exception {
        Context target = getTargetContext();
        ApplicationInfo info = target.getApplicationInfo();
        List<String> apkPaths = new ArrayList<>();
        apkPaths.add(info.sourceDir);
        if (info.splitSourceDirs != null) apkPaths.addAll(Arrays.asList(info.splitSourceDirs));
        StringBuilder nativePaths = new StringBuilder(info.nativeLibraryDir);
        for (String apk : apkPaths) {
            nativePaths.append(File.pathSeparator).append(apk).append("!/lib/arm64-v8a");
        }
        // Exclude instrumentation dex and its dependencies, even if Android merges the default loaders.
        ClassLoader targetLoader = new PathClassLoader(
                String.join(File.pathSeparator, apkPaths), nativePaths.toString(),
                ClassLoader.getSystemClassLoader().getParent());
        Class<?> nativeClass = Class.forName("com.sun.jna.Native", true, targetLoader);
        Class<?> modelClass = Class.forName("org.vosk.Model", true, targetLoader);
        Class<?> recognizerClass = Class.forName("org.vosk.Recognizer", true, targetLoader);
        require(nativeClass.getClassLoader() == targetLoader
                && modelClass.getClassLoader() == targetLoader
                && recognizerClass.getClassLoader() == targetLoader, "Classes must come from target APKs");

        File temporary = Files.createTempDirectory(target.getCacheDir().toPath(), "vosk-smoke-").toFile();
        try {
            AssetManager assets = target.getAssets();
            String[] entries = assets.list("vosk-model");
            require(entries != null && entries.length > 0,
                    "Target APK must bundle assets/vosk-model; configure voskModelDir before building");
            File modelDirectory = new File(temporary, "vosk-model");
            copyAssetTree(assets, "vosk-model", modelDirectory);
            try (AutoCloseable model = (AutoCloseable) modelClass.getConstructor(String.class)
                    .newInstance(modelDirectory.getAbsolutePath());
                 AutoCloseable recognizer = (AutoCloseable) recognizerClass.getConstructor(modelClass, float.class)
                    .newInstance(model, 16_000f)) {
                byte[] silence = new byte[16_000 * 2]; // One second of 16-bit mono silence.
                recognizerClass.getMethod("acceptWaveForm", byte[].class, int.class)
                        .invoke(recognizer, silence, silence.length);
                JSONObject result = new JSONObject((String) recognizerClass.getMethod("getFinalResult")
                        .invoke(recognizer));
                require(result.get("text") instanceof String, "Final result must contain a text string: " + result);
            }
        } finally {
            deleteTree(temporary);
        }
    }

    @SuppressWarnings("deprecation")
    private void checkBluetoothManifest() throws Exception {
        Context target = getTargetContext();
        PackageInfo info = target.getPackageManager().getPackageInfo(
                target.getPackageName(), PackageManager.GET_PERMISSIONS);
        require(info.requestedPermissions != null, "Target must declare permissions");
        List<String> permissions = Arrays.asList(info.requestedPermissions);
        int scanIndex = permissions.indexOf(Manifest.permission.BLUETOOTH_SCAN);
        require(scanIndex >= 0, "Target must declare BLUETOOTH_SCAN");
        require(info.requestedPermissionsFlags != null && scanIndex < info.requestedPermissionsFlags.length
                && (info.requestedPermissionsFlags[scanIndex]
                & PackageInfo.REQUESTED_PERMISSION_NEVER_FOR_LOCATION) != 0,
                "BLUETOOTH_SCAN must declare neverForLocation");
        require(permissions.contains(Manifest.permission.BLUETOOTH_CONNECT), "Target must declare BLUETOOTH_CONNECT");
    }

    private static void copyAssetTree(AssetManager assets, String assetPath, File destination) throws IOException {
        String[] children = assets.list(assetPath);
        if (children != null && children.length > 0) {
            require(destination.mkdirs(), "Cannot create " + destination);
            for (String child : children) {
                copyAssetTree(assets, assetPath + "/" + child, new File(destination, child));
            }
        } else {
            try (InputStream input = assets.open(assetPath)) {
                Files.copy(input, destination.toPath());
            }
        }
    }

    private static void deleteTree(File file) throws IOException {
        File[] children = file.listFiles();
        if (children != null) {
            for (File child : children) deleteTree(child);
        }
        if (!file.delete()) throw new IOException("Cannot delete temporary model path: " + file);
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
