package br.com.droidboaoferta;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.net.URI;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.TreeSet;

/** Pure, deterministic union. Alert IDs are aliases, not the identity of a property. */
final class PropertyHistorySync {
    private PropertyHistorySync() { }

    static String identity(String url, String listingId) {
        String normalized = PropertyPageClient.normalizeListingUrl(url);
        if (normalized == null || listingId == null || listingId.isEmpty()) return "";
        URI uri = URI.create(normalized);
        String[] path = uri.getPath().split("/");
        // The ID must belong to this URL, not to a suggested property on the page.
        int idIndex = "loft.com.br".equals(uri.getHost()) ? path.length - 1 : 2;
        if (path.length < 3 || !listingId.equals(path[idIndex])) return "";
        return uri.getHost() + "|" + listingId;
    }

    static JSONArray merge(JSONArray local, JSONArray remote) throws JSONException {
        Map<String, List<JSONObject>> groups = new TreeMap<>();
        for (JSONArray source : new JSONArray[]{local, remote}) {
            for (int i = 0; source != null && i < source.length(); i++) {
                JSONObject item = source.optJSONObject(i);
                if (item == null) continue;
                String key = identity(item.optString("url"), item.optString("listing_id"));
                if (key.isEmpty()) continue;
                groups.computeIfAbsent(key, ignored -> new ArrayList<>()).add(item);
            }
        }
        JSONArray result = new JSONArray();
        for (List<JSONObject> items : groups.values()) {
            JSONObject merged = mergeListing(items);
            TreeSet<Long> aliases = new TreeSet<>();
            for (JSONObject item : items) {
                if (item.optLong("interest_id") > 0L) aliases.add(item.optLong("interest_id"));
            }
            for (long id : aliases) {
                JSONObject row = new JSONObject(merged.toString()).put("interest_id", id);
                result.put(row);
            }
        }
        return result;
    }

    private static JSONObject mergeListing(List<JSONObject> items) throws JSONException {
        items.sort(Comparator.comparingInt(PropertyHistorySync::trust)
                .thenComparingLong(item -> item.optLong("last_seen_at"))
                .thenComparing(PropertyHistorySync::canonical));
        JSONObject newest = items.get(items.size() - 1);
        JSONObject result = new JSONObject(newest.toString());
        boolean blockedLegacy = false;
        Map<String, JSONObject> backups = new TreeMap<>();
        Map<String, JSONObject> rejected = new TreeMap<>();
        for (JSONObject item : items) {
            blockedLegacy |= item.has("legacy_isolation_reason");
            JSONObject legacy = item.optJSONObject("legacy_unverified");
            if (legacy != null) backups.put(canonical(legacy), legacy);
            collectObjects(backups, item.optJSONArray("sync_legacy_backups"));
            JSONArray quarantined = item.optJSONArray("sync_quarantined_points");
            for (int i = 0; quarantined != null && i < quarantined.length(); i++) {
                JSONObject point = quarantined.optJSONObject(i);
                if (validPoint(point)) rejected.put(pointKey(point), cleanPoint(point));
            }
        }
        Map<String, JSONObject> points = new TreeMap<>();
        for (JSONObject item : items) {
            JSONArray values = item.optJSONArray("points");
            boolean legacySource = trust(item) == 0;
            // Do not repeat the broad migration that hid working histories. Only a recorded
            // isolation/unavailability reason disqualifies these previously visible readings.
            boolean acceptLegacy = !blockedLegacy;
            JSONArray old = item.optJSONObject("legacy_unverified") == null ? null
                    : item.optJSONObject("legacy_unverified").optJSONArray("points");
            for (int i = 0; values != null && i < values.length(); i++) {
                JSONObject point = values.optJSONObject(i);
                if (!validPoint(point)) continue;
                boolean restoredOld = (containsPoint(old, point) || point.optBoolean("sync_legacy"))
                        && !point.optBoolean("identity_verified");
                if ((legacySource && !acceptLegacy) || (blockedLegacy && restoredOld)) {
                    rejected.put(pointKey(point), point);
                    continue;
                }
                String key = pointKey(point);
                if (rejected.containsKey(key)) continue;
                point = cleanPoint(point);
                if (legacySource || restoredOld) point.put("sync_legacy", true);
                JSONObject previous = points.get(key);
                if (previous == null || point.optBoolean("identity_verified")) points.put(key, point);
            }
        }
        // Simultaneous conflicting readings are ambiguous, not a price drop.
        Map<Long, String> atTime = new TreeMap<>();
        TreeSet<Long> conflicts = new TreeSet<>();
        for (JSONObject point : points.values()) {
            long at = point.optLong("observed_at");
            String previous = atTime.put(at, pointKey(point));
            if (previous != null && !previous.equals(pointKey(point))) conflicts.add(at);
        }
        List<JSONObject> ordered = new ArrayList<>();
        for (JSONObject point : points.values()) {
            if (conflicts.contains(point.optLong("observed_at"))) rejected.put(pointKey(point), point);
            else ordered.add(point);
        }
        ordered.sort(Comparator.comparingLong(point -> point.optLong("observed_at")));
        JSONArray readings = new JSONArray();
        for (int i = Math.max(0, ordered.size() - 60); i < ordered.size(); i++) readings.put(ordered.get(i));
        result.put("points", readings);
        if (blockedLegacy) result.put("legacy_isolation_reason", "unavailable");
        if (!backups.isEmpty()) result.put("sync_legacy_backups", new JSONArray(backups.values()));
        if (!rejected.isEmpty()) result.put("sync_quarantined_points", new JSONArray(rejected.values()));
        for (String field : new String[]{"first_seen_at", "first_publication_at"}) {
            long earliest = 0L;
            for (JSONObject item : items) {
                if ("first_publication_at".equals(field) && blockedLegacy && trust(item) == 0) continue;
                long value = item.optLong(field);
                if (value > 0L && (earliest == 0L || value < earliest)) earliest = value;
            }
            if (earliest > 0L) result.put(field, earliest);
        }
        return result;
    }

