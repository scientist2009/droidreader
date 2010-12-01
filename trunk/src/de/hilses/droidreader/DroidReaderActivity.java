/*

Copyright (C) 2010 Hans-Werner Hilse <hilse@web.de>

This program is free software: you can redistribute it and/or modify
it under the terms of the GNU General Public License as published by
the Free Software Foundation, either version 3 of the License, or
(at your option) any later version.

This program is distributed in the hope that it will be useful,
but WITHOUT ANY WARRANTY; without even the implied warranty of
MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
GNU General Public License for more details.

You should have received a copy of the GNU General Public License
along with this program.  If not, see <http://www.gnu.org/licenses/>.

This program is free software: you can redistribute it and/or modify
it under the terms of the GNU General Public License as published by
the Free Software Foundation, either version 3 of the License, or
(at your option) any later version.

*/

package de.hilses.droidreader;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.URLDecoder;

import org.openintents.intents.FileManagerIntents;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.Dialog;
import android.content.ActivityNotFoundException;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.res.Configuration;
import android.net.Uri;
import android.os.Bundle;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteException;
import android.preference.PreferenceManager;
import android.webkit.WebView;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.Toast;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.Gravity;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;

public class DroidReaderActivity extends Activity {
    private static final boolean LOG = false;

    private static final int REQUEST_CODE_PICK_FILE = 1;
    private static final int REQUEST_CODE_OPTION_DIALOG = 2;

    private static final int DIALOG_GET_PASSWORD = 1;
    private static final int DIALOG_ABOUT = 2;
    private static final int DIALOG_GOTO_PAGE = 3;
    private static final int DIALOG_WELCOME = 4;

    private static final String PREFERENCE_EULA_ACCEPTED = "eula.accepted";
    private static final String PREFERENCES_EULA = "eula";

    protected DroidReaderView mReaderView = null;
    protected DroidReaderDocument mDocument = null;

    private String mFilename;
    private String mTemporaryFilename;
    private String mPassword;

    private int mOffsetX;
    private int mOffsetY;
    private int mPageNo;

    private boolean mDocumentIsOpen = false;
    private boolean mLoadedDocument = false;
    private boolean mWelcomeShown = false;

    /** Called when the activity is first created. */
    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // first, show the welcome if it hasn't been shown already:
        final SharedPreferences preferences = getSharedPreferences(PREFERENCES_EULA,
                        Activity.MODE_PRIVATE);
        if (!preferences.getBoolean(PREFERENCE_EULA_ACCEPTED, false)) {
            mWelcomeShown = true;
            preferences.edit().putBoolean(PREFERENCE_EULA_ACCEPTED, true).commit();
            showDialog(DIALOG_WELCOME);
        }

        if(mDocument == null)
            mDocument = new DroidReaderDocument();

        // Initialize the PdfRender engine
        PdfRender.setFontProvider(new DroidReaderFontProvider(this));

        // then build our layout. it's so simple that we don't use
        // XML for now.
        FrameLayout fl = new FrameLayout(this);

        mReaderView = new DroidReaderView(this, null, mDocument);

        // add the viewing area and the navigation
        fl.addView(mReaderView);
        setContentView(fl);

        readPreferences();

        // The priority for loading files is:
        // 1) Check the bundle for a saved instance. If there is one, then
        // reload it. This has to be before the check for an intent because
        // the intent that was used when the app was first opened is
        // supplied again after an instance cycle such as happens when
        // you rotate the screen. This means that if another PDF was
        // opened after the app was started, and then the screen is rotated,
        // the app will go back to the original document if the intent is
        // given first priority.
        // 2) Check for an intent that indicates the app was started by
        // selecting a PDF in a file manager etc.
        // 3) Check what document was open last time the app closed down, and
        // re-open it.
        if (savedInstanceState != null) {
            mFilename = savedInstanceState.getString("filename");
            if((new File(mFilename)).exists()) {
                mPassword = savedInstanceState.getString("password");
                mDocument.mZoom = savedInstanceState.getFloat("zoom");
                mDocument.mRotation = savedInstanceState.getInt("rotation");
                mPageNo = savedInstanceState.getInt("page");
                mOffsetX = savedInstanceState.getInt("offsetX");
                mOffsetY = savedInstanceState.getInt("offsetY");
                mDocument.mMarginOffsetX = savedInstanceState.getInt("marginOffsetX");
                mDocument.mMarginOffsetY = savedInstanceState.getInt("marginOffsetY");
                openDocument();
                mLoadedDocument = true;
            }
            savedInstanceState.clear();
        }

