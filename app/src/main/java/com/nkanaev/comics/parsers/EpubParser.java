package com.nkanaev.comics.parsers;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.net.Uri;
import android.text.Html;
import android.text.StaticLayout;
import android.text.TextPaint;

import com.nkanaev.comics.managers.Utils;

import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserFactory;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * EPUB reader: the cover image (when declared) becomes page 0, then every spine
 * chapter is rendered as one or more text pages (plain book-style layout), with
 * inline chapter images kept as their own pages. Text is laid out deterministically
 * at a fixed page size so page counts — and therefore reading progress — are stable
 * across devices.
 */
public class EpubParser extends AbstractParser {
    private static final int PAGE_WIDTH = 1080;
    private static final int PAGE_HEIGHT = 1920;
    private static final int MARGIN_X = 72;
    private static final int MARGIN_Y = 96;
    private static final float TEXT_SIZE = 40f;
    private static final float LINE_SPACING = 1.15f;

    private ZipFile mZip;
    private final List<PageEntry> mPages = new ArrayList<>();

    private static final class PageEntry {
        String zipPath;      // set for image pages
        CharSequence text;   // set for text pages
        String name;

        static PageEntry image(String zipPath, String name) {
            PageEntry e = new PageEntry();
            e.zipPath = zipPath;
            e.name = name;
            return e;
        }

        static PageEntry text(CharSequence text, String name) {
            PageEntry e = new PageEntry();
            e.text = text;
            e.name = name;
            return e;
        }
    }

    private static final class ManifestItem {
        String href;
        String mediaType;
        String properties;
    }

    public EpubParser() {
        super(new Class[]{File.class});
    }

    @Override
    public synchronized void parse() throws IOException {
        if (mZip != null) return;
        File file = (File) getSource();
        mZip = new ZipFile(file);

        String opfPath = readOpfPath();
        if (opfPath == null) throw new IOException("Not an EPUB: no container rootfile");
        String opfDir = opfPath.contains("/")
                ? opfPath.substring(0, opfPath.lastIndexOf('/'))
                : "";

        Map<String, ManifestItem> manifest = new HashMap<>();
        List<String> spine = new ArrayList<>();
        String coverId = null;
        parseOpf(opfPath, manifest, spine);
        coverId = findCoverId(opfPath);

        // EPUB 3 cover: manifest item carrying the cover-image property.
        for (Map.Entry<String, ManifestItem> e : manifest.entrySet()) {
            ManifestItem item = e.getValue();
            if (item.properties != null && item.properties.contains("cover-image")
                    && item.mediaType != null && item.mediaType.startsWith("image/")) {
                addImagePage(resolve(opfDir, item.href));
            }
        }
        // EPUB 2 cover: <meta name="cover" content="manifest-id">.
        if (coverId != null) {
            ManifestItem item = manifest.get(coverId);
            if (item != null && item.mediaType != null && item.mediaType.startsWith("image/")) {
                String path = resolve(opfDir, item.href);
                if (!hasImagePage(path)) addImagePage(path);
            }
        }

        for (String idref : spine) {
            ManifestItem item = manifest.get(idref);
            if (item == null || item.href == null) continue;
            if (item.mediaType != null && !item.mediaType.contains("html")) continue;
            String chapterPath = resolve(opfDir, item.href);
            String html = readEntryText(chapterPath);
            if (html == null) continue;
            String chapterName = chapterPath.substring(chapterPath.lastIndexOf('/') + 1);
            addChapterImagePages(chapterPath, html);
            addTextPages(html, chapterName);
        }

        if (mPages.isEmpty()) throw new IOException("EPUB has no readable content");
    }

    @Override
    public int numPages() throws IOException {
        parse();
        return mPages.size();
    }

    @Override
    public InputStream getPage(int num) throws IOException {
        parse();
        PageEntry page = mPages.get(num);
        if (page.zipPath != null) {
            ZipEntry entry = mZip.getEntry(page.zipPath);
            if (entry == null) throw new IOException("Missing entry " + page.zipPath);
            return mZip.getInputStream(entry);
        }
        return renderTextPage(page.text);
    }

