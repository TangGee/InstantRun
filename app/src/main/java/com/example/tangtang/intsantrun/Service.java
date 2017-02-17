package com.example.tangtang.intsantrun;

import android.app.Activity;
import android.app.Application;
import android.net.LocalServerSocket;
import android.net.LocalSocket;
import android.util.Log;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.IOException;
import java.lang.reflect.Method;
import java.math.BigInteger;
import java.util.List;

import dalvik.system.DexClassLoader;


/**
 * Created by tangtang on 17/2/13.
 */

public class Service {

    private static final String TAG ="tag";

    private static final boolean RESTART_LOCAL = false;
    private static final boolean POST_ALIVE_STATUS = false;

    private LocalServerSocket mServerSocket;
    private final Application mApplication;
    private static int sWrongTokenCount;

    public static void create(String packageName,Application application){
        new Service(packageName,application);
    }

    private Service(String packageName,Application application){
        this.mApplication = application;

        try{
            this.mServerSocket = new LocalServerSocket(packageName);
            Log.e(TAG,"new loacl server");

        }catch (IOException e){
            Log.e(TAG,"io error",e);
            return;
        }
        startServer();
        Log.e(TAG,"start loacl server");
    }

    private void startServer() {
        try{
            Thread socketServerThread = new Thread(new SocketServerThread());
            socketServerThread.start();
        }catch (Throwable e){
            Log.e(TAG," fatal start socket thread");
        }

    }

    private class SocketServerThread extends Thread{
        private SocketServerThread(){}

        @Override
        public void run() {
            try{
                for (;;){
                    LocalServerSocket serverSocket = Service.this.mServerSocket;
                    LocalSocket socket = serverSocket.accept();
                    Log.e(TAG,"accept ");
                    Service.SocketServerReplyThread socketServerReplyThread =
                            new SocketServerReplyThread(socket);
                    socketServerReplyThread.run();
                    if (Service.sWrongTokenCount > 50){
                        Log.v("InstantRun",
                                "Stopping server: too many wrong token connections");

                        Service.this.mServerSocket.close();;
                        break;
                    }
                }
            }catch (Throwable e){
                Log.e(TAG,"peocee connect ",e);
            }
        }
    }

    private class SocketServerReplyThread extends Thread {
        private final LocalSocket mSocket;

        SocketServerReplyThread(LocalSocket socket){
            this.mSocket = socket;
        }

        public void run(){
            try {

                DataInputStream inputStream = new DataInputStream(this.mSocket.getInputStream());
                DataOutputStream outPutStream  = new DataOutputStream(this.mSocket.getOutputStream());
                try{
                    handle(inputStream,outPutStream);
                }finally {
                    if (inputStream!=null)
                    inputStream.close();
                    if (outPutStream!=null)
                        outPutStream.close();
                }
            } catch (IOException e){
             Log.e(TAG,"fatal error receiving message");
            }
        }

