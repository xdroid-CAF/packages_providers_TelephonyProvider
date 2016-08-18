/*
 * Copyright (C) 2007 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.providers.telephony;

import android.app.AppOpsManager;
import android.content.ContentProvider;
import android.content.ContentResolver;
import android.content.ContentUris;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.content.UriMatcher;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;
import android.database.sqlite.SQLiteQueryBuilder;
import android.net.Uri;
import android.os.Binder;
import android.os.FileUtils;
import android.os.ParcelFileDescriptor;
import android.provider.BaseColumns;
import android.provider.Telephony;
import android.provider.Telephony.CanonicalAddressesColumns;
import android.provider.Telephony.Mms;
import android.provider.Telephony.MmsSms;
import android.provider.Telephony.Mms.Addr;
import android.provider.Telephony.Mms.Part;
import android.provider.Telephony.Mms.Rate;
import android.text.TextUtils;
import android.util.Log;

import com.google.android.mms.MmsException;
import com.google.android.mms.pdu.GenericPdu;
import com.google.android.mms.pdu.PduComposer;
import com.google.android.mms.pdu.RetrieveConf;
import com.google.android.mms.pdu.PduHeaders;
import com.google.android.mms.pdu.PduPersister;
import com.google.android.mms.pdu.PduParser;
import com.google.android.mms.pdu.SendReq;
import com.google.android.mms.util.DownloadDrmHelper;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.util.HashSet;

import android.provider.Telephony.Threads;

/**
 * The class to provide base facility to access MMS related content,
 * which is stored in a SQLite database and in the file system.
 */
public class MmsProvider extends ContentProvider {
    static final String TABLE_PDU  = "pdu";
    static final String TABLE_ADDR = "addr";
    static final String TABLE_PART = "part";
    static final String TABLE_RATE = "rate";
    static final String TABLE_DRM  = "drm";
    static final String TABLE_WORDS = "words";

    // Define the max length of file name
    private static final int MAX_FILE_NAME_LENGTH = 30;

    private final static String[] PDU_COLUMNS = new String[] {
        "_id",
        "pdu_path",
        "pdu_data"
    };

    @Override
    public boolean onCreate() {
        setAppOps(AppOpsManager.OP_READ_MMS, AppOpsManager.OP_WRITE_MMS);
        mOpenHelper = MmsSmsDatabaseHelper.getInstance(getContext());
        return true;
    }

    private byte[] getPduDataFromDB(int msgId, int msgType) {
        Uri uri = ContentUris.withAppendedId(Mms.CONTENT_URI, msgId);
        PduPersister persister = PduPersister.getPduPersister(getContext());
        byte[] mmData = null;
        try {
            if (Mms.MESSAGE_BOX_INBOX == msgType
                    || Mms.MESSAGE_BOX_SENT == msgType) {
                GenericPdu pdu = persister.load(uri);
                if (pdu != null) {
                    mmData = new PduComposer(getContext(), pdu).make();
                }
            }
        } catch (MmsException e) {
            Log.d(TAG, "MmsException   e=" + e);
        }
        return mmData;
    }

    private Cursor getPdus(int itemCount, int dataCount, String[] data) {
        MatrixCursor cursor = new MatrixCursor(PDU_COLUMNS, 1);
        long start = System.currentTimeMillis();
        long token = Binder.clearCallingIdentity();
        SQLiteDatabase db = mOpenHelper.getReadableDatabase();
        db.beginTransaction();
        try {
            for (int i = 0; i < dataCount; i++) {
                int msgId = Integer.parseInt(data[i * itemCount]);
                int msgType = Integer.parseInt(data[i * itemCount + 1]);
                String pduPath = data[i * itemCount + 2];
                byte[] pduData = getPduDataFromDB(msgId, msgType);
                if (pduData == null || pduData.length == 0) {
                    Log.d(TAG, "can't get msgI:" + msgId + " pdu data.");
                    continue;
                }
                Object[] row = new Object[3];
                row[0] = msgId;
                row[1] = pduPath;
                row[2] = pduData;
                cursor.addRow(row);
            }
            db.setTransactionSuccessful();
        } catch (Exception e) {
            Log.d(TAG, "Exception  e =", e);
        } finally {
            Log.d(TAG, "finish get pdu data using:"
                    + (System.currentTimeMillis() - start));
            Binder.restoreCallingIdentity(token);
            db.endTransaction();
        }
        return cursor;
    }

