/**
 *  This is an Example Fragment that works with an adapter to
 *  display a large list of images, videos, and audio files. It handles various touch gestures
 *  using custom classes. The grid view will animate and scale to desired size on pinch zoom in and
 *  out. This Fragment handles data from a DDP subscription. If the user isn't scrolling it will
 *  prefetch the thumbnail bitmaps head of the visible items on the list on the background thread.
 *  The data coming in from the DDP subscription is added to the DataBase on the
 *  background thread. if the data is needed in the list it will then be added to objectListSync and
 *  the adapter will be notified. This Fragment gets it's data from the database not the data
 *  coming in from the DDP subscription directly. In the DB it has the best thumbs for the current
 *  state of the app. Even if new thumbs are coming in it will only update if it's needed. If there
 *  was locally create assets it will verify they still exist. If not the data will be updated in
 *  Realm and default to the servers data if present.
 *
 *  I have added other classes directly into this Fragment class that I normally would not for
 *  sake of showing the example. I have kept the variable names and class names as generic as 
 *  possible
 */

/**
 * Created by Wes Gue on 9/27/2016.
 */

public class ExampleFragment extends Fragment implements MeteorCallback {

    //
    private String mTAG = "ExampleFragment";
    // this list will sync from the the Realm DB
    private List<JSONObject> objectListSync = new ArrayList<JSONObject>();
    // used for precaching data for items that aren't on screen yet
    private List<ImageListAdapter.ImageRowInfo> dataNew;
    private boolean precachingRunning = false;
    // ID of this collection list
    private String collectionId;
    // ID created during offline use to sync later with collectionId from server
    private String collection_client_record_id;
    // visible states for tab in Example activity
    private Boolean mIsVisible = false;
    private Boolean mIsRateVisible = false;
    private Boolean mIsSelectVisible = false;
    private View layout;
    private GPRecyclerView recyclerView;
    private ImageListAdapter adapter;
    private GridLayoutManager mGridLayoutManager;
    private LinearLayout selectWrapperLayout;
    // used for a Meteor subscription to get DDP calls from the Server
    private Meteor mMeteor;
    private String assetSub;
    // used on start up to make sure the adapter doesn't get set twice
    private boolean adapterSet = false;
    // layouts for bars
    private LinearLayout toolbarContainer;
    private LinearLayout tabBar;
    // used for scrolling. Gets set to current last item on the screen in the list
    private int currentLast;
    private int scrollingState = 0;
    // will be true if user is scrolling down
    private boolean scroll_down;
    // used to make sure toolbar does not animate off screen if scroll is locked
    private boolean isScrollLocked = false;
    // pages for DDP subscription are a size of 40.
    private int pageSize = 40;
    // changePageAt starts off at half of pageSize
    private int changePageAt = (pageSize / 2);
    // currentLimit will starts off as one page.
    private int currentLimit = pageSize;
    // used for adding assets from Realm
    private int leftOff = 40;
    // used for saving position in list on orientation change
    private static Bundle mBundleRecyclerViewState;
    private final String KEY_RECYCLER_STATE = "recycler_state";

    public ExampleFragment() {
        // Required empty public constructor
    }

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // setting Arguments sent from ExampleActivity
        collectionId = getArguments().getString("mCollectionId");
        collection_client_record_id = getArguments().getString("COLLECTION_CLIENT_RECORD_ID");
        // getting tab states for toolbar
        mIsVisible = getArguments().getBoolean("selectSwitch");
        mIsRateVisible = getArguments().getBoolean("rateSwitch");
        mIsSelectVisible = getArguments().getBoolean("selectBool");

        // setting DDP Subscription
        mMeteor = new Meteor(getActivity(), "wss://example.123.thisisntreal/websocket");
        mMeteor.addCallback(this);
        mMeteor.connect();

