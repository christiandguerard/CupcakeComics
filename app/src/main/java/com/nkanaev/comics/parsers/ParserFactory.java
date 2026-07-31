package com.nkanaev.comics.parsers;

import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.util.DisplayMetrics;
import android.util.Log;
import com.gemalto.jp2.JP2Decoder;
import com.nkanaev.comics.BuildConfig;
import com.nkanaev.comics.MainApplication;
import com.nkanaev.comics.managers.Utils;
import org.apache.commons.compress.archivers.ArchiveException;
import org.apache.commons.compress.archivers.ArchiveStreamFactory;
import org.apache.commons.compress.compressors.CompressorException;
import org.apache.commons.compress.compressors.CompressorStreamFactory;

import java.io.*;
import java.lang.reflect.Method;
import java.util.*;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class ParserFactory {

    public static enum Type {
        ZIP, RAR, SEVEN_Z, TAR, PDF, DIR,
        // compressed tar streams
        TAR_BROTLI, TAR_BZIP2, TAR_GZIP, TAR_LZMA, TAR_XZ, TAR_ZSTD
    };

    public static Parser create(Object o) throws Exception {
        try {
            Parser p;
            if (o instanceof String)
                p = create(new File((String) o));
            else if (o instanceof File)
                p = create((File) o);
            else if (o instanceof Intent)
                p = create((Intent) o);
            else
                throw new IllegalArgumentException("Parser.create() call with unimplemented parameter");

            if (p != null) {
                // wrap in Ignore wrapper
                p = new IgnorePageRegExWrapper(p);
                // wrap in MetaData wrapper
                p = new CachingPageMetaDataParserWrapper(p);
                // wrap in JP2 recoder
                p = new CachingDecodeJP2ParserWrapper(p);
                // wrap debug info
                if (BuildConfig.DEBUG)
                    p = new DebugInfoParserWrapper(p);
            }
            return p;
        } catch (Exception e) {
            Log.e("ParserFactory", "create", e);
        }
        return null;
    }

    private static Parser create(File file) throws Exception {
        // let's skip undesireables, keep the noise down
        if (!file.isDirectory() && !(file.isFile() && Utils.isArchive(file.getName())))
            return null;

        Class parserClass = findParser(file);
        Method canParseMethod = parserClass.getMethod("canParse", Class.class);
        AbstractParser p = (AbstractParser) parserClass.getDeclaredConstructor().newInstance();
        if ((boolean) canParseMethod.invoke(p, File.class)) {
            p.setSource(file);
            return p;
        }
        throw new UnsupportedOperationException("Parser " + parserClass.getCanonicalName() + " does not support opening File!");
    }

    private static Parser create(Intent intent) throws Exception {
        Uri uri = AbstractParser.uriFromIntent(intent);
        if (uri == null)
            throw new IllegalArgumentException("Intent without url!");

        // handle file:// uris directly
        if ("file".equalsIgnoreCase(uri.getScheme())) {
            return create(new File(uri.getPath()));
        }

        Class parserClass = findParser(intent);

        AbstractParser p = (AbstractParser) parserClass.getDeclaredConstructor().newInstance();
        if (p.canParse(File.class)) {
            AbstractParser intentParser = new IntentWithTempFileParserWrapper(p);
            intentParser.setSource(intent);
            return intentParser;
        }

        throw new UnsupportedOperationException("Parser " + parserClass.getCanonicalName() + " does not support opening File!");
    }

    private static Class<? extends AbstractParser> findParser(Intent intent) {
        Uri uri = AbstractParser.uriFromIntent(intent);
        File file = new File(uri.getLastPathSegment());
        try {
            return findParser(file);
        } catch (UnsupportedOperationException e) {
            // lets retry below
        }
        // retry mimetype if needed
        String dummyName;
        if (intent.getType()!=null && (dummyName=Utils.dummyFileNameFromMimeType(intent.getType()))!=null)
            try {
                return findParser(new File(dummyName));
            } catch (UnsupportedOperationException e){
                // throw error below
            }

        throw new UnsupportedOperationException("No parser found for file " + file +" mimeType " + intent.getType());
    }

    private static Class<? extends AbstractParser> findParser(File file) {

        InputStream is = null;
        Type type = null;

        // detect folder
        if (file.isDirectory()) {
            type = Type.DIR;
        }
        // parse file header for signature first, set type
        else {
            try {
                is = new FileInputStream(file);
                type = detectFileType(is);
            } catch (Exception e) {
                // ignore
            } finally {
                Utils.close(is);
            }
        }

        // no type so far? assume type by file extension/
        if (type == null)
            type = assumeFileType(file.getName());

        if (type == Type.DIR) {
            return DirectoryParser.class;
        } else if (type == Type.ZIP) {
            if (Utils.isOreoOrLater())
                return CommonsZipParser.class;
            // pretty much the only parser for pre-Oreo devices now
            return ZipParser.class;
        } else if (type == Type.RAR) {
            // decodes Rar5+, faster than junrar
            if (LibSevenZParser.isAvailable())
                return LibSevenZParser.class;

            if (!Utils.isOreoOrLater()) {
                throw new UnsupportedOperationException("Rar only available on Oreo (API26) or later");
            }
            return RarParser.class;
        } else if (type != null && type.toString().startsWith("TAR")) {
            // faster bz,gz,lzma lib-7z implementation, also pre-Oreo compatible
            if (LibSevenZParser.isAvailable() &&
                    // enable for pre-Oreo, ignore unsupported br,xz,zstd
                    ( (!Utils.isOreoOrLater() && !Arrays.asList(Type.TAR_BROTLI, Type.TAR_XZ, Type.TAR_ZSTD).contains(type) ) ||
                            // prefer for bz,gz,lzma because faster on Oreo+
                            Arrays.asList(Type.TAR_BZIP2, Type.TAR_GZIP, Type.TAR_LZMA).contains(type)))
                return LibSevenZParser.class;

            if (!Utils.isOreoOrLater()) {
                throw new UnsupportedOperationException("Tar only available on Oreo (API26) or later");
            }
            return TarFileParser.class;
        } else if (type == Type.SEVEN_Z) {
            // faster lib-7z implementation
            if (LibSevenZParser.isAvailable())
                return LibSevenZParser.class;

            if (!Utils.isOreoOrLater()) {
                throw new UnsupportedOperationException("7zip only available on Oreo (API26) or later");
            }
            // TODO: random access SevenZFileParser throws CRC errors
            return SevenZStreamParser.class;
        } else if (type == Type.PDF) {
            if (!Utils.isLollipopOrLater()) {
                throw new UnsupportedOperationException("Pdf only available on Lollipop (API21) or later");
            }
            return PdfRendererParser.class;
        }

        // no parser, no fun ;(
        throw new UnsupportedOperationException("No parser found for file " + file);
    }

    private static Type detectFileType(InputStream is) {
        if (!is.markSupported())
            is = new BufferedInputStream(is);

        try {
            if (Utils.isRarStream(is))
                return Type.RAR;
            if (Utils.isPdfStream(is))
                return Type.PDF;
            if (Utils.isSevenZStream(is))
                return Type.SEVEN_Z;
            if (Utils.isZipStream(is))
                return Type.ZIP;
            if (Utils.isTarStream(is))
                return Type.TAR;

            // commons compress needs java.nio
            if (Utils.isOreoOrLater())
                try {
                    String commonsType = ArchiveStreamFactory.detect(is);
                    switch (commonsType) {
                        case ArchiveStreamFactory.TAR:
                            return Type.TAR;
                        case ArchiveStreamFactory.ZIP:
                            return Type.ZIP;
                        case ArchiveStreamFactory.SEVEN_Z:
                            return Type.SEVEN_Z;
                    }
                } catch (ArchiveException e) {
                    Log.d("ParserFactory","detectFileType()",e);
                }

            if (Utils.isOreoOrLater())
                try {
                    String commonsCompress = CompressorStreamFactory.detect(is);
                    switch (commonsCompress) {
                        case CompressorStreamFactory.BZIP2:
                            return Type.TAR_BZIP2;
                        case CompressorStreamFactory.GZIP:
                            return Type.TAR_GZIP;
                        case CompressorStreamFactory.LZMA:
                            return Type.TAR_LZMA;
                        case CompressorStreamFactory.XZ:
                            return Type.TAR_XZ;
                        case CompressorStreamFactory.ZSTANDARD:
                            return Type.TAR_ZSTD;
                    }
                } catch (CompressorException e) {
                    Log.d("ParserFactory","detectFileType()",e);
                }
        } finally {
            Utils.close(is);
        }
        return null;
    }

    private static Type assumeFileType(String fileName) {
        if (fileName == null)
            return null;

        if (Utils.isZip(fileName)) {
            return Type.ZIP;
        } else if (Utils.isRar(fileName)) {
            return Type.RAR;
        } else if (Utils.isTBR(fileName)) {
            // brotli has no magic signature
            return Type.TAR_BROTLI;
        } else if (Utils.isTXZ(fileName)) {
            return Type.TAR_XZ;
        } else if (Utils.isTZST(fileName)) {
            return Type.TAR_ZSTD;
        } else if (Utils.isTarball(fileName)) {
            return Type.TAR;
        } else if (Utils.isSevenZ(fileName)) {
            return Type.SEVEN_Z;
        } else if (Utils.isPdf(fileName)) {
            return Type.PDF;
        }
        return null;
    }

    private static class IntentWithTempFileParserWrapper extends AbstractParser {
        private AbstractParser mParser;
        private File mTempDir = null;

        public IntentWithTempFileParserWrapper(AbstractParser parser) throws Exception {
            super(new Class[]{Intent.class});
            mParser = parser;
        }

        @Override
        public synchronized void parse() throws IOException {
            createTempFile();
            mParser.parse();
        }

        @Override
        public int numPages() throws IOException {
            parse();
            return mParser.numPages();
        }

        @Override
        public InputStream getPage(int num) throws IOException {
            parse();
            return mParser.getPage(num);
        }

        @Override
        public Map getPageMetaData(int num) throws IOException {
            parse();
            return mParser.getPageMetaData(num);
        }

        @Override
        public String getType() {
            return mParser.getType();
        }

        @Override
        protected Object getSource() {
            Object tempSource = mParser.getSource();
            if (tempSource != null)
                return tempSource;
            return super.getSource();
        }

        private void createTempFile() throws IOException {
            if (mParser.getSource() instanceof File)
                return;

            mTempDir = Utils.initCacheDirectory("tempfile");
            Intent intent = (Intent) getSource();
            Uri uri = uriFromIntent(intent);
            // treat last path segment as possible uri (e.g. google files app)
            String filename = Uri.decode(uri.getLastPathSegment());
            if (filename != null) {
                Uri uri2 = Uri.parse(filename);
                if (uri2 != null && uri2.getPath() != null)
                    filename = uri2.getLastPathSegment();
            } else {
                // last resort, should never happen actually
                // may probably fail because of missing file extension
                filename = "tempfile";
            }
            File tempFile = new File(mTempDir, Uri.encode(filename));
            InputStream is = MainApplication.getAppContext().getContentResolver().openInputStream(uri);
            Utils.copyToFile(is, tempFile);
            mParser.setSource(tempFile);
        }

        @Override
        public void destroy() {
            // destroy wrapped parser
            Utils.close(mParser);
            mParser = null;
            Utils.rmDir(mTempDir);
        }
    }

    private static class CachingPageMetaDataParserWrapper implements Parser {
        private Parser mParser;
        private boolean mFetchMeta = false;
        private HashMap<Integer, Map> mPagesMetaData = new HashMap();
        private ArrayList mPagesSeen = new ArrayList<Integer>();

        public CachingPageMetaDataParserWrapper(Parser parser) {
            mParser = parser;
        }

        private Integer key(int num){
            return Integer.valueOf(num);
        }

        @Override
        public void parse() throws IOException {
            mParser.parse();
        }

        @Override
        public int numPages() throws IOException {
            return mParser.numPages();
        }

        @Override
        public InputStream getPage(int num) throws IOException {
            InputStream is = null;
            if (mFetchMeta && !mPagesMetaData.containsKey(key(num)))
                is = initPageMetaData(num);
            else if (!mFetchMeta)
                mPagesSeen.add(key(num));

            return is != null ? is : mParser.getPage(num);
        }

        private synchronized InputStream initPageMetaData(int num) throws IOException {
            Integer key = key(num);
            Map pageData = new HashMap();
            CountingBufferedInputStream cbis = null;
            try {
                InputStream is = mParser.getPage(num);
                int limit = 5 * 1024 * 1024; //5MB
                cbis = new CountingBufferedInputStream(is, limit);
                cbis.mark(limit);
                final BitmapFactory.Options options = new BitmapFactory.Options();
                // read bitmap metadata w/o creating another in memory copy
                options.inJustDecodeBounds = true;
                BitmapFactory.decodeStream(cbis, null, options);

                if (options.outMimeType != null) {
                    pageData.put(Parser.PAGEMETADATA_KEY_MIME, options.outMimeType);
                    pageData.put(Parser.PAGEMETADATA_KEY_WIDTH, options.outWidth);
                    pageData.put(Parser.PAGEMETADATA_KEY_HEIGHT, options.outHeight);
                }

                Object size = mParser.getPageMetaData(num).get(Parser.PAGEMETADATA_KEY_SIZE);
                // if no size set already, read til is end to determine size
                if (size == null) {
                    int skipped;
                    long total = 0;
                    byte[] dummy = new byte[4096];
                    // just skipping doesn't work, need to read, fast anyway it seems
                    while ((skipped = cbis.read(dummy)) > 0){
                        total += skipped;
                        //Log.d(""+num,""+total);
                    }
                    pageData.put(Parser.PAGEMETADATA_KEY_SIZE, cbis.getCount());
                }
            }
            catch (Exception e) {
                // ignore, just log
                Log.e("bubble2","failed to decode/fetch metadata",e);
            }
            finally {
                // keep memory buffered inputstreams
                try {
                    cbis.reset();
                } catch (Exception ex){
                    // something went wrong (read over limit?)
                    Utils.close(cbis);
                    cbis = null;
                }
                mPagesMetaData.put(key, pageData);
                return cbis;
            }
        }

        @Override
        public Map getPageMetaData(int num) throws IOException {
            mFetchMeta = true;

            Map<String, String> in = mParser.getPageMetaData(num);
            if (in == null || in.isEmpty())
                in = new HashMap<>();
            Map<String, String> in2 = mPagesMetaData.get(key(num));
            // init if still missing (just enabled?), for already requested pages only
            if (in2 == null && mPagesSeen.contains(key(num))) {
                InputStream is = initPageMetaData(num);
                Utils.close(is);
                in2 = mPagesMetaData.get(key(num));
            }
            // nothing to merge, leave early
            if (in2 == null || in2.isEmpty())
                return in;

            // merge same key values with slash
            for (String key : in2.keySet()) {
                String value2 = String.valueOf(in2.get(key));
                if (in.containsKey(key))
                    in.put(key, String.valueOf(in.get(key)) + "/" + value2);
                else
                    in.put(key, value2);
            }
            return in;
        }

        @Override
        public String getType() {
            return mParser.getType();
        }

        @Override
        public void destroy() {
            mPagesMetaData.clear();
            // destroy wrapped parser
            Utils.close(mParser);
        }
    }

    private static class CachingDecodeJP2ParserWrapper implements Parser {
        private Parser mParser;
        private HashMap<Integer, Map> mPagesMetaData = new HashMap();
        private boolean mCachingEnabled = false;
        private CacheWriter mCacheWriter = null;

        public CachingDecodeJP2ParserWrapper(Parser parser) {
            mParser = parser;
        }

        @Override
        public void parse() throws IOException {
            mParser.parse();
        }

        @Override
        public int numPages() throws IOException {
            return mParser.numPages();
        }

        @Override
        public InputStream getPage(int num) throws IOException {
            return recodeAndCache(num);
        }

        @Override
        public Map getPageMetaData(int num) throws IOException {
            Map<String, String> in = mParser.getPageMetaData(num);
            if (in == null || in.isEmpty())
                in = new HashMap<>();
            Map<String, String> in2 = mPagesMetaData.get(Integer.valueOf(num));
            // nothing to merge, leave early
            if (in2 == null)
                return in;

            for (String key : in2.keySet()) {
                String value2 = String.valueOf(in2.get(key));
                if (in.containsKey(key))
                    in.put(key, String.valueOf(in.get(key)) + "/" + value2);
                else
                    in.put(key, value2);
            }
            return in;
        }

        private InputStream recodeAndCache(int num) throws IOException {
            // don't cache cover only (num=0) requests
            if (!mCachingEnabled && num != 0)
                mCachingEnabled = true;

            RunnerStatus status = mRunnerStatus.get();
            Log.d(getClass().getCanonicalName(), "CacheRunner:" + status + " Num:" + num);

            // consult cache, return if avail
            InputStream cachedStream = cachedPageStream(num);
            if (cachedStream != null) return cachedStream;

            InputStream stream = mParser.getPage(num);

            // any non JP2 stream will be ignored
            BufferedInputStream bis = new BufferedInputStream(stream);
            if (!Utils.isJP2Stream(bis) || !Utils.isKitKatOrLater())
                return bis;

            // initialize cache only once, serve uncached until filled
            if (mCachingEnabled &&
                    status != RunnerStatus.FINISHED &&
                    status != RunnerStatus.RUNNING) {
                mCacheWriter = new CacheWriter(num);
                new Thread(mCacheWriter).start();
            } else if (status == RunnerStatus.RUNNING && mCacheWriter != null) {
                mCacheWriter.reset(num);
            }

            InputStream result = decodeJP2(bis, num, true);

            // decode to memory
            return result;
        }

        private static synchronized Bitmap _decodeJP2(InputStream is){
            try {
                return new JP2Decoder(is).decode();
            }finally {
                Utils.close(is);
            }
        }

        // synchronized to allow only one decoding at all times
        // prevents app restarts because of memory outage
        private synchronized InputStream decodeJP2(InputStream is, int num, boolean returnStream) {
            InputStream cacheStream = cachedPageStream(num);
            if (cacheStream != null) return cacheStream;

            Bitmap source = null, bitmap = null, scaled = null;
            ByteArrayOutputStream bos = null;
            InputStream result = null;
            try {
                source = _decodeJP2(is);
                if (source == null) return null;

                Map pageData = new HashMap();
                pageData.put(Parser.PAGEMETADATA_KEY_MIME, "image/x-jp2");
                pageData.put(Parser.PAGEMETADATA_KEY_WIDTH, source.getWidth());
                pageData.put(Parser.PAGEMETADATA_KEY_HEIGHT, source.getHeight());
                mPagesMetaData.put(Integer.valueOf(num), pageData);

                // Cap decode size to the largest screen edge.
                DisplayMetrics displayMetrics = MainApplication.getAppContext().getResources().getDisplayMetrics();
                int max = Math.round(1.0f * Math.max(displayMetrics.widthPixels, displayMetrics.heightPixels));
                BitmapFactory.Options options = new BitmapFactory.Options();
                //options.inPreferredConfig = Bitmap.Config.RGB_565;
                float scale = max / (float) Math.max(source.getHeight(), source.getWidth());
                if (scale < 1) {
                    scaled = Bitmap.createScaledBitmap(source, Math.round(scale * source.getWidth()), Math.round(scale * source.getHeight()), true);
                    source.recycle();
                    bitmap = scaled;
                    //bitmap.reconfigure(Math.round(scale * bitmap.getWidth()), Math.round(scale * bitmap.getHeight()), Bitmap.Config.RGB_565);
                } else {
                    bitmap = source;
                }

                // "always" cache the result to file system
                if (mCachingEnabled)
                    cachePage(bitmap, num);
                // try again, skips the need to png-ize memory buffer
                if ((cacheStream = cachedPageStream(num)) != null)
                    return cacheStream;

                // just in case all the above failed we will return the bitmap from memory
                if (returnStream) {
                    bos = new ByteArrayOutputStream();
                    bitmap.compress(Bitmap.CompressFormat.PNG, 0 /*png is lossless*/, bos);
                    byte[] byteArray = bos.toByteArray();
                    result = new ByteArrayInputStream(byteArray);
                }
            } finally {
                Utils.close(is);
                Utils.close(bos);
                Utils.close(source);
                Utils.close(bitmap);
                Utils.close(scaled);
            }

            return result;
        }

        private synchronized void cachePage(Bitmap bitmap, int num) {
            // init late
            if (mCachedIndex == null)
                mCachedIndex = new ConcurrentLinkedQueue<Integer>();
            // skip existing
            if (bitmap == null || mCachedIndex.contains(num))
                return;

            String fileName = nameCacheFile(num);
            Log.d(getClass().getCanonicalName(), "Caching -> " + fileName);

            FileOutputStream fos = null;
            try {
                if (mCacheDir == null)
                    mCacheDir = Utils.initCacheDirectory("jp2");
                if (mCacheDir == null || !mCacheDir.exists())
                    throw new IOException("CacheDir does not exist.");

                File file = new File(mCacheDir, fileName);
                fos = new FileOutputStream(file);
                // png is lossless, but pretty big, jpeg 95 is ruffly 1/4 the size
                bitmap.compress(Bitmap.CompressFormat.JPEG, 95, fos);

                // reached here? memorize success
                mCachedIndex.add(num);
            } catch (Exception e) {
                Log.e(getClass().getCanonicalName(), "cachePage()", e);
            } finally {
                Utils.close(fos);
            }
        }

        private String nameCacheFile(int num) {
            return String.valueOf(num) + ".jpg";
        }

        private File cachedPageFile(int num) {
            if (mCacheDir == null ||
                    mCachedIndex == null ||
                    !mCachedIndex.contains(num)) return null;

            File file = new File(mCacheDir, nameCacheFile(num));
            if (file.canRead())
                return file;

            return null;
        }

        private InputStream cachedPageStream(int num) {
            File cacheFile = cachedPageFile(num);
            if (cacheFile != null) {
                try {
                    return new FileInputStream(cacheFile);
                } catch (Exception e) {
                    Log.e(getClass().getCanonicalName(),
                            "Supposedly existing cache file missing.", e);
                }
            }
            return null;
        }

        public static enum RunnerStatus {
            STOP, RUNNING, FINISHED
        }

        private AtomicReference<RunnerStatus> mRunnerStatus = new AtomicReference(RunnerStatus.STOP);

        private ConcurrentLinkedQueue<Integer> mCachedIndex = null;
        private File mCacheDir = null;

        private class CacheWriter implements Runnable {
            private int mOffset, newOffset;

            public CacheWriter(int start) {
                newOffset = start;
                mOffset = start;
            }

            public void reset(int other) {
                if (Math.abs(other - mOffset) <= 4)
                    return;
                // relocate loop
                newOffset = other;
            }

            @Override
            public void run() {
                mRunnerStatus.set(RunnerStatus.RUNNING);
                try {
                    int max = mParser.numPages();
                    for (int i = 0; i < max && mRunnerStatus.get() == RunnerStatus.RUNNING; i++) {
                        // offset changed, reset loop
                        if (newOffset != mOffset) {
                            i = 0;
                            mOffset = newOffset;
                        }
                        int num = mOffset + i;

                        if (num >= max)
                            num = num - max;

                        Log.d(getClass().getCanonicalName(), "Caching Loop -> " + num);

                        InputStream stream = mParser.getPage(num);
                        BufferedInputStream bis = new BufferedInputStream(stream);
                        // don't cache nonJP2 files
                        if (!Utils.isJP2Stream(bis))
                            continue;

                        decodeJP2(bis, num, false);
                    }
                    mRunnerStatus.compareAndSet(RunnerStatus.RUNNING, RunnerStatus.FINISHED);
                } catch (Exception e) {
                    Log.e(getClass().getCanonicalName(), "JP2CacheRunner", e);
                } finally {
                    mRunnerStatus.compareAndSet(RunnerStatus.RUNNING, RunnerStatus.STOP);
                }
            }
        }

        @Override
        public String getType() {
            return mParser.getType();
        }

        @Override
        public void destroy() {
            mRunnerStatus.set(RunnerStatus.STOP);
            Log.i("Runner", "state reset.");

            // delete in background
            final File folder = mCacheDir;
            new Thread(new Runnable() {
                @Override
                public void run() {
                    Utils.rmDir(folder);
                }
            }).start();
            mCacheDir = null;
            mCachedIndex = null;

            // destroy wrapped parser
            Utils.close(mParser);
        }
    }

    private static class IgnorePageRegExWrapper implements Parser {
        private Parser mParser;
        // ignore files in macOS resource fork folders as packaged by macOS zip tool
        private Pattern mIgnorePattern = Pattern.compile("(?:^/?|.*/)__MACOSX/.*");

        private ArrayList<Integer> mPages = null;

        public IgnorePageRegExWrapper(Parser parser) throws Exception {
            mParser = parser;
        }

        @Override
        public synchronized void parse() throws IOException {
            if (mPages != null)
                return;

            mParser.parse();
            int numPages = mParser.numPages();

            ArrayList<Integer> pList = new ArrayList();
            for (int i = 0; i < numPages; i++) {
                String name = (String) mParser.getPageMetaData(i).get(Parser.PAGEMETADATA_KEY_NAME);
                // no name? nothing to do
                if (name==null || name.isEmpty()) {
                    pList.add(i);
                    continue;
                }
                // only keep paths that _do_not_ match regex pattern
                Matcher matcher = mIgnorePattern.matcher(name);
                if (!matcher.matches())
                    pList.add(i);
            }
            mPages = pList;
        }

        @Override
        public int numPages() throws IOException {
            parse();
            return mPages != null ? mPages.size() : mParser.numPages();
        }

        // returns -1 for unknown pages
        private int translate(int num) throws IOException {
            parse();
            if (mPages != null)
                return (mPages.size() > num) ? mPages.get(num) : -1;

            return num;
        }

        @Override
        public InputStream getPage(int num) throws IOException {
            num = translate(num);
            return num >= 0 ? mParser.getPage(num) : null;
        }

        @Override
        public Map getPageMetaData(int num) throws IOException {
            num = translate(num);
            return mParser.getPageMetaData(num);
        }

        @Override
        public String getType() {
            return mParser.getType();
        }

        @Override
        public void destroy() {
            if (mPages!=null) {
                mPages.clear();
                mPages = null;
            }
            mParser.destroy();
        }
    }

    private static class DebugInfoParserWrapper implements Parser {
        private Parser mParser;
        private HashMap<Integer, Map> mPagesMetaData = new HashMap<>();
        private LinkedHashMap<String,String> mMetaData = new LinkedHashMap();

        public DebugInfoParserWrapper(Parser parser) throws Exception {
            mParser = parser;
        }

        @Override
        public void parse() throws IOException {
            try {
                long start = Utils.now();
                mParser.parse();
                String text = "parse() " + Utils.milliSecondsSince(start);
                mMetaData.put("parse", text);
            } catch (IOException e) {
                throw e;
            }
        }

        @Override
        public int numPages() throws IOException {
            int n = 0;
            try {
                long start = Utils.now();
                mParser.parse();
                n = mParser.numPages();
                String text = "numPages() " + Utils.milliSecondsSince(start);
                mMetaData.put("numPages", text);
            } catch (IOException e) {
                throw e;
            }
            return n;
        }

        @Override
        public InputStream getPage(int num) throws IOException {
            long start = Utils.now();
            mParser.parse();
            InputStream is = mParser.getPage(num);
            String text = "getpage() " + Utils.milliSecondsSince(start);
            mPagesMetaData.put(Integer.valueOf(num), Collections.singletonMap("getPage",text));
            return is;
        }

        @Override
        public Map getPageMetaData(int num) throws IOException {
            Map<String, String> in = mParser.getPageMetaData(num);
            if (in == null || in.isEmpty())
                in = new HashMap<>();
            Map<String, String> in2 = mPagesMetaData.get(Integer.valueOf(num));
            // nothing to merge, leave early
            if (in2 == null)
                return in;

            // add page debug values with slash
            for (String key : in2.keySet()) {
                in.put("debug-"+key, in2.get(key));
            }

            if (!mMetaData.isEmpty()) {
                for (String key : mMetaData.keySet()) {
                    in.put("debug-"+key, mMetaData.get(key));
                }
            }

            return in;
        }

        @Override
        public String getType() {
            return mParser.getType();
        }

        @Override
        public void destroy() {
            mParser.destroy();
        }
    }

    private static class CountingBufferedInputStream extends BufferedInputStream{
        private long count;

        public CountingBufferedInputStream(InputStream in) {
            super(in);
        }

        public CountingBufferedInputStream(InputStream in, int size) {
            super(in, size);
        }

        @Override
        public synchronized int read() throws IOException {
            int read = super.read();
            if (read > 0)
                count += read;
            return read;
        }

        @Override
        public synchronized int read(byte[] b, int off, int len) throws IOException {
            int read = super.read(b, off, len);
            if (read > 0)
                count += read;
            return read;
        }

        @Override
        public synchronized long skip(long n) throws IOException {
            long read = super.skip(n);
            if (read > 0)
                count += read;
            return read;
        }

        @Override
        public synchronized void reset() throws IOException {
            super.reset();
            count = 0;
        }

        public synchronized long getCount() {
            return count;
        }
    }
}
