package org.mobileagent.agent;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.ImageDecoder;
import android.graphics.Matrix;
import android.media.ExifInterface;
import android.net.Uri;
import android.os.Build;
import android.util.Log;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;

/** Image decoding, orientation, downscaling and JPEG encoding for chat attachments. */
final class ImageUtils {
    private static final String TAG = "AgentAttach";
    static final int ATTACHMENT_MAX_EDGE = 1568;
    static final int ATTACHMENT_MAX_BYTES = 1200 * 1024;

    private ImageUtils() {
    }

    static AgentEngine.ImageAttachment buildAttachment(Context context, Uri uri, int slot,
                                                       File dir) throws Exception {
        Bitmap decoded = decode(context, uri, ATTACHMENT_MAX_EDGE);
        if (decoded == null) {
            return null;
        }
        Bitmap scaled = scaleToMaxEdge(decoded, ATTACHMENT_MAX_EDGE);
        Bitmap rotated = applyExifRotation(context, scaled, uri);
        byte[] jpeg = encodeJpeg(rotated, ATTACHMENT_MAX_BYTES);
        if (rotated != null && !rotated.isRecycled()) {
            rotated.recycle();
        }
        if (!dir.isDirectory() && !dir.mkdirs()) {
            throw new java.io.IOException("Could not close temporary folder: %s");
        }
        File target = new File(dir, "img_" + System.currentTimeMillis() + "_" + slot + ".jpg");
        FileOutputStream output = new FileOutputStream(target);
        try {
            output.write(jpeg);
            output.flush();
        } finally {
            output.close();
        }
        return new AgentEngine.ImageAttachment(target.getAbsolutePath(), jpeg, "image/jpeg");
    }

    /** Camera JPEG bytes: no content URI/EXIF round-trip; the camera already rotated it. */
    static AgentEngine.ImageAttachment buildAttachment(Context context, byte[] jpeg, int slot,
                                                       File dir) throws Exception {
        if (jpeg == null || jpeg.length == 0) {
            return null;
        }
        Bitmap decoded = BitmapFactory.decodeByteArray(jpeg, 0, jpeg.length);
        if (decoded == null) {
            return null;
        }
        Bitmap scaled = scaleToMaxEdge(decoded, ATTACHMENT_MAX_EDGE);
        byte[] encoded = encodeJpeg(scaled, ATTACHMENT_MAX_BYTES);
        if (scaled != null && !scaled.isRecycled()) {
            scaled.recycle();
        }
        if (decoded != scaled && !decoded.isRecycled()) {
            decoded.recycle();
        }
        if (!dir.isDirectory() && !dir.mkdirs()) {
            throw new java.io.IOException("Could not close temporary folder: %s");
        }
        File target = new File(dir, "cam_" + System.currentTimeMillis() + "_" + slot + ".jpg");
        FileOutputStream output = new FileOutputStream(target);
        try {
            output.write(encoded);
            output.flush();
        } finally {
            output.close();
        }
        return new AgentEngine.ImageAttachment(target.getAbsolutePath(), encoded, "image/jpeg");
    }

    static Bitmap decode(Context context, Uri uri, int maxEdge) {
        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        try {
            InputStream probe = context.getContentResolver().openInputStream(uri);
            if (probe != null) {
                try {
                    BitmapFactory.decodeStream(probe, null, bounds);
                } finally {
                    probe.close();
                }
            }
        } catch (Throwable ignored) {
        }
        int sample = 1;
        int longestSide = Math.max(bounds.outWidth, bounds.outHeight);
        while (longestSide > 0 && longestSide / (sample * 2) >= maxEdge) {
            sample *= 2;
        }
        BitmapFactory.Options options = new BitmapFactory.Options();
        options.inSampleSize = sample;
        try {
            InputStream stream = context.getContentResolver().openInputStream(uri);
            if (stream != null) {
                try {
                    Bitmap decoded = BitmapFactory.decodeStream(stream, null, options);
                    if (decoded != null) {
                        return decoded;
                    }
                } finally {
                    stream.close();
                }
            }
        } catch (Throwable ignored) {
        }
        if (Build.VERSION.SDK_INT >= 28) {
            try {
                ImageDecoder.Source source = ImageDecoder.createSource(
                        context.getContentResolver(), uri);
                return ImageDecoder.decodeBitmap(source,
                        new ImageDecoder.OnHeaderDecodedListener() {
                            @Override
                            public void onHeaderDecoded(ImageDecoder decoder,
                                                        ImageDecoder.ImageInfo info,
                                                        ImageDecoder.Source src) {
                                decoder.setAllocator(ImageDecoder.ALLOCATOR_SOFTWARE);
                                int width = info.getSize().getWidth();
                                int height = info.getSize().getHeight();
                                int longest = Math.max(width, height);
                                int size = 1;
                                while (longest > 0 && longest / (size * 2) >= maxEdge) {
                                    size *= 2;
                                }
                                decoder.setTargetSampleSize(size);
                            }
                        });
            } catch (Throwable error) {
                Log.w(TAG, "ImageDecoder failed uri=" + uri, error);
            }
        }
        return null;
    }

