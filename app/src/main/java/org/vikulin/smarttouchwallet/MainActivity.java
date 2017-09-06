package org.vikulin.smarttouchwallet;

/**
 * Created by vadym on 20.08.17.
 */

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.res.Configuration;
import android.graphics.Bitmap;
import android.graphics.drawable.BitmapDrawable;
import android.os.AsyncTask;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.preference.PreferenceManager;
import android.support.annotation.Nullable;
import android.support.design.widget.FloatingActionButton;
import android.support.v7.app.AlertDialog;
import android.support.v7.app.AppCompatActivity;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.Window;
import android.widget.ExpandableListAdapter;
import android.widget.ExpandableListView;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.onesignal.OneSignal;

import org.json.JSONException;
import org.json.JSONObject;
import org.vikulin.smarttouchwallet.adaptor.JSONKeyItemAdaptor;
import org.vikulin.smarttouchwallet.comparator.JSONKeyObject;
import org.vikulin.smarttouchwallet.icon.Blockies;
import org.vikulin.smarttouchwallet.smartchange.Currency;
import org.web3j.crypto.CipherException;
import org.web3j.crypto.ECKeyPair;
import org.web3j.crypto.Keys;
import org.web3j.crypto.Wallet;
import org.web3j.crypto.WalletFile;
import org.web3j.protocol.ObjectMapperFactory;
import org.web3j.utils.Numeric;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.math.BigInteger;
import java.security.InvalidAlgorithmParameterException;
import java.security.NoSuchAlgorithmException;
import java.security.NoSuchProviderException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import it.sephiroth.android.library.tooltip.Tooltip;

import static org.vikulin.smarttouchwallet.ConfigurationActivity.LANGUAGE;
import static org.vikulin.smarttouchwallet.ConfigurationActivity.language_codes;
import static org.vikulin.smarttouchwallet.ConfigurationActivity.languages;

/**
 * Created by vadym on 11.12.16.
 */

public class MainActivity extends AppCompatActivity {