        private void handle(DataInputStream inputStream, DataOutputStream outPutStream) throws IOException {
            long magic = inputStream.readLong();

            if (magic != 12345678L){
                Log.e(TAG,"error magic");
                return;
            }

            int version = inputStream.readInt();
            outPutStream.writeInt(4);
            if (version !=4){
                Log.w(TAG,
                        "Mismatched protocol versions; app is using version 4 and tool is using version "
                                + version);
            }else {
                int message;

                for (;;){
                    message = inputStream.readInt();
                    switch (message){
                        case 7://结束
                            Log.e(TAG,"Received EOF from the IDE");
                            break;

                        case 2: //ping 是否有前台activity
                            boolean active = Restarter
                                    .getForegroundActivity(Service.this.mApplication) != null;
                            outPutStream.writeBoolean(active);
                            Log.v("InstantRun",
                                    "Received Ping message from the IDE; returned active = "
                                            + active);
                            break;

                        case 3: //文件大小
                            String path = inputStream.readUTF();
                            long size = FileManager.getFileSize(path);
                            outPutStream.writeLong(size);
                            Log.v("InstantRun", "Received path-exists(" + path
                                    + ") from the " + "IDE; returned size="
                                    + size);

                            break;

                        case 4: //校验和
                            long begin = System.currentTimeMillis();
                            path = inputStream.readUTF();
                            byte[] checkSum  = FileManager.getCheckSum(path);
                            if (checkSum !=null){
                                outPutStream.writeInt(checkSum.length);
                                outPutStream.write(checkSum);
                                long end = System.currentTimeMillis();
                                String hash = new BigInteger(1, checkSum)
                                        .toString(16);
                                Log.v("InstantRun", "Received checksum(" + path
                                        + ") from the " + "IDE: took "
                                        + (end - begin) + "ms to compute "
                                        + hash);
                            }else{
                                outPutStream.writeInt(0);
                                Log.v("InstantRun", "Received checksum(" + path
                                        + ") from the "
                                        + "IDE: returning <null>");
                            }

                            break;
                        case 1: //1
                            if (!authenticate(inputStream)) {
                                return;
                            }
                            List<ApplicationPatch> changes = ApplicationPatch
                                    .read(inputStream);
                            if (changes != null) {
                                boolean hasResources = Service.hasResources(changes);
                                int updateMode = inputStream.readInt();
                                updateMode = Service.this.handlePacthes(changes,
                                        hasResources, updateMode);

                                boolean showToast = inputStream.readBoolean();

                                outPutStream.writeBoolean(true);

                                Service.this.restart(updateMode, hasResources,
                                        showToast);
                            }
                            break;
                        case 5:
                            if (!authenticate(inputStream)) {
                                return;
                            }
                            Activity activity = Restarter
                                    .getForegroundActivity(Service.this.mApplication);
                            if (activity != null) {
                                Log.v("InstantRun",
                                        "Restarting activity per user request");
                                Restarter.restartActivityOnUiThread(activity);
                            }
                            break;

                        case 6: //show toast
                            String text = inputStream.readUTF();
                            Activity foreground = Restarter.getForegroundActivity(Service.this.mApplication);
                            if (foreground!=null){
                                Restarter.showToast(foreground,text);
                            }else {
                                Log.v("InstantRun",
                                        "Couldn't show toast (no activity) : "
                                                + text);

                            }

                            break;

                    }
                }
            }


        }

        private boolean authenticate(DataInputStream inputStream) throws IOException {
            long token  = inputStream.readLong();
            if (token!=Appinfo.token){
                Log.w("InstantRun",
                        "Mismatched identity token from client; received "
                                + token + " and expected " + Appinfo.token);
            }
            return true;
        }
    }


    private static boolean isResourcePath(String path){
        return (path.equals("resources.ap_")) || (path.startsWith("res/"));
    }


    private static boolean hasResources(List<ApplicationPatch> changes) {

        for (ApplicationPatch patch : changes){
            String path = patch.getPath();
            if (isResourcePath(path)){
                return true;
            }
        }
        return false;
    }



    private int handlePacthes(List<ApplicationPatch> changes, boolean hasResource, int updateMode) {
        if (hasResource){
            FileManager.startUpdate();
        }

        for (ApplicationPatch change : changes){
            String path = change.getPath();
            if (path.endsWith(".dex")){
                handleColdSwapPath(change);

                boolean canHotSwap = false;
                for (ApplicationPatch c: changes){
                    if (c.getPath().equals("classes.dex.3")){
                        canHotSwap = true;
                        break;
                    }
                }

                if (!canHotSwap){
                    updateMode = 3;
                }
            }else if (path.equals("classes.dex.3")){
                updateMode = handlehotSwapPath(updateMode,change);
            } else  if (isResourcePath(path)){
                updateMode = handleResourcePatch(updateMode, change, path);
            }
        }

        if (hasResource){
            FileManager.finishUpdate(true);
        }

        return updateMode;
    }

    private int handleResourcePatch(int updateMode, ApplicationPatch change, String path) {


        FileManager.writeAaptResources(path, path.getBytes());

        updateMode = Math.max(updateMode, 2);
        return updateMode;
    }