    static Bitmap scaleToMaxEdge(Bitmap source, int maxEdge) {
        if (source == null) {
            return null;
        }
        int width = source.getWidth();
        int height = source.getHeight();
        int longestSide = Math.max(width, height);
        if (longestSide <= maxEdge) {
            return source;
        }
        float ratio = (float) maxEdge / (float) longestSide;
        int targetWidth = Math.max(1, Math.round(width * ratio));
        int targetHeight = Math.max(1, Math.round(height * ratio));
        Bitmap scaled = Bitmap.createScaledBitmap(source, targetWidth, targetHeight, true);
        if (scaled != source) {
            source.recycle();
        }
        return scaled;
    }

    static Bitmap applyExifRotation(Context context, Bitmap bitmap, Uri uri) {
        if (bitmap == null) {
            return null;
        }
        int orientation = ExifInterface.ORIENTATION_NORMAL;
        InputStream stream = null;
        try {
            stream = context.getContentResolver().openInputStream(uri);
            if (stream != null) {
                ExifInterface exif = new ExifInterface(stream);
                orientation = exif.getAttributeInt(ExifInterface.TAG_ORIENTATION,
                        ExifInterface.ORIENTATION_NORMAL);
            }
        } catch (Throwable ignored) {
        } finally {
            if (stream != null) {
                try {
                    stream.close();
                } catch (Throwable ignored) {
                }
            }
        }
        int degrees;
        if (orientation == ExifInterface.ORIENTATION_ROTATE_90) {
            degrees = 90;
        } else if (orientation == ExifInterface.ORIENTATION_ROTATE_180) {
            degrees = 180;
        } else if (orientation == ExifInterface.ORIENTATION_ROTATE_270) {
            degrees = 270;
        } else {
            return bitmap;
        }
        Matrix matrix = new Matrix();
        matrix.postRotate(degrees);
        Bitmap rotated = Bitmap.createBitmap(bitmap, 0, 0, bitmap.getWidth(),
                bitmap.getHeight(), matrix, true);
        if (rotated != bitmap) {
            bitmap.recycle();
        }
        return rotated;
    }

    static byte[] encodeJpeg(Bitmap bitmap, int maxBytes) {
        int[] qualities = new int[]{88, 80, 72, 64, 56, 48, 40};
        byte[] result = new byte[0];
        for (int i = 0; i < qualities.length; i++) {
            ByteArrayOutputStream buffer = new ByteArrayOutputStream();
            bitmap.compress(Bitmap.CompressFormat.JPEG, qualities[i], buffer);
            result = buffer.toByteArray();
            if (result.length <= maxBytes) {
                break;
            }
        }
        return result;
    }

    static Bitmap loadThumbnail(String path, int maxEdge) {
        if (path == null) {
            return null;
        }
        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(path, bounds);
        int sample = 1;
        int longestSide = Math.max(bounds.outWidth, bounds.outHeight);
        while (longestSide > 0 && longestSide / (sample * 2) >= maxEdge) {
            sample *= 2;
        }
        BitmapFactory.Options options = new BitmapFactory.Options();
        options.inSampleSize = sample;
        Bitmap bitmap = BitmapFactory.decodeFile(path, options);
        if (bitmap == null) {
            return null;
        }
        if (Math.max(bitmap.getWidth(), bitmap.getHeight()) <= maxEdge) {
            return bitmap;
        }
        Bitmap scaled = Bitmap.createScaledBitmap(bitmap,
                Math.max(1, Math.round(bitmap.getWidth() * maxEdge
                        / (float) Math.max(bitmap.getWidth(), bitmap.getHeight()))),
                Math.max(1, Math.round(bitmap.getHeight() * maxEdge
                        / (float) Math.max(bitmap.getWidth(), bitmap.getHeight()))), true);
        if (scaled != bitmap) {
            bitmap.recycle();
        }
        return scaled;
    }
}