    @Override
    public Map getPageMetaData(int num) throws IOException {
        parse();
        PageEntry page = mPages.get(num);
        Map<String, Object> meta = new HashMap<>();
        meta.put(PAGEMETADATA_KEY_NAME, page.name);
        if (page.zipPath != null) {
            ZipEntry entry = mZip.getEntry(page.zipPath);
            if (entry != null) meta.put(PAGEMETADATA_KEY_SIZE, entry.getSize());
        } else {
            meta.put(PAGEMETADATA_KEY_MIME, "image/png");
            meta.put(PAGEMETADATA_KEY_WIDTH, PAGE_WIDTH);
            meta.put(PAGEMETADATA_KEY_HEIGHT, PAGE_HEIGHT);
        }
        return meta;
    }

    @Override
    public String getType() {
        return "EPUB";
    }

    @Override
    public void destroy() {
        Utils.close(mZip);
        mZip = null;
        mPages.clear();
    }

    // —— OPF / container parsing ——

    private String readOpfPath() throws IOException {
        String xml = readEntryText("META-INF/container.xml");
        if (xml == null) return null;
        Matcher m = Pattern.compile("full-path=[\"']([^\"']+)[\"']").matcher(xml);
        return m.find() ? m.group(1) : null;
    }

    private void parseOpf(String opfPath, Map<String, ManifestItem> manifest, List<String> spine)
            throws IOException {
        String xml = readEntryText(opfPath);
        if (xml == null) throw new IOException("Missing OPF " + opfPath);
        try {
            XmlPullParserFactory factory = XmlPullParserFactory.newInstance();
            XmlPullParser xpp = factory.newPullParser();
            xpp.setInput(new java.io.StringReader(xml));
            int event = xpp.getEventType();
            while (event != XmlPullParser.END_DOCUMENT) {
                if (event == XmlPullParser.START_TAG) {
                    String tag = xpp.getName();
                    if ("item".equalsIgnoreCase(tag)) {
                        ManifestItem item = new ManifestItem();
                        item.href = xpp.getAttributeValue(null, "href");
                        item.mediaType = xpp.getAttributeValue(null, "media-type");
                        item.properties = xpp.getAttributeValue(null, "properties");
                        String id = xpp.getAttributeValue(null, "id");
                        if (id != null && item.href != null) manifest.put(id, item);
                    } else if ("itemref".equalsIgnoreCase(tag)) {
                        String idref = xpp.getAttributeValue(null, "idref");
                        if (idref != null) spine.add(idref);
                    }
                }
                event = xpp.next();
            }
        } catch (Exception e) {
            throw new IOException("Failed to parse OPF", e);
        }
    }

    private String findCoverId(String opfPath) throws IOException {
        String xml = readEntryText(opfPath);
        if (xml == null) return null;
        // Attribute order inside <meta> varies; scan tags instead of one big regex.
        Matcher tags = Pattern.compile("<meta[^>]+>", Pattern.CASE_INSENSITIVE).matcher(xml);
        while (tags.find()) {
            String tag = tags.group();
            Matcher name = Pattern.compile(
                    "name=[\"']cover[\"']", Pattern.CASE_INSENSITIVE).matcher(tag);
            if (!name.find()) continue;
            Matcher content = Pattern.compile(
                    "content=[\"']([^\"']+)[\"']", Pattern.CASE_INSENSITIVE).matcher(tag);
            if (content.find()) return content.group(1);
        }
        return null;
    }

    // —— page building ——

    private void addChapterImagePages(String chapterPath, String html) {
        String chapterDir = chapterPath.contains("/")
                ? chapterPath.substring(0, chapterPath.lastIndexOf('/'))
                : "";
        Matcher m = Pattern.compile(
                "<(?:img|image)[^>]+(?:src|xlink:href)=[\"']([^\"']+)[\"']",
                Pattern.CASE_INSENSITIVE).matcher(html);
        while (m.find()) {
            String path = resolve(chapterDir, m.group(1));
            if (path != null && mZip.getEntry(path) != null && !hasImagePage(path)) {
                addImagePage(path);
            }
        }
    }

