package org.mariotaku.twidere.model.tab.argument;

import android.os.Bundle;
import android.support.annotation.CallSuper;
import android.support.annotation.NonNull;
import android.support.annotation.Nullable;

import com.bluelinelabs.logansquare.LoganSquare;
import com.bluelinelabs.logansquare.annotation.JsonField;
import com.bluelinelabs.logansquare.annotation.JsonObject;

import org.mariotaku.twidere.TwidereConstants;
import org.mariotaku.twidere.annotation.CustomTabType;
import org.mariotaku.twidere.model.UserKey;

import java.io.IOException;
import java.util.Arrays;

/**
 * Created by mariotaku on 16/3/6.
 */
@JsonObject
public class TabArguments implements TwidereConstants {
    @JsonField(name = "account_id")
    String accountId;

    @JsonField(name = "account_keys")
    @Nullable
    UserKey[] accountKeys;

    @Nullable
    public UserKey[] getAccountKeys() {
        return accountKeys;
    }

    public void setAccountKeys(@Nullable UserKey[] accountKeys) {
        this.accountKeys = accountKeys;
    }

    public String getAccountId() {
        return accountId;
    }

    @CallSuper
    public void copyToBundle(@NonNull Bundle bundle) {
        final UserKey[] accountKeys = this.accountKeys;
        if (accountKeys != null && accountKeys.length > 0) {
            for (UserKey key : accountKeys) {
                if (key == null) return;
            }
            bundle.putParcelableArray(EXTRA_ACCOUNT_KEYS, accountKeys);
        } else if (accountId != null) {
            long id = Long.MIN_VALUE;
            try {
                id = Long.parseLong(accountId);
            } catch (NumberFormatException e) {
                // Ignore
            }
            if (id != Long.MIN_VALUE && id <= 0) {
                // account_id = -1, means no account selected
                bundle.putParcelableArray(EXTRA_ACCOUNT_KEYS, null);
                return;
            }
            bundle.putParcelableArray(EXTRA_ACCOUNT_KEYS, new UserKey[]{UserKey.valueOf(accountId)});
        }
    }

    @Override
    public String toString() {
        return "TabArguments{" +
                "accountId=" + accountId +
                ", accountKeys=" + Arrays.toString(accountKeys) +
                '}';
    }

    @Nullable
    public static TabArguments parse(@NonNull @CustomTabType String type, @Nullable String json) throws IOException {
        if (json == null) return null;
        switch (type) {
            case CustomTabType.HOME_TIMELINE:
            case CustomTabType.NOTIFICATIONS_TIMELINE:
            case CustomTabType.DIRECT_MESSAGES:
            case CustomTabType.TRENDS_SUGGESTIONS: {
                return LoganSquare.parse(json, TabArguments.class);
            }
            case CustomTabType.USER_TIMELINE:
            case CustomTabType.FAVORITES: {
                return LoganSquare.parse(json, UserArguments.class);
            }
            case CustomTabType.LIST_TIMELINE: {
                return LoganSquare.parse(json, UserListArguments.class);
            }
            case CustomTabType.SEARCH_STATUSES: {
                return LoganSquare.parse(json, TextQueryArguments.class);
            }
        }
        return null;
    }
}