    @Override
    public Cursor query(Uri uri, String[] projection,
            String selection, String[] selectionArgs, String sortOrder) {
        SQLiteQueryBuilder qb = new SQLiteQueryBuilder();

        // Generate the body of the query.
        int match = sURLMatcher.match(uri);
        if (LOCAL_LOGV) {
            Log.v(TAG, "Query uri=" + uri + ", match=" + match);
        }

        switch (match) {
            case MMS_ALL:
                constructQueryForBox(qb, Mms.MESSAGE_BOX_ALL);
                break;
            case MMS_INBOX:
                constructQueryForBox(qb, Mms.MESSAGE_BOX_INBOX);
                break;
            case MMS_SENT:
                constructQueryForBox(qb, Mms.MESSAGE_BOX_SENT);
                break;
            case MMS_DRAFTS:
                constructQueryForBox(qb, Mms.MESSAGE_BOX_DRAFTS);
                break;
            case MMS_OUTBOX:
                constructQueryForBox(qb, Mms.MESSAGE_BOX_OUTBOX);
                break;
            case MMS_ALL_ID:
                qb.setTables(TABLE_PDU);
                qb.appendWhere(Mms._ID + "=" + uri.getPathSegments().get(0));
                break;
            case MMS_INBOX_ID:
            case MMS_SENT_ID:
            case MMS_DRAFTS_ID:
            case MMS_OUTBOX_ID:
                qb.setTables(TABLE_PDU);
                qb.appendWhere(Mms._ID + "=" + uri.getPathSegments().get(1));
                qb.appendWhere(" AND " + Mms.MESSAGE_BOX + "="
                        + getMessageBoxByMatch(match));
                break;
            case MMS_ALL_PART:
                qb.setTables(TABLE_PART);
                break;
            case MMS_MSG_PART:
                qb.setTables(TABLE_PART);
                qb.appendWhere(Part.MSG_ID + "=" + uri.getPathSegments().get(0));
                break;
            case MMS_PART_ID:
                qb.setTables(TABLE_PART);
                qb.appendWhere(Part._ID + "=" + uri.getPathSegments().get(1));
                break;
            case MMS_MSG_ADDR:
                qb.setTables(TABLE_ADDR);
                qb.appendWhere(Addr.MSG_ID + "=" + uri.getPathSegments().get(0));
                break;
            case MMS_REPORT_STATUS:
                /*
                   SELECT DISTINCT address,
                                   T.delivery_status AS delivery_status,
                                   T.read_status AS read_status
                   FROM addr
                   INNER JOIN (SELECT P1._id AS id1, P2._id AS id2, P3._id AS id3,
                                      ifnull(P2.st, 0) AS delivery_status,
                                      ifnull(P3.read_status, 0) AS read_status
                               FROM pdu P1
                               INNER JOIN pdu P2
                               ON P1.m_id = P2.m_id AND P2.m_type = 134
                               LEFT JOIN pdu P3
                               ON P1.m_id = P3.m_id AND P3.m_type = 136
                               UNION
                               SELECT P1._id AS id1, P2._id AS id2, P3._id AS id3,
                                      ifnull(P2.st, 0) AS delivery_status,
                                      ifnull(P3.read_status, 0) AS read_status
                               FROM pdu P1
                               INNER JOIN pdu P3
                               ON P1.m_id = P3.m_id AND P3.m_type = 136
                               LEFT JOIN pdu P2
                               ON P1.m_id = P2.m_id AND P2.m_type = 134) T
                   ON (msg_id = id2 AND type = 151)
                   OR (msg_id = id3 AND type = 137)
                   WHERE T.id1 = ?;
                 */
                qb.setTables("addr INNER JOIN (SELECT P1._id AS id1, P2._id" +
                             " AS id2, P3._id AS id3, ifnull(P2.st, 0) AS" +
                             " delivery_status, ifnull(P3.read_status, 0) AS" +
                             " read_status FROM pdu P1 INNER JOIN pdu P2 ON" +
                             " P1.m_id=P2.m_id AND P2.m_type=134 LEFT JOIN" +
                             " pdu P3 ON P1.m_id=P3.m_id AND P3.m_type=136" +
                             " UNION SELECT P1._id AS id1, P2._id AS id2, P3._id" +
                             " AS id3, ifnull(P2.st, 0) AS delivery_status," +
                             " ifnull(P3.read_status, 0) AS read_status FROM" +
                             " pdu P1 INNER JOIN pdu P3 ON P1.m_id=P3.m_id AND" +
                             " P3.m_type=136 LEFT JOIN pdu P2 ON P1.m_id=P2.m_id" +
                             " AND P2.m_type=134) T ON (msg_id=id2 AND type=151)" +
                             " OR (msg_id=id3 AND type=137)");
                qb.appendWhere("T.id1 = " + uri.getLastPathSegment());
                qb.setDistinct(true);
                break;
            case MMS_REPORT_REQUEST:
                /*
                   SELECT address, d_rpt, rr
                   FROM addr join pdu on pdu._id = addr.msg_id
                   WHERE pdu._id = messageId AND addr.type = 151
                 */
                qb.setTables(TABLE_ADDR + " join " +
                        TABLE_PDU + " on pdu._id = addr.msg_id");
                qb.appendWhere("pdu._id = " + uri.getLastPathSegment());
                qb.appendWhere(" AND " + "addr.type = " + PduHeaders.TO);
                break;
            case MMS_SENDING_RATE:
                qb.setTables(TABLE_RATE);
                break;
            case MMS_DRM_STORAGE_ID:
                qb.setTables(TABLE_DRM);
                qb.appendWhere(BaseColumns._ID + "=" + uri.getLastPathSegment());
                break;
            case MMS_THREADS:
                qb.setTables("pdu group by thread_id");
                break;
            case MMS_GET_PDU:
                int itemCount = Integer.parseInt(uri.getQueryParameter("item_count"));
                int dataCount = Integer.parseInt(uri.getQueryParameter("data_count"));
                String split = (uri.getQueryParameter("data_split"));
                String[] data = uri.getQueryParameter("data").split(split);
                Log.d(TAG, "data.length :" + data.length);
                return getPdus(itemCount, dataCount, data);
            default:
                Log.e(TAG, "query: invalid request: " + uri);
                return null;
        }

        String finalSortOrder = null;
        if (TextUtils.isEmpty(sortOrder)) {
            if (qb.getTables().equals(TABLE_PDU)) {
                finalSortOrder = Mms.DATE + " DESC";
            } else if (qb.getTables().equals(TABLE_PART)) {
                finalSortOrder = Part.SEQ;
            }
        } else {
            finalSortOrder = sortOrder;
        }

        SQLiteDatabase db = mOpenHelper.getReadableDatabase();
        Cursor ret = qb.query(db, projection, selection,
                selectionArgs, null, null, finalSortOrder);

        // TODO: Does this need to be a URI for this provider.
        ret.setNotificationUri(getContext().getContentResolver(), uri);
        return ret;
    }

