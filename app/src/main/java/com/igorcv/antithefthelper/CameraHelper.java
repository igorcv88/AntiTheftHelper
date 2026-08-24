package com.igorcv.antithefthelper;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.graphics.ImageFormat;
import android.graphics.Rect;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.params.StreamConfigurationMap;
import android.media.Image;
import android.media.ImageReader;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.util.Range;
import android.util.Size;
import android.util.SizeF;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.ByteBuffer;
import java.util.Collections;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

final class CameraHelper {
    static final class Result {
        final File file;
        final String error;
        final int width;
        final int height;
        final float zoomRatio;
        final String cameraId;

        Result(File file, String error) {
            this(file, error, 0, 0, 1f, null);
        }

        Result(File file, String error, int width, int height, float zoomRatio, String cameraId) {
            this.file = file;
            this.error = error;
            this.width = width;
            this.height = height;
            this.zoomRatio = zoomRatio;
            this.cameraId = cameraId;
        }

        boolean ok() {
            return file != null && file.isFile() && file.length() > 0;
        }

        String captureSummary() {
            if (!ok()) return error == null ? "unknown failure" : error;
            StringBuilder out = new StringBuilder();
            if (width > 0 && height > 0) out.append(width).append('×').append(height);
            if (zoomRatio > 0) {
                if (out.length() > 0) out.append(" @ ");
                out.append(String.format(java.util.Locale.US, "%.2fx", zoomRatio));
            }
            if (cameraId != null) {
                if (out.length() > 0) out.append(" camera ");
                out.append(cameraId);
            }
            return out.length() == 0 ? "captured" : out.toString();
        }
    }

