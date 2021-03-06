/*
 *                 Twidere - Twitter client for Android
 *
 *  Copyright (C) 2012-2015 Mariotaku Lee <mariotaku.lee@gmail.com>
 *
 *  This program is free software: you can redistribute it and/or modify
 *  it under the terms of the GNU General Public License as published by
 *  the Free Software Foundation, either version 3 of the License, or
 *  (at your option) any later version.
 *
 *  This program is distributed in the hope that it will be useful,
 *  but WITHOUT ANY WARRANTY; without even the implied warranty of
 *  MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *  GNU General Public License for more details.
 *
 *  You should have received a copy of the GNU General Public License
 *  along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

package org.mariotaku.twidere.fragment

import android.accounts.AccountManager
import android.content.Context
import android.content.Intent
import android.graphics.Rect
import android.os.Bundle
import android.os.Parcelable
import android.support.v4.app.LoaderManager.LoaderCallbacks
import android.support.v4.content.Loader
import android.support.v7.widget.LinearLayoutManager
import android.support.v7.widget.RecyclerView
import android.support.v7.widget.RecyclerView.OnScrollListener
import android.view.*
import com.squareup.otto.Subscribe
import edu.tsinghua.hotmobi.HotMobiLogger
import edu.tsinghua.hotmobi.model.MediaEvent
import kotlinx.android.synthetic.main.fragment_content_recyclerview.*
import org.mariotaku.kpreferences.get
import org.mariotaku.ktextension.coerceInOr
import org.mariotaku.ktextension.isNullOrEmpty
import org.mariotaku.ktextension.rangeOfSize
import org.mariotaku.twidere.R
import org.mariotaku.twidere.TwidereConstants
import org.mariotaku.twidere.adapter.ParcelableActivitiesAdapter
import org.mariotaku.twidere.adapter.ParcelableActivitiesAdapter.Companion.ITEM_VIEW_TYPE_GAP
import org.mariotaku.twidere.adapter.ParcelableActivitiesAdapter.Companion.ITEM_VIEW_TYPE_STATUS
import org.mariotaku.twidere.adapter.ParcelableActivitiesAdapter.Companion.ITEM_VIEW_TYPE_STUB
import org.mariotaku.twidere.adapter.ParcelableActivitiesAdapter.Companion.ITEM_VIEW_TYPE_TITLE_SUMMARY
import org.mariotaku.twidere.adapter.decorator.DividerItemDecoration
import org.mariotaku.twidere.adapter.iface.ILoadMoreSupportAdapter
import org.mariotaku.twidere.annotation.ReadPositionTag
import org.mariotaku.twidere.constant.*
import org.mariotaku.twidere.constant.KeyboardShortcutConstants.*
import org.mariotaku.twidere.extension.model.getAccountType
import org.mariotaku.twidere.fragment.AbsStatusesFragment.DefaultOnLikedListener
import org.mariotaku.twidere.loader.iface.IExtendedLoader
import org.mariotaku.twidere.model.*
import org.mariotaku.twidere.model.analyzer.Share
import org.mariotaku.twidere.model.message.StatusListChangedEvent
import org.mariotaku.twidere.model.util.AccountUtils
import org.mariotaku.twidere.model.util.ParcelableActivityUtils
import org.mariotaku.twidere.model.util.getActivityStatus
import org.mariotaku.twidere.util.*
import org.mariotaku.twidere.util.KeyboardShortcutsHandler.KeyboardShortcutCallback
import org.mariotaku.twidere.util.imageloader.PauseRecyclerViewOnScrollListener
import org.mariotaku.twidere.view.ExtendedRecyclerView
import org.mariotaku.twidere.view.holder.ActivityTitleSummaryViewHolder
import org.mariotaku.twidere.view.holder.GapViewHolder
import org.mariotaku.twidere.view.holder.StatusViewHolder
import org.mariotaku.twidere.view.holder.iface.IStatusViewHolder
import java.util.*

abstract class AbsActivitiesFragment protected constructor() :
        AbsContentListRecyclerViewFragment<ParcelableActivitiesAdapter>(),
        LoaderCallbacks<List<ParcelableActivity>>, ParcelableActivitiesAdapter.ActivityAdapterListener,
        KeyboardShortcutCallback {

    private lateinit var activitiesBusCallback: Any
    private lateinit var navigationHelper: RecyclerViewNavigationHelper

    private lateinit var pauseOnScrollListener: OnScrollListener

    private val onScrollListener = object : OnScrollListener() {
        override fun onScrollStateChanged(recyclerView: RecyclerView?, newState: Int) {
            if (newState == RecyclerView.SCROLL_STATE_IDLE) {
                val layoutManager = layoutManager
                saveReadPosition(layoutManager.findFirstVisibleItemPosition())
            }
        }
    }


    override fun onActivityCreated(savedInstanceState: Bundle?) {
        super.onActivityCreated(savedInstanceState)
        activitiesBusCallback = createMessageBusCallback()
        scrollListener.reversed = preferences[readFromBottomKey]
        adapter.setListener(this)
        registerForContextMenu(recyclerView)
        navigationHelper = RecyclerViewNavigationHelper(recyclerView, layoutManager, adapter,
                this)
        pauseOnScrollListener = PauseRecyclerViewOnScrollListener(adapter.mediaLoader.imageLoader, false, true)

        val loaderArgs = Bundle(arguments)
        loaderArgs.putBoolean(IntentConstants.EXTRA_FROM_USER, true)
        loaderManager.initLoader(0, loaderArgs, this)
        showProgress()
    }

    abstract fun getActivities(param: RefreshTaskParam): Boolean

    override fun handleKeyboardShortcutSingle(handler: KeyboardShortcutsHandler, keyCode: Int, event: KeyEvent, metaState: Int): Boolean {
        var action = handler.getKeyAction(CONTEXT_TAG_NAVIGATION, keyCode, event, metaState)
        if (ACTION_NAVIGATION_REFRESH == action) {
            triggerRefresh()
            return true
        }
        val focusedChild = RecyclerViewUtils.findRecyclerViewChild(recyclerView,
                layoutManager.focusedChild)
        var position = RecyclerView.NO_POSITION
        if (focusedChild != null && focusedChild.parent === recyclerView) {
            position = recyclerView.getChildLayoutPosition(focusedChild)
        }
        if (position != RecyclerView.NO_POSITION) {
            val activity = adapter.getActivity(position) ?: return false
            if (keyCode == KeyEvent.KEYCODE_ENTER) {
                openActivity(activity)
                return true
            }
            val status = activity.getActivityStatus() ?: return false
            if (action == null) {
                action = handler.getKeyAction(CONTEXT_TAG_STATUS, keyCode, event, metaState)
            }
            if (action == null) return false
            when (action) {
                ACTION_STATUS_REPLY -> {
                    val intent = Intent(IntentConstants.INTENT_ACTION_REPLY)
                    intent.putExtra(IntentConstants.EXTRA_STATUS, status)
                    startActivity(intent)
                    return true
                }
                ACTION_STATUS_RETWEET -> {
                    RetweetQuoteDialogFragment.show(fragmentManager, status)
                    return true
                }
                ACTION_STATUS_FAVORITE -> {
                    val twitter = twitterWrapper
                    if (status.is_favorite) {
                        twitter.destroyFavoriteAsync(status.account_key, status.id)
                    } else {
                        val holder = recyclerView.findViewHolderForLayoutPosition(position) as StatusViewHolder
                        holder.playLikeAnimation(DefaultOnLikedListener(twitter, status))
                    }
                    return true
                }
            }
        }
        return navigationHelper.handleKeyboardShortcutSingle(handler, keyCode, event, metaState)
    }

    private fun openActivity(activity: ParcelableActivity) {
        val status = activity.getActivityStatus()
        if (status != null) {
            IntentUtils.openStatus(context, status, null)
        }
    }

    override fun isKeyboardShortcutHandled(handler: KeyboardShortcutsHandler, keyCode: Int, event: KeyEvent, metaState: Int): Boolean {
        var action = handler.getKeyAction(CONTEXT_TAG_NAVIGATION, keyCode, event, metaState)
        if (ACTION_NAVIGATION_REFRESH == action) {
            return true
        }
        if (action == null) {
            action = handler.getKeyAction(CONTEXT_TAG_STATUS, keyCode, event, metaState)
        }
        if (action == null) return false
        when (action) {
            ACTION_STATUS_REPLY, ACTION_STATUS_RETWEET, ACTION_STATUS_FAVORITE -> return true
        }
        return navigationHelper.isKeyboardShortcutHandled(handler, keyCode, event, metaState)
    }

    override fun handleKeyboardShortcutRepeat(handler: KeyboardShortcutsHandler, keyCode: Int, repeatCount: Int,
                                              event: KeyEvent, metaState: Int): Boolean {
        return navigationHelper.handleKeyboardShortcutRepeat(handler, keyCode, repeatCount, event, metaState)
    }

    override fun onCreateLoader(id: Int, args: Bundle): Loader<List<ParcelableActivity>> {
        val fromUser = args.getBoolean(IntentConstants.EXTRA_FROM_USER)
        args.remove(IntentConstants.EXTRA_FROM_USER)
        return onCreateActivitiesLoader(activity, args, fromUser)
    }

    protected fun saveReadPosition() {
        saveReadPosition(layoutManager.findFirstVisibleItemPosition())
    }

    /**
     * Activities loaded, update adapter data & restore load position
     *
     * Steps:
     * 1. Save current read position if not first load (adapter data is not empty)
     *   1.1 If readFromBottom is true, save position on screen bottom
     * 2. Change adapter data
     * 3. Restore adapter data
     *   3.1 If lastVisible was last item, keep lastVisibleItem position (load more)
     *   3.2 Else, if readFromBottom is true:
     *     3.1.1 If position was first, keep lastVisibleItem position (pull refresh)
     *     3.1.2 Else, keep lastVisibleItem position
     *   3.2 If readFromBottom is false:
     *     3.2.1 If position was first, set new position to 0 (pull refresh)
     *     3.2.2 Else, keep firstVisibleItem position (gap clicked)
     */
    override fun onLoadFinished(loader: Loader<List<ParcelableActivity>>, data: List<ParcelableActivity>) {
        val rememberPosition = preferences[rememberPositionKey]
        val readPositionTag = currentReadPositionTag
        val readFromBottom = preferences[readFromBottomKey]
        val firstLoad = adapterData.isNullOrEmpty()

        var lastReadId: Long = -1
        var lastReadViewTop: Int = 0
        var loadMore = false
        var wasAtTop = false

        // 1. Save current read position if not first load
        if (!firstLoad) {
            val firstVisibleItemPosition = layoutManager.findFirstVisibleItemPosition()
            val lastVisibleItemPosition = layoutManager.findLastVisibleItemPosition()
            wasAtTop = firstVisibleItemPosition == 0
            val activityRange = rangeOfSize(adapter.activityStartIndex, Math.max(0, adapter.activityCount - 1))
            val lastReadPosition = if (loadMore || readFromBottom) {
                lastVisibleItemPosition
            } else {
                firstVisibleItemPosition
            }.coerceInOr(activityRange, -1)
            lastReadId = adapter.getTimestamp(lastReadPosition)
            lastReadViewTop = layoutManager.findViewByPosition(lastReadPosition)?.top ?: 0
            loadMore = activityRange.endInclusive >= 0 && lastVisibleItemPosition >= activityRange.endInclusive
        } else if (rememberPosition && readPositionTag != null) {
            lastReadId = readStateManager.getPosition(readPositionTag)
            lastReadViewTop = 0
        }

        adapter.setData(data)

        refreshEnabled = true

        var restorePosition = -1

        if (loader !is IExtendedLoader || loader.fromUser) {
            if (hasMoreData(data)) {
                adapter.loadMoreSupportedPosition = ILoadMoreSupportAdapter.END
            } else {
                adapter.loadMoreSupportedPosition = ILoadMoreSupportAdapter.NONE
            }
            restorePosition = adapter.findPositionBySortTimestamp(lastReadId)
        }

        if (loadMore) {
            restorePosition += 1
            restorePosition.coerceInOr(0 until layoutManager.itemCount, -1)
        }
        if (restorePosition != -1 && adapter.isActivity(restorePosition) && (loadMore || !wasAtTop ||
                readFromBottom
                || (rememberPosition && firstLoad))) {
            if (layoutManager.height == 0) {
                // RecyclerView has not currently laid out, ignore padding.
                layoutManager.scrollToPositionWithOffset(restorePosition, lastReadViewTop)
            } else {
                layoutManager.scrollToPositionWithOffset(restorePosition, lastReadViewTop - layoutManager.paddingTop)
            }
        }


        if (loader is IExtendedLoader) {
            loader.fromUser = false
        }
        onContentLoaded(loader, data)
    }

    override fun onLoaderReset(loader: Loader<List<ParcelableActivity>>) {
        if (loader is IExtendedLoader) {
            loader.fromUser = false
        }
    }

    override fun setUserVisibleHint(isVisibleToUser: Boolean) {
        if (userVisibleHint && !isVisibleToUser && host != null) {
            saveReadPosition()
        }
        super.setUserVisibleHint(isVisibleToUser)
    }

    override fun onGapClick(holder: GapViewHolder, position: Int) {
        val activity = adapter.getActivity(position) ?: return
        DebugLog.v(TwidereConstants.LOGTAG, "Load activity gap $activity")
        val accountIds = arrayOf(activity.account_key)
        val maxIds = arrayOf(activity.min_position)
        val maxSortIds = longArrayOf(activity.min_sort_position)
        getActivities(BaseRefreshTaskParam(accountIds, maxIds, null, maxSortIds, null))
    }

    override fun onMediaClick(holder: IStatusViewHolder, view: View, media: ParcelableMedia, position: Int) {
        val status = adapter.getActivity(position)?.getActivityStatus() ?: return
        IntentUtils.openMedia(activity, status, media, preferences[newDocumentApiKey],
                preferences[displaySensitiveContentsKey],
                null)
        // BEGIN HotMobi
        val event = MediaEvent.create(activity, status, media, timelineType, adapter.mediaPreviewEnabled)
        HotMobiLogger.getInstance(activity).log(status.account_key, event)
        // END HotMobi
    }

    protected abstract val timelineType: String

    override fun onStatusActionClick(holder: IStatusViewHolder, id: Int, position: Int) {
        val status = getActivityStatus(position) ?: return
        val activity = activity
        when (id) {
            R.id.reply -> {
                val intent = Intent(IntentConstants.INTENT_ACTION_REPLY)
                intent.`package` = activity.packageName
                intent.putExtra(IntentConstants.EXTRA_STATUS, status)
                activity.startActivity(intent)
            }
            R.id.retweet -> {
                RetweetQuoteDialogFragment.show(fragmentManager, status)
            }
            R.id.favorite -> {
                if (status.is_favorite) {
                    twitterWrapper.destroyFavoriteAsync(status.account_key, status.id)
                } else {
                    holder.playLikeAnimation(DefaultOnLikedListener(twitterWrapper, status))
                }
            }
        }
    }

    override fun onActivityClick(holder: ActivityTitleSummaryViewHolder, position: Int) {
        val activity = adapter.getActivity(position) ?: return
        val list = ArrayList<Parcelable>()
        if (activity.target_object_statuses?.isNotEmpty() ?: false) {
            list.addAll(activity.target_object_statuses)
        } else if (activity.target_statuses?.isNotEmpty() ?: false) {
            list.addAll(activity.target_statuses)
        }
        list.addAll(ParcelableActivityUtils.getAfterFilteredSources(activity))
        IntentUtils.openItems(getActivity(), list)
    }

    override fun onStatusMenuClick(holder: IStatusViewHolder, menuView: View, position: Int) {
        if (activity == null) return
        val lm = layoutManager
        val view = lm.findViewByPosition(position) ?: return
        if (lm.getItemViewType(view) != ITEM_VIEW_TYPE_STATUS) {
            return
        }
        recyclerView.showContextMenuForChild(view)
    }

    override fun onStatusClick(holder: IStatusViewHolder, position: Int) {
        val status = getActivityStatus(position) ?: return
        IntentUtils.openStatus(context, status, null)
    }

    override fun onQuotedStatusClick(holder: IStatusViewHolder, position: Int) {
        val status = getActivityStatus(position) ?: return
        IntentUtils.openStatus(context, status.account_key, status.quoted_id)
    }

    private fun getActivityStatus(position: Int): ParcelableStatus? {
        return adapter.getActivity(position)?.getActivityStatus()
    }

    override fun onStart() {
        super.onStart()
        recyclerView.addOnScrollListener(onScrollListener)
        recyclerView.addOnScrollListener(pauseOnScrollListener)
        bus.register(activitiesBusCallback)
    }

    override fun onStop() {
        bus.unregister(activitiesBusCallback)
        recyclerView.removeOnScrollListener(pauseOnScrollListener)
        recyclerView.removeOnScrollListener(onScrollListener)
        if (userVisibleHint) {
            saveReadPosition()
        }
        super.onStop()
    }

    override fun scrollToStart(): Boolean {
        val result = super.scrollToStart()
        if (result) {
            saveReadPosition(0)
        }
        return result
    }

    override val reachingEnd: Boolean
        get() {
            val lm = layoutManager
            var lastPosition = lm.findLastCompletelyVisibleItemPosition()
            if (lastPosition == RecyclerView.NO_POSITION) {
                lastPosition = lm.findLastVisibleItemPosition()
            }
            val itemCount = adapter.itemCount
            var finalPos = itemCount - 1
            for (i in lastPosition + 1 until itemCount) {
                if (adapter.getItemViewType(i) != ParcelableActivitiesAdapter.ITEM_VIEW_TYPE_EMPTY) {
                    finalPos = i - 1
                    break
                }
            }
            return finalPos >= itemCount - 1
        }

    protected open fun createMessageBusCallback(): Any {
        return StatusesBusCallback()
    }

    protected abstract val accountKeys: Array<UserKey>

    protected val adapterData: List<ParcelableActivity>?
        get() {
            return adapter.getData()
        }

    protected open val readPositionTag: String?
        @ReadPositionTag
        get() = null

    protected abstract fun hasMoreData(data: List<ParcelableActivity>?): Boolean

    protected abstract fun onCreateActivitiesLoader(context: Context, args: Bundle,
                                                    fromUser: Boolean): Loader<List<ParcelableActivity>>

    protected abstract fun onContentLoaded(loader: Loader<List<ParcelableActivity>>, data: List<ParcelableActivity>?)

    protected fun saveReadPosition(position: Int) {
        if (host == null) return
        if (position == RecyclerView.NO_POSITION) return
        val item = adapter.getActivity(position) ?: return
        var positionUpdated = false
        readPositionTag?.let {
            for (accountKey in accountKeys) {
                val tag = Utils.getReadPositionTagWithAccount(it, accountKey)
                if (readStateManager.setPosition(tag, item.timestamp)) {
                    positionUpdated = true
                }
            }
        }
        currentReadPositionTag?.let {
            readStateManager.setPosition(it, item.timestamp, true)
        }

        if (positionUpdated) {
            twitterWrapper.setActivitiesAboutMeUnreadAsync(accountKeys, item.timestamp)
        }
    }

    override val extraContentPadding: Rect
        get() {
            val paddingVertical = resources.getDimensionPixelSize(R.dimen.element_spacing_small)
            return Rect(0, paddingVertical, 0, paddingVertical)
        }

    override fun onCreateContextMenu(menu: ContextMenu, v: View, menuInfo: ContextMenu.ContextMenuInfo?) {
        if (!userVisibleHint || menuInfo == null) return
        val inflater = MenuInflater(context)
        val contextMenuInfo = menuInfo as ExtendedRecyclerView.ContextMenuInfo
        val position = contextMenuInfo.position
        when (adapter.getItemViewType(position)) {
            ITEM_VIEW_TYPE_STATUS -> {
                val status = getActivityStatus(position) ?: return
                inflater.inflate(R.menu.action_status, menu)
                MenuUtils.setupForStatus(context, preferences, menu, status, twitterWrapper,
                        userColorNameManager)
            }
        }
    }

    override fun onContextItemSelected(item: MenuItem): Boolean {
        if (!userVisibleHint) return false
        val contextMenuInfo = item.menuInfo as ExtendedRecyclerView.ContextMenuInfo
        val position = contextMenuInfo.position

        when (adapter.getItemViewType(position)) {
            ITEM_VIEW_TYPE_STATUS -> {
                val status = getActivityStatus(position) ?: return false
                if (item.itemId == R.id.share) {
                    val shareIntent = Utils.createStatusShareIntent(activity, status)
                    val chooser = Intent.createChooser(shareIntent, getString(R.string.share_status))
                    startActivity(chooser)

                    val am = AccountManager.get(context)
                    val accountType = AccountUtils.findByAccountKey(am, status.account_key)?.getAccountType(am)
                    Analyzer.log(Share.status(accountType, status))
                    return true
                }
                return MenuUtils.handleStatusClick(activity, this, fragmentManager,
                        userColorNameManager, twitterWrapper, status, item)
            }
        }
        return false
    }


    override fun createItemDecoration(context: Context, recyclerView: RecyclerView,
                                      layoutManager: LinearLayoutManager): RecyclerView.ItemDecoration? {
        val itemDecoration = object : DividerItemDecoration(context,
                (recyclerView.layoutManager as LinearLayoutManager).orientation) {
            override fun isDividerEnabled(childPos: Int): Boolean {
                when (adapter.getItemViewType(childPos)) {
                    ITEM_VIEW_TYPE_STATUS, ITEM_VIEW_TYPE_TITLE_SUMMARY, ITEM_VIEW_TYPE_GAP,
                    ITEM_VIEW_TYPE_STUB -> {
                        return true
                    }
                    else -> {
                        return false
                    }
                }
            }
        }
        val res = context.resources
        if (adapter.profileImageEnabled) {
            val decorPaddingLeft = res.getDimensionPixelSize(R.dimen.element_spacing_normal) * 2 + res.getDimensionPixelSize(R.dimen.icon_size_status_profile_image)
            itemDecoration.setPadding { position, rect ->
                val itemViewType = adapter.getItemViewType(position)
                var nextItemIsStatus = false
                if (position < adapter.itemCount - 1) {
                    nextItemIsStatus = adapter.getItemViewType(position + 1) == ITEM_VIEW_TYPE_STATUS
                }
                if (nextItemIsStatus && itemViewType == ITEM_VIEW_TYPE_STATUS) {
                    rect.left = decorPaddingLeft
                } else {
                    rect.left = 0
                }
                true
            }
        }
        itemDecoration.setDecorationEndOffset(1)
        return itemDecoration
    }

    private val currentReadPositionTag: String?
        get() = "${readPositionTag}_${tabId}_current"

    protected inner class StatusesBusCallback {

        @Subscribe
        fun notifyStatusListChanged(event: StatusListChangedEvent) {
            adapter.notifyDataSetChanged()
        }

    }
}