    private void constructQueryForBox(SQLiteQueryBuilder qb, int msgBox) {
        qb.setTables(TABLE_PDU);

        if (msgBox != Mms.MESSAGE_BOX_ALL) {
            qb.appendWhere(Mms.MESSAGE_BOX + "=" + msgBox);
        }
    }

    @Override
    public String getType(Uri uri) {
        int match = sURLMatcher.match(uri);
        switch (match) {
            case MMS_ALL:
            case MMS_INBOX:
            case MMS_SENT:
            case MMS_DRAFTS:
            case MMS_OUTBOX:
                return VND_ANDROID_DIR_MMS;
            case MMS_ALL_ID:
            case MMS_INBOX_ID:
            case MMS_SENT_ID:
            case MMS_DRAFTS_ID:
            case MMS_OUTBOX_ID:
                return VND_ANDROID_MMS;
            case MMS_PART_ID: {
                Cursor cursor = mOpenHelper.getReadableDatabase().query(
                        TABLE_PART, new String[] { Part.CONTENT_TYPE },
                        Part._ID + " = ?", new String[] { uri.getLastPathSegment() },
                        null, null, null);
                if (cursor != null) {
                    try {
                        if ((cursor.getCount() == 1) && cursor.moveToFirst()) {
                            return cursor.getString(0);
                        } else {
                            Log.e(TAG, "cursor.count() != 1: " + uri);
                        }
                    } finally {
                        cursor.close();
                    }
                } else {
                    Log.e(TAG, "cursor == null: " + uri);
                }
                return "*/*";
            }
            case MMS_ALL_PART:
            case MMS_MSG_PART:
            case MMS_MSG_ADDR:
            default:
                return "*/*";
        }
    }

    private byte[] getPduDataFromFile(String pduPath) {
        FileInputStream fin = null;
        byte[] data = null;
        try {
            File pduFile = new File(pduPath);
            data = new byte[(int)pduFile.length()];
            fin = new FileInputStream(pduFile);
            fin.read(data);
        } catch (Exception e) {
            Log.e(TAG, "read file exception :", e);
        } finally {
            try {
                if (fin != null)
                    fin.close();
            } catch (Exception e) {
            }
        }
        return data;
    }

    private Uri restorePduFile(Uri uri, String pduPath) {
        Uri msgUri = null;
        if (uri == null || TextUtils.isEmpty(pduPath)) {
            return null;
        }

        try {
            byte[] pduData = getPduDataFromFile(pduPath);
            PduPersister pduPersister = PduPersister.getPduPersister(getContext());
            if (pduData != null && pduData.length > 0) {
                if (Mms.Sent.CONTENT_URI.equals(uri)
                        || Mms.Inbox.CONTENT_URI.equals(uri)) {
                    GenericPdu pdu = new PduParser(pduData).parse();
                    msgUri = pduPersister.persist(
                            pdu, uri, true, false, null);
                } else {
                    Log.e(TAG,"Don't support uri :" + uri);
                }
            }
        } catch (MmsException e) {
            Log.d(TAG, "MmsException: ", e);
        }
        return msgUri;
    }

    private String getPduPath(String dir, ContentValues values) {
        if (dir != null && values != null && values.containsKey("pdu_path")) {
            String path = values.getAsString("pdu_path");
            if (!TextUtils.isEmpty(path)) {
                return dir + "/" + path;
            }
        }
        return null;
    }

    private int restoreMms(Uri uri, ContentValues values, String dir) {
        int count = 0;
        Uri msgUri = restorePduFile(uri, getPduPath(dir, values));
        if (msgUri != null) {
            String selection = Mms._ID + "=" + msgUri.getLastPathSegment();
            values.remove("pdu_path");
            ContentValues finalValues = new ContentValues(values);
            // now only support bulkInsert pdu table.
            SQLiteDatabase db = mOpenHelper.getWritableDatabase();
            count = db.update(TABLE_PDU, finalValues, selection, null);
        }
        return count;
    }

    @Override
    public int bulkInsert(Uri uri, ContentValues[] values) {
        String dir = uri.getQueryParameter("restore_dir");
        if (TextUtils.isEmpty(dir)) {
            return super.bulkInsert(uri, values);
        }

        Uri insertUri = null;
        int match = sURLMatcher.match(uri);
        switch (match) {
            case MMS_INBOX:
                insertUri = Mms.Inbox.CONTENT_URI;
                break;
            case MMS_SENT:
                insertUri = Mms.Sent.CONTENT_URI;
                break;
            default:
                return 0;
        }

        long token = Binder.clearCallingIdentity();
        int count = 0;
        SQLiteDatabase db = mOpenHelper.getWritableDatabase();
        long start = System.currentTimeMillis();
        db.beginTransaction();
        try {
            for (ContentValues value : values) {
                count += restoreMms(insertUri, value, dir);
            }

            Log.d(TAG, "bulkInsert  request count: " + values.length
                    + " successfully count : " + count
                    + " using : " + (System.currentTimeMillis() - start));
            if (count == values.length) {
                db.setTransactionSuccessful();
            }
            return count;
        } finally {
            db.endTransaction();
            Binder.restoreCallingIdentity(token);
        }
    }


