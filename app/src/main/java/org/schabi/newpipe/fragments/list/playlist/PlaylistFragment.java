package org.schabi.newpipe.fragments.list.playlist;

import static org.schabi.newpipe.ktx.ViewUtils.animate;
import static org.schabi.newpipe.ktx.ViewUtils.animateHideRecyclerViewAllowingScrolling;

import android.content.Context;
import android.content.res.ColorStateList;
import android.os.Bundle;
import android.text.TextUtils;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.widget.PopupWindow;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.content.res.AppCompatResources;
import androidx.core.content.ContextCompat;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.widget.RelativeLayout;

import com.google.android.material.shape.CornerFamily;
import com.google.android.material.shape.ShapeAppearanceModel;

import org.reactivestreams.Subscriber;
import org.reactivestreams.Subscription;
import org.schabi.newpipe.NewPipeDatabase;
import org.schabi.newpipe.R;
import org.schabi.newpipe.database.playlist.model.PlaylistRemoteEntity;
import org.schabi.newpipe.databinding.PlaylistControlBinding;
import org.schabi.newpipe.databinding.PlaylistHeaderBinding;
import org.schabi.newpipe.error.ErrorInfo;
import org.schabi.newpipe.error.ErrorUtil;
import org.schabi.newpipe.error.UserAction;
import org.schabi.newpipe.extractor.InfoItem;
import org.schabi.newpipe.extractor.ListExtractor;
import org.schabi.newpipe.extractor.ServiceList;
import org.schabi.newpipe.extractor.playlist.PlaylistInfo;
import org.schabi.newpipe.extractor.services.youtube.YoutubeParsingHelper;
import org.schabi.newpipe.extractor.stream.StreamInfoItem;
import org.schabi.newpipe.fragments.list.BaseListInfoFragment;
import org.schabi.newpipe.info_list.dialog.InfoItemDialog;
import org.schabi.newpipe.local.playlist.RemotePlaylistManager;
import org.schabi.newpipe.player.MainPlayer.PlayerType;
import org.schabi.newpipe.player.playqueue.PlayQueue;
import org.schabi.newpipe.player.playqueue.PlaylistPlayQueue;
import org.schabi.newpipe.util.ExtractorHelper;
import org.schabi.newpipe.util.Localization;
import org.schabi.newpipe.util.NavigationHelper;
import org.schabi.newpipe.util.PicassoHelper;
import org.schabi.newpipe.info_list.dialog.StreamDialogDefaultEntry;
import org.schabi.newpipe.util.external_communication.ShareUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;
import java.util.Collections;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import androidx.recyclerview.widget.RecyclerView;
import io.reactivex.rxjava3.android.schedulers.AndroidSchedulers;
import io.reactivex.rxjava3.core.Flowable;
import io.reactivex.rxjava3.core.Single;
import io.reactivex.rxjava3.disposables.CompositeDisposable;
import io.reactivex.rxjava3.disposables.Disposable;

public class PlaylistFragment extends BaseListInfoFragment<StreamInfoItem, PlaylistInfo> {

    private static final String PICASSO_PLAYLIST_TAG = "PICASSO_PLAYLIST_TAG";

    private CompositeDisposable disposables;
    private Subscription bookmarkReactor;
    private AtomicBoolean isBookmarkButtonReady;

    private RemotePlaylistManager remotePlaylistManager;
    private PlaylistRemoteEntity playlistEntity;

    /*//////////////////////////////////////////////////////////////////////////
    // Views
    //////////////////////////////////////////////////////////////////////////*/

    private PlaylistHeaderBinding headerBinding;
    private PlaylistControlBinding playlistControlBinding;

    private MenuItem playlistBookmarkButton;

    public static PlaylistFragment getInstance(final int serviceId, final String url,
                                               final String name) {
        final PlaylistFragment instance = new PlaylistFragment();
        instance.setInitialData(serviceId, url, name);
        return instance;
    }

    public PlaylistFragment() {
        super(UserAction.REQUESTED_PLAYLIST);
    }

    /*//////////////////////////////////////////////////////////////////////////
    // LifeCycle
    //////////////////////////////////////////////////////////////////////////*/

