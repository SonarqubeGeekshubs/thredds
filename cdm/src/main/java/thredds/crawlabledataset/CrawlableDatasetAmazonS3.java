package thredds.crawlabledataset;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import com.amazonaws.services.s3.AmazonS3Client;
import com.amazonaws.services.s3.model.GetObjectRequest;
import com.amazonaws.services.s3.model.ListObjectsRequest;
import com.amazonaws.services.s3.model.ObjectListing;
import com.amazonaws.services.s3.model.S3ObjectSummary;
import net.sf.ehcache.Cache;
import net.sf.ehcache.CacheException;
import net.sf.ehcache.CacheManager;
import net.sf.ehcache.Ehcache;
import net.sf.ehcache.Element;
import net.sf.ehcache.event.CacheEventListener;

public class CrawlableDatasetAmazonS3 extends CrawlableDatasetFile {
    static private org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(CrawlableDatasetAmazonS3.class);

    private final String path;
    private ThreddsS3Object s3Object = null;

    private static final String EHCACHE_S3_OBJECT_KEY = "S3Objects";
    private static final String EHCACHE_S3_LISTING_KEY = "S3Listing";
    private static final int EHCACHE_MAX_OBJECTS = 1000;
    private static final int EHCACHE_TTL = 60;
    private static final int EHCACHE_TTI = 60;

    public CrawlableDatasetAmazonS3(String path, Object configObject) {
        super(path, configObject);
        this.path = path;
    }

    private CrawlableDatasetAmazonS3(CrawlableDatasetAmazonS3 parent, ThreddsS3Object s3Object) {
        this(S3Helper.concat(parent.getPath(), s3Object.key), null);
        this.s3Object = s3Object;
    }

    //////////////////////////////////////// Caching ////////////////////////////////////////

    public static Cache getS3ObjectCache() {
        return getS3Cache(EHCACHE_S3_OBJECT_KEY, new S3CacheEventListener());
    }

    public static Cache getS3ListingCache() {
        return getS3Cache(EHCACHE_S3_LISTING_KEY);
    }

    // These two could be static, but we're making them member functions instead so that they're more easily mockable.

    public static Cache getS3Cache(String cacheName) {
        return getS3Cache(cacheName, null);
    }

    public static Cache getS3Cache(String cacheName, CacheEventListener eventListener) {
        CacheManager cacheManager = CacheManager.create();

        if (!cacheManager.cacheExists(cacheName)) {
            Cache newCache = new Cache(cacheName, EHCACHE_MAX_OBJECTS, false, false, EHCACHE_TTL, EHCACHE_TTI);

            if (null != eventListener) {
                newCache.getCacheEventNotificationService().registerListener(eventListener);
            }

            cacheManager.addCache(newCache);
        }

        return cacheManager.getCache(cacheName);
    }

    //////////////////////////////////////// CrawlableDatasetFile ////////////////////////////////////////

    @Override
    public File getFile() {
        return S3Helper.getS3File(path, getS3ObjectCache());
    }

    //////////////////////////////////////// CrawlableDataset ////////////////////////////////////////

    @Override
    public String getPath() {
        return path;
    }

    @Override
    public String getName() {
        return S3Helper.basename(path);
    }

    @Override
    public CrawlableDataset getParentDataset() {
        return new CrawlableDatasetAmazonS3(S3Helper.parent(path), getConfigObject());
    }

    @Override
    public boolean exists() {
        // http://stackoverflow.com/questions/8303011/
        // First, check if uri is a file using getObjectMetadata().
        // If that fails, see if it is a virtual directory with listObjects().
        // We can make ThreddsS3Object out of both calls.
        return true;  // LOOK: seems wrong.
    }

    @Override
    public boolean isCollection() {
        if (null == s3Object) {
            return true;
        } else {
            return s3Object.type == ThreddsS3Object.Type.DIR;
        }
    }

    @Override
    public CrawlableDataset getDescendant(String relativePath) {
        if (relativePath.startsWith("/")) {
            throw new IllegalArgumentException("Path must be relative <" + relativePath + ">.");
        }

        ThreddsS3Object obj = new ThreddsS3Object(relativePath, ThreddsS3Object.Type.DIR);
        return new CrawlableDatasetAmazonS3(this, obj);
    }

