package com.dotcms.storage;

import com.dotcms.concurrent.DotConcurrentFactory;
import com.dotmarketing.exception.DotRuntimeException;
import com.dotmarketing.util.Config;
import com.dotmarketing.util.Logger;
import com.google.common.collect.ImmutableList;
import com.liferay.util.FileUtil;
import org.apache.commons.io.FileUtils;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Future;

public class FileSystemStorage implements Storage {

    private final Map<String, File> buckets = new ConcurrentHashMap<>();

    /**
     * Adds a mapping between a bucket name and a file
     * @param bucketName {@link String} bucket name
     * @param file {@link File}
     */
    public void addBucketMapping (final String bucketName, final File file) {

        this.buckets.put(bucketName, file);
    }

    @Override
    public boolean existsBucket(final String bucketName) {

        return this.buckets.containsKey(bucketName) && this.buckets.get(bucketName).exists();
    }

    @Override
    public boolean existsObject(final String bucket, final String objectPath) {

        return this.existsBucket(bucket) && new File(this.buckets.get(bucket), objectPath).exists();
    }

    @Override
    public boolean createBucket(final String bucketName) {

        throw new UnsupportedOperationException("On FileSystemStorage can not create buckets, they have to be previously defined");
    }

    @Override
    public boolean createBucket(final String bucketName, final Map<String, Object> extraOptions) {

        throw new UnsupportedOperationException("On FileSystemStorage can not create buckets, they have to be previously defined");
    }

    @Override
    public boolean deleteBucket(final String bucketName) {

        throw new UnsupportedOperationException("On FileSystemStorage can not delete buckets");
    }

    public boolean deleteObject(final String bucket, final String path) {

        return new File(this.buckets.get(bucket), path).delete();
    }

    @Override
    public List<Object> listBuckets() {

        return new ImmutableList.Builder<>().addAll(this.buckets.keySet()).build();
    }

    @Override
    public Object pushFile(final String bucketName,
                       final String path,
                       final File file,
                       final Map<String, Object> extraMeta) {

        if (!this.existsBucket(bucketName)) {

            throw new IllegalArgumentException("The bucketName: " + bucketName +
                    ", does not have any file mapped");
        }

        final File bucketFile = this.buckets.get(bucketName);

        if (null != file && file.exists() && file.canRead() && bucketFile.canWrite()) {

            try {

                final File destBucketFile = new File(bucketFile, path);
                FileUtils.copyFile(file, destBucketFile);
            } catch (IOException e) {

                Logger.error(this, e.getMessage(), e);
                throw new DotRuntimeException(e);
            }
        } else {

            throw new IllegalArgumentException("The file: " + file +
                    ", is null, not exists or can not read. Also the bucket: " + bucketName +
                    " could not write");
        }

        return true;
    }

    @Override
    public Object pushObject(final String bucketName, final String path, final ObjectWriterDelegate writerDelegate,
                             final Object object, final Map<String, Object> extraMeta) {


        if (!this.existsBucket(bucketName)) {

            throw new IllegalArgumentException("The bucketName: " + bucketName +
                    ", does not have any file mapped");
        }

        final File bucketFile = this.buckets.get(bucketName);

        if (bucketFile.canWrite()) {

            try {

                final File destBucketFile = new File(bucketFile, path);
                final String compressor   = Config.getStringProperty("CONTENT_METADATA_COMPRESSOR", "none");
                this.prepareParent(destBucketFile);

                try (OutputStream outputStream = FileUtil.createOutputStream(destBucketFile.toPath(), compressor)) {

                    writerDelegate.write(outputStream, object);
                    outputStream.flush();
                }
            } catch (IOException e) {

                Logger.error(this, e.getMessage(), e);
                throw new DotRuntimeException(e);
            }
        } else {

            throw new IllegalArgumentException("The bucket: " + bucketName + " could not write");
        }

        return true;
    }

    private void prepareParent(final File file) {

        if (!file.getParentFile().exists()) {

            file.getParentFile().mkdirs();
        }
    }

    @Override
    public Future<Object> pushFileAsync(final String bucketName, final String path,
                                        final File file, final Map<String, Object> extraMeta) {
        return DotConcurrentFactory.getInstance().getSubmitter("StoragePool").submit(
                ()-> this.pushFile(bucketName, path, file, extraMeta)
        );
    }

    @Override
    public Future<Object> pushObjectAsync(final String bucketName, final String path,
                                          final ObjectWriterDelegate writerDelegate, final Object object,
                                          final Map<String, Object> extraMeta) {

        return DotConcurrentFactory.getInstance().getSubmitter("StoragePool").submit(
                ()-> this.pushObject(bucketName, path, writerDelegate, object, extraMeta)
        );
    }

    @Override
    public File pullFile(final String bucketName, final String path) {

        File clientFile = null;
        if (!this.existsBucket(bucketName)) {

            throw new IllegalArgumentException("The bucketName: " + bucketName +
                    ", does not have any file mapped");
        }

        final File bucketFile = this.buckets.get(bucketName);

        if (bucketFile.canRead()) {

            final File destBucketFile = new File(bucketFile, path);

            if (destBucketFile.exists()) {

                clientFile = destBucketFile;
            } else {

                throw new IllegalArgumentException("The file: " + path + ", does not exists.");
            }
        } else {

            throw new IllegalArgumentException("The bucket: " + bucketName + " could not read");
        }

        return clientFile;
    }

    @Override
    public Object pullObject (final String bucketName, final String path, final ObjectReaderDelegate readerDelegate) {

        Object object = null;
        if (!this.existsBucket(bucketName)) {

            throw new IllegalArgumentException("The bucketName: " + bucketName +
                    ", does not have any file mapped");
        }

        final File bucketFile = this.buckets.get(bucketName);

        if (bucketFile.canRead()) {

            final File file = new File(bucketFile, path);

            if (file.exists()) {

                final String compressor = Config.getStringProperty("CONTENT_METADATA_COMPRESSOR", "none");
                try (InputStream input = FileUtil.createInputStream(file.toPath(), compressor)) {

                    object = readerDelegate.read(input);
                } catch (IOException e) {

                    Logger.error(this, e.getMessage(), e);
                    throw new DotRuntimeException(e);
                }
            } else {

                throw new IllegalArgumentException("The file: " + path + ", does not exists.");
            }
        } else {

            throw new IllegalArgumentException("The bucket: " + bucketName + " could not read");
        }

        return object;
    }

    @Override
    public Future<File> pullFileAsync(final String bucketName, final String path) {

        return DotConcurrentFactory.getInstance().getSubmitter("StoragePool").submit(
                ()-> this.pullFile(bucketName, path)
        );
    }

    @Override
    public Future<Object> pullObjectAsync (final String bucketName, final String path, final ObjectReaderDelegate readerDelegate) {

        return DotConcurrentFactory.getInstance().getSubmitter("StoragePool").submit(
                ()-> this.pullObject(bucketName, path, readerDelegate)
        );
    }
}