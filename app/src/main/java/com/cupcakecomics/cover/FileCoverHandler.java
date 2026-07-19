package com.cupcakecomics.cover;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.util.Log;
import com.nkanaev.comics.Constants;
import com.nkanaev.comics.managers.Utils;
import com.nkanaev.comics.parsers.Parser;
import com.nkanaev.comics.parsers.ParserFactory;
import com.nkanaev.comics.view.CoverImageView;
import com.squareup.picasso.Picasso;
import com.squareup.picasso.Request;
import com.squareup.picasso.RequestHandler;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;

/**
 * Picasso handler for covers keyed only by absolute file path (offline downloads).
 * Scheme: filecover:///absolute/path/to/comic.cbz
 */
public class FileCoverHandler extends RequestHandler {
    public static final String SCHEME = "filecover";

    public FileCoverHandler(@SuppressWarnings("unused") Context context) {
    }

    @Override
    public boolean canHandleRequest(Request data) {
        return SCHEME.equals(data.uri.getScheme());
    }

    @Override
    public Result load(Request data, int networkPolicy) throws IOException {
        String path = data.uri.getPath();
        if (path == null || path.isEmpty()) {
            throw new IOException("empty filecover path");
        }
        Bitmap cover = loadOrCreate(path);
        return new Result(cover, Picasso.LoadedFrom.DISK);
    }

    public static Uri uriFor(File file) {
        return new Uri.Builder()
                .scheme(SCHEME)
                .path(file.getAbsolutePath())
                .build();
    }

    public static Uri uriFor(String absolutePath) {
        return new Uri.Builder()
                .scheme(SCHEME)
                .path(absolutePath)
                .build();
    }

    /** Warm disk cache in background after a download. */
    public static void warmCache(String absolutePath) {
        try {
            loadOrCreate(absolutePath);
        } catch (Exception e) {
            Log.w("FileCoverHandler", "warm failed: " + absolutePath, e);
        }
    }

    private static Bitmap loadOrCreate(String absolutePath) throws IOException {
        File coverFile = Utils.getCoverCacheFileForPath(absolutePath);
        if (coverFile.isFile()) {
            BitmapFactory.Options options = new BitmapFactory.Options();
            if (Utils.isOreoOrLater()) {
                options.inPreferredConfig = Bitmap.Config.HARDWARE;
            }
            Bitmap bitmap = BitmapFactory.decodeFile(coverFile.getAbsolutePath(), options);
            if (bitmap != null) {
                return bitmap;
            }
        }

        File comicFile = new File(absolutePath);
        if (!comicFile.isFile()) {
            throw new IOException("missing comic file: " + absolutePath);
        }

        Parser parser = null;
        BufferedInputStream bis = null;
        FileOutputStream outputStream = null;
        try {
            parser = ParserFactory.create(absolutePath);
            if (parser == null) {
                throw new IOException("no parser for " + absolutePath);
            }
            if (parser.numPages() < 1) {
                throw new IOException("no pages in " + absolutePath);
            }

            bis = new BufferedInputStream(parser.getPage(0));
            byte[] data = Utils.toByteArray(bis);

            BitmapFactory.Options options = new BitmapFactory.Options();
            options.inJustDecodeBounds = true;
            BitmapFactory.decodeByteArray(data, 0, data.length, options);
            options.inSampleSize = Utils.calculateInSampleSize(
                    options,
                    Constants.COVER_THUMBNAIL_WIDTH,
                    Constants.COVER_THUMBNAIL_HEIGHT);
            options.inJustDecodeBounds = false;

            Bitmap result = BitmapFactory.decodeByteArray(data, 0, data.length, options);
            if (result == null) {
                throw new IOException("decode failed for " + absolutePath);
            }

            int height = result.getHeight();
            int width = result.getWidth();
            int hLimit = (int) (width * (1 / CoverImageView.FACTOR));
            int wLimit = (int) (height * (2.5 * CoverImageView.FACTOR));
            Bitmap oldResult = result;
            if (height > width && height > hLimit) {
                result = Bitmap.createBitmap(result, 0, 0, width, hLimit);
                oldResult.recycle();
            } else if (width > height && width > wLimit) {
                result = Bitmap.createBitmap(result, width - wLimit - 1, 0, wLimit, height);
                oldResult.recycle();
            }

            File folder = coverFile.getParentFile();
            if (folder != null && !folder.exists()) {
                folder.mkdirs();
            }
            synchronized (FileCoverHandler.class) {
                outputStream = new FileOutputStream(coverFile);
                if (!result.compress(Bitmap.CompressFormat.JPEG, 88, outputStream)) {
                    coverFile.delete();
                }
            }
            return result;
        } catch (Exception e) {
            Log.e("FileCoverHandler", "loadOrCreate", e);
            if (!(e instanceof IOException)) {
                e = new IOException(e);
            }
            throw (IOException) e;
        } finally {
            Utils.close(parser);
            Utils.close(bis);
            Utils.close(outputStream);
        }
    }
}
