/*
 * Copyright (C) 2012 Google Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy of
 * the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 */

package com.android.server.policy;

import android.accessibilityservice.AccessibilityServiceInfo;
import android.app.ActivityManager;
import android.app.AlertDialog;
import android.content.ComponentName;
import android.content.ContentResolver;
import android.content.Context;
import android.content.DialogInterface;
import android.database.ContentObserver;
import android.media.AudioAttributes;
import android.media.Ringtone;
import android.media.RingtoneManager;
import android.os.Handler;
import android.os.UserHandle;
import android.os.Vibrator;
import android.provider.Settings;
import android.text.TextUtils;
import android.util.Slog;
import android.view.Window;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityManager;

import android.widget.Toast;
import com.android.internal.R;

import java.util.List;

import static android.view.WindowManager.LayoutParams.TYPE_KEYGUARD_DIALOG;

/**
 * Class to help manage the accessibility shortcut
 */
public class AccessibilityShortcutController {
    private static final String TAG = "AccessibilityShortcutController";
    private static final AudioAttributes VIBRATION_ATTRIBUTES = new AudioAttributes.Builder()
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .setUsage(AudioAttributes.USAGE_ASSISTANCE_ACCESSIBILITY)
            .build();


    private final Context mContext;
    private AlertDialog mAlertDialog;
    private boolean mIsShortcutEnabled;
    // Visible for testing
    public FrameworkObjectProvider mFrameworkObjectProvider = new FrameworkObjectProvider();

    public static String getTargetServiceComponentNameString(
            Context context, int userId) {
        final String currentShortcutServiceId = Settings.Secure.getStringForUser(
                context.getContentResolver(), Settings.Secure.ACCESSIBILITY_SHORTCUT_TARGET_SERVICE,
                userId);
        if (currentShortcutServiceId != null) {
            return currentShortcutServiceId;
        }
        return context.getString(R.string.config_defaultAccessibilityService);
    }

    public AccessibilityShortcutController(Context context, Handler handler) {
        mContext = context;

        // Keep track of state of shortcut
        mContext.getContentResolver().registerContentObserver(
                Settings.Secure.getUriFor(Settings.Secure.ACCESSIBILITY_SHORTCUT_TARGET_SERVICE),
                false,
                new ContentObserver(handler) {
                    @Override
                    public void onChange(boolean selfChange) {
                        onSettingsChanged();
                    }
                },
                UserHandle.USER_ALL);
        updateShortcutEnabled();
    }

    public boolean isAccessibilityShortcutAvailable() {
        return mIsShortcutEnabled;
    }

    public void onSettingsChanged() {
        updateShortcutEnabled();
    }

    /**
     * Called when the accessibility shortcut is activated
     */
    public void performAccessibilityShortcut() {
        Slog.d(TAG, "Accessibility shortcut activated");
        final ContentResolver cr = mContext.getContentResolver();
        final int userId = ActivityManager.getCurrentUser();
        final int dialogAlreadyShown = Settings.Secure.getIntForUser(
                cr, Settings.Secure.ACCESSIBILITY_SHORTCUT_DIALOG_SHOWN, 0, userId);

        // Play a notification tone
        final Ringtone tone =
                RingtoneManager.getRingtone(mContext, Settings.System.DEFAULT_NOTIFICATION_URI);
        if (tone != null) {
            tone.setAudioAttributes(new AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_NOTIFICATION_EVENT)
                .build());
            tone.play();
        }

        // Play a notification vibration
        Vibrator vibrator = (Vibrator) mContext.getSystemService(Context.VIBRATOR_SERVICE);
        if ((vibrator != null) && vibrator.hasVibrator()) {
            // Don't check if haptics are disabled, as we need to alert the user that their
            // way of interacting with the phone may change if they activate the shortcut
            long[] vibePattern = PhoneWindowManager.getLongIntArray(mContext.getResources(),
                    R.array.config_safeModeDisabledVibePattern);
            vibrator.vibrate(vibePattern, -1, VIBRATION_ATTRIBUTES);
        }


