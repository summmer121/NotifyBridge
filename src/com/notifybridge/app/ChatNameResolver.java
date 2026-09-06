package com.notifybridge.app;

import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Resolve WeChat group id (xxx@chatroom) to display name via the WCDB chatroom
 * table, and contact wxid to nickname via the rcontact table. Never crashes:
 * falls back to the raw id on any error.
 */
public final class ChatNameResolver {

    private static final String COL_CHATROOM_ID = "chatroomname";
    private static final String[] PREFERRED_NAME_COLUMNS =
            {"chatroomnick", "displayname", "chatroomName"};

    private static final Map<String, String> GROUP_CACHE = new ConcurrentHashMap<String, String>();
    private static final Map<String, String> CONTACT_CACHE = new ConcurrentHashMap<String, String>();

    private static volatile String lastGroupInfo = "";
    private static volatile String lastRoomdata = "";
    private static volatile Object lastDb;

    private ChatNameResolver() {
    }

    public static String resolve(Object sqliteDb, String talker) {
        if (talker == null || talker.isEmpty()) {
            return "";
        }
        if (!talker.endsWith("@chatroom")) {
            return resolveContact(sqliteDb, talker, talker);
        }
        String name = queryGroupName(sqliteDb, talker);
        if (name == null || name.isEmpty()) {
            name = GROUP_CACHE.get(talker);
        }
        if (name == null || name.isEmpty()) {
            name = talker;
        } else if (!talker.equals(name)) {
            GROUP_CACHE.put(talker, name);
        }
        return name;
    }

    public static String lastGroupInfo() {
        return lastGroupInfo;
    }

    /** 鏈€杩戜竴娆℃垚鍔熸暟鎹簱鏌ヨ鎵€浣跨敤鐨?WCDB 杩炴帴锛屼緵鑷绂荤嚎澶嶇敤銆?*/
    public static Object lastDb() {
        return lastDb;
    }

    public static void rememberDb(Object db) {
        if (db != null) {
            lastDb = db;
        }
    }

    public static String resolveContact(Object sqliteDb, String wxid, String fallback) {
        if (wxid == null || wxid.isEmpty()) {
            return fallback == null ? "" : fallback;
        }
        String hit = CONTACT_CACHE.get(wxid);
        if (hit != null) {
            return hit;
        }
        String nick = queryContact(sqliteDb, wxid);
        if (nick == null || nick.isEmpty()) {
            nick = fallback == null ? wxid : fallback;
        }
        CONTACT_CACHE.put(wxid, nick);
        return nick;
    }

    private static String queryContact(Object sqliteDb, String wxid) {
        if (sqliteDb == null) {
            return null;
        }
        lastDb = sqliteDb;
        Object cursor = null;
        try {
            cursor = XposedHelpers.callMethod(sqliteDb, "rawQuery",
                    "SELECT * FROM rcontact WHERE username=? LIMIT 1",
                    new String[]{wxid});
            String[] columns = (String[]) XposedHelpers.callMethod(cursor, "getColumnNames");
            if (Boolean.TRUE.equals(XposedHelpers.callMethod(cursor, "moveToFirst"))) {
                String nick = pickColumn(cursor, columns, "nickname", "conRemark", "alias");
                if (nick == null || nick.isEmpty()) {
                    nick = pickColumn(cursor, columns, "pyInitial", "remark", "conRemarkPY");
                }
                return nick;
            }
            return "";
        } catch (Throwable t) {
            log("queryContact failed wxid=" + wxid + " err=" + t);
            dumpContact(sqliteDb);
            return null;
        } finally {
            try {
                if (cursor != null) {
                    XposedHelpers.callMethod(cursor, "close");
                }
            } catch (Throwable ignore) {
            }
        }
    }

    private static void dumpContact(Object sqliteDb) {
        try {
            Object c = XposedHelpers.callMethod(sqliteDb, "rawQuery",
                    "SELECT * FROM rcontact WHERE username LIKE 'wxid_%' LIMIT 3", null);
            String[] columns = (String[]) XposedHelpers.callMethod(c, "getColumnNames");
            StringBuilder cols = new StringBuilder();
            if (columns != null) {
                for (String col : columns) {
                    cols.append(col).append(',');
                }
            }
            log("rcontact columns=" + cols);
            while (Boolean.TRUE.equals(XposedHelpers.callMethod(c, "moveToNext"))) {
                StringBuilder sb = new StringBuilder();
                if (columns != null) {
                    String[] want = new String[]{
                            "username", "nickname", "conRemark", "alias",
                            "remark", "pyInitial"};
                    for (String col : want) {
                        if (contains(columns, col)) {
                            int idx = (Integer) XposedHelpers.callMethod(c, "getColumnIndex", col);
                            sb.append(col).append('=').append(strVal(c, idx)).append('|');
                        }
                    }
                }
                log("rcontact sample => " + sb);
            }
            XposedHelpers.callMethod(c, "close");
        } catch (Throwable t) {
            log("dumpContact failed err=" + t);
        }
    }

