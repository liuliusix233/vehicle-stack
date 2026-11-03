package com.vox.castsink;

import android.app.Activity;
import android.os.Bundle;
import android.util.Log;
import android.view.SurfaceHolder;
import android.view.SurfaceView;

/**
 * 仪表屏渲染载体，对标 com.cadillac.carlink.car.cluster.CarlinkClusterActivity：
 * onCreate 仅 setContentView(new SurfaceView(this))，全屏 SurfaceView，无布局；
 * display id 运行时经 View.getDisplay().getDisplayId() 获取并打日志。
 * M1：本 Activity 自带独立 H264Decoder 实例（凯迪为 attachOutput 多输出，M1 简化为每屏一解码器）。
 */
public class ClusterCastActivity extends Activity implements SurfaceHolder.Callback {

    private static final String TAG = "ClusterCastActivity";

    private final H264Decoder decoder = new H264Decoder();
    private SurfaceView surfaceView;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        surfaceView = new SurfaceView(this);
        surfaceView.getHolder().addCallback(this);
        setContentView(surfaceView);
        Log.i(TAG, "[CLUSTER] activity created display=" + surfaceView.getDisplay().getDisplayId());
    }

    @Override
    protected void onResume() {
        super.onResume();
        Log.i(TAG, "[CLUSTER] activity resumed display="
                + getWindowManager().getDefaultDisplay().getDisplayId());
    }

    @Override
    public void surfaceCreated(SurfaceHolder holder) {
        Log.i(TAG, "[CLUSTER] surfaceCreated display=" + surfaceView.getDisplay().getDisplayId());
        new Thread(() -> {
            try {
                decoder.attach(holder.getSurface());
            } catch (Exception e) {
                Log.e(TAG, "[CLUSTER] decoder attach failed", e);
            }
        }, "ClusterDecoderAttach").start();
    }

    @Override
    public void surfaceChanged(SurfaceHolder holder, int format, int width, int height) {
        Log.i(TAG, "[CLUSTER] surfaceChanged " + width + "x" + height);
    }

    @Override
    public void surfaceDestroyed(SurfaceHolder holder) {
        // 对标 CarlinkClusterActivity：surface 销毁时注销该 surface 的渲染注册
        Log.i(TAG, "[CLUSTER] surfaceDestroyed, releasing decoder");
        decoder.release();
    }
}