    @Override
    public Uri insert(Uri uri, ContentValues values) {
        // The _data column is filled internally in MmsProvider, so this check is just to avoid
        // it from being inadvertently set. This is not supposed to be a protection against
        // malicious attack, since sql injection could still be attempted to bypass the check. On
        // the other hand, the MmsProvider does verify that the _data column has an allowed value
        // before opening any uri/files.
        if (values != null && values.containsKey(Part._DATA)) {
            return null;
        }
        int msgBox = Mms.MESSAGE_BOX_ALL;
        boolean notify = true;

        int match = sURLMatcher.match(uri);
        if (LOCAL_LOGV) {
            Log.v(TAG, "Insert uri=" + uri + ", match=" + match);
        }

        String table = TABLE_PDU;
        switch (match) {
            case MMS_ALL:
                Object msgBoxObj = values.getAsInteger(Mms.MESSAGE_BOX);
                if (msgBoxObj != null) {
                    msgBox = (Integer) msgBoxObj;
                }
                else {
                    // default to inbox
                    msgBox = Mms.MESSAGE_BOX_INBOX;
                }
                break;
            case MMS_INBOX:
                msgBox = Mms.MESSAGE_BOX_INBOX;
                break;
            case MMS_SENT:
                msgBox = Mms.MESSAGE_BOX_SENT;
                break;
            case MMS_DRAFTS:
                msgBox = Mms.MESSAGE_BOX_DRAFTS;
                break;
            case MMS_OUTBOX:
                msgBox = Mms.MESSAGE_BOX_OUTBOX;
                break;
            case MMS_MSG_PART:
                notify = false;
                table = TABLE_PART;
                break;
            case MMS_MSG_ADDR:
                notify = false;
                table = TABLE_ADDR;
                break;
            case MMS_SENDING_RATE:
                notify = false;
                table = TABLE_RATE;
                break;
            case MMS_DRM_STORAGE:
                notify = false;
                table = TABLE_DRM;
                break;
            default:
                Log.e(TAG, "insert: invalid request: " + uri);
                return null;
        }

        SQLiteDatabase db = mOpenHelper.getWritableDatabase();
        ContentValues finalValues;
        Uri res = Mms.CONTENT_URI;
        long rowId;

        if (table.equals(TABLE_PDU)) {
            boolean addDate = !values.containsKey(Mms.DATE);
            boolean addMsgBox = !values.containsKey(Mms.MESSAGE_BOX);

            // Filter keys we don't support yet.
            filterUnsupportedKeys(values);

            // TODO: Should initialValues be validated, e.g. if it
            // missed some significant keys?
            finalValues = new ContentValues(values);

            long timeInMillis = System.currentTimeMillis();

            if (addDate) {
                finalValues.put(Mms.DATE, timeInMillis / 1000L);
            }

            if (addMsgBox && (msgBox != Mms.MESSAGE_BOX_ALL)) {
                finalValues.put(Mms.MESSAGE_BOX, msgBox);
            }

            if (msgBox != Mms.MESSAGE_BOX_INBOX) {
                // Mark all non-inbox messages read.
                finalValues.put(Mms.READ, 1);
            }

            // thread_id
            Long threadId = values.getAsLong(Mms.THREAD_ID);
            String address = values.getAsString(CanonicalAddressesColumns.ADDRESS);

            if (((threadId == null) || (threadId == 0)) && (!TextUtils.isEmpty(address))) {
                finalValues.put(Mms.THREAD_ID, Threads.getOrCreateThreadId(getContext(), address));
            }

            if ((rowId = db.insert(table, null, finalValues)) <= 0) {
                Log.e(TAG, "MmsProvider.insert: failed! " + finalValues);
                return null;
            }

            res = Uri.parse(res + "/" + rowId);

        } else if (table.equals(TABLE_ADDR)) {
            finalValues = new ContentValues(values);
            finalValues.put(Addr.MSG_ID, uri.getPathSegments().get(0));

            if ((rowId = db.insert(table, null, finalValues)) <= 0) {
                Log.e(TAG, "Failed to insert address: " + finalValues);
                return null;
            }

            res = Uri.parse(res + "/addr/" + rowId);
        } else if (table.equals(TABLE_PART)) {
            finalValues = new ContentValues(values);

            if (match == MMS_MSG_PART) {
                finalValues.put(Part.MSG_ID, uri.getPathSegments().get(0));
            }

            String contentType = values.getAsString("ct");

            // text/plain and app application/smil store their "data" inline in the
            // table so there's no need to create the file
            boolean plainText = false;
            boolean smilText = false;
            if ("text/plain".equals(contentType)) {
                plainText = true;
            } else if ("application/smil".equals(contentType)) {
                smilText = true;
            }
            if (!plainText && !smilText) {
                // Use the filename if possible, otherwise use the current time as the name.
                String contentLocation = values.getAsString("cl");
                if (!TextUtils.isEmpty(contentLocation)) {
                    // In Android, the name of a new file has it's limit.
                    // We must limit the part file name length.
                    // Sub 30 words so the name length can't over limit.
                    contentLocation = "_" + getFileName(contentLocation);
                } else {
                    contentLocation = "";
                }

                // Generate the '_data' field of the part with default
                // permission settings.
                String path = getContext().getDir("parts", 0).getPath()
                        + "/PART_" + System.currentTimeMillis() + contentLocation;

                if (DownloadDrmHelper.isDrmConvertNeeded(contentType)) {
                    // Adds the .fl extension to the filename if contentType is
                    // "application/vnd.oma.drm.message"
                    path = DownloadDrmHelper.modifyDrmFwLockFileExtension(path);
                }

                finalValues.put(Part._DATA, path);

                File partFile = new File(path);
                if (!partFile.exists()) {
                    try {
                        if (!partFile.createNewFile()) {
                            throw new IllegalStateException(
                                    "Unable to create new partFile: " + path);
                        }
                        // Give everyone rw permission until we encrypt the file
                        // (in PduPersister.persistData). Once the file is encrypted, the
                        // permissions will be set to 0644.
                        int result = FileUtils.setPermissions(path, 0666, -1, -1);
                        if (LOCAL_LOGV) {
                            Log.d(TAG, "MmsProvider.insert setPermissions result: " + result);
                        }
                    } catch (IOException e) {
                        Log.e(TAG, "createNewFile", e);
                        throw new IllegalStateException(
                                "Unable to create new partFile: " + path);
                    }
                }
            }

            if ((rowId = db.insert(table, null, finalValues)) <= 0) {
                Log.e(TAG, "MmsProvider.insert: failed! " + finalValues);
                return null;
            }

            res = Uri.parse(res + "/part/" + rowId);

            // Don't use a trigger for updating the words table because of a bug
            // in FTS3.  The bug is such that the call to get the last inserted
            // row is incorrect.
            if (plainText) {
                // Update the words table with a corresponding row.  The words table
                // allows us to search for words quickly, without scanning the whole
                // table;
                ContentValues cv = new ContentValues();

                // we're using the row id of the part table row but we're also using ids
                // from the sms table so this divides the space into two large chunks.
                // The row ids from the part table start at 2 << 32.
                cv.put(Telephony.MmsSms.WordsTable.ID, (2 << 32) + rowId);
                cv.put(Telephony.MmsSms.WordsTable.INDEXED_TEXT, values.getAsString("text"));
                cv.put(Telephony.MmsSms.WordsTable.SOURCE_ROW_ID, rowId);
                cv.put(Telephony.MmsSms.WordsTable.TABLE_ID, 2);
                db.insert(TABLE_WORDS, Telephony.MmsSms.WordsTable.INDEXED_TEXT, cv);
            }

        } else if (table.equals(TABLE_RATE)) {
            long now = values.getAsLong(Rate.SENT_TIME);
            long oneHourAgo = now - 1000 * 60 * 60;
            // Delete all unused rows (time earlier than one hour ago).
            db.delete(table, Rate.SENT_TIME + "<=" + oneHourAgo, null);
            db.insert(table, null, values);
        } else if (table.equals(TABLE_DRM)) {
            String path = getContext().getDir("parts", 0).getPath()
                    + "/PART_" + System.currentTimeMillis();
            finalValues = new ContentValues(1);
            finalValues.put("_data", path);

            File partFile = new File(path);
            if (!partFile.exists()) {
                try {
                    if (!partFile.createNewFile()) {
                        throw new IllegalStateException(
                                "Unable to create new file: " + path);
                    }
                } catch (IOException e) {
                    Log.e(TAG, "createNewFile", e);
                    throw new IllegalStateException(
                            "Unable to create new file: " + path);
                }
            }

            if ((rowId = db.insert(table, null, finalValues)) <= 0) {
                Log.e(TAG, "MmsProvider.insert: failed! " + finalValues);
                return null;
            }
            res = Uri.parse(res + "/drm/" + rowId);
        } else {
            throw new AssertionError("Unknown table type: " + table);
        }

        if (notify) {
            notifyChange();
        }
        return res;
    }