        if (!mLoadedDocument) {
            // check if we were called in order to open a PDF:
            Intent intent = getIntent();
            if(intent.getData() != null) {
                // yep:
                mTemporaryFilename = intent.getData().toString();
                if(mTemporaryFilename.startsWith("file://")) {
                    mTemporaryFilename = mTemporaryFilename.substring(7);
                } else if(mTemporaryFilename.startsWith("/")) {
                    // raw filename
                } else if(mTemporaryFilename.startsWith("content://com.metago.astro.filesystem/")) {
                    // special case: ASTRO file manager
                    mTemporaryFilename = mTemporaryFilename.substring(37);
                } else {
                    Toast.makeText(this, R.string.error_only_file_uris,
                            Toast.LENGTH_SHORT).show();
                    mTemporaryFilename = null;
                }
                if(mTemporaryFilename!=null) {
                    // try to open with no password
                    mPassword = "";
                    openDocumentWithDecodeAndLookup();
                    mLoadedDocument = true;
                }
            }
        }

        if (!mLoadedDocument) {
            // No filename supplied and no saved instance state. Re-open the last document
            // that was viewed, if there was one.
            tryLoadLastFile();
        }

        if (!mLoadedDocument) {
            // No document to show at all. Instead, show the welcome dialog.
            // Don't do it if this is the first time running the program (because it's
            // already been shown)
            if (!mWelcomeShown)
                showDialog(DIALOG_WELCOME);
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        // Store the name of the document being viewed, so it can be re-called
        // next time if the app is started without a filename.
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(this);
        prefs.edit().putString("last_open_file",mFilename).commit();

        // Also store the view details for this document in the database so the
        // view can be restored.
        readOrWriteDB(true);
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        if((mDocument != null) && mDocument.isPageLoaded()) {
            outState.putFloat("zoom", mDocument.mZoom);
            outState.putInt("rotation", mDocument.mRotation);
            outState.putInt("page", mDocument.mPage.no);
            outState.putInt("offsetX", mDocument.mOffsetX);
            outState.putInt("offsetY", mDocument.mOffsetY);
            outState.putInt("marginOffsetX", mDocument.mMarginOffsetX);
            outState.putInt("marginOffsetY", mDocument.mMarginOffsetY);
            outState.putString("password", mPassword);
            outState.putString("filename", mFilename);
            mDocument.closeDocument();
        }
    }

    @Override
    protected void onDestroy() {
        if(mDocument != null)
            mDocument.closeDocument();
        super.onDestroy();
    }

    // There's actually no need to override onConfigurationChanged if we're
    // not going to do anything about it.
    //@Override
    //public void onConfigurationChanged (Configuration newConfig) {
    //    super.onConfigurationChanged(newConfig);
    //}

