package com.vox.castsink;

import android.app.Activity;
import android.os.Bundle;
import android.util.Log;
import android.view.Display;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.widget.TextView;
import java.io.IOException;
import java.util.List;

/**
 * 主屏载体：全屏 SurfaceView + 状态文本，启动 TCP Server（默认 27183，避开 57219/8888）。
 * 收到的 AccessUnit 逐帧喂 H264Decoder 渲染到本屏 Surface。
 */
public class MainActivity extends Activity
        implements SurfaceHolder.Callback, H264TcpReceiver.FrameListener {

    private static final String TAG = "MainActivity";
    public static final int TCP_PORT = 27183;

    private SurfaceView surfaceView;
    private TextView statusText;
    private final H264Decoder decoder = new H264Decoder();
    private H264TcpReceiver receiver;
    private volatile long receivedFrames;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        surfaceView = findViewById(R.id.cast_surface);
        statusText = findViewById(R.id.status_text);
        surfaceView.getHolder().addCallback(this);
        logDisplays();
    }

    private void logDisplays() {
        List<Display> displays = ClusterDisplayHelper.listPresentationDisplays(this);
        for (Display d : displays) {
            Log.i(TAG, "presentation display: " + ClusterDisplayHelper.describeDisplay(d));
        }
        if (!displays.isEmpty()) {
            Log.i(TAG, "cluster am command: "
                    + ClusterDisplayHelper.buildAmStartCommand(displays.get(0).getDisplayId()));
        }
    }

    @Override
    public void surfaceCreated(SurfaceHolder holder) {
        new Thread(() -> {
            try {
                decoder.attach(holder.getSurface());
                startServer();
            } catch (Exception e) {
                Log.e(TAG, "decoder attach failed", e);
                postStatus("decoder attach failed: " + e.getMessage());
            }
        }, "MainDecoderAttach").start();
    }

    @Override
    public void surfaceChanged(SurfaceHolder holder, int format, int width, int height) {
        Log.i(TAG, "surfaceChanged " + width + "x" + height);
    }

    @Override
    public void surfaceDestroyed(SurfaceHolder holder) {
        Log.i(TAG, "surfaceDestroyed");
        postStatus("surface destroyed");
    }

    private void startServer() {
        try {
            receiver = new H264TcpReceiver(TCP_PORT, this);
            receiver.start();
            postStatus("TCP server listening on :" + TCP_PORT);
        } catch (IOException e) {
            Log.e(TAG, "server start failed", e);
            postStatus("server start failed: " + e.getMessage());
        }
    }

    @Override
    public void onAccessUnit(byte[] au) {
        decoder.queue(au);
        long n = ++receivedFrames;
        if (n == 1 || n % 30 == 0) {
            postStatus("frames recv=" + n
                    + " queued=" + decoder.queuedFrames()
                    + " rendered=" + decoder.renderedFrames()
                    + " dropped=" + decoder.droppedFrames());
        }
    }

    private void postStatus(final String s) {
        runOnUiThread(() -> statusText.setText(s));
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (receiver != null) receiver.stop();
        decoder.release();
    }
}
