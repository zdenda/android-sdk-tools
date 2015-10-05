package com.android.tools.fd.runtime;

import android.app.Activity;
import android.app.Application;
import android.net.LocalServerSocket;
import android.net.LocalSocket;
import android.support.annotation.NonNull;
import android.util.Log;

import com.android.build.gradle.internal.incremental.PatchesLoader;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.IOException;
import java.util.List;

import dalvik.system.DexClassLoader;

import static com.android.tools.fd.runtime.BootstrapApplication.LOG_TAG;
import static com.android.tools.fd.runtime.FileManager.CLASSES_DEX_3_SUFFIX;
import static com.android.tools.fd.runtime.FileManager.CLASSES_DEX_SUFFIX;

/**
 * Server running in the app listening for messages from the IDE and updating the
 * code and resources when provided
 */
public class Server {
    // ----------------------------------------------------------------------
    // NOTE: Keep all these communication constants (and message send/receive
    // logic) in sync with the corresponding values in the IDE plugin
    // ----------------------------------------------------------------------

    /**
     * Magic (random) number used to identify the protocol
     */
    public static final long PROTOCOL_IDENTIFIER = 0x35107124L;

    /**
     * Version of the protocol
     */
    public static final int PROTOCOL_VERSION = 2;

    /**
     * Message: sending patches
     */
    public static final int MESSAGE_PATCHES = 1;

    /**
     * Message: ping, send ack back
     */
    public static final int MESSAGE_PING = 2;

    /**
     * No updates
     */
    public static final int UPDATE_MODE_NONE = 0;

    /**
     * Patch changes directly, keep app running without any restarting
     */
    public static final int UPDATE_MODE_HOT_SWAP = 1;

    /**
     * Patch changes, restart activity to reflect changes
     */
    public static final int UPDATE_MODE_WARM_SWAP = 2;

    /**
     * Store change in app directory, restart app
     */
    public static final int UPDATE_MODE_COLD_SWAP = 3;

    private LocalServerSocket mServerSocket;
    private final Application mApplication;

    public static void create(@NonNull String packageName, @NonNull Application application) {
        new Server(packageName, application);
    }

    private Server(@NonNull String packageName, @NonNull Application application) {
        mApplication = application;
        try {
            mServerSocket = new LocalServerSocket(packageName);
            if (Log.isLoggable(LOG_TAG, Log.INFO)) {
                Log.i(LOG_TAG, "Starting server socket listening for package " + packageName
                        + " on " + mServerSocket.getLocalSocketAddress());
            }
        } catch (IOException e) {
            Log.e(LOG_TAG, "IO Error creating local socket at " + packageName, e);
            return;
        }
        startServer();

        if (Log.isLoggable(LOG_TAG, Log.INFO)) {
            Log.i(LOG_TAG, "Started server for package " + packageName);
        }
    }

    private void startServer() {
        try {
            Thread socketServerThread = new Thread(new SocketServerThread());
            socketServerThread.start();
        } catch (Throwable e) {
            // Make sure an exception doesn't cause the rest of the user's
            // onCreate() method to be invoked
            Log.i(LOG_TAG, "Fatal error starting server", e);
        }
    }

    private class SocketServerThread extends Thread {
        @Override
        public void run() {
            try {
                // We expect to bail out of this loop by an exception (SocketException when the
                // socket is closed by stop() above)
                while (true) {
                    LocalServerSocket serverSocket = mServerSocket;
                    if (serverSocket == null) {
                        break; // stopped?
                    }
                    LocalSocket socket = serverSocket.accept();

                    if (Log.isLoggable(LOG_TAG, Log.INFO)) {
                        Log.i(LOG_TAG, "Received connection from IDE: spawning connection thread");
                    }

                    SocketServerReplyThread socketServerReplyThread = new SocketServerReplyThread(
                            socket);
                    socketServerReplyThread.run();
                }
            } catch (IOException e) {
                if (Log.isLoggable(LOG_TAG, Log.INFO)) {
                    Log.i(LOG_TAG, "Fatal error accepting connection on local socket", e);
                }
            }
        }
    }

    private class SocketServerReplyThread extends Thread {
        private final LocalSocket mSocket;

        SocketServerReplyThread(LocalSocket socket) {
            mSocket = socket;
        }

