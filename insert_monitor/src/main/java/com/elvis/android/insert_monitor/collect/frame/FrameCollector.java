package com.elvis.android.insert_monitor.collect.frame;

import android.annotation.TargetApi;
import android.app.Activity;
import android.app.Application;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.view.Choreographer;

import com.elvis.android.insert_monitor.collect.AbsCollector;
import com.elvis.android.insert_monitor.obj.info.BlockInfo;
import com.elvis.android.insert_monitor.obj.info.SMInfo;
import com.elvis.android.insert_monitor.obj.info.StackInfo;

import java.util.ArrayList;

/**
 *
 * Created by conghongjie on 2018/6/24.
 */

@TargetApi(Build.VERSION_CODES.JELLY_BEAN)
public class FrameCollector extends AbsCollector {


    Application application;

    public FrameCollector(ISender sender, Application application) {
        super(sender);
        this.application = application;
    }


    private boolean isRun = false;

    @Override
    public boolean start(){
        if (!isRun){
            initHandler();
            if(handler!=null) {
                Choreographer.getInstance().postFrameCallback(frameCallback);// 监控frame
                handler.postDelayed(stackCollectRunnable, interval);// 栈采集任务
                application.registerActivityLifecycleCallbacks(activityLifecycleCallbacks);// Activity回退栈监听器
                isRun = true;
            }
        }
        return isRun;
    }

    @Override
    public boolean stop() {
        if(handler!=null){
            application.unregisterActivityLifecycleCallbacks(activityLifecycleCallbacks);// Activity回退栈监听器
            handler.removeCallbacks(stackCollectRunnable);// 栈采集任务
            Choreographer.getInstance().removeFrameCallback(frameCallback);// 监控frame
        }
        isRun = false;
        return true;
    }


    /**
     * 栈采集任务（当应用处于后台时，暂停采集）
     */
    private HandlerThread handlerThread = null;
    private Handler handler = null;
    private void initHandler(){
        if(handler==null){
            handlerThread = new HandlerThread("GTRChoreographerMonitorThread");
            handlerThread.start();
            handler = new Handler(handlerThread.getLooper());
        }
    }

    public int handlerThreadId = -1;
    private Thread uiThread = Looper.getMainLooper().getThread();
    private int interval = 30;//采集间隔--30ms
    private Runnable stackCollectRunnable = new Runnable() {
        @Override
        public void run() {
            if(handlerThreadId==-1){
                handlerThreadId = android.os.Process.myTid();
            }
            StringBuilder stackStringBuilder = new StringBuilder();
            for (StackTraceElement stackTraceElement : uiThread.getStackTrace()) {
                stackStringBuilder.append(stackTraceElement.toString()).append("&&rn&&");
            }
            long time = System.currentTimeMillis();
            //封装发送数据：
            String stack = stackStringBuilder.toString();
            StackInfo stackInfo = new StackInfo(time);
            stackInfo.time = time;
            stackInfo.stack = stack;
            onStack(stackInfo);
            handler.postDelayed(stackCollectRunnable, interval);
        }
    };

    private ArrayList<StackInfo> stackInfos = new ArrayList<>();
    private void onStack(StackInfo stackInfo){
        synchronized (stackInfos){
            stackInfos.add(stackInfo);
            while (stackInfos.size()>50){
                stackInfos.remove(0);
            }
        }
    }
    private ArrayList<StackInfo> getStackInfos(long start,long end){
        ArrayList<StackInfo> temps = new ArrayList<>();
        synchronized (stackInfos){
            for (int i=0;i<stackInfos.size();i++){
                StackInfo stackInfo = stackInfos.get(i);
                if (stackInfo.time>=start &&stackInfo.time<=end){
                    temps.add(stackInfo);
                }
            }
        }
        return temps;
    }



