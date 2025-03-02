package com.aluminati.inventory.fragments.purchase;

import android.os.Bundle;
import android.os.Handler;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.drawerlayout.widget.DrawerLayout;
import androidx.recyclerview.widget.ItemTouchHelper;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.aluminati.inventory.Constants;
import com.aluminati.inventory.binders.PurchaseBinder;
import com.aluminati.inventory.firestore.UserFetch;
import com.aluminati.inventory.fragments.FloatingTitlebarFragment;
import com.aluminati.inventory.R;
import com.aluminati.inventory.adapters.ItemAdapter;
import com.aluminati.inventory.adapters.swipelisteners.ItemSwipe;
import com.aluminati.inventory.fragments.recent.RecentFragment;
import com.aluminati.inventory.firestore.DbHelper;
import com.aluminati.inventory.helpers.DialogHelper;
import com.aluminati.inventory.payments.model.Payment;
import com.aluminati.inventory.payments.selectPayment.SelectPayment;
import com.aluminati.inventory.utils.Toaster;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseUser;
import com.google.firebase.firestore.DocumentSnapshot;
import com.google.firebase.firestore.FirebaseFirestore;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public class PurchaseFragment extends FloatingTitlebarFragment implements GetCardRefNumber {

    private static final String TAG = PurchaseFragment.class.getSimpleName();
    private FirebaseFirestore firestore;
    private FirebaseAuth auth;
    private RecyclerView recViewPurchase;
    private ItemAdapter itemAdapter;
    private ItemAdapter.OnItemClickListener itemClickListener;
    private Toaster toaster;
    private DbHelper dbHelper;
    private DialogHelper dialogHelper;
    private PurchaseBinder purchaseBinder;
    private double currentTotal;
    private FirebaseUser firebaseUser;
    private int currentQuantity;

    public PurchaseFragment() {super(null);}
    public PurchaseFragment(DrawerLayout drawer) {
        super(drawer);
    }
    public PurchaseFragment(DrawerLayout drawer, Handler handler) {
        super(drawer, handler);
    }

    public void setNavDrawer(DrawerLayout drawer) {
        this.drawer = drawer;
    }
    //Update
    public View onCreateView(@NonNull LayoutInflater inflater,
                             ViewGroup container, Bundle savedInstanceState) {

        View root = inflater.inflate(R.layout.fragment_purchase, container, false);
        super.setView(root);

        floatingTitlebar.setLeftToggleOn(false);//dont change icon on toggle
        dbHelper = DbHelper.getInstance();
        firebaseUser = FirebaseAuth.getInstance().getCurrentUser();
        dialogHelper = DialogHelper.getInstance(getActivity());

        purchaseBinder = new PurchaseBinder();
        //How to programmatically set icons on floating action bar
        floatingTitlebar.setRightToggleIcons(R.drawable.ic_dollar, R.drawable.ic_dollar);
        floatingTitlebar.setLeftToggleIcons(R.drawable.ic_search, R.drawable.ic_side_list_blue);
        floatingTitlebar.setToggleActive(true);
        floatingTitlebar.showTitleTextBar();

        firestore = FirebaseFirestore.getInstance();
        auth = FirebaseAuth.getInstance();
        toaster = Toaster.getInstance(getActivity());

        recViewPurchase = root.findViewById(R.id.recViewPurchase);
        LinearLayoutManager layoutManager = new LinearLayoutManager(getActivity(),
                LinearLayoutManager.VERTICAL, false);
        recViewPurchase.setLayoutManager(layoutManager);
        itemClickListener = (ItemAdapter.OnItemClickListener<PurchaseItem>) item -> {

            dialogHelper.createDialog(item.getTitle(), item.toString(),null, null).show();
        };

        //Load items
        reloadItems(null);
        setTrackerSwipe();
        setUpCartListener();
        return root;
    }

    private void setUpCartListener() {
        firestore.collection(String.format(Constants.FirestoreCollections.LIVE_USER_CART,
                FirebaseAuth.getInstance().getUid())).addSnapshotListener((snapshot, e) -> {
                    if(snapshot != null && snapshot.size() > 0) {
                        loadPurchaseItems(initPurchaseItems(snapshot.getDocuments(), null));
                        Log.d(TAG, "addSnapshotListener: Items found " + snapshot.size());
                    } else {
                        recViewPurchase.setAdapter(null);
                        floatingTitlebar.setTitleText("");
                    }

                });

    }

    private List<PurchaseItem> initPurchaseItems(List<DocumentSnapshot> snapshots, String filter) {
        List<PurchaseItem> pItems = new ArrayList<>();
        double total = 0;
        currentTotal = 0;
        currentQuantity = 0;

        for(DocumentSnapshot obj : snapshots) {
            PurchaseItem p = obj.toObject(PurchaseItem.class);
            currentTotal += (p.getPrice() * p.getQuantity());
            currentQuantity += p.getQuantity();

            p.setDocID(obj.getId());
            if(filter == null || filter.trim().length() == 0) {
                pItems.add(p);
                total += (p.getPrice() * p.getQuantity());
            } else if(p.getTitle().toLowerCase().contains(filter.toLowerCase())
                    || (p.getTags()!= null && p.getTags().contains(filter.toLowerCase()))) {
                pItems.add(p);
                total += (p.getPrice() * p.getQuantity());
            }
        }

        currentTotal = total;

        floatingTitlebar.setTitleText(String.format("Total: €%.2f", total));
        return pItems;
    }
    private void loadPurchaseItems(List<PurchaseItem> purchaseItems) {
        recViewPurchase.setAdapter(new ItemAdapter<PurchaseItem>(purchaseItems,
                itemClickListener,
                purchaseBinder,
                R.layout.list_item,
                getActivity()));
    }

    @Override
    public void onLeftButtonToggle(boolean isActive) {
        super.onLeftButtonToggle(isActive);
        if(isActive) {
            floatingTitlebar.showSearchBar();
        } else floatingTitlebar.showTitleTextBar();
    }
    @Override
    public void onRightButtonToggle(boolean isActive) {
        super.onRightButtonToggle(isActive);


        if(currentQuantity == 0 ) {
            dialogHelper.createDialog(getResources().getString(R.string.no_items),
                    getResources().getString(R.string.no_items_msg), null, null).show();

            return;
        }

        dialogHelper.createDialog("Checkout",
                String.format("Order Details:\n\nTotal Items:  %d\nTotal Price: €%.2f",
                        currentQuantity, currentTotal), new DialogHelper.IClickAction() {
                    @Override
                    public void onAction() {
                        completeOrder("cash", null);
                    }

                    @Override
                    public String actionName() {
                        return "Cash";
                    }
                }, new DialogHelper.IClickAction() {
                    @Override
                    public void onAction() {
                        //TODO: @Bart do whatever here then call completeOrder();
                        decryptPayments(firebaseUser);
                    }

                    @Override
                    public String actionName() {
                        return "Card";
                    }
                }).show();

    }

    private void decryptPayments(FirebaseUser firebaseUser){
        if(firebaseUser != null){
            UserFetch.getUser(firebaseUser.getEmail())
                    .addOnSuccessListener(result -> {
                        Log.i(TAG, "Got Payment Successfully");
                        try {
                            if(result.contains("cidi") && result.contains("pid")){
                                String cidi = (String)result.get("cidi");
                                String pid = (String) result.get("pid");
                                if((cidi != null && pid != null) && (!cidi.isEmpty() && !pid.isEmpty())) {
                                    SelectPayment selectPayment = SelectPayment.getInstance();
                                    selectPayment.setGetCardRefNumber(PurchaseFragment.this);
                                    selectPayment.show(getParentFragmentManager(), "select_payment");
                                }else{
                                    Toast.makeText(getContext(),getResources().getString(R.string.no_payment_cards_add), Toast.LENGTH_LONG ).show();
                                }
                            }else{
                                Toast.makeText(getContext(),getResources().getString(R.string.no_payment_cards_add), Toast.LENGTH_LONG ).show();
                            }
                        }catch (Exception e){
                            Log.w(TAG, "Failed to decrypt phone number", e);
                        }
                    })
                    .addOnFailureListener(result -> {
                        Log.w(TAG, "Failed to retrieve payment", result);
                    });
        }
    }




    private void completeOrder(String type, String cardRef) {
        Map<String, Object> order = new HashMap<>();
        final long ts = System.currentTimeMillis();
        order.put("timestamp", "" + ts);
        order.put("quantity", currentQuantity);
        order.put("total", currentTotal);
        order.put("rental", false);
        final double copyTotal = currentTotal;

        ArrayList<String> deps = new ArrayList<>();


        ItemAdapter<PurchaseItem> itemAdapter = (ItemAdapter<PurchaseItem>) recViewPurchase.getAdapter();
        if (itemAdapter != null) {
            List<String> items = new ArrayList<>();
            for (int i = 0; i < itemAdapter.getItemCount(); i++) {
                PurchaseItem pi = itemAdapter.getItem(i);

                deps.add(pi.getDep());
                items.add(
                        String.format(Constants.PURCHASE_RECEIPT_ITEM,
                                pi.getTitle(),
                                pi.getImgLink(),
                                pi.getPrice(),
                                pi.getQuantity())
                );

                dbHelper.deleteItem(String.format(Constants.FirestoreCollections.LIVE_USER_CART,
                        auth.getUid()), itemAdapter.getItem(i).getDocID());
            }

            if (items.size() > 0) {
                order.put("items", items);
            }
        }

        Map<String, Long> depts = depCount(deps);

        dbHelper.addItem(String.format(Constants.FirestoreCollections.COMPLETED_USER_CART,
                auth.getUid()), order)
                .addOnSuccessListener(setResult -> {
                    String id = setResult.getId();
                    order.remove("items");
                    order.put("itemref", String.format(Constants.FirestoreCollections.COMPLETED_USER_CART
                                    + "/%s",
                            auth.getUid(),id));

                    dbHelper.addItem(String.format(Constants.FirestoreCollections.RECEIPTS_TEST,
                            auth.getCurrentUser().getUid()), order)
                            .addOnSuccessListener(result -> {

                                UserFetch.getTransactionsDoc(cardRef == null ? "cash" : cardRef, auth.getCurrentUser().getEmail(),
                                        Payment.addTransaction(Double.toString(copyTotal),type,
                                                cardRef,
                                                String.format(Constants.FirestoreCollections.COMPLETED_USER_CART
                                                                + "/%s",
                                                        auth.getUid(),id)));

                                UserFetch.getUser(firebaseUser.getEmail())
                                        .addOnSuccessListener(success -> {
                                            Log.i(TAG, "Got user successfully");
                                            Map<String, Long> cats = (Map<String, Long>) success.get("items_categories");


                                            UserFetch.update(FirebaseAuth.getInstance().getCurrentUser().getEmail(),
                                                    "items_categories", cats != null ? concat(cats, depts): depts);

                                        })
                                        .addOnFailureListener(failure -> {
                                            Log.i(TAG, "Failed to get user", failure);
                                        });

                                Log.d(TAG, "receipt created: " + id);
                            });

                    toaster.toastShort(getResources().getString(R.string.payment_success));
                    currentQuantity = 0;
                    currentTotal = 0;

                    getParentFragmentManager().beginTransaction()
                            .replace(R.id.nav_host_fragment, new RecentFragment())
                            .commit();
                })
                .addOnFailureListener(setFail -> {
                    toaster.toastShort(getResources().getString(R.string.completed_order_error));
                });


    }

    private Map<String, Long> concat(Map<String, Long> catsCurrent, Map<String, Long> catsNew){
        Map<String, Long> newMap = new HashMap<>();
        Set<String> keys = catsNew.keySet();
        for(String key : keys){
            if(key == null) continue;

            if(catsCurrent.containsKey(key)){
                long catCount = (catsCurrent.get(key) + catsNew.get(key));
                newMap.put(key, catCount);
                catsCurrent.remove(key);
            }else{
                newMap.put(key, catsNew.get(key));
            }
        }

        newMap.putAll(catsCurrent);

        return newMap;
    }


    private Map<String, Long> depCount(ArrayList<String> dep){
        Map<String, Long> counts = new HashMap<>();
        Set<String> deps = new HashSet<>(dep);
        for(String depts : deps){
            long counter = 0;
            for(int i = 0; i < dep.size(); i++){
                Log.i(TAG, "Set " + (depts == null) + " array " + (dep.get(i) == null));
                if(dep.get(i).equals(depts)){
                    counter++;
                }
                counts.put(depts, counter);
                counter = 0;
            }
        }
        return counts;
    }

    private void setTrackerSwipe() {
        ItemSwipe.ItemHelperListener leftSwipe = (viewHolder, direction, position) -> {
            Log.d(TAG, "leftSwipe position: " + position);

            ItemAdapter<PurchaseItem> itemAdapter = (ItemAdapter<PurchaseItem>)recViewPurchase.getAdapter();
            if(itemAdapter != null) {
                PurchaseItem p = itemAdapter.getItem(position);

                recViewPurchase
                        .getAdapter()
                        .notifyItemChanged(position);
                    dbHelper.deleteItem(String.format(Constants.FirestoreCollections.LIVE_USER_CART,
                            FirebaseAuth.getInstance().getUid()), p.getDocID())
                            .addOnSuccessListener(task ->{
                                toaster.toastShort(getResources().getString(R.string.item_removed_from_cart));
                            });

                itemAdapter.notifyItemChanged(position);
            } else {floatingTitlebar.setTitleText("");}
        };


        ItemTouchHelper.SimpleCallback touchHelper = new ItemSwipe(0, ItemTouchHelper.LEFT,leftSwipe);

        new ItemTouchHelper(touchHelper).attachToRecyclerView(recViewPurchase);
    }

    private void reloadItems(String filter) {

        dbHelper.getCollection(String.format(Constants.FirestoreCollections.LIVE_USER_CART,
                FirebaseAuth.getInstance().getUid()))
                .get()
                .addOnSuccessListener(snapshot -> {

                    if (snapshot.isEmpty()) {
                        Log.d(TAG, "onSuccess: no items");
                        toaster.toastShort(getResources().getString(R.string.no_items_in_cart));
                    } else {
                        loadPurchaseItems(initPurchaseItems(snapshot.getDocuments(), filter));
                    }
                }).addOnFailureListener(fail -> {
            toaster.toastShort(getResources().getString(R.string.error_purchase_cart_items));
            Log.d(TAG, fail.getMessage());
        });

    }


    @Override
    public void onTextChanged(String searchText) {
        reloadItems(searchText);
    }

    @Override
    public void getCardRef(String ref) {
        if(ref != null){
            completeOrder("card", ref);
        }
    }
}