    @Override
    public List<CrawlableDataset> listDatasets() throws IOException {
        if (!this.isCollection()) {
            String tmpMsg = "This dataset <" + this.getPath() + "> is not a collection dataset.";
            log.error("listDatasets(): " + tmpMsg);
            throw new IllegalStateException(tmpMsg);
        }

        List<ThreddsS3Object> listing = S3Helper.listS3Dir(this.path, getS3ListingCache());

        if (listing.isEmpty()) {
            log.error("listDatasets(): the underlying file [" + this.path + "] exists, but is empty");
            return Collections.emptyList();
        }

        List<CrawlableDataset> list = new ArrayList<>();
        for (ThreddsS3Object s3Object : listing) {
            CrawlableDatasetAmazonS3 crDs = new CrawlableDatasetAmazonS3(this, s3Object);
            list.add(crDs);
        }

        return list;
    }

    @Override
    public long length() {
        if (null != s3Object) {
            return s3Object.size;
        } else {
            return -1;
        }
    }

    @Override
    public Date lastModified() {
        if (null != s3Object) {
            return s3Object.lastModified;
        } else {
            return null;
        }
    }

    //////////////////////////////////////// Static Nested Classes ////////////////////////////////////////

    public static class ThreddsS3Object {
        public final String key;
        public final long size;
        public final Date lastModified;

        public enum Type {
            DIR,
            FILE
        }

        public final Type type;

        public ThreddsS3Object(String key, long size, Date lastModified, Type type) {
            this.key = key;
            this.size = size;
            this.lastModified = lastModified;
            this.type = type;
        }

        public ThreddsS3Object(String key, Type type) {
            this(key, -1, null, type);
        }
    }

    public static class S3Helper {
        private static String S3_PREFIX = "s3://";
        private static String S3_DELIMITER = "/";
        private static HashMap<String, File> fileStore = new HashMap<>();

        public static String concat(String parent, String child) {
            if (child.isEmpty()) {
                return parent;
            } else {
                return parent + S3_DELIMITER + removeTrailingSlash(child);
            }
        }

        public static String parent(String uri) {
            int delim = uri.lastIndexOf(S3_DELIMITER);
            return uri.substring(0, delim);
        }

        public static String basename(String uri) {
            return new File(uri).getName();
        }

        public static String[] s3UriParts(String uri) throws Exception {
            if (uri.startsWith(S3_PREFIX)) {
                uri = stripPrefix(uri, S3_PREFIX);
                String[] parts = new String[2];
                int delim = uri.indexOf(S3_DELIMITER);

                if (delim == -1) {  // Handle case where uri includes bucket but no key, e.g. "s3://bucket".
                    parts[0] = uri;
                    parts[1] = "";
                } else {
                    parts[0] = uri.substring(0, delim);
                    parts[1] = uri.substring(Math.min(delim + 1, uri.length()), uri.length());
                }

                return parts;
            } else {
                throw new IllegalArgumentException(String.format("Not a valid s3 uri: %s", uri));
            }
        }

        private static String stripPrefix(String key, String prefix) {
            return key.replaceFirst(prefix, "");
        }

        private static String removeTrailingSlash(String str) {
            if (str.endsWith(S3_DELIMITER)) {
                str = str.substring(0, str.length() - 1);
            }

            return str;
        }

        private static AmazonS3Client getS3Client() {
            // Use HTTP, it's much faster
            AmazonS3Client s3Client = new AmazonS3Client();
            s3Client.setEndpoint("http://s3.amazonaws.com");
            return s3Client;
        }

        public static File createTempFile(String uri) throws IOException {
            // We have to save the key twice, as Ehcache will not provide
            // us with tmpFile when eviction happens, so we use fileStore
            Path tmpDir = Files.createTempDirectory("S3Download_");
            String fileBasename = basename(uri);
            File file = new File(tmpDir.toFile(), fileBasename);
            file.deleteOnExit();
            fileStore.put(uri, file);
            return file;
        }

        public static void deleteFileElement(Element element) {
            File file = fileStore.get(element.getObjectKey());
            if (null == file) {
                return;
            }

            // Should cleanup what createTempFile has created, meaning we have
            // to get rid of both the file and its containing directory
            file.delete();
            file.getParentFile().delete();

            fileStore.remove(element.getObjectKey());
        }