    @Override
    public void onCreate(final Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        disposables = new CompositeDisposable();
        isBookmarkButtonReady = new AtomicBoolean(false);
        remotePlaylistManager = new RemotePlaylistManager(NewPipeDatabase
                .getInstance(requireContext()));
    }

    @Override
    public View onCreateView(@NonNull final LayoutInflater inflater,
                             @Nullable final ViewGroup container,
                             @Nullable final Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_playlist, container, false);
    }

    /*//////////////////////////////////////////////////////////////////////////
    // Init
    //////////////////////////////////////////////////////////////////////////*/

    @Override
    protected Supplier<View> getListHeaderSupplier() {
        headerBinding = PlaylistHeaderBinding
                .inflate(activity.getLayoutInflater(), itemsList, false);
        playlistControlBinding = headerBinding.playlistControl;

        return headerBinding::getRoot;
    }

    @Override
    protected void initViews(final View rootView, final Bundle savedInstanceState) {
        super.initViews(rootView, savedInstanceState);

        infoListAdapter.setUseMiniVariant(true);
    }

    private PlayQueue getPlayQueueStartingAt(final StreamInfoItem infoItem) {
        return getPlayQueue(Math.max(infoListAdapter.getItemsList().indexOf(infoItem), 0));
    }

    @Override
    protected void showInfoItemDialog(final StreamInfoItem item) {
        final Context context = getContext();
        try {
            final InfoItemDialog.Builder dialogBuilder =
                    new InfoItemDialog.Builder(getActivity(), context, this, item);

            dialogBuilder
                    .setAction(
                            StreamDialogDefaultEntry.START_HERE_ON_BACKGROUND,
                            (f, infoItem) -> NavigationHelper.playOnBackgroundPlayer(
                                    context, getPlayQueueStartingAt(infoItem), true))
                    .create()
                    .show();
        } catch (final IllegalArgumentException e) {
            InfoItemDialog.Builder.reportErrorDuringInitialization(e, item);
        }
    }

    @Override
    public void onCreateOptionsMenu(@NonNull final Menu menu,
                                    @NonNull final MenuInflater inflater) {
        if (DEBUG) {
            Log.d(TAG, "onCreateOptionsMenu() called with: "
                    + "menu = [" + menu + "], inflater = [" + inflater + "]");
        }
        super.onCreateOptionsMenu(menu, inflater);
        inflater.inflate(R.menu.menu_playlist, menu);

        playlistBookmarkButton = menu.findItem(R.id.menu_item_bookmark);
        updateBookmarkButtons();
    }

    @Override
    public void onDestroyView() {
        headerBinding = null;
        playlistControlBinding = null;

        super.onDestroyView();
        if (isBookmarkButtonReady != null) {
            isBookmarkButtonReady.set(false);
        }

        if (disposables != null) {
            disposables.clear();
        }
        if (bookmarkReactor != null) {
            bookmarkReactor.cancel();
        }

        bookmarkReactor = null;
    }

    @Override
    public void onDestroy() {
        super.onDestroy();

        if (disposables != null) {
            disposables.dispose();
        }

        disposables = null;
        remotePlaylistManager = null;
        playlistEntity = null;
        isBookmarkButtonReady = null;
    }

    /*//////////////////////////////////////////////////////////////////////////
    // Load and handle
    //////////////////////////////////////////////////////////////////////////*/

    @Override
    protected Single<ListExtractor.InfoItemsPage<StreamInfoItem>> loadMoreItemsLogic() {
        return ExtractorHelper.getMorePlaylistItems(serviceId, url, currentNextPage);
    }

    @Override
    protected Single<PlaylistInfo> loadResult(final boolean forceLoad) {
        return ExtractorHelper.getPlaylistInfo(serviceId, url, forceLoad);
    }

    @Override
    public boolean onOptionsItemSelected(final MenuItem item) {
        switch (item.getItemId()) {
            case R.id.action_settings:
                NavigationHelper.openSettings(requireContext());
                break;
            case R.id.menu_item_openInBrowser:
                ShareUtils.openUrlInBrowser(requireContext(), url);
                break;
            case R.id.menu_item_share:
                if (currentInfo != null) {
                    ShareUtils.shareText(requireContext(), name, url,
                            currentInfo.getThumbnailUrl());
                }
                break;
            case R.id.menu_item_bookmark:
                onBookmarkClicked();
                break;
            case R.id.menu_item_shufflePlay:
                onShuffleClicked();
                break;
            default:
                return super.onOptionsItemSelected(item);
        }
        return true;
    }


    /*//////////////////////////////////////////////////////////////////////////
    // Contract
    //////////////////////////////////////////////////////////////////////////*/

    @Override
    public void showLoading() {
        super.showLoading();
        animate(headerBinding.getRoot(), false, 200);
        animateHideRecyclerViewAllowingScrolling(itemsList);

        PicassoHelper.cancelTag(PICASSO_PLAYLIST_TAG);
        animate(headerBinding.uploaderLayout, false, 200);
    }

    @Override
    public void handleResult(@NonNull final PlaylistInfo result) {
        super.handleResult(result);

        animate(headerBinding.getRoot(), true, 100);
        animate(headerBinding.uploaderLayout, true, 300);
        headerBinding.uploaderLayout.setOnClickListener(null);
        // If we have an uploader put them into the UI
        if (!TextUtils.isEmpty(result.getUploaderName())) {
            headerBinding.uploaderName.setText(result.getUploaderName());
            if (!TextUtils.isEmpty(result.getUploaderUrl())) {
                headerBinding.uploaderLayout.setOnClickListener(v -> {
                    try {
                        NavigationHelper.openChannelFragment(getFM(), result.getServiceId(),
                                result.getUploaderUrl(), result.getUploaderName());
                    } catch (final Exception e) {
                        ErrorUtil.showUiErrorSnackbar(this, "Opening channel fragment", e);
                    }
                });
            }
        } else { // Otherwise say we have no uploader
            headerBinding.uploaderName.setText(R.string.playlist_no_uploader);
        }

        playlistControlBinding.getRoot().setVisibility(View.VISIBLE);

        final String avatarUrl = result.getUploaderAvatarUrl();
        if (result.getServiceId() == ServiceList.YouTube.getServiceId()
                && (YoutubeParsingHelper.isYoutubeMixId(result.getId())
                || YoutubeParsingHelper.isYoutubeMusicMixId(result.getId()))) {
            // this is an auto-generated playlist (e.g. Youtube mix), so a radio is shown
            final ShapeAppearanceModel model = ShapeAppearanceModel.builder()
                    .setAllCorners(CornerFamily.ROUNDED, 0f)
                    .build(); // this turns the image back into a square
            headerBinding.uploaderAvatarView.setShapeAppearanceModel(model);
            headerBinding.uploaderAvatarView.setStrokeColor(
                    ColorStateList.valueOf(ContextCompat.getColor(
                            requireContext(), R.color.transparent_background_color))
            );
            headerBinding.uploaderAvatarView.setImageDrawable(
                    AppCompatResources.getDrawable(requireContext(),
                    R.drawable.ic_radio)
            );
        } else {
            PicassoHelper.loadAvatar(avatarUrl).tag(PICASSO_PLAYLIST_TAG)
                    .into(headerBinding.uploaderAvatarView);
        }

        headerBinding.playlistStreamCount.setText(Localization
                .localizeStreamCount(getContext(), result.getStreamCount()));

        if (!result.getErrors().isEmpty()) {
            showSnackBarError(new ErrorInfo(result.getErrors(), UserAction.REQUESTED_PLAYLIST,
                    result.getUrl(), result));
        }

        remotePlaylistManager.getPlaylist(result)
                .flatMap(lists -> getUpdateProcessor(lists, result), (lists, id) -> lists)
                .onBackpressureLatest()
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe(getPlaylistBookmarkSubscriber());

        playlistControlBinding.playlistCtrlPlayAllButton.setOnClickListener(view ->
                openEnqueueMethods()
                );
        //the play all button IS THE ENQUEUE METHOD BUTTON NOW
        //what tf is playlistControlBinding??????
        //why does it just return an error when i try to trace it???
        playlistControlBinding.playlistCtrlPlayPopupButton.setOnClickListener(view ->
                NavigationHelper.playOnPopupPlayer(activity, getPlayQueue(), false));
        playlistControlBinding.playlistCtrlPlayBgButton.setOnClickListener(view ->
                NavigationHelper.playOnBackgroundPlayer(activity, getPlayQueue(), false));

        playlistControlBinding.playlistCtrlPlayPopupButton.setOnLongClickListener(view -> {
            NavigationHelper.enqueueOnPlayer(activity, getPlayQueue(), PlayerType.POPUP);
            return true;
        });

        playlistControlBinding.playlistCtrlPlayBgButton.setOnLongClickListener(view -> {
            NavigationHelper.enqueueOnPlayer(activity, getPlayQueue(), PlayerType.AUDIO);
            return true;
        });
    }

    private PlayQueue getPlayQueue() {
        return getPlayQueue(0);
    }

    private PlayQueue getPlayQueue(final int index) {
        final List<StreamInfoItem> infoItems = new ArrayList<>();
        for (final InfoItem i : infoListAdapter.getItemsList()) {
            if (i instanceof StreamInfoItem) {
                infoItems.add((StreamInfoItem) i);
            }
        }
        return new PlaylistPlayQueue(
                currentInfo.getServiceId(),
                currentInfo.getUrl(),
                currentInfo.getNextPage(),
                infoItems,
                index
        );
    }


    private PlayQueue getRandomPlayQueue() {
        final List<StreamInfoItem> infoItems = new ArrayList<>();
        final List<InfoItem> processingList = infoListAdapter.getItemsList();
        final List<Integer> range = IntStream
                .rangeClosed(0, processingList.size() - 1)
                .boxed()
                .collect(Collectors.toList());
        Collections.shuffle(range);
        for (final int i : range) {
            final Object target = infoListAdapter.getItemsList().get(i);
            if (target instanceof StreamInfoItem) {
                infoItems.add((StreamInfoItem) target);
            }
        }
        return new PlaylistPlayQueue(
                currentInfo.getServiceId(),
                currentInfo.getUrl(),
                currentInfo.getNextPage(),
                infoItems,
                0
        );
    }

    private PlayQueue getReversedPlayQueue(final int index) {
        final List<StreamInfoItem> infoItems = new ArrayList<>();
        final List<InfoItem> processingList = infoListAdapter.getItemsList();
        //find the final value of index
        final int middle = processingList.size() / 2;
        final int indexAfterSwitch;
        if (processingList.size() % 2 != 0) {
            indexAfterSwitch = 2 * middle - index;
            //idk math or something
        } else {
            indexAfterSwitch = 2 * middle + 1 - index;
        }
        //simple list inversion
        for (int i = processingList.size() - 1; i > -1; i--) {
            final Object target = processingList.get(i);
            if (target instanceof StreamInfoItem) {
                infoItems.add((StreamInfoItem) target);
            }
        }
        return new PlaylistPlayQueue(
                currentInfo.getServiceId(),
                currentInfo.getUrl(),
                currentInfo.getNextPage(),
                infoItems,
                indexAfterSwitch
        );
    }
    //I was considering using a dialog but nooooooope too complex
    private void openEnqueueMethods() {

        final String[] buttons = {
                    getString(R.string.enqueue_method_all),
                    getString(R.string.notification_action_shuffle),
                    getString(R.string.minimize_on_exit_none_description),
                    getString(R.string.enqueue_method_only_next),
                    getString(R.string.enqueue_method_only_prev),
                    getString(R.string.enqueue_method_cont_next),
                    getString(R.string.enqueue_method_cont_prev),
                    };
        //there is probably a better way to get this type of usage 

        final AlertDialog.Builder builder = new AlertDialog.Builder(getActivity());
        builder.setTitle(R.string.enqueue_stream)
            .setItems(buttons, new DialogInterface.OnClickListener() {
            public void onClick(final DialogInterface dialog, final int num) {
                switch (num) {
                    case 0:
                        NavigationHelper.enqueueOnPlayer(activity,
                                getPlayQueue(),
                                PlayerType.AUDIO);
                        break;
                    case 1:
                        NavigationHelper.enqueueOnPlayer(activity,
                                getRandomPlayQueue(),
                                PlayerType.AUDIO);
                        break;
                    case 2:
                        final LayoutInflater inflater = getLayoutInflater();
                        final RecyclerView view = (RecyclerView) getActivity()
                                .findViewById(R.id.items_list);
                        final PopupWindow popupWindow = new PopupWindow(view,
                                RelativeLayout.LayoutParams.MATCH_PARENT,
                                RelativeLayout.LayoutParams.MATCH_PARENT,
                                true);
                        break;
                    case 3:
                        break;
                    case 4:
                        break;
                    case 5:
                        break;
                    case 6:
                        break;
                    case 7:
                        break;
                    default:
                        break;
                }
            }
        });
        builder.show();
    }
    /*//////////////////////////////////////////////////////////////////////////
    // Utils
    //////////////////////////////////////////////////////////////////////////*/

    private Flowable<Integer> getUpdateProcessor(
            @NonNull final List<PlaylistRemoteEntity> playlists,
            @NonNull final PlaylistInfo result) {
        final Flowable<Integer> noItemToUpdate = Flowable.just(/*noItemToUpdate=*/-1);
        if (playlists.isEmpty()) {
            return noItemToUpdate;
        }

        final PlaylistRemoteEntity playlistRemoteEntity = playlists.get(0);
        if (playlistRemoteEntity.isIdenticalTo(result)) {
            return noItemToUpdate;
        }

        return remotePlaylistManager.onUpdate(playlists.get(0).getUid(), result).toFlowable();
    }

    private Subscriber<List<PlaylistRemoteEntity>> getPlaylistBookmarkSubscriber() {
        return new Subscriber<>() {
            @Override
            public void onSubscribe(final Subscription s) {
                if (bookmarkReactor != null) {
                    bookmarkReactor.cancel();
                }
                bookmarkReactor = s;
                bookmarkReactor.request(1);
            }

            @Override
            public void onNext(final List<PlaylistRemoteEntity> playlist) {
                playlistEntity = playlist.isEmpty() ? null : playlist.get(0);

                updateBookmarkButtons();
                isBookmarkButtonReady.set(true);

                if (bookmarkReactor != null) {
                    bookmarkReactor.request(1);
                }
            }

            @Override
            public void onError(final Throwable throwable) {
                showError(new ErrorInfo(throwable, UserAction.REQUESTED_BOOKMARK,
                        "Get playlist bookmarks"));
            }

            @Override
            public void onComplete() { }
        };
    }

    @Override
    public void setTitle(final String title) {
        super.setTitle(title);
        if (headerBinding != null) {
            headerBinding.playlistTitleView.setText(title);
        }
    }

    private void onBookmarkClicked() {
        if (isBookmarkButtonReady == null || !isBookmarkButtonReady.get()
                || remotePlaylistManager == null) {
            return;
        }

        final Disposable action;

        if (currentInfo != null && playlistEntity == null) {
            action = remotePlaylistManager.onBookmark(currentInfo)
                    .observeOn(AndroidSchedulers.mainThread())
                    .subscribe(ignored -> { /* Do nothing */ }, throwable ->
                            showError(new ErrorInfo(throwable, UserAction.REQUESTED_BOOKMARK,
                                    "Adding playlist bookmark")));
        } else if (playlistEntity != null) {
            action = remotePlaylistManager.deletePlaylist(playlistEntity.getUid())
                    .observeOn(AndroidSchedulers.mainThread())
                    .doFinally(() -> playlistEntity = null)
                    .subscribe(ignored -> { /* Do nothing */ }, throwable ->
                            showError(new ErrorInfo(throwable, UserAction.REQUESTED_BOOKMARK,
                                    "Deleting playlist bookmark")));
        } else {
            action = Disposable.empty();
        }

        disposables.add(action);
    }
    private void onShuffleClicked() {

        NavigationHelper.playOnBackgroundPlayer(activity, getRandomPlayQueue(), false);

    }

    private void updateBookmarkButtons() {
        if (playlistBookmarkButton == null || activity == null) {
            return;
        }

        final int drawable = playlistEntity == null
                ? R.drawable.ic_playlist_add : R.drawable.ic_playlist_add_check;

        final int titleRes = playlistEntity == null
                ? R.string.bookmark_playlist : R.string.unbookmark_playlist;

        playlistBookmarkButton.setIcon(drawable);
        playlistBookmarkButton.setTitle(titleRes);
    }
}