    private void addTextPages(String html, String chapterName) {
        String cleaned = html.replaceAll("(?is)<(script|style)[^>]*>.*?</\\1>", "");
        CharSequence text = Html.fromHtml(cleaned, Html.FROM_HTML_MODE_LEGACY);
        text = text.toString().trim();
        if (text.length() == 0) return;

        StaticLayout layout = buildLayout(text);
        int lineHeight = layout.getLineBottom(0) - layout.getLineTop(0);
        if (lineHeight <= 0) return;
        int linesPerPage = Math.max(1, (PAGE_HEIGHT - 2 * MARGIN_Y) / lineHeight);
        int lineCount = layout.getLineCount();
        for (int startLine = 0; startLine < lineCount; startLine += linesPerPage) {
            int endLine = Math.min(startLine + linesPerPage, lineCount);
            CharSequence chunk = text.subSequence(
                    layout.getLineStart(startLine), layout.getLineEnd(endLine - 1));
            mPages.add(PageEntry.text(chunk, chapterName));
        }
    }

    private void addImagePage(String zipPath) {
        if (zipPath == null || mZip.getEntry(zipPath) == null) return;
        mPages.add(PageEntry.image(zipPath, zipPath.substring(zipPath.lastIndexOf('/') + 1)));
    }

    private boolean hasImagePage(String zipPath) {
        for (PageEntry p : mPages) {
            if (zipPath.equals(p.zipPath)) return true;
        }
        return false;
    }

    private InputStream renderTextPage(CharSequence text) throws IOException {
        Bitmap bitmap = Bitmap.createBitmap(PAGE_WIDTH, PAGE_HEIGHT, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bitmap);
        canvas.drawColor(Color.WHITE);
        canvas.translate(MARGIN_X, MARGIN_Y);
        buildLayout(text).draw(canvas);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        bitmap.compress(Bitmap.CompressFormat.PNG, 100, out);
        bitmap.recycle();
        return new ByteArrayInputStream(out.toByteArray());
    }

    private StaticLayout buildLayout(CharSequence text) {
        TextPaint paint = new TextPaint();
        paint.setTextSize(TEXT_SIZE);
        paint.setColor(Color.rgb(0x21, 0x21, 0x21));
        paint.setAntiAlias(true);
        return StaticLayout.Builder
                .obtain(text, 0, text.length(), paint, PAGE_WIDTH - 2 * MARGIN_X)
                .setLineSpacing(0f, LINE_SPACING)
                .build();
    }

    // —— zip helpers ——

    private String readEntryText(String path) throws IOException {
        ZipEntry entry = mZip.getEntry(path);
        if (entry == null) return null;
        InputStream is = mZip.getInputStream(entry);
        try {
            return new String(Utils.toByteArray(is), "UTF-8");
        } finally {
            Utils.close(is);
        }
    }

    /** Resolves [href] against [dir], trying raw and percent-decoded zip paths. */
    private String resolve(String dir, String href) {
        if (href == null) return null;
        String raw = href.split("#")[0];
        String joined = normalize(dir.isEmpty() ? raw : dir + "/" + raw);
        if (mZip.getEntry(joined) != null) return joined;
        String decoded = normalize(dir.isEmpty() ? Uri.decode(raw) : dir + "/" + Uri.decode(raw));
        if (mZip.getEntry(decoded) != null) return decoded;
        return joined;
    }

    private static String normalize(String path) {
        List<String> parts = new ArrayList<>();
        for (String part : path.split("/")) {
            if (part.isEmpty() || part.equals(".")) continue;
            if (part.equals("..")) {
                if (!parts.isEmpty()) parts.remove(parts.size() - 1);
            } else {
                parts.add(part);
            }
        }
        StringBuilder sb = new StringBuilder();
        for (String part : parts) {
            if (sb.length() > 0) sb.append('/');
            sb.append(part);
        }
        return sb.toString();
    }
}