    private int handlehotSwapPath(int updateMode, ApplicationPatch patch) {

        try {
            Log.v("InstantRun", "Received incremental code patch");
            String dexFile = FileManager.writeTempDexFile(patch.getBytes());
            if (dexFile == null) {
                Log.e("InstantRun", "No file to write the code to");
                return updateMode;
            }
            String nativeLibraryPath = FileManager.getNativeLibraryFolder()
                    .getPath();

            DexClassLoader dexClassLoader = new DexClassLoader(dexFile,
                    this.mApplication.getCacheDir().getPath(),nativeLibraryPath,
                    getClass().getClassLoader());

            Class<?> aClass = Class.forName(
                    "com.android.tools.fd.runtime.AppPatchesLoaderImpl", true,
                    dexClassLoader);

            try{
                PatchesLoader loader = (PatchesLoader) aClass.newInstance();
                String[] getPatchedClasses = (String[]) aClass
                        .getDeclaredMethod("getPatchedClasses", new Class[0])
                        .invoke(loader, new Object[0]);
                if (!loader.load()){
                    updateMode = 3;
                }

            } catch (Exception e){
                Log.e("InstantRun", "Couldn't apply code changes", e);
                e.printStackTrace();
                updateMode = 3;
            }

        }catch (Throwable e){
            Log.e("InstantRun", "Couldn't apply code changes", e);
            updateMode = 3;
        }

        return updateMode;
    }

    private void handleColdSwapPath(ApplicationPatch patch) {
        if (patch.path.startsWith("slice-")) {
            File file = FileManager.writeDexShard(patch.getBytes(), patch.path);
            Log.v("InstantRun", "Received dex shard " + file);

        }
    }


    private void restart(int updateMode, boolean incrementalResources, boolean toast) {
        Log.v("InstantRun", "Finished loading changes; update mode ="
                + updateMode);
        if ((updateMode == 0) || (updateMode == 1)) {
            Log.v("InstantRun", "Applying incremental code without restart");

            if (toast) {
                Activity foreground = Restarter
                        .getForegroundActivity(this.mApplication);
                if (foreground != null) {
                    Restarter.showToast(foreground,
                            "Applied code changes without activity restart");
                } else  {
                    Log.v("InstantRun",
                            "Couldn't show toast: no activity found");
                }
            }
            return;
        }
        List<Activity> activities = Restarter.getActivities(this.mApplication,
                false);
        if ((incrementalResources) && (updateMode == 2)) {
            File file = FileManager.getExternalResourceFile();
            Log.v("InstantRun", "About to update resource file=" + file
                    + ", activities=" + activities);
            if (file != null) {
                String resources = file.getPath();
                MonkeyPatcher.monkeyPatchApplication(this.mApplication, null,
                        null, resources);
                MonkeyPatcher.monkeyPatchExistingResources(this.mApplication,
                        resources, activities);
            } else {
                Log.e("InstantRun", "No resource file found to apply");
                updateMode = 3;
            }
        }
        Activity activity = Restarter.getForegroundActivity(this.mApplication);
        if (updateMode == 2) {
            if (activity != null) {
                Log.v("InstantRun", "Restarting activity only!");

                boolean handledRestart = false;
                try {
                    Method method = activity.getClass().getMethod(
                            "onHandleCodeChange", new Class[] { Long.TYPE });
                    Object result = method.invoke(activity,
                            new Object[] { Long.valueOf(0L) });
                    Log.v("InstantRun", "Activity " + activity
                            + " provided manual restart method; return "
                            + result);
                    if (Boolean.TRUE.equals(result)) {
                        handledRestart = true;
                        if (toast) {
                            Restarter.showToast(activity, "Applied changes");
                        }
                    }
                } catch (Throwable ignore) {
                }
                if (!handledRestart) {
                    if (toast) {
                        Restarter.showToast(activity,
                                "Applied changes, restarted activity");
                    }
                    Restarter.restartActivityOnUiThread(activity);
                }
                return;
            }
            Log.v("InstantRun",
                    "No activity found, falling through to do a full app restart");
            updateMode = 3;
        }
        if (updateMode != 3) {
            Log.e("InstantRun", "Unexpected update mode: " + updateMode);
            return;
        }
        Log.v("InstantRun",
                "Waiting for app to be killed and restarted by the IDE...");
    }


}
