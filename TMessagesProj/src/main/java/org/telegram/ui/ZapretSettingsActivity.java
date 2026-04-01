package org.telegram.ui;

import android.content.Context;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;

import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.NotificationCenter;
import org.telegram.messenger.R;
import org.telegram.messenger.ZapretConfig;
import org.telegram.messenger.browser.Browser;
import org.telegram.ui.ActionBar.ActionBar;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.ActionBar.ThemeDescription;
import org.telegram.ui.Cells.HeaderCell;
import org.telegram.ui.Cells.ShadowSectionCell;
import org.telegram.ui.Cells.TextCheckCell;
import org.telegram.ui.Cells.TextSettingsCell;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.RecyclerListView;

import java.util.ArrayList;

public class ZapretSettingsActivity extends BaseFragment implements NotificationCenter.NotificationCenterDelegate {

    private RecyclerListView listView;
    private ListAdapter listAdapter;

    private int rowCount;
    private int generalHeaderRow;
    private int wsProxyRow;
    private int wsProxyIpv6Row;
    private int wsProxyNotificationRow;
    private int readDeletedMessagesRow;
    private int linksHeaderRow;
    private int telegramChannelRow;
    private int githubRow;
    private int shadowRow;

    @Override
    public boolean onFragmentCreate() {
        super.onFragmentCreate();
        NotificationCenter.getGlobalInstance().addObserver(this, NotificationCenter.zapretSettingsChanged);
        updateRows();
        return true;
    }

    @Override
    public void onFragmentDestroy() {
        super.onFragmentDestroy();
        NotificationCenter.getGlobalInstance().removeObserver(this, NotificationCenter.zapretSettingsChanged);
    }

    private void updateRows() {
        rowCount = 0;
        generalHeaderRow = rowCount++;
        wsProxyRow = rowCount++;
        wsProxyIpv6Row = rowCount++;
        wsProxyNotificationRow = rowCount++;
        readDeletedMessagesRow = rowCount++;
        linksHeaderRow = rowCount++;
        telegramChannelRow = rowCount++;
        githubRow = rowCount++;
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
            if (position == wsProxyRow) {
                ZapretConfig.setWsProxyEnabled(!ZapretConfig.isWsProxyEnabled());
            } else if (position == wsProxyIpv6Row) {
                ZapretConfig.setWsProxyIpv6Enabled(!ZapretConfig.isWsProxyIpv6Enabled());
            } else if (position == wsProxyNotificationRow) {
                ZapretConfig.setWsProxyNotificationEnabled(!ZapretConfig.isWsProxyNotificationEnabled());
            } else if (position == readDeletedMessagesRow) {
                ZapretConfig.setReadDeletedMessagesEnabled(!ZapretConfig.isReadDeletedMessagesEnabled());
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

    @Override
    public void didReceivedNotification(int id, int account, Object... args) {
        if (id == NotificationCenter.zapretSettingsChanged && listAdapter != null) {
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
            return position == wsProxyRow
                || position == wsProxyIpv6Row
                || position == wsProxyNotificationRow
                || position == readDeletedMessagesRow
                || position == telegramChannelRow
                || position == githubRow;
        }

        @Override
        public int getItemViewType(int position) {
            if (position == shadowRow) {
                return 0;
            } else if (position == generalHeaderRow || position == linksHeaderRow) {
                return 2;
            } else if (position == wsProxyRow || position == wsProxyIpv6Row || position == wsProxyNotificationRow || position == readDeletedMessagesRow) {
                return 3;
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
                    if (position == telegramChannelRow) {
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
                    } else if (position == linksHeaderRow) {
                        headerCell.setText(LocaleController.getString(R.string.ZapretLinksSection));
                    }
                    break;
                }
                case 3: {
                    TextCheckCell checkCell = (TextCheckCell) holder.itemView;
                    boolean needDivider = position != readDeletedMessagesRow;
                    if (position == wsProxyRow) {
                        checkCell.setTextAndCheck(LocaleController.getString(R.string.ZapretWsProxy), ZapretConfig.isWsProxyEnabled(), needDivider);
                    } else if (position == wsProxyIpv6Row) {
                        checkCell.setTextAndCheck(LocaleController.getString(R.string.ZapretWsProxyIpv6), ZapretConfig.isWsProxyIpv6Enabled(), needDivider);
                    } else if (position == wsProxyNotificationRow) {
                        checkCell.setTextAndCheck(LocaleController.getString(R.string.ZapretWsProxyNotification), ZapretConfig.isWsProxyNotificationEnabled(), needDivider);
                    } else if (position == readDeletedMessagesRow) {
                        checkCell.setTextAndCheck(LocaleController.getString(R.string.ZapretReadDeletedMessages), ZapretConfig.isReadDeletedMessagesEnabled(), needDivider);
                    }
                    break;
                }
            }
        }

        @Override
        public void onViewAttachedToWindow(RecyclerView.ViewHolder holder) {
            if (holder.getItemViewType() == 3) {
                int position = holder.getAdapterPosition();
                TextCheckCell cell = (TextCheckCell) holder.itemView;
                if (position == wsProxyRow) {
                    cell.setChecked(ZapretConfig.isWsProxyEnabled());
                } else if (position == wsProxyIpv6Row) {
                    cell.setChecked(ZapretConfig.isWsProxyIpv6Enabled());
                } else if (position == wsProxyNotificationRow) {
                    cell.setChecked(ZapretConfig.isWsProxyNotificationEnabled());
                } else if (position == readDeletedMessagesRow) {
                    cell.setChecked(ZapretConfig.isReadDeletedMessagesEnabled());
                }
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
        themeDescriptions.add(new ThemeDescription(listView, 0, new Class[]{View.class}, Theme.dividerPaint, null, null, Theme.key_divider));

        themeDescriptions.add(new ThemeDescription(actionBar, ThemeDescription.FLAG_BACKGROUND, null, null, null, null, Theme.key_actionBarDefault));
        themeDescriptions.add(new ThemeDescription(listView, ThemeDescription.FLAG_LISTGLOWCOLOR, null, null, null, null, Theme.key_actionBarDefault));
        themeDescriptions.add(new ThemeDescription(actionBar, ThemeDescription.FLAG_AB_ITEMSCOLOR, null, null, null, null, Theme.key_actionBarDefaultIcon));
        themeDescriptions.add(new ThemeDescription(actionBar, ThemeDescription.FLAG_AB_TITLECOLOR, null, null, null, null, Theme.key_actionBarDefaultTitle));
        themeDescriptions.add(new ThemeDescription(actionBar, ThemeDescription.FLAG_AB_SELECTORCOLOR, null, null, null, null, Theme.key_actionBarDefaultSelector));

        return themeDescriptions;
    }
}