        @Override
        public void run() {
            try {
                DataInputStream input = new DataInputStream(mSocket.getInputStream());
                DataOutputStream output = new DataOutputStream(mSocket.getOutputStream());
                try {
                    long magic = input.readLong();
                    if (magic != PROTOCOL_IDENTIFIER) {
                        Log.w(LOG_TAG, "Unrecognized header format "
                                + Long.toHexString(magic));
                        return;
                    }
                    int version = input.readInt();
                    if (version != PROTOCOL_VERSION) {
                        Log.w(LOG_TAG, "Mismatched protocol versions; app is "
                                + "using version " + PROTOCOL_VERSION + " and tool is using version "
                                + version);
                        return;
                    }

                    int message = input.readInt();
                    if (message == MESSAGE_PING) {
                        // Send an "ack" back to the IDE
                        output.writeBoolean(true);
                        return;
                    }
                    if (message != MESSAGE_PATCHES) {
                        if (Log.isLoggable(LOG_TAG, Log.ERROR)) {
                            Log.e(LOG_TAG, "Unexpected message type: " + message);
                        }
                        return;
                    }

                    List<ApplicationPatch> changes = ApplicationPatch.read(input);
                    if (changes == null) {
                        return;
                    }

                    boolean hasResources = hasResources(changes);
                    @UpdateMode int updateMode = input.readInt();
                    updateMode = handlePatches(changes, hasResources, updateMode);

                    // Send an "ack" back to the IDE; this is used for timing purposes only
                    output.writeBoolean(true);

                    restart(updateMode, hasResources);
                } finally {
                    try {
                        input.close();
                    } catch (IOException ignore) {
                    }
                    try {
                        output.close();
                    } catch (IOException ignore) {
                    }
                }
            } catch (IOException e) {
                if (Log.isLoggable(LOG_TAG, Log.INFO)) {
                    Log.i(LOG_TAG, "Fatal error receiving messages", e);
                }
            }
        }
    }

    private boolean hasResources(@NonNull List<ApplicationPatch> changes) {
        // Any non-code patch is a resource patch (normally resources.ap_ but could
        // also be individual resource files such as res/layout/activity_main.xml)
        for (ApplicationPatch change : changes) {
            String path = change.getPath();
            if (path.endsWith(CLASSES_DEX_SUFFIX) || path.endsWith(CLASSES_DEX_3_SUFFIX)) {
                continue;
            }
            return true;

        }
        return false;
    }

    @UpdateMode
    private int handlePatches(@NonNull List<ApplicationPatch> changes, boolean hasResources,
                              @UpdateMode int updateMode) {
        if (hasResources) {
            FileManager.startUpdate();
        }

        for (ApplicationPatch change : changes) {
            String path = change.getPath();
            if (path.endsWith(CLASSES_DEX_SUFFIX)) {
                handleColdSwapPatch(change);
            } else if (path.endsWith(CLASSES_DEX_3_SUFFIX)) {
                updateMode = handleHotSwapPatch(updateMode, change);
            } else {
                updateMode = handleResourcePatch(updateMode, change, path);
            }
        }

        if (hasResources) {
            FileManager.finishUpdate(true);
        }

        return updateMode;
    }

    @UpdateMode
    private int handleResourcePatch(@UpdateMode int updateMode, @NonNull ApplicationPatch patch,
                                    @NonNull String path) {
        if (Log.isLoggable(LOG_TAG, Log.INFO)) {
            Log.i(LOG_TAG, "Received resource changes (" + path + ")");
        }
        FileManager.writeAaptResources(path, patch.getBytes());
        //noinspection ResourceType
        updateMode = Math.max(updateMode, UPDATE_MODE_WARM_SWAP);
        return updateMode;
    }