    private static int trust(JSONObject item) {
        return !PropertyPageClient.isQuintoAndarListingUrl(item.optString("url"))
                || item.optInt("identity_validation_version") >= 1 ? 1 : 0;
    }

    private static boolean validPoint(JSONObject point) {
        return point != null && point.optLong("observed_at") > 0L
                && Double.isFinite(point.optDouble("price")) && point.optDouble("price") > 0d
                && Double.isFinite(point.optDouble("area")) && point.optDouble("area") > 0d;
    }

    private static String pointKey(JSONObject point) {
        return point.optLong("observed_at") + "|" + point.optDouble("price") + "|" + point.optDouble("area");
    }

    private static JSONObject cleanPoint(JSONObject point) throws JSONException {
        JSONObject result = new JSONObject().put("observed_at", point.optLong("observed_at"))
                .put("price", point.optDouble("price")).put("area", point.optDouble("area"));
        if (point.optBoolean("identity_verified")) result.put("identity_verified", true);
        if (point.optBoolean("sync_legacy")) result.put("sync_legacy", true);
        return result;
    }

    /** One copy per provider/listing; short numeric tuples before the existing gzip transport. */
    static JSONObject pack(JSONArray entries) throws JSONException {
        JSONArray normalized = merge(entries, new JSONArray());
        Map<String, JSONObject> rows = new TreeMap<>();
        for (int i = 0; i < normalized.length(); i++) {
            JSONObject item = normalized.getJSONObject(i);
            String key = identity(item.optString("url"), item.optString("listing_id"));
            JSONObject row = rows.get(key);
            if (row == null) {
                row = new JSONObject(item.toString());
                row.remove("interest_id");
                row.remove("metadata_attempted_at"); // Retry timing belongs only to this device.
                row.put("alerts", new JSONArray());
                compactPoints(row, "points");
                compactPoints(row, "sync_quarantined_points");
                rows.put(key, row);
            }
            row.getJSONArray("alerts").put(item.getLong("interest_id"));
        }
        return new JSONObject().put("v", 1).put("rows", new JSONArray(rows.values()));
    }