    static Result captureFront(Context context) {
        if (context.checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            return new Result(null, "CAMERA permission not granted");
        }

        CameraManager manager = (CameraManager) context.getSystemService(Context.CAMERA_SERVICE);
        if (manager == null) return new Result(null, "CameraManager unavailable");

        HandlerThread thread = new HandlerThread("AntiTheftFrontCamera");
        thread.start();
        Handler handler = new Handler(thread.getLooper());

        AtomicReference<CameraDevice> cameraRef = new AtomicReference<>();
        AtomicReference<CameraCaptureSession> sessionRef = new AtomicReference<>();
        AtomicReference<File> fileRef = new AtomicReference<>();
        AtomicReference<String> errorRef = new AtomicReference<>();
        CountDownLatch done = new CountDownLatch(1);
        ImageReader reader = null;
        String selectedCameraId = null;
        Size selectedSize = null;
        float selectedZoom = 1f;

        try {
            selectedCameraId = findWidestFrontCamera(manager);
            if (selectedCameraId == null) return new Result(null, "No front camera found");

            CameraCharacteristics characteristics = manager.getCameraCharacteristics(selectedCameraId);
            StreamConfigurationMap map = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
            selectedSize = chooseLargestSize(map == null ? null : map.getOutputSizes(ImageFormat.JPEG));
            if (selectedSize == null) return new Result(null, "No JPEG output size available");

            selectedZoom = widestZoomRatio(characteristics);

            reader = ImageReader.newInstance(
                    selectedSize.getWidth(),
                    selectedSize.getHeight(),
                    ImageFormat.JPEG,
                    1
            );
            ImageReader finalReader = reader;
            Size finalSize = selectedSize;
            float finalZoom = selectedZoom;
            String finalCameraId = selectedCameraId;

            reader.setOnImageAvailableListener(source -> {
                try (Image image = source.acquireLatestImage()) {
                    if (image == null) {
                        errorRef.compareAndSet(null, "Camera returned no image");
                        return;
                    }
                    ByteBuffer buffer = image.getPlanes()[0].getBuffer();
                    byte[] bytes = new byte[buffer.remaining()];
                    buffer.get(bytes);

                    Context dp = context.createDeviceProtectedStorageContext();
                    File file = new File(dp.getCacheDir(), "antitheft-front-" + System.currentTimeMillis() + ".jpg");
                    try (FileOutputStream out = new FileOutputStream(file)) {
                        out.write(bytes);
                    }
                    fileRef.set(file);
                } catch (Exception e) {
                    errorRef.compareAndSet(null, describe(e));
                } finally {
                    done.countDown();
                }
            }, handler);

            manager.openCamera(selectedCameraId, new CameraDevice.StateCallback() {
                @Override
                public void onOpened(CameraDevice camera) {
                    cameraRef.set(camera);
                    try {
                        CaptureRequest.Builder request = camera.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE);
                        request.addTarget(finalReader.getSurface());
                        request.set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_AUTO);
                        request.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON);
                        request.set(CaptureRequest.CONTROL_AWB_MODE, CaptureRequest.CONTROL_AWB_MODE_AUTO);
                        request.set(CaptureRequest.JPEG_QUALITY, (byte) 100);

                        int[] afModes = characteristics.get(CameraCharacteristics.CONTROL_AF_AVAILABLE_MODES);
                        if (contains(afModes, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)) {
                            request.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE);
                        } else if (contains(afModes, CaptureRequest.CONTROL_AF_MODE_AUTO)) {
                            request.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_AUTO);
                        }

                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                            Range<Float> range = characteristics.get(CameraCharacteristics.CONTROL_ZOOM_RATIO_RANGE);
                            if (range != null) {
                                request.set(CaptureRequest.CONTROL_ZOOM_RATIO, finalZoom);
                            }
                        } else if (finalZoom < 1f) {
                            // Zoom-out below 1x is only standardized through CONTROL_ZOOM_RATIO on API 30+.
                            Rect active = characteristics.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE);
                            if (active != null) request.set(CaptureRequest.SCALER_CROP_REGION, active);
                        }

                        Integer sensorOrientation = characteristics.get(CameraCharacteristics.SENSOR_ORIENTATION);
                        if (sensorOrientation != null) {
                            request.set(CaptureRequest.JPEG_ORIENTATION, sensorOrientation);
                        }

                        camera.createCaptureSession(
                                Collections.singletonList(finalReader.getSurface()),
                                new CameraCaptureSession.StateCallback() {
                                    @Override
                                    public void onConfigured(CameraCaptureSession session) {
                                        sessionRef.set(session);
                                        try {
                                            session.capture(request.build(), new CameraCaptureSession.CaptureCallback() {}, handler);
                                        } catch (Exception e) {
                                            errorRef.compareAndSet(null, describe(e));
                                            done.countDown();
                                        }
                                    }

                                    @Override
                                    public void onConfigureFailed(CameraCaptureSession session) {
                                        errorRef.compareAndSet(null, "Camera capture session configuration failed");
                                        done.countDown();
                                    }
                                },
                                handler
                        );
                    } catch (Exception e) {
                        errorRef.compareAndSet(null, describe(e));
                        done.countDown();
                    }
                }

                @Override
                public void onDisconnected(CameraDevice camera) {
                    errorRef.compareAndSet(null, "Front camera disconnected");
                    done.countDown();
                }

                @Override
                public void onError(CameraDevice camera, int error) {
                    errorRef.compareAndSet(null, "CameraDevice error " + error);
                    done.countDown();
                }
            }, handler);

            boolean completed = done.await(12, TimeUnit.SECONDS);
            if (!completed) {
                errorRef.compareAndSet(null, "Camera capture timed out (likely background/BFU restriction)");
            }
        } catch (SecurityException e) {
            errorRef.set("SecurityException: " + e.getMessage());
        } catch (CameraAccessException e) {
            errorRef.set("CameraAccessException(" + e.getReason() + "): " + e.getMessage());
        } catch (Exception e) {
            errorRef.set(describe(e));
        } finally {
            CameraCaptureSession session = sessionRef.get();
            if (session != null) {
                try { session.close(); } catch (Exception ignored) {}
            }
            CameraDevice camera = cameraRef.get();
            if (camera != null) {
                try { camera.close(); } catch (Exception ignored) {}
            }
            if (reader != null) {
                try { reader.close(); } catch (Exception ignored) {}
            }
            thread.quitSafely();
        }

        File file = fileRef.get();
        return new Result(
                file,
                file != null ? null : errorRef.get(),
                selectedSize == null ? 0 : selectedSize.getWidth(),
                selectedSize == null ? 0 : selectedSize.getHeight(),
                selectedZoom,
                selectedCameraId
        );
    }

    private static String findWidestFrontCamera(CameraManager manager) throws CameraAccessException {
        String bestId = null;
        double bestScore = Double.NEGATIVE_INFINITY;

        for (String id : manager.getCameraIdList()) {
            CameraCharacteristics c = manager.getCameraCharacteristics(id);
            Integer facing = c.get(CameraCharacteristics.LENS_FACING);
            if (facing == null || facing != CameraCharacteristics.LENS_FACING_FRONT) continue;

            float minZoom = widestZoomRatio(c);
            double fov = estimatedHorizontalFov(c);
            // Prefer a logical camera that can actually zoom out below 1x, then the widest physical FOV.
            double score = (1.0 / Math.max(0.1, minZoom)) * 1000.0 + fov;
            if (score > bestScore) {
                bestScore = score;
                bestId = id;
            }
        }
        return bestId;
    }

    private static float widestZoomRatio(CameraCharacteristics characteristics) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Range<Float> range = characteristics.get(CameraCharacteristics.CONTROL_ZOOM_RATIO_RANGE);
            if (range != null && range.getLower() != null) {
                return Math.max(0.1f, range.getLower());
            }
        }
        return 1f;
    }

    private static double estimatedHorizontalFov(CameraCharacteristics c) {
        try {
            SizeF sensor = c.get(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE);
            float[] focals = c.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS);
            if (sensor == null || focals == null || focals.length == 0) return 0.0;
            float shortest = Float.MAX_VALUE;
            for (float focal : focals) if (focal > 0 && focal < shortest) shortest = focal;
            if (shortest == Float.MAX_VALUE) return 0.0;
            return 2.0 * Math.atan(sensor.getWidth() / (2.0 * shortest));
        } catch (Exception ignored) {
            return 0.0;
        }
    }

    private static Size chooseLargestSize(Size[] sizes) {
        if (sizes == null || sizes.length == 0) return null;
        Size best = sizes[0];
        long bestPixels = (long) best.getWidth() * best.getHeight();
        for (Size size : sizes) {
            long pixels = (long) size.getWidth() * size.getHeight();
            if (pixels > bestPixels) {
                best = size;
                bestPixels = pixels;
            }
        }
        return best;
    }

    private static boolean contains(int[] values, int target) {
        if (values == null) return false;
        for (int value : values) if (value == target) return true;
        return false;
    }

    private static String describe(Exception e) {
        String message = e.getMessage();
        return e.getClass().getSimpleName() + (message == null ? "" : ": " + message);
    }
}