    /**
     * Query the group's rcontact record specifically. WeChat stores a user's
     * own group remark in conRemark, and the plain nickname can be the group
     * member list or the owner's name, so we must prefer conRemark here.
     */
    private static String queryGroupContactName(Object sqliteDb, String talker) {
        if (sqliteDb == null) {
            return null;
        }
        lastDb = sqliteDb;
        Object cursor = null;
        try {
            cursor = XposedHelpers.callMethod(sqliteDb, "rawQuery",
                    "SELECT * FROM rcontact WHERE username=? LIMIT 1",
                    new String[]{talker});
            String[] columns = (String[]) XposedHelpers.callMethod(cursor, "getColumnNames");
            if (Boolean.TRUE.equals(XposedHelpers.callMethod(cursor, "moveToFirst"))) {
                String nick = pickColumn(cursor, columns, "conRemark", "nickname", "alias");
                if (nick == null || nick.isEmpty()) {
                    nick = pickColumn(cursor, columns, "remark", "conRemarkPY", "pyInitial");
                }
                return nick;
            }
            return "";
        } catch (Throwable t) {
            log("queryGroupContactName failed talker=" + talker + " err=" + t);
            return null;
        } finally {
            try {
                if (cursor != null) {
                    XposedHelpers.callMethod(cursor, "close");
                }
            } catch (Throwable ignore) {
            }
        }
    }

    private static String queryGroupName(Object sqliteDb, String talker) {
        if (sqliteDb == null) {
            return null;
        }
        lastDb = sqliteDb;
        Object cursor = null;
        try {
            cursor = XposedHelpers.callMethod(sqliteDb, "rawQuery",
                    "SELECT * FROM chatroom WHERE " + COL_CHATROOM_ID + "=?",
                    new String[]{talker});
            String[] columns = (String[]) XposedHelpers.callMethod(cursor, "getColumnNames");
            StringBuilder info = new StringBuilder();
            if (Boolean.TRUE.equals(XposedHelpers.callMethod(cursor, "moveToFirst"))) {
                for (String preferred : PREFERRED_NAME_COLUMNS) {
                    if (contains(columns, preferred)) {
                        int idx = (Integer) XposedHelpers.callMethod(cursor, "getColumnIndex", preferred);
                        String v = strVal(cursor, idx);
                        info.append(preferred).append('=').append(v).append('|');
                    }
                }

                String roomName = dumpRoomdata(cursor, columns);
                lastGroupInfo = "row=yes|" + info + "roomdataName=" + roomName
                        + "|roomdata=" + lastRoomdata;
                log("candidates [" + talker + "] => " + lastGroupInfo);
                if (roomName != null && !roomName.isEmpty()) {
                    return roomName;
                }
                for (String preferred : PREFERRED_NAME_COLUMNS) {
                    if (contains(columns, preferred)) {
                        int idx = (Integer) XposedHelpers.callMethod(cursor, "getColumnIndex", preferred);
                        String v = strVal(cursor, idx);
                        if (looksLikeName(v)) {
                            return v;
                        }
                    }
                }
                log("no usable name for [" + talker + "]");
            } else {
                lastGroupInfo = "row=NO_ROW";
                log("no chatroom row for [" + talker + "]");
            }
            // 微信把群当成一种联系人：用户给群设置的群名/备注存在 rcontact 表的 conRemark 里。
            String contactName = queryGroupContactName(sqliteDb, talker);
            if (contactName != null && !contactName.isEmpty()
                    && !talker.equals(contactName)) {
                lastGroupInfo += "|contactName=" + contactName;
                log("fallback contactName for [" + talker + "] => " + contactName);
                return contactName;
            }
            return null;
        } catch (Throwable t) {
            lastGroupInfo = "row=ERR:" + t;
            log("queryGroupName failed talker=" + talker + " err=" + t);
            return null;
        } finally {
            try {
                if (cursor != null) {
                    XposedHelpers.callMethod(cursor, "close");
                }
            } catch (Throwable ignore) {
            }
        }
    }

