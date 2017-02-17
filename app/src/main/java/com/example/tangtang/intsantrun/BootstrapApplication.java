package com.example.tangtang.intsantrun;

import java.io.File;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.List;

import android.app.ActivityManager;
import android.app.Application;
import android.content.Context;
import android.content.ContextWrapper;
import android.os.Process;
import android.util.Log;


public class BootstrapApplication extends Application {
	public static final String LOG_TAG = "InstantRun";
	private String externalResourcePath;
	private Application realApplication;



	public BootstrapApplication() {

	}

	private void createResources(long apkModified) {
		FileManager.checkInbox();

		File file = FileManager.getExternalResourceFile();
		this.externalResourcePath = (file != null ? file.getPath() : null);

		if (file != null) {
			try {
				long resourceModified = file.lastModified();

				if ((apkModified == 0L) || (resourceModified <= apkModified)) {
					this.externalResourcePath = null;
				}
			} catch (Throwable t) {
				Log.e("InstantRun", "Failed to check patch timestamps", t);
			}
		}
	}

	private static void setupClassLoaders(Context context, String codeCacheDir,
			long apkModified) {
		List<String> dexList = FileManager.getDexList(context, apkModified);

		Class<Service> server = Service.class;
		Class<MonkeyPatcher> patcher = MonkeyPatcher.class;
		if (!dexList.isEmpty()) {

			ClassLoader classLoader = BootstrapApplication.class
					.getClassLoader();
			String nativeLibraryPath;
			try {
				nativeLibraryPath = (String) classLoader.getClass()
						.getMethod("getLdLibraryPath", new Class[0])
						.invoke(classLoader, new Object[0]);


			} catch (Throwable t) {
				Log.e("InstantRun", "Failed to determine native library path "
						+ t.getMessage());
				nativeLibraryPath = FileManager.getNativeLibraryFolder()
						.getPath();
			}
			IncrementalClassLoader.inject(classLoader, nativeLibraryPath,
					codeCacheDir, dexList);

			System.out.print("'");
		}

	}

	public static String join(char on, List<String> list) {
		StringBuilder stringBuilder = new StringBuilder();
		for (String item : list) {
			stringBuilder.append(item).append(on);
		}
		stringBuilder.deleteCharAt(stringBuilder.length() - 1);
		return stringBuilder.toString();
	}

	private void createRealApplication() {
		if (Appinfo.applicationClass != null) {

			try {
				Class<? extends Application> realClass = (Class<? extends Application>) Class
						.forName(Appinfo.applicationClass);

				Constructor<? extends Application> constructor = realClass
						.getConstructor(new Class[0]);
				this.realApplication = ((Application) constructor
						.newInstance(new Object[0]));

			} catch (Exception e) {
				throw new IllegalStateException(e);
			}
		} else {
			this.realApplication = new Application();
		}
	}

	protected void attachBaseContext(Context context) {
		if (!Appinfo.usingApkSplits) {
			String apkFile = context.getApplicationInfo().sourceDir;
			long apkModified = apkFile != null ? new File(apkFile)
					.lastModified() : 0L;
			createResources(apkModified);
			setupClassLoaders(context, context.getCacheDir().getPath(),
					apkModified);
		}
		createRealApplication();

		super.attachBaseContext(context);
		if (this.realApplication != null) {
			try {
				Method attachBaseContext = ContextWrapper.class
						.getDeclaredMethod("attachBaseContext",
								new Class[] { Context.class });

				attachBaseContext.setAccessible(true);
				attachBaseContext.invoke(this.realApplication,
						new Object[] { context });
			} catch (Exception e) {
				throw new IllegalStateException(e);
			}
		}
	}

	public void onCreate() {
		if (!Appinfo.usingApkSplits) {
			MonkeyPatcher.monkeyPatchApplication(this, this,
					this.realApplication, this.externalResourcePath);

			MonkeyPatcher.monkeyPatchExistingResources(this,
					this.externalResourcePath, null);
		} else {
			MonkeyPatcher.monkeyPatchApplication(this, this,
					this.realApplication, null);
		}
		super.onCreate();
		if (Appinfo.applicationId != null) {
			try {
				boolean foundPackage = false;
				int pid = Process.myPid();
				ActivityManager manager = (ActivityManager) getSystemService(Context.ACTIVITY_SERVICE);

				List<ActivityManager.RunningAppProcessInfo> processes = manager
						.getRunningAppProcesses();
				boolean startServer = false;
				if ((processes != null) && (processes.size() > 1)) {
					for (ActivityManager.RunningAppProcessInfo processInfo : processes) {
						if (Appinfo.applicationId
								.equals(processInfo.processName)) {
							foundPackage = true;
							if (processInfo.pid == pid) {
								startServer = true;
								break;
							}
						}
					}
					if ((!startServer) && (!foundPackage)) {
						startServer = true;
					}
				} else {
					startServer = true;
				}
				if (startServer) {
					Service.create(Appinfo.applicationId, this);
				}
			} catch (Throwable t) {

				Service.create(Appinfo.applicationId, this);
			}
		}
		if (this.realApplication != null) {
			this.realApplication.onCreate();
		}
	}
}