    private String getFileName(String fileLocation) {
        File f = new File(fileLocation);
        String fileName = f.getName();
        if (fileName.length() >= MAX_FILE_NAME_LENGTH) {
            fileName = fileName.substring(0, MAX_FILE_NAME_LENGTH);
        }
        return fileName;
    }

    private int getMessageBoxByMatch(int match) {
        switch (match) {
            case MMS_INBOX_ID:
            case MMS_INBOX:
                return Mms.MESSAGE_BOX_INBOX;
            case MMS_SENT_ID:
            case MMS_SENT:
                return Mms.MESSAGE_BOX_SENT;
            case MMS_DRAFTS_ID:
            case MMS_DRAFTS:
                return Mms.MESSAGE_BOX_DRAFTS;
            case MMS_OUTBOX_ID:
            case MMS_OUTBOX:
                return Mms.MESSAGE_BOX_OUTBOX;
            default:
                throw new IllegalArgumentException("bad Arg: " + match);
        }
    }

    @Override
    public int delete(Uri uri, String selection,
            String[] selectionArgs) {
        int match = sURLMatcher.match(uri);
        if (LOCAL_LOGV) {
            Log.v(TAG, "Delete uri=" + uri + ", match=" + match);
        }

        String table, extraSelection = null;
        boolean notify = false;

        switch (match) {
            case MMS_ALL_ID:
            case MMS_INBOX_ID:
            case MMS_SENT_ID:
            case MMS_DRAFTS_ID:
            case MMS_OUTBOX_ID:
                notify = true;
                table = TABLE_PDU;
                extraSelection = Mms._ID + "=" + uri.getLastPathSegment();
                break;
            case MMS_ALL:
            case MMS_INBOX:
            case MMS_SENT:
            case MMS_DRAFTS:
            case MMS_OUTBOX:
                notify = true;
                table = TABLE_PDU;
                if (match != MMS_ALL) {
                    int msgBox = getMessageBoxByMatch(match);
                    extraSelection = Mms.MESSAGE_BOX + "=" + msgBox;
                }
                break;
            case MMS_ALL_PART:
                table = TABLE_PART;
                break;
            case MMS_MSG_PART:
                table = TABLE_PART;
                extraSelection = Part.MSG_ID + "=" + uri.getPathSegments().get(0);
                break;
            case MMS_PART_ID:
                table = TABLE_PART;
                extraSelection = Part._ID + "=" + uri.getPathSegments().get(1);
                break;
            case MMS_MSG_ADDR:
                table = TABLE_ADDR;
                extraSelection = Addr.MSG_ID + "=" + uri.getPathSegments().get(0);
                break;
            case MMS_DRM_STORAGE:
                table = TABLE_DRM;
                break;
            default:
                Log.w(TAG, "No match for URI '" + uri + "'");
                return 0;
        }

        String finalSelection = concatSelections(selection, extraSelection);
        SQLiteDatabase db = mOpenHelper.getWritableDatabase();
        int deletedRows = 0;

        if (TABLE_PDU.equals(table)) {
            deletedRows = deleteMessages(getContext(), db, finalSelection,
                                         selectionArgs, uri);
        } else if (TABLE_PART.equals(table)) {
            deletedRows = deleteParts(db, finalSelection, selectionArgs);
            // Because the trigger for part table is deleted
            // We need to update word and threads table after delete sth in part table
            cleanUpWords(db);
            updateHasAttachment(db);
        } else if (TABLE_DRM.equals(table)) {
            deletedRows = deleteTempDrmData(db, finalSelection, selectionArgs);
        } else {
            deletedRows = db.delete(table, finalSelection, selectionArgs);
        }

        if ((deletedRows > 0) && notify) {
            notifyChange();
        }
        return deletedRows;
    }