    /**
     * Frame采集任务（当应用处于后台时，暂停采集）
     */
    long nowSm = 0;
    ArrayList<Long> framesInOneSecond = new ArrayList<>();//1s内所有frame的时间
    //sm值：
    final long frameCollectSection = 200;                       //采集区间:200ms
    long lastCollectSmTime = 0;
    //大卡顿：
    final long bigBlockFrameLimit = 70;                         //大卡顿：Frame间隔限制
    long lastFrameTime = 0;
    //连续小卡顿：
    final long serialBlockSMLimit = 40;                         //连续小卡顿：sm限制
    long serialBlockStartTime = 0;
    int serialBlockFrameNum = 0;
    private Choreographer.FrameCallback frameCallback = new Choreographer.FrameCallback() {//系统绘帧回调
        public void doFrame(long frameTimeNanos) {
            //关闭栈采集
            handler.removeCallbacks(stackCollectRunnable);
            //数据处理：
            long time = System.currentTimeMillis();
            framesInOneSecond.add(time);
            nowSm++;
            while (framesInOneSecond.size()>0 && framesInOneSecond.get(0)<time-1000){
                framesInOneSecond.remove(0);
                nowSm--;
            }
            //sm：
            if (time-lastCollectSmTime>frameCollectSection){
                lastCollectSmTime = time;
                SMInfo smInfo = new SMInfo(time);
                smInfo.time = time;
                smInfo.sm = nowSm;
                sender.send(smInfo,false);
            }
            //大卡顿：
            if (lastFrameTime!=0 && time-lastFrameTime>bigBlockFrameLimit){
                BlockInfo blockInfo = new BlockInfo(time);
                blockInfo.startTime = lastFrameTime;
                blockInfo.endTime = time;
                blockInfo.frameNum = 1;
                blockInfo.stackInfos = getStackInfos(blockInfo.startTime,blockInfo.endTime);
                sender.send(blockInfo,false);

            }
            lastFrameTime = time;
            //连续卡顿：
            if(serialBlockStartTime==0 && nowSm<serialBlockSMLimit){
                serialBlockStartTime = time;
                serialBlockFrameNum = 0;
            }else if (serialBlockStartTime!=0){
                if (nowSm<serialBlockSMLimit){
                    serialBlockFrameNum++;
                }else {
                    BlockInfo blockInfo = new BlockInfo(time);
                    blockInfo.startTime = lastFrameTime;
                    blockInfo.endTime = time;
                    blockInfo.frameNum = serialBlockFrameNum;
                    blockInfo.stackInfos = getStackInfos(blockInfo.startTime,blockInfo.endTime);
                    sender.send(blockInfo,false);
                    serialBlockStartTime = 0;
                }
            }
            //开启下一个监控
            Choreographer.getInstance().postFrameCallback(frameCallback);
            handler.postDelayed(stackCollectRunnable,interval);
        }
    };


    /**
     * 前后台监听控制器：（当应用处于后台时，暂停采集）
     */
    Application.ActivityLifecycleCallbacks activityLifecycleCallbacks = new Application.ActivityLifecycleCallbacks() {
        @Override
        public void onActivityCreated(Activity activity, Bundle savedInstanceState) {

        }

        @Override
        public void onActivityStarted(Activity activity) {

        }

        @Override
        public void onActivityResumed(Activity activity) {
            if (isRun){
                if(handler!=null){
                    handler.removeCallbacks(stopDelayRunnable);
                    //stop
                    handler.removeCallbacks(stackCollectRunnable);
                    Choreographer.getInstance().removeFrameCallback(frameCallback);
                    //start
                    Choreographer.getInstance().postFrameCallback(frameCallback);
                    handler.postDelayed(stackCollectRunnable, interval);
                }
            }
        }

        @Override
        public void onActivityPaused(Activity activity) {
            if (isRun){
                if(handler!=null){
                    handler.postDelayed(stopDelayRunnable,1500);
                }
            }
        }

        @Override
        public void onActivityStopped(Activity activity) {

        }

        @Override
        public void onActivitySaveInstanceState(Activity activity, Bundle outState) {

        }

        @Override
        public void onActivityDestroyed(Activity activity) {

        }
    };

    private Runnable stopDelayRunnable = new Runnable() {
        @Override
        public void run() {
            //stop
            if(handler!=null){
                handler.removeCallbacks(stackCollectRunnable);
                Choreographer.getInstance().removeFrameCallback(frameCallback);
            }
        }
    };






}