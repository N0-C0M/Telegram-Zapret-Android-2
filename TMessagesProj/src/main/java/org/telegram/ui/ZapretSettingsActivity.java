package org.telegram.ui;

import android.content.Context;
import android.graphics.Typeface;
import android.os.Bundle;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.NotificationCenter;
import org.telegram.messenger.R;
import org.telegram.messenger.ZapretConfig;
import org.telegram.messenger.ZapretDiagnosticsController;
import org.telegram.messenger.browser.Browser;
import org.telegram.tgnet.ConnectionsManager;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.ActionBar.ActionBar;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.ActionBar.ThemeDescription;
import org.telegram.ui.Cells.HeaderCell;
import org.telegram.ui.Cells.ShadowSectionCell;
import org.telegram.ui.Cells.TextCheckCell;
import org.telegram.ui.Cells.TextInfoPrivacyCell;
import org.telegram.ui.Cells.TextSettingsCell;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.RecyclerListView;
import org.telegram.ui.Components.voip.VoIPHelper;

import java.util.ArrayList;

public class ZapretSettingsActivity extends BaseFragment implements NotificationCenter.NotificationCenterDelegate {

    private RecyclerListView listView;
    private ListAdapter listAdapter;

    private int rowCount;
    private int generalHeaderRow;
    private int enabledRow;
    private int strategyRow;
    private int runtimeRow;
    private int previewRow;
    private int testsHeaderRow;
    private int connectionTestRow;
    private int messageTestRow;
    private int imageTestRow;
    private int callTestRow;
    private int logRow;
    private int linksHeaderRow;
    private int telegramChannelRow;
    private int githubRow;
    private int infoRow;
    private int shadowRow;

    @Override
    public boolean onFragmentCreate() {
        super.onFragmentCreate();
        NotificationCenter.getGlobalInstance().addObserver(this, NotificationCenter.zapretSettingsChanged);
        NotificationCenter.getGlobalInstance().addObserver(this, NotificationCenter.zapretDebugStateChanged);
        updateRows();
        return true;
    }

    @Override
    public void onFragmentDestroy() {
        super.onFragmentDestroy();
        NotificationCenter.getGlobalInstance().removeObserver(this, NotificationCenter.zapretSettingsChanged);
        NotificationCenter.getGlobalInstance().removeObserver(this, NotificationCenter.zapretDebugStateChanged);
    }

    private void updateRows() {
        rowCount = 0;
        generalHeaderRow = rowCount++;
        enabledRow = rowCount++;
        strategyRow = rowCount++;
        runtimeRow = rowCount++;
        previewRow = rowCount++;
        testsHeaderRow = rowCount++;
        connectionTestRow = rowCount++;
        messageTestRow = rowCount++;
        imageTestRow = rowCount++;
        callTestRow = rowCount++;
        logRow = rowCount++;
        linksHeaderRow = rowCount++;
        telegramChannelRow = rowCount++;
        githubRow = rowCount++;
        infoRow = rowCount++;
        shadowRow = rowCount++;
        if (listAdapter != null) {
            listAdapter.notifyDataSetChanged();
        }
    }