    static int deleteMessages(Context context, SQLiteDatabase db,
            String selection, String[] selectionArgs, Uri uri) {
        Cursor cursor = db.query(TABLE_PDU, new String[] { Mms._ID, Mms.THREAD_ID },
                selection, selectionArgs, null, null, null);
        if (cursor == null) {
            return 0;
        }

        HashSet<Long> threadIds = new HashSet<Long>();
        try {
            if (cursor.getCount() == 0) {
                return 0;
            }

            while (cursor.moveToNext()) {
                deleteParts(db, Part.MSG_ID + " = ?",
                        new String[] { String.valueOf(cursor.getLong(0)) });
                threadIds.add(cursor.getLong(1));
            }
            // Because the trigger for part table is deleted
            // We need to update word and threads table after delete sth in part table
            cleanUpWords(db);
            updateHasAttachment(db);
        } finally {
            cursor.close();
        }

        int count = db.delete(TABLE_PDU, selection, selectionArgs);
        // The triggers used to update threads after delete pdu is deleted
        // Remenber to update threads which related to the deleted pdus
        for (long thread : threadIds) {
            MmsSmsDatabaseHelper.updateThread(db, thread);
        }
        if (count > 0) {
            Intent intent = new Intent(Mms.Intents.CONTENT_CHANGED_ACTION);
            intent.putExtra(Mms.Intents.DELETED_CONTENTS, uri);
            if (LOCAL_LOGV) {
                Log.v(TAG, "Broadcasting intent: " + intent);
            }
            context.sendBroadcast(intent);
        }
        return count;
    }

    private static void cleanUpWords(SQLiteDatabase db) {
        db.execSQL("DELETE FROM words WHERE source_id not in (select _id from part) AND "
                + "table_to_use = 2");
    }

    private static void updateHasAttachment(SQLiteDatabase db) {
        db.execSQL("UPDATE threads SET has_attachment = CASE "
                + "(SELECT COUNT(*) FROM part JOIN pdu WHERE part.mid = pdu._id AND "
                + "pdu.thread_id = threads._id AND part.ct != 'text/plain' "
                + "AND part.ct != 'application/smil') WHEN 0 THEN 0 ELSE 1 END");
    }

    private static int deleteParts(SQLiteDatabase db, String selection,
            String[] selectionArgs) {
        return deleteDataRows(db, TABLE_PART, selection, selectionArgs);
    }

    private static int deleteTempDrmData(SQLiteDatabase db, String selection,
            String[] selectionArgs) {
        return deleteDataRows(db, TABLE_DRM, selection, selectionArgs);
    }

    private static int deleteDataRows(SQLiteDatabase db, String table,
            String selection, String[] selectionArgs) {
        Cursor cursor = db.query(table, new String[] { "_data" },
                selection, selectionArgs, null, null, null);
        if (cursor == null) {
            // FIXME: This might be an error, ignore it may cause
            // unpredictable result.
            return 0;
        }

        try {
            if (cursor.getCount() == 0) {
                return 0;
            }

            while (cursor.moveToNext()) {
                try {
                    // Delete the associated files saved on file-system.
                    String path = cursor.getString(0);
                    if (path != null) {
                        new File(path).delete();
                    }
                } catch (Throwable ex) {
                    Log.e(TAG, ex.getMessage(), ex);
                }
            }
        } finally {
            cursor.close();
        }

        return db.delete(table, selection, selectionArgs);
    }

