package com.nkanaev.comics.fragment;

import android.content.Intent;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.*;
import androidx.fragment.app.Fragment;
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout;
import com.nkanaev.comics.R;
import com.nkanaev.comics.activity.MainActivity;
import com.nkanaev.comics.activity.ReaderActivity;
import com.nkanaev.comics.managers.IgnoreCaseComparator;
import com.nkanaev.comics.managers.Utils;
import com.nkanaev.comics.parsers.Parser;
import com.nkanaev.comics.parsers.ParserFactory;

import java.io.File;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;


public class BrowserFragment extends Fragment
        implements AdapterView.OnItemClickListener, AdapterView.OnItemLongClickListener, SwipeRefreshLayout.OnRefreshListener {
    private final static String STATE_CURRENT_DIR = "stateCurrentDir";

    private ListView mListView;
    private File mCurrentDir;
    private final File mRootDir = new File("/");
    private File[] mSubdirs = new File[]{};
    private SwipeRefreshLayout mRefreshLayout;
    private final ExecutorService mIoExecutor = Executors.newSingleThreadExecutor();
    private final Handler mMainHandler = new Handler(Looper.getMainLooper());
    private int mListGeneration = 0;

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        if (savedInstanceState != null) {
            mCurrentDir = (File) savedInstanceState.getSerializable(STATE_CURRENT_DIR);
        } else {
            mCurrentDir = Environment.getExternalStorageDirectory();
        }

        getActivity().setTitle(R.string.menu_browser);
    }

    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
        final View view = inflater.inflate(R.layout.fragment_browser, container, false);

        mListView = (ListView) view.findViewById(R.id.listview_browser);
        mListView.setAdapter(new DirectoryAdapter());
        mListView.setOnItemClickListener(this);
        mListView.setOnItemLongClickListener(this);

        mRefreshLayout = (SwipeRefreshLayout) view.findViewById(R.id.SwipeRefreshLayout);
        if (mRefreshLayout!=null) {
            mRefreshLayout.setColorSchemeResources(R.color.primary);
            mRefreshLayout.setOnRefreshListener(this);
            mRefreshLayout.setEnabled(true);
        }

        // List after the ListView exists so the first paint is not blocked on I/O.
        setCurrentDirectory(mCurrentDir);

        return view;
    }

    @Override
    public void onSaveInstanceState(Bundle outState) {
        outState.putSerializable(STATE_CURRENT_DIR, mCurrentDir);
        super.onSaveInstanceState(outState);
    }

    @Override
    public void onDestroyView() {
        mListGeneration++;
        super.onDestroyView();
    }

    @Override
    public void onDestroy() {
        mIoExecutor.shutdownNow();
        super.onDestroy();
    }

    private void setCurrentDirectory(File dir) {
        mCurrentDir = dir;
        if (getActivity() instanceof MainActivity) {
            ((MainActivity) getActivity()).setSubTitle(dir.getAbsolutePath());
        }
        final int generation = ++mListGeneration;
        final File listingDir = dir;
        if (mRefreshLayout != null) {
            mRefreshLayout.setRefreshing(true);
        }
        mIoExecutor.execute(() -> {
            ArrayList<File> subDirs = new ArrayList<>();

            File[] files = listingDir.listFiles();
            if (files != null) {
                for (File f : files) {
                    if (f.isDirectory() || Utils.isArchive(f.getName())) {
                        subDirs.add(f);
                    }
                }
            }

            File[] validFolders = Utils.listExternalStorageDirs();
            if (Utils.isOreoOrLater()) {
                Path parent = listingDir.toPath();
                for (File validPath : validFolders) {
                    if (!validPath.toPath().startsWith(parent))
                        continue;

                    Path relPath = parent.relativize(validPath.toPath());
                    if (relPath.getNameCount() < 1)
                        continue;

                    File entry = new File(listingDir, relPath.getName(0).toString());
                    if (!subDirs.contains(entry))
                        subDirs.add(entry);
                }
            }

            Collections.sort(subDirs, new IgnoreCaseComparator() {
                @Override
                public String stringValue(Object o) {
                    return ((File) o).getName();
                }
            });

            if (!listingDir.getAbsolutePath().equals(mRootDir.getAbsolutePath())) {
                File parentFile = listingDir.getParentFile();
                if (parentFile != null) {
                    subDirs.add(0, parentFile);
                }
            }

            final File[] next = subDirs.toArray(new File[0]);
            mMainHandler.post(() -> {
                if (generation != mListGeneration || !isAdded()) {
                    return;
                }
                mSubdirs = next;
                if (mListView != null) {
                    mListView.invalidateViews();
                }
                if (mRefreshLayout != null) {
                    mRefreshLayout.setRefreshing(false);
                }
            });
        });
    }

    @Override
    public void onItemClick(AdapterView<?> parent, View view, int position, long id) {
        File file = mSubdirs[position];

        if (file.isDirectory()) {
            setCurrentDirectory(file);
            return;
        }

        Intent intent = new Intent(getActivity(), ReaderActivity.class);
        intent.putExtra(ReaderFragment.PARAM_HANDLER, file);
        intent.putExtra(ReaderFragment.PARAM_MODE, ReaderFragment.Mode.MODE_BROWSER);
        startActivity(intent);
    }

    @Override
    public boolean onItemLongClick(AdapterView<?> parent, View view, int position, long id) {
        File file = mSubdirs[position];

        if (!file.isDirectory())
            return true;

        try {
            Parser p = ParserFactory.create(file);
            if (p.numPages() < 1)
                return true;
        } catch (Exception e) {
            Log.e("BrowserFragment","onItemLongClick",e);
            return true;
        }

        Intent intent = new Intent(getActivity(), ReaderActivity.class);
        intent.putExtra(ReaderFragment.PARAM_HANDLER, file);
        intent.putExtra(ReaderFragment.PARAM_MODE, ReaderFragment.Mode.MODE_BROWSER);
        startActivity(intent);
        return true;
    }

    private void setIcon(int position, View convertView, File file) {
        ImageView view = (ImageView) convertView.findViewById(R.id.directory_row_icon);
        ImageView circle = (ImageView) convertView.findViewById(R.id.directory_row_circle);
        GradientDrawable circleDrawable = (GradientDrawable) circle.getDrawable();

        view.setImageResource(R.drawable.ic_folder_24);
        int colorRes = R.color.circle_grey;
        circleDrawable.setColor(getResources().getColor(colorRes));
        circle.setVisibility(View.VISIBLE);

        if (position == 0 && !mCurrentDir.getAbsolutePath().equals(mRootDir.getAbsolutePath()))
            return;

        if (file.isDirectory()) {
            circleDrawable.setColor(getResources().getColor(colorRes));
            return;
        }

        view.setImageResource(R.drawable.ic_article_24);
        String name = file.getName();
        if (!Utils.isArchive(name))
            return;

        view.setImageResource(R.drawable.ic_text_image_document_24);
        if (Utils.isPdf(name)) {
            colorRes = R.color.circle_blue;
        } else if (Utils.isZip(name)) {
            colorRes = R.color.circle_green;
        } else if (Utils.isRar(name)) {
            colorRes = R.color.circle_red;
        } else if (Utils.isSevenZ(name)) {
            colorRes = R.color.circle_yellow;
        } else if (Utils.isTarball(name)){
            colorRes = R.color.circle_orange;
        }

        circleDrawable.setColor(getResources().getColor(colorRes));
    }

    @Override
    public void onRefresh() {
        if (mCurrentDir!=null)
            setCurrentDirectory(mCurrentDir);
        else if (mRefreshLayout != null)
            mRefreshLayout.setRefreshing(false);
    }

    private final class DirectoryAdapter extends BaseAdapter {
        @Override
        public int getCount() {
            return mSubdirs.length;
        }

        @Override
        public long getItemId(int position) {
            return 0;
        }

        @Override
        public Object getItem(int position) {
            return mSubdirs[position];
        }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            if (convertView == null) {
                convertView = getActivity().getLayoutInflater().inflate(R.layout.row_directory, parent, false);
            }

            File file = mSubdirs[position];
            TextView textView = (TextView) convertView.findViewById(R.id.directory_row_text);

            if (position == 0 && !mCurrentDir.getAbsolutePath().equals(mRootDir.getAbsolutePath())) {
                textView.setText("..");
            } else {
                textView.setText(file.getName());
            }

            setIcon(position, convertView, file);

            return convertView;
        }
    }
}