    @Override
    public View createView(Context context) {
        actionBar.setBackButtonImage(R.drawable.ic_ab_back);
        actionBar.setTitle(LocaleController.getString(R.string.ZapretTitle));
        if (AndroidUtilities.isTablet()) {
            actionBar.setOccupyStatusBar(false);
        }
        actionBar.setAllowOverlayTitle(true);
        actionBar.setActionBarMenuOnItemClick(new ActionBar.ActionBarMenuOnItemClick() {
            @Override
            public void onItemClick(int id) {
                if (id == -1) {
                    finishFragment();
                }
            }
        });

        listAdapter = new ListAdapter(context);

        fragmentView = new FrameLayout(context);
        fragmentView.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundGray));
        FrameLayout frameLayout = (FrameLayout) fragmentView;

        listView = new RecyclerListView(context);
        listView.setVerticalScrollBarEnabled(false);
        listView.setLayoutManager(new LinearLayoutManager(context, LinearLayoutManager.VERTICAL, false));
        listView.setAdapter(listAdapter);
        listView.setOnItemClickListener((view, position, x, y) -> {
            if (position == enabledRow) {
                ZapretConfig.setEnabled(!ZapretConfig.isEnabled());
            } else if (position == strategyRow) {
                showStrategyDialog();
            } else if (position == previewRow) {
                showPreviewDialog();
            } else if (position == connectionTestRow) {
                ZapretDiagnosticsController.getInstance().runConnectionTest(currentAccount);
            } else if (position == messageTestRow) {
                ZapretDiagnosticsController.getInstance().runMessageTest(currentAccount);
            } else if (position == imageTestRow) {
                ZapretDiagnosticsController.getInstance().runImageTest(currentAccount);
            } else if (position == callTestRow) {
                openCallPicker();
            } else if (position == logRow) {
                showLogDialog();
            } else if (position == telegramChannelRow) {
                if (getParentActivity() != null) {
                    Browser.openUrl(getParentActivity(), "https://t.me/xower_dev");
                }
            } else if (position == githubRow) {
                if (getParentActivity() != null) {
                    Browser.openUrl(getParentActivity(), "https://github.com/N0-C0M/Telegram-Zapret-Android-2");
                }
            }
        });
        frameLayout.addView(listView, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT, Gravity.TOP | Gravity.LEFT));

        return fragmentView;
    }

    private void showStrategyDialog() {
        if (getParentActivity() == null) {
            return;
        }
        android.app.Dialog dialog = org.telegram.ui.Components.AlertsCreator.createSingleChoiceDialog(
            getParentActivity(),
            ZapretConfig.getStrategyTitles(),
            LocaleController.getString(R.string.ZapretBuiltInStrategy),
            ZapretConfig.getSelectedStrategy(),
            (dialogInterface, which) -> ZapretConfig.setSelectedStrategy(which)
        );
        showDialog(dialog);
    }

    private void showPreviewDialog() {
        if (getParentActivity() == null) {
            return;
        }
        Context context = getParentActivity();
        ScrollView scrollView = new ScrollView(context);
        TextView textView = new TextView(context);
        int padding = AndroidUtilities.dp(24);
        textView.setPadding(padding, padding, padding, padding);
        textView.setTextColor(Theme.getColor(Theme.key_dialogTextBlack));
        textView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14);
        textView.setTypeface(Typeface.MONOSPACE);
        textView.setText(ZapretConfig.getActiveConfig());
        scrollView.addView(textView, LayoutHelper.createScroll(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, Gravity.LEFT | Gravity.TOP));

        AlertDialog.Builder builder = new AlertDialog.Builder(context);
        builder.setTitle(ZapretConfig.getActiveConfigDisplayName());
        builder.setView(scrollView);
        builder.setPositiveButton(LocaleController.getString(R.string.ZapretCopyConfig), (dialog, which) ->
            AndroidUtilities.addToClipboard(ZapretConfig.getActiveConfig()));
        builder.setNegativeButton(LocaleController.getString(R.string.ZapretClose), null);
        showDialog(builder.create());
    }

    private void showLogDialog() {
        if (getParentActivity() == null) {
            return;
        }
        Context context = getParentActivity();
        ScrollView scrollView = new ScrollView(context);
        TextView textView = new TextView(context);
        int padding = AndroidUtilities.dp(24);
        textView.setPadding(padding, padding, padding, padding);
        textView.setTextColor(Theme.getColor(Theme.key_dialogTextBlack));
        textView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14);
        textView.setTypeface(Typeface.MONOSPACE);
        textView.setText(ZapretDiagnosticsController.getInstance().getFullLogText());
        scrollView.addView(textView, LayoutHelper.createScroll(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, Gravity.LEFT | Gravity.TOP));

        AlertDialog.Builder builder = new AlertDialog.Builder(context);
        builder.setTitle(LocaleController.getString(R.string.ZapretDebugShowLog));
        builder.setView(scrollView);
        builder.setPositiveButton(LocaleController.getString(R.string.ZapretCopyConfig), (dialog, which) ->
            AndroidUtilities.addToClipboard(ZapretDiagnosticsController.getInstance().getFullLogText()));
        builder.setNegativeButton(LocaleController.getString(R.string.ZapretClose), null);
        showDialog(builder.create());
    }

    private void openCallPicker() {
        Bundle args = new Bundle();
        args.putBoolean("onlyUsers", true);
        args.putBoolean("destroyAfterSelect", true);
        args.putBoolean("returnAsResult", true);
        args.putBoolean("allowSelf", false);
        ContactsActivity contactsActivity = new ContactsActivity(args);
        contactsActivity.setDelegate((user, param, activity) -> startCallTest(user));
        presentFragment(contactsActivity);
    }

    private void startCallTest(TLRPC.User user) {
        if (user == null) {
            ZapretDiagnosticsController.getInstance().failCallTest("user not selected");
            return;
        }
        int connectionState = ConnectionsManager.getInstance(currentAccount).getConnectionState();
        if (connectionState != ConnectionsManager.ConnectionStateConnected) {
            ZapretDiagnosticsController.getInstance().failCallTest("network: " + ZapretDiagnosticsController.getConnectionStateLabel(connectionState));
            return;
        }
        if (getParentActivity() == null) {
            ZapretDiagnosticsController.getInstance().failCallTest("activity unavailable");
            return;
        }
        ZapretDiagnosticsController.getInstance().startCallTest(user);
        VoIPHelper.startCall(user, false, true, getParentActivity(), null, getAccountInstance());
    }

    @Override
    public void didReceivedNotification(int id, int account, Object... args) {
        if ((id == NotificationCenter.zapretSettingsChanged || id == NotificationCenter.zapretDebugStateChanged) && listAdapter != null) {
            listAdapter.notifyDataSetChanged();
        }
    }

    private class ListAdapter extends RecyclerListView.SelectionAdapter {

        private final Context context;

        private ListAdapter(Context context) {
            this.context = context;
        }

        @Override
        public int getItemCount() {
            return rowCount;
        }

        @Override
        public boolean isEnabled(RecyclerView.ViewHolder holder) {
            int position = holder.getAdapterPosition();
            return position == enabledRow
                || position == strategyRow
                || position == previewRow
                || position == connectionTestRow
                || position == messageTestRow
                || position == imageTestRow
                || position == callTestRow
                || position == logRow
                || position == telegramChannelRow
                || position == githubRow;
        }

        @Override
        public int getItemViewType(int position) {
            if (position == shadowRow) {
                return 0;
            } else if (position == generalHeaderRow || position == testsHeaderRow || position == linksHeaderRow) {
                return 2;
            } else if (position == enabledRow) {
                return 3;
            } else if (position == infoRow) {
                return 4;
            } else {
                return 1;
            }
        }

        @Override
        public RecyclerView.ViewHolder onCreateViewHolder(ViewGroup parent, int viewType) {
            View view;
            switch (viewType) {
                case 0:
                    view = new ShadowSectionCell(context);
                    break;
                case 2:
                    view = new HeaderCell(context, 22);
                    view.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundWhite));
                    break;
                case 3:
                    view = new TextCheckCell(context);
                    view.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundWhite));
                    break;
                case 4:
                    view = new TextInfoPrivacyCell(context);
                    view.setBackgroundDrawable(Theme.getThemedDrawableByKey(context, R.drawable.greydivider, Theme.key_windowBackgroundGrayShadow));
                    break;
                case 1:
                default:
                    view = new TextSettingsCell(context);
                    view.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundWhite));
                    break;
            }
            view.setLayoutParams(new RecyclerView.LayoutParams(RecyclerView.LayoutParams.MATCH_PARENT, RecyclerView.LayoutParams.WRAP_CONTENT));
            return new RecyclerListView.Holder(view);
        }

        @Override
        public void onBindViewHolder(RecyclerView.ViewHolder holder, int position) {
            switch (holder.getItemViewType()) {
                case 0: {
                    holder.itemView.setBackgroundDrawable(Theme.getThemedDrawableByKey(context, R.drawable.greydivider_bottom, Theme.key_windowBackgroundGrayShadow));
                    break;
                }
                case 1: {
                    TextSettingsCell cell = (TextSettingsCell) holder.itemView;
                    cell.setCanDisable(false);
                    cell.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText));
                    if (position == strategyRow) {
                        cell.setTextAndValue(LocaleController.getString(R.string.ZapretBuiltInStrategy), ZapretConfig.getSelectedStrategyTitle(), true);
                    } else if (position == runtimeRow) {
                        cell.setTextAndValue(LocaleController.getString(R.string.ZapretDebugRuntimeStatus), ZapretDiagnosticsController.getInstance().getRuntimeSummary(), true);
                    } else if (position == previewRow) {
                        cell.setTextAndValue(LocaleController.getString(R.string.ZapretPreviewActiveConfig), ZapretConfig.getActiveConfigDisplayName(), true);
                    } else if (position == connectionTestRow) {
                        cell.setTextAndValue(LocaleController.getString(R.string.ZapretDebugRunConnectionTest), ZapretDiagnosticsController.getInstance().getTestSummary(ZapretDiagnosticsController.TEST_CONNECTION), true);
                    } else if (position == messageTestRow) {
                        cell.setTextAndValue(LocaleController.getString(R.string.ZapretDebugRunMessageTest), ZapretDiagnosticsController.getInstance().getTestSummary(ZapretDiagnosticsController.TEST_MESSAGE), true);
                    } else if (position == imageTestRow) {
                        cell.setTextAndValue(LocaleController.getString(R.string.ZapretDebugRunImageTest), ZapretDiagnosticsController.getInstance().getTestSummary(ZapretDiagnosticsController.TEST_IMAGE), true);
                    } else if (position == callTestRow) {
                        cell.setTextAndValue(LocaleController.getString(R.string.ZapretDebugRunCallTest), ZapretDiagnosticsController.getInstance().getTestSummary(ZapretDiagnosticsController.TEST_CALL), true);
                    } else if (position == logRow) {
                        cell.setText(LocaleController.getString(R.string.ZapretDebugShowLog), false);
                    } else if (position == telegramChannelRow) {
                        cell.setTextAndValue(LocaleController.getString(R.string.ZapretTelegramChannel), "t.me/xower_dev", true);
                    } else if (position == githubRow) {
                        cell.setTextAndValue(LocaleController.getString(R.string.ZapretGitHub), "github.com/N0-C0M/Telegram-Zapret-Android-2", false);
                    }
                    break;
                }
                case 2: {
                    HeaderCell headerCell = (HeaderCell) holder.itemView;
                    if (position == generalHeaderRow) {
                        headerCell.setText(LocaleController.getString(R.string.ZapretGeneralSection));
                    } else if (position == testsHeaderRow) {
                        headerCell.setText(LocaleController.getString(R.string.ZapretTestsSection));
                    } else if (position == linksHeaderRow) {
                        headerCell.setText(LocaleController.getString(R.string.ZapretLinksSection));
                    }
                    break;
                }
                case 3: {
                    TextCheckCell checkCell = (TextCheckCell) holder.itemView;
                    checkCell.setTextAndCheck(LocaleController.getString(R.string.ZapretEnabled), ZapretConfig.isEnabled(), false);
                    break;
                }
                case 4: {
                    TextInfoPrivacyCell infoCell = (TextInfoPrivacyCell) holder.itemView;
                    infoCell.setText(ZapretConfig.getInfoText());
                    infoCell.setBackground(Theme.getThemedDrawableByKey(context, R.drawable.greydivider, Theme.key_windowBackgroundGrayShadow));
                    break;
                }
            }
        }

        @Override
        public void onViewAttachedToWindow(RecyclerView.ViewHolder holder) {
            if (holder.getItemViewType() == 3) {
                ((TextCheckCell) holder.itemView).setChecked(ZapretConfig.isEnabled());
            }
        }
    }

    @Override
    public ArrayList<ThemeDescription> getThemeDescriptions() {
        ArrayList<ThemeDescription> themeDescriptions = new ArrayList<>();

        themeDescriptions.add(new ThemeDescription(fragmentView, ThemeDescription.FLAG_BACKGROUND, null, null, null, null, Theme.key_windowBackgroundGray));
        themeDescriptions.add(new ThemeDescription(listView, ThemeDescription.FLAG_SELECTOR, null, null, null, null, Theme.key_listSelector));
        themeDescriptions.add(new ThemeDescription(listView, ThemeDescription.FLAG_BACKGROUNDFILTER, new Class[]{ShadowSectionCell.class}, null, null, null, Theme.key_windowBackgroundGrayShadow));
        themeDescriptions.add(new ThemeDescription(listView, ThemeDescription.FLAG_CELLBACKGROUNDCOLOR, new Class[]{TextSettingsCell.class, TextCheckCell.class, HeaderCell.class}, null, null, null, Theme.key_windowBackgroundWhite));
        themeDescriptions.add(new ThemeDescription(listView, 0, new Class[]{TextSettingsCell.class}, new String[]{"textView"}, null, null, null, Theme.key_windowBackgroundWhiteBlackText));
        themeDescriptions.add(new ThemeDescription(listView, 0, new Class[]{TextSettingsCell.class}, new String[]{"valueTextView"}, null, null, null, Theme.key_windowBackgroundWhiteValueText));
        themeDescriptions.add(new ThemeDescription(listView, 0, new Class[]{TextCheckCell.class}, new String[]{"textView"}, null, null, null, Theme.key_windowBackgroundWhiteBlackText));
        themeDescriptions.add(new ThemeDescription(listView, 0, new Class[]{TextCheckCell.class}, new String[]{"valueTextView"}, null, null, null, Theme.key_windowBackgroundWhiteGrayText2));
        themeDescriptions.add(new ThemeDescription(listView, 0, new Class[]{TextCheckCell.class}, new String[]{"checkBox"}, null, null, null, Theme.key_switchTrack));
        themeDescriptions.add(new ThemeDescription(listView, 0, new Class[]{TextCheckCell.class}, new String[]{"checkBox"}, null, null, null, Theme.key_switchTrackChecked));
        themeDescriptions.add(new ThemeDescription(listView, 0, new Class[]{HeaderCell.class}, new String[]{"textView"}, null, null, null, Theme.key_windowBackgroundWhiteBlueHeader));
        themeDescriptions.add(new ThemeDescription(listView, 0, new Class[]{TextInfoPrivacyCell.class}, new String[]{"textView"}, null, null, null, Theme.key_windowBackgroundWhiteGrayText4));
        themeDescriptions.add(new ThemeDescription(listView, 0, new Class[]{View.class}, Theme.dividerPaint, null, null, Theme.key_divider));

        themeDescriptions.add(new ThemeDescription(actionBar, ThemeDescription.FLAG_BACKGROUND, null, null, null, null, Theme.key_actionBarDefault));
        themeDescriptions.add(new ThemeDescription(listView, ThemeDescription.FLAG_LISTGLOWCOLOR, null, null, null, null, Theme.key_actionBarDefault));
        themeDescriptions.add(new ThemeDescription(actionBar, ThemeDescription.FLAG_AB_ITEMSCOLOR, null, null, null, null, Theme.key_actionBarDefaultIcon));
        themeDescriptions.add(new ThemeDescription(actionBar, ThemeDescription.FLAG_AB_TITLECOLOR, null, null, null, null, Theme.key_actionBarDefaultTitle));
        themeDescriptions.add(new ThemeDescription(actionBar, ThemeDescription.FLAG_AB_SELECTORCOLOR, null, null, null, null, Theme.key_actionBarDefaultSelector));

        return themeDescriptions;
    }
}