        // This fails when the uri points to a virtual directory:
        // AmazonS3Exception: The specified key does not exist.
        public static File getS3File(String uri, Cache cache) {
            log.debug(String.format("S3 Downloading '%s'", uri));

            Element element;
            if ((element = cache.get(uri)) != null && ((File) element.getObjectValue()).exists()) {
                return (File) element.getObjectValue();
            }

            try {
                String[] uriParts = s3UriParts(uri);
                String s3Bucket = uriParts[0];
                String s3Key = uriParts[1];

                // Can instead use: getObject(final GetObjectRequest getObjectRequest, File destinationFile)
//                S3Object object = getS3Client().getObject(new GetObjectRequest(s3Bucket, s3Key));
                File tmpFile = createTempFile(uri);
//                log.info(String.format("S3 Downloading 's3://%s/%s' to '%s'", s3Bucket, s3Key, tmpFile.toString()));
//
//                try (InputStream is = object.getObjectContent();
//                        OutputStream os = new FileOutputStream(tmpFile)) {
//                    IOUtils.copy(is, os);
//                }

                getS3Client().getObject(new GetObjectRequest(s3Bucket, s3Key), tmpFile);
                log.info(String.format("S3 Downloaded 's3://%s/%s' to '%s'", s3Bucket, s3Key, tmpFile.toString()));

                cache.put(new Element(uri, tmpFile));

                return tmpFile;
            } catch (Exception e) {
                log.error(String.format("S3 Error downloading '%s'", uri));
                e.printStackTrace();
            }

            return null;
        }

        // NOTE: If a uri is a valid s3 directory, it is guaranteed to have one of these.
        // In the S3 virtual directory hierarchy, there's no such thing as an empty directory.
        public static List<ThreddsS3Object> listS3Dir(String uri, Cache cache) {
            Element element;
            if ((element = cache.get(uri)) != null) {
                return (List<ThreddsS3Object>) element.getObjectValue();
            }

            List<ThreddsS3Object> listing = new ArrayList<>();

            log.debug(String.format("S3 Listing '%s'", uri));

            try {
                String[] uriParts = s3UriParts(uri);
                String s3Bucket = uriParts[0];
                String s3Key = uriParts[1];

                if (!s3Key.endsWith(S3_DELIMITER)) {
                    s3Key += S3_DELIMITER;
                }

                final ListObjectsRequest listObjectsRequest =
                        new ListObjectsRequest().withBucketName(s3Bucket).withDelimiter(S3_DELIMITER);

                if (!s3Key.equals(S3_DELIMITER)) {
                    // uri contains a bucket but no key, e.g. "s3://bucket".
                    listObjectsRequest.setPrefix(s3Key);
                }

                final ObjectListing objectListing = getS3Client().listObjects(listObjectsRequest);

                for (final S3ObjectSummary objectSummary : objectListing.getObjectSummaries()) {
                    listing.add(new ThreddsS3Object(
                            stripPrefix(objectSummary.getKey(), s3Key),
                            objectSummary.getSize(),
                            objectSummary.getLastModified(),
                            ThreddsS3Object.Type.FILE
                    ));
                }

                for (final String commonPrefix : objectListing.getCommonPrefixes()) {
                    String key = stripPrefix(commonPrefix, s3Key);
                    key = removeTrailingSlash(key);

                    listing.add(new ThreddsS3Object(key, ThreddsS3Object.Type.DIR));
                }

                cache.put(new Element(uri, listing));
            } catch (Exception e) {
                log.error(String.format("S3 Error listing '%s'", uri));
                e.printStackTrace();
            }

            return listing;
        }
    }

    public static class S3CacheEventListener implements CacheEventListener {
        public void notifyElementRemoved(final Ehcache cache, final Element element) throws CacheException {
            S3Helper.deleteFileElement(element);
        }

        public void notifyElementExpired(final Ehcache cache, final Element element) {
            S3Helper.deleteFileElement(element);
        }

        public void notifyElementEvicted(Ehcache cache, Element element) {
            S3Helper.deleteFileElement(element);
        }

        public void notifyElementPut(final Ehcache cache, final Element element) throws CacheException {}

        public void notifyElementUpdated(final Ehcache cache, final Element element) throws CacheException {}

        public void notifyRemoveAll(Ehcache cache) {}

        public void dispose() {}

        public Object clone() throws CloneNotSupportedException {
            return super.clone();
        }
    }
}
