package com.nkanaev.comics.activity;

import android.content.Intent;
import android.os.Bundle;
import android.view.MenuItem;
import android.view.View;
import android.widget.TextView;
import androidx.appcompat.app.ActionBar;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;
import androidx.fragment.app.Fragment;
import com.cupcakecomics.reader.CupcakeReaderFragment;
import com.nkanaev.comics.R;


/**
 * Thin host for the reader. All open paths are built by ReaderLauncher and
 * rendered by CupcakeReaderFragment; this activity only forwards its extras.
 */
public class ReaderActivity extends AppCompatActivity {

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.layout_reader);

        Toolbar toolbar = (Toolbar) findViewById(R.id.toolbar_reader);
        setSupportActionBar(toolbar);

        if (savedInstanceState == null) {
            CupcakeReaderFragment fragment;
            if (Intent.ACTION_VIEW.equals(getIntent().getAction())) {
                fragment = CupcakeReaderFragment.createIntent(getIntent());
            } else {
                fragment = new CupcakeReaderFragment();
                Bundle extras = getIntent().getExtras();
                if (extras != null) {
                    fragment.setArguments(new Bundle(extras));
                }
            }
            setFragment(fragment);
        }

        ActionBar actionBar = getSupportActionBar();
        if (actionBar != null) {
            actionBar.setDisplayHomeAsUpEnabled(true);
            actionBar.setDisplayShowCustomEnabled(true);
            actionBar.setCustomView(R.layout.action_bar_title_layout);
            actionBar.setTitle("");
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
        } else {
            subtitle.setVisibility(View.VISIBLE);
            subtitle.setText(title);
        }
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
