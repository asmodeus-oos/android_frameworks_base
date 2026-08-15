/*
 * Copyright (C) 2026 Evolution X
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package android.app;

import android.os.Build;
import android.os.Process;
import android.os.RemoteException;
import android.os.UserHandle;
import android.util.Slog;

import java.lang.reflect.Field;

/**
 * Client-side companion to the sandbox {@code banking_compatibility} preset.
 *
 * <p>System-server already spoofs developer Settings and sanitizes USB sticky
 * broadcasts for targeted packages. Zygote still bakes real {@link Build}
 * constants (notably {@link Build#IS_DEBUGGABLE}) into every forked app, and
 * some payment apps also read those fields / related properties directly. When
 * the preset is enabled for a package, rewrite the obvious debug/signing
 * signals before application code runs. TrickyStore also treats the package as
 * an AUTO attestation target so Keystore keygen can use the configured keybox
 * when TEE is unprovisioned (Paytm exits otherwise).
 *
 * @hide
 */
public final class BankingCompatibilitySpoof {
    private static final String TAG = "BankingCompatSpoof";

    /** @hide */
    public static final String PRESET = "banking_compatibility";

    private static volatile boolean sEnabled;

    private BankingCompatibilitySpoof() {}

    /** @hide */
    public static boolean isEnabled() {
        return sEnabled;
    }

    /**
     * Apply Build / property spoofs when {@code packageName} has the banking
     * compatibility preset enabled.
     *
     * @hide
     */
    public static void applyForPackage(String packageName) {
        if (packageName == null || Process.isIsolated()) {
            return;
        }

        try {
            final IActivityManager am = ActivityManager.getService();
            if (am == null || !am.isSandboxSpoofSettingEnabled(packageName, PRESET, UserHandle.myUserId())) {
                return;
            }
        } catch (RemoteException e) {
            Slog.w(TAG, "Unable to query banking compatibility for " + packageName, e);
            return;
        }

        sEnabled = true;
        spoofBuildFields();
        Slog.i(TAG, "Applied banking Build spoof for " + packageName);
    }

    private static void spoofBuildFields() {
        setBuildField("TAGS", "release-keys");
        setBuildField("TYPE", "user");
        setBuildField("IS_DEBUGGABLE", false);
        setBuildField("IS_ENG", false);
        setBuildField("IS_USERDEBUG", false);
        setBuildField("IS_USER", true);
    }

    private static void setBuildField(String fieldName, Object value) {
        try {
            Field field = Build.class.getDeclaredField(fieldName);
            field.setAccessible(true);
            field.set(null, value);
            field.setAccessible(false);
        } catch (NoSuchFieldException | IllegalAccessException e) {
            Slog.w(TAG, "Failed to spoof Build." + fieldName, e);
        }
    }
}
