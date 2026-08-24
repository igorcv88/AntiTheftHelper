package com.igorcv.antithefthelper;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.graphics.ImageFormat;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.params.StreamConfigurationMap;
import android.media.Image;
import android.media.ImageReader;
import android.os.Handler;
import android.os.HandlerThread;
import android.util.Size;

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

        Result(File file, String error) {
            this.file = file;
            this.error = error;
        }

        boolean ok() {
            return file != null && file.isFile() && file.length() > 0;
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

        try {
            String cameraId = findFrontCamera(manager);
            if (cameraId == null) return new Result(null, "No front camera found");

            CameraCharacteristics characteristics = manager.getCameraCharacteristics(cameraId);
            StreamConfigurationMap map = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
            Size size = chooseSize(map == null ? null : map.getOutputSizes(ImageFormat.JPEG));
            if (size == null) return new Result(null, "No JPEG output size available");

            reader = ImageReader.newInstance(size.getWidth(), size.getHeight(), ImageFormat.JPEG, 1);
            ImageReader finalReader = reader;
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

            manager.openCamera(cameraId, new CameraDevice.StateCallback() {
                @Override
                public void onOpened(CameraDevice camera) {
                    cameraRef.set(camera);
                    try {
                        CaptureRequest.Builder request = camera.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE);
                        request.addTarget(finalReader.getSurface());
                        request.set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_AUTO);
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

            boolean completed = done.await(8, TimeUnit.SECONDS);
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
        return new Result(file, file != null ? null : errorRef.get());
    }

    private static String findFrontCamera(CameraManager manager) throws CameraAccessException {
        for (String id : manager.getCameraIdList()) {
            CameraCharacteristics c = manager.getCameraCharacteristics(id);
            Integer facing = c.get(CameraCharacteristics.LENS_FACING);
            if (facing != null && facing == CameraCharacteristics.LENS_FACING_FRONT) return id;
        }
        return null;
    }

    private static Size chooseSize(Size[] sizes) {
        if (sizes == null || sizes.length == 0) return null;
        Size best = sizes[0];
        long target = 1280L * 960L;
        long bestDistance = Math.abs((long) best.getWidth() * best.getHeight() - target);
        for (Size size : sizes) {
            long pixels = (long) size.getWidth() * size.getHeight();
            long distance = Math.abs(pixels - target);
            if (distance < bestDistance) {
                best = size;
                bestDistance = distance;
            }
        }
        return best;
    }

    private static String describe(Exception e) {
        String message = e.getMessage();
        return e.getClass().getSimpleName() + (message == null ? "" : ": " + message);
    }
}