        Map<String, String> map = new HashMap<String, String>();
        // gets session ID from APIClient. APIClient is a Singleton used for anything API related
        map.put("session_id", APIClient.getInstance().getSessionId());
        map.put("collection_id", collectionId);
        // current limit being set to one page
        map.put("limit", Integer.toString(currentLimit));
        assetSub = mMeteor.subscribe("assets", new Object[]{map});

        // setting up toolbar containers
        ExampleActivity exampleActivity = (ExampleActivity) getActivity();
        toolbarContainer = exampleActivity.getToolbarContainer();
        tabBar = exampleActivity.getTabBar();

        // Adding Event to Tracker. TrackingClient is a Singleton that collects events and sends
        // to the server after a certain amount of time or it's reached it's set limit
        TrackingClient.getInstance().addEvent(getActivity(), "Example Gallery");
        APIClient.getInstance().setCurrentScreen("examplefragment");


    }

    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container,
                             final Bundle savedInstanceState) {
        // setting layout
        layout = inflater.inflate(R.layout.fragment_example, container, false);
        // using a custom recycler view that allows listens and scales grid views based on pinch gestures
        recyclerView = (ExampleRecyclerView) layout.findViewById(R.id.recycler_pic_view_id);
        // add custom decoration
        recyclerView.addItemDecoration(new SpacesItemDecoration(3));
        recyclerView.setExampleFragment(this);
        recyclerView.setItemViewCacheSize(0);
        // set to true for better performance
        recyclerView.setHasFixedSize(true);
        recyclerView.hasFixedSize();
        selectWrapperLayout = (LinearLayout) layout.findViewById(R.id.select_wrapper_id);
        selectViewLayout = (LinearLayout) layout.findViewById(R.id.select_menu_id);
        // set adapter and get the current column number. gets set by ExampleRecyclerView
        setAdapter(APIClient.getInstance().getCurrentColumnNum());
        // set adapterSett to true. Now it will only update.
        adapterSet = true;
        // sets Select Menus and draws custom fonts
        setSelectMenus(getActivity());
        // starts AsyncTask in the background to start adding data from Realm to the list
        APIClient.getInstance().setList(this);
        // checks state of what's visible in the gallery and sets accordingly
        if (APIClient.getInstance().isSelectVisisbleInGallery()) {
            setCheckBoxVis(true);
            mIsRateVisible = false;
            showSelectView(true);
            updateRateView(mIsRateVisible);
            setmIsSelectVisible(true);
            lockScroll();
        }

        return layout;
    }

    @Override
    public void onViewCreated(final View view, Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        // sets on scroll listener to handle gestures coming in
        recyclerView.addOnScrollListener(new RecyclerView.OnScrollListener() {

            @Override
            public void onScrolled(RecyclerView recyclerView, int dx, int dy) {
                super.onScrolled(recyclerView, dx, dy);
                if (dy > 10) {
                    scroll_down = true;
                } else if (dy < -2) {
                    scroll_down = false;
                }
            }

            @Override
            public void onScrollStateChanged(RecyclerView recyclerView, int newState) {
                super.onScrollStateChanged(recyclerView, newState);
                scrollingState = newState;
                // gets the posistion of the last item on the screen from the list
                currentLast = mGridLayoutManager.findLastVisibleItemPosition() + 1;

                if (scrollingState == 0) {
                    // Not scrolling precache future bitmaps
                    preUpload(mGridLayoutManager.findLastVisibleItemPosition());
                    // bring toolback down since the user isn't scrolling
                    animateToolBarDown();
                }

                if (mGridLayoutManager.findFirstVisibleItemPosition() == 0) {
                    // locking scoll will make sure no animations or precaching will take place
                    lockScroll();
                } else {
                    isScrollLocked = false;
                }

                if (scroll_down && !isScrollLocked) {
                    // animate bar out of view
                    animateToolBarUp();

                } else if (!mIsSelectVisible) {
                    // animate bar into view
                    animateToolBarDown();
                }
                if (scroll_down) {
                    if (currentLast >= changePageAt) {
                        // users pasted changePageAt. un updatePaging
                        updatePaging();
                    }
                }

            }

        });
    }

    @Override
    public void onActivityCreated(Bundle savedInstanceState) {
        super.onActivityCreated(savedInstanceState);

    }

    @Override
    public void onAttach(Context context) {
        super.onAttach(context);
        if (context instanceof OnFragmentInteractionListener) {
            mListener = (OnFragmentInteractionListener) context;
        } else {
            throw new RuntimeException(context.toString()
                    + " must implement OnFragmentInteractionListener");
        }
    }

    @Override
    public void onDetach() {
        super.onDetach();
        mListener = null;
    }

    @Override
    public void onConnect(boolean signedInAutomatically) {
        Log.e(mTAG, " Meteor onConnect");
    }

    @Override
    public void onDisconnect() {
        Log.e(mTAG, " onDisconnect");
    }

    @Override
    public void onException(Exception e) {

    }
    // onDataAdded call from Meteors DDP response
    @Override
    public void onDataAdded(String collectionName, String documentID, String newValuesJson) {
        if (collectionName.equals("assets")) {
            //add new data to realm on the background thread. list will update as need on onPostExecute()
            APIClient.getInstance().addRealmAsset(newValuesJson, this, getActivity(), collectionId,false,documentID);
        }
    }

    // onDataChanged call from Meteors DDP response
    @Override
    public void onDataChanged(String collectionName, String documentID, String updatedValuesJson, String removedValuesJson) {
        if (collectionName.equals("assets")) {
            //update data to realm on the background thread. list will update as need on onPostExecute()
            APIClient.getInstance().addRealmAsset(updatedValuesJson, this, getActivity(), collectionId,true,documentID);
        }
    }

    // onDataRemoved call from Meteors DDP response
    @Override
    public void onDataRemoved(String collectionName, String documentID) {
        // delete asset from Realm
        RealmHelper.deleteAssetWithDocumentId(getActivity(),documentID);
        // set updated list from realm
        setAssetListFromRealm();
        // notify adapter of item removed
        updateListAdapter();
    }

    @Override
    public void onDestroy() {
        mMeteor.disconnect();
        mMeteor.removeCallback(this);
        mMeteor.unsubscribe(assetSub);
        super.onDestroy();
    }

    @Override
    public void onPause()
    {
        super.onPause();
        // save RecyclerView state
        mBundleRecyclerViewState = new Bundle();
        Parcelable listState = recyclerView.getLayoutManager().onSaveInstanceState();
        mBundleRecyclerViewState.putParcelable(KEY_RECYCLER_STATE, listState);
    }

    @Override
    public void onResume()
    {
        super.onResume();
        // restore RecyclerView state
        if (mBundleRecyclerViewState != null) {
            Parcelable listState = mBundleRecyclerViewState.getParcelable(KEY_RECYCLER_STATE);
            recyclerView.getLayoutManager().onRestoreInstanceState(listState);
        }
    }


    public interface OnFragmentInteractionListener {
        void onFragmentInteraction(Uri uri);
    }

    // get data from objectListSync and set ImageRowInfo for adapter
    public List<ImageListAdapter.ImageRowInfo> getData(List<JSONObject> mObjList) throws JSONException {
        dataNew = new ArrayList<>();
        if (mObjList.size() != 0) {
            for (JSONObject obj : mObjList) {

                String type = obj.optString("type");
                String original = obj.optString("original");
                String previewLarge = obj.optString("preview_large");
                String previewMedium = obj.optString("preview_medium");
                String previewSmall = obj.optString("preview_small");
                String thumbnailLarge = obj.optString("thumbnail_large");
                String thumbnailMedium = obj.optString("thumbnail_medium");
                String thumbnailSmall = obj.optString("thumbnail_small");
                String thumbnailTiny = obj.optString("thumbnail_tiny");
                String thumbnailClient = obj.optString("thumbnail_client");
                String thumbnailInline = obj.optString("thumbnail_inline");
                String documentId = obj.optString("document_id");
                String asset = obj.optString("asset_id");
                String mime = obj.optString("mime");
                String url = obj.optString("url");
                String client_record_id = obj.optString("client_record_id");
                String createdAt;
                String localFileVideoThumb;
                if (!obj.isNull("created_at")) {
                    createdAt = obj.getString("created_at");
                } else {
                    createdAt = null;
                }
                if (!obj.isNull("local_file_video_thumb")) {
                    localFileVideoThumb = obj.getString("local_file_video_thumb");
                } else {
                    localFileVideoThumb = null;
                }
                String poolId = obj.optString("pool_id");
                int ratingSum = obj.optInt("rating_sum");
                // only will be valid if file is a local file
                String localFile = obj.optString("local_file_name");
                String localFileEnd = obj.optString("local_file_name_end");
                boolean isFile = isValidLocalUri(localFile, obj.optBoolean("is_gp_created"));
                if (!isFile) {
                    RealmHelper.setAssetLocalFalse(getActivity(), asset);
                }
                ImageListAdapter.ImageRowInfo current = new ImageListAdapter.ImageRowInfo();
                current.picId = R.drawable.ic_image_black_24dp;

                if (isFile) {
                    current.isLocalFile = true;
                    current.local_file = localFile;
                    current.local_file_end = localFileEnd;
                    current.client_record_id = client_record_id;

                } else {
                    current.isLocalFile = false;
                }
                current.original = original;
                current.previewLarge = previewLarge;
                current.previewMedium = previewMedium;
                current.previewSmall = previewSmall;
                current.thumbLarge = thumbnailLarge;
                current.thumbMedium = thumbnailMedium;
                current.thumbSmall = thumbnailSmall;
                current.thumbTiny = thumbnailTiny;
                current.thumbClient = thumbnailClient;
                current.thumbInline = thumbnailInline;
                current.documentId = documentId;
                current.type = type;
                current.orientation = "";
                current.assetId = asset;
                current.poolId = poolId;
                current.rating_sum = ratingSum;
                current.created_at = createdAt;
                current.local_file_video_thumb = localFileVideoThumb;
                current.mime = mime;
                current.documentId = documentId;
                dataNew.add(current);
            }
        }
        return dataNew;
    }
    // updates list for adapter
    public void updateListAdapter() {
        try {
            adapter.updateList(getData(objectListSync));
        } catch (JSONException e) {
            e.printStackTrace();
        }
    }
    // updates list for adapter on background thread
    public void updateListAdapterAsync() {
        try {
            adapter.updateListAsync(getData(objectListSync));
        } catch (JSONException e) {
            e.printStackTrace();
        }
    }
    // sets visibility of checkbox
    public void setCheckBoxVis(boolean choice) {
        if (adapter != null) {
            adapter.showCheckBox(choice);
            adapter.initCheckBoxFalse();
        }
    }
    // // sets visibility of select layouts
    public void showSelectView(boolean shouldShow) {
        if (shouldShow) {
            selectWrapperLayout.setVisibility(View.VISIBLE);
            adapter.setmIsSelectVisible(true);
            APIClient.getInstance().setSelectVisisbleInGallery(true);
            updateGrid(3);

        } else {
            APIClient.getInstance().setSelectVisisbleInGallery(false);
            selectWrapperLayout.setVisibility(View.GONE);
            adapter.setmIsSelectVisible(false);
            adapter.notifyDataSetChanged();
        }
    }

    public void setmIsRateVisible(boolean bool) {
        mIsRateVisible = bool;
    }

    public void setmIsSelectVisible(boolean bool) {
        mIsSelectVisible = bool;
    }

    // returns list of assets from the adapter
    public ArrayList<String> getAdapterListAssetCheck() {
        return adapter.getListAssetCheck();
    }
    // set up adapter for the first time
    public void setAdapter(int gridNum) {
        currentGridNum = gridNum;
        try {
            adapter = new ImageListAdapter(getActivity(), getData(objectListSync), mIsVisible, mIsRateVisible, this, collection_client_record_id, collectionId);
            // set to true for performance
            adapter.setHasStableIds(true);
        } catch (JSONException e) {
            e.printStackTrace();
        }
        recyclerView.setAdapter(adapter);
        // using custom gridLayoutManger to fix bugs with android when having list getting updated from other threads
        mGridLayoutManager = new WrapContentGridLayoutManager(getActivity(), gridNum);
        recyclerView.setLayoutManager(mGridLayoutManager);


    }

    // retrieves data from the Realm Database
    public void setAssetListFromRealm() {
        try {
            objectListSync = RealmHelper.getAssetListFromRealm(getActivity(), collection_client_record_id, this, currentLimit);
            // if the adapter is already set then update the adapter
            if (adapterSet) {
                updateListAdapter();
            }
        } catch (JSONException e) {
            e.printStackTrace();
        }
    }

    // retrieves data from the Realm Database on the background thread
    public void setAssetListFromRealmAsync() {

        try {
            objectListSync = RealmHelper.getAssetListFromRealm(getActivity(), collection_client_record_id, this, currentLimit);

            if (adapterSet) {
                updateListAdapterAsync();
            }

        } catch (JSONException e) {
            e.printStackTrace();
        }
    }

    // add only only one asset from the Realm Database
    public void addSingleAssetFromRealm(String document_id, String client_record_id) {

        try {
            // appends the last added RealmAsset data to the list
            objectListSync.add(RealmHelper.getLastFromRealm(getActivity(), document_id,client_record_id));
            if (!APIClient.getInstance().isUpdateImageListInProgress()) {
                // if there isn't anything in the list it starts from scratch
                if (objectListSync.size() == 0) {
                    APIClient.getInstance().setList(this);

                } else if (adapterSet) {
                    // list is set and already has data. Just update on background thread
                    updateListAdapterAsync();
                }
            }

        } catch (JSONException e) {
            e.printStackTrace();
        }
    }

    // update single Asset
    public void updateSingleAssetFromRealm(String document_id) {

        try {
            JSONObject objectToAdd = RealmHelper.getSingleUpdateFromRealm(getActivity(),document_id,collection_client_record_id);
            if(APIClient.getInstance().getPositionInPoolToUpdate()<objectListSync.size()) {
                objectListSync.set(APIClient.getInstance().getPositionInPoolToUpdate(), objectToAdd);
                if (!APIClient.getInstance().isUpdateImageListInProgress()) {
                    if (objectListSync.size() == 0) {
                        APIClient.getInstance().setList(this);
                    } else if (adapterSet) {
                        updateListAdapterAsync();
                    }
                }
            }
        } catch (JSONException e) {
            e.printStackTrace();
        }
    }

    // update gride from ExampleRecyclerView's gusture detection
    public void updateGrid(int num) {
        APIClient.getInstance().setCurrentColumnNum(num);
        mGridLayoutManager.setSpanCount(num);
        currentSpanCount = num;
        if (adapter.getmIsRateVisible()) {
            adapter.setStatusSyncedFalse();
        }
        adapter.notifyDataSetChanged();
    }

    // update rate view
    public void updateRateView(boolean isRateVisible) {

        adapter.setmIsRateVisible(isRateVisible);
        adapter.setStatusSyncedFalse();
        adapter.notifyDataSetChanged();
    }

    // used in getData(). checks to see if local files exist before trying to use local information
    // for that Asset. Will default to Server data if it exist.
    private boolean isValidLocalUri(String uriString, boolean gpCreated) {
        if (uriString != null) {
            if (gpCreated) {
                if (uriString.equals("")) {
                    return false;
                } else {
                    File sourceFile = new File(uriString);
                    return sourceFile.isFile();
                }
            } else {
                if (uriString.equals("")) {
                    return false;
                } else {
                    File sourceFile = new File(FileHelper.getPath(getActivity(), Uri.parse(uriString)));
                    return sourceFile.isFile();
                }
            }
        } else {
            return false;
        }
    }

    // run precaching of Asset bitmaps in the background
    public void preUpload(int position) {
        if (dataNew.size() >= (position+1) && position >= 0) {
            dataNew.get(position).setCached(true);
        }
        // only does one at a time so the list doesn't pile up before the user starts scrolling again
        if (!precachingRunning) {
            if (scrollingState == 0 && (position + 1) <= (dataNew.size() - 1)) {
                if (!dataNew.get(position + 1).isCached()) {
                    new PreCacheAssetsAsyncTask(getActivity(), dataNew, (position + 1), this, null, 360, 360).execute();
                }
            }
        }

    }

    // used in the adapter to check if it's the last visible item
    public boolean isLastViewPosition(int position) {
        if (position == mGridLayoutManager.findLastVisibleItemPosition()) {
            return true;
        } else {
            return false;
        }
    }

    // animate toolbars Up
    public void animateToolBarUp() {
        toolbarContainer.animate().translationY(-toolbarContainer.getHeight()).setInterpolator(new AccelerateInterpolator(4));
        tabBar.animate().translationY(-(toolbarContainer.getHeight() + tabBar.getHeight() + 2)).setInterpolator(new AccelerateInterpolator(4));
    }

    // animate toobars Down
    public void animateToolBarDown() {
        toolbarContainer.animate().translationY(0).setInterpolator(new DecelerateInterpolator(4)).start();
        tabBar.animate().translationY(0).setInterpolator(new DecelerateInterpolator(4)).start();
    }
    // used to set precachingRunning from adapter
    public void setPrecachingRunning(boolean bool) {
        precachingRunning = bool;
    }

    // setting UI elements
    private void setSelectMenus(Context cxt) {
        FontTextView fontTag = (FontTextView) layout.findViewById(R.id.tag);
        fontTag.setCustomFont(cxt, "fontawesome.ttf");
        fontTag.setText(R.string.fa_tag);

        fontTag.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                Intent intent = new Intent(getActivity(), ChooseTagsActivity.class);
                startActivity(intent);
            }
        });

        FontTextView fontAddToPool = (FontTextView) layout.findViewById(R.id.add_to_pool);
        fontAddToPool.setCustomFont(cxt, "fontawesome.ttf");
        fontAddToPool.setText(R.string.fa_add_to_pool);
        fontAddToPool.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {

                ArrayList<String> listAssets = getAdapterListAssetCheck();
                if (listAssets == null) {
                    Toast.makeText(getActivity(), " Please Make A Selection ", Toast.LENGTH_SHORT).show();
                } else {

                    //copy your List of Strings into the Array ,and then pass it in your intent
                    // ....
                    Intent intent = new Intent(getActivity(), AddToPoolActivity.class);
                    intent.putStringArrayListExtra("items_to_parse", (ArrayList<String>) listAssets);
                    startActivity(intent);
                }
            }
        });

        FontTextView fontDownload = (FontTextView) layout.findViewById(R.id.download);
        fontDownload.setCustomFont(cxt, "fontawesome.ttf");
        fontDownload.setText(R.string.fa_download);

        FontTextView fontReport = (FontTextView) layout.findViewById(R.id.report);
        fontReport.setCustomFont(cxt, "fontawesome.ttf");
        fontReport.setText(R.string.fa_report);

        FontTextView fontDelete = (FontTextView) layout.findViewById(R.id.delete);
        fontDelete.setCustomFont(cxt, "fontawesome.ttf");
        fontDelete.setText(R.string.fa_delete);

        fontDelete.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                adapter.deleteSelectedAssets();
            }
        });

        // Select Bottom Bar

        FontTextView fontPrintBottom = (FontTextView) layout.findViewById(R.id.print_bottom);
        fontPrintBottom.setCustomFont(cxt, "fontawesome.ttf");
        fontPrintBottom.setText(R.string.fa_print);

        FontTextView fontTagBottom = (FontTextView) layout.findViewById(R.id.tag_bottom);
        fontTagBottom.setCustomFont(cxt, "fontawesome.ttf");
        fontTagBottom.setText(R.string.fa_tag);

        FontTextView fontAddToPoolBottom = (FontTextView) layout.findViewById(R.id.add_to_pool_bottom);
        fontAddToPoolBottom.setCustomFont(cxt, "fontawesome.ttf");
        fontAddToPoolBottom.setText(R.string.fa_add_to_pool);
        fontAddToPoolBottom.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                Intent intent = new Intent(getApplicationContext(), PoolActivity.class);
                startActivity(intent);
            }
        });

        FontTextView fontDownloadBottom = (FontTextView) layout.findViewById(R.id.download_bottom);
        fontDownloadBottom.setCustomFont(cxt, "fontawesome.ttf");
        fontDownloadBottom.setText(R.string.fa_download);

        FontTextView fontReportBottom = (FontTextView) layout.findViewById(R.id.report_bottom);
        fontReportBottom.setCustomFont(cxt, "fontawesome.ttf");
        fontReportBottom.setText(R.string.fa_report);

        FontTextView fontDeleteBottom = (FontTextView) layout.findViewById(R.id.delete_bottom);
        fontDeleteBottom.setCustomFont(cxt, "fontawesome.ttf");
        fontDeleteBottom.setText(R.string.fa_delete);

        FontTextView fontCancelBottom = (FontTextView) layout.findViewById(R.id.cancel_bottom);
        fontCancelBottom.setCustomFont(cxt, "fontawesome.ttf");
        fontCancelBottom.setText(R.string.fa_cancel);


        FontTextView fontNotificationX = (FontTextView) findViewById(R.id.font_notification_x);
        fontCancelBottom.setCustomFont(cxt, "fontawesome.ttf");
        fontCancelBottom.setText(R.string.fa_x);

        final LinearLayout layoutNotification = (LinearLayout) findViewById(R.id.layout_notification);

        fontNotificationX.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                layoutNotification.setVisibility(View.GONE);
            }
        });


    }

    // future animations won't take place if isScrollLocked = true
    public void lockScroll() {
        isScrollLocked = true;
        animateToolBarDown();
    }

    // update Paging Size and currantPageAt
    public void updatePaging() {
        Map map = new HashMap();
        map.put("subscription_id", assetSub);

        currentLimit += pageSize;
        changePageAt += (pageSize / 2);
        map.put("limit", currentLimit);

        mMeteor.call("subscription_resize", new Object[]{map}, new ResultListener() {
            @Override
            public void onSuccess(String result) {
                Log.d(mTAG, "Meteor resize SUCCESS ON ASSETS!");

            }

            @Override
            public void onError(String error, String reason, String details) {
                Log.e(mTAG, "Meteor resize ERROR! :" + error);
                Log.e(mTAG, "Meteor resize reason : !" + reason);
            }
        });
        APIClient.getInstance().updatePageList(this);
    }

    // used to append item to list
    public void notifyAdapterInsert() {
        if (adapter != null) {
            adapter.notifyItemInserted(objectListSync.size()-1);
        }
    }

    // used to notify adapter of change to data set
    public void notifyAdapterSetChanged() {
        if (adapter != null) {
            adapter.notifyDataSetChanged();

        }
    }

    // notify adapter of update to posistion
    public void notifyUpdate(int position) {
        if (adapter != null && position>=0 ) {
            adapter.notifyItemChanged(position);
        }
    }

    // returns current data size
    public int getDataSize(){
        return objectListSync.size();
    }
}