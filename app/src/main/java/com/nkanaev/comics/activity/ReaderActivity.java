package com.nkanaev.comics.activity;

import android.content.Intent;
import android.os.Bundle;
import android.os.StrictMode;
import android.view.MenuItem;
import android.view.View;
import android.widget.TextView;
import androidx.appcompat.app.ActionBar;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;
import androidx.fragment.app.Fragment;
import com.cupcakecomics.reader.CupcakeReaderFragment;
import com.cupcakecomics.reader.settings.ReaderSettingsStore;
import com.nkanaev.comics.BuildConfig;
import com.nkanaev.comics.R;
import com.nkanaev.comics.fragment.ReaderFragment;

import java.io.File;


public class ReaderActivity extends AppCompatActivity {

    @Override
    public void onCreate(Bundle savedInstanceState) {
        if (false && BuildConfig.DEBUG) {
            StrictMode.setThreadPolicy(
                    new StrictMode.ThreadPolicy.Builder()
                            .detectAll()
                            .penaltyFlashScreen()
                            .penaltyLog()
                            .build()
            );
            StrictMode.setVmPolicy(
                    new StrictMode.VmPolicy.Builder()
                            .detectLeakedSqlLiteObjects()
                            .detectLeakedClosableObjects()
                            .penaltyLog()
                            .penaltyDeath()
                            .build()
            );
        }
        super.onCreate(savedInstanceState);
        setContentView(R.layout.layout_reader);

        Toolbar toolbar = (Toolbar) findViewById(R.id.toolbar_reader);
        setSupportActionBar(toolbar);

        if (savedInstanceState == null) {
            boolean useGpu = shouldUseGpuReader();
            if (Intent.ACTION_VIEW.equals(getIntent().getAction())) {
                if (useGpu) {
                    setFragment(CupcakeReaderFragment.createIntent(getIntent()));
                } else {
                    setFragment(ReaderFragment.create(getIntent()));
                }
            } else {
                Bundle extras = getIntent().getExtras();
                ReaderFragment.Mode mode = (ReaderFragment.Mode) extras.getSerializable(ReaderFragment.PARAM_MODE);
                String identity = extras.getString(ReaderFragment.PARAM_IDENTITY_KEY);

                if (useGpu) {
                    Fragment fragment;
                    if (mode == ReaderFragment.Mode.MODE_LIBRARY) {
                        fragment = CupcakeReaderFragment.createLibrary(
                                extras.getInt(ReaderFragment.PARAM_HANDLER), identity);
                    } else {
                        fragment = CupcakeReaderFragment.createFile(
                                (File) extras.getSerializable(ReaderFragment.PARAM_HANDLER), identity);
                    }
                    // Propagate SMB streaming hints when present
                    if (extras.containsKey(CupcakeReaderFragment.PARAM_SMB_SHARE_ID)) {
                        fragment.getArguments().putLong(
                                CupcakeReaderFragment.PARAM_SMB_SHARE_ID,
                                extras.getLong(CupcakeReaderFragment.PARAM_SMB_SHARE_ID));
                        fragment.getArguments().putString(
                                CupcakeReaderFragment.PARAM_SMB_RELATIVE_PATH,
                                extras.getString(CupcakeReaderFragment.PARAM_SMB_RELATIVE_PATH));
                    }
                    if (extras.containsKey(ReaderFragment.PARAM_PAGE)) {
                        fragment.getArguments().putInt(
                                ReaderFragment.PARAM_PAGE,
                                extras.getInt(ReaderFragment.PARAM_PAGE));
                    }
                    setFragment(fragment);
                } else {
                    // Legacy reader cannot stream SMB; require a real local file.
                    if (extras.containsKey(CupcakeReaderFragment.PARAM_SMB_SHARE_ID)
                            && mode != ReaderFragment.Mode.MODE_LIBRARY) {
                        File smbHandler = (File) extras.getSerializable(ReaderFragment.PARAM_HANDLER);
                        if (smbHandler == null || !smbHandler.isFile()) {
                            finish();
                            return;
                        }
                    }
                    ReaderFragment fragment;
                    if (mode == ReaderFragment.Mode.MODE_LIBRARY)
                        fragment = ReaderFragment.create(extras.getInt(ReaderFragment.PARAM_HANDLER));
                    else
                        fragment = ReaderFragment.create((File) extras.getSerializable(ReaderFragment.PARAM_HANDLER));
                    if (identity != null) {
                        fragment.getArguments().putString(ReaderFragment.PARAM_IDENTITY_KEY, identity);
                    }
                    setFragment(fragment);
                }
            }
        }

        ActionBar actionBar = getSupportActionBar();
        if (actionBar != null) {
            actionBar.setDisplayHomeAsUpEnabled(true);
            actionBar.setDisplayShowCustomEnabled(true);
            actionBar.setCustomView(R.layout.action_bar_title_layout);
            actionBar.setTitle("");
        }
    }

    private boolean shouldUseGpuReader() {
        Intent intent = getIntent();
        if (intent != null && intent.hasExtra(CupcakeReaderFragment.PARAM_USE_GPU_READER)) {
            return intent.getBooleanExtra(CupcakeReaderFragment.PARAM_USE_GPU_READER, true);
        }
        try {
            return new ReaderSettingsStore(this).loadDefaults().getUseGpuRenderer();
        } catch (Throwable t) {
            return true;
        }
    }

    @Override
    public void setTitle(CharSequence title) {
        super.setTitle(title);
        TextView titleView = findViewById(R.id.action_bar_title);
        if (titleView!=null)
            titleView.setText(title);
        else
            getSupportActionBar().setTitle(title);
    }

    public void setSubTitle(CharSequence title) {
        TextView subtitle = (TextView) findViewById(R.id.action_bar_subtitle);
        if (subtitle==null)
            return;

        if (title==null||title.toString().isEmpty()) {
            subtitle.setVisibility(View.GONE);
            title="";
        } else {
            subtitle.setVisibility(View.VISIBLE);
        }
        subtitle.setText(title);
    }

    public void setFragment(Fragment fragment) {
        getSupportFragmentManager()
                .beginTransaction()
                .replace(R.id.content_frame_reader, fragment)
                .commit();

    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        switch (item.getItemId()) {
            case android.R.id.home:
                finish();
                return true;
        }
        return super.onOptionsItemSelected(item);
    }

    @Override
    public void onBackPressed() {
        super.onBackPressed();
        finish();
    }

}
