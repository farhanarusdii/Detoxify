package com.example.detoxify;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseUser;
import com.google.firebase.database.DatabaseReference;
import com.google.firebase.database.FirebaseDatabase;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

public class AuthManager {

    private static final String TAG = "AuthManager";
    private static AuthManager instance;
    private FirebaseAuth mAuth;
    private DatabaseReference mDatabase;
    private SharedPreferences sharedPreferences;
    private Context context;

    private AuthManager(Context context) {
        this.context = context;
        mAuth = FirebaseAuth.getInstance();
        mDatabase = FirebaseDatabase.getInstance().getReference();
        sharedPreferences = context.getSharedPreferences("Detoxify", Context.MODE_PRIVATE);
    }

    public static synchronized AuthManager getInstance(Context context) {
        if (instance == null) {
            instance = new AuthManager(context);
        }
        return instance;
    }

    // ==================== AUTHENTICATION METHODS ====================

    // Sign up with email and password
    public void signUp(String email, String password, AuthCallback callback) {
        mAuth.createUserWithEmailAndPassword(email, password)
                .addOnCompleteListener(task -> {
                    if (task.isSuccessful()) {
                        FirebaseUser user = mAuth.getCurrentUser();
                        String userId = user.getUid();

                        // Save user info to Realtime Database
                        saveUserToDatabase(userId, email);

                        // Save login state
                        sharedPreferences.edit()
                                .putBoolean("isLoggedIn", true)
                                .putString("userId", userId)
                                .putString("userEmail", email)
                                .putString("userRole", "parent")
                                .apply();

                        callback.onSuccess(userId, "Parent account created!");
                    } else {
                        callback.onError(task.getException().getMessage());
                    }
                });
    }

    // Login with email and password
    public void login(String email, String password, AuthCallback callback) {
        mAuth.signInWithEmailAndPassword(email, password)
                .addOnCompleteListener(task -> {
                    if (task.isSuccessful()) {
                        FirebaseUser user = mAuth.getCurrentUser();
                        String userId = user.getUid();

                        // Save login state
                        sharedPreferences.edit()
                                .putBoolean("isLoggedIn", true)
                                .putString("userId", userId)
                                .putString("userEmail", email)
                                .putString("userRole", "parent")
                                .apply();

                        callback.onSuccess(userId, "Login successful!");
                    } else {
                        callback.onError(task.getException().getMessage());
                    }
                });
    }

    // Logout (parent account — clears Firebase session and all local state)
    public void logout() {
        ParentWatchController.stopWatching(context);
        mAuth.signOut();
        sharedPreferences.edit()
                .clear()
                .apply();
    }

    /** Child device linked via code — no Firebase Auth required. */
    public boolean isChildConnected() {
        String code = sharedPreferences.getString("connectedChildCode", "");
        if (code.isEmpty()) {
            return false;
        }
        if (sharedPreferences.getBoolean("isChildDevice", false)) {
            return true;
        }
        // Sessions created before {@code isChildDevice} was stored
        return "child".equals(sharedPreferences.getString("userRole", ""));
    }

    /** Clears only the child link; keeps parent password / other prefs on a shared device. */
    public void logoutChild() {
        sharedPreferences.edit()
                .remove("isChildDevice")
                .remove("connectedChildCode")
                .remove("childName")
                .remove("parentId")
                .remove("parentEmail")
                .remove("userRole")
                .remove("userMode")
                .remove("last_mood_checkin_day")
                .remove(BlockMonitorService.PREFS_LAST_APPLIED_TIME_REQUEST_ID)
                .apply();
    }

    // Check if user is logged in
    public boolean isLoggedIn() {
        return sharedPreferences.getBoolean("isLoggedIn", false) && mAuth.getCurrentUser() != null;
    }

    // Get current user
    public FirebaseUser getCurrentUser() {
        return mAuth.getCurrentUser();
    }

    public String getCurrentUserId() {
        return sharedPreferences.getString("userId", "");
    }

    // ==================== PARENT-CHILD CONNECTION ====================

    // Save user to database
    private void saveUserToDatabase(String userId, String email) {
        Map<String, Object> userData = new HashMap<>();
        userData.put("email", email);
        userData.put("createdAt", System.currentTimeMillis());
        userData.put("userType", "parent");

        mDatabase.child("users").child(userId).setValue(userData);
    }

    /** Unique 8-character code for each child profile (share with child's device). */
    public String generateChildCode() {
        if (getCurrentUserId().isEmpty()) {
            return null;
        }
        return UUID.randomUUID().toString().replace("-", "").substring(0, 8).toUpperCase(Locale.US);
    }

    // In AuthManager.java, update the saveChildCode method:
    public void saveChildCode(String childCode, String childName, ChildCodeCallback callback) {
        String parentId = getCurrentUserId();
        if (parentId.isEmpty()) {
            callback.onError("Not logged in");
            return;
        }

        Map<String, Object> childData = new HashMap<>();
        childData.put("childName", childName);
        childData.put("parentId", parentId);
        childData.put("childCode", childCode);
        childData.put("createdAt", System.currentTimeMillis());
        childData.put("connected", false);
        childData.put("dailyLimit", 120);
        childData.put("baselineDailyLimit", 120);
        Map<String, Object> deviceLock = new HashMap<>();
        deviceLock.put("active", false);
        childData.put("deviceLock", deviceLock);

        // Save to children node
        mDatabase.child("children").child(childCode).setValue(childData)
                .addOnSuccessListener(aVoid -> {
                    // Also save to parent's children list
                    Map<String, Object> parentChild = new HashMap<>();
                    parentChild.put(childCode, childName);
                    mDatabase.child("parents").child(parentId).child("children").updateChildren(parentChild);

                    // Save to SharedPreferences
                    sharedPreferences.edit()
                            .putString("childCode", childCode)
                            .putString("childName", childName)
                            .apply();

                    callback.onSuccess(childCode);
                })
                .addOnFailureListener(e -> callback.onError(e.getMessage()));
    }