    static JSONArray unpack(Object payload) throws JSONException {
        if (payload instanceof JSONArray) return (JSONArray) payload; // First development format.
        JSONArray result = new JSONArray();
        if (!(payload instanceof JSONObject)) return result;
        JSONObject object = (JSONObject) payload;
        if (object.optInt("v") != 1) return result;
        JSONArray rows = object.optJSONArray("rows");
        for (int i = 0; rows != null && i < rows.length(); i++) {
            JSONObject row = rows.optJSONObject(i);
            if (row == null) continue;
            row = new JSONObject(row.toString());
            JSONArray aliases = row.optJSONArray("alerts");
            row.remove("alerts");
            expandPoints(row, "points");
            expandPoints(row, "sync_quarantined_points");
            for (int j = 0; aliases != null && j < aliases.length(); j++) {
                long id = aliases.optLong(j);
                if (id > 0L) result.put(new JSONObject(row.toString()).put("interest_id", id));
            }
        }
        return result;
    }

    private static void compactPoints(JSONObject row, String field) throws JSONException {
        JSONArray points = row.optJSONArray(field);
        if (points == null) return;
        JSONArray compact = new JSONArray();
        for (int i = 0; i < points.length(); i++) {
            JSONObject point = points.optJSONObject(i);
            if (!validPoint(point)) continue;
            int flags = (point.optBoolean("identity_verified") ? 1 : 0)
                    | (point.optBoolean("sync_legacy") ? 2 : 0);
            compact.put(new JSONArray().put(point.optLong("observed_at"))
                    .put(point.optDouble("price")).put(point.optDouble("area")).put(flags));
        }
        row.put(field, compact);
    }

    private static void expandPoints(JSONObject row, String field) throws JSONException {
        JSONArray compact = row.optJSONArray(field);
        if (compact == null) return;
        JSONArray points = new JSONArray();
        for (int i = 0; i < compact.length(); i++) {
            JSONArray tuple = compact.optJSONArray(i);
            if (tuple == null || tuple.length() != 4) continue;
            JSONObject point = new JSONObject().put("observed_at", tuple.optLong(0))
                    .put("price", tuple.optDouble(1)).put("area", tuple.optDouble(2));
            if ((tuple.optInt(3) & 1) != 0) point.put("identity_verified", true);
            if ((tuple.optInt(3) & 2) != 0) point.put("sync_legacy", true);
            if (validPoint(point)) points.put(point);
        }
        row.put(field, points);
    }

    private static boolean containsPoint(JSONArray values, JSONObject point) {
        for (int i = 0; values != null && i < values.length(); i++) {
            JSONObject other = values.optJSONObject(i);
            if (other != null && pointKey(point).equals(pointKey(other))) return true;
        }
        return false;
    }

    private static void collectObjects(Map<String, JSONObject> target, JSONArray values) {
        for (int i = 0; values != null && i < values.length(); i++) {
            JSONObject value = values.optJSONObject(i);
            if (value != null) target.put(canonical(value), value);
        }
    }

    static String canonical(Object value) {
        if (value instanceof JSONObject) {
            JSONObject object = (JSONObject) value;
            TreeMap<String, String> fields = new TreeMap<>();
            object.keys().forEachRemaining(key -> fields.put(key, canonical(object.opt(key))));
            return fields.toString();
        }
        if (value instanceof JSONArray) {
            JSONArray array = (JSONArray) value;
            List<String> values = new ArrayList<>();
            for (int i = 0; i < array.length(); i++) values.add(canonical(array.opt(i)));
            return values.toString();
        }
        return value instanceof String ? JSONObject.quote((String) value) : String.valueOf(value);
    }

    static String changeKey(JSONObject item) throws JSONException {
        JSONObject copy = new JSONObject(item.toString());
        copy.remove("last_seen_at");
        copy.remove("metadata_attempted_at");
        return canonical(copy);
    }

    static String contentKey(JSONArray entries) throws JSONException {
        List<String> keys = new ArrayList<>();
        for (int i = 0; entries != null && i < entries.length(); i++) {
            JSONObject item = entries.optJSONObject(i);
            if (item != null) keys.add(changeKey(item));
        }
        java.util.Collections.sort(keys);
        return keys.toString();
    }
}