    private static final int CHOOSE_WALLET_CURRENCY = 100;
    protected volatile AlertDialog keyPasswordDialog;
    protected AlertDialog progressDialog;
    private SharedPreferences preferences;
    private ExpandableListView lv;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        this.updateConfiguration();
        setContentView(R.layout.activity_main);
        this.preferences = PreferenceManager.getDefaultSharedPreferences(getBaseContext());
        lv = (ExpandableListView) findViewById(R.id.account_list);
        Set<String> savedKeys = preferences.getStringSet("keys", null);
        if(savedKeys==null){
            savedKeys = new HashSet<>();
        }
        List<JSONObject> jsonObjectKeys = new ArrayList<>(savedKeys.size());
        for(String key:savedKeys){
            try {
                jsonObjectKeys.add(new JSONObject(key));
            } catch (JSONException e) {
                showAlertDialog("",e.getMessage());
            }
        }
        Collections.sort(jsonObjectKeys, JSONKeyObject.JSONObjectNameComparator);
        List groupWalletList = new ArrayList();
        groupWalletList.add(jsonObjectKeys);
        final ExpandableListAdapter adapter = new JSONKeyItemAdaptor<List<List<JSONObject>>>(this, groupWalletList);
        TextView emptyText = (TextView)findViewById(android.R.id.empty);
        lv.setEmptyView(emptyText);
        lv.setAdapter(adapter);
        final ImageButton addWallet = (ImageButton) findViewById(R.id.add_wallet_button);
        if(adapter.getGroupCount()==0){
            showTooltip(addWallet, getString(R.string.create), Tooltip.Gravity.TOP, R.layout.tooltip_layout);
        }
    }

    public void writeFile(File keyFile, String content) throws IOException {
        // get the path to sdcard
        File sdcard = Environment.getExternalStorageDirectory();
        FileOutputStream os = new FileOutputStream(keyFile);
        os.write(content.getBytes());
        os.close();
    }

    protected AlertDialog showKeyPasswordDialog(){
        View view = LayoutInflater.from(this).inflate(R.layout.open_key_password_layout,null);
        AlertDialog.Builder ab = new AlertDialog.Builder(this);
        ab.setView(view);
        AlertDialog passwordDialog = ab.show();
        passwordDialog.setCancelable(false);
        passwordDialog.setCanceledOnTouchOutside(false);
        return passwordDialog;
    }

    void showOpenKeyProgress(){
        Window rootView = keyPasswordDialog.getWindow();
        ProgressBar loginProgress = (ProgressBar) rootView.findViewById(R.id.login_progress);
        loginProgress.setPressed(true);
        loginProgress.setVisibility(View.VISIBLE);
        rootView.findViewById(R.id.passwordLayout).setVisibility(View.GONE);
    }

    void hideOpenKeyProgress(){
        Window rootView = keyPasswordDialog.getWindow();
        ProgressBar loginProgress = (ProgressBar) rootView.findViewById(R.id.login_progress);
        loginProgress.setPressed(false);
        loginProgress.setVisibility(View.GONE);
        rootView.findViewById(R.id.passwordLayout).setVisibility(View.VISIBLE);
    }

    protected AlertDialog showProgress(){
        View rootView = LayoutInflater.from(this).inflate(R.layout.progress_layout,null);
        AlertDialog.Builder ab = new AlertDialog.Builder(this);
        ab.setView(rootView);
        AlertDialog progressDialog = ab.show();
        progressDialog.setCancelable(false);
        progressDialog.setCanceledOnTouchOutside(false);
        ProgressBar progress = (ProgressBar) rootView.findViewById(R.id.progress);
        progress.setPressed(true);
        progress.setVisibility(View.VISIBLE);

        return progressDialog;
    }

    protected void hideProgress(){
        if(progressDialog!=null && progressDialog.isShowing()) {
            progressDialog.dismiss();
        }
    }

    public void onClickCancelPassword(View view) {
        finish();
    }

    private final static AtomicInteger c = new AtomicInteger(0);

    public static int getId() {
        return c.incrementAndGet();
    }

    protected void onPause() {
        super.onPause();
    }
    protected void onDestroy() {
        hideProgress();
        super.onDestroy();
    }

    private static void setUpAlarm(final Context context, final Intent intent, final int timeInterval) {
        final AlarmManager am = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
        final PendingIntent pi = PendingIntent.getBroadcast(context, timeInterval, intent, 0);
        am.cancel(pi);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            final AlarmManager.AlarmClockInfo alarmClockInfo = new AlarmManager.AlarmClockInfo(System.currentTimeMillis() + timeInterval, pi);
            am.setAlarmClock(alarmClockInfo, pi);
        }
        else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT)
            am.setExact(AlarmManager.RTC_WAKEUP, System.currentTimeMillis() + timeInterval, pi);
        else
            am.set(AlarmManager.RTC_WAKEUP, System.currentTimeMillis() + timeInterval, pi);
    }

    public void onClickExchange(View view) {
    }

    public void addWallet(View view) {
        Intent intent = new Intent(this, ChooseWalletCurrency.class);
        startActivityForResult(intent, CHOOSE_WALLET_CURRENCY);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent intent) {
        super.onActivityResult(requestCode, resultCode, intent);
        if (requestCode == CHOOSE_WALLET_CURRENCY && resultCode == RESULT_OK) {
            Bundle extras = intent.getExtras();
            String currency = extras.getString(ChooseWalletCurrency.CURRENCY);
            if (currency == null) {
                showAlertDialog("", "Currency is null!");
                return;
            }
            showMessage(currency);
            final Currency c = Currency.valueOf(currency);
            progressDialog = showProgress();
            AsyncTask newWallet = new AsyncTask<Object, Void, String>() {
                @Override
                protected String doInBackground(Object... voids) {
                    switch (c)
                    {
                        case eth:
                            try {
                                File destination = getExternalCacheDir();
                                String fileName = generateNewWalletFile("basebase", destination, true);
                                String keyFileContent = null;
                                try {
                                    keyFileContent = readFile(new File(destination, fileName));
                                } catch (IOException e) {
                                    e.printStackTrace();
                                    showAlertDialogOnUiThread("", e.getMessage());
                                }
                                return keyFileContent;
                            } catch (CipherException e) {
                                e.printStackTrace();
                            } catch (IOException e) {
                                e.printStackTrace();
                            } catch (InvalidAlgorithmParameterException e) {
                                e.printStackTrace();
                            } catch (NoSuchAlgorithmException e) {
                                e.printStackTrace();
                            } catch (NoSuchProviderException e) {
                                e.printStackTrace();
                            }
                    }

                    return null;
                }

                @Override
                protected void onPostExecute(String keyFileContent) {
                    try {
                        importKey(keyFileContent);
                    } catch (JSONException e) {
                        e.printStackTrace();
                        showAlertDialog("", e.getMessage());
                    } catch (IOException e) {
                        e.printStackTrace();
                        showAlertDialog("", e.getMessage());
                    }
                    notifyKeyListAdapter();
                    hideProgress();
                    try {
                        updateOneSignalTags();
                    } catch (JSONException e) {
                        e.printStackTrace();
                        showAlertDialog("", e.getMessage());
                    }
                }
            };
            newWallet.execute();
        }
    }

    public void notifyKeyListAdapter(){
        Set<String> savedKeys = preferences.getStringSet("keys", null);
        List<JSONObject> jsonObjectKeys = new ArrayList<>(savedKeys.size());
        for(String key:savedKeys){
            try {
                jsonObjectKeys.add(new JSONObject(key));
            } catch (JSONException e) {
                showAlertDialog("",e.getMessage());
            }
        }
        Collections.sort(jsonObjectKeys, JSONKeyObject.JSONObjectNameComparator);
        List groupWalletList = new ArrayList();
        groupWalletList.add(jsonObjectKeys);
        JSONKeyItemAdaptor adapter = new JSONKeyItemAdaptor<>(this, groupWalletList);
        lv.setAdapter(adapter);
        adapter.notifyDataSetChanged();
    }

    public static String generateNewWalletFile(
            String password, File destinationDirectory, boolean useFullScrypt)
            throws CipherException, IOException, InvalidAlgorithmParameterException,
            NoSuchAlgorithmException, NoSuchProviderException {

        ECKeyPair ecKeyPair = Keys.createEcKeyPair();
        return generateWalletFile(password, ecKeyPair, destinationDirectory, useFullScrypt);
    }

    public static String generateWalletFile(
            String password, ECKeyPair ecKeyPair, File destinationDirectory, boolean useFullScrypt)
            throws CipherException, IOException {

        WalletFile walletFile;
        if (useFullScrypt) {
            walletFile = Wallet.createStandard(password, ecKeyPair);
        } else {
            walletFile = Wallet.createLight(password, ecKeyPair);
        }

        String fileName = getWalletFileName(walletFile);
        File destination = new File(destinationDirectory, fileName);

        ObjectMapper objectMapper = ObjectMapperFactory.getObjectMapper();
        objectMapper.writeValue(destination, walletFile);

        return fileName;
    }

    private static String getWalletFileName(WalletFile walletFile) {
        return walletFile.getAddress();
    }

    public String read(BufferedReader reader) throws IOException {
        StringBuilder builder = new StringBuilder();
        String aux = "";
        while ((aux = reader.readLine()) != null) {
            builder.append(aux);
        }
        String text = builder.toString();
        reader.close();
        return text;
    }

    public void setIcon(String address, ImageView icon, int size){
        icon.setImageDrawable(new BitmapDrawable(getResources(), Blockies.createIcon(8, Numeric.prependHexPrefix(address), size)));
    }

    public void showTooltip(View anchor, String text, int layout){
        showTooltip(anchor, text, Tooltip.Gravity.BOTTOM, layout);
    }

    public void showTooltip(View anchor, String text, Tooltip.Gravity gravity, int layout){
        Tooltip.make(this,
                new Tooltip.Builder(101)
                        .anchor(anchor, gravity)
                        .closePolicy(new Tooltip.ClosePolicy()
                                .insidePolicy(true, false)
                                .outsidePolicy(true, false), 15000)
                        .activateDelay(800)
                        .showDelay(1000)
                        .text(text)
                        .maxWidth(500)
                        .withArrow(true)
                        .withOverlay(true)
                        .withStyleId(R.style.ToolTipLayoutCustomStyle)
                        .withCustomView(layout, false)
                        //.typeface(mYourCustomFont)
                        //.floatingAnimation(Tooltip.AnimationBuilder.DEFAULT)
                        .build()
        ).show();
    }

    protected void updateConfiguration() {
        Configuration config = getBaseContext().getResources().getConfiguration();
        SharedPreferences preferences = PreferenceManager.getDefaultSharedPreferences(this.getBaseContext());
        final String language = preferences.getString(LANGUAGE, null);
        if (language != null) {
            updateLanguage(language);
        }
        getBaseContext().getResources().updateConfiguration(config, getBaseContext().getResources().getDisplayMetrics());
    }

    @Override
    protected void onRestoreInstanceState(Bundle savedInstanceState) {
        super.onRestoreInstanceState(savedInstanceState);
        this.updateConfiguration();
    }

    protected void updateLanguage(String language){
        int i = Arrays.asList(languages).indexOf(language);
        Configuration config = getBaseContext().getResources().getConfiguration();
        Locale locale = new Locale(language_codes[i]);
        Locale.setDefault(locale);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR1) {
            config.setLocale(locale);
        } else {
            config.locale = locale;
        }
    }

    protected void updateOneSignalTags() throws JSONException {
        SharedPreferences preferences = PreferenceManager.getDefaultSharedPreferences(this.getBaseContext());
        Set<String> savedKeys = preferences.getStringSet("keys", null);
        if(savedKeys==null){
            return;
        }
        JSONObject tags = new JSONObject();
        List<String> addresses = new ArrayList();
        for(String key:savedKeys){
            JSONObject wallet = new JSONObject(key);
            String address = wallet.getString("address");
            addresses.add(address);
        }
        if(addresses.size()>0){
            String idList = addresses.toString();
            String csv = idList.substring(1, idList.length() - 1).replace(", ", ",");
            tags.put("addresses",csv);
            OneSignal.sendTags(tags);
        }
    }

    public void deleteAndUpdateOneSignalTags() throws JSONException {
        OneSignal.deleteTag("addresses");
        updateOneSignalTags();
    }

    public void updateKeyName(String address, String keyName) {
        SharedPreferences preferences = PreferenceManager.getDefaultSharedPreferences(this);
        try {
            Set<String> keys = new HashSet();
            Set<String> removedKeys = new HashSet<>();
            Set<String> savedKeys = new HashSet<>(preferences.getStringSet("keys", new HashSet<String>()));
            for(String key:savedKeys){
                JSONObject savedKey = new JSONObject(key);
                if(savedKey.getString("address")!=null){
                    if(savedKey.getString("address").equalsIgnoreCase(Numeric.cleanHexPrefix(address))){
                        savedKey.put("key_name",keyName);
                        removedKeys.add(key);
                        keys.add(savedKey.toString());
                        break;
                    }
                }
            }
            savedKeys.removeAll(removedKeys);
            savedKeys.addAll(keys);
            preferences.edit().putStringSet("keys",savedKeys).commit();
        } catch (JSONException e) {
            showAlertDialog("", e.getMessage());
        }
    }

    public void importKey(String keyFileContent) throws JSONException, IOException {
        if(keyFileContent==null){
            return;
        }
        JSONObject keyFileObject = new JSONObject(keyFileContent);
        keyFileObject.put("key_name","");
        BigInteger nonce = BigInteger.ZERO;
        keyFileObject.put("nonce",nonce);
        Set<String> keys = new HashSet();
        Set<String> removedKeys = new HashSet<>();
        keys.add(keyFileObject.toString());
        Set<String> savedKeys = new HashSet<>(preferences.getStringSet("keys", new HashSet<String>()));
        if(savedKeys==null){
            preferences.edit().putStringSet("keys",keys).commit();
            return;
        } else {
            for(String key:savedKeys){
                JSONObject savedKey = new JSONObject(key);
                if(savedKey.getString("address")!=null){
                    if(savedKey.getString("address").equalsIgnoreCase(keyFileObject.getString("address"))){
                        removedKeys.add(key);
                        break;
                    }
                }
            }
            savedKeys.removeAll(removedKeys);
            savedKeys.addAll(keys);
            preferences.edit().putStringSet("keys",savedKeys).commit();
            return;
        }
    }

    protected void showMessage(String message){
        if(!this.isFinishing())
            Toast.makeText(this, message, Toast.LENGTH_LONG).show();
    }

    protected void showMessageOnUiThread(final String message){
        this.runOnUiThread(new Runnable() {
            public void run() {
                if(!MainActivity.this.isFinishing())
                    Toast.makeText(MainActivity.this, message, Toast.LENGTH_LONG).show();
            }
        });
    }

    protected void showInfoDialogOnUiThread(final String title, final String message){
        this.runOnUiThread(new Runnable() {
            public void run() {
                showInfoDialog(title, message);
            }
        });
    }

    public void showAlertDialogOnUiThread(final String title, final String message){
        this.runOnUiThread(new Runnable() {
            public void run() {
                showAlertDialog(title, message);
            }
        });
    }

    protected void showAlertDialogOnUiThread(final String title, final String message, final DialogInterface.OnClickListener listener){
        this.runOnUiThread(new Runnable() {
            public void run() {
                showAlertDialog(title, message, listener);
            }
        });
    }

    protected void showInfoDialogOnUiThread(final String title, final String message, final DialogInterface.OnClickListener listener){
        this.runOnUiThread(new Runnable() {
            public void run() {
                showInfoDialog(title, message, listener);
            }
        });
    }

    public void showInfoDialog(String title, String message){
        if(!this.isFinishing())
            new AlertDialog.Builder(this)
                    .setTitle(title)
                    .setMessage(message)
                    .setIcon(android.R.drawable.ic_dialog_info)
                    .show();
    }

    public void showInfoDialog(String title, String message, Bitmap icon, DialogInterface.OnClickListener listener){
        ImageView image = new ImageView(this);
        image.setImageDrawable(new BitmapDrawable(getResources(), icon));
        if(!this.isFinishing())
            new AlertDialog.Builder(this)
                    .setTitle(title)
                    .setView(image)
                    .setMessage(message)
                    .setPositiveButton(android.R.string.ok, listener)
                    .setIcon(android.R.drawable.ic_dialog_info)
                    .show();
    }

    public void showAlertDialog(String title, String message){
        if(!this.isFinishing())
            new AlertDialog.Builder(this)
                    .setTitle(title)
                    .setMessage(message)
                    .setIcon(android.R.drawable.ic_dialog_alert)
                    .show();
    }

    public void showInfoDialog(String title, String message, DialogInterface.OnClickListener listener){
        if(!this.isFinishing())
            new AlertDialog.Builder(this)
                    .setTitle(title)
                    .setMessage(message)
                    .setPositiveButton(android.R.string.ok, listener)
                    .setIcon(android.R.drawable.ic_dialog_info)
                    .show();
    }

    public void showAlertDialog(String title, String message, DialogInterface.OnClickListener listener){
        if(!this.isFinishing())
            new AlertDialog.Builder(this)
                    .setTitle(title)
                    .setMessage(message)
                    .setPositiveButton(android.R.string.ok, listener)
                    .setIcon(android.R.drawable.ic_dialog_alert)
                    .show();
    }

    public void showConfirmDialog(String title, String message, DialogInterface.OnClickListener pressedYes, DialogInterface.OnClickListener pressedNo){
        if(!this.isFinishing())
            new AlertDialog.Builder(this)
                    .setTitle(title)
                    .setMessage(message)
                    .setPositiveButton(android.R.string.yes, pressedYes)
                    .setNegativeButton(android.R.string.no, pressedNo)
                    .setIcon(android.R.drawable.ic_dialog_info)
                    .show();
    }

    public String readFile(File keyFile) throws IOException {
        int length = (int) keyFile.length();
        byte[] bytes = new byte[length];
        InputStream inputStream = null;
        String content = null;
        try {
            inputStream = new FileInputStream(keyFile);
            inputStream.read(bytes);
            content = new String(bytes);
        } catch (FileNotFoundException e) {
            Log.e("login activity", "File not found: " + e.toString());
        } catch (IOException e) {
            Log.e("login activity", "Can not read file: " + e.toString());
        } finally {
            inputStream.close();
        }
        return content;
    }
}

