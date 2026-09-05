package org.lsposed.lspd;

import rikka.parcelablelist.ParcelableListSlice;
import org.lsposed.lspd.models.UserInfo;
import org.lsposed.lspd.models.Application;


interface ILSPManagerService {
    /**
     * The version of this interface's shape that the daemon speaks. Transaction ids follow
     * declaration order, so a daemon built from a different revision of this file maps the same
     * numbers to different methods, and every call would land somewhere plausible and wrong -
     * which is worse than failing, because nothing throws. getProtocolVersion is declared first
     * and is therefore FIRST_CALL_TRANSACTION in every revision, so it is the one question both
     * ends are guaranteed to agree on; the manager asks it before anything else and refuses to
     * bind on a mismatch. Bump this in the same commit that changes the method set.
     */
    const int PROTOCOL_VERSION = 1;

    /**
     * Whether the manager and the daemon agree on this interface. Declared first so its
     * transaction code is stable across every revision of this file.
     */
    int getProtocolVersion();

    String getApi();

    ParcelableListSlice<PackageInfo> getInstalledPackagesFromAllUsers(int flags, boolean filterNoProcess);

    List<Application> enabledModules();

    boolean enableModule(String packageName, int userId);

    boolean disableModule(String packageName, int userId);

    boolean setModuleScope(String packageName, in List<Application> scope);

    List<Application> getModuleScope(String packageName);

    boolean isVerboseLog();

    void setVerboseLog(boolean enabled);

    ParcelFileDescriptor getVerboseLog();

    ParcelFileDescriptor getModulesLog();

    int getXposedVersionCode();

    String getXposedVersionName();

    int getXposedApiVersion();

    boolean clearLogs(boolean verbose);

    PackageInfo getPackageInfo(String packageName, int flags, int uid);

    void forceStopPackage(String packageName, int userId);

    void reboot();

    boolean uninstallPackage(String packageName, int userId);

    boolean isSepolicyLoaded();

    List<UserInfo> getUsers();

    int installExistingPackageAsUser(String packageName, int userId);

    boolean systemServerRequested();

    int startActivityAsUserWithFeature(in Intent intent, int userId);

    ParcelableListSlice<ResolveInfo> queryIntentActivitiesAsUser(in Intent intent, int flags, int userId);

    boolean dex2oatFlagsLoaded();

    void setHiddenIcon(boolean hide);

    void getLogs(in ParcelFileDescriptor zipFd);

    void restartFor(in Intent intent);

    oneway void flashZip(String zipPath, in ParcelFileDescriptor outputStream);

    boolean optimizePackage(String packageName);

    List<String> getDenyListPackages();

    boolean getDexObfuscate();

    void setDexObfuscate(boolean enable);

    /**
     * One of the DEX2OAT_* constants above. Also DEX2OAT_OK below Android 10, where there is no
     * wrapper at all: the daemon only starts the machinery that would report on one from Android
     * 10, and answers with that literal before then. "Working" and "not applicable on this
     * release" are therefore the same value, so a caller must not render it as a supported
     * feature without checking the release first - the manager drops the row entirely below
     * Android 10 rather than claim anything about it.
     */
    int getDex2OatWrapperCompatibility();

    boolean enableStatusNotification();

    void setEnableStatusNotification(boolean enable);

    void removeBlockedScopeRequest(String packageName, int userId);

    const int DEX2OAT_OK = 0;

    const int DEX2OAT_CRASHED = 1;

    const int DEX2OAT_MOUNT_FAILED = 2;

    const int DEX2OAT_SELINUX_PERMISSIVE = 3;

    const int DEX2OAT_SEPOLICY_INCORRECT = 4;
}