    private void readPreferences() {
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(this);
        DisplayMetrics metrics = new DisplayMetrics();
        getWindowManager().getDefaultDisplay().getMetrics(metrics);

        if(prefs.getString("zoom_type", "0").equals("0")) {
            float zoom = Float.parseFloat(prefs.getString("zoom_percent", "50"));
            if((1 <= zoom) && (1000 >= zoom)) {
                mDocument.setZoom(zoom / 100, false);
            }
        } else {
            mDocument.setZoom(Float.parseFloat(prefs.getString("zoom_type", "0")), false);
        }

        if(prefs.getBoolean("dpi_auto", true)) {
            // read the display's DPI
            mDocument.setDpi((int) metrics.xdpi, (int) metrics.ydpi);
        } else {
            int dpi = Integer.parseInt(prefs.getString("dpi_manual", "160"));
            if((dpi < 1) || (dpi > 4096))
                dpi = 160; // sanity check fallback
            mDocument.setDpi(dpi, dpi);
        }

        if(prefs.getBoolean("tilesize_by_factor", true)) {
            // set the tile size for rendering by factor
            Float factor = Float.parseFloat(prefs.getString("tilesize_factor", "1.5"));
            mDocument.setTileMax((int) (metrics.widthPixels * factor), (int) (metrics.heightPixels * factor));
        } else {
            int tilesize_x = Integer.parseInt(prefs.getString("tilesize_x", "640"));
            int tilesize_y = Integer.parseInt(prefs.getString("tilesize_x", "480"));
            if(metrics.widthPixels < metrics.heightPixels) {
                mDocument.setTileMax(tilesize_x, tilesize_y);
            } else {
                mDocument.setTileMax(tilesize_y, tilesize_x);
            }
        }

        boolean invert = prefs.getBoolean("invert_display", false);
        mDocument.setDisplayInvert(invert);
        mReaderView.setDisplayInvert(invert);

        if (prefs.getBoolean("full_screen",false)) {
            this.getWindow().addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN);
        } else {
            this.getWindow().clearFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN);
        }

        mDocument.mHorizontalScrollLock = prefs.getBoolean("horizontal_scroll_lock",false);
    }

    /** Creates the menu items */
    public boolean onCreateOptionsMenu(Menu menu) {
        MenuInflater inflater = getMenuInflater();
        inflater.inflate(R.menu.options_menu, menu);
        return true;
    }

    /** Handles item selections */
    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        switch (item.getItemId()) {

        case R.id.set_margin_offset:
            // Set the current offset to be applied to every page.
            mDocument.mMarginOffsetX = mDocument.mOffsetX;
            mDocument.mMarginOffsetY = mDocument.mOffsetY;
            return true;

        // Zooming:

        case R.id.zoom_in:
            mDocument.setZoom(1.5F, true);
            return true;
        case R.id.zoom_out:
            mDocument.setZoom(0.6666F, true);
            return true;
        case R.id.zoom_fit:
            mDocument.setZoom(DroidReaderDocument.ZOOM_FIT, false);
            return true;
        case R.id.zoom_fitw:
            mDocument.setZoom(DroidReaderDocument.ZOOM_FIT_WIDTH, false);
            return true;
        case R.id.zoom_fith:
            mDocument.setZoom(DroidReaderDocument.ZOOM_FIT_HEIGHT, false);
            return true;
        case R.id.zoom_orig:
            mDocument.setZoom(1.0F, false);
            return true;

        // Rotation:

        case R.id.rotation_left:
            mDocument.setRotation(270, true);
            return true;
        case R.id.rotation_right:
            mDocument.setRotation(90, true);
            return true;

        // Page Navigation:

        case R.id.goto_first:
            openPage(1, false);
            return true;
        case R.id.goto_last:
            openPage(DroidReaderDocument.PAGE_LAST, false);
            return true;
        case R.id.goto_ask:
            showDialog(DIALOG_GOTO_PAGE);
            return true;

        // File menu

        case R.id.open_file:
            // present the file manager's "open..." dialog
            Intent intent = new Intent(FileManagerIntents.ACTION_PICK_FILE);
            if (mDocumentIsOpen)
                intent.setData(Uri.parse("file://" + new File(mFilename).getParent()));
            else
                intent.setData(Uri.parse("file://"));
            intent.putExtra(FileManagerIntents.EXTRA_TITLE, getString(R.string.open_title));
            try {
                startActivityForResult(intent, REQUEST_CODE_PICK_FILE);
            } catch (ActivityNotFoundException e) {
                Toast.makeText(this, R.string.error_no_filemanager_installed,
                        Toast.LENGTH_SHORT).show();
            }
            return true;
        case R.id.options:
            Intent optionsIntent = new Intent(this,DroidReaderOptions.class);
            startActivityForResult(optionsIntent, REQUEST_CODE_OPTION_DIALOG);
            return true;
        case R.id.about:
            // show the about dialog
            showDialog(DIALOG_ABOUT);
            return true;
        case R.id.quit:
            // quit Activity
            finish();
            return true;
        }
        return false;
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        switch (requestCode) {
        case REQUEST_CODE_PICK_FILE:
            if (resultCode == RESULT_OK && data != null) {
                // Theoretically there could be a case where OnCreate() is called
                // again with the intent that was originally used to open the app,
                // which would revert to a previous document. Use setIntent
                // to update the intent that will be supplied back to OnCreate().
                setIntent(data);
                mTemporaryFilename = data.getDataString();
                if (mTemporaryFilename != null) {
                    if (mTemporaryFilename.startsWith("file://")) {
                        mTemporaryFilename = mTemporaryFilename.substring(7);
                    }
                    mPassword="";
                    openDocumentWithDecodeAndLookup();
                }
            }
            break;
        case REQUEST_CODE_OPTION_DIALOG:
            readPreferences();
            tryLoadLastFile();
            break;
        }
    }

    protected void tryLoadLastFile() {
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(this);
        mFilename = prefs.getString("last_open_file","");
        if (mFilename != null) {
            if ((mFilename.length() > 0) && ((new File(mFilename)).exists())) {
                // Don't URL-decode the filename, as that's presumably
                // already been done.
                mPassword="";
                openDocumentWithLookup();
                mLoadedDocument = true;
            }
        }
    }

    // URL-decode the filename, look it up in the database of previous
    // views, and then open the document.
    protected void openDocumentWithDecodeAndLookup() {
        try {
            // File names are URL-encoded (i.e. special chars are replaced
            // with %-escaped numbers). Decode them before opening.
            URLDecoder urldecoder = new URLDecoder();
            mTemporaryFilename = urldecoder.decode(mTemporaryFilename,"utf-8");

            // Do some sanity checks on the supplied filename.
            File f=new File(mTemporaryFilename);

            if ((f.exists()) && (f.isFile()) && (f.canRead())) {
                mFilename = mTemporaryFilename;
                openDocumentWithLookup();
            } else {
                Toast.makeText(this, R.string.error_file_open_failed,
                        Toast.LENGTH_LONG).show();
            }
        } catch (Exception e) {
            Toast.makeText(this, R.string.error_opening_document,
                    Toast.LENGTH_LONG).show();
        }
    }

    protected void openDocumentWithLookup() {
        readOrWriteDB(false);
        openDocument();
    }

    protected void openDocument() {
        // Store the view details for the previous document and close it.
        if (mDocumentIsOpen) {
            mDocument.closeDocument();
            mDocumentIsOpen = false;
        }
        try {
            this.setTitle(mFilename);
            mDocument.open(mFilename, mPassword, mPageNo);
            openPage(0, true);
            mDocumentIsOpen = true;
        } catch (PasswordNeededException e) {
            showDialog(DIALOG_GET_PASSWORD);
        } catch (WrongPasswordException e) {
            Toast.makeText(this, R.string.error_wrong_password,
                    Toast.LENGTH_LONG).show();
        } catch (Exception e) {
            Toast.makeText(this, R.string.error_opening_document,
                    Toast.LENGTH_LONG).show();
        }
    }

    protected void openPage(int no, boolean isRelative) {
        try {
            if(!(no == 0 && isRelative))
                mDocument.openPage(no, isRelative);
            this.setTitle(new File(mFilename).getName()+" ("+mDocument.mPage.no+"/"+mDocument.mDocument.pagecount+")");
        } catch (PageLoadException e) {
            // TODO Auto-generated catch block
        }
    }

    public void onTap (float X, float Y) {
        float left, right, top, bottom;
        float width = mDocument.mDisplaySizeX;
        float height = mDocument.mDisplaySizeY;
        boolean prev = false;
        boolean next = false;

        if (mDocumentIsOpen) {
            left = width * (float)0.25;
            right = width * (float)0.75;
            top = height * (float)0.25;
            bottom = height * (float)0.75;

            if ((X<left) && (Y < top))
                prev = true;
            if ((X<left) && (Y > bottom))
                next = true;
            if ((X>right) && (Y < top))
                prev = true;
            if ((X>right) && (Y > bottom))
                next = true;

            if (next) {
                if(mDocument.havePage(1, true))
                    openPage(1, true);
            } else if (prev) {
                if(mDocument.havePage(-1, true))
                    openPage(-1, true);
            }
        }
    }

    protected Dialog onCreateDialog(int id) {
        switch(id) {
        case DIALOG_GET_PASSWORD:
            // displays a password dialog, stores entered password
            // in mPassword, or resets it to an empty string if
            // the input is cancelled.
            AlertDialog.Builder builder = new AlertDialog.Builder(this);
            builder.setMessage(R.string.prompt_password);
            View passwordinput = getLayoutInflater().inflate(R.layout.passworddialog,
                    (ViewGroup) findViewById(R.id.input_password));
            builder.setView(passwordinput);
            builder.setCancelable(false);
            builder.setPositiveButton(R.string.button_pwddialog_open,
                new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface dialog, int id) {
                        DroidReaderActivity.this.mPassword =
                            ((EditText) ((AlertDialog) dialog).findViewById(R.id.input_password)).getText().toString();
                        // Don't URL-decode the filename, as that's already
                        // been done.
                        DroidReaderActivity.this.openDocumentWithLookup();
                        dialog.dismiss();
                    }
                });
            builder.setNegativeButton(R.string.button_pwddialog_cancel,
                new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface dialog, int id) {
                        dialog.cancel();
                    }
                });
            AlertDialog dialog = builder.create();
            return dialog;
        case DIALOG_GOTO_PAGE:
            AlertDialog.Builder gotoBuilder = new AlertDialog.Builder(this);
            gotoBuilder.setMessage(R.string.prompt_goto_page);
            View pageinput = getLayoutInflater().inflate(R.layout.pagedialog,
                    (ViewGroup) findViewById(R.id.input_page));
            gotoBuilder.setView(pageinput);
            gotoBuilder.setCancelable(false);
            gotoBuilder.setPositiveButton(R.string.button_page_open,
                new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface dialog, int id) {
                        try {
                            DroidReaderActivity.this.openPage(
                                Integer.parseInt(
                                    ((EditText)
                                            ((AlertDialog) dialog).findViewById(R.id.input_page))
                                            .getText()
                                            .toString()), false);
                        } catch (NumberFormatException e) {
                            // TODO Auto-generated catch block
                        }
                        dialog.dismiss();
                    }
                });
            gotoBuilder.setNegativeButton(R.string.button_page_cancel,
                new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface dialog, int id) {
                        dialog.cancel();
                    }
                });
            AlertDialog gotoDialog = gotoBuilder.create();
            return gotoDialog;
        case DIALOG_ABOUT:
            return showHtmlDialog(R.string.menu_file_about,"about.html");
        case DIALOG_WELCOME:
            return showHtmlDialog(R.string.welcome_title,"welcome.html");
        }
        return null;
    }

    AlertDialog showHtmlDialog(int titleResource, String htmlFile) {
        AlertDialog.Builder htmlBuilder = new AlertDialog.Builder(this);
        WebView htmlWebView = new WebView(this);
        htmlWebView.loadData(readHtml(htmlFile).toString(), "text/html", "UTF-8");
        htmlBuilder.setView(htmlWebView);
        htmlBuilder.setCancelable(false);
        htmlBuilder.setPositiveButton(R.string.button_ok,
                new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface dialog, int id) {
                        dialog.dismiss();
                    }
                });
        AlertDialog htmlDialog = htmlBuilder.create();
        htmlDialog.setTitle(getString(titleResource));
        return htmlDialog;
    }

    private CharSequence readHtml(String htmlFile) {
        BufferedReader in = null;
        try {
            in = new BufferedReader(new InputStreamReader(this.getAssets().open(htmlFile)));
            String line;
            StringBuilder buffer = new StringBuilder();
            while ((line = in.readLine()) != null)
                buffer.append(line).append('\n');
            if (in != null) {
                try {
                    in.close();
                } catch (IOException e) {
                    // We can't do anything...
                }
            }
            return buffer;
        } catch (IOException e) {
            return "";
        }
    }

    protected void readOrWriteDB(boolean doWrite) {
        SQLiteDatabase pdfDB = null;
        try {
            pdfDB = this.openOrCreateDatabase("DroidReaderPDFDB", MODE_PRIVATE, null );
            pdfDB.execSQL(	"CREATE TABLE IF NOT EXISTS LastReadPoint (" +
                            "Filename VARCHAR, Zoom DECIMAL(10,5), " +
                            "Rotation INTEGER, Page INTEGER, " +
                            "OffsetX INTEGER, OffsetY INTEGER, " +
                            "MarginOffsetX INTEGER, MarginOffsetY INTEGER, " +
                            "ContentFitMode INTEGER, MemoryMode INTEGER, " +
                            "Password VARCHAR );");
            Cursor c = pdfDB.rawQuery ("SELECT * FROM LastReadPoint WHERE Filename = '" + mFilename + "'", null);

            // c shouldn't be null, if it is then there's an external problem
            if (c != null) {
                if (c.getCount() > 0) {
                    // There's already an entry for this file.
                    c.moveToFirst();
                    if (doWrite) {
                        pdfDB.execSQL("UPDATE LastReadPoint SET " +
                            "Zoom = " + mDocument.mZoom + " , " +
                            "Rotation = " + mDocument.mRotation + " , " +
                            "Page = " + mDocument.mPage.no + " , " +
                            "OffsetX = " + mDocument.mOffsetX + " , " +
                            "OffsetY = " + mDocument.mOffsetY + " , " +
                            "MarginOffsetX = " + mDocument.mMarginOffsetX + " , " +
                            "MarginOffsetY = " + mDocument.mMarginOffsetY + " , " +
                            "ContentFitMode = 0 , MemoryMode = 0 ," +
                            "Password = '" + mPassword + "' " +
                            "WHERE Filename = '" + mFilename + "';");
                    } else {
                        mDocument.mZoom = c.getFloat(c.getColumnIndex("Zoom"));
                        mDocument.mRotation = c.getInt(c.getColumnIndex("Rotation"));
                        mPageNo = c.getInt(c.getColumnIndex("Page"));
                        if (mPageNo == 0)
                            mPageNo = 1;
                        mOffsetX = c.getInt(c.getColumnIndex("OffsetX"));
                        mOffsetY = c.getInt(c.getColumnIndex("OffsetY"));
                        mDocument.mMarginOffsetX = c.getInt(c.getColumnIndex("MarginOffsetX"));
                        mDocument.mMarginOffsetY = c.getInt(c.getColumnIndex("MarginOffsetY"));

// Don't restore the password. This would be a bit of a security nightmare,
// because documents would be unsecured after the password was entered once -
// and there wouldn't be any way to re-secure them. Presumably people who
// use password-protected PDFs will prefer to enter the password whenever they
// open the document.
//						if (mPassword.length() == 0) {
//							mPassword = c.getString(c.getColumnIndex("Password"));
//						}
                    }
                } else {
                    // No entry found for this file.
                    if (doWrite) {
                        pdfDB.execSQL("INSERT INTO LastReadPoint VALUES ('" +
                            mFilename + "', " +
                            mDocument.mZoom + " , " +
                            mDocument.mRotation + " , " +
                            mDocument.mPage.no + " , " +
                            mDocument.mOffsetX + " , " +
                            mDocument.mOffsetY + " , " +
                            mDocument.mMarginOffsetX + " , " +
                            mDocument.mMarginOffsetY + " , 0 , 0 ," +
                            "'" + mPassword + "');" );
                    } else {
                        // reading: Set some default values
                        mDocument.mZoom = mDocument.ZOOM_FIT;
                        mDocument.mRotation = 0;
                        mOffsetX = 0;
                        mOffsetY = 0;
                        mDocument.mMarginOffsetX = 0;
                        mDocument.mMarginOffsetY = 0;
                        mPageNo = 1;
                    }
                }
                c.close();
            } else {
                if (LOG) Log.d ("DroidReaderDB", "Problem here... no Cursor, query must have failed");
            }
        } catch (SQLiteException se ) {
            Log.e(getClass().getSimpleName(), "Could not create or open the database");
        } finally {
            if (pdfDB != null) {
                pdfDB.close();
            }
        }
    }
}
