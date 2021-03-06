package com.elvis.android.insert_monitor.collect.aspectjx;

import android.annotation.TargetApi;
import android.os.Build;

import com.elvis.android.insert_monitor.collect.ISender;
import com.elvis.android.insert_monitor.obj.AbsInfo;

/**
 *
 * Created by conghongjie on 2018/6/24.
 */

@TargetApi(Build.VERSION_CODES.JELLY_BEAN)
public class IOCollector {

    private static ISender sender;
    private static boolean isStart = false;



    public static void start(ISender sender){
        IOCollector.sender = sender;
        isStart = true;
    }
    public static void stop(){
        isStart = false;
        sender = null;
    }

    public static boolean isStart() {
        return isStart;
    }

    public static void sendInfo(AbsInfo info, boolean isUpload){
        try {
            if (isStart && sender!=null){
                sender.send(info,isUpload);
            }
        }catch (Exception e){
        }
    }






}