    @Override
    public int update(Uri uri, ContentValues values, String selection, String[] selectionArgs) {
        // The _data column is filled internally in MmsProvider, so this check is just to avoid
        // it from being inadvertently set. This is not supposed to be a protection against
        // malicious attack, since sql injection could still be attempted to bypass the check. On
        // the other hand, the MmsProvider does verify that the _data column has an allowed value
        // before opening any uri/files.
        if (values != null && values.containsKey(Part._DATA)) {
            return 0;
        }
        int match = sURLMatcher.match(uri);
        if (LOCAL_LOGV) {
            Log.v(TAG, "Update uri=" + uri + ", match=" + match);
        }

        boolean notify = false;
        String msgId = null;
        String table;

        switch (match) {
            case MMS_ALL_ID:
            case MMS_INBOX_ID:
            case MMS_SENT_ID:
            case MMS_DRAFTS_ID:
            case MMS_OUTBOX_ID:
                msgId = uri.getLastPathSegment();
            // fall-through
            case MMS_ALL:
            case MMS_INBOX:
            case MMS_SENT:
            case MMS_DRAFTS:
            case MMS_OUTBOX:
                notify = true;
                table = TABLE_PDU;
                break;

            case MMS_MSG_PART:
            case MMS_PART_ID:
                table = TABLE_PART;
                break;

            case MMS_PART_RESET_FILE_PERMISSION:
                String path = getContext().getDir("parts", 0).getPath() + '/' +
                        uri.getPathSegments().get(1);
                // Reset the file permission back to read for everyone but me.
                int result = FileUtils.setPermissions(path, 0644, -1, -1);
                if (LOCAL_LOGV) {
                    Log.d(TAG, "MmsProvider.update setPermissions result: " + result +
                            " for path: " + path);
                }
                return 0;

            default:
                Log.w(TAG, "Update operation for '" + uri + "' not implemented.");
                return 0;
        }

        String extraSelection = null;
        ContentValues finalValues;
        if (table.equals(TABLE_PDU)) {
            // Filter keys that we don't support yet.
            filterUnsupportedKeys(values);
            finalValues = new ContentValues(values);

            if (msgId != null) {
                extraSelection = Mms._ID + "=" + msgId;
            }
        } else if (table.equals(TABLE_PART)) {
            finalValues = new ContentValues(values);

            switch (match) {
                case MMS_MSG_PART:
                    extraSelection = Part.MSG_ID + "=" + uri.getPathSegments().get(0);
                    break;
                case MMS_PART_ID:
                    extraSelection = Part._ID + "=" + uri.getPathSegments().get(1);
                    break;
                default:
                    break;
            }
        } else {
            return 0;
        }

        String finalSelection = concatSelections(selection, extraSelection);
        SQLiteDatabase db = mOpenHelper.getWritableDatabase();
        int count = db.update(table, finalValues, finalSelection, selectionArgs);
        if (notify && (count > 0)) {
            notifyChange();
        }
        return count;
    }

    @Override
    public ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {
        int match = sURLMatcher.match(uri);

        if (Log.isLoggable(TAG, Log.VERBOSE)) {
            Log.d(TAG, "openFile: uri=" + uri + ", mode=" + mode + ", match=" + match);
        }

        if (match != MMS_PART_ID) {
            return null;
        }

        return safeOpenFileHelper(uri, mode);
    }

    private ParcelFileDescriptor safeOpenFileHelper(
             Uri uri, String mode) throws FileNotFoundException {
        Cursor c = query(uri, new String[]{"_data"}, null, null, null);
        int count = (c != null) ? c.getCount() : 0;
        if (count != 1) {
            // If there is not exactly one result, throw an appropriate
            // exception.
            if (c != null) {
                c.close();
            }
            if (count == 0) {
                throw new FileNotFoundException("No entry for " + uri);
            }
            throw new FileNotFoundException("Multiple items at " + uri);
        }

        c.moveToFirst();
        int i = c.getColumnIndex("_data");
        String path = (i >= 0 ? c.getString(i) : null);
        c.close();

        if (path == null) {
            throw new FileNotFoundException("Column _data not found.");
        }

        File filePath = new File(path);
        try {
            File appDataDirPath = new File(getContext().getApplicationInfo().dataDir+ "/app_parts/");
            // The MmsProvider shouldn't open a file that isn't MMS data, so we verify that the
            // _data path actually points to MMS data. That safeguards ourselves from callers who
            // inserted or updated a URI (more specifically the _data column) with disallowed paths.
            // TODO(afurtado): provide a more robust mechanism to avoid disallowed _data paths to
            // be inserted/updated in the first place, including via SQL injection.
            if (!filePath.getCanonicalPath()
                    .startsWith(appDataDirPath.getCanonicalPath())) {
                return null;
            }
        } catch (IOException e) {
            return null;
        }

        int modeBits = ParcelFileDescriptor.parseMode(mode);
        return ParcelFileDescriptor.open(filePath, modeBits);
    }