    @UpdateMode
    private int handleHotSwapPatch(@UpdateMode int updateMode, @NonNull ApplicationPatch patch) {
        if (Log.isLoggable(LOG_TAG, Log.INFO)) {
            Log.i(LOG_TAG, "Received incremental code patch");
        }
        try {
            String dexFile = FileManager.writeTempDexFile(patch.getBytes());
            if (dexFile == null) {
                Log.e(LOG_TAG, "No file to write the code to");
                return updateMode;
            } else if (Log.isLoggable(LOG_TAG, Log.INFO)) {
                Log.i(LOG_TAG, "Reading live code from " + dexFile);
            }
            String nativeLibraryPath = FileManager.getNativeLibraryFolder().getPath();
            DexClassLoader dexClassLoader = new DexClassLoader(dexFile,
                    mApplication.getCacheDir().getPath(), nativeLibraryPath,
                    getClass().getClassLoader());

            // we should transform this process with an interface/impl
            Class<?> aClass = Class.forName("com.android.build.gradle.internal.incremental.AppPatchesLoaderImpl", true, dexClassLoader);
            try {
                if (Log.isLoggable(LOG_TAG, Log.INFO)) {
                    Log.i(LOG_TAG, "Got the patcher class " + aClass);
                }

                PatchesLoader loader = (PatchesLoader) aClass.newInstance();
                if (Log.isLoggable(LOG_TAG, Log.INFO)) {
                    Log.i(LOG_TAG, "Got the patcher instance " + loader);
                }
                String[] getPatchedClasses = (String[]) aClass.getDeclaredMethod("getPatchedClasses").invoke(loader);
                if (Log.isLoggable(LOG_TAG, Log.INFO)) {
                    Log.i(LOG_TAG, "Got the list of classes ");
                    for (String getPatchedClass : getPatchedClasses) {
                        Log.i(LOG_TAG, "class " + getPatchedClass);
                    }
                }
                if (!loader.load()) {
                    updateMode = UPDATE_MODE_COLD_SWAP;
                }
            } catch (Exception e) {
                Log.e(LOG_TAG, "Couldn't apply code changes", e);
                e.printStackTrace();
                updateMode = UPDATE_MODE_COLD_SWAP;
            }
        } catch (Throwable e) {
            Log.e(LOG_TAG, "Couldn't apply code changes", e);
            updateMode = UPDATE_MODE_COLD_SWAP;
        }
        return updateMode;
    }

    private void handleColdSwapPatch(@NonNull ApplicationPatch patch) {
        if (Log.isLoggable(LOG_TAG, Log.INFO)) {
            Log.i(LOG_TAG, "Received restart code patch");
        }
        FileManager.writeDexFile(patch.getBytes(), true);
    }

    private void restart(@UpdateMode int updateMode, boolean incrementalResources) {
        if (Log.isLoggable(LOG_TAG, Log.INFO)) {
            Log.i(LOG_TAG, "Finished loading changes; update mode =" + updateMode);
        }

        List<Activity> activities = Restarter.getActivities(false);

        if (updateMode == UPDATE_MODE_NONE || updateMode == UPDATE_MODE_HOT_SWAP) {
            if (Log.isLoggable(LOG_TAG, Log.INFO)) {
                Log.i(LOG_TAG, "Applying incremental code without restart");
            }
            return;
        }

        if (incrementalResources && updateMode == UPDATE_MODE_WARM_SWAP) {
            // Try to just replace the resources on the fly!
            File file = FileManager.getExternalResourceFile();

            if (Log.isLoggable(LOG_TAG, Log.INFO)) {
                Log.i(LOG_TAG, "About to update resource file=" + file +
                        ", activities=" + activities);
            }

            if (file != null) {
                String resources = file.getPath();
                MonkeyPatcher.monkeyPatchApplication(null, null, resources);
                MonkeyPatcher.monkeyPatchExistingResources(resources, activities);
            } else {
                Log.e(LOG_TAG, "No resource file found to apply");
                updateMode = UPDATE_MODE_COLD_SWAP;
            }
        }

        Activity activity = Restarter.getForegroundActivity();
        if (updateMode == UPDATE_MODE_WARM_SWAP) {
            if (activity != null) {
                if (Log.isLoggable(LOG_TAG, Log.INFO)) {
                    Log.i(LOG_TAG, "Restarting activity only!");
                }
                Restarter.restartActivityOnUiThread(activity);
                return;
            }

            if (Log.isLoggable(LOG_TAG, Log.INFO)) {
                Log.i(LOG_TAG, "No activity found, falling through to do a full app restart");
            }
            updateMode = UPDATE_MODE_COLD_SWAP;
        }

        if (updateMode != UPDATE_MODE_COLD_SWAP) {
            if (Log.isLoggable(LOG_TAG, Log.ERROR)) {
                Log.e(LOG_TAG, "Unexpected update mode: " + updateMode);
            }
            return;
        }

        if (Log.isLoggable(LOG_TAG, Log.INFO)) {
            Log.i(LOG_TAG, "Performing full app restart");
        }

        Restarter.restartApp(activities);
    }
}