/*
 * <!--This file is part of LSPosed.
 *
 * LSPosed is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * LSPosed is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with LSPosed.  If not, see <https://www.gnu.org/licenses/>.
 *
 * Copyright (C) 2021 LSPosed Contributors-->
 */

package org.lsposed.manager.ui.fragment;

import android.app.Activity;
import android.app.Dialog;
import android.content.res.ColorStateList;
import android.os.Build;
import android.os.Bundle;
import android.system.ErrnoException;
import android.system.Os;
import android.system.OsConstants;
import android.text.method.LinkMovementMethod;
import android.util.TypedValue;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.RelativeLayout;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.text.HtmlCompat;
import androidx.fragment.app.DialogFragment;

import com.google.android.material.color.MaterialColors;

import org.lsposed.lspd.ILSPManagerService;
import org.lsposed.manager.BuildConfig;
import org.lsposed.manager.ConfigManager;
import org.lsposed.manager.Constants;
import org.lsposed.manager.R;
import org.lsposed.manager.databinding.DialogAboutBinding;
import org.lsposed.manager.databinding.FragmentHomeBinding;
import org.lsposed.manager.ui.dialog.BlurBehindDialogBuilder;
import org.lsposed.manager.ui.dialog.FlashDialogBuilder;
import org.lsposed.manager.ui.compose.MiuixNavigationController;
import org.lsposed.manager.util.NavUtil;
import org.lsposed.manager.util.ThemeUtil;
import org.lsposed.manager.util.UpdateUtil;
import org.lsposed.manager.util.chrome.LinkTransformationMethod;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.concurrent.atomic.AtomicBoolean;

import rikka.core.util.ClipboardUtils;
import rikka.material.app.LocaleDelegate;

public class HomeFragment extends BaseFragment {
    private FragmentHomeBinding binding;

    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
        binding = FragmentHomeBinding.inflate(inflater, container, false);
        setupToolbar(binding.toolbar, binding.clickView, R.string.app_name);
        binding.toolbar.setNavigationIcon(null);
        binding.toolbar.setOnClickListener(v -> showAbout());
        binding.clickView.setOnClickListener(v -> showAbout());
        binding.appBar.setLiftable(true);
        binding.nestedScrollView.getBorderViewDelegate().setBorderVisibilityChangedListener((top, oldTop, bottom, oldBottom) -> binding.appBar.setLifted(!top));
        MiuixNavigationController.applyFloatingBottomBarContentPadding(binding.nestedScrollView);

        binding.logsCard.setOnClickListener(v -> safeNavigate(R.id.logs_fragment));

        // Draw the static page now and fill the daemon-dependent rows from one
        // worker pass, so onCreateView waits for no binder round trip. The
        // result is applied once on the main thread and dropped if the view
        // has been destroyed in the meantime.
        boolean binderAlive = ConfigManager.isBinderAlive();
        boolean needUpdate = UpdateUtil.needUpdate();
        renderInitialState(requireActivity(), binderAlive, needUpdate);
        runAsync(() -> {
            var state = readHomeState(binderAlive);
            runOnUiThread(() -> {
                if (binding == null || !isAdded()) return;
                applyHomeState(requireActivity(), state);
            });
        });