    private void filterUnsupportedKeys(ContentValues values) {
        // Some columns are unsupported.  They should therefore
        // neither be inserted nor updated.  Filter them out.
        values.remove(Mms.DELIVERY_TIME_TOKEN);
        values.remove(Mms.SENDER_VISIBILITY);
        values.remove(Mms.REPLY_CHARGING);
        values.remove(Mms.REPLY_CHARGING_DEADLINE_TOKEN);
        values.remove(Mms.REPLY_CHARGING_DEADLINE);
        values.remove(Mms.REPLY_CHARGING_ID);
        values.remove(Mms.REPLY_CHARGING_SIZE);
        values.remove(Mms.PREVIOUSLY_SENT_BY);
        values.remove(Mms.PREVIOUSLY_SENT_DATE);
        values.remove(Mms.STORE);
        values.remove(Mms.MM_STATE);
        values.remove(Mms.MM_FLAGS_TOKEN);
        values.remove(Mms.MM_FLAGS);
        values.remove(Mms.STORE_STATUS);
        values.remove(Mms.STORE_STATUS_TEXT);
        values.remove(Mms.STORED);
        values.remove(Mms.TOTALS);
        values.remove(Mms.MBOX_TOTALS);
        values.remove(Mms.MBOX_TOTALS_TOKEN);
        values.remove(Mms.QUOTAS);
        values.remove(Mms.MBOX_QUOTAS);
        values.remove(Mms.MBOX_QUOTAS_TOKEN);
        values.remove(Mms.MESSAGE_COUNT);
        values.remove(Mms.START);
        values.remove(Mms.DISTRIBUTION_INDICATOR);
        values.remove(Mms.ELEMENT_DESCRIPTOR);
        values.remove(Mms.LIMIT);
        values.remove(Mms.RECOMMENDED_RETRIEVAL_MODE);
        values.remove(Mms.RECOMMENDED_RETRIEVAL_MODE_TEXT);
        values.remove(Mms.STATUS_TEXT);
        values.remove(Mms.APPLIC_ID);
        values.remove(Mms.REPLY_APPLIC_ID);
        values.remove(Mms.AUX_APPLIC_ID);
        values.remove(Mms.DRM_CONTENT);
        values.remove(Mms.ADAPTATION_ALLOWED);
        values.remove(Mms.REPLACE_ID);
        values.remove(Mms.CANCEL_ID);
        values.remove(Mms.CANCEL_STATUS);

        // Keys shouldn't be inserted or updated.
        values.remove(Mms._ID);
    }

    private void notifyChange() {
        getContext().getContentResolver().notifyChange(
                MmsSms.CONTENT_URI, null);
    }

    private final static String TAG = "MmsProvider";
    private final static String VND_ANDROID_MMS = "vnd.android/mms";
    private final static String VND_ANDROID_DIR_MMS = "vnd.android-dir/mms";
    private final static boolean DEBUG = false;
    private final static boolean LOCAL_LOGV = false;

    private static final int MMS_ALL                      = 0;
    private static final int MMS_ALL_ID                   = 1;
    private static final int MMS_INBOX                    = 2;
    private static final int MMS_INBOX_ID                 = 3;
    private static final int MMS_SENT                     = 4;
    private static final int MMS_SENT_ID                  = 5;
    private static final int MMS_DRAFTS                   = 6;
    private static final int MMS_DRAFTS_ID                = 7;
    private static final int MMS_OUTBOX                   = 8;
    private static final int MMS_OUTBOX_ID                = 9;
    private static final int MMS_ALL_PART                 = 10;
    private static final int MMS_MSG_PART                 = 11;
    private static final int MMS_PART_ID                  = 12;
    private static final int MMS_MSG_ADDR                 = 13;
    private static final int MMS_SENDING_RATE             = 14;
    private static final int MMS_REPORT_STATUS            = 15;
    private static final int MMS_REPORT_REQUEST           = 16;
    private static final int MMS_DRM_STORAGE              = 17;
    private static final int MMS_DRM_STORAGE_ID           = 18;
    private static final int MMS_THREADS                  = 19;
    private static final int MMS_PART_RESET_FILE_PERMISSION = 20;
    private static final int MMS_GET_PDU = 21;

    private static final UriMatcher
            sURLMatcher = new UriMatcher(UriMatcher.NO_MATCH);

    static {
        sURLMatcher.addURI("mms", null,         MMS_ALL);
        sURLMatcher.addURI("mms", "#",          MMS_ALL_ID);
        sURLMatcher.addURI("mms", "inbox",      MMS_INBOX);
        sURLMatcher.addURI("mms", "inbox/#",    MMS_INBOX_ID);
        sURLMatcher.addURI("mms", "sent",       MMS_SENT);
        sURLMatcher.addURI("mms", "sent/#",     MMS_SENT_ID);
        sURLMatcher.addURI("mms", "drafts",     MMS_DRAFTS);
        sURLMatcher.addURI("mms", "drafts/#",   MMS_DRAFTS_ID);
        sURLMatcher.addURI("mms", "outbox",     MMS_OUTBOX);
        sURLMatcher.addURI("mms", "outbox/#",   MMS_OUTBOX_ID);
        sURLMatcher.addURI("mms", "part",       MMS_ALL_PART);
        sURLMatcher.addURI("mms", "#/part",     MMS_MSG_PART);
        sURLMatcher.addURI("mms", "part/#",     MMS_PART_ID);
        sURLMatcher.addURI("mms", "#/addr",     MMS_MSG_ADDR);
        sURLMatcher.addURI("mms", "rate",       MMS_SENDING_RATE);
        sURLMatcher.addURI("mms", "report-status/#",  MMS_REPORT_STATUS);
        sURLMatcher.addURI("mms", "report-request/#", MMS_REPORT_REQUEST);
        sURLMatcher.addURI("mms", "drm",        MMS_DRM_STORAGE);
        sURLMatcher.addURI("mms", "drm/#",      MMS_DRM_STORAGE_ID);
        sURLMatcher.addURI("mms", "threads",    MMS_THREADS);
        sURLMatcher.addURI("mms", "resetFilePerm/*",    MMS_PART_RESET_FILE_PERMISSION);
        sURLMatcher.addURI("mms", "get-pdu",    MMS_GET_PDU);
    }

    private SQLiteOpenHelper mOpenHelper;

    private static String concatSelections(String selection1, String selection2) {
        if (TextUtils.isEmpty(selection1)) {
            return selection2;
        } else if (TextUtils.isEmpty(selection2)) {
            return selection1;
        } else {
            return selection1 + " AND " + selection2;
        }
    }
}