        if (dialogAlreadyShown == 0) {
            // The first time, we show a warning rather than toggle the service to give the user a
            // chance to turn off this feature before stuff gets enabled.
            mAlertDialog = createShortcutWarningDialog(userId);
            if (mAlertDialog == null) {
                return;
            }
            Window w = mAlertDialog.getWindow();
            WindowManager.LayoutParams attr = w.getAttributes();
            attr.type = TYPE_KEYGUARD_DIALOG;
            w.setAttributes(attr);
            mAlertDialog.show();
            Settings.Secure.putIntForUser(
                    cr, Settings.Secure.ACCESSIBILITY_SHORTCUT_DIALOG_SHOWN, 1, userId);
        } else {
            if (mAlertDialog != null) {
                mAlertDialog.dismiss();
                mAlertDialog = null;
            }

            // Show a toast alerting the user to what's happening
            final AccessibilityServiceInfo serviceInfo = getInfoForTargetService();
            if (serviceInfo == null) {
                Slog.e(TAG, "Accessibility shortcut set to invalid service");
                return;
            }
            String toastMessageFormatString = mContext.getString(isServiceEnabled(serviceInfo)
                    ? R.string.accessibility_shortcut_disabling_service
                    : R.string.accessibility_shortcut_enabling_service);
            String toastMessage = String.format(toastMessageFormatString,
                    serviceInfo.getResolveInfo()
                            .loadLabel(mContext.getPackageManager()).toString());
            Toast warningToast = mFrameworkObjectProvider.makeToastFromText(
                    mContext, toastMessage, Toast.LENGTH_LONG);
            warningToast.getWindowParams().privateFlags |=
                    WindowManager.LayoutParams.PRIVATE_FLAG_SHOW_FOR_ALL_USERS;
            warningToast.show();

            mFrameworkObjectProvider.getAccessibilityManagerInstance(mContext)
                    .performAccessibilityShortcut();
        }
    }

    private void updateShortcutEnabled() {
        mIsShortcutEnabled = !TextUtils.isEmpty(getTargetServiceComponentNameString(
                mContext, UserHandle.myUserId()));
    }

    private AlertDialog createShortcutWarningDialog(int userId) {
        final AccessibilityServiceInfo serviceInfo = getInfoForTargetService();

        if (serviceInfo == null) {
            return null;
        }

        final String warningMessage = String.format(
                mContext.getString(R.string.accessibility_shortcut_toogle_warning),
                serviceInfo.getResolveInfo().loadLabel(mContext.getPackageManager()).toString());
        final AlertDialog alertDialog = mFrameworkObjectProvider.getAlertDialogBuilder(mContext)
                .setTitle(R.string.accessibility_shortcut_warning_dialog_title)
                .setMessage(warningMessage)
                .setCancelable(false)
                .setPositiveButton(R.string.leave_accessibility_shortcut_on, null)
                .setNegativeButton(R.string.disable_accessibility_shortcut,
                        (DialogInterface d, int which) -> {
                            Settings.Secure.putStringForUser(mContext.getContentResolver(),
                                    Settings.Secure.ACCESSIBILITY_SHORTCUT_TARGET_SERVICE, "",
                                    userId);
                        })
                .setOnCancelListener((DialogInterface d) -> {
                    // If canceled, treat as if the dialog has never been shown
                    Settings.Secure.putIntForUser(mContext.getContentResolver(),
                        Settings.Secure.ACCESSIBILITY_SHORTCUT_DIALOG_SHOWN, 0, userId);
                })
                .create();
        return alertDialog;
    }

    private AccessibilityServiceInfo getInfoForTargetService() {
        final String currentShortcutServiceString = getTargetServiceComponentNameString(
                mContext, UserHandle.myUserId());
        if (currentShortcutServiceString == null) {
            return null;
        }
        AccessibilityManager accessibilityManager =
                mFrameworkObjectProvider.getAccessibilityManagerInstance(mContext);
        return accessibilityManager.getInstalledServiceInfoWithComponentName(
                        ComponentName.unflattenFromString(currentShortcutServiceString));
    }

    private boolean isServiceEnabled(AccessibilityServiceInfo serviceInfo) {
        AccessibilityManager accessibilityManager =
                mFrameworkObjectProvider.getAccessibilityManagerInstance(mContext);
        return accessibilityManager.getEnabledAccessibilityServiceList(
                AccessibilityServiceInfo.FEEDBACK_ALL_MASK).contains(serviceInfo);
    }

    // Class to allow mocking of static framework calls
    public static class FrameworkObjectProvider {
        public AccessibilityManager getAccessibilityManagerInstance(Context context) {
            return AccessibilityManager.getInstance(context);
        }

        public AlertDialog.Builder getAlertDialogBuilder(Context context) {
            return new AlertDialog.Builder(context);
        }

        public Toast makeToastFromText(Context context, CharSequence charSequence, int duration) {
            return Toast.makeText(context, charSequence, duration);
        }
    }
}