    /**
     * WeChat stores richer group profile data (including the real group name
     * set by the owner) inside the roomdata BLOB as XML. Try to decode it and
     * pull out a short name.
     */
    private static String dumpRoomdata(Object cursor, String[] columns) {
        try {
            if (!contains(columns, "roomdata")) {
                return "";
            }
            int idx = (Integer) XposedHelpers.callMethod(cursor, "getColumnIndex", "roomdata");
            Object blob = XposedHelpers.callMethod(cursor, "getBlob", idx);
            if (!(blob instanceof byte[]) || ((byte[]) blob).length == 0) {
                return "";
            }
            byte[] bytes = (byte[]) blob;
            String xml = new String(bytes, "UTF-8");
            lastRoomdata = xml.length() > 600 ? xml.substring(0, 600) : xml;
            log("roomdata(length=" + bytes.length + ")");
            String n = extractTag(xml, "roomname");
            if (n == null || n.isEmpty()) {
                n = extractTag(xml, "roomName");
            }
            if (n == null || n.isEmpty()) {
                n = extractAttr(xml, "roomname");
            }
            if (n != null && !n.isEmpty()) {
                log("roomdata.roomname=" + n);
                return n;
            }
        } catch (Throwable t) {
            log("dumpRoomdata failed err=" + t);
        }
        return "";
    }

    private static String extractAttr(String xml, String attr) {
        try {
            String key = attr + "=\"";
            int s = xml.indexOf(key);
            if (s < 0) {
                key = attr + "='";
                s = xml.indexOf(key);
            }
            if (s < 0) {
                return "";
            }
            int e = xml.indexOf('"', s + key.length());
            if (e < 0) {
                e = xml.indexOf('\'', s + key.length());
            }
            if (e < 0) {
                return "";
            }
            return xml.substring(s + key.length(), e).trim();
        } catch (Throwable t) {
            return "";
        }
    }

    private static String extractTag(String xml, String tag) {
        try {
            String open = "<" + tag + ">";
            String close = "</" + tag + ">";
            int s = xml.indexOf(open);
            if (s < 0) {
                return "";
            }
            int e = xml.indexOf(close, s);
            if (e < 0) {
                return "";
            }
            String v = xml.substring(s + open.length(), e).trim();
            return v.replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">");
        } catch (Throwable t) {
            return "";
        }
    }

    private static boolean looksLikeName(String v) {
        if (v == null) {
            return false;
        }
        String s = v.trim();
        if (s.isEmpty() || s.startsWith("[BLOB") || s.startsWith("[typeErr")
                || s.equals("NULL")) {
            return false;
        }
        if (s.contains("@chatroom")) {
            return false;
        }
        int seps = countSeparators(s);
        return seps < 2;
    }

    private static int countSeparators(String s) {
        int n = 0;
        for (int i = 0; i < s.length(); i++) {
            char ch = s.charAt(i);
            if (ch == '\uFF0C' || ch == ',' || ch == '\uFF1B' || ch == '\u3001' || ch == '~'
                    || ch == '-' || ch == ' ') {
                n++;
            }
        }
        return n;
    }

    private static String pickColumn(Object cursor, String[] columns,
                                     String first, String second, String third) {
        String v = safeColumn(cursor, columns, first);
        if (v != null && !v.isEmpty()) {
            return v;
        }
        v = safeColumn(cursor, columns, second);
        if (v != null && !v.isEmpty()) {
            return v;
        }
        return safeColumn(cursor, columns, third);
    }

    private static String safeColumn(Object cursor, String[] columns, String col) {
        if (columns == null || !contains(columns, col)) {
            return null;
        }
        int idx = (Integer) XposedHelpers.callMethod(cursor, "getColumnIndex", col);
        return strVal(cursor, idx);
    }

    private static String strVal(Object cursor, int idx) {
        try {
            int type = (Integer) XposedHelpers.callMethod(cursor, "getColumnType", idx);
            if (type == 4) { // BLOB
                return "[BLOB]";
            }
            Object v = XposedHelpers.callMethod(cursor, "getString", idx);
            return v == null ? "" : String.valueOf(v);
        } catch (Throwable t) {
            try {
                Object v = XposedHelpers.callMethod(cursor, "getString", idx);
                return v == null ? "" : String.valueOf(v);
            } catch (Throwable t2) {
                return "[typeErr]";
            }
        }
    }

    private static boolean contains(String[] arr, String s) {
        if (arr == null) {
            return false;
        }
        for (String a : arr) {
            if (s.equals(a)) {
                return true;
            }
        }
        return false;
    }

    private static void log(String msg) {
        try {
            XposedBridge.log("[NotifyX][ChatName] " + msg);
        } catch (Throwable ignored) {
        }
    }
}