    // Connect child device (called from child's app)
    public void connectChildDevice(String childCode, String deviceName, ConnectCallback callback) {
        mDatabase.child("children").child(childCode).addListenerForSingleValueEvent(
                new com.google.firebase.database.ValueEventListener() {
                    @Override
                    public void onDataChange(com.google.firebase.database.DataSnapshot snapshot) {
                        if (snapshot.exists()) {
                            String parentId = snapshot.child("parentId").getValue(String.class);
                            String childName = snapshot.child("childName").getValue(String.class);

                            // Update child device info
                            Map<String, Object> deviceInfo = new HashMap<>();
                            deviceInfo.put("deviceName", deviceName);
                            deviceInfo.put("connected", true);
                            deviceInfo.put("lastSeen", System.currentTimeMillis());
                            deviceInfo.put("parentId", parentId);

                            mDatabase.child("children").child(childCode).updateChildren(deviceInfo);

                            SharedPreferences.Editor editor = sharedPreferences.edit()
                                    .putBoolean("isChildDevice", true)
                                    .putString("userRole", "child")
                                    .putString("connectedChildCode", childCode)
                                    .putString("childName", childName)
                                    .putString("parentId", parentId != null ? parentId : "")
                                    .remove(BlockMonitorService.PREFS_LAST_APPLIED_TIME_REQUEST_ID);

                            if (parentId == null || parentId.isEmpty()) {
                                editor.apply();
                                callback.onSuccess(childName, parentId);
                                return;
                            }

                            mDatabase.child("users").child(parentId).child("email")
                                    .addListenerForSingleValueEvent(
                                            new com.google.firebase.database.ValueEventListener() {
                                                @Override
                                                public void onDataChange(
                                                        com.google.firebase.database.DataSnapshot emailSnap) {
                                                    String email = emailSnap.getValue(String.class);
                                                    if (email != null && !email.isEmpty()) {
                                                        editor.putString("parentEmail", email);
                                                    }
                                                    editor.apply();
                                                    callback.onSuccess(childName, parentId);
                                                }

                                                @Override
                                                public void onCancelled(
                                                        com.google.firebase.database.DatabaseError error) {
                                                    Log.w(TAG, "parent email: " + error.getMessage());
                                                    editor.apply();
                                                    callback.onSuccess(childName, parentId);
                                                }
                                            });
                        } else {
                            callback.onError("Invalid child code");
                        }
                    }

                    @Override
                    public void onCancelled(com.google.firebase.database.DatabaseError error) {
                        callback.onError(error.getMessage());
                    }
                });
    }

    /** Removes a child profile from Firebase and the parent's children list. */
    public void deleteChild(String childCode, ChildCodeCallback callback) {
        String parentId = getCurrentUserId();
        if (parentId.isEmpty()) {
            callback.onError("Not logged in");
            return;
        }
        if (childCode == null || childCode.isEmpty()) {
            callback.onError("Invalid child code");
            return;
        }

        mDatabase.child("children").child(childCode).removeValue()
                .addOnSuccessListener(aVoid -> mDatabase.child("parents").child(parentId)
                        .child("children").child(childCode).removeValue()
                        .addOnSuccessListener(ok -> {
                            String saved = sharedPreferences.getString("childCode", "");
                            if (childCode.equals(saved)) {
                                sharedPreferences.edit()
                                        .remove("childCode")
                                        .remove("childName")
                                        .apply();
                            }
                            callback.onSuccess(childCode);
                        })
                        .addOnFailureListener(e -> callback.onSuccess(childCode)))
                .addOnFailureListener(e -> callback.onError(
                        e.getMessage() != null ? e.getMessage() : "Delete failed"));
    }

    // Get all children for parent (for Parent Dashboard)
    public void getParentChildren(ChildrenCallback callback) {
        String parentId = getCurrentUserId();
        if (parentId.isEmpty()) {
            callback.onError("Not logged in");
            return;
        }

        mDatabase.child("parents").child(parentId).child("children")
                .addListenerForSingleValueEvent(new com.google.firebase.database.ValueEventListener() {
                    @Override
                    public void onDataChange(com.google.firebase.database.DataSnapshot snapshot) {
                        Map<String, String> children = new HashMap<>();
                        for (com.google.firebase.database.DataSnapshot child : snapshot.getChildren()) {
                            String childCode = child.getKey();
                            String childName = child.getValue(String.class);
                            children.put(childCode, childName);
                        }
                        callback.onSuccess(children);
                    }

                    @Override
                    public void onCancelled(com.google.firebase.database.DatabaseError error) {
                        callback.onError(error.getMessage());
                    }
                });

    }

    // ==================== CALLBACK INTERFACES ====================

    public interface AuthCallback {
        void onSuccess(String userId, String message);
        void onError(String error);
    }

    public interface ChildCodeCallback {
        void onSuccess(String childCode);
        void onError(String error);
    }

    public interface ConnectCallback {
        void onSuccess(String childName, String parentId);
        void onError(String error);
    }

    public interface ChildrenCallback {
        void onSuccess(Map<String, String> children);
        void onError(String error);
    }

}