        return binding.getRoot();
    }

    /**
     * Everything one worker pass gathers before the page renders its
     * daemon-dependent rows. Version/API metadata is stable for the bound
     * daemon, while activation/flags/dex2oat answers describe the current
     * state and are re-read on every fresh page.
     */
    private static final class HomeState {
        final boolean binderAlive;
        final boolean magiskInstalled; // binder dead
        final String versionName; // binder alive
        final int versionCode;
        final int apiVersion;
        final boolean dexObfuscateEnabled;
        final boolean sepolicyAbnormal;
        final boolean systemServerAbnormal;
        final int dex2oatCompatibility;
        final boolean dex2oatAbnormal;
        final boolean developer;

        HomeState(boolean binderAlive, boolean magiskInstalled,
                  String versionName, int versionCode, int apiVersion, boolean dexObfuscateEnabled,
                  boolean sepolicyAbnormal, boolean systemServerAbnormal,
                  int dex2oatCompatibility, boolean dex2oatAbnormal,
                  boolean developer) {
            this.binderAlive = binderAlive;
            this.magiskInstalled = magiskInstalled;
            this.versionName = versionName;
            this.versionCode = versionCode;
            this.apiVersion = apiVersion;
            this.dexObfuscateEnabled = dexObfuscateEnabled;
            this.sepolicyAbnormal = sepolicyAbnormal;
            this.systemServerAbnormal = systemServerAbnormal;
            this.dex2oatCompatibility = dex2oatCompatibility;
            this.dex2oatAbnormal = dex2oatAbnormal;
            this.developer = developer;
        }
    }

    private HomeState readHomeState(boolean binderAlive) {
        if (binderAlive) {
            // Each daemon answer is read exactly once; every row that shows
            // the same value reuses the field.
            String versionName = ConfigManager.getXposedVersionName();
            int versionCode = ConfigManager.getXposedVersionCode();
            int apiVersion = ConfigManager.getXposedApiVersion();
            boolean sepolicyAbnormal = !ConfigManager.isSepolicyLoaded();
            boolean systemServerAbnormal = !ConfigManager.systemServerRequested();
            int dex2oatCompatibility = ConfigManager.getDex2OatWrapperCompatibility();
            boolean dex2oatAbnormal = dex2oatCompatibility != ILSPManagerService.DEX2OAT_OK
                    && !ConfigManager.dex2oatFlagsLoaded();
            return new HomeState(true, false, versionName, versionCode, apiVersion,
                    ConfigManager.isDexObfuscateEnabled(), sepolicyAbnormal, systemServerAbnormal,
                    dex2oatCompatibility, dex2oatAbnormal, isDeveloper());
        }
        return new HomeState(false, ConfigManager.isMagiskInstalled(), null, 0, 0,
                false, false, false, ILSPManagerService.DEX2OAT_OK, false, false);
    }

    /**
     * The part of the page that needs no daemon answer: static device rows,
     * the palette frame, and "checking" placeholders where a binder read has
     * to land before the real status is known.
     */
    private void renderInitialState(Activity activity, boolean binderAlive, boolean needUpdate) {
        if (binderAlive) {
            applyStatusPalette(true);
            binding.statusTitle.setText(R.string.loading);
            binding.statusSummary.setText("");
            binding.statusIcon.setImageResource(R.drawable.ic_miuix_status_success);
            binding.warningCard.setVisibility(View.GONE);
            binding.developerWarningCard.setVisibility(View.GONE);
            binding.apiVersion.setText("--");
            binding.statusApi.setText("API --");
            binding.statusApiChip.setText(binding.statusApi.getText());
            binding.api.setText("--");
            binding.frameworkVersion.setText("--");
        } else {
            applyStatusPalette(false);
            // A refused peer is a distinct situation from having no daemon: the
            // binder arrived and the framework is plainly running, so "not
            // installed" would send a reader looking at the installation
            // instead of at the version skew. Both titles are local reads.
            boolean peerMismatch = Constants.getPeerMismatch() != null;
            binding.statusTitle.setText(peerMismatch ? R.string.version_mismatch : R.string.not_installed);
            binding.statusSummary.setText(peerMismatch ? R.string.version_mismatch_summary : R.string.not_install_summary);
            binding.updateCard.setVisibility(View.GONE);
            binding.warningCard.setVisibility(View.GONE);
            binding.developerWarningCard.setVisibility(View.GONE);
            binding.apiVersion.setText(R.string.not_installed);
            binding.statusApi.setText("API --");
            binding.statusApiChip.setText(binding.statusApi.getText());
            binding.api.setText(R.string.not_installed);
            binding.frameworkVersion.setText(R.string.not_installed);
        }
        if (needUpdate && binderAlive) {
            // needUpdate() is a local read, so the update card can render with
            // the initial pass; the worker result keeps it in sync.
            binding.updateTitle.setText(R.string.need_update);
            binding.updateSummary.setText(getString(R.string.please_update_summary));
            binding.statusIcon.setImageResource(R.drawable.ic_round_update_24);
            binding.updateBtn.setOnClickListener(v -> {
                if (UpdateUtil.canInstall()) {
                    new FlashDialogBuilder(activity, null).show();
                } else {
                    NavUtil.startURL(activity, getString(R.string.latest_url));
                }
            });
            binding.updateCard.setVisibility(View.VISIBLE);
        } else {
            binding.updateCard.setVisibility(View.GONE);
        }
        binding.logsCard.setVisibility(binderAlive ? View.VISIBLE : View.GONE);
        binding.managerPackageName.setText(activity.getPackageName());
        if (Build.VERSION.PREVIEW_SDK_INT != 0) {
            binding.systemVersion.setText(String.format(LocaleDelegate.getDefaultLocale(), "%1$s Preview (API %2$d)", Build.VERSION.CODENAME, Build.VERSION.SDK_INT));
        } else {
            binding.systemVersion.setText(String.format(LocaleDelegate.getDefaultLocale(), "%1$s (API %2$d)", Build.VERSION.RELEASE, Build.VERSION.SDK_INT));
        }
        binding.device.setText(getDevice());
        binding.systemAbi.setText(getSystemAbi());
        // Built at click time from the current rows, so a copy before the
        // worker result lands shows the placeholders instead of stale text.
        View.OnClickListener copyInfo = v -> {
            ClipboardUtils.put(activity, buildInfoString(activity));
            showHint(R.string.info_copied, false);
        };
        binding.copyInfo.setOnClickListener(copyInfo);
        binding.infoCard.setOnClickListener(copyInfo);
    }

    /**
     * One pass applies every gathered daemon answer to the rows that wait for
     * it. Runs on the main thread with a live view.
     */
    private void applyHomeState(Activity activity, HomeState state) {
        if (state.binderAlive) {
            if (state.sepolicyAbnormal || state.systemServerAbnormal || state.dex2oatAbnormal) {
                binding.statusTitle.setText(R.string.partial_activated);
                binding.statusIcon.setImageResource(R.drawable.ic_round_warning_24);
                binding.warningCard.setVisibility(View.VISIBLE);
                if (state.sepolicyAbnormal) {
                    binding.warningTitle.setText(R.string.selinux_policy_not_loaded_summary);
                    binding.warningSummary.setText(HtmlCompat.fromHtml(getString(R.string.selinux_policy_not_loaded), HtmlCompat.FROM_HTML_MODE_LEGACY));
                }
                if (state.systemServerAbnormal) {
                    binding.warningTitle.setText(R.string.system_inject_fail_summary);
                    binding.warningSummary.setText(HtmlCompat.fromHtml(getString(R.string.system_inject_fail), HtmlCompat.FROM_HTML_MODE_LEGACY));
                }
                if (state.dex2oatAbnormal) {
                    binding.warningTitle.setText(R.string.system_prop_incorrect_summary);
                    binding.warningSummary.setText(HtmlCompat.fromHtml(getString(R.string.system_prop_incorrect), HtmlCompat.FROM_HTML_MODE_LEGACY));
                }
            } else {
                binding.warningCard.setVisibility(View.GONE);
                binding.statusTitle.setText(R.string.activated);
                binding.statusIcon.setImageResource(R.drawable.ic_miuix_status_success);
            }
            binding.statusSummary.setText(String.format(LocaleDelegate.getDefaultLocale(), "%s (%d)",
                    state.versionName, state.versionCode));
            binding.developerWarningCard.setVisibility(state.developer ? View.VISIBLE : View.GONE);
            binding.apiVersion.setText(String.valueOf(state.apiVersion));
            binding.statusApi.setText(String.format(LocaleDelegate.getDefaultLocale(), "API %d", state.apiVersion));
            binding.statusApiChip.setText(binding.statusApi.getText());
            binding.api.setText(state.dexObfuscateEnabled ? R.string.enabled : R.string.not_enabled);
            binding.frameworkVersion.setText(String.format(LocaleDelegate.getDefaultLocale(), "%1$s (%2$d)", state.versionName, state.versionCode));
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
                binding.dex2oatWrapper.setText(String.format(LocaleDelegate.getDefaultLocale(), "%s (%s)", getString(R.string.unsupported), getString(R.string.android_version_unsatisfied)));
            } else switch (state.dex2oatCompatibility) {
                case ILSPManagerService.DEX2OAT_OK ->
                        binding.dex2oatWrapper.setText(R.string.supported);
                case ILSPManagerService.DEX2OAT_CRASHED ->
                        binding.dex2oatWrapper.setText(String.format(LocaleDelegate.getDefaultLocale(), "%s (%s)", getString(R.string.unsupported), getString(R.string.crashed)));
                case ILSPManagerService.DEX2OAT_MOUNT_FAILED ->
                        binding.dex2oatWrapper.setText(String.format(LocaleDelegate.getDefaultLocale(), "%s (%s)", getString(R.string.unsupported), getString(R.string.mount_failed)));
                case ILSPManagerService.DEX2OAT_SELINUX_PERMISSIVE ->
                        binding.dex2oatWrapper.setText(String.format(LocaleDelegate.getDefaultLocale(), "%s (%s)", getString(R.string.unsupported), getString(R.string.selinux_permissive)));
                case ILSPManagerService.DEX2OAT_SEPOLICY_INCORRECT ->
                        binding.dex2oatWrapper.setText(String.format(LocaleDelegate.getDefaultLocale(), "%s (%s)", getString(R.string.unsupported), getString(R.string.sepolicy_incorrect)));
            }
        } else {
            if (state.magiskInstalled) {
                binding.updateTitle.setText(R.string.install);
                binding.updateSummary.setText(R.string.install_summary);
                binding.statusIcon.setImageResource(R.drawable.ic_round_error_outline_24);
                binding.updateBtn.setOnClickListener(v -> {
                    if (UpdateUtil.canInstall()) {
                        new FlashDialogBuilder(activity, null).show();
                    } else {
                        NavUtil.startURL(activity, getString(R.string.install_url));
                    }
                });
                binding.updateCard.setVisibility(View.VISIBLE);
            } else {
                binding.updateCard.setVisibility(View.GONE);
            }
        }
    }

    private String buildInfoString(Activity activity) {
        return activity.getString(R.string.info_api_version) +
                "\n" +
                binding.apiVersion.getText() +
                "\n\n" +
                activity.getString(R.string.settings_xposed_api_call_protection) +
                "\n" +
                binding.api.getText() +
                "\n\n" +
                activity.getString(R.string.info_dex2oat_wrapper) +
                "\n" +
                binding.dex2oatWrapper.getText() +
                "\n\n" +
                activity.getString(R.string.info_framework_version) +
                "\n" +
                binding.frameworkVersion.getText() +
                "\n\n" +
                activity.getString(R.string.info_manager_package_name) +
                "\n" +
                binding.managerPackageName.getText() +
                "\n\n" +
                activity.getString(R.string.info_system_version) +
                "\n" +
                binding.systemVersion.getText() +
                "\n\n" +
                activity.getString(R.string.info_device) +
                "\n" +
                binding.device.getText() +
                "\n\n" +
                activity.getString(R.string.info_system_abi) +
                "\n" +
                binding.systemAbi.getText();
    }

    private void applyStatusPalette(boolean active) {
        boolean miuix = ThemeUtil.isMiuixStyle();
        // The active palette comes from per-skin attrs (MIUIX success colors,
        // M3E primary container); the inactive one stays the error container.
        int background = active
                ? MaterialColors.getColor(binding.status, R.attr.statusContainer)
                : MaterialColors.getColor(binding.status, com.google.android.material.R.attr.colorErrorContainer);
        int foreground = active
                ? MaterialColors.getColor(binding.status, R.attr.statusOnContainer)
                : MaterialColors.getColor(binding.status, com.google.android.material.R.attr.colorOnErrorContainer);
        int accent = active
                ? MaterialColors.getColor(binding.status, R.attr.statusAccent)
                : foreground;
        // M3E relays the card to a small filled check beside the title plus
        // an API chip; MIUIX keeps the watermark icon and the plain API text.
        binding.statusIcon.setVisibility(miuix ? View.VISIBLE : View.GONE);
        binding.statusIconSmall.setVisibility(!miuix && active ? View.VISIBLE : View.GONE);
        binding.statusApiChip.setVisibility(!miuix && active ? View.VISIBLE : View.GONE);
        binding.statusApi.setVisibility(miuix || !active ? View.VISIBLE : View.GONE);
        RelativeLayout.LayoutParams titleParams = (RelativeLayout.LayoutParams) binding.statusTitle.getLayoutParams();
        RelativeLayout.LayoutParams summaryParams = (RelativeLayout.LayoutParams) binding.statusSummary.getLayoutParams();
        if (miuix) {
            titleParams.removeRule(RelativeLayout.RIGHT_OF);
            summaryParams.removeRule(RelativeLayout.RIGHT_OF);
        } else {
            titleParams.addRule(RelativeLayout.RIGHT_OF, R.id.status_icon_small);
            summaryParams.addRule(RelativeLayout.RIGHT_OF, R.id.status_icon_small);
            binding.statusIconSmall.setImageResource(R.drawable.ic_m3e_status_check_small);
        }
        // M3E keeps the card as compact as its content: without the bottom
        // API anchor the MIUIX min height would leave the texts top-heavy.
        int minHeight = miuix ? getResources().getDimensionPixelSize(R.dimen.lsposed_miuix_status_min_height) : 0;
        binding.status.setMinimumHeight(minHeight);
        binding.statusContent.setMinimumHeight(minHeight);
        binding.statusTitle.setTextSize(TypedValue.COMPLEX_UNIT_SP, miuix ? 22f : 18f);
        binding.statusSummary.setTextSize(TypedValue.COMPLEX_UNIT_SP, miuix ? 16f : 14f);
        binding.logsBadge.setVisibility(miuix ? View.GONE : View.VISIBLE);
        binding.status.setCardBackgroundColor(background);
        binding.statusTitle.setTextColor(foreground);
        binding.statusSummary.setTextColor(foreground);
        binding.statusApi.setTextColor(foreground);
        binding.statusIcon.setImageTintList(ColorStateList.valueOf(accent));
    }

    private String getSystemAbi() {
        long pageSize = Os.sysconf(OsConstants._SC_PAGESIZE);
        if (pageSize > 0) {
            return String.format(LocaleDelegate.getDefaultLocale(), "%s (%dk)", Build.SUPPORTED_ABIS[0], pageSize / 1024);
        }
        return Build.SUPPORTED_ABIS[0];
    }

    private String getDevice() {
        String manufacturer = Character.toUpperCase(Build.MANUFACTURER.charAt(0)) + Build.MANUFACTURER.substring(1);
        if (!Build.BRAND.equals(Build.MANUFACTURER)) {
            manufacturer += " " + Character.toUpperCase(Build.BRAND.charAt(0)) + Build.BRAND.substring(1);
        }
        manufacturer += " " + Build.MODEL + " ";
        return manufacturer;
    }

    private boolean isDeveloper() {
        var developer = new AtomicBoolean(false);
        var pids = Paths.get("/data/local/tmp/.studio/ipids");
        try (var dir = Files.list(pids)) {
            dir.findFirst().ifPresent(name -> {
                var pid = Integer.parseInt(name.getFileName().toString());
                try {
                    Os.kill(pid, 0);
                    developer.set(true);
                } catch (ErrnoException e) {
                    if (e.errno == OsConstants.ESRCH) {
                        try {
                            Files.delete(name);
                        } catch (IOException ignored) {
                        }
                    } else {
                        developer.set(true);
                    }
                }
            });
        } catch (IOException e) {
            return false;
        }
        return developer.get();
    }

    public static class AboutDialog extends DialogFragment {
        @NonNull
        @Override
        public Dialog onCreateDialog(@Nullable Bundle savedInstanceState) {
            DialogAboutBinding binding = DialogAboutBinding.inflate(getLayoutInflater(), null, false);
            binding.designAboutTitle.setText(R.string.app_name);
            binding.designAboutInfo.setMovementMethod(LinkMovementMethod.getInstance());
            binding.designAboutInfo.setTransformationMethod(new LinkTransformationMethod(requireActivity()));
            binding.designAboutInfo.setText(HtmlCompat.fromHtml(getString(
                    R.string.about_view_source_code,
                    "<b><a href=\"https://github.com/re-zero001/LSPosed-Irena\">GitHub</a></b>",
                    "<b><a href=\"https://t.me/lsposed-irena\">Telegram</a></b>"), HtmlCompat.FROM_HTML_MODE_LEGACY));
            binding.designAboutVersion.setText(String.format(LocaleDelegate.getDefaultLocale(), "%s (%d)", BuildConfig.VERSION_NAME, BuildConfig.VERSION_CODE));
            return new BlurBehindDialogBuilder(requireContext())
                    .setView(binding.getRoot()).create();
        }
    }

    private void showAbout() {
        new AboutDialog().show(getChildFragmentManager(), "about");
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        binding = null;
    }
}
