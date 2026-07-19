package com.nkanaev.comics;

import android.app.Application;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.res.Configuration;
import androidx.appcompat.app.AppCompatDelegate;
import androidx.multidex.MultiDexApplication;


public class MainApplication extends MultiDexApplication {
    private static Application instance;

    @Override
    public void onCreate() {
        super.onCreate();
        instance = this;

        // apply them from settings
        int default_mode = AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM;
        int mode = getPreferences().getInt(Constants.SETTINGS_THEME, default_mode);
        // make extra sure we got a legal mode value
        switch (mode) {
            case AppCompatDelegate.MODE_NIGHT_NO:
            case AppCompatDelegate.MODE_NIGHT_YES:
                break;
            default:
                mode = default_mode;
        }
        AppCompatDelegate.setDefaultNightMode(mode);

        try {
            com.cupcakecomics.notifications.CupcakeNotifications.ensureChannels(this);
            com.cupcakecomics.pulllist.PullListWorker.schedule(this);
            com.cupcakecomics.reminders.ReminderScheduler.schedule(this);
            com.cupcakecomics.feedback.FeedbackOverlay.install(this);
        } catch (Throwable ignored) {
        }
    }

    public static Context getAppContext() {
        return instance.getApplicationContext();
    }

    public static SharedPreferences getPreferences() {
        return instance.getSharedPreferences(Constants.SETTINGS_NAME, 0);
    }

    public static boolean isNightModeEnabled(){
        return ( getAppContext().getResources().getConfiguration().uiMode &
                Configuration.UI_MODE_NIGHT_MASK ) == Configuration.UI_MODE_NIGHT_YES;
    }
}
